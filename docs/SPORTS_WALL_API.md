# StreamVault Sports Wall API

Sports Wall exposes a semantic REST API from the Shield on TCP port `8789`.
It is designed for Home Assistant, media-orchestrator, and MCP adapters; callers
do not send stream URLs or provider credentials.

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
| `GET` | `/v1/channels/search?q=...&limit=20` | Search the active provider's safe channel catalog. |
| `PUT` | `/v1/layout` | Atomically assign four channel IDs/nulls and open multiview. |
| `PUT` / `DELETE` | `/v1/panes/{1..4}` | Replace or clear one pane. |
| `PUT` | `/v1/audio` | Pin audio to a pane; send `null` to follow focus. |
| `PUT` | `/v1/performance` | Set `AUTO`, `CONSERVATIVE`, `BALANCED`, or `MAXIMUM`. |
| `POST` | `/v1/fullscreen` | Open one populated pane fullscreen. |
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
