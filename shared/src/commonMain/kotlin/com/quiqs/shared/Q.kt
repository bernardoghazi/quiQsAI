package com.quiqs.shared

import kotlinx.datetime.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class Q(
    val id: Uuid,
    val text: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
