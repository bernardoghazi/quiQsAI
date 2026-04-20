package com.quiqs.client

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.quiqs.client.audio.createRecorderProvider
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {

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
        val recorderProvider = createRecorderProvider(
            context = applicationContext,
            requestPermission = {
                val deferred = CompletableDeferred<Boolean>()
                pendingPermission = deferred
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                deferred.await()
            },
        )
        setContent { App(repository, agentProvider, recorderProvider) }
    }
}
