# Pairing

Desktop “Pair Device” creates an in-memory invitation with a random 128-bit ID, random 256-bit
secret, host SPKI fingerprint, and two-minute expiry. It is encoded as a versioned
`simpmusic-lan://pair/v1?` payload and rendered with the project's existing ZXing dependency.
Closing the dialog invalidates the invitation.

Android opens the existing CameraX/ZXing scanner component. LAN payloads are parsed only when the
explicit scheme/path/version match; the pre-existing account-transfer screen and payload handling
are unchanged. The confirmation shows the host, endpoint, and a shortened fingerprint. The checkbox
“Trust this device and sync automatically when available” defaults to unchecked.

The client creates a TLS client pinned to the QR fingerprint before sending the invitation secret.
A successful HTTPS pair call consumes the invitation and returns a per-device token. Failed attempts
are bounded and invitations are single-use.

With trust checked, the client atomically persists the peer identity, token, pin, last endpoint, and
auto-sync setting. Without trust, both sides keep authenticated credentials only in memory for a
bounded session and do not reconnect automatically after it ends.

Forget revokes authorization and closes matching active WebSockets without deleting favorites.
Disconnect ends the current connection while retaining trust. Reinstall or host key rotation
requires explicit pairing again.
