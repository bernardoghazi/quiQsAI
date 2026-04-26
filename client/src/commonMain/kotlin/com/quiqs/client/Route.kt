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
 * Splitting this off from [Route] lets the prompt screen render the same UI for all
 * three modes while dispatching to mode-specific agent calls (create, edit, query).
 */
@OptIn(ExperimentalUuidApi::class)
sealed interface PromptMode {
    /** Create a new Q from the prompt. */
    data object Create : PromptMode

    /** Modify the existing Q with [qId]. */
    data class Edit(val qId: Uuid) : PromptMode

    /** Ask a question about the user's Qs (read-only, uses query tools). */
    data object Query : PromptMode

    /**
     * Translate natural language into a [com.quiqs.shared.QFilterSort] spec applied to
     * the list screen. Read-only — no repository writes happen in this mode.
     */
    data object FilterSort : PromptMode
}
