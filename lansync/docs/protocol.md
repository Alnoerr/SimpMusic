# LAN protocol v2

Protocol v2 is an explicit compatibility boundary for playlist records. Discovery, QR payloads,
server info, HTTP requests, and WebSocket events carry the version; v1 peers are rejected as a
protocol mismatch instead of receiving unknown entity types.

All bodies use Kotlin Serialization JSON over HTTPS. Except for server info and single-use pairing,
endpoints require an authenticated per-device bearer credential.

| Method | Path | Body/result |
| --- | --- | --- |
| GET | /api/v1/info | protocol/schema, host identity, pin, capabilities |
| POST | /api/v1/pair | single-use invitation to device credential |
| GET | /api/v1/snapshot | favorites, songs, playlists, ordered entries, saved playlist references |
| GET | /api/v1/changes | sequenced incremental changes after a cursor |
| POST | /api/v1/changes | durable operation acknowledgments |
| GET | /api/v1/status | authenticated device and latest sequence |
| WSS | /api/v1/events | changes-available notifications |

Entity types are SONG, FAVORITE, PLAYLIST, PLAYLIST_ENTRY, and SAVED_PLAYLIST_REFERENCE. Saved playlist references use source-qualified IDs, carry explicit saved/unsaved state, and are applied only to local Room rows; no YouTube mutation endpoint is called. Playlist and entry deletion is
encoded as a tombstone payload and operation. The server validates envelope identity, logical
version, operation/tombstone agreement, payload size, authenticated source device, batch size, and
schema version before appending.

Playlist metadata is last-writer-wins by (Lamport counter, device ID). Membership is not a
whole-playlist snapshot: each occurrence has its own entry ID and version. Ordering uses
(orderKey, entryId). Duplicate operation IDs are acknowledged idempotently without allocating a new
host sequence.
