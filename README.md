# fMessage (Android, Kotlin)

Native port of the fMessage web messenger. Jetpack Compose, Material 3 Expressive, all icons are SVG vector drawables.
Backend = private GitHub repo `FaraonTT/fmessage-data` (JSON files via GitHub Contents API, polling for realtime).

## Setup
1. Create a fine-grained token limited to `fmessage-data` with **Contents: Read and write**.
2. Either add it as Actions secret `DATA_TOKEN` (baked into the APK), or enter it in the app on first launch.
3. Push to `main` - Actions builds a signed release APK and publishes it under Releases.

The first registered account becomes owner/admin.
