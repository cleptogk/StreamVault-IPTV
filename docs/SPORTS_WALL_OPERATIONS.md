# StreamVault Sports Wall operations

This runbook covers the separately signed Sports Wall fork on an NVIDIA Shield.
It preserves upstream StreamVault and TiviMate as independent rollback apps.

## Architecture

```text
Home Assistant Assist / MCP client
              |
              v
Media Orchestrator (room-scoped semantic control)
              |
              v  bearer-authenticated LAN REST
StreamVault Sports Wall on Shield :8789
       |                         |
       v                         v
IPTV provider              Channels DVR :8089
       |
       +-- Media3 playback read is teed into local timeshift
                                  |
                                  v
                        dedicated SMB share on Vault RAID0
```

The Shield does not create a kernel SMB mount and does not reuse TiviMate's
mount. StreamVault connects directly with its own encrypted SMB profile. Initial
resume uses the app's rolling local cache; immutable completed segments are
also uploaded to SMB for longer retention and recovery. Uploads are serialized
to avoid the heap growth that overlapping multi-megabyte segment uploads caused
on the Shield.

Media3's existing provider request supplies the initial HLS segment bytes. When
the visible player switches to a local snapshot, ownership transfers to one
independent capture request; the two requests do not overlap. Finite local HLS
snapshots are chained by the last captured segment token so delayed playback
does not freeze at `#EXT-X-ENDLIST`.

## TV setup

### Channels DVR

1. Open **Settings → Recording**.
2. Enter the Channels DVR server address and save it.
3. Return to multiview and open a pane's options.
4. The **DVR Recording** button remains disabled until the address is valid;
   after setup it opens completed recording listings directly.

The wall requests the native video-copy HLS rendition where Channels supports
it. A 1080p recording should report `1920×1080`; a channel that is natively
720p should remain `1280×720` rather than being falsely upscaled.
Channels' `corrupted` marker is diagnostic only: Channels may set it for a
single bad frame, so completed and processed recordings remain listed and
playable. Cancelled or incomplete recordings remain excluded.

### Dedicated SMB profile

1. Open **Settings → Recording → StreamVault SMB storage**.
2. Enter the server/IP, dedicated share, dedicated username and password.
3. Set separate relative folders for **Timeshift** and **Live recordings**.
4. Enable **Use this profile for timeshift and recording**.
5. Select **Test connection**. Success must verify write access to both folders.
6. Save the profile.

Credentials are stored in Android Keystore-backed encrypted preferences. Do not
place them in screenshots, logs, Git, Home Assistant YAML, or MCP arguments.
The server should restrict the share to the dedicated account and directories.
Samba documents `valid users`, create/directory masks, and the security impact
of `force user` in the official [`smb.conf`
manual](https://www.samba.org/samba/docs/current/man-html/smb.conf.5.html).

## Wall controls

- Pane options include global **Pause all** / **Resume all**. The action always
  applies to every populated pane, including recordings and live streams.
- Selecting **Fullscreen** opens the active pane; **Restore wall** returns to
  the 2×2 layout.
- Only one pane decodes audio. The active audio pane shows the compact
  headphones icon.
- Use `MAXIMUM` only as the explicit four-decoder override. Avoid UHD inputs in
  mixed four-pane walls unless the device has been separately validated.

## Semantic MCP and Assist operations

The normal control plane is Media Orchestrator, not direct ADB or raw Shield
REST. Expected operations include:

- search/list safe channels and read wall state;
- set an atomic 2×2 channel layout or replace one pane;
- infer a team/game to its current or upcoming channel;
- place the newest matching Channels DVR recording fullscreen or in pane 1–4;
- select audio, fullscreen/restore, save/load presets, and set performance;
- pause all, resume all, and read summarized timeshift health.

Home Assistant custom sentences can trigger constrained intents, as documented
by the official [Conversation integration](https://www.home-assistant.io/integrations/conversation/).
Examples:

- "Put the 49ers, Illinois, RedZone, and Oregon on the downstairs wall."
- "Put the latest Fever recording in pane one downstairs."
- "Pause the downstairs sports wall."
- "Resume the downstairs sports wall."
- "Is downstairs live rewind healthy?"

Event phrases are resolved through Stream Snatcher; exact channel terms such as
`RedZone` fall back to safe channel search. Ambiguous results should be returned
for clarification rather than guessed.

## Health checks

Use the room-scoped MCP health tool in normal operation. Direct checks are for
diagnosis:

```bash
curl -fsS http://<shield-ip>:8789/v1/health
curl -fsS -H "Authorization: Bearer $SPORTS_WALL_TOKEN" \
  http://<shield-ip>:8789/v1/diagnostics/timeshift
adb -s <shield-ip>:5555 shell pidof com.cleptogk.streamvault.sportswall
adb -s <shield-ip>:5555 shell dumpsys meminfo com.cleptogk.streamvault.sportswall
```

A healthy resumed live pane is `READY`, `isPlaying=true`, and normally
`PLAYING_BEHIND_LIVE`. The process PID must remain stable and the crash buffer
must contain no OOM. An HLS continuation boundary may briefly report `ENDED`,
but it must return automatically to `READY` (verified at approximately one
second on the target Shield).

### Failure guide

| Symptom | Check | Action |
| --- | --- | --- |
| Live pane returns 403 | Provider/Channels logs and bounded retry state | Let the three same-slot retries complete; replace the pane only if recovery fails. |
| Provider returns 509/connection-limit error | Confirm only one capture owner | Do not start a second recorder; use the playback tee/handoff path. |
| Resume skips or app crashes | Java heap, crash buffer, SMB upload concurrency | Confirm single-upload serialization and that local storage remains the immediate resume source. |
| Pane stays `ENDED` | Timeshift diagnostics and PID | Continuation should recover automatically; collect logs if it remains ended. |
| Recordings pause or buffer | Channels DVR logs and RAID0 temporary space | Confirm its streaming/temp path has free space and all segment requests return 200. |
| Shield screensaver starts | Window state | Confirm `FLAG_KEEP_SCREEN_ON`/`mHoldScreenWindow` while any pane is playing. |

## Upgrade and rollback

Follow [SPORTS_WALL_BUILD.md](SPORTS_WALL_BUILD.md). Every update must keep:

- application ID `com.cleptogk.streamvault.sportswall`;
- the established signing certificate;
- a monotonically increasing `versionCode`;
- a last-known-good signed APK and checksum;
- upstream StreamVault and TiviMate installed.

## Adding another TV

Treat each Shield/TV as a separate endpoint:

1. Assign a stable address and authorized ADB host key.
2. Install the same signed package without copying provider data.
3. Configure its own provider, Channels DVR address, and SMB profile on-device.
4. Rotate/store a distinct Sports Wall bearer token.
5. Add a new room/player mapping in Media Orchestrator and Home Assistant.
6. Verify its own four-pane, three-minute pause, continuation, signing, and
   rollback gates before enabling voice commands.

Never share one device token across rooms and never expose port `8789` outside
the trusted LAN.

## Research references

- [Official StreamVault releases](https://github.com/Davidona/StreamVault-IPTV/releases)
- [StreamVault license and modified-build attribution](https://github.com/Davidona/StreamVault-IPTV/blob/master/LICENSE)
- [Android Media3 HLS guide](https://developer.android.com/media/media3/exoplayer/hls)
- [Android app signing and update identity](https://developer.android.com/studio/publish/app-signing)
- [Model Context Protocol tools specification](https://modelcontextprotocol.io/specification/draft/server/tools)
- [Home Assistant Conversation integration](https://www.home-assistant.io/integrations/conversation/)
- [Samba `smb.conf` reference](https://www.samba.org/samba/docs/current/man-html/smb.conf.5.html)
