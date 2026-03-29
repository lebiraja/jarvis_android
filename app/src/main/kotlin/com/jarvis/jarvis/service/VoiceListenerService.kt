package com.jarvis.jarvis.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.jarvis.jarvis.nlu.IntentParser
import com.jarvis.jarvis.stt.VoskRecognizer
import com.jarvis.jarvis.contacts.ContactMatcher
import com.jarvis.jarvis.actions.CallAction
import com.jarvis.jarvis.ui.AudioFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceListenerService : LifecycleService() {

    private lateinit var wakeWordEngine: WakeWordEngine
    private lateinit var voskRecognizer: VoskRecognizer
    private lateinit var audioFeedback: AudioFeedback

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        
        audioFeedback = AudioFeedback(this)
        
        voskRecognizer = VoskRecognizer(this)
        voskRecognizer.initialize(
            onReady = { Log.i("VoiceListenerService", "Vosk Ready") },
            onError = { e -> Log.e("VoiceListenerService", "Vosk Init Error", e) }
        )

        wakeWordEngine = WakeWordEngine(this)
        wakeWordEngine.initialize {
            lifecycleScope.launch {
                onWakeWordDetected()
            }
        }
        wakeWordEngine.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Assistant Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis is listening")
            .setContentText("Waiting for wake word...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private suspend fun onWakeWordDetected() {
        Log.i("VoiceListenerService", "Wake Word Detected!")
        
        if (!voskRecognizer.isReady) {
            Log.w("VoiceListenerService", "Vosk not ready yet")
            return
        }

        wakeWordEngine.pause()
        
        withContext(Dispatchers.Main) {
            Toast.makeText(applicationContext, "Listening...", Toast.LENGTH_SHORT).show()
        }
        
        val record = wakeWordEngine.getAudioRecord()
        if (record != null) {
            val transcript = voskRecognizer.listenForCommand(record)
            if (transcript.isNotBlank()) {
                val intentResult = IntentParser.parse(transcript)
                if (intentResult != null && intentResult.intent == "make_call") {
                    Log.i("VoiceListenerService", "Parsed Intent: Call ${intentResult.contactQuery} (Speaker: ${intentResult.speakerphone})")
                    val contact = ContactMatcher.find(intentResult.contactQuery, this@VoiceListenerService)
                    
                    if (contact != null) {
                        Log.i("VoiceListenerService", "Matched: ${contact.name}")
                        withContext(Dispatchers.Main) {
                            audioFeedback.speak("Calling ${contact.name}")
                            Toast.makeText(applicationContext, "Calling ${contact.name}...", Toast.LENGTH_LONG).show()
                        }
                        CallAction.execute(this@VoiceListenerService, contact.number, intentResult.speakerphone)
                    } else {
                        Log.w("VoiceListenerService", "Contact not found")
                        withContext(Dispatchers.Main) {
                            audioFeedback.speak("Contact not found")
                            Toast.makeText(applicationContext, "Contact not found", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Log.w("VoiceListenerService", "No intent parsed from: $transcript")
                }
            }
        }

        wakeWordEngine.resume()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeWordEngine.stop()
        audioFeedback.shutdown()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "voice_assistant_channel"
    }
}
