package cz.shiftmanager.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.shiftmanager.data.Storage
import cz.shiftmanager.logic.PayCalculator
import cz.shiftmanager.logic.PdfExporter
import cz.shiftmanager.model.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun ShiftApp() {
    val context = LocalContext.current
    val storage = remember { Storage(context) }
    val scope = rememberCoroutineScope()
    val settings by storage.settings.collectAsState(initial = PaySettings())
    val entries by storage.entries.collectAsState(initial = emptyList())
    var tab by remember { mutableIntStateOf(0) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var dialog by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val todayType = ShiftCycle.typeFor(today, settings)

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        ScrollableTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Dnes") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Kalendář") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Měsíc") })
            Tab(tab == 3, { tab = 3 }, text = { Text("Rok") })
            Tab(tab == 4, { tab = 4 }, text = { Text("Cyklus") })
            Tab(tab == 5, { tab = 5 }, text = { Text("Nastavení") })
        }
        when (tab) {
            0 -> HomeScreen(settings, entries, today, todayType,
                onSelect = { selectedDate = it; dialog = true },
                onExport = {
                    PdfExporter.exportMonth(context, today.monthValue, today.year, settings, entries)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"; putExtra(Intent.EXTRA_SUBJECT, "Přehled směn ${today.monthValue}/${today.year}")
                    }, "Sdílet PDF"))
                })
            1 -> InfiniteCalendarScreen(selectedDate, settings, entries) { selectedDate = it; dialog = true }
            2 -> MonthlyOverviewScreen(settings, entries, selectedDate)
            3 -> YearOverviewScreen(settings, entries, today.year)
            4 -> CycleEditorScreen(settings) { scope.launch { storage.saveSettings(it) } }
            5 -> SettingsScreen(settings) { scope.launch { storage.saveSettings(it) } }
        }
    }
    if (dialog) EntryDialog(selectedDate,
        entries.firstOrNull { it.date == selectedDate.toString() } ?: WorkEntry(selectedDate),
        { scope.launch { storage.saveEntries(storage.upsertEntry(entries, it)) }; dialog = false },
        { dialog = false })
}

@Composable
fun CycleEditorScreen(s: PaySettings, onSave: (PaySettings) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(LocalDate.now())) }
    var pattern by remember(month) {
        mutableStateOf((1..month.lengthOfMonth()).map {
            when (ShiftCycle.typeFor(month.atDay(it), s)) {
                ShiftType.MORNING -> 'R'; ShiftType.NIGHT -> 'N'; else -> 'V'
            }
        }.joinToString(""))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton({ month = month.minusMonths(1) }) { Text("◀") }
            Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy")), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            TextButton({ month = month.plusMonths(1) }) { Text("▶") }
        }
        Text("Klepnutím na den změň směnu: R → N → V → R", fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))

        val first = month.atDay(1)
        val offset = (first.dayOfWeek.value + 6) % 7
        val cells = List(offset) { null as Int? } + (1..month.lengthOfMonth()).map { it }
        LazyVerticalGrid(columns = GridCells.Fixed(7), userScrollEnabled = false,
            modifier = Modifier.height((((month.lengthOfMonth() + offset + 6) / 7) * 62).dp)) {
            items(cells) { day ->
                if (day == null) Box(Modifier.aspectRatio(1f)) else {
                    val ch = pattern[day - 1]
                    val c = when (ch) { 'R' -> Color(0xFFFF8F00); 'N' -> Color(0xFF1A237E); else -> Color(0xFF2E7D32) }
                    Box(Modifier.padding(2.dp).aspectRatio(1f).background(c.copy(alpha = .25f), CircleShape)
                        .clickable {
                            val sb = StringBuilder(pattern)
                            sb[day - 1] = when (ch) { 'R' -> 'N'; 'N' -> 'V'; else -> 'R' }
                            pattern = sb.toString()
                        }, contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(day.toString(), fontSize = 13.sp)
                            Text(ch.toString(), fontSize = 12.sp, color = c, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            onSave(s.copy(cycleStart = month.atDay(1).toString(), cyclePattern = pattern))
        }, modifier = Modifier.fillMaxWidth()) { Text("Uložit cyklus od ${month.atDay(1).format(DateTimeFormatter.ofPattern("d. M. yyyy"))}") }
        Text("Od tohoto měsíce bude aplikace pokračovat podle uloženého rozvrhu.", fontSize = 12.sp)
    }
}

@Composable
fun HomeScreen(s: PaySettings, entries: List<WorkEntry>, today: LocalDate, type: ShiftType, onSelect: (LocalDate) -> Unit, onExport: () -> Unit) {
    val gradient = when (type) {
        ShiftType.MORNING -> Brush.linearGradient(listOf(Color(0xFFFFB300), Color(0xFFFF6F00)))
        ShiftType.NIGHT -> Brush.linearGradient(listOf(Color(0xFF1A237E), Color(0xFF0D47A1)))
        ShiftType.OFF -> Brush.linearGradient(listOf(Color(0xFF2E7D32), Color(0xFF00695C)))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(220.dp).background(gradient, MaterialTheme.shapes.extraLarge), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when (type) { ShiftType.MORNING -> "☀️"; ShiftType.NIGHT -> "🌙"; ShiftType.OFF -> "🌴" }, fontSize = 60.sp)
                Text(when (type) { ShiftType.MORNING -> "Ranní směna"; ShiftType.NIGHT -> "Noční směna"; ShiftType.OFF -> "Volno" }, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(when (type) { ShiftType.MORNING -> "6:00 – 18:00"; ShiftType.NIGHT -> "18:00 – 6:00"; ShiftType.OFF -> "Dnes nepracuješ" }, fontSize = 18.sp)
                Text(today.format(DateTimeFormatter.ofPattern("EEEE d. MMMM yyyy")), fontSize = 14.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onExport) { Text("Exportovat PDF") } }
        MonthCalendar(YearMonth.from(today), s, entries, today, onSelect)
    }
}

@Composable
fun MonthCalendar(month: YearMonth, s: PaySettings, entries: List<WorkEntry>, selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val first = month.atDay(1); val offset = (first.dayOfWeek.value + 6) % 7
    val days = List(offset) { null as LocalDate? } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    Column {
        Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy")), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("Po","Út","St","Čt","Pá","So","Ne").forEach { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
        LazyVerticalGrid(columns = GridCells.Fixed(7), userScrollEnabled = false,
            modifier = Modifier.height((((month.lengthOfMonth() + offset + 6) / 7) * 58).dp)) {
            items(days) { day ->
                if (day == null) Box(Modifier.aspectRatio(1f)) else {
                    val t = ShiftCycle.typeFor(day, s)
                    val c = when (t) { ShiftType.MORNING -> Color(0xFFFF8F00); ShiftType.NIGHT -> Color(0xFF1A237E); ShiftType.OFF -> Color(0xFF2E7D32) }
                    Box(Modifier.padding(2.dp).aspectRatio(1f).background(c.copy(alpha = .2f), CircleShape)
                        .border(if (day == selected) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { onSelect(day) }, contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(day.dayOfMonth.toString(), fontSize = 13.sp)
                            Text(when (t) { ShiftType.MORNING -> "R"; ShiftType.NIGHT -> "N"; ShiftType.OFF -> "V" }, fontSize = 10.sp, color = c, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InfiniteCalendarScreen(selected: LocalDate, s: PaySettings, entries: List<WorkEntry>, onSelect: (LocalDate) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton({ month = month.minusMonths(1) }) { Text("◀") }
            Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy")), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            TextButton({ month = month.plusMonths(1) }) { Text("▶") }
        }
        MonthCalendar(month, s, entries, selected, onSelect)
    }
}

@Composable
fun MonthlyOverviewScreen(s: PaySettings, entries: List<WorkEntry>, selected: LocalDate) {
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    val r = PayCalculator.calculate(month.monthValue, month.year, s, entries)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton({ month = month.minusMonths(1) }) { Text("◀") }
            Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy")), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            TextButton({ month = month.plusMonths(1) }) { Text("▶") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Naplánováno", "${r.plannedShifts.toInt()} směn", Modifier.weight(1f))
            StatCard("Odpracováno", "${r.workedShifts.toInt()} směn", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Přesčas", "${r.overtimeHours} h", Modifier.weight(1f))
            StatCard("Čistá mzda", String.format("%,.0f Kč", r.net), Modifier.weight(1f))
        }
        HorizontalDivider()
        PayRow("HRUBÁ MZDA", r.gross, true)
        PayRow("Noční příplatky", r.nightPremium); PayRow("Víkendové příplatky", r.weekendPremium)
        PayRow("Přesčas", r.overtimePremium); PayRow("Svátky", r.holidayPremium)
        PayRow("Nemocenská", r.sickPay); PayRow("Dovolená", r.vacationPay)
        PayRow("Dojíždění", r.commuteAllowance); PayRow("Stravenky", r.mealAllowance); PayRow("Prací prostředky", r.clothesAllowance)
        HorizontalDivider(); PayRow("ČISTÁ MZDA", r.net, true)
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) { Column(Modifier.padding(12.dp)) { Text(label, fontSize = 12.sp); Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold) } }
}

@Composable
fun YearOverviewScreen(s: PaySettings, entries: List<WorkEntry>, year: Int) {
    var y by remember { mutableStateOf(year) }
    val results = PayCalculator.yearResults(y, s, entries)
    val tg = results.sumOf { it.gross }; val tn = results.sumOf { it.net }; val to = results.sumOf { it.overtimeHours }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton({ y -= 1 }) { Text("◀") }; Text("Rok $y", fontSize = 22.sp, fontWeight = FontWeight.Bold); TextButton({ y += 1 }) { Text("▶") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Hrubo ročně", String.format("%,.0f Kč", tg), Modifier.weight(1f))
            StatCard("Čisto ročně", String.format("%,.0f Kč", tn), Modifier.weight(1f))
        }
        StatCard("Přesčas ročně", "$to h", Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        results.forEach { r -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(r.month.toString(), Modifier.weight(2f))
            Text(String.format("%,.0f", r.gross), Modifier.weight(2f))
            Text(String.format("%,.0f", r.net), Modifier.weight(2f)) } }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth()) {
            Text("CELKEM", Modifier.weight(2f), fontWeight = FontWeight.Bold)
            Text(String.format("%,.0f", tg), Modifier.weight(2f), fontWeight = FontWeight.Bold)
            Text(String.format("%,.0f", tn), Modifier.weight(2f), fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun EntryDialog(date: LocalDate, existing: WorkEntry, onSave: (WorkEntry) -> Unit, onDismiss: () -> Unit) {
    var ot by remember { mutableStateOf(existing.overtimeHours.let { if (it == 0.0) "" else it.toString() }) }
    var sk by remember { mutableStateOf(existing.sickDays.let { if (it == 0.0) "" else it.toString() }) }
    var vc by remember { mutableStateOf(existing.vacationDays.let { if (it == 0.0) "" else it.toString() }) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(date.format(DateTimeFormatter.ofPattern("d. M. yyyy"))) },
        text = { Column { OutlinedTextField(ot, { ot = it }, label = { Text("Přesčas [hodiny]") }); OutlinedTextField(sk, { sk = it }, label = { Text("Nemoc [dny]") }); OutlinedTextField(vc, { vc = it }, label = { Text("Dovolená [dny]") }) } },
        confirmButton = { TextButton({ onSave(existing.copy(ot.toDoubleOrNull() ?: 0.0, sk.toDoubleOrNull() ?: 0.0, vc.toDoubleOrNull() ?: 0.0)) }) { Text("Uložit") } },
        dismissButton = { TextButton(onDismiss) { Text("Zavřít") } })
}

@Composable
fun SettingsScreen(s: PaySettings, onChange: (PaySettings) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mzda a příplatky", fontWeight = FontWeight.Bold)
        Num("Smluvní měsíční mzda", s.monthlyWage) { onChange(s.copy(monthlyWage = it)) }
        Num("Pohyblivá složka", s.variablePay) { onChange(s.copy(variablePay = it)) }
        Num("Dojíždění / měsíc při 85 %+ směn", s.commuteAllowance) { onChange(s.copy(commuteAllowance = it)) }
        Num("Stravenkový paušál / směnu", s.mealAllowance) { onChange(s.copy(mealAllowance = it)) }
        Num("Prací prostředky / měsíc", s.workClothesAllowance) { onChange(s.copy(workClothesAllowance = it)) }
        Num("Noční příplatek %", s.nightPremium) { onChange(s.copy(nightPremium = it)) }
        Num("Víkendový příplatek %", s.weekendPremium) { onChange(s.copy(weekendPremium = it)) }
        Num("Přesčas %", s.overtimePremium) { onChange(s.copy(overtimePremium = it)) }
        Num("Svátek %", s.holidayPremium) { onChange(s.copy(holidayPremium = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(s.applyTaxDiscount, { onChange(s.copy(applyTaxDiscount = it)) }); Text("Sleva na dani 2 570 Kč") }
    }
}

@Composable
fun Num(label: String, value: Double, onChange: (Double) -> Unit) {
    OutlinedTextField(if (value == 0.0) "" else value.toString(), { onChange(it.toDoubleOrNull() ?: 0.0) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}

@Composable
fun PayRow(l: String, v: Double, b: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(l, fontWeight = if (b) FontWeight.Bold else FontWeight.Normal)
        Text(String.format("%,.0f Kč", v), fontWeight = if (b) FontWeight.Bold else FontWeight.Normal)
    }
}
