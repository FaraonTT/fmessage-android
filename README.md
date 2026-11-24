# fMessage (Android, Kotlin)

Native Android messenger built with Jetpack Compose.

The app uses the private GitHub repository `FaraonTT/fmessage-data` as its data store. The repository name and GitHub token are supplied to Gradle during the release build. The token is compiled into the APK, so the app no longer asks users to enter repository details or a token.

## Build release APK

Configure these GitHub Actions repository secrets:

- `DATA_TOKEN` — fine-grained GitHub token for `FaraonTT/fmessage-data`, with **Contents: Read and write**.
- `KEYSTORE_B64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

Push to `main` or run the workflow manually. The workflow builds and publishes the signed APK.

**Security warning:** embedding a GitHub token in an APK makes it extractable by anyone who obtains the APK. Use a dedicated fine-grained token limited to this single repository, and revoke/rotate it if the APK is distributed beyond the intended audience.

The first registered account becomes owner/admin.
