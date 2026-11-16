package com.quiqs.shared.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.quiqs.shared.Alarm
import com.quiqs.shared.Priority
import com.quiqs.shared.Q
import com.quiqs.shared.QRepository
import com.quiqs.shared.nextRecurringInstant
import java.io.File
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Manual harness: runs text prompts through the real Gemma model and the app's own
 * prompt → parser → alarm-decoder pipeline, then writes what came out.
 *
 * Skipped unless `QUIQS_MODEL` points at a `.litertlm` file. `QUIQS_OUT` is the report
 * path and `QUIQS_PROMPTS` an optional file with one prompt per line (default list below).
 * Uses the CPU backend and the Android engine's greedy sampling, so results track the
 * phone's up to numeric differences between backends. The clock is frozen to Monday
 * 2026-11-16 15:22 in America/Sao_Paulo so alarm dates are reproducible.
 */
class TextPromptHarness {

    private val tz = TimeZone.of("America/Sao_Paulo")
    private val now = Instant.parse("2026-11-16T15:22:00-03:00")

    @Test
    fun runPrompts() {
        val modelPath = System.getenv("QUIQS_MODEL") ?: return
        val out = File(System.getenv("QUIQS_OUT") ?: "text-prompt-report.txt")
        val prompts = System.getenv("QUIQS_PROMPTS")?.let { File(it).readLines().filter(String::isNotBlank) }
            ?: DEFAULT_PROMPTS

        val engine = CpuEngine(modelPath)
        val agent = QAgent(engine, NoRepository, LlmPromptBuilder(clock = { now }, timeZone = tz))
        val report = StringBuilder("Now: $now ($tz, ${local(now)})\n\n")
        try {
            for (prompt in prompts) {
                report.appendLine("PROMPT: $prompt")
                val started = Clock.System.now()
                val outcome = runBlocking { agent.newOrFilterFromTextStreaming(prompt) }
                val secs = (Clock.System.now() - started).inWholeMilliseconds / 1000.0
                when (outcome) {
                    is QAgentParseOutcome.Parsed -> {
                        report.appendLine("  raw:    ${outcome.raw.trim()}")
                        report.appendLine("  parsed: ${describe(outcome.command)}")
                        (outcome.command as? QCommand.Create)?.alarms?.forEach { report.appendLine("    ${describe(it)}") }
                    }
                    is QAgentParseOutcome.ParseFailed -> {
                        report.appendLine("  raw:    ${outcome.raw.trim()}")
                        report.appendLine("  FAILED: ${outcome.reason}")
                    }
                }
                report.appendLine("  (${"%.1f".format(secs)}s, ${agent.lastMetrics?.chunks ?: "?"} chunks)\n")
                out.writeText(report.toString())
            }
        } finally {
            engine.close()
        }
    }

    private fun local(i: Instant): String {
        val l = i.toLocalDateTime(tz)
        return "${l.date} ${l.dayOfWeek.name.take(3)} ${l.hour.toString().padStart(2, '0')}:${l.minute.toString().padStart(2, '0')}"
    }

    private fun describe(c: QCommand): String = when (c) {
        is QCommand.Create -> "create text=\"${c.text}\" priority=${c.priority} tags=${c.tags} alarms=${c.alarms.size}"
        is QCommand.Update -> "update $c"
        is QCommand.FilterSort -> "filterSort ${c.spec}"
        is QCommand.Answer -> "answer ${c.text}"
        is QCommand.Delete -> "delete ${c.id}"
    }

    private fun describe(a: Alarm): String = when (a) {
        is Alarm.OneOffTime -> "one-off  ${local(a.at)}"
        is Alarm.RecurringTime -> {
            val next = generateSequence(nextRecurringInstant(a, now, tz)) { nextRecurringInstant(a, it, tz) }
                .take(4).joinToString(" | ") { local(it) }
            "recurring ${a.rule}  start=${local(a.dtStart)}  until=${a.until?.let(::local)}  count=${a.count}\n" +
                "      next firings: $next"
        }
        is Alarm.Geofence -> "geofence $a"
    }

    private class CpuEngine(modelPath: String) : LlmEngine {
        private val engine = Engine(
            EngineConfig(modelPath = modelPath, backend = Backend.CPU(), maxNumTokens = 4096),
        ).also { it.initialize() }

        @Volatile
        override var lastMetrics: LlmMetrics? = null
            private set

        override suspend fun generate(
            prompt: String,
            systemInstruction: String?,
            audioPath: String?,
            tools: List<LlmTool>,
            onChunk: (String) -> Unit,
        ): String {
            val config = ConversationConfig(
                systemInstruction = systemInstruction?.let { Contents.of(Content.Text(it)) },
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            )
            return engine.createConversation(config).use { conversation ->
                val buffer = StringBuilder()
                val tracker = JsonBalanceTracker()
                var chunks = 0
                conversation.sendMessageAsync(Contents.of(Content.Text(prompt))).transformWhile { chunk ->
                    val piece = chunk.toString()
                    emit(piece)
                    !tracker.consume(piece)
                }.collect {
                    chunks++
                    buffer.append(it)
                    onChunk(it)
                }
                lastMetrics = LlmMetrics(false, systemInstruction?.length ?: 0, prompt.length, 0, 0, chunks)
                buffer.toString()
            }
        }

        override fun close() = engine.close()
    }

    private object NoRepository : QRepository {
        override fun observeAll(): Flow<List<Q>> = flowOf(emptyList())
        override fun observeById(id: Uuid): Flow<Q?> = flowOf(null)
        override suspend fun getAll(): List<Q> = emptyList()
        override suspend fun getById(id: Uuid): Q? = null
        override suspend fun create(
            text: String, tags: List<String>, alarms: List<Alarm>, priority: Priority,
            completed: Boolean, voiceNotePath: String?,
        ): Q = error("harness is parse-only")
        override suspend fun update(
            id: Uuid, text: String?, tags: List<String>?, alarms: List<Alarm>?,
            priority: Priority?, completed: Boolean?,
        ): Q? = error("harness is parse-only")
        override suspend fun setVoiceNote(id: Uuid, path: String?): Q? = error("harness is parse-only")
        override suspend fun delete(id: Uuid) = Unit
    }

    private companion object {
        val DEFAULT_PROMPTS = listOf(
            "Remind me to call the doctor tomorrow at noon",
            "Every Monday, Wednesday and Friday at 5pm take out the trash",
            "In 45 minutes check the oven",
            "Pay rent on the 5th of every month",
            "Cue me on the second Sunday of every month at 10am to call mom",
            "Every two weeks on Tuesday and Thursday at 8am until the end of January, water the plants",
            "Urgent: submit the tax form on December 20th at 9am",
            "Every weekday at 9am daily standup",
            "Take vitamins every day at 8am and 8pm",
            "me lembra todo dia às 7 da manhã de tomar remédio",
            "amanhã às 6 da manhã me lembra de fazer exame de sangue às 9",
            "buy milk",
            "show only high priority",
            "sort by next alarm",
            "what's due this week",
            "remind me every month on the last Friday to submit the report",
            "water the plants every 3 days at 7pm",
        )
    }
}
