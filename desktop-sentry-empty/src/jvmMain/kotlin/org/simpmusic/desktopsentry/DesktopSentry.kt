package org.simpmusic.desktopsentry

object DesktopSentry {
    fun initialize(
        dsn: String,
        versionName: String,
    ) = Unit

    fun captureException(throwable: Throwable) = Unit

    fun captureMessage(message: String) = Unit

    fun endSession() = Unit
}
