package com.quiqs.client

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
sealed interface Route {
    data object List : Route
    data class Detail(val id: Uuid?) : Route
    data class Prompt(val mode: PromptMode) : Route
}

@OptIn(ExperimentalUuidApi::class)
sealed interface PromptMode {
    data object Create : PromptMode
    data class Edit(val qId: Uuid) : PromptMode
    data object Query : PromptMode
}
