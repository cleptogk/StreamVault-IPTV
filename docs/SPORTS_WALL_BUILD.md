# StreamVault Sports Wall build and deployment

This branch is a side-by-side fork of upstream StreamVault 1.0.17 for the
Sports Wall control project.

## Stable Android identity

- Application ID: `com.cleptogk.streamvault.sportswall`
- Initial version: `1.0.17.1` (`versionCode` `1000018`)
- First authenticated API version: `1.0.17.2` (`versionCode` `1000019`)
- Update source: `cleptogk/StreamVault-IPTV` GitHub releases

Android will accept an in-place update only when the application ID and signing
certificate are unchanged and the new APK has a higher `versionCode`. Do not
generate a replacement release key.

The upstream application (`com.streamvault.app`) is a separate installation.
Installing, updating, or removing Sports Wall does not replace upstream
StreamVault or TiviMate.

## Signed release build

Provide the release key and credentials through the build environment:

```text
SPORTS_WALL_STORE_FILE
SPORTS_WALL_STORE_PASSWORD
SPORTS_WALL_KEY_ALIAS
SPORTS_WALL_KEY_PASSWORD
```

Then run:

```bash
./gradlew assembleRelease
```

The signed APK is written to `app/build/outputs/apk/release/app-release.apk`.
Never commit the keystore, credentials, `keystore.properties`, or generated APK.

Before deployment, verify the APK package, version, and signing certificate.
Install with ADB using `adb install`; use `adb install -r` for later updates.

## Rollback

The official upstream StreamVault and TiviMate should remain installed as
known-good fallbacks. If a Sports Wall build fails, launch either fallback or
reinstall the last known-good Sports Wall APK signed by the same release key.
Downgrading to a lower `versionCode` requires uninstalling Sports Wall first and
will erase only the Sports Wall app's local data.
