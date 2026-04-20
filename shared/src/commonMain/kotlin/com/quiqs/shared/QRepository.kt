@file:OptIn(ExperimentalUuidApi::class)

package com.quiqs.shared

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.SqlDriver
import com.quiqs.shared.db.QDatabase
import com.quiqs.shared.db.QEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

interface QRepository {
    fun observeAll(): Flow<List<Q>>
    fun observeById(id: Uuid): Flow<Q?>
    suspend fun getAll(): List<Q>
    suspend fun create(
        text: String,
        tags: List<String> = emptyList(),
        alarms: List<Alarm> = emptyList(),
    ): Q
    suspend fun update(
        id: Uuid,
        text: String? = null,
        tags: List<String>? = null,
        alarms: List<Alarm>? = null,
    ): Q?
    suspend fun delete(id: Uuid)
}

private val json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

private val tagsSerializer = ListSerializer(String.serializer())
private val alarmsSerializer = ListSerializer(Alarm.serializer())

class SqlDelightQRepository(driver: SqlDriver) : QRepository {

    private val db = QDatabase(driver)
    private val queries = db.qQueries

    override fun observeAll(): Flow<List<Q>> =
        queries.selectAll().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map(::toDomain)
        }

    override fun observeById(id: Uuid): Flow<Q?> =
        queries.selectById(id.toString()).asFlow().mapToOneOrNull(Dispatchers.Default).map { row ->
            row?.let(::toDomain)
        }

    override suspend fun getAll(): List<Q> =
        queries.selectAll().executeAsList().map(::toDomain)

    override suspend fun create(text: String, tags: List<String>, alarms: List<Alarm>): Q {
        val now = Clock.System.now()
        val q = Q(
            id = Uuid.random(),
            text = text,
            tags = tags,
            alarms = alarms,
            createdAt = now,
            updatedAt = now,
        )
        queries.insert(
            id = q.id.toString(),
            text = q.text,
            tagsJson = json.encodeToString(tagsSerializer, q.tags),
            alarmsJson = json.encodeToString(alarmsSerializer, q.alarms),
            createdAt = q.createdAt.toEpochMilliseconds(),
            updatedAt = q.updatedAt.toEpochMilliseconds(),
        )
        return q
    }

    override suspend fun update(
        id: Uuid,
        text: String?,
        tags: List<String>?,
        alarms: List<Alarm>?,
    ): Q? {
        val existing = queries.selectById(id.toString()).executeAsOneOrNull() ?: return null
        val now = Clock.System.now()
        val newText = text ?: existing.text
        val newTagsJson = tags?.let { json.encodeToString(tagsSerializer, it) } ?: existing.tagsJson
        val newAlarmsJson = alarms?.let { json.encodeToString(alarmsSerializer, it) } ?: existing.alarmsJson
        queries.updateFields(
            text = newText,
            tagsJson = newTagsJson,
            alarmsJson = newAlarmsJson,
            updatedAt = now.toEpochMilliseconds(),
            id = existing.id,
        )
        return toDomain(
            existing.copy(
                text = newText,
                tagsJson = newTagsJson,
                alarmsJson = newAlarmsJson,
                updatedAt = now.toEpochMilliseconds(),
            )
        )
    }

    override suspend fun delete(id: Uuid) {
        queries.deleteById(id.toString())
    }

    private fun toDomain(row: QEntity): Q = Q(
        id = Uuid.parse(row.id),
        text = row.text,
        tags = json.decodeFromString(tagsSerializer, row.tagsJson),
        alarms = json.decodeFromString(alarmsSerializer, row.alarmsJson),
        createdAt = Instant.fromEpochMilliseconds(row.createdAt),
        updatedAt = Instant.fromEpochMilliseconds(row.updatedAt),
    )
}
