# fMessage (Android, Kotlin)

Native port of the fMessage web messenger. Jetpack Compose, Material 3 Expressive, all icons are SVG vector drawables.
Backend = private GitHub repo `FaraonTT/fmessage-data` (JSON files via GitHub Contents API, polling for realtime).

## Setup
1. Create a fine-grained token limited to `fmessage-data` with **Contents: Read and write**.
2. Enter the GitHub token in the app's first-run GitHub configuration screen. Do not put a write token in the APK or in Gradle build variables: APK contents can be inspected and extracted.
3. Add the signing credentials as GitHub Actions secrets (see the workflow): `KEYSTORE_B64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, and `SIGNING_KEY_PASSWORD`.
4. Push to `main` - Actions builds a signed release APK and publishes it under Releases.

The first registered account becomes owner/admin.
