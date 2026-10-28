package dev.nero.tafh.data.db

import androidx.room.TypeConverter
import dev.nero.tafh.model.AuthMethod
import dev.nero.tafh.model.MultiplexerType
import dev.nero.tafh.model.Transport

class Converters {
    @TypeConverter fun fromTransport(value: Transport): String = value.name

    @TypeConverter fun toTransport(value: String): Transport = Transport.valueOf(value)

    @TypeConverter fun fromAuthMethod(value: AuthMethod): String = value.name

    @TypeConverter fun toAuthMethod(value: String): AuthMethod = AuthMethod.valueOf(value)

    @TypeConverter fun fromMultiplexer(value: MultiplexerType?): String? = value?.id

    @TypeConverter
    fun toMultiplexer(value: String?): MultiplexerType? =
        value?.let { MultiplexerType.fromPersistedValue(it) }
}
