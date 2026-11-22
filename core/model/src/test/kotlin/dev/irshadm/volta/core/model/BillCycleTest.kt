package dev.irshadm.volta.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class BillCycleTest {
    private val s = EnergySettings(billCycleStartDay = 20, billCycleStartMonth = 10)

    private fun start(today: String, months: Int) = s.cycleStart(LocalDate.parse(today), months).toString()

    @Test
    fun monthlyCycleStartsEveryMonth() {
        assertEquals("2026-11-20", start("2026-11-23", 1))
        assertEquals("2026-11-20", start("2026-11-20", 1))
        assertEquals("2026-10-20", start("2026-11-19", 1))
    }

    @Test
    fun monthlyCycleRunsOverTheNewYear() {
        assertEquals("2026-12-20", start("2027-01-05", 1))
        assertEquals("2027-01-20", start("2027-01-20", 1))
    }

    @Test
    fun twoMonthCycleKeepsItsGrid() {
        assertEquals("2026-10-20", start("2026-11-23", 2))
        assertEquals("2026-10-20", start("2026-12-19", 2))
        assertEquals("2026-12-20", start("2027-01-25", 2))
        assertEquals("2027-02-20", start("2027-02-20", 2))
    }

    @Test
    fun switchingTheCycleKeepsTheSameHouseholdUse() {
        val monthly = EnergySettings.unitsForCycle(255.0, from = 2, to = 1)
        assertEquals(127.5, monthly, 1e-9)
        assertEquals(255.0, EnergySettings.unitsForCycle(monthly, from = 1, to = 2), 1e-9)
    }

    @Test
    fun theSameUseCostsHalfOnAMonthlyBill() {
        val twoMonths = Tariff(billingMonths = 2)
        val oneMonth = twoMonths.copy(billingMonths = 1)
        assertEquals(twoMonths.billForCycle(360.0) / 2, oneMonth.billForCycle(EnergySettings.unitsForCycle(360.0, 2, 1)), 1e-9)
        assertEquals(twoMonths.headroomPerMonth(360.0), oneMonth.headroomPerMonth(180.0), 1e-9)
    }
}
