package pro.ivol.voicebridge.exchange

import java.io.StringReader
import java.math.BigDecimal
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException

internal data class ParsedCbrVndRate(
    val nominalVnd: BigDecimal,
    val valueRub: BigDecimal,
    val nominalUsd: BigDecimal,
    val usdValueRub: BigDecimal,
    val nominalEur: BigDecimal,
    val eurValueRub: BigDecimal,
    val nominalUah: BigDecimal,
    val uahValueRub: BigDecimal,
    val effectiveDate: String,
) {
    init {
        require(nominalVnd.signum() > 0) { "VND nominal must be positive." }
        require(valueRub.signum() > 0) { "VND value in RUB must be positive." }
        require(nominalUsd.signum() > 0) { "USD nominal must be positive." }
        require(usdValueRub.signum() > 0) { "USD value in RUB must be positive." }
        require(nominalEur.signum() > 0) { "EUR nominal must be positive." }
        require(eurValueRub.signum() > 0) { "EUR value in RUB must be positive." }
        require(nominalUah.signum() > 0) { "UAH nominal must be positive." }
        require(uahValueRub.signum() > 0) { "UAH value in RUB must be positive." }
        require(CBR_DATE_REGEX.matches(effectiveDate)) { "Invalid CBR date." }
    }

    private companion object {
        val CBR_DATE_REGEX = Regex("(?:0[1-9]|[12][0-9]|3[01])\\.(?:0[1-9]|1[0-2])\\.\\d{4}")
    }
}

/** Strict, XXE-safe parser for the bounded CBR XML response. */
internal object CbrVndXmlParser {
    private const val ROOT_TAG = "ValCurs"
    private const val CURRENCY_TAG = "Valute"
    private const val VND_CODE = "VND"
    private const val USD_CODE = "USD"
    private const val EUR_CODE = "EUR"
    private const val UAH_CODE = "UAH"
    private val unsignedInteger = Regex("[0-9]+")
    private val unsignedDecimalComma = Regex("[0-9]+(?:,[0-9]+)?")

    fun parse(xml: String): ParsedCbrVndRate {
        require(xml.isNotBlank()) { "The CBR response is empty." }
        rejectDeclarationsThatCanDefineEntities(xml)

        val factory = hardenedFactory()
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ ->
                throw SAXException("External XML entities are not allowed.")
            }
        }
        val document = builder.parse(InputSource(StringReader(xml)))
        val root = document.documentElement
        require(root != null && root.tagName == ROOT_TAG) { "Unexpected CBR XML root." }

        val effectiveDate = root.getAttribute("Date").trim()
        val vnd = root.singleCurrency(VND_CODE)
        val usd = root.singleCurrency(USD_CODE)
        val eur = root.singleCurrency(EUR_CODE)
        val uah = root.singleCurrency(UAH_CODE)
        val vndNominalText = vnd.directChildText("Nominal")
        val vndValueText = vnd.directChildText("Value")
        val usdNominalText = usd.directChildText("Nominal")
        val usdValueText = usd.directChildText("Value")
        val eurNominalText = eur.directChildText("Nominal")
        val eurValueText = eur.directChildText("Value")
        val uahNominalText = uah.directChildText("Nominal")
        val uahValueText = uah.directChildText("Value")
        require(unsignedInteger.matches(vndNominalText)) { "Invalid VND nominal." }
        require(unsignedDecimalComma.matches(vndValueText)) { "Invalid VND value." }
        require(unsignedInteger.matches(usdNominalText)) { "Invalid USD nominal." }
        require(unsignedDecimalComma.matches(usdValueText)) { "Invalid USD value." }
        require(unsignedInteger.matches(eurNominalText)) { "Invalid EUR nominal." }
        require(unsignedDecimalComma.matches(eurValueText)) { "Invalid EUR value." }
        require(unsignedInteger.matches(uahNominalText)) { "Invalid UAH nominal." }
        require(unsignedDecimalComma.matches(uahValueText)) { "Invalid UAH value." }

        return ParsedCbrVndRate(
            nominalVnd = vndNominalText.toBigDecimal(),
            valueRub = vndValueText.replace(',', '.').toBigDecimal(),
            nominalUsd = usdNominalText.toBigDecimal(),
            usdValueRub = usdValueText.replace(',', '.').toBigDecimal(),
            nominalEur = eurNominalText.toBigDecimal(),
            eurValueRub = eurValueText.replace(',', '.').toBigDecimal(),
            nominalUah = uahNominalText.toBigDecimal(),
            uahValueRub = uahValueText.replace(',', '.').toBigDecimal(),
            effectiveDate = effectiveDate,
        )
    }

    private fun Element.singleCurrency(charCode: String): Element {
        val matches = buildList {
            val currencies = getElementsByTagName(CURRENCY_TAG)
            for (index in 0 until currencies.length) {
                val currency = currencies.item(index) as? Element ?: continue
                if (currency.directChildText("CharCode") == charCode) add(currency)
            }
        }
        require(matches.size == 1) {
            "The CBR response must contain exactly one $charCode quote."
        }
        return matches.single()
    }

    private fun hardenedFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }

            // The declaration pre-check below is the fail-closed protection on
            // Android parsers that do not implement every optional SAX feature.
            safeSetFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            safeSetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            safeSetFeature("http://xml.org/sax/features/external-general-entities", false)
            safeSetFeature("http://xml.org/sax/features/external-parameter-entities", false)
            safeSetFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }

    private fun DocumentBuilderFactory.safeSetFeature(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private fun rejectDeclarationsThatCanDefineEntities(xml: String) {
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) {
            "DOCTYPE declarations are not allowed."
        }
        require(!xml.contains("<!ENTITY", ignoreCase = true)) {
            "ENTITY declarations are not allowed."
        }
    }

    private fun Element.directChildText(tag: String): String {
        var match: String? = null
        val children = childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element && child.tagName == tag) {
                require(match == null) { "Duplicate $tag element in a currency quote." }
                match = child.textContent?.trim().orEmpty()
            }
        }
        return requireNotNull(match) { "Missing $tag element in a currency quote." }
    }
}
