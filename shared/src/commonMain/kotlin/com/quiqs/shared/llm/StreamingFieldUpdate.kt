package com.quiqs.shared.llm

import com.quiqs.shared.Alarm
import com.quiqs.shared.Priority

/**
 * Per-field event emitted by [StreamingCommandExtractor] as the LLM streams its JSON
 * output. The UI uses these to fill the create sheet incrementally before the full
 * command has arrived.
 *
 * Only fields of the outermost JSON object are surfaced; nested structures are
 * decoded into a single update once their value becomes balanced.
 */
sealed interface StreamingFieldUpdate {
    /** The polymorphic `"type"` discriminator (e.g. `"create"`, `"filterSort"`). */
    data class Type(val value: String) : StreamingFieldUpdate

    data class Text(val value: String) : StreamingFieldUpdate
    data class Priority(val value: com.quiqs.shared.Priority) : StreamingFieldUpdate
    data class Tags(val value: List<String>) : StreamingFieldUpdate
    data class Alarms(val value: List<Alarm>) : StreamingFieldUpdate
    data class Completed(val value: Boolean) : StreamingFieldUpdate
}
