package com.quiqs.shared

import kotlinx.serialization.Serializable

@Serializable
data class Q(
    val id: String,
    val text: String,
    val tags: List<String> = emptyList(),
)
