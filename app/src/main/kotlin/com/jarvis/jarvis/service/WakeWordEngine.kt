package com.jarvis.jarvis.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.*
import java.nio.FloatBuffer

class WakeWordEngine(private val context: Context) {

    private var onWakeWordDetected: (() -> Unit)? = null
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private val scope = CoroutineScope(Dispatchers.IO)

    private var ortEnvironment: OrtEnvironment? = null
    private var melSession: OrtSession? = null
    private var wwSession: OrtSession? = null

    // For keeping the past 16 frames of 96-dim melspectrograms
    private val featureRingBuffer = Array(16) { FloatArray(96) }
    private var framesCount = 0

    fun initialize(onDetected: () -> Unit) {
        this.onWakeWordDetected = onDetected
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            
            // NOTE: Ensure melspectrogram.onnx and hey_jarvis.onnx are placed in app/src/main/assets/
            val melBytes = context.assets.open("melspectrogram.onnx").readBytes()
            val wwBytes = context.assets.open("hey_jarvis.onnx").readBytes()
            
            melSession = ortEnvironment?.createSession(melBytes)
            wwSession = ortEnvironment?.createSession(wwBytes)

            Log.i("WakeWordEngine", "openWakeWord initialized via ONNX Runtime")
        } catch (e: Exception) {
            Log.e("WakeWordEngine", "Failed to load ONNX models. Did you place them in assets/ directory?", e)
        }
    }

    fun start() {
        if (melSession == null || wwSession == null) {
            Log.e("WakeWordEngine", "ONNX sessions not initialized")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("WakeWordEngine", "Microphone permission not granted")
            return
        }

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioRecord?.startRecording()
        resume()
    }

    private fun processAudioStep(pcmBuffer: ShortArray) {
        val env = ortEnvironment ?: return
        val melSess = melSession ?: return
        val wwSess = wwSession ?: return

        try {
            // 1. Convert PCM to Float (-1.0 to 1.0)
            val floatAudio = FloatArray(pcmBuffer.size) { i -> pcmBuffer[i] / 32768f }
            val audioBuffer = FloatBuffer.wrap(floatAudio)
            
            val audioTensor = OnnxTensor.createTensor(env, audioBuffer, longArrayOf(1, floatAudio.size.toLong()))
            val melResult = melSess.run(mapOf("audio" to audioTensor))
            
            // Mel features shape typically [1, 1, 96] or similar depending on the exact preprocessor ONNX
            val melOutput = melResult.get(0).value as Array<Array<FloatArray>>
            val features = melOutput[0][0] // The 96-dim vector for this 80ms chunk
            
            melResult.close()
            audioTensor.close()

            // 2. Add to ring buffer
            featureRingBuffer[framesCount % 16] = features
            framesCount++

            // Only run WW model when we have at least 16 frames
            if (framesCount >= 16) {
                // Flatten the ring buffer in chronological order
                val flattenedFeatures = FloatArray(16 * 96)
                for (i in 0 until 16) {
                    val frameIndex = (framesCount - 16 + i) % 16
                    System.arraycopy(featureRingBuffer[frameIndex], 0, flattenedFeatures, i * 96, 96)
                }

                val featureBuffer = FloatBuffer.wrap(flattenedFeatures)
                val wwTensor = OnnxTensor.createTensor(env, featureBuffer, longArrayOf(1, 16, 96))
                
                // Typical input name is 'input' but could be different in specific openWakeWord models
                val wwResult = wwSess.run(mapOf("inputs" to wwTensor))
                val wwOutput = wwResult.get(0).value as Array<FloatArray>
                val score = wwOutput[0][0]
                
                wwResult.close()
                wwTensor.close()

                if (score > 0.5f) { // Arbitrary threshold, tune as needed
                    Log.i("WakeWordEngine", "Wake word detected! Score: $score")
                    framesCount = 0 // Debounce by resetting buffer
                    scope.launch(Dispatchers.Main) {
                        onWakeWordDetected?.invoke()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WakeWordEngine", "Error processing audio step", e)
        }
    }

    fun stop() {
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun getAudioRecord(): AudioRecord? = audioRecord

    fun pause() {
        isListening = false
    }

    fun resume() {
        if (isListening) return
        isListening = true
        scope.launch {
            val samplesPerStep = 1280
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
