# Diagnostics 1.3.4

Android connection waits: AmneziaWG 30 seconds for one profile / 20 seconds per failover attempt; other protocols 45 / 30 seconds. Timeout reports include the configured limit, elapsed duration, attempt, profile count and automatic failover flag.

Subscription failures distinguish request, body read, non-success HTTP status and empty response, with elapsed duration, proxy and insecure TLS flags. Network exceptions are classified locally (DNS, TLS, timeout and socket categories). Android attaches coarse connectivity flags: transport, connected, validated, captive portal, metered and VPN active. Reading network state must never prevent recording an error. Crash handling avoids querying Android connectivity services.

Reports exclude exception messages, subscription URLs, configuration content, credentials and network identifiers. The receiver strictly validates optional fields and remains compatible with earlier versions. Different network failure categories and HTTP statuses are grouped separately; elapsed time and current network flags do not split groups.

Validation: 105 JVM tests passed; five receiver tests passed. Packaged Windows collector reports traversed HTTPS and were verified in the production database. Android release APK versionCode 17 retains the existing signing certificate and was installed over the previous version on an Android 14 emulator; real subscription failures reached the receiver with expanded context and connectivity fields. Windows packaged UI opened and responded. No successful physical-device VPN tunnel or full Windows installer upgrade is claimed by these checks.

This change improves waiting and evidence collection. It does not establish or repair the remote network cause of the earlier reports. Offline delivery uses the existing bounded queue. Forced process termination and native Windows crashes are not captured.
