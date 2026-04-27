package com.quiqs.client

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Top-level navigation destinations in the app.
 *
 * The app uses a simple in-memory route model (no nav library) because there are only
 * three screens and routing is driven by a single `mutableStateOf<Route>` in [App].
 */
@OptIn(ExperimentalUuidApi::class)
sealed interface Route {
    /** The list of all Qs. Landing screen. */
    data object List : Route

    /**
     * The detail screen for a Q.
     * @property id `null` means "create a new Q" (the legacy manual-entry path).
     */
    data class Detail(val id: Uuid?) : Route

    /**
     * The LLM prompt screen. The [mode] determines the system prompt, button labels,
     * and which `QAgent` method is invoked on send.
     */
    data class Prompt(val mode: PromptMode) : Route
}

/**
 * What kind of LLM interaction the user opened the prompt screen to perform.
 *
 * Splitting this off from [Route] lets the prompt screen render the same UI for every
 * mode while dispatching to mode-specific agent calls.
 */
@OptIn(ExperimentalUuidApi::class)
sealed interface PromptMode {
    /** Modify the existing Q with [qId]. */
    data class Edit(val qId: Uuid) : PromptMode

    /** Ask a question about the user's Qs (read-only, uses query tools). */
    data object Query : PromptMode

    /**
     * Combined "new Q OR filter & sort" mode. The system prompt lists both commands
     * and a disambiguation rubric, letting the LLM decide which intent the prompt
     * matches. Used by the list screen's voice FAB.
     */
    data object NewOrFilter : PromptMode
}
