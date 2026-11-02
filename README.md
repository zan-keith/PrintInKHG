# KHG Print Probe — Android Studio prototype

Purpose: determine whether the existing KHG CUPS queue will accept direct IPP requests and PDF print jobs from Android **without installing a native Linux driver**.

## What it does
- Connects to the hard-coded existing KHG server over verified HTTPS (`https://davinci.khg.jku.at:631/printers/P3055`).
- Sends an IPP Get-Printer-Attributes request and reports supported document formats, authentication and printer status.
- Lets the user select a PDF using Android's system file picker, confirms the choice and sends it as an IPP Print-Job.
- Uses HTTP Basic credentials **only in memory**, never written to disk, network URLs or logs.
- No CUPS server runs on Android, and there is no connection to the physical printer IP.

## Run
1. Install Android Studio and the Android SDK for API 35; use JDK 17.
2. Unzip and open the `KHGPrintPrototype` folder in Android Studio.
3. Sync Gradle. This source distribution does not include a Gradle wrapper JAR; if Studio requires it, generate a wrapper with Gradle 8.9 or copy these app sources into a new Android Studio project with the matching Gradle configuration.
4. Connect Android to `KHG-WiFi`. Select the connected Android device in Studio and press Run.
5. Enter your KHG username/password. Tap `Check IPP printer capabilities` **before** attempting a print.
6. If successful and `application/pdf` is listed, try a **one-page PDF** using `Choose PDF to print`.

## Current UI architecture

The app UI is implemented with Jetpack Compose and Material 3. The flow is split into onboarding, home, printer connection, PDF preparation, print review, and job status screens. Credentials are encrypted with Android Keystore when the user chooses to remember them.

The repository history is organized by milestone:

- `chore: initialize KHG print prototype`
- `build: add reproducible Gradle wrapper`
- `feat(print): support page ranges and print options`
- `feat(ui): migrate screens to Compose Material 3`
- `polish(identity): add KHG launcher logo and app icon`

## Important limitations
- **Not tested on the KHG network, a device, or Android SDK.** It is source code, not a tested APK.
- Supports HTTP Basic authentication only. If the server responds with `WWW-Authenticate: Digest`, add a correct Digest handler or a vetted compatible IPP client. Never disable authentication.
- Uses standard Android certificate verification. Certificate validation failures require an authorized trusted CA configuration, not an unsafe trust-all workaround.
- Uses HTTP chunked request streaming for PDF submission; a CUPS configuration that rejects chunked requests may need a fixed Content-Length fallback.
- The minimal IPP response parser handles ordinary primitive attributes but not collection-valued attributes.
- Does not yet integrate as an Android `PrintService`. This is intentional; first prove a direct authenticated IPP Print-Job.
- Doesn't implement a print preview, job cancellation, printer queue discovery, job progress polling, duplex controls, or password persistence.
- Only test printing your own documents, with any KHG printing policies or charges in mind.

## Technical references
- https://www.pwg.org/ipp/ippguide.html
- https://www.rfc-editor.org/rfc/rfc8011
- https://developer.android.com/reference/android/printservice/PrintService
