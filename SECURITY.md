# Security and privacy review

This document describes the security posture of the current debug prototype. It is not a guarantee of the KHG server's security or a legal privacy notice.

## Checks performed

- Android manifest and permissions reviewed.
- `assembleDebug` completed from the checked-in Gradle wrapper.
- Android Lint completed with zero errors; one dependency freshness warning remains for `activity-compose:1.10.0`.
- APK signing verified with APK Signature Scheme v2.
- Static source scan checked for cleartext URLs, trust-all TLS handlers, hostname bypasses, logging of credentials, WebViews, and unsafe backup settings.
- Runtime dependency inventory generated with Gradle.

## Current protections

- The only declared permission is `INTERNET`.
- Cleartext traffic is disabled; the configured IPP endpoint uses HTTPS.
- Default Android certificate and hostname verification are preserved. There is no trust-all certificate or hostname verifier.
- Remembered username and password are encrypted together with AES-GCM using an Android Keystore key.
- Shared preferences are excluded from legacy backup, cloud backup, and device transfer.
- Logout removes the stored encrypted payload and deletes the Keystore key.
- The app has no analytics, advertising, developer backend, WebView, or cloud upload service.
- The PDF is read through Android's system picker and sent only after the user confirms the print job.

## Important limitations and warnings

- This is a debuggable, unofficial build. Do not treat it as a production security release.
- HTTP Basic credentials are sent to the configured server over HTTPS. The app currently does not implement HTTP Digest authentication.
- The KHG IPP/CUPS server may retain authentication records, print-job metadata, documents, or logs under policies outside this app's control.
- The printer URL, queue name, authentication policy, certificate, and network rules may change.
- No device/emulator runtime test or authorized penetration test was available in this environment. The actual KHG network and server were not tested.
- Do not print documents containing sensitive personal data unless you understand the printer service's handling and retention rules.

For app-specific issues, contact `zankeith.contact@gmail.com` or open an issue at `https://github.com/zan-keith`. For printer-service or institutional data-protection questions, contact the responsible KHG administrator.
