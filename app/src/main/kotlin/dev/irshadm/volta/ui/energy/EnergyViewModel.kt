package dev.irshadm.volta.ui.energy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.irshadm.volta.core.model.ChargeCategory
import dev.irshadm.volta.core.model.EnergyReport
import dev.irshadm.volta.core.model.EnergySettings
import dev.irshadm.volta.core.model.SessionEnergy
import dev.irshadm.volta.core.model.Tariff
import dev.irshadm.volta.core.model.VehicleProfile
import dev.irshadm.volta.data.SettingsRepository
import dev.irshadm.volta.data.db.ChargeDao
import dev.irshadm.volta.data.db.ChargeSessionEntity
import dev.irshadm.volta.data.db.SocReadingDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/** Reporting period. The screen labels these itself ("This bill cycle", the month's name, the year). */
enum class Period { Cycle, Month, Year, Custom }

/** One calendar month for the "By month" chart: kWh per category and the estimated ₹ for the month. */
data class MonthBar(val label: String, val kwh: Map<ChargeCategory, Double>, val cost: Double, val current: Boolean) {
    val totalKwh: Double get() = kwh.values.sum()
}

data class EnergyUiState(
    val period: Period = Period.Cycle,
    val from: Long = 0,
    val to: Long = 0,
    val report: EnergyReport? = null,
    val sessions: List<ChargeSessionEntity> = emptyList(),
    /** Months from the first one with data up to the current month (no empty leading months). */
    val months: List<MonthBar> = emptyList(),
    val tariff: Tariff = Tariff(),
    val energy: EnergySettings = EnergySettings(),
    val capacityKwh: Double = VehicleProfile().usableCapacityKwh,
    /** Used to turn slab headroom into km when the period has no km of its own. */
    val fallbackKmPerKwh: Double = VehicleProfile().highwayKmPerKwh,
    val loaded: Boolean = false,
) {
    val hasHome: Boolean get() = energy.hasHome
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EnergyViewModel @Inject constructor(
    private val charges: ChargeDao,
    private val readings: SocReadingDao,
    private val settings: SettingsRepository,
) : ViewModel() {
    private data class Selection(val period: Period, val customFrom: Long = 0, val customTo: Long = 0)
    private val selection = MutableStateFlow(Selection(Period.Cycle))

    private fun range(s: Selection, e: EnergySettings, t: Tariff): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return when (s.period) {
            Period.Month -> {
                c.set(Calendar.DAY_OF_MONTH, 1)
                c.timeInMillis to now
            }

            Period.Year -> {
                c.set(Calendar.DAY_OF_YEAR, 1)
                c.timeInMillis to now
            }

            Period.Custom -> s.customFrom to s.customTo

            Period.Cycle -> {
                val zone = java.time.ZoneId.systemDefault()
                e.cycleStart(java.time.LocalDate.now(zone), t.billingMonths).atStartOfDay(zone).toInstant().toEpochMilli() to now
            }
        }
    }

    private fun kwhOf(s: ChargeSessionEntity, capacity: Double): Double = ((s.endKwh ?: s.startKwh ?: 0.0) - (s.startKwh ?: 0.0)).takeIf { it > 0 }
        ?: (((s.endSoc ?: 0.0) - (s.startSoc ?: 0.0)) / 100 * capacity).coerceAtLeast(0.0)

    private val sixMonthsAgo = Calendar.getInstance().apply {
        add(Calendar.MONTH, -5)
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
    }.timeInMillis

    val state: StateFlow<EnergyUiState> = combine(selection, settings.energySettings, settings.tariff) { s, e, t -> Triple(s, e, t) }.flatMapLatest { (sel, e, t) ->
        val (from, to) = range(sel, e, t)
        combine(charges.between(from, to), charges.between(sixMonthsAgo, Long.MAX_VALUE), readings.kmBetween(from, to), settings.vehicleProfile) { list, recent, km, profile ->
            val cap = profile.usableCapacityKwh
            val energies = list.map { SessionEnergy(ChargeCategory.valueOf(it.category), kwhOf(it, cap), it.gridUnits, it.costRs) }
            val months = ((to - from) / 86_400_000.0 / 30.44).coerceAtLeast(0.1)
            EnergyUiState(
                period = sel.period, from = from, to = to,
                report = EnergyReport.compute(energies, e, t, months, km),
                sessions = list, months = monthBars(recent, cap, e, t),
                tariff = t, energy = e, capacityKwh = cap, fallbackKmPerKwh = profile.highwayKmPerKwh, loaded = true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EnergyUiState())

    /** Last six months, trimmed to start at the first month that has a session. */
    private fun monthBars(recent: List<ChargeSessionEntity>, cap: Double, e: EnergySettings, t: Tariff): List<MonthBar> {
        val now = Calendar.getInstance()
        val fmt = java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault())
        return (5 downTo 0).map { back ->
            val m = Calendar.getInstance().apply { add(Calendar.MONTH, -back) }
            val inMonth = recent.filter { s ->
                Calendar.getInstance().apply { timeInMillis = s.startedAt }.let { it.get(Calendar.YEAR) == m.get(Calendar.YEAR) && it.get(Calendar.MONTH) == m.get(Calendar.MONTH) }
            }
            val energies = inMonth.map { SessionEnergy(ChargeCategory.valueOf(it.category), kwhOf(it, cap), it.gridUnits, it.costRs) }
            val cost = if (energies.isEmpty()) 0.0 else EnergyReport.compute(energies, e, t, 1.0, null).totalCost
            MonthBar(
                label = fmt.format(m.time),
                kwh = ChargeCategory.entries.associateWith { cat -> inMonth.filter { it.category == cat.name }.sumOf { kwhOf(it, cap) } },
                cost = cost,
                current = back == 0 && now.get(Calendar.MONTH) == m.get(Calendar.MONTH),
            )
        }.dropWhile { it.totalKwh < 0.1 }
    }

    fun select(p: Period) {
        selection.value = selection.value.copy(period = p)
    }
    fun selectCustom(from: Long, to: Long) {
        selection.value = Selection(Period.Custom, from, to + 86_399_999)
    }

    /** The user corrects a session's type (e.g. a friend's house counted as public). */
    fun setCategory(s: ChargeSessionEntity, c: ChargeCategory) = viewModelScope.launch {
        charges.update(s.copy(category = c.name, userEdited = true))
    }
}
