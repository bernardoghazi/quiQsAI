package com.quiqs.client.audio

/**
 * Plays back a recorded voice-note WAV file. Used by the bottom-sheet's "tap to play
 * voice note" pill.
 *
 * Single-track: starting playback while another track is in flight stops the previous
 * one. Implementations are responsible for routing logic (e.g. switching from speaker
 * to earpiece when the proximity sensor reports the phone is at the user's ear).
 */
interface AudioPlayer {
    /**
     * Begins playing the audio file at [path]. Returns immediately — playback runs in
     * the background. [onComplete] fires when the audio finishes naturally; it is NOT
     * called when [stop] aborts playback.
     */
    fun play(path: String, onComplete: () -> Unit = {})

    /** Halts any in-progress playback and releases playback resources. */
    fun stop()
}

/**
 * Result of asking the platform for a player. Mirrors [com.quiqs.client.audio.RecorderStatus]
 * — [Unavailable] is the only failure mode (no permissions are required for playback of
 * files the app already owns).
 */
sealed interface PlayerStatus {
    /** A working player is ready. */
    data class Available(val player: AudioPlayer) : PlayerStatus

    /** No player — [reason] is a user-facing explanation. */
    data class Unavailable(val reason: String) : PlayerStatus
}

/** Lazy factory for an [AudioPlayer]. Platform actuals build their MediaPlayer/AVPlayer. */
fun interface PlayerProvider {
    suspend fun get(): PlayerStatus
}

/** Stub provider used by targets without playback wiring (currently iOS / desktop). */
val NoPlayer: PlayerProvider = PlayerProvider {
    PlayerStatus.Unavailable("Audio playback is not wired up on this platform.")
}
