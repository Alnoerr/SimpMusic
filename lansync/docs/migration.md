# Migration from experimental AnySync

The obsolete AnySync source module and build/runtime artifacts have been removed. LAN Sync is the
only synchronization backend.

Persisted user data is handled conservatively:

1. The Room database is never deleted, recreated, or migrated for this change.
2. Existing Room favorites and local playlists seed LAN Sync's first reconciliation.
3. AnySync checkpoints are never reused as LAN cursors.
4. Existing app-private AnySync account/runtime files are not read or deleted by the new code; they
   remain available for manual backup or a future explicit converter.
5. LAN credentials, host identity, journals, UUID mappings, and tombstones use their own lansync
   namespace.

There is no safe automatic conversion for opaque Any account state. Removing repository source and
native packaging therefore does not erase prior app-private state. Playlist deletion intent begins
when a playlist has a persisted LAN mapping; a new device with an empty library cannot delete
another device's playlists during initial import.
