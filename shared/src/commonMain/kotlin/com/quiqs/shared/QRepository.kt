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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

interface QRepository {
    fun observeAll(): Flow<List<Q>>
    fun observeById(id: Uuid): Flow<Q?>
    suspend fun create(text: String): Q
    suspend fun update(id: Uuid, text: String): Q?
    suspend fun delete(id: Uuid)
}

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

    override suspend fun create(text: String): Q {
        val now = Clock.System.now()
        val q = Q(
            id = Uuid.random(),
            text = text,
            createdAt = now,
            updatedAt = now,
        )
        queries.insert(
            id = q.id.toString(),
            text = q.text,
            createdAt = q.createdAt.toEpochMilliseconds(),
            updatedAt = q.updatedAt.toEpochMilliseconds(),
        )
        return q
    }

    override suspend fun update(id: Uuid, text: String): Q? {
        val existing = queries.selectById(id.toString()).executeAsOneOrNull() ?: return null
        val now = Clock.System.now()
        queries.updateText(
            text = text,
            updatedAt = now.toEpochMilliseconds(),
            id = existing.id,
        )
        return toDomain(existing.copy(text = text, updatedAt = now.toEpochMilliseconds()))
    }

    override suspend fun delete(id: Uuid) {
        queries.deleteById(id.toString())
    }

    private fun toDomain(row: QEntity): Q = Q(
        id = Uuid.parse(row.id),
        text = row.text,
        createdAt = Instant.fromEpochMilliseconds(row.createdAt),
        updatedAt = Instant.fromEpochMilliseconds(row.updatedAt),
    )
}
