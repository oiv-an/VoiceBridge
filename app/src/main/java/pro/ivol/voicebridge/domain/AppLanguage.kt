package pro.ivol.voicebridge.domain

enum class AppLanguage(
    val languageTag: String,
    val displayName: String,
) {
    RUSSIAN("ru-RU", "Russian"),
    ENGLISH("en-US", "English"),
    VIETNAMESE("vi-VN", "Vietnamese"),
}
