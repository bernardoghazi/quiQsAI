package com.quiqs.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * User-set importance attached to a [Q]. Stored and exchanged with the LLM as the
 * lowercase string in [wire]; that string is also the SQLite column value.
 */
@Serializable
enum class Priority {
    @SerialName("high") HIGH,
    @SerialName("medium") MEDIUM,
    @SerialName("low") LOW;

    val wire: String
        get() = when (this) {
            HIGH -> "high"
            MEDIUM -> "medium"
            LOW -> "low"
        }

    companion object {
        fun fromWire(s: String): Priority = when (s) {
            "high" -> HIGH
            "low" -> LOW
            else -> MEDIUM
        }
    }
}
