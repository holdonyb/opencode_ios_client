package ai.opencode.mobile.android.speech

enum class SpeechProvider(
    val wireValue: String,
    val displayName: String
) {
    AIBUILDERS(
        wireValue = "ai_builders",
        displayName = "AI Builder"
    ),
    DOUBAO(
        wireValue = "doubao",
        displayName = "Doubao"
    );

    companion object {
        fun fromWireValue(raw: String?): SpeechProvider {
            return entries.firstOrNull { it.wireValue == raw } ?: AIBUILDERS
        }
    }
}
