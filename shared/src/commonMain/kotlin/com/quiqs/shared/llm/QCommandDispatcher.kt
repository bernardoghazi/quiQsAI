@file:OptIn(ExperimentalUuidApi::class)

package com.quiqs.shared.llm

import com.quiqs.shared.QRepository
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

sealed interface QCommandResult {
    val message: String

    data class Created(val id: Uuid, override val message: String) : QCommandResult
    data class Updated(val id: Uuid, override val message: String) : QCommandResult
    data class Deleted(val id: Uuid, override val message: String) : QCommandResult
    data class Answered(override val message: String) : QCommandResult
    data class Failed(override val message: String) : QCommandResult
}

class QCommandDispatcher(private val repository: QRepository) {

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

    private fun parseId(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()
}
