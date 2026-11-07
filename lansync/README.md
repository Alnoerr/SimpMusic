# SimpMusic LAN synchronization

The lansync module is SimpMusic's single Android/Desktop LAN synchronization engine. It synchronizes
favorite songs and locally created playlists through the existing authenticated Ktor HTTPS/WSS
session, durable outbox, host change log, Lamport versions, and Room adapters.

Protocol v2 adds playlist metadata and entry-level ordered records. See
[implementation-status.md](docs/implementation-status.md) for verified scope and limitations.
