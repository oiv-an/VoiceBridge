package pro.ivol.voicebridge.translation

import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

/**
 * Finds Vietnamese-dong amounts in recognized text without depending on its language.
 *
 * Every numeric amount whose resulting value is at least 1,000 is eligible, including phone-like
 * digit strings. Vietnamese, Russian, and English thousand/million scale markers are supported.
 */
object UniversalVndAmountParser {
    /** Returns unique detected amounts in their order of appearance. */
    fun parseAllVnd(text: String): List<Long> {
        if (text.isBlank()) return emptyList()

        val amounts = LinkedHashSet<Long>()
        findNumericCandidates(text)
            .sortedBy(MoneyCandidate::start)
            .forEach { amounts += it.amount }

        // ASR may leave spoken numbers as words even when the same phrase also
        // contains a year, room number, or another numeric amount.
        (
            VietnameseMoneyParser.parseMostLikelyVnd(text)
                ?: RussianEnglishSpokenVndParser.parseMostLikelyVnd(text)
            )
            ?.takeIf { it >= MIN_AMOUNT_VND }
            ?.let(amounts::add)

        return amounts.toList()
    }

    fun parseMostLikelyVnd(text: String): Long? {
        if (text.isBlank()) return null

        val numericCandidates = findNumericCandidates(text)
        val candidates = numericCandidates.toMutableList()

        // Keep support for Vietnamese amounts spoken entirely as words. Numeric candidates are
        // parsed here, so the legacy parser is added only when it contributes a different amount.
        (
            VietnameseMoneyParser.parseMostLikelyVnd(text)
                ?: RussianEnglishSpokenVndParser.parseMostLikelyVnd(text)
            )
            ?.takeIf { it >= MIN_AMOUNT_VND }
            ?.takeIf { amount -> numericCandidates.none { it.amount == amount } }
            ?.let { amount ->
                candidates += MoneyCandidate(
                    amount = amount,
                    confidence = CONFIDENCE_WORD_SCALE,
                    start = Int.MAX_VALUE,
                    spanLength = 0,
                )
            }

        return candidates
            .sortedWith(
                compareByDescending<MoneyCandidate> { it.confidence }
                    .thenBy { it.start }
                    .thenByDescending { it.spanLength },
            )
            .firstOrNull()
            ?.amount
    }

    /**
     * Groups integer digit tokens of at least 1,000 with spaces for easier reading.
     *
     * Decimal values immediately followed by a scale marker stay untouched, so `1,5 triệu`
     * remains a decimal million rather than becoming an integer-looking value.
     */
    fun formatRecognizedText(text: String): String {
        if (text.isEmpty()) return text

        val matches = DIGIT_LITERAL.findAll(text).toList()
        if (matches.isEmpty()) return text

        return buildString(text.length) {
            var copiedUntil = 0
            matches.forEach { match ->
                append(text, copiedUntil, match.range.first)

                val raw = match.value
                val suffix = text.substring(
                    match.range.last + 1,
                    minOf(text.length, match.range.last + 1 + MARKER_WINDOW),
                )
                val markers = findSuffixMarkers(suffix)
                val nextCharacter = text.getOrNull(match.range.last + 1)
                val isStandalone = nextCharacter == null ||
                    (!nextCharacter.isLetterOrDigit() && nextCharacter != '_') ||
                    markers.hasAnyMarker

                val replacement = when {
                    !isStandalone -> raw
                    markers.scaleMultiplier != null && isScaledDecimal(raw) -> raw
                    else -> parseGroupedInteger(raw)
                        ?.takeIf(::isAtLeastOneThousand)
                        ?.let(::groupDigitsWithSpaces)
                        ?: raw
                }

                append(replacement)
                copiedUntil = match.range.last + 1
            }
            append(text, copiedUntil, text.length)
        }
    }

    private fun findNumericCandidates(text: String): List<MoneyCandidate> = buildList {
        DIGIT_LITERAL.findAll(text).forEach { match ->
            val prefix = text.substring(
                maxOf(0, match.range.first - MARKER_WINDOW),
                match.range.first,
            )
            val suffix = text.substring(
                match.range.last + 1,
                minOf(text.length, match.range.last + 1 + MARKER_WINDOW),
            )
            val suffixMarkers = findSuffixMarkers(suffix)
            val hasCurrency = hasCurrencyAtEnd(prefix) || suffixMarkers.hasCurrency
            val hasScale = suffixMarkers.scaleMultiplier != null
            val amount = parseNumericLiteral(
                raw = match.value,
                multiplier = suffixMarkers.scaleMultiplier ?: 1L,
            ) ?: return@forEach

            if (amount < MIN_AMOUNT_VND) return@forEach

            add(
                MoneyCandidate(
                    amount = amount,
                    confidence = confidence(hasCurrency = hasCurrency, hasScale = hasScale),
                    start = match.range.first,
                    spanLength = match.value.length + suffixMarkers.matchedLength,
                ),
            )
        }
    }

    private fun parseNumericLiteral(raw: String, multiplier: Long): Long? {
        val value = if (multiplier == 1L) {
            parseGroupedInteger(raw)?.let(::BigDecimal)
        } else {
            parseScaledDecimal(raw)
        } ?: return null

        if (value.signum() < 0) return null
        return runCatching {
            value.multiply(BigDecimal.valueOf(multiplier)).longValueExact()
        }.getOrNull()
    }

    private fun parseScaledDecimal(raw: String): BigDecimal? {
        val trimmed = raw.trim()
        val decimalPosition = trimmed.indexOfLast { it == '.' || it == ',' }
        if (decimalPosition < 0) return parseGroupedInteger(trimmed)?.let(::BigDecimal)

        val fractionalDigits = trimmed
            .substring(decimalPosition + 1)
            .filterNot(Char::isWhitespace)

        val integerDigits = parseGroupedInteger(trimmed.substring(0, decimalPosition))
        val isDecimalFraction = fractionalDigits.all(Char::isDigit) &&
            (fractionalDigits.length in 1..2 || integerDigits?.all { it == '0' } == true)

        if (isDecimalFraction) {
            integerDigits ?: return null
            return runCatching { BigDecimal("$integerDigits.$fractionalDigits") }.getOrNull()
        }

        return parseGroupedInteger(trimmed)?.let(::BigDecimal)
    }

    private fun isScaledDecimal(raw: String): Boolean {
        val trimmed = raw.trim()
        val decimalPosition = trimmed.indexOfLast { it == '.' || it == ',' }
        if (decimalPosition < 0) return false

        val fractionalDigits = trimmed
            .substring(decimalPosition + 1)
            .filterNot(Char::isWhitespace)

        val integerDigits = parseGroupedInteger(trimmed.substring(0, decimalPosition))
        return fractionalDigits.all(Char::isDigit) &&
            (fractionalDigits.length in 1..2 || integerDigits?.all { it == '0' } == true) &&
            integerDigits != null
    }

    private fun parseGroupedInteger(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.all(Char::isDigit)) return trimmed

        val separatorKinds = buildSet {
            trimmed.forEach { character ->
                when {
                    character == '.' -> add('.')
                    character == ',' -> add(',')
                    character.isWhitespace() -> add(' ')
                    !character.isDigit() -> return null
                }
            }
        }
        if (separatorKinds.size != 1) return null

        val groups = trimmed.split(GROUP_SEPARATOR).filter(String::isNotBlank)
        if (groups.size < 2) return null
        if (groups.first().length !in 1..3 || groups.first().any { !it.isDigit() }) return null
        if (groups.drop(1).any { group -> group.length != 3 || group.any { !it.isDigit() } }) return null
        return groups.joinToString(separator = "")
    }

    private fun findSuffixMarkers(suffix: String): SuffixMarkers {
        val normalized = normalize(suffix)
        val match = SUFFIX_MARKERS.find(normalized) ?: return SuffixMarkers()
        val scaleName = match.groupValues[1].ifBlank { null }
        val currencyName = match.groupValues[2].ifBlank { null }

        return SuffixMarkers(
            scaleMultiplier = scaleName?.let(::scaleMultiplier),
            hasCurrency = currencyName != null,
            matchedLength = match.value.length,
        )
    }

    private fun hasCurrencyAtEnd(prefix: String): Boolean =
        CURRENCY_AT_END.containsMatchIn(normalize(prefix))

    private fun normalize(text: String): String {
        val expandedCurrencies = text
            .lowercase(Locale.ROOT)
            .replace(STANDALONE_DONG_SYMBOL, " vnd ")
            .replace("₫", " vnd ")
        val decomposed = Normalizer.normalize(expandedCurrencies, Normalizer.Form.NFD)

        return buildString(decomposed.length) {
            decomposed.forEach { character ->
                when {
                    Character.getType(character) == Character.NON_SPACING_MARK.toInt() -> Unit
                    character == 'đ' -> append('d')
                    else -> append(character)
                }
            }
        }
    }

    private fun scaleMultiplier(name: String): Long = when (name) {
        "trieu", "million", "millions",
        "миллион", "миллиона", "миллионов",
        -> 1_000_000L

        "nghin", "ngin", "ngan", "k", "thousand", "thousands",
        "тысяча", "тысячи", "тысяч",
        -> 1_000L

        else -> error("Unknown amount scale: $name")
    }

    private fun confidence(hasCurrency: Boolean, hasScale: Boolean): Int = when {
        hasCurrency && hasScale -> CONFIDENCE_CURRENCY_AND_SCALE
        hasCurrency -> CONFIDENCE_CURRENCY
        hasScale -> CONFIDENCE_SCALE
        else -> CONFIDENCE_BARE_NUMBER
    }

    private fun isAtLeastOneThousand(digits: String): Boolean =
        digits.trimStart('0').length >= 4

    private fun groupDigitsWithSpaces(digits: String): String {
        val remainder = digits.length % 3
        val firstGroupLength = remainder.takeIf { it != 0 } ?: 3
        return buildString(digits.length + digits.length / 3) {
            append(digits, 0, firstGroupLength)
            var index = firstGroupLength
            while (index < digits.length) {
                append(' ')
                append(digits, index, index + 3)
                index += 3
            }
        }
    }

    private data class MoneyCandidate(
        val amount: Long,
        val confidence: Int,
        val start: Int,
        val spanLength: Int,
    )

    private data class SuffixMarkers(
        val scaleMultiplier: Long? = null,
        val hasCurrency: Boolean = false,
        val matchedLength: Int = 0,
    ) {
        val hasAnyMarker: Boolean
            get() = scaleMultiplier != null || hasCurrency
    }

    private const val MIN_AMOUNT_VND = 1_000L
    private const val MARKER_WINDOW = 48

    private const val CONFIDENCE_CURRENCY_AND_SCALE = 400
    private const val CONFIDENCE_CURRENCY = 350
    private const val CONFIDENCE_SCALE = 300
    private const val CONFIDENCE_WORD_SCALE = 250
    private const val CONFIDENCE_BARE_NUMBER = 100

    private const val SCALE_PATTERN =
        "trieu|nghin|ngin|ngan|k|thousand|thousands|million|millions|" +
            "тысяча|тысячи|тысяч|миллион|миллиона|миллионов"
    private const val CURRENCY_PATTERN = "vnd|dong|донг|донга|донгов"

    private val DIGIT_LITERAL = Regex(
        "(?<![\\p{L}\\p{N}_])(?:" +
            "\\d{1,3}(?:[.\\u00A0\\u202F ]\\d{3})+(?:,\\d{1,2})?|" +
            "\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|" +
            "\\d+[.,]\\d{1,2}|" +
            "\\d+" +
            ")(?!\\d)",
    )
    private val GROUP_SEPARATOR = Regex("[.,\\s\\u00A0\\u202F]+")
    private val SUFFIX_MARKERS = Regex(
        "^\\s*(?:($SCALE_PATTERN)(?![\\p{L}\\p{N}_]))?" +
            "\\s*(?:($CURRENCY_PATTERN)(?![\\p{L}\\p{N}_]))?",
    )
    private val CURRENCY_AT_END = Regex(
        "(?:^|[^\\p{L}\\p{N}_])($CURRENCY_PATTERN)\\s*$",
    )
    private val STANDALONE_DONG_SYMBOL = Regex("(?<![\\p{L}])đ(?![\\p{L}])", RegexOption.IGNORE_CASE)
}
