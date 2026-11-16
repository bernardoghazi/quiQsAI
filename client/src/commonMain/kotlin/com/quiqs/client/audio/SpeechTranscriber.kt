package com.quiqs.client.audio

/**
 * Turns live microphone audio into text, as a separate step in front of the LLM.
 *
 * The recorder keeps writing the WAV voice note as before and also hands every PCM chunk
 * to an open [TranscriptionSession], so the same capture yields both the stored audio and
 * a transcript. PCM is 16 kHz mono 16-bit little-endian (what [AudioRecorder] produces).
 */
interface SpeechTranscriber {
    /**
     * Opens a session recognizing [language]. [onPartial] receives the running
     * transcript while the user is still speaking (main thread on Android).
     */
    suspend fun begin(language: SpeechLanguage, onPartial: (String) -> Unit): TranscriptionSession
}

/** Language the speech recognizer should expect. */
enum class SpeechLanguage(val label: String, val tag: String?) {
    /** Whatever language the device is set to. */
    Device("Device language", null),
    English("English", "en-US"),
    Portuguese("Português", "pt-BR"),

    /** Let the recognizer pick between English and Portuguese (support varies by device). */
    Auto("Auto: English / Português", null),
}

/** One utterance being transcribed. */
interface TranscriptionSession {
    /** Feeds a PCM chunk. Never blocks the caller; safe to call from the recorder thread. */
    fun write(pcm: ByteArray, length: Int)

    /**
     * Signals end of audio and waits for the final transcript. Returns the best text
     * available (possibly the last partial) or an empty string if nothing was recognised.
     * Throws if recognition failed outright.
     */
    suspend fun finish(): String

    /** Aborts the session and releases resources. Safe to call at any time. */
    fun cancel()
}

/** Result of asking the platform for a [SpeechTranscriber]. */
sealed interface TranscriberStatus {
    data class Available(val transcriber: SpeechTranscriber) : TranscriberStatus
    data class Unavailable(val reason: String) : TranscriberStatus
}

/** Lazy factory for a [SpeechTranscriber]. */
fun interface TranscriberProvider {
    suspend fun get(): TranscriberStatus
}

/** Stub provider for targets without speech-to-text wiring. */
val NoTranscriber: TranscriberProvider = TranscriberProvider {
    TranscriberStatus.Unavailable("Speech-to-text is not wired up on this platform.")
}

/** How a held-FAB voice prompt reaches the LLM. */
enum class VoicePipeline(val label: String) {
    /** Platform speech-to-text first, then the transcript goes to the LLM as text. */
    SpeechToText("On-device speech-to-text → LLM (text)"),

    /** The recorded audio goes straight to the multimodal LLM (original path). */
    LlmAudio("Audio straight to the LLM"),
}
