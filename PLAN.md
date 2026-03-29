# VoiceCall Assistant — Android Project Plan
> A privacy-first, offline, always-on voice calling assistant for Android.  
> Better than Siri for calling. Better than Google Assistant. No confirmation dialogs.

---

## Project Overview

### What We're Building
An Android app that:
- Listens for a custom wake word 24/7 (phone locked or unlocked)
- Understands natural language calling commands ("Call Raj on speaker", "Ring mom", "Dial bro")
- Matches contacts using phonetic + fuzzy matching (no more "contact not found")
- Places calls instantly — **zero confirmation dialogs**
- Works 100% offline — no cloud, no latency, no privacy leaks
- Runs as a foreground service, survives background kill

### Why This Exists
Google Assistant asks for confirmation, mismatches contacts, depends on cloud, and is slow. Siri works because Apple controls the hardware + OS stack. We replicate Siri's pipeline entirely in user space on Android using open-source tools.

---

## How Siri & Google Work (Context for Claude Code)

### Siri Architecture
```
[DSP/Neural Engine Chip] → Wake Word (1-5mW, always on)
       ↓
[On-device ASR]          → Speech to Text (transformer model, offline since iOS 15)
       ↓
[On-device NLU]          → Intent: make_call | Slots: contact="Raj", speaker=true
       ↓
[SiriKit / OS APIs]      → INStartCallIntent → Places call, no permission gates
```
- Siri is NOT an app. It's `assistantd`, a system daemon that starts at boot and runs as root
- Wake word runs on a physically separate low-power chip (Neural Engine inside A-series SoC)
- Apple owns the full stack: chip + OS + assistant = no permission restrictions, no confirmations
- Pre-AI (2011–2017): Used HMM (Hidden Markov Models) + phoneme trees
- Post-2017: Small on-device RNN/LSTM → now transformer-based acoustic models

### Google Assistant Architecture
```
[DSP / SoundTrigger HAL] → "Hey Google" wake word (hardware abstraction layer)
       ↓
[Cloud STT]              → Audio sent to Google servers → text returned
       ↓
[Cloud NLU]              → Intent classification (partly still cloud)
       ↓
[Android Intent]         → ACTION_CALL → confirmation dialog (CALL_PHONE = dangerous permission)
```
- Google Assistant is a **privileged system app** in `/system/priv-app/` — not a normal APK
- On Pixel: uses Tensor chip DSP for wake word (low power)
- On Samsung/Xiaomi: falls back to software detection on main CPU (higher battery drain)
- Asks for confirmation because: Android is open (Google doesn't control OEMs), CALL_PHONE is a dangerous permission, cloud NLU has latency = safety buffer needed, liability hedge

### Resource Usage Comparison
| Metric | Siri | Google Assistant | Our App (Target) |
|--------|------|-----------------|-----------------|
| Wake word CPU | ~0% (Neural Engine) | 1–3% (DSP) or 5–8% (CPU) | ~2–4% (Porcupine on ARM) |
| Background RAM | 40–80 MB | 120–200 MB | ~60–90 MB |
| Network dependency | Low (on-device) | High (cloud NLU) | Zero |
| Battery drain | Negligible | Noticeable non-Pixel | Low (target) |
| Confirmation dialog | Never | Always | Never |

### What We Can and Cannot Do (Third-Party Reality)
```
Siri/Google CAN              We CAN (User Space)
─────────────────────        ──────────────────────────────
Run as OS daemon (root)  →   Foreground Service + START_STICKY
Use DSP directly         →   Android SoundTrigger API (limited) or software detection
Never get killed         →   Battery optimization exempt + OEM-specific handling
No permission gates      →   CALL_PHONE + RECORD_AUDIO declared in manifest
Access all system APIs   →   Standard Telecom SDK APIs
```
Our gap vs Siri: only the DSP chip (1mW vs our ~4% CPU). Everything else is matchable in software.

---

## Technical Architecture

### Full Pipeline
```
┌──────────────────────────────────────────────────────────────┐
│                    VoiceListenerService (Foreground)          │
│                                                               │
│  ┌─────────────┐    ┌──────────────┐    ┌─────────────────┐  │
│  │  Wake Word  │───▶│     STT      │───▶│   NLU / Intent  │  │
│  │ (Porcupine) │    │   (Vosk)     │    │  (Regex + LLM)  │  │
│  └─────────────┘    └──────────────┘    └────────┬────────┘  │
│                                                   │           │
│                                        ┌──────────▼────────┐  │
│                                        │  Contact Matcher  │  │
│                                        │ (Fuzzy+Phonetic)  │  │
│                                        └──────────┬────────┘  │
│                                                   │           │
│                                        ┌──────────▼────────┐  │
│                                        │   Call Executor   │  │
│                                        │ (TelecomManager)  │  │
│                                        └───────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

### Component Details

#### 1. Wake Word Engine — Porcupine (Picovoice)
- Library: `ai.picovoice:porcupine-android:3.0.1`
- Runs on-device, ~1% CPU on ARM processors
- Free tier: built-in keywords (Jarvis, Alexa, etc.)
- Custom wake word: trainable via Picovoice Console (free tier available)
- Processes 16kHz PCM audio in short frames
- Why Porcupine over alternatives:
  - openWakeWord: fully open but less tested on Android
  - Snowboy: deprecated
  - Custom ONNX model: possible but complex to train initially
- Alternative (fully FOSS): openWakeWord via ONNX Runtime Android

#### 2. Speech-to-Text — Vosk
- Library: `com.alphacephei:vosk-android:0.3.47`
- Model size: ~50MB (small-en) — fast, offline, accurate for short commands
- Why Vosk over Whisper: Whisper is ~150MB+ and slower for short 2–5 second commands; Vosk is optimized for real-time transcription
- Why not Android built-in STT: sends audio to Google servers, has latency, requires network

#### 3. NLU / Intent Parser — Dual-Mode
- **Primary: Regex rule engine** (fast, deterministic, no latency)
  - Covers: call/ring/dial, contact name extraction, speaker detection
  - Handles: "call X", "ring X", "dial X on speaker", "call X on speakerphone"
- **Fallback: Small on-device LLM** (for unusual phrasing)
  - Options: Gemma 2B via MediaPipe LLM Inference API, or Phi-3 Mini via ONNX Runtime
  - Handles: "give bro a ring", "patch me through to mom", "loudspeaker call to Raj"
  - Only activates when regex returns null

#### 4. Contact Matcher — FuzzyWuzzy + Phonetic
- Library: `me.xdrop:fuzzywuzzy:1.4.0`
- Algorithm stack:
  1. Exact name match (fastest path)
  2. `partialRatio` — handles "Raj" matching "Rajesh Kumar"
  3. `ratio` — direct string similarity
  4. Phonetic (Soundex) — "Rajes" → "Rajesh", "Mom" → stored nickname
- Confidence threshold: >70 score = proceed, <70 = "Contact not found" response
- Custom nicknames: user can register "mom", "dad", "bro" → phone numbers

#### 5. Call Executor — TelecomManager
- Direct API call: `TelecomManager.placeCall(uri, extras)`
- Speakerphone: `EXTRA_START_CALL_WITH_SPEAKERPHONE = true`
- No confirmation dialog because we call `placeCall()` directly vs `ACTION_CALL` intent
- Works from lock screen with `FLAG_SHOW_WHEN_LOCKED`

---

## Project Structure

```
VoiceCallAssistant/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── kotlin/com/yourname/voicecall/
│   │   │   │   ├── service/
│   │   │   │   │   ├── VoiceListenerService.kt      # Core foreground service
│   │   │   │   │   └── WakeWordEngine.kt            # Porcupine wrapper
│   │   │   │   ├── stt/
│   │   │   │   │   └── VoskRecognizer.kt            # Speech-to-text wrapper
│   │   │   │   ├── nlu/
│   │   │   │   │   ├── IntentParser.kt              # Regex rule engine
│   │   │   │   │   ├── IntentResult.kt              # Data class: intent + slots
│   │   │   │   │   └── LLMFallback.kt               # Optional ONNX LLM fallback
│   │   │   │   ├── contacts/
│   │   │   │   │   ├── ContactRepository.kt         # Read + cache contacts
│   │   │   │   │   ├── ContactMatcher.kt            # Fuzzy + phonetic matching
│   │   │   │   │   └── NicknameStore.kt             # User-defined aliases
│   │   │   │   ├── actions/
│   │   │   │   │   └── CallAction.kt                # TelecomManager call execution
│   │   │   │   ├── ui/
│   │   │   │   │   ├── MainActivity.kt              # Onboarding + permissions
│   │   │   │   │   ├── SettingsActivity.kt          # Wake word, nicknames config
│   │   │   │   │   └── OverlayFeedback.kt           # Visual feedback on wake
│   │   │   │   └── util/
│   │   │   │       ├── OEMBatteryHelper.kt          # Per-OEM battery whitelisting
│   │   │   │       └── PermissionHelper.kt          # Runtime permission flow
│   │   │   ├── assets/
│   │   │   │   ├── porcupine_params.pv              # Porcupine base model
│   │   │   │   ├── wake_word.ppn                    # Custom or built-in keyword
│   │   │   │   └── vosk-model-small-en-us-0.22/     # STT model (extracted here)
│   │   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── gradle/
│   └── libs.versions.toml                           # Version catalog
├── build.gradle.kts
├── settings.gradle.kts
└── PLAN.md                                          # This file
```

---

## Dependencies

### app/build.gradle.kts
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yourname.voicecall"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yourname.voicecall"
        minSdk = 26          // Android 8.0+ — needed for TelecomManager.placeCall
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Wake Word
    implementation("ai.picovoice:porcupine-android:3.0.1")

    // Offline Speech-to-Text
    implementation("com.alphacephei:vosk-android:0.3.47")

    // Fuzzy contact matching
    implementation("me.xdrop:fuzzywuzzy:1.4.0")

    // Coroutines — async audio pipeline
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Lifecycle-aware service
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // Optional: ONNX Runtime for LLM fallback
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")

    // Optional: DataStore for settings persistence
    implementation("androidx.datastore:datastore-preferences:1.0.0")
}
```

### AndroidManifest.xml Permissions
```xml
<!-- Core permissions -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CALL_PHONE" />
<uses-permission android:name="android.permission.READ_CONTACTS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

<!-- Lock screen + screen wake -->
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.DISABLE_KEYGUARD" />

<!-- Battery optimization exemption -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

<!-- Overlay for visual feedback -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

<!-- Auto-start on boot -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

---

## Implementation Plan — Phase by Phase

### Phase 1: Foundation (Week 1)
**Goal: App runs, service survives, audio pipeline works**

#### Step 1.1 — Project Setup
- [ ] Create new Android project (Kotlin, minSdk 26)
- [ ] Add all dependencies to build.gradle.kts
- [ ] Set up version catalog in libs.versions.toml
- [ ] Configure ProGuard rules for Vosk and Porcupine

#### Step 1.2 — Permissions Flow (MainActivity.kt)
- [ ] Build permission request flow: RECORD_AUDIO → CALL_PHONE → READ_CONTACTS
- [ ] Add battery optimization exemption request
- [ ] Detect device manufacturer and show OEM-specific battery settings instructions
- [ ] On all permissions granted → start VoiceListenerService

```kotlin
// Permission request order (must be sequential, each needs user approval)
// 1. RECORD_AUDIO       — for mic access
// 2. CALL_PHONE         — for placing calls without confirmation
// 3. READ_CONTACTS      — to read contact list
// 4. Battery whitelist  — Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
```

#### Step 1.3 — VoiceListenerService.kt (Core)
- [ ] Extend `LifecycleService`
- [ ] Implement `startForeground()` with persistent notification
- [ ] Return `START_STICKY` from `onStartCommand()`
- [ ] Implement `BootReceiver` (BroadcastReceiver for BOOT_COMPLETED) to auto-start service on reboot
- [ ] Implement AudioRecord setup at 16kHz mono PCM_16BIT (required by Porcupine)

```kotlin
// Service lifecycle:
// Boot → BootReceiver → startForegroundService(VoiceListenerService)
// App killed by OEM → START_STICKY → OS restarts service
// Low memory → START_STICKY → OS restarts service after resources free
```

#### Step 1.4 — WakeWordEngine.kt
- [ ] Initialize Porcupine with built-in keyword (JARVIS or ALEXA for testing)
- [ ] Implement audio loop: read PCM frames → process → detect
- [ ] Callback: `onWakeWordDetected()` fires the STT pipeline
- [ ] Properly release Porcupine on service destroy (prevent memory leaks)

---

### Phase 2: STT + NLU (Week 2)
**Goal: Voice command fully understood**

#### Step 2.1 — Vosk Model Setup
- [ ] Download vosk-model-small-en-us-0.22 (~50MB) from alphacephei.com
- [ ] Bundle in `assets/` folder OR download on first launch (first launch download preferred to keep APK size manageable)
- [ ] Extract model to app's internal storage on first run
- [ ] Initialize `Model` and `SpeechRecognizer` from Vosk

#### Step 2.2 — VoskRecognizer.kt
- [ ] After wake word fires, switch AudioRecord to full STT capture mode
- [ ] Feed audio frames to Vosk recognizer
- [ ] Detect end of utterance (silence detection — 1.5 second silence timeout)
- [ ] Return final transcript string
- [ ] Return to wake word listening mode after transcript received

```kotlin
// Audio state machine:
// IDLE → [wake word] → LISTENING → [silence timeout] → PROCESSING → IDLE
```

#### Step 2.3 — IntentParser.kt
- [ ] Implement regex-based intent extraction
- [ ] Extract: intent type, contact name, speakerphone flag
- [ ] Handle variations:
  - "call [name]"
  - "ring [name]"
  - "dial [name]"
  - "call [name] on speaker"
  - "call [name] on speakerphone"
  - "put [name] on speaker" (future)
- [ ] Return `IntentResult` data class or null if no match

```kotlin
data class IntentResult(
    val intent: String,           // "make_call"
    val contactQuery: String,     // "Rajesh" or "mom"
    val speakerphone: Boolean,    // true/false
    val confidence: Float         // 0.0–1.0
)
```

#### Step 2.4 — LLMFallback.kt (Optional, implement after core works)
- [ ] Load small ONNX model (Phi-3 Mini or similar)
- [ ] Prompt template: extract intent + contact name + speaker flag as JSON
- [ ] Only invoked when IntentParser returns null
- [ ] Parse JSON response back to IntentResult

---

### Phase 3: Contact Matching (Week 2–3)
**Goal: Find the right contact even with mispronounced or partial names**

#### Step 3.1 — ContactRepository.kt
- [ ] Read all contacts using `ContactsContract` ContentProvider
- [ ] Cache contacts in memory (refresh on contact change via ContentObserver)
- [ ] Store: display name, first name, last name, all phone numbers, existing nicknames
- [ ] Register ContentObserver to refresh cache when contacts change

#### Step 3.2 — NicknameStore.kt
- [ ] DataStore-backed key-value store: "mom" → "+919876543210"
- [ ] UI in SettingsActivity to add/edit/delete nicknames
- [ ] Checked FIRST before ContactRepository (user's aliases win)

#### Step 3.3 — ContactMatcher.kt
- [ ] Step 1: Check NicknameStore (exact match, fastest path)
- [ ] Step 2: Exact match against contact display names
- [ ] Step 3: FuzzySearch.partialRatio (spoken "raj" matches "Rajesh Kumar")
- [ ] Step 4: FuzzySearch.ratio (overall string similarity)
- [ ] Step 5: Soundex phonetic match (pronunciation variant tolerance)
- [ ] Combine scores: `score = partialRatio * 0.5 + ratio * 0.3 + soundex * 0.2`
- [ ] Return best match only if score > 70
- [ ] If score < 70: trigger voice response "Contact not found"
- [ ] If multiple matches with close scores (diff < 5): ask "Did you mean X or Y?"

---

### Phase 4: Call Execution + Lock Screen (Week 3)
**Goal: Call placed instantly from any phone state**

#### Step 4.1 — CallAction.kt
- [ ] Use `TelecomManager.placeCall()` — direct call, no confirmation
- [ ] Set `EXTRA_START_CALL_WITH_SPEAKERPHONE` based on intent
- [ ] Check CALL_PHONE permission before calling (defensive check)
- [ ] Handle `SecurityException` gracefully with user feedback

```kotlin
// TelecomManager.placeCall() vs Intent ACTION_CALL:
// ACTION_CALL → Android shows confirmation dialog → bad UX
// placeCall() → direct, no dialog → what we want
// Requires: CALL_PHONE permission granted
```

#### Step 4.2 — Lock Screen Behavior
- [ ] Set window flags on overlay: `FLAG_SHOW_WHEN_LOCKED | FLAG_TURN_SCREEN_ON`
- [ ] Show minimal overlay UI: "Calling [Name]..." feedback
- [ ] Auto-dismiss overlay 3 seconds after call connects
- [ ] Handle keyguard state (detect if phone is locked, proceed anyway)

#### Step 4.3 — Audio Feedback
- [ ] Play earcon sound on wake word detection (confirms assistant is listening)
- [ ] Text-to-speech response: "Calling Rajesh" before dialing
- [ ] Error TTS: "Contact not found", "Please say that again"
- [ ] Use Android's built-in `TextToSpeech` API (no extra library needed)

---

### Phase 5: OEM Battery Killer Handling (Week 3–4)
**Goal: Service stays alive on all major Android skins**

This is the hardest part of the whole project. Every OEM has their own background process killer on top of Android.

#### OEM-specific handling required:
```
Samsung One UI    → "Unrestricted" battery mode in App Battery settings
Xiaomi MIUI       → Autostart permission + battery whitelist + MIUI background pop-up
Oppo/Realme       → "Allow background activity" in battery settings + autostart
OnePlus           → Battery optimization → "Don't optimize"
Huawei EMUI       → Protected apps list
Vivo              → Background app management
Stock Android     → Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (standard)
```

#### Step 5.1 — OEMBatteryHelper.kt
- [ ] Detect manufacturer via `Build.MANUFACTURER`
- [ ] Show bottom sheet dialog with step-by-step screenshots/instructions per OEM
- [ ] Use `AutoStarter` library or direct intent to open the correct settings screen
- [ ] Persist flag: user has completed battery whitelist setup

#### Step 5.2 — Service Resurrection (belt + suspenders)
- [ ] `START_STICKY` in `onStartCommand()` — OS restarts on kill
- [ ] `BootReceiver` — restarts after reboot
- [ ] `AlarmManager` — periodic watchdog every 15 minutes checks if service is running
- [ ] `JobScheduler` as secondary resurrection mechanism

```kotlin
// Watchdog pattern:
// AlarmManager fires every 15 min →
// Check if VoiceListenerService is running →
// If not → startForegroundService() again
```

---

### Phase 6: Settings UI (Week 4)
**Goal: User can configure the assistant**

#### Step 6.1 — MainActivity.kt
- [ ] Onboarding flow: explain permissions → request each → setup complete screen
- [ ] Service status indicator (green dot = active, red = stopped)
- [ ] Quick test button: "Test wake word" mode

#### Step 6.2 — SettingsActivity.kt
- [ ] Wake word selection (built-in options or custom)
- [ ] Nickname manager: add "mom → +91XXXXXXXXXX", "bro → ...", etc.
- [ ] Sensitivity slider for wake word (false positive vs miss rate tradeoff)
- [ ] Toggle: speakerphone by default
- [ ] Toggle: visual overlay feedback on wake
- [ ] Battery optimization shortcut button

---

### Phase 7: Polish + Edge Cases (Week 4–5)
**Goal: Production-quality reliability**

#### Edge Cases to Handle
- [ ] No contacts match → TTS "Contact not found, try again"
- [ ] Multiple matches (score diff < 5) → TTS "Did you mean Raj or Rajan?"
- [ ] Phone already in call → TTS "You're already on a call"
- [ ] No microphone permission → show permission screen
- [ ] Vosk model not yet downloaded → show loading state
- [ ] Very noisy environment → increase confidence threshold
- [ ] Name with special characters (Ã, é, etc.) → normalize before matching
- [ ] Contact has multiple numbers → pick mobile over landline, or ask user

#### Testing Checklist
- [ ] Test on: Pixel (stock Android), Samsung One UI, Xiaomi MIUI
- [ ] Test: phone locked → wake word → call placed
- [ ] Test: phone unlocked → wake word → call placed
- [ ] Test: phone in pocket (screen off) → wake word → call placed
- [ ] Test: mispronounced names ("Rajesh" said as "Rajes", "Rahesh")
- [ ] Test: nickname matching ("mom", "dad", "bro")
- [ ] Test: speakerphone flag ("call Raj on speaker")
- [ ] Test: service survives after 12 hours background
- [ ] Test: service restarts after phone reboot

---

## Key Code Snippets for Claude Code

### VoiceListenerService.kt (Template)
```kotlin
class VoiceListenerService : LifecycleService() {

    private lateinit var porcupine: Porcupine
    private lateinit var audioRecord: AudioRecord
    private lateinit var voskRecognizer: VoskRecognizer
    private var isListening = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        initPorcupine()
        initAudioRecord()
        initVosk()
        startWakeWordLoop()
    }

    private fun initPorcupine() {
        porcupine = Porcupine.Builder()
            .setAccessKey(BuildConfig.PORCUPINE_KEY)
            .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
            .build(applicationContext)
    }

    private fun initAudioRecord() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 4
        )
    }

    private fun startWakeWordLoop() {
        isListening = true
        audioRecord.startRecording()

        lifecycleScope.launch(Dispatchers.IO) {
            val pcmBuffer = ShortArray(porcupine.frameLength)
            while (isListening) {
                val read = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                if (read == porcupine.frameLength) {
                    val detected = porcupine.process(pcmBuffer)
                    if (detected) {
                        onWakeWordDetected()
                    }
                }
            }
        }
    }

    private suspend fun onWakeWordDetected() {
        playEarcon()
        val transcript = voskRecognizer.listenForCommand(audioRecord)
        val intent = IntentParser.parse(transcript) ?: return
        val contact = ContactMatcher.find(intent.contactQuery, applicationContext) ?: run {
            speak("Contact not found")
            return
        }
        speak("Calling ${contact.name}")
        CallAction.execute(contact.number, intent.speakerphone, applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        porcupine.delete()
        audioRecord.stop()
        audioRecord.release()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "voice_assistant_channel"
    }
}
```

### ContactMatcher.kt (Template)
```kotlin
object ContactMatcher {

    data class ResolvedContact(val name: String, val number: String)

    fun find(query: String, context: Context): ResolvedContact? {
        // Step 1: Check user-defined nicknames first
        NicknameStore.get(query)?.let { return it }

        // Step 2: Load contacts
        val contacts = ContactRepository.getAll(context)

        // Step 3: Score each contact
        var bestScore = 0
        var bestContact: ResolvedContact? = null

        for (contact in contacts) {
            val candidates = listOf(
                contact.displayName,
                contact.firstName,
                contact.displayName.split(" ").first()
            ).filter { it.isNotBlank() }

            val score = candidates.maxOf { candidate ->
                val ratio = FuzzySearch.ratio(query.lowercase(), candidate.lowercase())
                val partial = FuzzySearch.partialRatio(query.lowercase(), candidate.lowercase())
                maxOf(ratio, partial)
            }

            if (score > bestScore) {
                bestScore = score
                bestContact = ResolvedContact(contact.displayName, contact.primaryNumber)
            }
        }

        return if (bestScore >= 70) bestContact else null
    }
}
```

### IntentParser.kt (Template)
```kotlin
object IntentParser {

    private val CALL_PATTERN = Regex(
        """^(?:call|ring|dial|phone|contact)\s+(.+?)(?:\s+on\s+(?:speaker|speakerphone|loudspeaker))?$""",
        RegexOption.IGNORE_CASE
    )

    private val SPEAKER_KEYWORDS = listOf("speaker", "speakerphone", "loudspeaker", "loud")

    fun parse(transcript: String): IntentResult? {
        val trimmed = transcript.trim().lowercase()
        val match = CALL_PATTERN.find(trimmed) ?: return null

        val contactName = match.groupValues[1].trim()
        val hasSpeaker = SPEAKER_KEYWORDS.any { trimmed.contains(it) }

        return IntentResult(
            intent = "make_call",
            contactQuery = contactName,
            speakerphone = hasSpeaker,
            confidence = 1.0f
        )
    }
}
```

---

## File Download Requirements

### Vosk Model
- URL: https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.22.zip
- Size: ~50MB
- Extract to: `context.filesDir/vosk-model-small-en-us-0.22/`
- Download on first launch with progress UI

### Porcupine Access Key
- Sign up at: https://console.picovoice.ai/
- Free tier: 3 keywords, unlimited usage
- Add to `local.properties`: `PORCUPINE_ACCESS_KEY=your_key_here`
- Expose via `BuildConfig` in build.gradle.kts

---

## Known Challenges & Solutions

### Challenge 1: OEM Background Kill
- Problem: Samsung/Xiaomi kill foreground services after 5–15 minutes
- Solution: `START_STICKY` + `AlarmManager` watchdog + guide user through OEM battery settings
- Reference: dontkillmyapp.com has all OEM-specific instructions

### Challenge 2: Wake Word False Positives
- Problem: Porcupine might trigger on words that sound like the wake word
- Solution: Tune sensitivity (0.0–1.0), default 0.5; expose slider in settings

### Challenge 3: Vosk Model Size
- Problem: 50MB model in APK is too large for Play Store (100MB APK limit)
- Solution: Download on first launch, show progress, cache in internal storage

### Challenge 4: AudioRecord and Porcupine Frame Size
- Problem: Porcupine requires EXACTLY `porcupine.frameLength` shorts per `process()` call
- Solution: Buffer reads and only call `process()` when buffer is full

### Challenge 5: Calling from Background / Lock Screen
- Problem: Android 10+ restricts starting activities from background
- Solution: Use `TelecomManager.placeCall()` (not startActivity) — this works from background services

### Challenge 6: Multiple Phone Numbers per Contact
- Problem: Contact "Raj" has 3 numbers — which to call?
- Solution: Priority: mobile > work > home. If 2+ mobile numbers, TTS "Raj has multiple numbers, calling mobile"

---

## Future Enhancements (Post-MVP)

- [ ] Custom wake word training (Picovoice Console or openWakeWord)
- [ ] WhatsApp / Signal call support via Accessibility Service
- [ ] "Send message to X" command
- [ ] Multi-language support (Hindi, Tamil, etc. — Vosk has multilingual models)
- [ ] Conversation context ("Call him back" after someone calls you)
- [ ] Contact learning — improve match scores from user corrections
- [ ] Tasker / Shortcut integration
- [ ] Wear OS companion

---

## Development Environment Setup

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34
- Kotlin 1.9.x
- Gradle 8.x

### First-Time Setup
```bash
# Clone and open project
git clone <repo>
cd VoiceCallAssistant

# Add your Porcupine key
echo "PORCUPINE_ACCESS_KEY=your_key" >> local.properties

# Download Vosk model (optional — app can download at runtime)
# Place at: app/src/main/assets/vosk-model-small-en-us-0.22/

# Build
./gradlew assembleDebug
```

### Test Device Recommendation
- Primary: Any Pixel phone (cleanest Android, no OEM battery killers)
- Secondary: Samsung Galaxy (One UI = most common Android skin globally)
- Third: Xiaomi (MIUI = hardest OEM to survive on, good stress test)

---

## Summary

| Phase | What Gets Built | Duration |
|-------|----------------|----------|
| 1 — Foundation | Service + wake word + audio loop | Week 1 |
| 2 — STT + NLU | Voice → intent understanding | Week 2 |
| 3 — Contact Matching | Fuzzy + phonetic resolution | Week 2–3 |
| 4 — Call Execution | TelecomManager + lock screen | Week 3 |
| 5 — OEM Handling | Survive Samsung/Xiaomi kill | Week 3–4 |
| 6 — Settings UI | Config + nicknames + onboarding | Week 4 |
| 7 — Polish | Edge cases + testing | Week 4–5 |

**MVP Target: 5 weeks to a working, shippable app.**

The core loop — wake word → STT → match contact → place call — can be working end-to-end in Week 2. Everything after that is making it bulletproof.
