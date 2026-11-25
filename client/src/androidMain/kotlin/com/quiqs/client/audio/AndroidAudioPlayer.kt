package com.quiqs.client.audio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager

/**
 * Builds an Android [PlayerProvider] backed by [MediaPlayer]. No permission prompt —
 * playback of app-owned files needs none — so this just hands out a fresh player.
 */
fun createPlayerProvider(context: Context): PlayerProvider {
    val appContext = context.applicationContext
    return PlayerProvider { PlayerStatus.Available(AndroidAudioPlayer(appContext)) }
}

/**
 * Single-track [AudioPlayer]. Starts playing through the loudspeaker (USAGE_MEDIA), and
 * switches to the earpiece (USAGE_VOICE_COMMUNICATION + MODE_IN_COMMUNICATION) when the
 * proximity sensor reports the phone is at the user's ear — same UX as messaging apps'
 * voice-message playback.
 *
 * Routing change is implemented by recreating the [MediaPlayer] at the current playback
 * position with new [AudioAttributes]; MediaPlayer's audio attributes can't be swapped
 * after `prepare()`. While near the ear we also acquire a `PROXIMITY_SCREEN_OFF_WAKE_LOCK`
 * so the screen dims (preventing accidental touches and saving battery).
 */
private class AndroidAudioPlayer(private val context: Context) : AudioPlayer {

    private var session: PlaybackSession? = null

    override fun play(path: String, onComplete: () -> Unit) {
        // Stop any prior session before starting a new one — single-track semantics.
        stop()
        val s = PlaybackSession(
            context = context,
            path = path,
            onFinished = {
                if (session === it) session = null
                onComplete()
            },
        )
        session = s
        s.start()
    }

    override fun stop() {
        session?.release()
        session = null
    }
}

/**
 * One playback's state — owns the current [MediaPlayer], the proximity listener, the
 * proximity wake lock, and the saved [AudioManager] mode it restores on release.
 */
private class PlaybackSession(
    private val context: Context,
    private val path: String,
    private val onFinished: (PlaybackSession) -> Unit,
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var routingToEarpiece = false
    private var listenerRegistered = false
    private val originalAudioMode = audioManager?.mode ?: AudioManager.MODE_NORMAL
    private val originalSpeakerOn = audioManager?.isSpeakerphoneOn ?: false

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // Many devices report a coarse 0/maxRange (binary) reading; some report a
            // continuous distance in cm. Treat anything below maxRange-and-5cm as "near".
            val maxRange = proximitySensor?.maximumRange ?: 5f
            val v = event.values.firstOrNull() ?: return
            val near = v < maxRange && v < 5f
            if (near != routingToEarpiece) routeTo(earpiece = near)
        }
        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        startPlayer(toEarpiece = false, seekToMs = 0)
        if (proximitySensor != null) {
            sensorManager?.registerListener(
                proximityListener,
                proximitySensor,
                SensorManager.SENSOR_DELAY_NORMAL,
            )
            listenerRegistered = true
        }
    }

    fun release() {
        if (listenerRegistered) {
            runCatching { sensorManager?.unregisterListener(proximityListener) }
            listenerRegistered = false
        }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { player?.release() }
        player = null
        // Restore whatever the system was in before we tampered with it. Safe to call
        // even if we never touched these (we save them at construction).
        runCatching {
            audioManager?.mode = originalAudioMode
            audioManager?.isSpeakerphoneOn = originalSpeakerOn
        }
    }

    private fun startPlayer(toEarpiece: Boolean, seekToMs: Int) {
        runCatching { player?.release() }
        val mp = MediaPlayer()
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(
                    if (toEarpiece) AudioAttributes.USAGE_VOICE_COMMUNICATION
                    else AudioAttributes.USAGE_MEDIA,
                )
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(path)
            mp.prepare()
            if (seekToMs > 0) mp.seekTo(seekToMs)
            mp.setOnCompletionListener {
                runCatching { it.release() }
                if (player === it) player = null
                cleanupRoutingState()
                onFinished(this)
            }
            mp.start()
            player = mp
            routingToEarpiece = toEarpiece
        } catch (t: Throwable) {
            runCatching { mp.release() }
            cleanupRoutingState()
            onFinished(this)
        }
    }

    private fun routeTo(earpiece: Boolean) {
        val pos = runCatching { player?.currentPosition }.getOrNull() ?: 0
        if (earpiece) {
            // MODE_IN_COMMUNICATION + speakerphone-off is the combination that reliably
            // routes audio to the earpiece across Android versions; AudioAttributes alone
            // doesn't always force the route on older devices.
            runCatching {
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager?.isSpeakerphoneOn = false
            }
            runCatching {
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "quiQsAI:VoiceNotePlayback",
                )?.also { it.acquire(10 * 60 * 1000L) }
            }
        } else {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            wakeLock = null
            runCatching {
                audioManager?.mode = AudioManager.MODE_NORMAL
                audioManager?.isSpeakerphoneOn = originalSpeakerOn
            }
        }
        startPlayer(toEarpiece = earpiece, seekToMs = pos)
    }

    private fun cleanupRoutingState() {
        if (listenerRegistered) {
            runCatching { sensorManager?.unregisterListener(proximityListener) }
            listenerRegistered = false
        }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching {
            audioManager?.mode = originalAudioMode
            audioManager?.isSpeakerphoneOn = originalSpeakerOn
        }
    }
}
