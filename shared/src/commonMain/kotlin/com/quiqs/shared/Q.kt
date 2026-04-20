package com.quiqs.shared

import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

data class Q(
    val id: Uuid,
    val text: String,
    val tags: List<String> = emptyList(),
    val alarms: List<Alarm> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
)
