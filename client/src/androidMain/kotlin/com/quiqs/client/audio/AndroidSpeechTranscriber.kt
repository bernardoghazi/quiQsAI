package com.quiqs.client.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale

private const val TAG = "SpeechTranscriber"

/** Matches [AndroidAudioRecorder]'s capture format. */
private const val SAMPLE_RATE = 16_000

/** How long [TranscriptionSession.finish] waits after end-of-audio before nudging the recognizer. */
private const val STOP_NUDGE_MS = 1_500L

/** Hard cap on waiting for a final result; past this we return the last partial. */
private const val FINISH_TIMEOUT_MS = 6_000L

/**
 * Builds a [TranscriberProvider] backed by Android's on-device [SpeechRecognizer].
 *
 * Needs Android 13+ (API 33) because audio is fed through
 * [RecognizerIntent.EXTRA_AUDIO_SOURCE] — a pipe the recorder writes into — instead of
 * letting the recognizer open the mic itself. That way the app's own recorder keeps
 * capturing the WAV voice note at the same time.
 */
fun createTranscriberProvider(context: Context): TranscriberProvider {
    val appContext = context.applicationContext
    return TranscriberProvider {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return@TranscriberProvider TranscriberStatus.Unavailable(
                "On-device speech-to-text needs Android 13 or newer.",
            )
        }
        val available = withContext(Dispatchers.Main) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        }
        if (!available) {
            TranscriberStatus.Unavailable("On-device speech recognition isn't available on this device.")
        } else {
            TranscriberStatus.Available(AndroidSpeechTranscriber(appContext))
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AndroidSpeechTranscriber(private val context: Context) : SpeechTranscriber {

    override suspend fun begin(
        language: SpeechLanguage,
        onPartial: (String) -> Unit,
    ): TranscriptionSession =
        withContext(Dispatchers.Main) {
            val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val intent = recognizerIntent(readEnd, language)
            val session = PipeSession(
                recognizer, intent, readEnd, writeEnd, language.describe(), onPartial,
            )
            recognizer.setRecognitionListener(session)
            recognizer.startListening(intent)
            session
        }

    private fun SpeechLanguage.describe(): String = tag ?: when (this) {
        SpeechLanguage.Auto -> "auto-detect (en-US, pt-BR)"
        else -> Locale.getDefault().toLanguageTag()
    }

    private fun recognizerIntent(audio: ParcelFileDescriptor, language: SpeechLanguage) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag ?: Locale.getDefault().toLanguageTag())
            if (language == SpeechLanguage.Auto) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                    arrayListOf("en-US", "pt-BR"),
                )
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // After a pause the recognizer starts a new segment; ask it to keep listening and
            // report each finished segment so earlier words aren't lost.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            // The user ends the utterance by releasing the FAB; discourage the recognizer
            // from ending it early on a pause.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
        }

    /**
     * One recognition over a pipe. PCM chunks are queued on an unbounded channel and a
     * background writer drains them into the pipe, so the recorder thread never blocks
     * even if the recognizer stops reading.
     */
    private class PipeSession(
        private val recognizer: SpeechRecognizer,
        private val intent: Intent,
        private val readEnd: ParcelFileDescriptor,
        writeEnd: ParcelFileDescriptor,
        private val languageName: String,
        private val onPartial: (String) -> Unit,
    ) : TranscriptionSession, RecognitionListener {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val pcm = Channel<ByteArray>(Channel.UNLIMITED)
        private val result = CompletableDeferred<String>()

        // Finished segments (a pause starts a new one) and the one still being spoken.
        // Written on the main thread; [snapshot] is the joined text, safe to read anywhere.
        private val committed = mutableListOf<String>()
        private var current = ""

        @Volatile
        private var snapshot = ""

        @Volatile
        private var released = false

        private fun refreshSnapshot() {
            snapshot = (committed + current).filter { it.isNotBlank() }.joinToString(" ")
        }

        private val writer: Job = scope.launch {
            ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                for (chunk in pcm) {
                    try {
                        out.write(chunk)
                    } catch (e: IOException) {
                        // Recognizer closed its end (finished or failed); drop the rest.
                        Log.w(TAG, "Pipe write failed: ${e.message}")
                        break
                    }
                }
            }
        }

        override fun write(pcm: ByteArray, length: Int) {
            if (released) return
            this.pcm.trySend(if (length == pcm.size) pcm else pcm.copyOf(length))
        }

        override suspend fun finish(): String {
            if (result.isCompleted) {
                Log.w(TAG, "Recognizer ended before the user released the button")
            }
            // Closing the channel lets the writer drain and close the pipe: EOF tells the
            // recognizer the utterance is over.
            pcm.close()
            try {
                val text = withTimeoutOrNull(STOP_NUDGE_MS) { result.await() }
                    ?: run {
                        withContext(Dispatchers.Main) { runCatching { recognizer.stopListening() } }
                        withTimeoutOrNull(FINISH_TIMEOUT_MS - STOP_NUDGE_MS) { result.await() }
                    }
                    ?: snapshot.also { Log.w(TAG, "No final result in time; using transcript so far") }
                return text.trim()
            } finally {
                release()
            }
        }

        override fun cancel() {
            pcm.close()
            result.cancel()
            scope.launch(Dispatchers.Main) { release() }
        }

        private suspend fun release() {
            if (released) return
            released = true
            withContext(Dispatchers.Main) {
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
            }
            runCatching { readEnd.close() }
            writer.cancel()
            scope.cancel()
        }

        // --- RecognitionListener (main thread) ---

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults.firstResult() ?: return
            if (text.isBlank()) return
            // Fallback for recognizers that restart their partials after a pause without
            // sending segment callbacks: a partial that is much shorter than the previous
            // one and isn't a prefix of it is a new segment, so keep what we had.
            if (current.isNotBlank() && text.length < current.length / 2 &&
                !current.startsWith(text, ignoreCase = true)
            ) {
                Log.d(TAG, "Partials restarted; keeping \"$current\" as a finished segment")
                committed += current
            }
            current = text
            refreshSnapshot()
            onPartial(snapshot)
        }

        /** A finished segment (the speaker paused). Needs `EXTRA_SEGMENTED_SESSION` support. */
        override fun onSegmentResults(segmentResults: Bundle) {
            val text = segmentResults.firstResult()?.takeIf { it.isNotBlank() } ?: current
            Log.d(TAG, "Segment finished: \"$text\"")
            if (text.isNotBlank()) committed += text
            current = ""
            refreshSnapshot()
            onPartial(snapshot)
        }

        override fun onEndOfSegmentedSession() {
            result.complete(snapshot)
        }

        override fun onResults(results: Bundle?) {
            // Non-segmented completion. If the recognizer's final text already contains the
            // earlier segments, use it alone; otherwise append it to what we kept.
            val text = results.firstResult()?.takeIf { it.isNotBlank() }
            val earlier = committed.joinToString(" ")
            result.complete(
                when {
                    text == null -> snapshot
                    earlier.isEmpty() || text.contains(earlier, ignoreCase = true) -> text
                    committed.lastOrNull() == text -> earlier
                    else -> "$earlier $text"
                },
            )
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> result.complete(snapshot)

                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    // The on-device model for this language isn't installed; ask the
                    // system to fetch it so a later attempt can succeed.
                    runCatching { recognizer.triggerModelDownload(intent) }
                    result.completeExceptionally(
                        IllegalStateException(
                            "Speech model for $languageName isn't installed " +
                                "(${errorName(error)}). A download was requested — try again later.",
                        ),
                    )
                }

                else -> result.completeExceptionally(
                    IllegalStateException("Speech recognition failed: ${errorName(error)}"),
                )
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun Bundle?.firstResult(): String? =
            this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    }
}

private fun errorName(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
    SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
    SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
    SpeechRecognizer.ERROR_SERVER -> "SERVER"
    SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
    SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
    SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
    else -> "error $code"
}
