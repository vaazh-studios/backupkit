package com.vocabloot.backupkit

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/** The Ktor client BackupKit uses when the app does not pass its own. */
public object BackupKitHttp {
    public fun default(): HttpClient = HttpClient(OkHttp)
}
