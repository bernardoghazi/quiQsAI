package com.quiqs.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
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

    /**
     * Fire-and-forget POST_NOTIFICATIONS request. Separate from [permissionLauncher] so
     * a notification prompt that's open while the user holds the mic doesn't collide
     * with the RECORD_AUDIO flow.
     */
    private val notificationsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result handled by the system; alarm notifications no-op if denied */ }

    /**
     * Holds the Q the alarm notification asked us to open. Read by Compose so changes
     * via [onNewIntent] (Activity reused while in background) propagate to [App] and
     * trigger the bottom-sheet open. Cleared by the consumer once the sheet is opened.
     */
    private var deepLinkQId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLinkQId = intent?.getStringExtra(EXTRA_OPEN_Q_ID)
        maybeRequestNotificationsPermission()
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
        setContent {
            App(
                repository = repository,
                agentProvider = agentProvider,
                recorderProvider = recorderProvider,
                playerProvider = playerProvider,
                initialOpenQId = deepLinkQId,
                onInitialOpenQIdConsumed = { deepLinkQId = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_Q_ID)?.let { deepLinkQId = it }
    }

    /**
     * On Android 13+ alarm notifications need `POST_NOTIFICATIONS`. We ask at launch so
     * the prompt appears before the user sets their first alarm. Android's system-level
     * rate limiting handles repeated calls — after a second denial the prompt is
     * silently suppressed and the user must enable it from Settings.
     */
    private fun maybeRequestNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        /** Intent extra: Uuid string of the Q whose detail sheet should open at launch. */
        const val EXTRA_OPEN_Q_ID = "com.quiqs.client.EXTRA_OPEN_Q_ID"
    }
}
