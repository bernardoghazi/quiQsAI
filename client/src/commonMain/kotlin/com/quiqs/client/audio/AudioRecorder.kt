package com.quiqs.client.audio

/**
 * Captures microphone audio for voice-mode prompts.
 *
 * Lifecycle: [start] begins writing to a temp file; [stop] finalises the file and
 * returns its absolute path for the caller to hand off to [com.quiqs.shared.llm.LlmEngine].
 * [cancel] aborts and deletes the partial recording.
 */
interface AudioRecorder {
    /**
     * Begin recording. Fails if already recording.
     *
     * @param onPcm Optional tap that receives every captured PCM chunk (16 kHz mono
     *   16-bit LE) as it is written, e.g. to feed a [TranscriptionSession]. Called on the
     *   recorder's background thread; the array is a fresh copy the callee may keep.
     */
    suspend fun start(onPcm: ((ByteArray, Int) -> Unit)? = null)

    /** Finalise the recording and return the absolute path of the produced audio file. */
    suspend fun stop(): String

    /** Abort recording and delete the partial file. Safe to call at any time. */
    fun cancel()
}

/**
 * Result of asking the platform for a recorder. Mirrors [com.quiqs.client.AgentStatus]
 * in spirit but adds [PermissionRequired] so the UI can branch on the permission case.
 */
sealed interface RecorderStatus {
    /** A working recorder is ready. */
    data class Available(val recorder: AudioRecorder) : RecorderStatus

    /** No recorder — [reason] is a user-facing explanation. */
    data class Unavailable(val reason: String) : RecorderStatus

    /** The user denied the microphone permission. */
    data object PermissionRequired : RecorderStatus
}

/** Lazy factory for an [AudioRecorder]. Platform actuals handle permissions on first call. */
fun interface RecorderProvider {
    suspend fun get(): RecorderStatus
}

/** Stub provider used by targets without audio wiring. */
val NoRecorder: RecorderProvider = RecorderProvider {
    RecorderStatus.Unavailable("Audio recording is not wired up on this platform.")
}
