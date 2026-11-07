# Testing

The JVM integration suite uses two independent file-backed Room databases, real DAO queries,
actual LAN adapters, the embedded Ktor HTTPS/WSS server/client, TLS pinning, real pairing and
authorization, a durable host log, and a durable client journal.

PlaylistLanSyncHttpsIntegrationTest verifies exact playlist content and order for:

- initial bidirectional import without title-based merging;
- thumbnail/title propagation;
- duplicate song occurrences;
- track reorder and removal;
- offline additions on both devices and deterministic convergence;
- playlist tombstones, idempotent resync, and no duplicate playlist creation;
- saved/favorited PlaylistEntity reference import, save/unsave propagation, and restart-safe replay.

The focused playlist integration test and aggregate :lansync:jvmTest passed on Windows on
2026-11-07. :lansync:compileAndroidMain, :desktopApp:compileKotlinJvm, :androidApp:assembleDebug,
and :vivoAndroidApp:assembleDebug also passed. git diff --check passed. Android application deployment was previously completed on the connected Samsung SM-S721B, but Desktop application startup and physical playlist UI propagation were not exercised in this pass.

Historical physical verification covers Samsung SM-S721B to Windows pairing, trusted reconnection,
and bidirectional favorite/unfavorite propagation. Playlist UI behavior on physical devices remains unverified: Computer Use exposed Android Studio but no targetable SimpMusic Windows application, so the disposable playlist workflow could not be executed without guessing at an unavailable window.
