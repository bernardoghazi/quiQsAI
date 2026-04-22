package com.quiqs.shared.llm

class LlmTool(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
    val execute: suspend (String) -> String,
)
