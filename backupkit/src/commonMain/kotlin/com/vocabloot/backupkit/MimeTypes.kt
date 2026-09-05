package com.vocabloot.backupkit

/** Mime type by file extension, case-insensitive. Unknown extensions are `application/octet-stream`. */
public fun defaultMimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "json" -> "application/json"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "txt" -> "text/plain"
    else -> "application/octet-stream"
}
