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

/**
 * Persistent store for [Q]s.
 *
 * Observers (`observeAll`/`observeById`) emit on every write, so UI layers can subscribe
 * once and react to mutations without re-querying.
 */
interface QRepository {
    /** Emits the full list of Qs on every change. */
    fun observeAll(): Flow<List<Q>>

    /** Emits the Q with [id] on every change, or `null` once after it is deleted. */
    fun observeById(id: Uuid): Flow<Q?>

    /** One-shot read of all Qs. Prefer [observeAll] in UI code. */
    suspend fun getAll(): List<Q>

    /** One-shot read of a single Q, or `null` if no row matches [id]. */
    suspend fun getById(id: Uuid): Q?

    /** Inserts a new Q with a fresh random UUID and `createdAt == updatedAt == now`. */
    suspend fun create(
        text: String,
        tags: List<String> = emptyList(),
        alarms: List<Alarm> = emptyList(),
        priority: Priority = Priority.MEDIUM,
        completed: Boolean = false,
    ): Q

    /**
     * Partially updates an existing Q. Any argument left `null` keeps the stored value.
     * Returns the updated [Q], or `null` if no row with [id] exists.
     */
    suspend fun update(
        id: Uuid,
        text: String? = null,
        tags: List<String>? = null,
        alarms: List<Alarm>? = null,
        priority: Priority? = null,
        completed: Boolean? = null,
    ): Q?

    /** Deletes the row with [id]. No-op if the row does not exist. */
    suspend fun delete(id: Uuid)
}

private val json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

private val tagsSerializer = ListSerializer(String.serializer())
private val alarmsSerializer = ListSerializer(Alarm.serializer())

/**
 * SQLDelight-backed [QRepository] used on every target (Android, iOS, desktop).
 *
 * Storage shape: each Q is a single row; [Q.tags] and [Q.alarms] are stored as
 * JSON strings so schema changes to those shapes don't require migrations.
 */
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

    override suspend fun getById(id: Uuid): Q? =
        queries.selectById(id.toString()).executeAsOneOrNull()?.let(::toDomain)

    override suspend fun create(
        text: String,
        tags: List<String>,
        alarms: List<Alarm>,
        priority: Priority,
        completed: Boolean,
    ): Q {
        val now = Clock.System.now()
        val q = Q(
            id = Uuid.random(),
            text = text,
            tags = tags,
            alarms = alarms,
            priority = priority,
            completed = completed,
            createdAt = now,
            updatedAt = now,
        )
        queries.insert(
            id = q.id.toString(),
            text = q.text,
            tagsJson = json.encodeToString(tagsSerializer, q.tags),
            alarmsJson = json.encodeToString(alarmsSerializer, q.alarms),
            priority = q.priority.wire,
            completed = if (q.completed) 1L else 0L,
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
        priority: Priority?,
        completed: Boolean?,
    ): Q? {
        val existing = queries.selectById(id.toString()).executeAsOneOrNull() ?: return null
        val now = Clock.System.now()
        val newText = text ?: existing.text
        val newTagsJson = tags?.let { json.encodeToString(tagsSerializer, it) } ?: existing.tagsJson
        val newAlarmsJson = alarms?.let { json.encodeToString(alarmsSerializer, it) } ?: existing.alarmsJson
        val newPriority = priority?.wire ?: existing.priority
        val newCompleted = completed?.let { if (it) 1L else 0L } ?: existing.completed
        queries.updateFields(
            text = newText,
            tagsJson = newTagsJson,
            alarmsJson = newAlarmsJson,
            priority = newPriority,
            completed = newCompleted,
            updatedAt = now.toEpochMilliseconds(),
            id = existing.id,
        )
        return toDomain(
            existing.copy(
                text = newText,
                tagsJson = newTagsJson,
                alarmsJson = newAlarmsJson,
                priority = newPriority,
                completed = newCompleted,
                updatedAt = now.toEpochMilliseconds(),
            )
        )
    }

    override suspend fun delete(id: Uuid) {
        queries.deleteById(id.toString())
    }

    /** Maps a raw SQLDelight row to the domain [Q], decoding the JSON-encoded columns. */
    private fun toDomain(row: QEntity): Q = Q(
        id = Uuid.parse(row.id),
        text = row.text,
        tags = json.decodeFromString(tagsSerializer, row.tagsJson),
        alarms = json.decodeFromString(alarmsSerializer, row.alarmsJson),
        priority = Priority.fromWire(row.priority),
        completed = row.completed != 0L,
        createdAt = Instant.fromEpochMilliseconds(row.createdAt),
        updatedAt = Instant.fromEpochMilliseconds(row.updatedAt),
    )
}
