package pro.ivol.voicebridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import pro.ivol.voicebridge.domain.ConversionCurrency

@Composable
fun NumericInputDialog(
    initialValue: String,
    selectedCurrencies: Set<ConversionCurrency>,
    estimateCurrencies: (Long) -> QuickCurrencyEstimateUi?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var digits by remember(initialValue) {
        mutableStateOf(initialValue.filter(Char::isDigit).take(MAX_DIGITS))
    }
    val estimate = digits.toLongOrNull()?.let(estimateCurrencies)

    fun append(token: String) {
        digits = when {
            digits.length >= MAX_DIGITS -> digits
            digits.isBlank() && token.all { it == '0' } -> "0"
            digits == "0" && token.any { it != '0' } -> token.take(MAX_DIGITS)
            else -> (digits + token).take(MAX_DIGITS)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 520.dp),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Quick amount entry",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = estimate
                        ?.let { value ->
                            "≈ ${formatSelectedCurrencyEstimates(selectedCurrencies) { currency ->
                                value.minorUnits(currency)
                            }}"
                        }
                        ?: "≈ ${selectedCurrencies
                            .ordered()
                            .joinToString("  ·  ") { currency -> "${currency.symbol}—" }}",
                    modifier = Modifier.semantics {
                        contentDescription = estimate
                            ?.let { value ->
                                "Approximately ${formatSelectedCurrencyEstimates(
                                    selectedCurrencies,
                                ) { currency -> value.minorUnits(currency) }}"
                            }
                            ?: "Currency estimates unavailable"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Text(
                        text = formatNumberForDisplay(digits.ifBlank { "0" }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 18.dp)
                            .semantics { contentDescription = "Entered ${digits.ifBlank { "0" }}" },
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 44.sp,
                        lineHeight = 50.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                    )
                }

                KEYPAD_ROWS.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { token ->
                            Button(
                                onClick = { append(token) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(64.dp)
                                    .semantics { contentDescription = "Enter $token" },
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(
                                    text = token,
                                    fontSize = if (token.length == 1) 28.sp else 22.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { digits = "" },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Clear")
                    }
                    OutlinedButton(
                        onClick = { digits = digits.dropLast(1) },
                        enabled = digits.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "Delete the last digit" },
                    ) {
                        Text("⌫", fontSize = 24.sp)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onConfirm(formatNumberForDisplay(digits)) },
                        enabled = digits.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }
}

internal fun formatNumberForDisplay(digits: String): String =
    digits.filter(Char::isDigit)
        .reversed()
        .chunked(3)
        .joinToString(" ")
        .reversed()

internal fun formatUsdCentsForDisplay(usdCents: Long): String =
    formatCurrencyMinorUnitsForDisplay(ConversionCurrency.USD, usdCents)

internal fun formatCurrencyMinorUnitsForDisplay(
    currency: ConversionCurrency,
    minorUnits: Long,
): String {
    require(minorUnits >= 0L) { "Currency estimate cannot be negative." }
    val divisor = if (currency.fractionDigits == 0) 1L else 100L
    val whole = formatNumberForDisplay((minorUnits / divisor).toString())
    val remainder = minorUnits % divisor
    val amount = if (remainder == 0L || currency.fractionDigits == 0) {
        whole
    } else {
        "$whole.${remainder.toString().padStart(currency.fractionDigits, '0')}"
    }
    return when (currency) {
        ConversionCurrency.USD,
        ConversionCurrency.EUR,
        -> "${currency.symbol}$amount"
        ConversionCurrency.RUB,
        ConversionCurrency.UAH,
        -> "$amount ${currency.symbol}"
    }
}

internal fun formatSelectedCurrencyEstimates(
    selectedCurrencies: Set<ConversionCurrency>,
    minorUnits: (ConversionCurrency) -> Long?,
): String = selectedCurrencies
    .ordered()
    .mapNotNull { currency ->
        minorUnits(currency)?.let { amount ->
            formatCurrencyMinorUnitsForDisplay(currency, amount)
        }
    }
    .joinToString("  ·  ")

internal fun Set<ConversionCurrency>.ordered(): List<ConversionCurrency> =
    ConversionCurrency.entries.filter(::contains)

internal fun QuickCurrencyEstimateUi.minorUnits(currency: ConversionCurrency): Long =
    when (currency) {
        ConversionCurrency.RUB -> estimatedRubles
        ConversionCurrency.USD -> estimatedUsdCents
        ConversionCurrency.EUR -> estimatedEurCents
        ConversionCurrency.UAH -> estimatedUahKopecks
    }

internal fun CurrencyConversionUi.minorUnits(currency: ConversionCurrency): Long? =
    when (currency) {
        ConversionCurrency.RUB -> estimatedRubles
        ConversionCurrency.USD -> estimatedUsdCents
        ConversionCurrency.EUR -> estimatedEurCents
        ConversionCurrency.UAH -> estimatedUahKopecks
    }

private const val MAX_DIGITS = 15

private val KEYPAD_ROWS = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("00", "0", "000"),
)
