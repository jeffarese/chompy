package com.jeffarese.rexmobile.model

import java.io.File

/**
 * How each return to the app went, and each change of network while in it: how long it was away,
 * whether each host answered, and how long each terminal took to be live again. Kept in a file for
 * Settings to show and copy, since the phone has no logcat attached. An entry is a few lines
 * without blank ones; the newest comes first.
 */
class ReturnLog(private val file: File) {
    private val lock = Any()

    fun add(entry: String) = synchronized(lock) {
        val older = read()?.split(SEPARATOR).orEmpty().take(MAX_ENTRIES - 1)
        runCatching { file.writeText((listOf(entry.trimEnd()) + older).joinToString(SEPARATOR)) }
        Unit
    }

    /** The returns logged, newest first; null when there are none. */
    fun read(): String? = synchronized(lock) {
        runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val SEPARATOR = "\n\n"
        const val MAX_ENTRIES = 40
    }
}
