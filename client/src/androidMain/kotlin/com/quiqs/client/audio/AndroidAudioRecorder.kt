package com.quiqs.client.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val SAMPLE_RATE = 16_000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
private const val BITS_PER_SAMPLE = 16
private const val CHANNELS = 1

fun createRecorderProvider(
    context: Context,
    requestPermission: suspend () -> Boolean,
): RecorderProvider {
    val appContext = context.applicationContext
    return RecorderProvider {
        if (!hasPermission(appContext)) {
            val granted = requestPermission()
            if (!granted) return@RecorderProvider RecorderStatus.PermissionRequired
        }
        RecorderStatus.Available(AndroidAudioRecorder(appContext))
    }
}

private fun hasPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private class AndroidAudioRecorder(private val context: Context) : AudioRecorder {

    private val mutex = Mutex()
    private var recorder: AudioRecord? = null
    private var job: Job? = null
    private var outputFile: File? = null

    @SuppressLint("MissingPermission")
    override suspend fun start() = mutex.withLock {
        check(recorder == null) { "Recorder already started" }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE * 2)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize,
        )
        val file = File(context.cacheDir, "prompt-${System.currentTimeMillis()}.wav")
        val scope = CoroutineScope(Dispatchers.IO)
        recorder = rec
        outputFile = file
        rec.startRecording()
        job = scope.launch {
            writePcmToWav(rec, file, bufferSize)
        }
    }

    override suspend fun stop(): String = mutex.withLock {
        val rec = recorder ?: error("Recorder not started")
        val file = outputFile ?: error("Output file missing")
        rec.stop()
        job?.cancelAndJoin()
        rec.release()
        recorder = null
        job = null
        outputFile = null
        file.absolutePath
    }

    override fun cancel() {
        val rec = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        job?.cancel()
        job = null
        file?.delete()
    }

    private suspend fun writePcmToWav(rec: AudioRecord, file: File, bufferSize: Int) {
        withContext(Dispatchers.IO) {
            FileOutputStream(file).use { fos ->
                val out = DataOutputStream(fos)
                writeWavHeader(out, dataSize = 0)
                val buffer = ByteArray(bufferSize)
                while (isActive) {
                    val read = rec.read(buffer, 0, buffer.size)
                    if (read > 0) out.write(buffer, 0, read)
                    else if (read < 0) break
                }
                out.flush()
            }
            patchWavHeader(file)
        }
    }

    private fun writeWavHeader(out: DataOutputStream, dataSize: Int) {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        val chunkSize = 36 + dataSize
        out.writeBytes("RIFF")
        out.writeIntLe(chunkSize)
        out.writeBytes("WAVE")
        out.writeBytes("fmt ")
        out.writeIntLe(16)
        out.writeShortLe(1)
        out.writeShortLe(CHANNELS)
        out.writeIntLe(SAMPLE_RATE)
        out.writeIntLe(byteRate)
        out.writeShortLe(blockAlign)
        out.writeShortLe(BITS_PER_SAMPLE)
        out.writeBytes("data")
        out.writeIntLe(dataSize)
    }

    private fun patchWavHeader(file: File) {
        val totalLen = file.length().toInt()
        val dataLen = totalLen - 44
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(intToLeBytes(totalLen - 8))
            raf.seek(40)
            raf.write(intToLeBytes(dataLen))
        }
    }

    private fun intToLeBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun DataOutputStream.writeIntLe(value: Int) {
        write(intToLeBytes(value))
    }

    private fun DataOutputStream.writeShortLe(value: Int) {
        write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
    }
}
