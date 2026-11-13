package com.nova.browser.devtools

/**
 * Console message model + in-memory store — pure Kotlin, JVM-testable.
 *
 * Fed from WebChromeClient.onConsoleMessage (main thread). The store is
 * still synchronized: the REPL and the UI read it from composition while
 * messages arrive, and the cap keeps memory bounded at [MAX_ENTRIES].
 */
data class ConsoleMessage(
    val level: String, // "log" | "info" | "warn" | "error" | "debug"
    val message: String,
    val sourceId: String?,
    val lineNumber: Int
) {
    companion object {
        /** Normalize a WebChromeClient messageLevel name ("LOG", "ERROR", ...) */
        fun normalizeLevel(raw: String?): String {
            return when (raw?.uppercase()) {
                "ERROR" -> "error"
                "WARNING" -> "warn"
                "TIP" -> "info"
                "LOG" -> "log"
                "DEBUG" -> "debug"
                else -> "log"
            }
        }
    }
}

class ConsoleStore {

    companion object {
        const val MAX_ENTRIES = 200
    }

    private val lock = Any()
    private val messages = ArrayDeque<ConsoleMessage>()

    /** Add one message; the oldest drops off past [MAX_ENTRIES]. Never throws. */
    fun add(level: String, message: String, sourceId: String?, lineNumber: Int) {
        synchronized(lock) {
            messages.addLast(
                ConsoleMessage(
                    level = ConsoleMessage.normalizeLevel(level),
                    message = message,
                    sourceId = sourceId,
                    lineNumber = lineNumber
                )
            )
            while (messages.size > MAX_ENTRIES) messages.removeFirst()
        }
    }

    /** Add a message the user (or the REPL) produced, e.g. "> document.title". */
    fun addLocal(level: String, message: String) {
        add(level, message, null, 0)
    }

    /** Oldest-first snapshot for the UI. [levelFilter] null/"" means all. */
    fun snapshot(levelFilter: String? = null): List<ConsoleMessage> = synchronized(lock) {
        if (levelFilter.isNullOrBlank() || levelFilter == "all") messages.toList()
        else messages.filter { it.level == levelFilter }
    }

    fun size(): Int = synchronized(lock) { messages.size }

    fun errorCount(): Int = synchronized(lock) { messages.count { it.level == "error" } }

    fun clear() = synchronized(lock) { messages.clear() }
}
