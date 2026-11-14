package dev.irshadm.volta.ui.home

import dev.irshadm.volta.core.model.Timed
import dev.irshadm.volta.core.model.VehicleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Comet sends a long row of codes while driving; only the ones Volta can name may become alerts. */
class CarWarningCodesTest {
    private val now = 1_800_000_000_000L

    private fun status(codes: List<Int>, driving: Boolean) = VehicleStatus(
        ignitionOn = Timed(driving, now),
        speedKmh = Timed(if (driving) 42.0 else 0.0, now),
        warningCodes = Timed(codes, now),
    )

    @Test
    fun `unknown codes never become alerts, driving or parked`() {
        val codes = (1..29).filter { it != 4 }
        assertTrue(carAlerts(status(codes, driving = true), now, 15).isEmpty())
        assertTrue(carAlerts(status(codes, driving = false), now, 15).isEmpty())
    }

    @Test
    fun `tyre code still alerts once, even when repeated`() {
        val alerts = carAlerts(status(listOf(4, 4, 17), driving = true), now, 15)
        assertEquals(listOf("Tyres"), alerts.map { it.short })
    }

    @Test
    fun `other codes are listed sorted and once, only while parked`() {
        val codes = listOf(29, 4, 3, 29, 12)
        assertEquals(listOf(3, 12, 29), otherCarCodes(status(codes, driving = false)))
        assertTrue(otherCarCodes(status(codes, driving = true)).isEmpty())
    }
}
