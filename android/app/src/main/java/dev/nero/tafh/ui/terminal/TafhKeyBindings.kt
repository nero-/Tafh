package dev.nero.tafh.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class TafhHint(val key: String, val description: String)

class TafhKeyBindings(
    private val hints: List<TafhHint>,
    private val handlers: Map<Char, () -> Unit>,
) {
    var isPrefixActive: Boolean by mutableStateOf(false)
        private set

    fun togglePrefix() {
        isPrefixActive = !isPrefixActive
    }

    fun reset() {
        isPrefixActive = false
    }

    fun handleText(text: String): Boolean {
        if (!isPrefixActive || text.isBlank()) return false
        val key = text.first().lowercaseChar()
        handlers[key]?.invoke()
        isPrefixActive = false
        return true
    }

    fun hints(): List<TafhHint> = hints

    companion object {
        /**
         * Merge builtin-command shortcuts and per-custom-action shortcuts into a single set of
         * hints and key handlers.
         *
         * Builtin commands are processed first, in [BuiltinCommand] enum order, so their ordering
         * is deterministic and they take priority over custom actions bound to the same key. The
         * first binding for a key wins (settings already prevents builtin duplicates). Custom
         * actions that collide with a builtin key are dropped. When several custom actions share
         * one key, the handler defers to [onSelectAmongActions] instead of dispatching directly.
         *
         * The actual side effects live in the caller-supplied lambdas ([builtinCommandHandlers],
         * [onDispatchAction], [onSelectAmongActions]) so this factory stays free of
         * terminal/view-model state.
         */
        fun build(
            builtinShortcuts: Map<String, String>,
            builtinCommandHandlers: Map<BuiltinCommand, () -> Unit>,
            customGroups: List<TerminalCustomKeyGroup>,
            onDispatchAction: (TerminalCustomAction) -> Unit,
            onSelectAmongActions: (List<TerminalCustomAction>) -> Unit,
        ): TafhKeyBindings {
            val builtinHints = mutableListOf<TafhHint>()
            val builtinHandlers = mutableMapOf<Char, () -> Unit>()
            BuiltinCommand.entries.forEach { command ->
                val shortcut =
                    builtinShortcuts[command.id]?.takeIf { it.isNotEmpty() } ?: return@forEach
                val handler = builtinCommandHandlers[command] ?: return@forEach
                val keyChar = shortcut.first().lowercaseChar()
                if (keyChar in builtinHandlers) return@forEach
                builtinHandlers[keyChar] = handler
                builtinHints += TafhHint(key = shortcut, description = command.label)
            }

            val builtinKeys = builtinHandlers.keys.toSet()
            val customHints = mutableListOf<TafhHint>()
            val customHandlers = mutableMapOf<Char, () -> Unit>()
            val seenShortcuts = builtinKeys.toMutableSet()
            val shortcutActionsMap = mutableMapOf<Char, MutableList<TerminalCustomAction>>()
            customGroups.forEach { group ->
                group.actions.forEach { action ->
                    val shortcut = action.shortcut?.takeIf { it.length == 1 } ?: return@forEach
                    val keyChar = shortcut.first().lowercaseChar()
                    if (keyChar in builtinKeys) return@forEach
                    shortcutActionsMap.getOrPut(keyChar) { mutableListOf() }.add(action)
                }
            }
            shortcutActionsMap.forEach { (keyChar, actions) ->
                if (!seenShortcuts.add(keyChar)) return@forEach
                customHints +=
                    TafhHint(
                        key = keyChar.toString(),
                        description = "[${actions.joinToString(", ") { it.label }}]",
                    )
                customHandlers[keyChar] = {
                    if (actions.size == 1) {
                        onDispatchAction(actions.first())
                    } else {
                        onSelectAmongActions(actions)
                    }
                }
            }

            return TafhKeyBindings(
                hints = builtinHints + customHints,
                handlers = builtinHandlers + customHandlers,
            )
        }
    }
}
