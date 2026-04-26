@file:OptIn(ExperimentalUuidApi::class)

package com.quiqs.shared.llm

import com.quiqs.shared.QFilterSort
import com.quiqs.shared.QRepository
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Outcome of dispatching a [QCommand] — carries the user-facing [message] and, where
 * applicable, the affected Q's id.
 */
sealed interface QCommandResult {
    /** Human-readable summary suitable for UI display. */
    val message: String

    /** Returned by [QCommand.Create]. */
    data class Created(val id: Uuid, override val message: String) : QCommandResult

    /** Returned by [QCommand.Update] when the target Q existed and was mutated. */
    data class Updated(val id: Uuid, override val message: String) : QCommandResult

    /** Returned by [QCommand.Delete] (even if the row didn't exist — delete is idempotent). */
    data class Deleted(val id: Uuid, override val message: String) : QCommandResult

    /** Returned by [QCommand.Answer] — wraps the model's free-text response. */
    data class Answered(override val message: String) : QCommandResult

    /** Returned by [QCommand.FilterSort] — read-only, carries the spec back to the UI. */
    data class FilterSorted(val spec: QFilterSort, override val message: String) : QCommandResult

    /** Catch-all for invalid ids or writes that couldn't be applied. */
    data class Failed(override val message: String) : QCommandResult
}

/**
 * Applies a parsed [QCommand] to the [QRepository]. This is the only place in the app
 * where LLM-produced instructions are allowed to mutate persistent state.
 */
class QCommandDispatcher(private val repository: QRepository) {

    /** Executes [command] against the repository and returns a [QCommandResult]. */
    suspend fun dispatch(command: QCommand): QCommandResult = when (command) {
        is QCommand.Create -> {
            val q = repository.create(
                text = command.text,
                tags = command.tags,
                alarms = command.alarms,
                priority = command.priority,
                completed = command.completed,
            )
            QCommandResult.Created(q.id, "Created “${q.text}”")
        }
        is QCommand.Update -> {
            val id = parseId(command.id)
                ?: return QCommandResult.Failed("Invalid id: ${command.id}")
            val q = repository.update(
                id = id,
                text = command.text,
                tags = command.tags,
                alarms = command.alarms,
                priority = command.priority,
                completed = command.completed,
            )
            if (q == null) QCommandResult.Failed("No Q with id $id")
            else QCommandResult.Updated(q.id, "Updated “${q.text}”")
        }
        is QCommand.Delete -> {
            val id = parseId(command.id)
                ?: return QCommandResult.Failed("Invalid id: ${command.id}")
            repository.delete(id)
            QCommandResult.Deleted(id, "Deleted $id")
        }
        is QCommand.Answer -> QCommandResult.Answered(command.text)
        is QCommand.FilterSort -> QCommandResult.FilterSorted(command.spec, describe(command.spec))
    }

    /** Short human-readable summary of [spec] used as the dispatcher's status message. */
    private fun describe(spec: QFilterSort): String {
        if (spec.isDefault) return "Cleared filter & sort"
        val parts = buildList {
            spec.text?.takeIf { it.isNotBlank() }?.let { add("text~“${it}”") }
            if (spec.tags.isNotEmpty()) add("tags=${spec.tags.joinToString(",")}")
            if (spec.priorities.isNotEmpty()) add("priority=${spec.priorities.joinToString(",") { it.wire }}")
            if (spec.alarm != QFilterSort.AlarmFilter.ANY) add("alarm=${spec.alarm.name.lowercase()}")
            if (spec.completed != QFilterSort.CompletedFilter.ANY) add("completed=${spec.completed.name.lowercase()}")
            add("sort=${spec.sortBy.name.lowercase()} ${spec.sortDir.name.lowercase()}")
        }
        return "Applied: ${parts.joinToString(", ")}"
    }

    /** Parses a UUID string, returning `null` for malformed input (model hallucination guard). */
    private fun parseId(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()
}
