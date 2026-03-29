package com.jarvis.jarvis.nlu

data class IntentResult(
    val intent: String,
    val contactQuery: String,
    val speakerphone: Boolean,
    val confidence: Float
)
