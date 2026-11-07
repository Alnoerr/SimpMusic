# Upstream compatibility

Most code remains inside lansync. Necessary changes outside it are deliberately small:

- core/domain: six LAN-only local-playlist repository operations.
- core/data: Room observation plus atomic metadata/entry replacement implementations.
- composeApp: Koin wires the playlist adapter and the popup names the expanded scope.
- protocol/version tests and default English resources were updated.

No Room entity or schema version changed. No playback, download, ViewModel, YouTube API, credential,
or media behavior changed. Android and Desktop reuse the same controller/session, Ktor transport,
WebSocket, durable outbox, host log, and discovery process.

The AnySync source tree, workers, tests, runtime staging, native assets, and vendor scripts were
removed. Existing app-private legacy files remain untouched on installed devices.
