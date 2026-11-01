package org.simpmusic.desktopsentry

import io.sentry.ScopeType
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.User
import java.io.File
import java.security.MessageDigest

private const val SENTRY_APP_OPEN = "app.open"

object DesktopSentry {
    @Volatile
    private var initialized = false

    fun initialize(
        dsn: String,
        versionName: String,
    ) {
        if (dsn.isBlank()) return

        val installationId = machineId()
        Sentry.init { options ->
            options.dsn = dsn
            options.release = "simpmusic-desktop@$versionName"
            options.setDiagnosticLevel(SentryLevel.ERROR)
            options.distinctId = installationId
            options.isSendDefaultPii = true
            options.setTracesSampler { context -> if (context.transactionContext.name == SENTRY_APP_OPEN) 1.0 else 0.0 }
        }
        Sentry.configureScope(ScopeType.GLOBAL) { it.contexts.setOperatingSystem(desktopOs()) }
        if (installationId != null) Sentry.setUser(User().apply { id = installationId })
        Sentry.startSession()
        Sentry.startTransaction(SENTRY_APP_OPEN, SENTRY_APP_OPEN).finish()
        initialized = true
    }

    fun captureException(throwable: Throwable) {
        if (initialized) Sentry.captureException(throwable)
    }

    fun captureMessage(message: String) {
        if (initialized) Sentry.withScope { Sentry.captureMessage(message) }
    }

    fun endSession() {
        if (initialized) Sentry.endSession()
    }
}

private fun machineId(): String? {
    val os = System.getProperty("os.name").orEmpty()
    val raw =
        runCatching {
            when {
                os.startsWith("Windows") -> {
                    runCommand("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid", "/reg:64")
                        .substringAfter("REG_SZ", "")
                        .trim()
                }

                os.startsWith("Mac") -> {
                    Regex("\"IOPlatformUUID\" = \"([^\"]+)\"")
                        .find(runCommand("ioreg", "-rd1", "-c", "IOPlatformExpertDevice"))
                        ?.groupValues
                        ?.get(1)
                }

                else -> {
                    listOf("/etc/machine-id", "/var/lib/dbus/machine-id")
                        .firstNotNullOfOrNull { path -> File(path).takeIf { it.canRead() }?.readText()?.trim() }
                }
            }
        }.getOrNull()
    if (raw.isNullOrBlank()) return null
    return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
}

private fun desktopOs(): OperatingSystem {
    val osName = System.getProperty("os.name").orEmpty()
    val osVersion = System.getProperty("os.version")
    return runCatching {
        OperatingSystem().apply {
            when {
                osName.startsWith("Windows") -> {
                    val reg = runCommand("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "/reg:64")

                    fun value(key: String) = Regex("^\\s+$key\\s+REG_\\w+\\s+(\\S+)", RegexOption.MULTILINE).find(reg)?.groupValues?.get(1)
                    val ubr = value("UBR")?.removePrefix("0x")?.toIntOrNull(16)
                    name = "Windows"
                    version = listOfNotNull(osName.removePrefix("Windows "), value("DisplayVersion") ?: value("ReleaseId")).joinToString(" ")
                    build = value("CurrentBuild")?.let { if (ubr != null) "$it.$ubr" else it }
                }

                osName.startsWith("Mac") -> {
                    val plist = File("/System/Library/CoreServices/.SystemVersionPlatform.plist").readText()

                    fun value(key: String) = Regex("<key>$key</key>\\s*<string>([^<]+)</string>").find(plist)?.groupValues?.get(1)
                    name = "macOS"
                    version = value("ProductVersion")
                    build = value("ProductBuildVersion")
                }

                else -> {
                    val release =
                        listOf("/etc/os-release", "/usr/lib/os-release")
                            .firstNotNullOfOrNull { path -> File(path).takeIf { it.canRead() }?.readLines() }
                            .orEmpty()
                            .associate { it.substringBefore('=') to it.substringAfter('=').trim('"', '\'') }
                    name = release["NAME"] ?: osName
                    version = release["VERSION_ID"]
                    kernelVersion = osVersion
                }
            }
        }
    }.getOrElse {
        OperatingSystem().apply {
            name = osName
            version = osVersion
        }
    }
}

private fun runCommand(vararg command: String): String =
    ProcessBuilder(*command)
        .start()
        .inputStream
        .bufferedReader()
        .use { it.readText() }
