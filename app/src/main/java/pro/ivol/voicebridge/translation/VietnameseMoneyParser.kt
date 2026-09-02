package pro.ivol.voicebridge.translation

import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

/**
 * Extracts the most likely Vietnamese-dong amount from recognized Vietnamese text.
 *
 * A bare number is deliberately ignored unless [allowBareLargeNumeric] is enabled. This keeps
 * dates, phone numbers, quantities, and addresses from silently becoming prices. A currency or
 * scale marker (for example, `VND`, `₫`, `đồng`, `triệu`, or `k`) is always explicit enough.
 */
object VietnameseMoneyParser {
    fun parseMostLikelyVnd(
        text: String,
        allowBareLargeNumeric: Boolean = false,
    ): Long? {
        if (text.isBlank()) return null

        val normalized = normalizeVietnamese(text)
        val candidates = buildList {
            addAll(findDigitCandidates(normalized, allowBareLargeNumeric))
            addAll(findWordCandidates(normalized))
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

    private fun findDigitCandidates(
        normalized: String,
        allowBareLargeNumeric: Boolean,
    ): List<MoneyCandidate> = buildList {
        DIGIT_LITERAL.findAll(normalized).forEach { match ->
            val prefix = normalized.substring(maxOf(0, match.range.first - MARKER_WINDOW), match.range.first)
            val suffix = normalized.substring(match.range.last + 1, minOf(normalized.length, match.range.last + 1 + MARKER_WINDOW))

            val prefixCurrency = CURRENCY_AT_END.find(prefix)?.groupValues?.get(1)
            val suffixMarkers = SUFFIX_MARKERS.find(suffix)
            val scaleName = suffixMarkers?.groupValues?.get(1).orEmpty().ifBlank { null }
            val suffixCurrency = suffixMarkers?.groupValues?.get(2).orEmpty().ifBlank { null }
            val hasCurrency = prefixCurrency != null || suffixCurrency != null
            val scale = scaleName?.let(::scaleMultiplier)
            val isExplicit = hasCurrency || scale != null

            if (!isExplicit && !allowBareLargeNumeric) return@forEach
            if (!isExplicit && isLikelyPhoneNumber(normalized, match)) return@forEach

            val amount = parseNumericLiteral(match.value, scale ?: 1L) ?: return@forEach
            if (!isExplicit && amount < MIN_BARE_AMOUNT) return@forEach

            add(
                MoneyCandidate(
                    amount = amount,
                    confidence = confidence(hasCurrency, scale != null),
                    start = match.range.first,
                    spanLength = match.value.length + (suffixMarkers?.value?.length ?: 0),
                ),
            )
        }
    }

    private fun findWordCandidates(normalized: String): List<MoneyCandidate> = buildList {
        WORD_PHRASE.findAll(normalized).forEach { match ->
            val tokens = match.value
                .split(WORD_SEPARATOR)
                .filter(String::isNotBlank)
                .toMutableList()

            var hasCurrency = false
            if (tokens.firstOrNull() in CURRENCY_WORDS) {
                hasCurrency = true
                tokens.removeAt(0)
            }
            if (tokens.lastOrNull() in CURRENCY_WORDS) {
                hasCurrency = true
                tokens.removeAt(tokens.lastIndex)
            }
            if (tokens.any { it in CURRENCY_WORDS }) return@forEach

            val hasScale = tokens.any { it in SCALE_WORDS }
            if (!hasCurrency && !hasScale) return@forEach
            if (tokens.none { it in NUMBER_WORDS }) return@forEach

            val amount = parseVietnameseNumberWords(tokens) ?: return@forEach
            add(
                MoneyCandidate(
                    amount = amount,
                    confidence = confidence(hasCurrency, hasScale),
                    start = match.range.first,
                    spanLength = match.value.length,
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
        val punctuationPositions = trimmed.indices.filter { trimmed[it] == '.' || trimmed[it] == ',' }
        if (punctuationPositions.isEmpty()) {
            return parseGroupedInteger(trimmed)?.let(::BigDecimal)
        }

        val decimalPosition = punctuationPositions.last()
        val fractionalPart = trimmed.substring(decimalPosition + 1)
        val fractionalDigits = fractionalPart.filterNot(Char::isWhitespace)

        // One or two final digits are treated as a decimal fraction when a scale follows:
        // `1,5 triệu` is 1.5 million. Three final digits remain a thousands group.
        if (fractionalDigits.length in 1..2 && fractionalDigits.all(Char::isDigit)) {
            val integerPart = trimmed.substring(0, decimalPosition)
            val integerDigits = parseGroupedInteger(integerPart) ?: return null
            return BigDecimal("$integerDigits.$fractionalDigits")
        }

        return parseGroupedInteger(trimmed)?.let(::BigDecimal)
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
        if (groups.drop(1).any { it.length != 3 || it.any { character -> !character.isDigit() } }) return null
        return groups.joinToString(separator = "")
    }

    private fun parseVietnameseNumberWords(tokens: List<String>): Long? {
        if (tokens.isEmpty()) return null

        var total = 0L
        var groupStart = 0
        var sawMillion = false
        var sawThousand = false

        tokens.forEachIndexed { index, token ->
            when (token) {
                "trieu" -> {
                    if (sawMillion || sawThousand) return null
                    val group = parseUnderOneThousand(tokens.subList(groupStart, index)) ?: return null
                    total = safeAdd(total, safeMultiply(group.toLong(), 1_000_000L) ?: return null) ?: return null
                    sawMillion = true
                    groupStart = index + 1
                }

                "nghin", "ngin", "ngan" -> {
                    if (sawThousand) return null
                    val group = parseUnderOneThousand(tokens.subList(groupStart, index)) ?: return null
                    total = safeAdd(total, safeMultiply(group.toLong(), 1_000L) ?: return null) ?: return null
                    sawThousand = true
                    groupStart = index + 1
                }
            }
        }

        if (groupStart < tokens.size) {
            val remainder = parseUnderOneThousand(tokens.subList(groupStart, tokens.size)) ?: return null
            total = safeAdd(total, remainder.toLong()) ?: return null
        } else if (!sawMillion && !sawThousand) {
            return null
        }

        return total
    }

    private fun parseUnderOneThousand(tokens: List<String>): Int? {
        if (tokens.isEmpty()) return null
        if (tokens.any { it in SCALE_WORDS || it in CURRENCY_WORDS }) return null

        val hundredIndex = tokens.indexOf("tram")
        if (hundredIndex >= 0) {
            if (tokens.lastIndexOf("tram") != hundredIndex || hundredIndex !in 0..1) return null
            val hundreds = if (hundredIndex == 0) {
                1
            } else {
                UNIT_VALUES[tokens[0]] ?: return null
            }
            val remainderTokens = tokens.drop(hundredIndex + 1)
            val remainder = if (remainderTokens.isEmpty()) 0 else parseUnderOneHundred(remainderTokens) ?: return null
            return hundreds * 100 + remainder
        }

        return parseUnderOneHundred(tokens)
    }

    private fun parseUnderOneHundred(tokens: List<String>): Int? = when (tokens.size) {
        1 -> UNIT_VALUES[tokens[0]] ?: if (tokens[0] == "muoi") 10 else null
        2 -> {
            when {
                tokens[0] == "muoi" -> 10 + (UNIT_VALUES[tokens[1]] ?: return null)
                tokens[1] == "muoi" -> (UNIT_VALUES[tokens[0]] ?: return null) * 10
                else -> null
            }
        }

        3 -> {
            if (tokens[1] != "muoi") return null
            val tens = UNIT_VALUES[tokens[0]] ?: return null
            val units = UNIT_VALUES[tokens[2]] ?: return null
            tens * 10 + units
        }

        else -> null
    }

    private fun confidence(hasCurrency: Boolean, hasScale: Boolean): Int = when {
        hasCurrency && hasScale -> 400
        hasCurrency -> 350
        hasScale -> 300
        else -> 100
    }

    private fun isLikelyPhoneNumber(normalized: String, match: MatchResult): Boolean {
        val raw = match.value.trim()
        val digits = raw.filter(Char::isDigit)
        val context = normalized.substring(
            maxOf(0, match.range.first - PHONE_CONTEXT_WINDOW),
            minOf(normalized.length, match.range.last + 1 + PHONE_CONTEXT_WINDOW),
        )

        if (PHONE_CONTEXT.containsMatchIn(context)) return true
        if (digits.length in 8..12 && (digits.startsWith('0') || digits.startsWith("84"))) return true
        if (digits.length in 9..12 && raw.all(Char::isDigit)) return true
        if (match.range.first > 0 && normalized[match.range.first - 1] == '+' && digits.length in 8..12) return true
        return false
    }

    private fun normalizeVietnamese(text: String): String {
        val expandedCurrencies = text
            .lowercase(Locale.ROOT)
            .replace(Regex("(?<![\\p{L}])đ(?![\\p{L}])", RegexOption.IGNORE_CASE), " vnd ")
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
        "trieu" -> 1_000_000L
        "nghin", "ngin", "ngan", "k" -> 1_000L
        else -> error("Unknown Vietnamese scale: $name")
    }

    private fun safeMultiply(left: Long, right: Long): Long? =
        runCatching { Math.multiplyExact(left, right) }.getOrNull()

    private fun safeAdd(left: Long, right: Long): Long? =
        runCatching { Math.addExact(left, right) }.getOrNull()

    private data class MoneyCandidate(
        val amount: Long,
        val confidence: Int,
        val start: Int,
        val spanLength: Int,
    )

    private const val MIN_BARE_AMOUNT = 1_000_000L
    private const val MARKER_WINDOW = 32
    private const val PHONE_CONTEXT_WINDOW = 28

    private val CURRENCY_WORDS = setOf("vnd", "dong")
    private val SCALE_WORDS = setOf("trieu", "nghin", "ngin", "ngan")
    private val UNIT_VALUES = mapOf(
        "mot" to 1,
        "hai" to 2,
        "ba" to 3,
        "bon" to 4,
        "nam" to 5,
        "sau" to 6,
        "bay" to 7,
        "tam" to 8,
        "chin" to 9,
    )
    private val NUMBER_WORDS = UNIT_VALUES.keys + setOf("muoi", "tram")

    private val DIGIT_LITERAL = Regex("(?<![a-z0-9])\\d+(?:(?:[.,\\u00A0\\u202F ]+)\\d+)*(?!\\d)")
    private val CURRENCY_AT_END = Regex("(?:^|[^a-z])(vnd|dong)\\s*$")
    private val SUFFIX_MARKERS = Regex("^\\s*(?:(trieu|nghin|ngin|ngan|k)(?![a-z]))?\\s*(?:(vnd|dong)(?![a-z]))?")
    private val GROUP_SEPARATOR = Regex("[.,\\s]+")
    private val WORD_SEPARATOR = Regex("[\\s-]+")
    private val WORD_PHRASE = Regex(
        "(?<![a-z])(?:mot|hai|ba|bon|nam|sau|bay|tam|chin|muoi|tram|nghin|ngin|ngan|trieu|vnd|dong)" +
            "(?:[\\s-]+(?:mot|hai|ba|bon|nam|sau|bay|tam|chin|muoi|tram|nghin|ngin|ngan|trieu|vnd|dong))*(?![a-z])",
    )
    private val PHONE_CONTEXT = Regex("(?:so\\s+dien\\s+thoai|dien\\s+thoai|phone|telephone|tel|hotline|zalo|lien\\s+he)")
}
