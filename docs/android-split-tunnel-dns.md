# Android split tunneling: stale mapped DNS after reconnect

Reported symptom: Selected apps only intermittently interrupts YouTube Shorts;
disconnect/reconnect does not reliably help, while applying Bypass selected and
then Selected apps only restores traffic.

## Confirmed defect

Android's TUN uses HEV mapped DNS: it returns synthetic IPv4 addresses from
`100.64.0.0/10` and translates them back to domain names for SOCKS connections.
Previously every tun2socks stop destroyed that table. The next run allocated
addresses again starting at `100.64.0.0`. An application retaining an address
could therefore connect to the wrong domain, or to an unmapped synthetic IP.
The DNS response TTL is one second, but clearing our table cannot invalidate
addresses already held by application connection pools.

The native regression test reproduced this without Android or an external
server: resolve `video.example.com`, stop/start, then resolve
`other.example.com`. Before the fix, lookup of the original video address
returned `other.example.com`.

## Change

- Retain one bounded mapping table, including its LRU order, across sequential
  tunnel runs within the same process. The configured limit remains 10,000
  entries. Reset it if the mapping network, mask, or capacity changes, or if
  mapped DNS is disabled.
- Use ordinary heap allocation for this table: the HEV task allocator is tied
  to the tunnel thread and is shut down between runs.
- Only translate IPs inside the configured mapping network. Previously a real
  IP with matching host bits could also resolve to an unrelated cached domain.

This is a confirmed DNS lifecycle defect, **not a confirmed reproduction of the
reporter's Shorts interruptions**. No reporter logs, Android version, or device
were available. Changing app routing may cause applications to discard network
state. Android also treats allowed/disallowed application changes specially
during VPN handover; see the [Android VPN implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/connectivity/Vpn.java).
That makes the workaround consistent with stale state, but does not establish
the cause of every interruption.

## Validation

Run `make -C tests/native test` for the packet-level regression under UBSan.
On Linux, use `make -C tests/native test SANITIZERS=address,undefined` for ASan
as well; PR checks run this command. The local Apple ASan runtime hangs during
its own initialization, before `main`, so local validation used UBSan.

The test covers cached address reuse after a thread exits, new domains arriving
first after restart, 100 restarts, normal IPv4 addresses, cache bounds/LRU,
configuration changes, and disabled DNS. Build Android with
`./gradlew :androidApp:assembleDebug`.

For device validation, select YouTube in Selected apps only, reproduce the
reported workload, and repeat with ordinary VPN stop/start and with routing
mode changes. Record whether the VPN itself enters Reconnecting and capture
the app log around interruptions. This remains necessary to confirm the user
report is resolved.

Mappings do not survive process death and may still be evicted when the cache
fills; this change does not add disk persistence, UDP support, or change the
transport watchdog.
