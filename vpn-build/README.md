# USA Auto VPN build

This branch contains a small Android application module built on the OpenVPN engine from:

- https://github.com/hoang-rio/vpngate-connector
- submodule: https://github.com/hoang-rio/vpnLib

Upstream commit pinned by the workflow:
`e733f5f1861e4a5f3843a19a789be2844d84008e`

## Behavior

- UI contains one ON/OFF button only.
- VPN Gate CSV is filtered strictly to `CountryShort == US`.
- Candidate servers are ranked by VPN Gate score, latency and throughput.
- TCP candidates receive a live socket probe before ranking.
- A recently successful US profile is cached for faster startup.
- OpenVPN keepalive/restart parameters are tightened.
- Tunnel health is checked against two independent HTTPS endpoints.
- A failed server is temporarily blacklisted and the next US server is started automatically.
- No ads, analytics, account system, or non-US fallback.

## Important limitation

VPN Gate nodes are volunteer-operated public servers. The application can optimize selection and failover, but it cannot guarantee the bandwidth, uptime, privacy policy, or routing quality of third-party servers.
