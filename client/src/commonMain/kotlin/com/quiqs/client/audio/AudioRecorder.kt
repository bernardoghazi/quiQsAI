package com.quiqs.client.audio

interface AudioRecorder {
    suspend fun start()
    suspend fun stop(): String
    fun cancel()
}

sealed interface RecorderStatus {
    data class Available(val recorder: AudioRecorder) : RecorderStatus
    data class Unavailable(val reason: String) : RecorderStatus
    data object PermissionRequired : RecorderStatus
}

fun interface RecorderProvider {
    suspend fun get(): RecorderStatus
}

val NoRecorder: RecorderProvider = RecorderProvider {
    RecorderStatus.Unavailable("Audio recording is not wired up on this platform.")
}
