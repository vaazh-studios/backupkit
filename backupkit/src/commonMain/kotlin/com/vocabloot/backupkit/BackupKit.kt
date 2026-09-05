package com.vocabloot.backupkit

/** Severity of a [BackupKitLogger] line. */
public enum class LogLevel { Debug, Info, Warn }

/** Plug your logger in through [BackupKit.logger]. The default discards everything. */
public fun interface BackupKitLogger {
    public fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}

/** Library-wide switches. */
public object BackupKit {
    /** Receives every log line the library emits. Never receives file contents or tokens. */
    public var logger: BackupKitLogger = BackupKitLogger { _, _, _, _ -> }
}

internal fun logI(tag: String, message: () -> String) = BackupKit.logger.log(LogLevel.Info, tag, message(), null)

internal fun logW(tag: String, throwable: Throwable? = null, message: () -> String) =
    BackupKit.logger.log(LogLevel.Warn, tag, message(), throwable)
