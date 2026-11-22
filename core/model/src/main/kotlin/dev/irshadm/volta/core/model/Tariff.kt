package dev.irshadm.volta.core.model

import java.time.LocalDate
import kotlin.math.max

/**
 * Slab-based domestic tariff. The defaults are Kerala's KSEB LT-1A rates. Rates are per unit per MONTH; a bi-monthly bill doubles
 * the slab limits. Defaults are the most-cited 2025–27 figures found on 2026-10-26 and MUST be
 * confirmed against the user's bill (the screen shows when they were last verified).
 */
data class Tariff(
    /** Telescopic slabs up to [telescopicLimit]: (size in units/month, ₹/unit). */
    val telescopic: List<Pair<Int, Double>> = listOf(50 to 3.35, 50 to 4.25, 50 to 5.35, 50 to 7.20, 50 to 8.50),
    /** Above the limit ALL units are billed at the band's rate: (up to units/month, ₹/unit). */
    val nonTelescopic: List<Pair<Int, Double>> = listOf(300 to 6.75, 350 to 7.60, 400 to 7.95, 500 to 8.25, Int.MAX_VALUE to 9.20),
    val telescopicLimit: Int = 250,
    val dutyPercent: Double = 10.0,
    val fuelSurchargePerUnit: Double = 0.0,
    val billingMonths: Int = 2,
    val verifiedOn: String? = null,
) {
    /** Energy charge + surcharge + duty for [units] consumed in one billing cycle (fixed charge excluded). */
    fun billForCycle(units: Double): Double {
        val perMonth = units / billingMonths
        val monthly = if (perMonth <= telescopicLimit) {
            var left = perMonth
            var sum = 0.0
            for ((size, rate) in telescopic) {
                val u = minOf(left, size.toDouble())
                sum += u * rate
                left -= u
                if (left <= 0) break
            }
            sum
        } else {
            perMonth * nonTelescopic.first { perMonth <= it.first }.second
        }
        val energy = monthly * billingMonths + units * fuelSurchargePerUnit
        return energy * (1 + dutyPercent / 100.0)
    }

    /** Extra bill caused by [evUnits] on top of [householdUnits] (both per cycle). */
    fun evCost(householdUnits: Double, evUnits: Double): Double = max(0.0, billForCycle(householdUnits + evUnits) - billForCycle(householdUnits))

    /** Units left per month before the non-telescopic jump (negative = already over). */
    fun headroomPerMonth(totalUnitsPerCycle: Double): Double = telescopicLimit - totalUnitsPerCycle / billingMonths
}

enum class ChargeCategory(val label: String) { HOME("Home"), PUBLIC_AC("Public AC"), PUBLIC_DC("DC fast") }

/** Home charging and bill settings. */
data class EnergySettings(
    val homeLat: Double? = null,
    val homeLng: Double? = null,
    val homeRadiusM: Double = 150.0,
    val homeEfficiency: Double = 0.88,
    val publicAcEfficiency: Double = 0.88,
    val dcEfficiency: Double = 0.93,
    val publicAcRatePerKwh: Double = 14.0,
    val dcRatePerKwh: Double = 22.0,
    val householdUnitsPerCycle: Double = 260.0, // sample: a flat with ACs, about 130 units a month, until the owner sets it
    val billCycleStartDay: Int = 20, // the owner's bill cycle starts on the 20th...
    val billCycleStartMonth: Int = 10, // ...of October, December, February and so on (1 = January)
    val dcThresholdKw: Double = 15.0,
) {
    val hasHome: Boolean get() = homeLat != null && homeLng != null

    /**
     * When the bill cycle containing [today] began: the start day in the latest month on the cycle's grid. A 2-month
     * cycle anchored on October starts on 20 Oct, 20 Dec, 20 Feb and so on; a monthly one starts every month.
     */
    fun cycleStart(today: LocalDate, billingMonths: Int): LocalDate {
        var month = today.withDayOfMonth(1)
        if (today.dayOfMonth < billCycleStartDay.coerceAtMost(today.lengthOfMonth())) month = month.minusMonths(1)
        while (Math.floorMod(month.monthValue - billCycleStartMonth, billingMonths) != 0) month = month.minusMonths(1)
        return month.withDayOfMonth(billCycleStartDay.coerceAtMost(month.lengthOfMonth()))
    }

    companion object {
        /** The same household use, told per cycle of [to] months instead of [from]. */
        fun unitsForCycle(units: Double, from: Int, to: Int): Double = units * to / from
    }
}
