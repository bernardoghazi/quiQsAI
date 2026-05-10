package com.quiqs.client

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.quiqs.client.audio.createPlayerProvider
import com.quiqs.client.audio.createRecorderProvider
import kotlinx.coroutines.CompletableDeferred

/**
 * Android entry point.
 *
 * Wires up the shared [App] with Android-specific providers for persistence, the LLM
 * engine, and the microphone. Permission prompts are bridged to suspending code via
 * [CompletableDeferred] so callers can `await()` the user's choice.
 *
 * Releases the cached LLM engine on finishing destruction to free GPU resources —
 * recreations are cheap compared to holding it across a kill.
 */
class MainActivity : ComponentActivity() {

    /** Outstanding permission request — completed by [permissionLauncher] on user response. */
    private var pendingPermission: CompletableDeferred<Boolean>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        pendingPermission?.complete(granted)
        pendingPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = createQRepository(applicationContext)
        val agentProvider = createAgentProvider(applicationContext, repository)
        lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY && isFinishing) {
                    releaseAgent()
                }
            },
        )
        val recorderProvider = createRecorderProvider(
            context = applicationContext,
            requestPermission = {
                val deferred = CompletableDeferred<Boolean>()
                pendingPermission = deferred
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                deferred.await()
            },
        )
        val playerProvider = createPlayerProvider(context = applicationContext)
        setContent { App(repository, agentProvider, recorderProvider, playerProvider) }
    }
}
