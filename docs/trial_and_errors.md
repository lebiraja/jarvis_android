# Jarvis Android: Development & Trial Logs

## Attempt 1: Picovoice Porcupine (Initial Architecture)
- **Status:** Abandoned 
- **Reason:** Porcupine requires registering an account on the Picovoice Console to get a free AccessKey. We attempted to bypass this to achieve a completely key-less, 100% open-source solution.

## Attempt 2: openWakeWord + ONNX Runtime (Manual Integration)
- **Status:** Failed / Highly Unstable
- **Issues Faced:** 
  1. **Missing SDK:** `openWakeWord` does not have an official Android library. We had to manually write audio pipeline logic feeding `PCM16` audio into `FloatBuffers` and passing them through ONNX Runtime.
  2. **Model Corruption:** Fetching the large `.onnx` models (`melspectrogram.onnx`, `hey_jarvis.onnx`) directly through the Terminal via `wget` and `curl` ran into GitHub LFS (Large File Storage) walls. Instead of the raw 1.5MB model, the app downloaded corrupted 130-byte text pointers, triggering `ORT_INVALID_PROTOBUF` fatal crashes.
  3. **Audio Normalization:** The Python engine relies on numpy casting tricks that clash with standard Android PCM-to-Float normalization mapping, causing the AI to hallucinate silence.
  
## Attempt 3: Switch back to Picovoice Porcupine (Current)
- **Status:** Active
- **Reason:** The official `ai.picovoice:porcupine-android` package guarantees absolute stability, doesn't require downloading fragile LFS models, and handles all microphone loop routing natively in C++. It is the industry standard for Android. The only requirement is that the user injects their free AccessKey into local.properties!
