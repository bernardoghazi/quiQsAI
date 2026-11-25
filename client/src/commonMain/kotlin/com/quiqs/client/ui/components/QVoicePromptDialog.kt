package com.quiqs.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Phases of the FAB voice-prompt flow shown by [QVoicePromptDialog].
 *
 * Lifecycle: [Recording] while the user holds the FAB → [Processing] while the LLM
 * streams its reply → [Done] or [Error] once the agent returns. Only the terminal
 * states are dismissable.
 */
sealed interface VoicePromptState {
    /** No dialog showing. */
    data object Idle : VoicePromptState
    /**
     * User is holding the FAB and audio is being captured. [transcript] is the live
     * speech-to-text partial (empty when the audio goes straight to the LLM).
     */
    data class Recording(val transcript: String = "") : VoicePromptState
    /**
     * Recorder stopped; LLM is streaming its response. [streaming] accumulates chunks;
     * [transcript] is the final speech-to-text result sent to the LLM, if any.
     */
    data class Processing(val streaming: String, val transcript: String = "") : VoicePromptState
    /** LLM finished. [message] is the dispatcher's success message. */
    data class Done(val message: String) : VoicePromptState
    /** Something failed (recorder, parser, agent). */
    data class Error(val message: String) : VoicePromptState
}

/**
 * Modal shown by the list screen during the FAB long-press voice flow.
 *
 * Renders a spinner while [state] is [VoicePromptState.Recording] or has no streaming
 * text yet, the streaming LLM output once chunks start arriving, and an OK button
 * (only) once [state] reaches [VoicePromptState.Done] or [VoicePromptState.Error].
 *
 * Recording/processing phases swallow dismiss gestures (back press, scrim tap) so the
 * user can't drop the dialog while audio capture or model inference is in flight.
 */
@Composable
fun QVoicePromptDialog(
    state: VoicePromptState,
    onDismiss: () -> Unit,
) {
    if (state is VoicePromptState.Idle) return
    val terminal = state is VoicePromptState.Done || state is VoicePromptState.Error
    AlertDialog(
        onDismissRequest = { if (terminal) onDismiss() },
        confirmButton = {
            if (terminal) TextButton(onClick = onDismiss) { Text("OK") }
        },
        title = {
            Text(
                when (state) {
                    is VoicePromptState.Recording -> "Listening…"
                    is VoicePromptState.Processing -> "Thinking…"
                    is VoicePromptState.Done -> "Done"
                    is VoicePromptState.Error -> "Something went wrong"
                    VoicePromptState.Idle -> ""
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (state) {
                    is VoicePromptState.Recording -> {
                        if (state.transcript.isNotEmpty()) Text("“${state.transcript}”")
                        Spinner()
                    }
                    is VoicePromptState.Processing -> {
                        if (state.transcript.isNotEmpty()) Text("“${state.transcript}”")
                        if (state.streaming.isEmpty()) Spinner()
                        else Text(
                            text = state.streaming,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                    is VoicePromptState.Done -> Text(state.message)
                    is VoicePromptState.Error -> Text(state.message)
                    VoicePromptState.Idle -> Unit
                }
            }
        },
    )
}

@Composable
private fun Spinner() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}
