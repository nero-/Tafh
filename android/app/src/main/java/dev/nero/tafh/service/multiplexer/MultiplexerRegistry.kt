package dev.nero.tafh.service.multiplexer

import dev.nero.tafh.model.MultiplexerType

object MultiplexerRegistry {
    val defaultType: MultiplexerType = MultiplexerType.Tmux

    fun forType(type: MultiplexerType): Multiplexer? =
        when (type) {
            MultiplexerType.Tmux -> TmuxMultiplexer
            MultiplexerType.Zmx -> ZmxMultiplexer
            MultiplexerType.Zellij -> null
        }
}
