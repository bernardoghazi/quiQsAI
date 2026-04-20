package com.quiqs.shared.llm

import com.quiqs.shared.Alarm
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface QCommand {

    @Serializable
    @SerialName("create")
    data class Create(
        val text: String,
        val tags: List<String> = emptyList(),
        val alarms: List<Alarm> = emptyList(),
    ) : QCommand

    @Serializable
    @SerialName("update")
    data class Update(
        val id: String,
        val text: String? = null,
        val tags: List<String>? = null,
        val alarms: List<Alarm>? = null,
    ) : QCommand

    @Serializable
    @SerialName("delete")
    data class Delete(val id: String) : QCommand

    @Serializable
    @SerialName("answer")
    data class Answer(val text: String) : QCommand
}
