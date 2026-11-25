package com.quiqs.shared.llm

import com.quiqs.shared.Alarm
import com.quiqs.shared.Priority
import com.quiqs.shared.QFilterSort
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The structured JSON command the LLM is instructed to emit.
 *
 * Every prompt in [LlmPromptBuilder] asks the model for exactly one JSON object of this
 * shape. [LlmCommandParser] decodes it; [QCommandDispatcher] applies it. Variants align
 * with the app's UI modes (`create`, `update`, `delete`, `answer`).
 *
 * Polymorphic discriminator: `"type"`.
 */
@Serializable
sealed interface QCommand {

    /** Create a new Q. */
    @Serializable
    @SerialName("create")
    data class Create(
        val text: String,
        val tags: List<String> = emptyList(),
        val alarms: List<Alarm> = emptyList(),
        val priority: Priority = Priority.MEDIUM,
        val completed: Boolean = false,
    ) : QCommand

    /**
     * Partially update an existing Q. Fields left `null` are kept as-is — the prompt
     * instructs the model to omit unchanged fields.
     *
     * @property id String form of the target Q's [kotlin.uuid.Uuid].
     */
    @Serializable
    @SerialName("update")
    data class Update(
        val id: String,
        val text: String? = null,
        val tags: List<String>? = null,
        val alarms: List<Alarm>? = null,
        val priority: Priority? = null,
        val completed: Boolean? = null,
    ) : QCommand

    /** Delete an existing Q by UUID string. */
    @Serializable
    @SerialName("delete")
    data class Delete(val id: String) : QCommand

    /**
     * Answer a user question about their Qs. Emitted by query-mode prompts after the
     * model has called the lookup tools from `QueryTools`.
     */
    @Serializable
    @SerialName("answer")
    data class Answer(val text: String) : QCommand

    /**
     * Apply a filter+sort projection to the Q list. Read-only — the dispatcher hands
     * the spec back to the caller (see `QCommandResult.FilterSorted`) instead of
     * mutating any persisted state.
     */
    @Serializable
    @SerialName("filterSort")
    data class FilterSort(val spec: QFilterSort) : QCommand
}
