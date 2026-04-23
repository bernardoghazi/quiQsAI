@file:OptIn(ExperimentalUuidApi::class)

package com.quiqs.shared.llm

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
            val q = repository.create(command.text, command.tags, command.alarms)
            QCommandResult.Created(q.id, "Created “${q.text}”")
        }
        is QCommand.Update -> {
            val id = parseId(command.id)
                ?: return QCommandResult.Failed("Invalid id: ${command.id}")
            val q = repository.update(id, command.text, command.tags, command.alarms)
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
    }

    /** Parses a UUID string, returning `null` for malformed input (model hallucination guard). */
    private fun parseId(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()
}
