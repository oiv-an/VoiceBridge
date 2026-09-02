package pro.ivol.voicebridge.translation

import java.text.Normalizer
import java.util.Locale

/** A short, offline, Russian-friendly Southern Vietnamese pronunciation hint. */
object VietnamesePronunciation {
    private const val DEFAULT_MAX_WORDS = 5

    private val wordRegex = Regex("""[\p{L}\p{M}]+""")
    private val tokenRegex = Regex(
        """[\p{L}\p{M}]+|[\p{N}]+|[^\p{L}\p{M}\p{N}]+""",
    )
    private val sentenceBreakRegex = Regex("""[.!?…]+(?=\s|$)""")

    fun shortRussianHint(
        text: String,
        maxWords: Int = DEFAULT_MAX_WORDS,
    ): String? {
        require(maxWords > 0) { "The pronunciation word limit must be positive." }
        val phrase = text.trim()
        if (phrase.isEmpty()) return null

        val wordCount = wordRegex.findAll(phrase).count()
        if (wordCount !in 1..maxWords) return null

        val sentenceCount = sentenceBreakRegex
            .split(phrase)
            .count { fragment -> fragment.any { it.isLetterOrDigit() } }
        if (sentenceCount > 1) return null

        var convertedSyllable = false
        val hint = tokenRegex.replace(phrase) { token ->
            transliterateSyllable(token.value)?.also {
                convertedSyllable = true
            } ?: token.value
        }
        return hint.takeIf { convertedSyllable }
    }

    private fun transliterateSyllable(word: String): String? {
        val parsed = parseTone(word)
        val base = parsed.base
        if (base.isEmpty()) return null

        val onsetKey = onsetKeys.firstOrNull(base::startsWith).orEmpty()
        var remainder = base.drop(onsetKey.length)
        val codaKey = codaKeys.firstOrNull(remainder::endsWith).orEmpty()
        var nucleus = remainder.dropLast(codaKey.length)

        if (onsetKey == "gi" && nucleus.isEmpty()) {
            nucleus = "i"
        } else if (onsetKey == "gi" && nucleus.startsWith("ê")) {
            nucleus = "i$nucleus"
        }

        if (nucleus.isEmpty() || nucleus.any { it !in vietnameseVowels }) return null

        val russian = commonSyllables[base] ?: run {
            val russianNucleus = transliterateNucleus(nucleus) ?: return null
            joinOnsetAndNucleus(
                onset = onsets.getValue(onsetKey),
                nucleus = russianNucleus,
            ) + codas.getValue(codaKey)
        }
        val cased = if (word.firstOrNull()?.isUpperCase() == true) {
            russian.replaceFirstChar { character -> character.uppercaseChar() }
        } else {
            russian
        }
        return cased + parsed.tone.arrow
    }

    private fun parseTone(word: String): ParsedSyllable {
        var tone = Tone.LEVEL
        val withoutTone = buildString {
            Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .forEach { character ->
                    when (character) {
                        '\u0301' -> tone = Tone.RISING
                        '\u0300' -> tone = Tone.FALLING
                        '\u0309' -> tone = Tone.DIPPING
                        '\u0303' -> tone = Tone.DIPPING
                        '\u0323' -> tone = Tone.LOW_SHORT
                        else -> append(character)
                    }
                }
        }
        return ParsedSyllable(
            base = Normalizer.normalize(withoutTone, Normalizer.Form.NFC),
            tone = tone,
        )
    }

    private fun transliterateNucleus(nucleus: String): String? {
        nuclei[nucleus]?.let { return it }
        return buildString {
            nucleus.forEach { vowel ->
                append(singleVowels[vowel] ?: return null)
            }
        }
    }

    private fun joinOnsetAndNucleus(onset: String, nucleus: String): String = when {
        onset == "й" && nucleus.startsWith("а") -> "я" + nucleus.drop(1)
        onset == "й" && nucleus.startsWith("у") -> "ю" + nucleus.drop(1)
        onset == "нь" && nucleus.startsWith("и") -> "н" + nucleus
        else -> onset + nucleus
    }

    private data class ParsedSyllable(
        val base: String,
        val tone: Tone,
    )

    private enum class Tone(val arrow: String) {
        LEVEL("→"),
        RISING("↗"),
        FALLING("↘"),
        DIPPING("↘↗"),
        LOW_SHORT("↓"),
    }

    private val onsetKeys = listOf(
        "ngh", "ch", "gh", "gi", "kh", "ng", "nh", "ph", "qu", "th", "tr",
        "b", "c", "d", "đ", "g", "h", "k", "l", "m", "n", "p", "q", "r",
        "s", "t", "v", "x",
    )
    private val codaKeys = listOf("ch", "ng", "nh", "c", "k", "m", "n", "p", "t")

    private val onsets = mapOf(
        "" to "",
        "b" to "б",
        "c" to "к",
        "ch" to "ч",
        "d" to "й",
        "đ" to "д",
        "g" to "г",
        "gh" to "г",
        "gi" to "й",
        "h" to "х",
        "k" to "к",
        "kh" to "х",
        "l" to "л",
        "m" to "м",
        "n" to "н",
        "ng" to "нг",
        "ngh" to "нг",
        "nh" to "нь",
        "ph" to "ф",
        "p" to "п",
        "q" to "к",
        "qu" to "у",
        "r" to "р",
        "s" to "с",
        "t" to "т",
        "th" to "тх",
        "tr" to "ч",
        "v" to "й",
        "x" to "с",
    )

    private val codas = mapOf(
        "" to "",
        "c" to "к",
        "ch" to "ть",
        "k" to "к",
        "m" to "м",
        "n" to "н",
        "ng" to "нг",
        "nh" to "нь",
        "p" to "п",
        "t" to "т",
    )

    private val nuclei = mapOf(
        "iêu" to "иэу",
        "yêu" to "иэу",
        "uôi" to "уой",
        "ươi" to "ыэй",
        "ươu" to "ыэу",
        "oai" to "уай",
        "uai" to "уай",
        "oay" to "уай",
        "uay" to "уай",
        "oao" to "уау",
        "oeo" to "уэу",
        "ueo" to "уэу",
        "uêu" to "уэу",
        "uyu" to "уиу",
        "uây" to "уэй",
        "uyê" to "уиэ",
        "uya" to "уиа",
        "ia" to "иэ",
        "iê" to "иэ",
        "yê" to "иэ",
        "ua" to "уо",
        "uô" to "уо",
        "ưa" to "ыэ",
        "ươ" to "ыэ",
        "ai" to "ай",
        "ay" to "ай",
        "ao" to "ау",
        "au" to "ау",
        "ây" to "эй",
        "âu" to "эу",
        "eo" to "эу",
        "êu" to "эу",
        "iu" to "иу",
        "oi" to "ой",
        "ôi" to "ой",
        "ơi" to "ой",
        "ui" to "уй",
        "uy" to "уи",
        "ưi" to "ый",
        "ưu" to "ыу",
        "oa" to "уа",
        "oă" to "уа",
        "oe" to "уэ",
        "uă" to "уа",
        "uâ" to "уэ",
        "ue" to "уэ",
        "uê" to "уэ",
        "uơ" to "уо",
        "oo" to "о",
        "ôô" to "о",
    )

    private val singleVowels = mapOf(
        'a' to "а",
        'ă' to "а",
        'â' to "э",
        'e' to "э",
        'ê' to "э",
        'i' to "и",
        'y' to "и",
        'o' to "о",
        'ô' to "о",
        'ơ' to "о",
        'u' to "у",
        'ư' to "ы",
    )
    private val vietnameseVowels = singleVowels.keys

    private val commonSyllables = mapOf(
        "xin" to "син",
        "chao" to "чау",
        "cam" to "кам",
        "ơn" to "онг",
        "bao" to "бау",
        "nhiêu" to "ниэу",
        "tiên" to "тиэнг",
        "không" to "хоунг",
        "đươc" to "дыэк",
        "vâng" to "йэнг",
        "môt" to "мок",
        "hai" to "хай",
        "ba" to "ба",
        "triêu" to "чиэу",
        "nghin" to "нгын",
        "ngan" to "нганг",
        "tôi" to "той",
        "muôn" to "муонг",
    )
}
