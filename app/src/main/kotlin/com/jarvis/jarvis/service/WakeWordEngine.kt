package com.jarvis.jarvis.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import com.jarvis.jarvis.BuildConfig
import kotlinx.coroutines.*

class WakeWordEngine(private val context: Context) {

    private var onWakeWordDetected: (() -> Unit)? = null
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private val scope = CoroutineScope(Dispatchers.IO)

    private var porcupine: Porcupine? = null

    fun initialize(onDetected: () -> Unit) {
        this.onWakeWordDetected = onDetected
        try {
            val accessKey = BuildConfig.PORCUPINE_KEY
            if (accessKey.isBlank() || accessKey == "\"\"") {
                Log.e("WakeWordEngine", "PORCUPINE_KEY is missing from local.properties! Register on console.picovoice.ai to get a free key.")
                return
            }

            porcupine = Porcupine.Builder()
                .setAccessKey(accessKey)
                // Using the native built-in wake word "Porcupine". You can switch to JARVIS if you train it on the console!
                .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
                .setSensitivity(0.7f)
                .build(context)

            Log.i("WakeWordEngine", "Picovoice Porcupine initialized flawlessly")
        } catch (e: PorcupineException) {
            Log.e("WakeWordEngine", "Failed to load Porcupine ML model", e)
        }
    }

    fun start() {
        if (porcupine == null) {
            Log.w("WakeWordEngine", "Porcupine not initialized. You MUST add PORCUPINE_KEY=\"your_key_here\" to local.properties")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("WakeWordEngine", "Microphone permission not granted")
            return
        }

        try {
            val sampleRate = porcupine?.sampleRate ?: 16000
            val bufferSize = Math.max(
                AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ) * 2,
                (porcupine?.frameLength ?: 512) * 2
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            audioRecord?.startRecording()
            resume()
        } catch (e: Exception) {
            Log.e("WakeWordEngine", "Failed to start AudioRecord pipeline", e)
        }
    }

    private fun processAudioStep(pcmBuffer: ShortArray) {
        try {
            val keywordIndex = porcupine?.process(pcmBuffer) ?: -1
            if (keywordIndex == 0) {
                Log.i("WakeWordEngine", "Wake word detected!")
                scope.launch(Dispatchers.Main) {
                    onWakeWordDetected?.invoke()
                }
            }
        } catch (e: Exception) {
            Log.e("WakeWordEngine", "Error validating audio arrays", e)
        }
    }

    fun stop() {
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        porcupine?.delete()
    }

    fun getAudioRecord(): AudioRecord? = audioRecord

    fun pause() {
        isListening = false
    }

    fun resume() {
        if (isListening) return
        isListening = true
        scope.launch {
            val samplesPerStep = porcupine?.frameLength ?: 512
            val pcmBuffer = ShortArray(samplesPerStep)
            while (isListening && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val bytesRead = audioRecord?.read(pcmBuffer, 0, samplesPerStep) ?: 0
                if (bytesRead == samplesPerStep) {
                    processAudioStep(pcmBuffer)
                }
            }
        }
    }
}
