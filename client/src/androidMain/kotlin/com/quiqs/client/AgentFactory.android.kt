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
            val candidates = candidatePaths(appContext)
            val modelFile = candidates.firstOrNull { it.exists() }
                ?: return@withContext AgentStatus.Unavailable(
                    "Model file not found. Push it to one of:\n" +
                        candidates.joinToString("\n") { "  • ${it.absolutePath}" },
                )
            val engine = LlmEngineFactory.create(
                modelPath = modelFile.absolutePath,
                cacheDir = appContext.cacheDir.absolutePath,
            )
            val agent = QAgent(
                engine = engine,
                repository = repository,
                promptBuilder = LlmPromptBuilder(clock = { Clock.System.now() }),
            )
            AgentStatus.Available(agent)
        }
    }
}

private fun candidatePaths(context: Context): List<File> = listOfNotNull(
    context.getExternalFilesDir(null)?.let { File(it, MODEL_FILENAME) },
    File(context.filesDir, MODEL_FILENAME),
)
