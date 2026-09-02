package pro.ivol.voicebridge.exchange

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CbrVndXmlParserTest {
    @Test
    fun `parses all conversion quotes from the same official-shaped response`() {
        val parsed = CbrVndXmlParser.parse(
            xml(
                """
                <Valute ID="R01235">
                    <CharCode>USD</CharCode><Nominal>1</Nominal><Value>80,0000</Value>
                </Valute>
                <Valute ID="R01239">
                    <CharCode>EUR</CharCode><Nominal>1</Nominal><Value>99,6820</Value>
                </Valute>
                <Valute ID="R01720">
                    <CharCode>UAH</CharCode><Nominal>10</Nominal><Value>19,2143</Value>
                </Valute>
                <Valute ID="R01150">
                    <NumCode>704</NumCode><CharCode>VND</CharCode>
                    <Nominal>10000</Nominal><Name>Vietnamese dong</Name><Value>30,5584</Value>
                </Valute>
                """.trimIndent(),
            ),
        )

        assertDecimalEquals("10000", parsed.nominalVnd)
        assertDecimalEquals("30.5584", parsed.valueRub)
        assertDecimalEquals("1", parsed.nominalUsd)
        assertDecimalEquals("80.0000", parsed.usdValueRub)
        assertDecimalEquals("1", parsed.nominalEur)
        assertDecimalEquals("99.6820", parsed.eurValueRub)
        assertDecimalEquals("10", parsed.nominalUah)
        assertDecimalEquals("19.2143", parsed.uahValueRub)
        assertEquals("23.08.2026", parsed.effectiveDate)
    }

    @Test
    fun `rejects response without VND`() {
        assertThrows(IllegalArgumentException::class.java) {
            CbrVndXmlParser.parse(xml(validUsd + validEur + validUah))
        }
    }

    @Test
    fun `rejects response without USD`() {
        assertThrows(IllegalArgumentException::class.java) {
            CbrVndXmlParser.parse(xml(validVnd + validEur + validUah))
        }
    }

    @Test
    fun `rejects response without EUR or UAH`() {
        assertThrows(IllegalArgumentException::class.java) {
            CbrVndXmlParser.parse(xml(validVnd + validUsd + validUah))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CbrVndXmlParser.parse(xml(validVnd + validUsd + validEur))
        }
    }

    @Test
    fun `rejects duplicate VND quotes`() {
        assertThrows(IllegalArgumentException::class.java) {
            CbrVndXmlParser.parse(xml(validUsd + validEur + validUah + validVnd + validVnd))
        }
    }

    @Test
    fun `rejects duplicate USD quotes`() {
        assertThrows(IllegalArgumentException::class.java) {
            CbrVndXmlParser.parse(xml(validVnd + validEur + validUah + validUsd + validUsd))
        }
    }

    @Test
    fun `rejects zero or malformed rates`() {
        assertThrows(IllegalArgumentException::class.java) {
            CbrVndXmlParser.parse(
                xml(
                    validUsd + validEur + validUah +
                        "<Valute><CharCode>VND</CharCode><Nominal>0</Nominal><Value>30,0</Value></Valute>",
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CbrVndXmlParser.parse(
                xml(
                    validUsd + validEur + validUah +
                        "<Valute><CharCode>VND</CharCode><Nominal>10000</Nominal><Value>30.0</Value></Valute>",
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CbrVndXmlParser.parse(
                xml(
                    validVnd + validEur + validUah +
                        "<Valute><CharCode>USD</CharCode><Nominal>0</Nominal><Value>80,0</Value></Valute>",
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CbrVndXmlParser.parse(
                xml(
                    validVnd + validEur + validUah +
                        "<Valute><CharCode>USD</CharCode><Nominal>1</Nominal><Value>80.0</Value></Valute>",
                ),
            )
        }
    }

    @Test
    fun `rejects document type and entity declarations before XML parsing`() {
        val malicious = """
            <?xml version="1.0"?>
            <!DOCTYPE ValCurs [<!ENTITY leak SYSTEM "file:///etc/passwd">]>
            <ValCurs Date="23.08.2026">
                <Valute><CharCode>VND</CharCode><Nominal>10000</Nominal><Value>&leak;</Value></Valute>
            </ValCurs>
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            CbrVndXmlParser.parse(malicious)
        }
    }

    @Test
    fun `rejects unexpected root and malformed effective date`() {
        assertThrows(IllegalArgumentException::class.java) {
            CbrVndXmlParser.parse("<Rates Date=\"23.08.2026\"/>")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CbrVndXmlParser.parse(
                xml(
                    body = validVnd + validUsd + validEur + validUah,
                    date = "2026-08-23",
                ),
            )
        }
    }

    private fun xml(body: String, date: String = "23.08.2026"): String =
        "<?xml version=\"1.0\" encoding=\"windows-1251\"?>\n" +
            "<ValCurs Date=\"$date\" name=\"Foreign Currency Market\">\n" +
            body +
            "\n</ValCurs>"

    private fun assertDecimalEquals(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }

    private companion object {
        const val validVnd =
            "<Valute><CharCode>VND</CharCode><Nominal>10000</Nominal><Value>30,0</Value></Valute>"
        const val validUsd =
            "<Valute><CharCode>USD</CharCode><Nominal>1</Nominal><Value>80,0</Value></Valute>"
        const val validEur =
            "<Valute><CharCode>EUR</CharCode><Nominal>1</Nominal><Value>100,0</Value></Valute>"
        const val validUah =
            "<Valute><CharCode>UAH</CharCode><Nominal>10</Nominal><Value>20,0</Value></Valute>"
    }
}
