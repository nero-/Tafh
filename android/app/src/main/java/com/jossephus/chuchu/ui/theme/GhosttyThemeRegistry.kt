package com.jossephus.chuchu.ui.theme

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe cache over the bundled Ghostty theme assets.
 *
 * `getTheme` is called concurrently from the Compose main thread, the settings background parser
 * (all 463 assets) and the foreground-service notifier, so the asset list is volatile and the
 * parsed-theme cache is a [ConcurrentHashMap].
 *
 * Asset I/O is deliberately performed outside any lock: a background bulk parse must not stall the
 * main thread waiting for a single theme lookup.
 */
object GhosttyThemeRegistry {

    @Volatile private var themeNames: List<String> = emptyList()

    private val cache = ConcurrentHashMap<String, GhosttyTheme>()
    private val initLock = Any()

    fun init(context: Context) {
        if (themeNames.isNotEmpty()) return
        synchronized(initLock) {
            if (themeNames.isNotEmpty()) return
            themeNames = context.assets.list("themes")?.sorted() ?: emptyList()
        }
    }

    val availableThemeNames: List<String>
        get() = themeNames

    fun getTheme(context: Context, name: String): GhosttyTheme? {
        if (name !in themeNames) return null
        cache[name]?.let {
            return it
        }
        val content =
            runCatching { context.assets.open("themes/$name").bufferedReader().readText() }
                .getOrNull() ?: return null
        val theme = GhosttyTheme.parse(name, content)
        // A concurrent caller may have won the race; keep whichever value landed first so
        // every caller observes a single parsed instance.
        return cache.putIfAbsent(name, theme) ?: theme
    }
}
