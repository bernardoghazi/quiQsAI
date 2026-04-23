package com.quiqs.shared.llm

/**
 * Platform-neutral description of a function the model may call.
 *
 * Tools live in commonMain so they can be written against [com.quiqs.shared.QRepository]
 * and other shared types. The Android `LlmEngine` actual bridges each [LlmTool] to a
 * LiteRT-LM `OpenApiTool`; other platforms can do the same. See `QueryTools` for the
 * concrete tools exposed to query-mode prompts.
 *
 * @property name Function name the model will use when invoking the tool.
 * @property description Short rationale the model uses to decide when to call this tool.
 * @property parametersJsonSchema JSON Schema (OpenAPI-style) describing the arguments
 *   object the model will emit. Whitespace doesn't matter.
 * @property execute Runs the tool. Receives the raw JSON arguments string produced by
 *   the model and returns the JSON response string fed back into the conversation.
 */
class LlmTool(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
    val execute: suspend (String) -> String,
)
