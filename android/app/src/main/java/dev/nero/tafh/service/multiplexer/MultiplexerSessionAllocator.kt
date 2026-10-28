package dev.nero.tafh.service.multiplexer

object MultiplexerSessionAllocator {
    // `chuchu-` is the prefix used before the rename. Sessions created by an older
    // build are still running on the server, so both spellings stay reusable;
    // otherwise every upgrade silently orphans them and allocates `tafh-1` next to
    // them. New sessions are always created with `tafh-`.
    private val tafhSessionRegex = Regex("^(?:tafh|chuchu)-([1-9][0-9]*)$")

    fun reusableDetachedTafhSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String? {
        val localNames = localSessionNames.toSet()
        return remoteSessions
            .asSequence()
            .filter { session -> !session.attached && session.name !in localNames }
            .mapNotNull { session ->
                tafhSessionRegex
                    .matchEntire(session.name)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.let { index -> session.name to index }
            }
            .maxByOrNull { (_, index) -> index }
            ?.first
    }

    fun nextTafhSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String {
        val used = remoteSessions.map { it.name }.toSet() + localSessionNames
        var index = 1
        while ("tafh-$index" in used) index += 1
        return "tafh-$index"
    }
}
