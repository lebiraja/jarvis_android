package com.jarvis.jarvis.stt

import android.content.Context
import android.media.AudioRecord
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoskRecognizer(private val context: Context) {

    private var model: Model? = null
    var isReady = false
        private set

    fun initialize(onReady: () -> Unit, onError: (Exception) -> Unit) {
        StorageService.unpack(context, "model-en-us", "model",
            { unpackedModel ->
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        model = unpackedModel
                        isReady = true
                        Log.i("VoskRecognizer", "Model loaded successfully")
                        withContext(Dispatchers.Main) {
                            onReady()
                        }
                    } catch (e: Exception) {
                        Log.e("VoskRecognizer", "Error creating model", e)
                        withContext(Dispatchers.Main) {
                            onError(e)
                        }
                    }
                }
            },
            { exception ->
                Log.e("VoskRecognizer", "Failed to unpack model from assets/model-en-us folder", exception)
                onError(exception)
            }
        )
    }

    suspend fun listenForCommand(audioRecord: AudioRecord): String = withContext(Dispatchers.IO) {
        var transcript = ""
        val recognizer = model?.let { Recognizer(it, 16000.0f) } ?: return@withContext ""
        
        try {
            val bufferSize = Math.round(16000 * 0.2f)
            val b = ShortArray(bufferSize)
            var sentenceComplete = false
            
            // Do not stop/start audioRecord here if it's already actively recording
            // audioRecord.startRecording()
            
            val startTime = System.currentTimeMillis()
            val maxListenTimeMs = 8000 
            
            while (!sentenceComplete && (System.currentTimeMillis() - startTime) < maxListenTimeMs) {
                val nread = audioRecord.read(b, 0, b.size)
                if (nread > 0) {
                    if (recognizer.acceptWaveForm(b, nread)) {
                        sentenceComplete = true
                        val result = recognizer.result
                        val json = JSONObject(result)
                        transcript = json.optString("text", "")
                    }
                }
            }
            
            if (!sentenceComplete) {
                val finalResult = recognizer.finalResult
                val json = JSONObject(finalResult)
                transcript = json.optString("text", "")
            }
        } catch (e: Exception) {
            Log.e("VoskRecognizer", "STT error", e)
        } finally {
            recognizer.close()
        }
        
        Log.i("VoskRecognizer", "Transcript: $transcript")
        return@withContext transcript
    }
}
