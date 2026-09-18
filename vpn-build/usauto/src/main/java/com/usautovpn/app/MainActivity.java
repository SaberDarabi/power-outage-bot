package com.usautovpn.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;

public class MainActivity extends Activity {
    private static final int VPN_PERMISSION_REQUEST = 7001;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Button powerButton;

    private final Runnable refreshUi = new Runnable() {
        @Override
        public void run() {
            render();
            uiHandler.postDelayed(this, 350);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(248, 249, 250));

        powerButton = new Button(this);
        powerButton.setTextSize(24f);
        powerButton.setAllCaps(false);
        powerButton.setOnClickListener(v -> toggle());

        int size = dp(170);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
        params.gravity = Gravity.CENTER;
        root.addView(powerButton, params);

        setContentView(root);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiHandler.removeCallbacks(refreshUi);
        uiHandler.post(refreshUi);
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(refreshUi);
        super.onPause();
    }

    private void toggle() {
        boolean desired = getSharedPreferences(AutoVpnService.PREFS, MODE_PRIVATE)
                .getBoolean(AutoVpnService.KEY_DESIRED, false);

        if (desired) {
            sendControllerAction(AutoVpnService.ACTION_STOP);
            return;
        }

        Intent permissionIntent = VpnService.prepare(this);
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, VPN_PERMISSION_REQUEST);
        } else {
            sendControllerAction(AutoVpnService.ACTION_START);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_PERMISSION_REQUEST && resultCode == RESULT_OK) {
            sendControllerAction(AutoVpnService.ACTION_START);
        }
    }

    private void sendControllerAction(String action) {
        Intent intent = new Intent(this, AutoVpnService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && AutoVpnService.ACTION_START.equals(action)) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void render() {
        boolean desired = getSharedPreferences(AutoVpnService.PREFS, MODE_PRIVATE)
                .getBoolean(AutoVpnService.KEY_DESIRED, false);
        String state = getSharedPreferences(AutoVpnService.PREFS, MODE_PRIVATE)
                .getString(AutoVpnService.KEY_STATE, AutoVpnService.STATE_OFF);

        powerButton.setText(desired ? "ON" : "OFF");
        if (!desired) {
            powerButton.setBackgroundColor(Color.rgb(70, 73, 78));
            powerButton.setTextColor(Color.WHITE);
            powerButton.setAlpha(1f);
        } else if (AutoVpnService.STATE_CONNECTED.equals(state)) {
            powerButton.setBackgroundColor(Color.rgb(20, 135, 75));
            powerButton.setTextColor(Color.WHITE);
            powerButton.setAlpha(1f);
        } else {
            powerButton.setBackgroundColor(Color.rgb(16, 95, 180));
            powerButton.setTextColor(Color.WHITE);
            powerButton.setAlpha(0.72f);
        }
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
