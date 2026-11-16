package com.haitham.voiceassistant.model

/** High level state of the assistant shown in the UI and the notification. */
enum class AssistantStatus(val label: String) {
    STOPPED("متوقف"),
    LOADING_MODEL("جارٍ تحميل النموذج الصوتي"),
    WAITING_WAKE_WORD("نشط – بانتظار كلمة التنبيه"),
    WAITING_DIRECT_COMMAND("نشط – قل الأمر مباشرة"),
    LISTENING_COMMAND("يستمع للأمر"),
    PROCESSING("يعالج الأمر"),
    AWAITING_CHOICE("بانتظار اختيارك"),
    PAUSED_IN_CALL("متوقف مؤقتاً أثناء المكالمة"),
    MIC_BLOCKED("الميكروفون مشغول بتطبيق آخر"),
    ERROR("خطأ")
}

/** One word returned by the recogniser with its confidence and timing (seconds). */
data class RecognizedWord(val word: String, val conf: Float, val start: Float, val end: Float)

/** A recognition hypothesis from one recogniser. */
data class Hypothesis(val text: String, val words: List<RecognizedWord>, val source: Source) {
    enum class Source { GRAMMAR, FREE, ON_DEVICE }

    val meanConfidence: Float
        get() = if (words.isEmpty()) 0f else words.map { it.conf }.average().toFloat()
}

data class PhoneEntry(val number: String, val type: Int, val label: String?, val isPrimary: Boolean)

data class Contact(
    val id: Long,
    val displayName: String,
    val phones: List<PhoneEntry>,
    val starred: Boolean = false,
    /** How many times this contact was called (from the call log, if permitted). Used only to break exact ties. */
    val callCount: Int = 0
)

/** Parsed user intention. */
sealed class VoiceIntent {
    data class CallContact(val nameQuery: String, val alternatives: List<String>) : VoiceIntent()
    data class CallNumber(val digits: String) : VoiceIntent()
    data object Redial : VoiceIntent()
    data object StopListening : VoiceIntent()
    data object OpenApp : VoiceIntent()
    data object Cancel : VoiceIntent()
    data class Choice(val index: Int) : VoiceIntent()   // "الأول" / "الثاني" during disambiguation
    data object Yes : VoiceIntent()
    data object No : VoiceIntent()
    data class Unknown(val text: String) : VoiceIntent()

    /** Stable key used to suppress duplicate execution of the same command. */
    val signature: String
        get() = when (this) {
            is CallContact -> "contact:$nameQuery"
            is CallNumber -> "number:$digits"
            else -> this::class.simpleName ?: "?"
        }
}

data class ContactCandidate(val contact: Contact, val phone: PhoneEntry, val score: Double)

sealed class MatchResult {
    data class Confident(val candidate: ContactCandidate) : MatchResult()
    data class NeedsConfirmation(val candidate: ContactCandidate) : MatchResult()
    data class Ambiguous(val candidates: List<ContactCandidate>) : MatchResult()
    data object NotFound : MatchResult()
}

/** Snapshot published to the UI. */
data class AssistantUiState(
    val status: AssistantStatus = AssistantStatus.STOPPED,
    val serviceRunning: Boolean = false,
    val lastHeard: String = "",
    val lastResult: String = "",
    val level: Float = 0f,
    val modelInfo: String = "",
    val testTranscript: String = "",
    /** Status of the phone's on-device AI recogniser (Arabic installed or not). */
    val aiEngineInfo: String = "",
    val error: String? = null
)
