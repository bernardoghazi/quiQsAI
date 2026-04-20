package com.quiqs.client

import android.content.Context
import com.quiqs.shared.QRepository
import com.quiqs.shared.llm.LlmEngineFactory
import com.quiqs.shared.llm.LlmPromptBuilder
import com.quiqs.shared.llm.QAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import java.io.File

private const val MODEL_FILENAME = "gemma.litertlm"

fun createAgentProvider(context: Context, repository: QRepository): AgentProvider {
    val appContext = context.applicationContext
    return AgentProvider {
        withContext(Dispatchers.IO) {
            val modelFile = resolveModelFile(appContext) ?: return@withContext null
            val engine = LlmEngineFactory.create(modelFile.absolutePath)
            QAgent(
                engine = engine,
                repository = repository,
                promptBuilder = LlmPromptBuilder(clock = { Clock.System.now() }),
            )
        }
    }
}

private fun resolveModelFile(context: Context): File? {
    val external = context.getExternalFilesDir(null)?.let { File(it, MODEL_FILENAME) }
    if (external != null && external.exists()) return external
    val internal = File(context.filesDir, MODEL_FILENAME)
    return internal.takeIf { it.exists() }
}
