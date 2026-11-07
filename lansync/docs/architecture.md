# Architecture

LAN Sync uses one synchronization pipeline for favorites and local playlists. SyncEngine owns Room
observation, the durable outbox, initial union, Lamport conflict resolution, tombstones, cursor
advancement, reconnect recovery, and single-flight reconciliation. Local Room changes, reconnects,
manual Sync Now, and authenticated WebSocket notifications all trigger that engine.

CompositeMusicLibraryAdapter combines the existing favorite adapter with
RoomPlaylistMusicLibraryAdapter. Playlist transport state is not another database: Room remains the
source of truth, while playlist-state.json stores only stable global-ID mappings, entry identity,
logical versions, and tombstones. Existing local Long keys and pair-row keys are never replaced.

Locally created playlist IDs are deterministic UUID-shaped IDs prefixed with localplaylist:.
Entry IDs are separate UUID-shaped IDs, so duplicate song occurrences remain distinct. Entries use
a zero-padded sparse-compatible ordering token and entry ID as the deterministic tie-breaker.
Metadata and each entry merge independently by logical version. Concurrent additions therefore
survive; incompatible concurrent reorders converge by per-entry version and the deterministic
ordering tie-breaker.

The adapter applies changes only through LAN-specific local repository methods. Those methods
update Room atomically and never call YouTube mutation APIs. Saved PlaylistEntity references use
source-qualified IDs (`ytmusic-playlist:<id>`) and synchronize explicit liked/unsaved state plus
rendering metadata without creating, editing, or deleting remote YouTube playlists. Playlist deletion
removes the local playlist and relationship rows through Room's existing cascade; song rows,
favorites, downloads, and external playlists are untouched.

Android and Desktop each own one application-scoped controller. They reuse the existing pairing,
TLS pinning, credential, discovery, HTTP/WSS, journal, and host-log infrastructure. The Home popup
remains the control surface and now identifies the synchronized scope as Favorites & Playlists.
