# StreamVault Sports Wall build and deployment

This branch is a side-by-side fork of upstream StreamVault 1.0.17 for the
Sports Wall control project.

## Stable Android identity

- Application ID: `com.cleptogk.streamvault.sportswall`
- Release candidate: `1.0.17.24` (`versionCode` `1000041`)
- Update source: `cleptogk/StreamVault-IPTV` GitHub releases

Android will accept an in-place update only when the application ID and signing
certificate are unchanged and the new APK has a higher `versionCode`. Do not
generate a replacement release key. This matches Android's documented update
rule: the installed and replacement APK certificates must match ([Android app
signing](https://developer.android.com/studio/publish/app-signing)).

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

Example verification (use the Android SDK tools installed on the build host):

```bash
aapt dump badging app/build/outputs/apk/release/app-release.apk | head
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
adb -s <shield-ip>:5555 shell dumpsys package com.cleptogk.streamvault.sportswall \
  | rg 'versionCode|versionName|lastUpdateTime'
```

Compare the certificate digest with the already installed Sports Wall package
before installation. Never print the keystore password or API/SMB credentials.

## Fast diagnostic deployment

For edit-test cycles on a configured Shield, use the diagnostic build rather
than the release build:

```bash
SHIELD_ADB_SERIAL=<shield-ip>:5555 tools/deploy-diagnostic.sh
```

The diagnostic variant uses the stable Sports Wall application ID and release
signing key, so `adb install -r` updates the installed fork without clearing its
data. It keeps release code and resources but omits release minification,
resource shrinking, and release-only lint-vital work. The script uses the
persistent Gradle daemon, parallel execution, and the build cache, then installs
the resulting APK in one command. Configuration caching stays off because the
bundled FFmpeg artifact verifier intentionally inspects nested archives at task
execution time.

The first diagnostic build populates the caches. Measure subsequent edit-test
cycles with the final timing line printed by the script. Use `assembleRelease`
for the final release gate; the fast diagnostic path does not replace release
verification.

## Release gate

Before promoting a diagnostic build:

1. Verify two, three, and four simultaneous panes and preserve TiviMate and
   upstream StreamVault.
2. Verify three Channels DVR recordings plus one non-UHD live channel.
3. Pause all panes for three minutes, resume, and observe beyond the first HLS
   snapshot boundary.
4. Confirm the same PID, no crash/OOM, bounded Java heap, and all panes in
   `READY`/playing after resume.
5. Capture the bottom-right quadrant every two seconds for at least 90 seconds
   and confirm frame progression rather than relying on a single screenshot.
6. Verify unauthenticated API control returns `401`, authenticated health and
   diagnostics work, and the listener remains LAN-scoped.

Media3 supports regular live HLS and periodically refreshes primary live
playlists; the Sports Wall's local rewind snapshots therefore have explicit
continuation handling when a finite local playlist reaches its end. See the
official [Media3 HLS guide](https://developer.android.com/media/media3/exoplayer/hls)
and [HLS playlist tracker reference](https://developer.android.com/reference/androidx/media3/exoplayer/hls/playlist/HlsPlaylistTracker).

## Rollback

The official upstream StreamVault and TiviMate should remain installed as
known-good fallbacks. If a Sports Wall build fails, launch either fallback or
reinstall the last known-good Sports Wall APK signed by the same release key.
Downgrading to a lower `versionCode` requires uninstalling Sports Wall first and
will erase only the Sports Wall app's local data.

Keep a verified copy of the last known-good signed APK and record its version,
SHA-256, and certificate digest in the private release record. Never store the
keystore or passwords with the APK.

The fork remains subject to the upstream [StreamVault source-available
license](https://github.com/Davidona/StreamVault-IPTV/blob/master/LICENSE),
including attribution requirements for modified builds. Upstream stable release
history remains at the [official StreamVault releases
page](https://github.com/Davidona/StreamVault-IPTV/releases).
