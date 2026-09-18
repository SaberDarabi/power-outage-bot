package com.usautovpn.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.core.ConnectionStatus;
import de.blinkt.openvpn.core.IOpenVPNServiceInternal;
import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VPNLaunchHelper;
import de.blinkt.openvpn.core.VpnStatus;

public class AutoVpnService extends Service implements VpnStatus.StateListener {
    public static final String ACTION_START = "com.usautovpn.app.START";
    public static final String ACTION_STOP = "com.usautovpn.app.STOP";

    public static final String PREFS = "usa_auto_vpn";
    public static final String KEY_DESIRED = "desired";
    public static final String KEY_STATE = "state";
    private static final String KEY_CACHE_AT = "cache_at";
    private static final String KEY_CACHE_PREFIX = "cache_";
    private static final String KEY_LAST_GOOD = "last_good";
    private static final String KEY_LAST_GOOD_AT = "last_good_at";

    public static final String STATE_OFF = "OFF";
    public static final String STATE_CONNECTING = "CONNECTING";
    public static final String STATE_CONNECTED = "CONNECTED";

    private static final String CHANNEL_ID = "usa_auto_vpn";
    private static final int NOTIFICATION_ID = 8801;
    private static final String VPN_GATE_API = "https://www.vpngate.net/api/iphone/";

    private static final long LAST_GOOD_MAX_AGE_MS = 60L * 60L * 1000L;
    private static final long POOL_CACHE_MAX_AGE_MS = 20L * 60L * 1000L;
    private static final long BLACKLIST_MS = 3L * 60L * 1000L;
    private static final long CONNECT_DEADLINE_MS = 6500L;
    private static final long REPLACE_GRACE_MS = 1400L;
    private static final long HEALTH_INTERVAL_MS = 2000L;
    private static final long HEALTH_OVERALL_DEADLINE_MS = 1500L;

    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService io = Executors.newFixedThreadPool(8);
    private final ExecutorService healthPool = Executors.newFixedThreadPool(2);
    private final ExecutorService probePool = Executors.newFixedThreadPool(8);
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
    private final AtomicBoolean healthInFlight = new AtomicBoolean(false);

    private final List<Server> candidates = new ArrayList<>();
    private final Map<String, Long> blacklistUntil = new HashMap<>();

    private SharedPreferences prefs;
    private IOpenVPNServiceInternal openVpnBinder;
    private boolean openVpnBound = false;
    private boolean desired = false;
    private boolean connected = false;
    private boolean connecting = false;
    private int nextIndex = 0;
    private long generation = 0L;
    private long replaceGraceUntil = 0L;
    private Server currentServer;

    private final ServiceConnection openVpnConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            openVpnBinder = IOpenVPNServiceInternal.Stub.asInterface(service);
            openVpnBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            openVpnBinder = null;
            openVpnBound = false;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Ready"));
        VpnStatus.addStateListener(this);
        bindOpenVpnService();

        control.scheduleWithFixedDelay(
                this::scheduleHealthCheckIfNeeded,
                HEALTH_INTERVAL_MS,
                HEALTH_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            control.execute(this::turnOff);
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action) || (action == null && prefs.getBoolean(KEY_DESIRED, false))) {
            control.execute(this::turnOn);
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        VpnStatus.removeStateListener(this);
        if (openVpnBound) {
            try {
                unbindService(openVpnConnection);
            } catch (Exception ignored) {
            }
        }
        control.shutdownNow();
        io.shutdownNow();
        healthPool.shutdownNow();
        probePool.shutdownNow();
        super.onDestroy();
    }

    private void turnOn() {
        if (desired && (connected || connecting)) {
            refreshServersAsync(false);
            return;
        }

        if (VpnService.prepare(this) != null) {
            desired = false;
            persistState(false, STATE_OFF);
            updateNotification("VPN permission required");
            stopSelf();
            return;
        }

        desired = true;
        connected = false;
        connecting = false;
        generation++;
        persistState(true, STATE_CONNECTING);
        updateNotification("Selecting the fastest US server");

        candidates.clear();
        nextIndex = 0;
        loadCachedCandidates();

        if (!candidates.isEmpty()) {
            connectNext("cached");
            refreshServersAsync(false);
        } else {
            refreshServersAsync(true);
        }
    }

    private void turnOff() {
        desired = false;
        connected = false;
        connecting = false;
        generation++;
        currentServer = null;
        candidates.clear();
        blacklistUntil.clear();
        persistState(false, STATE_OFF);
        updateNotification("Off");

        ProfileManager.setConntectedVpnProfileDisconnected(this);
        if (openVpnBinder != null) {
            try {
                openVpnBinder.stopVPN(false);
            } catch (RemoteException ignored) {
            }
        } else {
            stopService(new Intent(this, OpenVPNService.class));
        }

        stopForeground(true);
        stopSelf();
    }

    private void bindOpenVpnService() {
        try {
            Intent intent = new Intent(this, OpenVPNService.class);
            intent.setAction(OpenVPNService.START_SERVICE);
            bindService(intent, openVpnConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception ignored) {
        }
    }

    private void refreshServersAsync(boolean connectAfterRefresh) {
        if (!desired || !refreshInFlight.compareAndSet(false, true)) {
            return;
        }

        io.execute(() -> {
            List<Server> fresh;
            try {
                fresh = fetchAndRankUsServers();
            } catch (Exception e) {
                fresh = Collections.emptyList();
            }

            final List<Server> result = fresh;
            control.execute(() -> {
                refreshInFlight.set(false);
                if (!desired) {
                    return;
                }

                if (!result.isEmpty()) {
                    Server active = currentServer;
                    candidates.clear();
                    if (active != null && !containsServer(result, active.key())) {
                        candidates.add(active);
                    }
                    candidates.addAll(result);
                    nextIndex = 0;
                    savePoolCache(result);
                }

                if ((connectAfterRefresh || (!connected && !connecting)) && !candidates.isEmpty()) {
                    connectNext("fresh");
                } else if (!connected && !connecting && candidates.isEmpty()) {
                    updateNotification("No US server available; retrying");
                    control.schedule(() -> refreshServersAsync(true), 1500, TimeUnit.MILLISECONDS);
                }
            });
        });
    }

    private List<Server> fetchAndRankUsServers() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(VPN_GATE_API).openConnection();
        conn.setConnectTimeout(3500);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("User-Agent", "USA-Auto-VPN/1.0");
        conn.setRequestProperty("Accept", "text/plain,text/csv,*/*");
        conn.setUseCaches(false);

        List<Server> us = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Server server = parseCsvServer(line);
                if (server != null && "US".equalsIgnoreCase(server.countryShort)) {
                    us.add(server);
                }
            }
        } finally {
            conn.disconnect();
        }

        us.sort((a, b) -> {
            int byScore = Long.compare(b.score, a.score);
            if (byScore != 0) return byScore;
            int byPing = Integer.compare(normalizedPing(a.ping), normalizedPing(b.ping));
            if (byPing != 0) return byPing;
            return Long.compare(b.speed, a.speed);
        });

        if (us.size() > 24) {
            us = new ArrayList<>(us.subList(0, 24));
        }

        List<Future<?>> probes = new ArrayList<>();
        for (Server server : us) {
            if (server.tcp) {
                probes.add(probePool.submit(() -> probeTcp(server)));
            }
        }
        for (Future<?> future : probes) {
            try {
                future.get(900, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                future.cancel(true);
            }
        }

        us.removeIf(server -> server.tcp && server.liveLatencyMs == -2);
        us.sort(Comparator
                .comparingInt((Server s) -> s.liveLatencyMs >= 0 ? s.liveLatencyMs : normalizedPing(s.ping))
                .thenComparing((Server a, Server b) -> Long.compare(b.speed, a.speed))
                .thenComparing((Server a, Server b) -> Long.compare(b.score, a.score)));

        if (us.size() > 12) {
            return new ArrayList<>(us.subList(0, 12));
        }
        return us;
    }

    private void probeTcp(Server server) {
        long started = SystemClock.elapsedRealtime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(server.host, server.port), 650);
            server.liveLatencyMs = (int) Math.max(1L, SystemClock.elapsedRealtime() - started);
        } catch (Exception e) {
            server.liveLatencyMs = -2;
        }
    }

    private void connectNext(String reason) {
        if (!desired) {
            return;
        }

        expireBlacklist();

        Server selected = null;
        int scanned = 0;
        while (!candidates.isEmpty() && scanned < candidates.size()) {
            if (nextIndex >= candidates.size()) {
                nextIndex = 0;
            }
            Server candidate = candidates.get(nextIndex++);
            scanned++;
            Long until = blacklistUntil.get(candidate.key());
            if (until == null || until <= SystemClock.elapsedRealtime()) {
                selected = candidate;
                break;
            }
        }

        if (selected == null) {
            connecting = false;
            connected = false;
            updateUiState(STATE_CONNECTING);
            updateNotification("Refreshing US servers");
            refreshServersAsync(true);
            return;
        }

        currentServer = selected;
        connected = false;
        connecting = true;
        final long thisGeneration = ++generation;
        replaceGraceUntil = SystemClock.elapsedRealtime() + REPLACE_GRACE_MS;
        updateUiState(STATE_CONNECTING);
        updateNotification("Connecting to US");

        try {
            byte[] raw = Base64.decode(selected.configBase64, Base64.DEFAULT);
            String config = new String(raw, StandardCharsets.UTF_8);
            String tuned = tuneConfigForFastFailover(config);

            ConfigParser parser = new ConfigParser();
            parser.parseConfig(new InputStreamReader(
                    new ByteArrayInputStream(tuned.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8
            ));
            VpnProfile profile = parser.convertProfile();
            profile.mName = "USA Auto VPN";
            ProfileManager.setTemporaryProfile(getApplicationContext(), profile);

            VPNLaunchHelper.startOpenVpn(
                    profile,
                    getApplicationContext(),
                    "Automatic US server selection: " + reason,
                    true
            );

            control.schedule(() -> {
                if (!desired || generation != thisGeneration || connected) {
                    return;
                }
                blacklistCurrent();
                connecting = false;
                connectNext("connect-timeout");
            }, CONNECT_DEADLINE_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            blacklistCurrent();
            connecting = false;
            control.schedule(() -> connectNext("profile-error"), 80, TimeUnit.MILLISECONDS);
        }
    }

    private String tuneConfigForFastFailover(String config) {
        StringBuilder out = new StringBuilder();
        for (String line : config.split("\\r?\\n")) {
            String trimmed = line.trim().toLowerCase(Locale.US);
            if (trimmed.startsWith("ping ")
                    || trimmed.startsWith("ping-restart ")
                    || trimmed.startsWith("connect-timeout ")
                    || trimmed.startsWith("connect-retry ")
                    || trimmed.startsWith("connect-retry-max ")
                    || trimmed.startsWith("resolv-retry ")) {
                continue;
            }
            out.append(line).append('\n');
        }

        out.append("connect-timeout 2\n");
        out.append("connect-retry 1 2\n");
        out.append("connect-retry-max 1\n");
        out.append("resolv-retry 2\n");
        out.append("ping 1\n");
        out.append("ping-restart 4\n");
        out.append("auth-nocache\n");
        return out.toString();
    }

    private void failCurrent(String reason) {
        if (!desired) {
            return;
        }
        blacklistCurrent();
        connected = false;
        connecting = false;
        updateUiState(STATE_CONNECTING);
        connectNext(reason);
    }

    private void blacklistCurrent() {
        if (currentServer != null) {
            blacklistUntil.put(currentServer.key(), SystemClock.elapsedRealtime() + BLACKLIST_MS);
        }
    }

    private void expireBlacklist() {
        long now = SystemClock.elapsedRealtime();
        blacklistUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private void scheduleHealthCheckIfNeeded() {
        if (!desired || !connected || currentServer == null || !hasUsableUnderlyingNetwork()) {
            return;
        }
        if (!healthInFlight.compareAndSet(false, true)) {
            return;
        }

        final long checkGeneration = generation;
        io.execute(() -> {
            boolean healthy = checkTunnelHealth();
            control.execute(() -> {
                healthInFlight.set(false);
                if (!desired || !connected || generation != checkGeneration) {
                    return;
                }
                if (!healthy && hasUsableUnderlyingNetwork()) {
                    failCurrent("health-check");
                }
            });
        });
    }

    private boolean checkTunnelHealth() {
        Future<Boolean> first = healthPool.submit(() -> checkUrl("https://www.gstatic.com/generate_204"));
        Future<Boolean> second = healthPool.submit(() -> checkUrl("https://www.cloudflare.com/cdn-cgi/trace"));

        long deadline = SystemClock.elapsedRealtime() + HEALTH_OVERALL_DEADLINE_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                if (first.isDone() && Boolean.TRUE.equals(first.get())) {
                    second.cancel(true);
                    return true;
                }
                if (second.isDone() && Boolean.TRUE.equals(second.get())) {
                    first.cancel(true);
                    return true;
                }
                if (first.isDone() && second.isDone()) {
                    return false;
                }
                Thread.sleep(20);
            } catch (Exception ignored) {
            }
        }

        first.cancel(true);
        second.cancel(true);
        return false;
    }

    private boolean checkUrl(String urlString) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Connection", "close");
            conn.setUseCaches(false);
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean hasUsableUnderlyingNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void loadCachedCandidates() {
        long now = System.currentTimeMillis();
        Set<String> keys = new HashSet<>();

        long lastGoodAt = prefs.getLong(KEY_LAST_GOOD_AT, 0L);
        String lastGood = prefs.getString(KEY_LAST_GOOD, null);
        if (lastGood != null && now - lastGoodAt <= LAST_GOOD_MAX_AGE_MS) {
            Server s = Server.fromCachedConfig(lastGood);
            if (s != null && keys.add(s.key())) {
                candidates.add(s);
            }
        }

        long cacheAt = prefs.getLong(KEY_CACHE_AT, 0L);
        if (now - cacheAt <= POOL_CACHE_MAX_AGE_MS) {
            for (int i = 0; i < 6; i++) {
                String encoded = prefs.getString(KEY_CACHE_PREFIX + i, null);
                if (encoded == null) continue;
                Server s = Server.fromCachedConfig(encoded);
                if (s != null && keys.add(s.key())) {
                    candidates.add(s);
                }
            }
        }
    }

    private void savePoolCache(List<Server> servers) {
        SharedPreferences.Editor editor = prefs.edit().putLong(KEY_CACHE_AT, System.currentTimeMillis());
        for (int i = 0; i < 6; i++) {
            if (i < servers.size()) {
                editor.putString(KEY_CACHE_PREFIX + i, servers.get(i).configBase64);
            } else {
                editor.remove(KEY_CACHE_PREFIX + i);
            }
        }
        editor.apply();
    }

    private void saveLastGood() {
        if (currentServer == null) return;
        prefs.edit()
                .putString(KEY_LAST_GOOD, currentServer.configBase64)
                .putLong(KEY_LAST_GOOD_AT, System.currentTimeMillis())
                .apply();
    }

    private boolean containsServer(List<Server> list, String key) {
        for (Server s : list) {
            if (s.key().equals(key)) return true;
        }
        return false;
    }

    private void persistState(boolean desiredValue, String state) {
        prefs.edit()
                .putBoolean(KEY_DESIRED, desiredValue)
                .putString(KEY_STATE, state)
                .apply();
    }

    private void updateUiState(String state) {
        prefs.edit().putString(KEY_STATE, state).apply();
    }

    @Override
    public void updateState(String state, String logmessage, int localizedResId,
                            ConnectionStatus level, Intent intent) {
        control.execute(() -> {
            if (!desired) {
                return;
            }

            if (level == ConnectionStatus.LEVEL_CONNECTED || "CONNECTED".equals(state)) {
                connected = true;
                connecting = false;
                updateUiState(STATE_CONNECTED);
                updateNotification("Connected — US");
                saveLastGood();
                return;
            }

            if (level == ConnectionStatus.LEVEL_AUTH_FAILED) {
                failCurrent("authentication");
                return;
            }

            if (level == ConnectionStatus.LEVEL_NOTCONNECTED) {
                if (SystemClock.elapsedRealtime() < replaceGraceUntil) {
                    return;
                }
                if (connected || connecting) {
                    failCurrent("disconnected");
                }
            }
        });
    }

    @Override
    public void setConnectedVPN(String uuid) {
        // State transitions are handled in updateState().
    }

    private Server parseCsvServer(String line) {
        if (line == null || line.isEmpty() || line.charAt(0) == '*' || line.charAt(0) == '#') {
            return null;
        }

        List<String> firstFields = new ArrayList<>(13);
        int start = 0;
        for (int i = 0; i < 13; i++) {
            int comma = line.indexOf(',', start);
            if (comma < 0) return null;
            firstFields.add(line.substring(start, comma));
            start = comma + 1;
        }

        int lastComma = line.lastIndexOf(',');
        if (lastComma <= start || firstFields.size() < 13) {
            return null;
        }

        String countryShort = firstFields.get(6).trim();
        if (!"US".equalsIgnoreCase(countryShort)) {
            return null;
        }

        String configBase64 = line.substring(lastComma + 1).trim();
        if (configBase64.length() < 64) {
            return null;
        }

        try {
            Server server = Server.fromConfig(
                    firstFields.get(0).trim(),
                    firstFields.get(1).trim(),
                    parseLong(firstFields.get(2)),
                    parseInt(firstFields.get(3)),
                    parseLong(firstFields.get(4)),
                    countryShort,
                    configBase64
            );
            return server;
        } catch (Exception e) {
            return null;
        }
    }

    private static int normalizedPing(int ping) {
        return ping > 0 ? ping : 999;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "USA Auto VPN",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Automatic US VPN connection and failover");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("USA Auto VPN")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private static final class Server {
        final String hostName;
        final String ip;
        final long score;
        final int ping;
        final long speed;
        final String countryShort;
        final String configBase64;
        final String host;
        final int port;
        final boolean tcp;
        volatile int liveLatencyMs = -1;

        private Server(String hostName, String ip, long score, int ping, long speed,
                       String countryShort, String configBase64, String host, int port, boolean tcp) {
            this.hostName = hostName;
            this.ip = ip;
            this.score = score;
            this.ping = ping;
            this.speed = speed;
            this.countryShort = countryShort;
            this.configBase64 = configBase64;
            this.host = host;
            this.port = port;
            this.tcp = tcp;
        }

        static Server fromConfig(String hostName, String ip, long score, int ping, long speed,
                                 String countryShort, String configBase64) {
            byte[] raw = Base64.decode(configBase64, Base64.DEFAULT);
            String config = new String(raw, StandardCharsets.UTF_8);

            String remoteHost = ip;
            int remotePort = 1194;
            boolean isTcp = false;

            for (String rawLine : config.split("\\r?\\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;

                String lower = line.toLowerCase(Locale.US);
                if (lower.startsWith("proto ")) {
                    isTcp = lower.contains("tcp");
                } else if (lower.startsWith("remote ")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) remoteHost = parts[1];
                    if (parts.length >= 3) {
                        try {
                            remotePort = Integer.parseInt(parts[2]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }

            if (remoteHost == null || remoteHost.isEmpty()) {
                return null;
            }

            return new Server(
                    hostName,
                    ip,
                    score,
                    ping,
                    speed,
                    countryShort,
                    configBase64,
                    remoteHost,
                    remotePort,
                    isTcp
            );
        }

        static Server fromCachedConfig(String configBase64) {
            try {
                return fromConfig("cached", "", 0L, 0, 0L, "US", configBase64);
            } catch (Exception e) {
                return null;
            }
        }

        String key() {
            return host + ":" + port + ":" + (tcp ? "tcp" : "udp");
        }
    }
}
