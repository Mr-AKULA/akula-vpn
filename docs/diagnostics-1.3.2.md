# Technical reports in 1.3.2

Android and Windows persist uncaught JVM exceptions and instrumented errors
(VPN status, subscriptions, imports, pings and update checks) to a bounded local
queue. Reports are sent over HTTPS to `https://akula-hosting.ru/api/app-reports`.
A crash report is normally sent after the next launch. Failed requests retain
the report for retry. Repeated handled errors are suppressed for five minutes.
The queue holds up to 50 reports, with ten slots reserved for crashes.

Payload: a random report UUID, application/OS version, platform, event time,
operation, exception class names and stack method names/line numbers. Exception
messages, configuration contents, keys, visited sites and device identifiers
are excluded. Android 11+ also reports previous native-crash/ANR exit reasons
without system descriptions or native dumps. Windows native crashes and forced
process termination are not captured. A full queue can drop further events.

The receiver is deployed separately in the existing VPN panel backend. Reports
are restricted to authenticated administrators at
`https://akula-hosting.ru/api/admin/app-reports`. The Android R8 mapping must be
retained privately for this release to interpret obfuscated stack traces.

## Building on Windows

Use a full JDK 21 containing jpackage. The packaging JDK can be supplied using
`-Pakula.packageJavaHome=<JDK directory>`; Android signing uses the existing
external signing configuration, never a keystore checked into this repository.
If Git checked out native header symlinks as plain text, run
`python scripts/materialize-native-headers.py` in this checkout before building.
Do not commit those generated header replacements.

Release validation: JVM suite, server validation/authentication tests, signed
Android upgrade from public 1.3.0, Android 7/14 launch, actual Android crash to
production receiver, packaged Windows UI launch and Windows collector HTTPS
delivery. MSI version and stable UpgradeCode checked without modifying an
installed user application. A full Windows installer upgrade and live VPN
traffic on physical devices are not covered by these checks.
