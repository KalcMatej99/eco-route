package org.kalc.ecoroute

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class OfficialFuelPriceTest {
    private val html = "<table><tr><th>Obdobje</th><th>NMB-95 (EUR/liter)</th><th>Dizel (EUR/liter)</th></tr><tr><td>od 29. 12. do 11. 1. 2027</td><td>1,450</td><td>1,550</td></tr></table>"
    @Test fun parsesBothFuelsAndYearBoundaryWithWhitespace() {
        val p = OfficialFuelPrice.parse(html.replace(" ", "&nbsp;"), LocalDate.of(2026, 12, 30))
        assertEquals(LocalDate.of(2026,12,29),p.validFrom); assertEquals(LocalDate.of(2027,1,11),p.validUntil)
        assertEquals(1.55,p.prices["diesel"]!!,1e-10); assertEquals(1.45,p.prices["petrol"]!!,1e-10)
    }
    @Test fun neverUsesExpiredQuoteOrGuess() {
        assertThrows(EcoApiException::class.java) { OfficialFuelPrice.parse(html,LocalDate.of(2027,1,12)) }
    }
}
