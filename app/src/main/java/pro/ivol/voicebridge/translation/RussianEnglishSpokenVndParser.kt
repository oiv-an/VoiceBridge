package pro.ivol.voicebridge.translation

import java.util.Locale

/** Parses common Russian and English spoken thousand/million amounts. */
internal object RussianEnglishSpokenVndParser {
    fun parseMostLikelyVnd(text: String): Long? {
        val tokens = WORD.findAll(
            text.lowercase(Locale.ROOT).replace('ё', 'е'),
        ).map(MatchResult::value).toList()
        if (tokens.isEmpty()) return null

        return parseRuns(tokens, RUSSIAN) ?: parseRuns(tokens, ENGLISH)
    }

    private fun parseRuns(tokens: List<String>, language: LanguageRules): Long? {
        var runStart = -1
        for (index in 0..tokens.size) {
            val token = tokens.getOrNull(index)
            val belongsToNumber = token != null && token in language.vocabulary
            if (belongsToNumber && runStart < 0) runStart = index

            if ((!belongsToNumber || index == tokens.size) && runStart >= 0) {
                val runEnd = index
                parseScaled(tokens.subList(runStart, runEnd), language)?.let { amount ->
                    if (amount >= MIN_AMOUNT_VND) return amount
                }
                runStart = -1
            }
        }
        return null
    }

    private fun parseScaled(tokens: List<String>, language: LanguageRules): Long? {
        val clean = tokens.filterNot { it in language.connectors }
        if (clean.isEmpty()) return null
        if (clean.none(language::isNumberWord)) return null

        val millionIndex = clean.indexOfFirst { it in language.millionWords }
        val thousandIndex = clean.indexOfFirst { it in language.thousandWords }
        if (millionIndex < 0 && thousandIndex < 0) return null
        if (
            clean.count { it in language.millionWords } > 1 ||
            clean.count { it in language.thousandWords } > 1 ||
            (millionIndex >= 0 && thousandIndex >= 0 && thousandIndex < millionIndex)
        ) {
            return null
        }

        var total = 0L
        var segmentStart = 0

        if (millionIndex >= 0) {
            val group = parseGroupOrOne(clean.subList(segmentStart, millionIndex), language)
                ?: return null
            total += group * 1_000_000L
            segmentStart = millionIndex + 1
        }

        if (thousandIndex >= 0) {
            val group = parseGroupOrOne(clean.subList(segmentStart, thousandIndex), language)
                ?: return null
            total += group * 1_000L
            segmentStart = thousandIndex + 1
        }

        if (segmentStart < clean.size) {
            total += language.parseUnderOneThousand(clean.subList(segmentStart, clean.size))
                ?: return null
        }

        return total
    }

    private fun parseGroupOrOne(tokens: List<String>, language: LanguageRules): Int? =
        if (tokens.isEmpty()) 1 else language.parseUnderOneThousand(tokens)

    private data class LanguageRules(
        val unitValues: Map<String, Int>,
        val teenValues: Map<String, Int>,
        val tensValues: Map<String, Int>,
        val hundredValues: Map<String, Int>,
        val hundredWord: String?,
        val thousandWords: Set<String>,
        val millionWords: Set<String>,
        val connectors: Set<String>,
    ) {
        val vocabulary: Set<String> =
            unitValues.keys + teenValues.keys + tensValues.keys + hundredValues.keys +
                listOfNotNull(hundredWord) + thousandWords + millionWords + connectors

        fun isNumberWord(token: String): Boolean =
            token in unitValues || token in teenValues || token in tensValues ||
                token in hundredValues || token == hundredWord

        fun parseUnderOneThousand(tokens: List<String>): Int? {
            if (tokens.isEmpty()) return null
            if (tokens.any { it in thousandWords || it in millionWords }) return null

            if (hundredWord != null) {
                val hundredIndex = tokens.indexOf(hundredWord)
                if (hundredIndex >= 0) {
                    if (tokens.lastIndexOf(hundredWord) != hundredIndex || hundredIndex !in 0..1) {
                        return null
                    }
                    val hundreds = if (hundredIndex == 0) {
                        1
                    } else {
                        unitValues[tokens.first()] ?: return null
                    }
                    val remainder = tokens.drop(hundredIndex + 1)
                    return hundreds * 100 + if (remainder.isEmpty()) {
                        0
                    } else {
                        parseUnderOneHundred(remainder) ?: return null
                    }
                }
            }

            val explicitHundreds = tokens.mapNotNull(hundredValues::get)
            if (explicitHundreds.size > 1) return null
            val remaining = if (explicitHundreds.isEmpty()) {
                tokens
            } else {
                val hundredIndex = tokens.indexOfFirst { it in hundredValues }
                if (hundredIndex != 0) return null
                tokens.drop(1)
            }
            val remainder = if (remaining.isEmpty()) 0 else parseUnderOneHundred(remaining)
                ?: return null
            return explicitHundreds.singleOrNull().orZero() + remainder
        }

        private fun parseUnderOneHundred(tokens: List<String>): Int? = when (tokens.size) {
            1 -> unitValues[tokens[0]] ?: teenValues[tokens[0]] ?: tensValues[tokens[0]]
            2 -> {
                val tens = tensValues[tokens[0]] ?: return null
                val units = unitValues[tokens[1]] ?: return null
                tens + units
            }

            else -> null
        }

        private fun Int?.orZero(): Int = this ?: 0
    }

    private const val MIN_AMOUNT_VND = 1_000L
    private val WORD = Regex("[\\p{L}]+")

    private val RUSSIAN = LanguageRules(
        unitValues = mapOf(
            "один" to 1, "одна" to 1, "одно" to 1,
            "два" to 2, "две" to 2, "три" to 3, "четыре" to 4,
            "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9,
        ),
        teenValues = mapOf(
            "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12,
            "тринадцать" to 13, "четырнадцать" to 14, "пятнадцать" to 15,
            "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18,
            "девятнадцать" to 19,
        ),
        tensValues = mapOf(
            "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50,
            "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80,
            "девяносто" to 90,
        ),
        hundredValues = mapOf(
            "сто" to 100, "двести" to 200, "триста" to 300, "четыреста" to 400,
            "пятьсот" to 500, "шестьсот" to 600, "семьсот" to 700,
            "восемьсот" to 800, "девятьсот" to 900,
        ),
        hundredWord = null,
        thousandWords = setOf("тысяча", "тысячи", "тысяч"),
        millionWords = setOf("миллион", "миллиона", "миллионов"),
        connectors = setOf("и"),
    )

    private val ENGLISH = LanguageRules(
        unitValues = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        ),
        teenValues = mapOf(
            "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
            "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
            "eighteen" to 18, "nineteen" to 19,
        ),
        tensValues = mapOf(
            "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
            "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
        ),
        hundredValues = emptyMap(),
        hundredWord = "hundred",
        thousandWords = setOf("thousand", "thousands"),
        millionWords = setOf("million", "millions"),
        connectors = setOf("and"),
    )
}
