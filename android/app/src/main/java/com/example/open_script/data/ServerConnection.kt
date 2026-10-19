package com.example.open_script.data

/** Keeps stored credentials bound to their existing server when build defaults change. */
object ServerConnection {
    fun resolveOrigin(
        savedOrigin: String?,
        configuredOrigin: String,
        hasSession: Boolean,
        debug: Boolean,
    ): String = when {
        savedOrigin != null -> savedOrigin
        hasSession && debug -> "http://10.0.2.2:8000"
        hasSession -> ""
        else -> configuredOrigin
    }
}
