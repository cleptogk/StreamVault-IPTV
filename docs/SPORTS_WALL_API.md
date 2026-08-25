# StreamVault Sports Wall API

Sports Wall exposes a semantic REST API from the Shield on TCP port `8789`.
It is designed for Home Assistant, media-orchestrator, and MCP adapters. Voice
and MCP callers use semantic channel, event, and recording names; only the
trusted media-orchestrator adapter may submit the validated Channels DVR URL
required by the low-level recording endpoint. Provider credentials are never
accepted or returned by this API.

## Security boundary

- The listener accepts only loopback and `10.217.0.0/24` clients.
- Every endpoint except `GET /v1/health` requires an `Authorization: Bearer`
  header.
- The app creates a random 256-bit token and stores it with Android Keystore-
  backed encrypted preferences when available.
- The token is revealed or rotated only from **Settings → About → Sports Wall
  API** on the TV.
- API responses omit provider URLs, stream URLs, and credentials.
- Adult or user-protected channels cannot be searched or assigned remotely.

The API currently uses HTTP on the trusted home LAN. Do not port-forward it or
publish it through a WAN or unauthenticated reverse proxy.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/v1/health` | Unauthenticated liveness and version check. |
| `GET` | `/v1/state` | Current 2×2 assignments, audio pane, focus, and performance mode. |
| `GET` | `/v1/diagnostics/timeshift` | Per-pane playback, resolution, live-buffer, and timeshift health. |
| `GET` | `/v1/channels/search?q=...&limit=20` | Search the active provider's safe channel catalog. |
| `PUT` | `/v1/layout` | Atomically assign four channel IDs/nulls and open multiview. |
| `PUT` / `DELETE` | `/v1/panes/{1..4}` | Replace or clear one pane. |
| `PUT` | `/v1/panes/{1..4}/recording` | Assign a validated Channels DVR recording to one pane. |
| `PUT` | `/v1/audio` | Pin audio to a pane; send `null` to follow focus. |
| `PUT` | `/v1/performance` | Set `AUTO`, `CONSERVATIVE`, `BALANCED`, or `MAXIMUM`. |
| `POST` | `/v1/fullscreen` | Open one populated pane fullscreen. |
| `POST` | `/v1/recordings/fullscreen` | Open a validated Channels DVR recording fullscreen. |
| `POST` | `/v1/playback/pause` | Pause every populated pane; live panes enter local timeshift. |
| `POST` | `/v1/playback/resume` | Resume every populated pane from its coordinated pause point. |
| `POST` | `/v1/restore` | Return to the 2×2 wall. |
| `POST` | `/v1/presets/{1..3}/save` | Save current assignments. |
| `POST` | `/v1/presets/{1..3}/load` | Load assignments and open multiview. |
| `POST` | `/v1/launch` | Open the current multiview state. |

Pane numbers are one-based. Layout bodies always contain exactly four entries:

```json
{
  "channelIds": [101, 202, 303, 404],
  "launch": true
}
```

Individual assignment and audio examples:

```json
{"channelId": 505, "launch": true}
```

```json
{"pane": 2}
```

The pause and resume endpoints have no request body. They return the normal
wall state, including `"paused": true|false`. Pause is deliberately global:
there is no remote operation that pauses only the selected pane.

## Timeshift diagnostics

`GET /v1/diagnostics/timeshift` returns no URLs or credentials:

```json
{
  "capturedAtMs": 1787644210962,
  "panes": [
    {
      "pane": 4,
      "playbackState": "READY",
      "isPlaying": true,
      "videoWidth": 1280,
      "videoHeight": 720,
      "timeshiftStatus": "PLAYING_BEHIND_LIVE",
      "timeshiftBackend": "DISK",
      "bufferedDurationMs": 291673,
      "offsetFromLiveMs": 274306,
      "updatedAtMs": 1787644210962
    }
  ]
}
```

Expected live states include `PREPARING`, `LIVE`, `BUFFERING`,
`PAUSED_BEHIND_LIVE`, and `PLAYING_BEHIND_LIVE`. `FAILED` or
`UNSUPPORTED` requires operator attention. Recordings normally report
`DISABLED`/`NONE` because they are already seekable media.

An `ENDED` sample during delayed HLS playback may be a continuation boundary.
The player should return to `READY` automatically as the next captured chunk is
loaded. Treat it as unhealthy only when it remains ended or the process changes.

## Client example

Keep the real token in a secret store and pass it without printing it:

```bash
curl -fsS \
  -H "Authorization: Bearer $SPORTS_WALL_TOKEN" \
  http://10.217.0.133:8789/v1/state
```

Callers should search first, disambiguate channel matches semantically, then
submit one atomic `/v1/layout` request. This prevents partially updated walls
when a voice request names multiple events.

`MAXIMUM` is an explicit four-decoder override in the Sports Wall fork even
when Android's memory-class heuristic labels the Shield as a low-tier device.
Thermal telemetry can still reduce active playback if the device reaches a
severe or critical condition.

## Client contract

- Use the MCP's room-scoped semantic tools instead of calling the Shield from a
  voice client.
- Use one atomic layout call for multi-event requests.
- Do not retry mutations without a bounded policy. Health and diagnostics reads
  are safe to poll.
- Preserve one bearer token per Shield/TV. A second endpoint must not reuse the
  downstairs token.
- Never expose this HTTP listener to the WAN.

MCP tool definitions use object-root JSON Schemas and deterministic names, as
required by the current [Model Context Protocol tools
specification](https://modelcontextprotocol.io/specification/draft/server/tools).
