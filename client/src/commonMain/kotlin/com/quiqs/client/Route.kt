package com.quiqs.client

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
sealed interface Route {
    data object List : Route
    data class Detail(val id: Uuid?) : Route
}
