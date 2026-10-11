# Callix PBX

Android SIP softphone for Callix PBX extensions.

## Features

- Extension-to-extension calling and incoming-call screen
- Live extension directory and online/offline presence
- Call history, contacts, and configurable incoming ringtone
- Five-minute location reporting for signed-in users
- GitHub Releases update check and installer hand-off

## Release APK

For public distribution, upload the **universal release APK** from
`app/build/outputs/apk/release/` as a GitHub Release asset. Name the release tag
`v1.00`, then use `v1.01`, `v1.02`, and so on. The app checks the latest release
and selects an APK asset, preferring one whose filename contains `universal`.
