package lansync.security

import android.content.Context
import java.io.File
import okio.Path.Companion.toPath

fun androidLanCredentialStore(context: Context): LanCredentialStore =
    JsonLanCredentialStore(
        File(context.filesDir, "lansync/credentials.json").absolutePath.toPath(),
    )
