package dev.irshadm.volta.ui.energy

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.irshadm.volta.core.designsystem.LocalReducedMotion
import dev.irshadm.volta.core.designsystem.VoltaColors
import dev.irshadm.volta.core.designsystem.VoltaIcons
import dev.irshadm.volta.core.designsystem.VoltaMotion
import dev.irshadm.volta.core.designsystem.tabular
import dev.irshadm.volta.core.model.ChargeCategory
import dev.irshadm.volta.core.model.EnergyReport
import dev.irshadm.volta.core.model.EnergySettings
import dev.irshadm.volta.data.db.ChargeSessionEntity
import dev.irshadm.volta.ui.components.VoltaCard
import dev.irshadm.volta.ui.components.appear
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun color(c: ChargeCategory) = when (c) {
    ChargeCategory.HOME -> VoltaColors.Green
    ChargeCategory.PUBLIC_AC -> VoltaColors.BlueSoft
    ChargeCategory.PUBLIC_DC -> VoltaColors.Gold
}

private fun icon(c: ChargeCategory) = when (c) {
    ChargeCategory.HOME -> VoltaIcons.Pin
    ChargeCategory.PUBLIC_AC -> VoltaIcons.Plug
    ChargeCategory.PUBLIC_DC -> VoltaIcons.Bolt
}

private fun rs(x: Double) = "₹" + "%,d".format(x.roundToInt())
private val dayFmt = SimpleDateFormat("d MMM", Locale.getDefault())
private val monthFmt = SimpleDateFormat("MMMM", Locale.getDefault())

/** Calendar glyph in the VoltaIcons stroke style, for the date button in the header. */
private val CalendarIcon: ImageVector = ImageVector.Builder("calendar", 24.dp, 24.dp, 24f, 24f).apply {
    listOf("M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M3 10h18M8 3v4M16 3v4").forEach {
        addPath(addPathNodes(it), stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }
}.build()

/** Sessions under this many kWh on one day fold into a single "tiny top-ups" row. */
private const val TINY_KWH = 0.5

private fun kwhOf(s: ChargeSessionEntity, capacity: Double): Double = ((s.endKwh ?: s.startKwh ?: 0.0) - (s.startKwh ?: 0.0)).takeIf { it > 0 }
    ?: (((s.endSoc ?: 0.0) - (s.startSoc ?: 0.0)) / 100 * capacity).coerceAtLeast(0.0)

private fun costOf(s: ChargeSessionEntity, kwh: Double, r: EnergyReport, e: EnergySettings): Double = s.costRs ?: when (ChargeCategory.valueOf(s.category)) {
    ChargeCategory.HOME -> (s.gridUnits ?: (kwh / e.homeEfficiency)) * r.costPerHomeUnit
    ChargeCategory.PUBLIC_AC -> kwh / e.publicAcEfficiency * e.publicAcRatePerKwh
    ChargeCategory.PUBLIC_DC -> kwh / e.dcEfficiency * e.dcRatePerKwh
}

private fun dayKey(t: Long): Long = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 1000L + it.get(Calendar.DAY_OF_YEAR) }

/** "8 h 13 min", "8 h", "25 min". */
private fun duration(mins: Long): String = when {
    mins >= 60 && mins % 60 == 0L -> "${mins / 60} h"
    mins >= 60 -> "${mins / 60} h ${mins % 60} min"
    else -> "$mins min"
}

private sealed interface SessionItem {
    data class One(val s: ChargeSessionEntity) : SessionItem
    data class Tiny(val day: Long, val sessions: List<ChargeSessionEntity>) : SessionItem
}

/** Keeps the list order; two or more finished sessions under [TINY_KWH] on the same day become one [SessionItem.Tiny]. */
private fun fold(sessions: List<ChargeSessionEntity>, capacity: Double): List<SessionItem> {
    val tinyByDay = sessions.filter { it.endedAt != null && kwhOf(it, capacity) < TINY_KWH }.groupBy { dayKey(it.startedAt) }.filterValues { it.size >= 2 }
    val folded = HashSet<Long>()
    return sessions.mapNotNull { s ->
        val key = dayKey(s.startedAt)
        val group = tinyByDay[key]
        when {
            group == null || s !in group -> SessionItem.One(s)
            folded.add(key) -> SessionItem.Tiny(s.startedAt, group)
            else -> null
        }
    }
}

@Composable private fun heroStyle() = MaterialTheme.typography.displayLarge.copy(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold)

@Composable private fun billStyle() = MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold).tabular()

@Composable private fun statStyle() = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold).tabular()

@Composable private fun rowTitle() = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)

@Composable private fun rowSub() = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnergyScreen(onSettings: () -> Unit, vm: EnergyViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        val picker = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton({
                    val a = picker.selectedStartDateMillis
                    val b = picker.selectedEndDateMillis ?: a
                    if (a != null && b != null) vm.selectCustom(a, b)
                    picking = false
                }, enabled = picker.selectedStartDateMillis != null) { Text("Show", color = VoltaColors.Gold) }
            },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel", color = VoltaColors.Muted) } },
            colors = DatePickerDefaults.colors(containerColor = VoltaColors.Surface),
        ) {
            DateRangePicker(picker, Modifier.height(480.dp), title = { Text("Pick dates", Modifier.padding(16.dp)) })
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp).appear(0), verticalAlignment = Alignment.CenterVertically) {
            Text("Energy", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
            RoundIconButton(CalendarIcon, "Pick dates", active = state.period == Period.Custom) { picking = true }
        }
        PeriodSegments(state.period, vm::select, Modifier.padding(top = 12.dp).appear(1))
        if (state.loaded && state.from > 0) {
            Text(
                "${dayFmt.format(Date(state.from))} – ${dayFmt.format(Date(state.to))}",
                style = MaterialTheme.typography.bodySmall,
                color = VoltaColors.Dim,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }
        val r = state.report ?: return@Column

        HeroCard(r, state.from, state.to, state.period, Modifier.padding(top = 16.dp).appear(2))
        BillCard(r, state, onSettings, Modifier.padding(top = 12.dp).appear(3))
        if (!state.hasHome) {
            VoltaCard(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClick = onSettings), radius = 18.dp) {
                Text("Set your home location", style = MaterialTheme.typography.titleSmall)
                Text("Charging there counts as home electricity; everything else is public. Settings → Home & electricity.", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
            }
        }
        MonthChart(state.months, Modifier.padding(top = 12.dp).appear(4))

        Row(Modifier.padding(horizontal = 4.dp).padding(top = 24.dp)) {
            Text("Charging sessions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).alignByBaseline())
            if (state.sessions.isNotEmpty()) Text("hold to change type", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Dim, modifier = Modifier.alignByBaseline())
        }
        if (state.sessions.isNotEmpty()) {
            var editing by remember { mutableStateOf<ChargeSessionEntity?>(null) }
            editing?.let { s ->
                CategoryDialog(s, onDismiss = { editing = null }) { c ->
                    vm.setCategory(s, c)
                    editing = null
                }
            }
            val items = remember(state.sessions, state.capacityKwh) { fold(state.sessions.take(40), state.capacityKwh) }
            VoltaCard(Modifier.fillMaxWidth().padding(top = 10.dp).appear(5), radius = 18.dp, padding = PaddingValues(0.dp)) {
                Column {
                    items.forEachIndexed { i, item ->
                        if (i > 0) HorizontalDivider(color = VoltaColors.Border)
                        when (item) {
                            is SessionItem.One -> SessionRow(item.s, r, state) { editing = item.s }
                            is SessionItem.Tiny -> TinyRow(item, r, state)
                        }
                    }
                }
            }
        }
        val onlyHome = state.sessions.isNotEmpty() && state.sessions.all { it.category == ChargeCategory.HOME.name }
        Text(
            (if (state.sessions.isEmpty()) "Nothing logged in this period yet. " else "") +
                "Sessions are recorded whenever the car reports it is plugged in." +
                (if (onlyHome) " Public and DC sessions appear here with their own colour once you have one." else ""),
            style = MaterialTheme.typography.bodySmall,
            color = VoltaColors.Dim,
            modifier = Modifier.padding(horizontal = 4.dp).padding(top = 14.dp),
        )
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(if (active) VoltaColors.tint(VoltaColors.Gold, 0.18f) else VoltaColors.Surface)
            .border(1.dp, if (active) VoltaColors.tint(VoltaColors.Gold, 0.5f) else VoltaColors.Border, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = if (active) VoltaColors.Gold else VoltaColors.TextSoft, modifier = Modifier.size(18.dp)) }
}

/** "This bill cycle" · the month's name · the year. A custom range is picked from the calendar button, so nothing is lit then. */
@Composable
private fun PeriodSegments(selected: Period, onSelect: (Period) -> Unit, modifier: Modifier) {
    val now = remember { Date() }
    val items = remember(now) {
        listOf(Period.Cycle to "This bill cycle", Period.Month to monthFmt.format(now), Period.Year to Calendar.getInstance().get(Calendar.YEAR).toString())
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (p, label) ->
            val on = p == selected
            val bg by animateColorAsState(if (on) VoltaColors.Border else Color.Transparent, tween(200), label = "segment")
            Box(
                Modifier.clip(RoundedCornerShape(11.dp)).background(bg).clickable(role = Role.Tab) { onSelect(p) }.height(34.dp).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = MaterialTheme.typography.titleSmall, color = if (on) VoltaColors.Text else VoltaColors.Muted) }
        }
    }
}

/** Money first: the period's charging cost, kWh beside it, then ₹/km, ₹/kWh and the share charged at home. */
@Composable
private fun HeroCard(r: EnergyReport, from: Long, to: Long, key: Any, modifier: Modifier) {
    VoltaCard(modifier.fillMaxWidth(), radius = 22.dp, padding = PaddingValues(18.dp)) {
        Text("Charging cost · ${dayFmt.format(Date(from))} – ${dayFmt.format(Date(to))}", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
        Row {
            Text(rs(r.totalCost), style = heroStyle(), modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(10.dp))
            Text("${r.totalKwh.roundToInt()} kWh", style = MaterialTheme.typography.bodyLarge, color = VoltaColors.Muted, modifier = Modifier.alignByBaseline())
        }
        val total = r.totalKwh
        if (total > 0.5) {
            val km = r.km
            val perKm = r.costPerKm
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (km != null && perKm != null) Stat("₹%.2f".format(perKm), "per km · ${km.roundToInt()} km")
                Stat("₹%.1f".format(r.totalCost / total), "per kWh")
                Stat("${((r.kwh[ChargeCategory.HOME] ?: 0.0) / total * 100).roundToInt()} %", "charged at home", VoltaColors.Green)
            }
        }
        // The split only earns its place once more than one kind of charging has happened.
        val active = ChargeCategory.entries.filter { (r.kwh[it] ?: 0.0) > 0.05 }
        if (active.size > 1) {
            SplitBar(r.kwh, key, Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                active.forEach { c ->
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(color(c)))
                            Text(" ${c.label}", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
                        }
                        Text("%.1f kWh".format(r.kwh[c] ?: 0.0), style = MaterialTheme.typography.titleSmall.tabular())
                        Text(rs(if (c == ChargeCategory.HOME) r.homeCost else r.publicCost[c] ?: 0.0), style = MaterialTheme.typography.bodySmall.tabular(), color = VoltaColors.Dim)
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color = VoltaColors.Text) {
    Column {
        Text(value, style = statStyle(), color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
    }
}

/** Electricity bill for one cycle, how much the car adds, and how close the household is to the flat-rate jump, in units and in km. */
@Composable
private fun BillCard(r: EnergyReport, state: EnergyUiState, onSettings: () -> Unit, modifier: Modifier) {
    val t = state.tariff
    val limit = t.telescopicLimit * t.billingMonths.toDouble()
    // Household + car for a whole cycle, as the report projects it.
    val projected = limit - r.headroomPerMonth * t.billingMonths
    // "So far" is only meaningful when the period is the bill cycle itself: the household's share of the cycle
    // elapsed (an estimate from Settings) plus the car's actual units in the period.
    val used: Double? = if (state.period == Period.Cycle && state.from > 0) {
        val end = Calendar.getInstance().apply {
            timeInMillis = state.from
            add(Calendar.MONTH, t.billingMonths)
        }.timeInMillis
        val elapsed = ((state.to - state.from).toDouble() / (end - state.from).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        (state.energy.householdUnitsPerCycle * elapsed + r.homeUnits).coerceAtMost(projected)
    } else {
        null
    }
    val over = projected > limit
    val year = Calendar.getInstance().get(Calendar.YEAR).toString()
    VoltaCard(modifier.fillMaxWidth(), radius = 22.dp, padding = PaddingValues(18.dp)) {
        Row {
            Text("Electricity bill · ${if (t.billingMonths == 1) "monthly" else "${t.billingMonths}-month cycle"}", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted, modifier = Modifier.weight(1f).alignByBaseline())
            Text(
                t.verifiedOn?.let { "rates ${it.removeSuffix(" $year")}" } ?: "sample rates",
                style = MaterialTheme.typography.labelSmall,
                color = if (t.verifiedOn == null) VoltaColors.Amber else VoltaColors.Dim,
                modifier = Modifier.alignByBaseline().clickable(role = Role.Button, onClickLabel = "Check the rates", onClick = onSettings),
            )
        }
        Row {
            Text(rs(r.billWithEv), style = billStyle(), modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(10.dp))
            Text(
                "the car adds ${rs(r.billWithEv - r.billWithoutEv)}",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp).tabular(),
                color = VoltaColors.AmberText,
                modifier = Modifier.alignByBaseline(),
            )
        }
        SlabGauge(used, projected, limit, state.period, Modifier.padding(top = 6.dp))
        Row {
            Text(
                used?.let { "${it.roundToInt()} used · ${projected.roundToInt()} projected" } ?: "${projected.roundToInt()} projected for the cycle",
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = VoltaColors.Muted,
                modifier = Modifier.weight(1f),
            )
            Text(if (over) "past the ${limit.roundToInt()} limit" else "${limit.roundToInt()} before the slab jumps", style = MaterialTheme.typography.labelSmall.tabular(), color = VoltaColors.AmberText)
        }
        // Rate once the whole cycle is re-billed at the flat (non-telescopic) band just above the limit, duty included.
        val flatRate = t.nonTelescopic.first { t.telescopicLimit < it.first }.second * (1 + t.dutyPercent / 100) + t.fuelSurchargePerUnit
        val kmPerKwh = r.km?.takeIf { it > 1 && r.totalKwh > 0.5 }?.let { it / r.totalKwh } ?: state.fallbackKmPerKwh
        val headroomKm = r.headroomPerMonth * t.billingMonths * state.energy.homeEfficiency * kmPerKwh
        when {
            r.crossesLimit -> Text(
                "The car pushes this cycle past ${limit.roundToInt()} units, so every unit is billed at the higher flat rate, about ${rs(flatRate)} each.",
                style = MaterialTheme.typography.bodyMedium,
                color = VoltaColors.AmberText,
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(VoltaColors.tint(VoltaColors.Amber, 0.08f)).padding(10.dp),
            )

            over -> Text(
                "This cycle is past ${limit.roundToInt()} units even without the car, so every unit is billed at the flat rate, about ${rs(flatRate)} each.",
                style = MaterialTheme.typography.bodyMedium,
                color = VoltaColors.AmberText,
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(VoltaColors.tint(VoltaColors.Amber, 0.08f)).padding(10.dp),
            )

            headroomKm >= 1 -> Text(
                buildAnnotatedString {
                    append("Room for about ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = VoltaColors.Text)) { append("${(headroomKm / 10).roundToInt() * 10} km") }
                    append(" more of home charging this cycle. Past the limit, every unit is re-rated at about ${rs(flatRate)}.")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = VoltaColors.TextSoft,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Solid fill for units used so far, hatched up to the projected total, a gold tick at the slab limit. Scale runs to 1.25 × the limit. */
@Composable
private fun SlabGauge(used: Double?, projected: Double, limit: Double, key: Any, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    val grow = remember(key) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(key) { if (!reduced) grow.animateTo(1f, tween(900, easing = VoltaMotion.EaseOut)) }
    val scale = limit * 1.25
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val h = size.height
        val w = size.width
        fun px(v: Double) = (w * (v / scale).coerceIn(0.0, 1.0)).toFloat() * grow.value
        drawRoundRect(VoltaColors.Border, Offset.Zero, Size(w, h), CornerRadius(h / 2))
        val fill = if (projected > limit) VoltaColors.Amber else VoltaColors.Green
        val solidEnd = px(used ?: projected)
        val projEnd = px(projected)
        if (solidEnd > 1f) drawRoundRect(fill, Offset.Zero, Size(solidEnd, h), CornerRadius(h / 2))
        if (projEnd > solidEnd + 1f) {
            val stripe = 3.dp.toPx()
            val step = 6.dp.toPx()
            clipRect(solidEnd, 0f, projEnd, h) {
                var x = solidEnd
                while (x < projEnd) {
                    drawRect(fill.copy(alpha = 0.45f), Offset(x, 0f), Size(stripe, h))
                    x += step
                }
            }
        }
        val tick = (w * (limit / scale)).toFloat()
        drawLine(VoltaColors.Gold, Offset(tick, -5.dp.toPx()), Offset(tick, h + 5.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
    }
}

/** ₹ per month from the first logged month; bars scale by ₹, kWh sits under the month name. This month is green. */
@Composable
private fun MonthChart(months: List<MonthBar>, modifier: Modifier) {
    if (months.none { it.totalKwh >= 0.1 }) return
    val max = months.maxOf { it.cost }.coerceAtLeast(1.0)
    VoltaCard(modifier.fillMaxWidth(), radius = 22.dp, padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Row {
            Text("By month", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).alignByBaseline())
            Text("₹ · kWh", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted, modifier = Modifier.alignByBaseline())
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            months.forEach { m ->
                val on = m.current
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (m.totalKwh >= 0.1) Text(rs(m.cost), style = MaterialTheme.typography.labelMedium.tabular(), color = if (on) VoltaColors.Text else VoltaColors.Muted)
                    Box(
                        Modifier.fillMaxWidth().padding(top = 6.dp).height((84 * (m.cost / max)).dp.coerceAtLeast(3.dp))
                            .clip(RoundedCornerShape(8.dp)).background(if (on) VoltaColors.Green else VoltaColors.Border),
                    )
                    Text(m.label, style = MaterialTheme.typography.labelMedium, color = if (on) VoltaColors.Text else VoltaColors.Muted, modifier = Modifier.padding(top = 8.dp))
                    Text("${m.totalKwh.roundToInt()} kWh", style = MaterialTheme.typography.labelSmall.tabular(), color = VoltaColors.Dim)
                }
            }
            // Bars stay narrow while the log is young instead of stretching two months across the card.
            repeat((5 - months.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
        }
        Text("Starts from your first logged month.", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Dim)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(s: ChargeSessionEntity, r: EnergyReport, state: EnergyUiState, onEdit: () -> Unit) {
    val cat = ChargeCategory.valueOf(s.category)
    val kwh = kwhOf(s, state.capacityKwh)
    val cost = costOf(s, kwh, r, state.energy)
    val end = s.endedAt
    val mins = end?.let { (it - s.startedAt) / 60_000 }
    val overnight = end != null && mins != null && mins > 300 && dayKey(s.startedAt) != dayKey(end)
    val detail = listOfNotNull(
        "${s.startSoc?.roundToInt() ?: "?"} → ${s.endSoc?.roundToInt() ?: "?"} %",
        mins?.takeIf { it > 0 }?.let { duration(it) + if (overnight) " overnight" else "" },
        if (s.inferred) "inferred" else null,
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(role = Role.Button, onClickLabel = "Change type", onLongClickLabel = "Change type", onLongClick = onEdit, onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color(cat)))
        Column(Modifier.weight(1f)) {
            Text(
                "${s.placeName ?: cat.label} · ${dayFmt.format(Date(s.startedAt))}" + if (end == null) " · charging now" else "",
                style = rowTitle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(detail, style = rowSub().tabular(), color = VoltaColors.Muted, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(rs(cost), style = rowTitle().tabular())
            Text("%.1f kWh".format(kwh), style = rowSub().tabular(), color = VoltaColors.Muted)
        }
    }
}

/** Several sub-0.5 kWh sessions on one day: the car was plugged in but hardly charged. */
@Composable
private fun TinyRow(item: SessionItem.Tiny, r: EnergyReport, state: EnergyUiState) {
    val kwh = item.sessions.sumOf { kwhOf(it, state.capacityKwh) }
    val cost = item.sessions.sumOf { costOf(it, kwhOf(it, state.capacityKwh), r, state.energy) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(VoltaColors.BorderStrong))
        Column(Modifier.weight(1f)) {
            Text("${item.sessions.size} tiny top-ups · ${dayFmt.format(Date(item.day))}", style = rowTitle(), color = VoltaColors.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("%.1f kWh together · plugged in, hardly charged".format(kwh), style = rowSub().tabular(), color = VoltaColors.Muted, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
        Text(rs(cost), style = rowTitle().tabular(), color = VoltaColors.Muted)
    }
}

@Composable
private fun CategoryDialog(s: ChargeSessionEntity, onDismiss: () -> Unit, onPick: (ChargeCategory) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VoltaColors.Surface,
        title = { Text("Where was this charge?", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChargeCategory.entries.forEach { c ->
                    val on = s.category == c.name
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) VoltaColors.tint(color(c), 0.14f) else VoltaColors.SurfaceHigh)
                            .border(1.dp, if (on) VoltaColors.tint(color(c), 0.5f) else VoltaColors.Border, RoundedCornerShape(12.dp))
                            .clickable { onPick(c) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon(c), null, tint = color(c), modifier = Modifier.size(18.dp))
                        Text("  ${c.label}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        if (on) Icon(VoltaIcons.Check, null, tint = color(c), modifier = Modifier.size(16.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close", color = VoltaColors.Muted) } },
    )
}

@Composable
private fun SplitBar(kwh: Map<ChargeCategory, Double>, key: Any, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    val grow = remember(key) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(key) { if (!reduced) grow.animateTo(1f, tween(900, easing = VoltaMotion.EaseOut)) }
    val total = kwh.values.sum().takeIf { it > 0 } ?: 1.0
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        drawRoundRect(VoltaColors.Border, cornerRadius = CornerRadius(5.dp.toPx()))
        var x = 0f
        val gap = 2.dp.toPx()
        ChargeCategory.entries.forEach { c ->
            val w = (size.width * ((kwh[c] ?: 0.0) / total).toFloat() * grow.value)
            if (w > 1f) {
                drawRoundRect(color(c), Offset(x, 0f), Size((w - gap).coerceAtLeast(2f), size.height), CornerRadius(5.dp.toPx()))
                x += w
            }
        }
    }
}
