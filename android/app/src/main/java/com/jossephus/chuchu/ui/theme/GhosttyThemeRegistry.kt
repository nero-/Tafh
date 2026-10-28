package com.jossephus.chuchu.ui.theme

import android.content.Context

/**
 * Thread-safe cache over the bundled Ghostty theme assets.
 *
 * `getTheme` may be called concurrently from the Compose main thread, the settings
 * background parser and the foreground service notifier, so both the asset list and the
 * parsed-theme cache are guarded.
 */
object GhosttyThemeRegistry {

    @Volatile
    private var themeNames: List<String> = emptyList()

    private val cache = HashMap<String, GhosttyTheme>()
    private val initLock = Any()
    private val cacheLock = Any()

    fun init(context: Context) {
        if (themeNames.isNotEmpty()) return
        synchronized(initLock) {
            if (themeNames.isNotEmpty()) return
            themeNames =
                context.assets.list("themes")
                    ?.sorted()
                    ?: emptyList()
        }
    }

    val availableThemeNames: List<String> get() = themeNames

    fun getTheme(context: Context, name: String): GhosttyTheme? {
        if (name !in themeNames) return null
        synchronized(cacheLock) {
            cache[name]?.let { return it }
            val content =
                runCatching {
                    context.assets.open("themes/$name").bufferedReader().readText()
                }.getOrNull() ?: return null
            val theme = GhosttyTheme.parse(name, content)
            cache[name] = theme
            return theme
        }
    }
}
