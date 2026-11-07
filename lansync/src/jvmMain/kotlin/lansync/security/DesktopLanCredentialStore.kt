package lansync.security

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import okio.Path.Companion.toPath

fun desktopLanSyncDirectory(): Path =
    Path.of(System.getProperty("user.home"), ".simpmusic", "lansync")

fun desktopLanCredentialStore(
    directory: Path = desktopLanSyncDirectory(),
): LanCredentialStore =
    JsonLanCredentialStore(
        directory.resolve("credentials.json").toString().toPath(),
        afterWrite = { path ->
            runCatching {
                Files.getFileAttributeView(path.toNioPath(), PosixFileAttributeView::class.java)
                    ?.setPermissions(
                        setOf(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                        ),
                    )
            }
        },
    )
