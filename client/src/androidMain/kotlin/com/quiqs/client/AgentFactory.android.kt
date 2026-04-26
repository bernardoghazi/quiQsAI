package com.quiqs.client

import android.content.Context
import android.util.Log
import com.quiqs.shared.QRepository
import com.quiqs.shared.llm.LlmEngine
import com.quiqs.shared.llm.LlmEngineFactory
import com.quiqs.shared.llm.LlmPromptBuilder
import com.quiqs.shared.llm.QAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import java.io.File

private const val TAG = "AgentFactory"

/** Expected filename of the LiteRT-LM weights bundle (pushed to the device manually during dev). */
private const val MODEL_FILENAME = "gemma.litertlm"

/** Used to serialise engine creation so the expensive model load only happens once. */
private val engineMutex = Mutex()
private var cachedEngine: LlmEngine? = null

/**
 * Builds an Android-specific [AgentProvider]. Each `get()` returns the same cached
 * engine wrapped in a fresh [QAgent] tied to the supplied [repository].
 *
 * Discovery order for the model file: external-files dir first (where `adb push` lands
 * it during dev), then the internal files dir. The unavailable message lists the paths
 * checked so it's trivial to push the bundle to the right location.
 */
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
            val engine = engineMutex.withLock {
                cachedEngine ?: LlmEngineFactory.create(
                    modelPath = modelFile.absolutePath,
                    cacheDir = appContext.cacheDir.absolutePath,
                ).also { cachedEngine = it }
            }
            val agent = QAgent(
                engine = engine,
                repository = repository,
                promptBuilder = LlmPromptBuilder(clock = { Clock.System.now() }),
            )
            AgentStatus.Available(agent)
        }
    }
}

/**
 * Closes and discards the cached engine. Called from [MainActivity] on finishing
 * destruction so the GPU context is released promptly.
 */
fun releaseAgent() {
    val engine = cachedEngine ?: return
    cachedEngine = null
    try {
        engine.close()
    } catch (t: Throwable) {
        Log.w(TAG, "Engine close failed", t)
    }
}

private fun candidatePaths(context: Context): List<File> = listOfNotNull(
    context.getExternalFilesDir(null)?.let { File(it, MODEL_FILENAME) },
    File(context.filesDir, MODEL_FILENAME),
)
