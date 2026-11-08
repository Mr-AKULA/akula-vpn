# Diagnostics 1.3.3

VPN errors include a closed, privacy-safe context: protocol, stage, reason and
optional bounded elapsed milliseconds, attempt number and process exit code.
Expected failures use diagnostic.event with no invented Throwable stack.
Real exceptions retain their cause and stack; exception messages, profiles,
keys, endpoints and traffic are excluded. Authentication failures are preserved
instead of being replaced by a later generic timeout. Failover attempts and the
final failed attempt are distinguished. Receiver grouping uses reason/stage/
protocol, not elapsed time or attempt number.

The separately deployed panel receiver is versioned in server/app_reports.py.
Install it as routers/app_reports.py in the existing panel backend; its admin
dependency comes from that backend. It remains compatible with 1.3.2 reports.
Run server/test_reports.py with FastAPI, httpx and the backend dependencies.
Administrator access: https://akula-hosting.ru/api/admin/app-reports
R8 mappings for both releases are retained privately, never published as assets.

Verified on 2026-11-08: 102 JVM tests passed; four receiver tests passed;
production anonymous access 401, ordinary user 403, administrator 200.
Release APK has the same signing certificate and installs over 1.3.2 (code 16).
On Android 14 emulator, an intentionally invalid AmneziaWG test profile produced
a real HTTPS report with validate_profile/profile_invalid on the live receiver.
Packaged Windows collector delivered controlled timeout, authentication and JVM
crash reports; packaged Windows UI launched with an isolated profile. Android
and Windows installers built successfully. Privacy, retry, deduplication and
context validation were checked. These checks do not prove live VPN connectivity
on physical devices or a full Windows installer upgrade. Windows installers are
unsigned. Coverage and bounded-queue limitations from 1.3.2 remain applicable.

Existing user reports in 1.3.2 cannot recover missing network error messages:
one connection timeout, three subscription DNS errors and two subscription
socket errors were present during verification. Test reports are separate.
