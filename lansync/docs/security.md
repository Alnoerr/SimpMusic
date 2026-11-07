# Security

The existing LAN security model is unchanged: HTTPS/WSS, persistent Desktop EC identity, SHA-256
SPKI pinning, short-lived single-use invitations, random per-device bearer credentials, revocation,
request limits, and WebSocket frame limits. Discovery advertises only protocol version, public
device ID, and port.

Protocol v2 validates playlist and entry envelope identity, logical version, operation/tombstone
agreement, schema version, payload size, batch size, and authenticated source device before a change
reaches Room. Playlist payloads contain titles, cover URLs, source track IDs, and ordering metadata.
They do not contain YouTube credentials, local media paths, audio files, download state, history, or
playback position.

Credentials and sync state remain atomic app-private files. No Keystore, DPAPI, Keychain, or new
security framework was introduced.
