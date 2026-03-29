package com.jarvis.jarvis.nlu

object IntentParser {

    private val CALL_PATTERN = Regex(
        """^(?:call|ring|dial|phone|contact)\s+(.+?)(?:\s+on\s+(?:speaker|speakerphone|loudspeaker|loud))?$""",
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
