package cz.shiftmanager.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

private val Czech = Locale.forLanguageTag("cs-CZ")
private val MonthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", Czech)
private val DateFormat = DateTimeFormatter.ofPattern("d. M. yyyy", Czech)

private fun money(value: Double): String =
    String.format(Czech, "%,.0f Kč", value)

private fun number(value: Double): String =
    if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        value.toString().replace('.', ',')
    }

private fun parseNumber(text: String): Double? =
    text.trim().replace(" ", "").replace(',', '.').toDoubleOrNull()

private fun shiftLetter(type: ShiftType): Char = when (type) {
    ShiftType.MORNING -> 'R'
    ShiftType.NIGHT -> 'N'
    ShiftType.OFF -> 'V'
}

private fun shiftColor(letter: Char): Color = when (letter) {
    'R' -> Color(0xFFE69A13)
    'N' -> Color(0xFF5667D8)
    else -> Color(0xFF28946D)
}

@Composable
fun ShiftApp() {
    val context = LocalContext.current
    val storage = remember(context) { Storage(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Wait for stored settings instead of rendering editable defaults.
    val settings by storage.settings.collectAsState<PaySettings, PaySettings?>(
    initial = null
)
    val entries by storage.entries.collectAsState(
        initial = emptyList()
    )

    var tab by remember { mutableIntStateOf(0) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var visibleMonth by remember {
        mutableStateOf(YearMonth.now())
    }
    var editDate by remember { mutableStateOf<LocalDate?>(null) }
    var busy by remember { mutableStateOf(false) }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val currentSettings = settings

        if (uri != null && currentSettings != null) {
            val monthToExport = visibleMonth
            val entriesToExport = entries.toList()

            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) {
                        val generated = PdfExporter.exportMonth(
                            context,
                            monthToExport.monthValue,
                            monthToExport.year,
                            currentSettings,
                            entriesToExport
                        )

                        val output = context.contentResolver
                            .openOutputStream(uri)
                            ?: error("Soubor PDF nelze otevřít pro zápis.")

                        output.use { destination ->
                            generated.inputStream().use { source ->
                                source.copyTo(destination)
                            }
                        }
                    }
                    snackbar.showSnackbar("PDF bylo uloženo.")
                } catch (e: Exception) {
                    snackbar.showSnackbar(
                        "PDF se nepodařilo uložit: ${e.message}"
                    )
                } finally {
                    busy = false
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        val currentSettings = settings

        if (currentSettings == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                ScrollableTabRow(
                    selectedTabIndex = tab,
                    edgePadding = 8.dp
                ) {
                    listOf(
                        "Dnes",
                        "Kalendář",
                        "Měsíc",
                        "Rok",
                        "Cyklus",
                        "Nastavení"
                    ).forEachIndexed { index, title ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(title) }
                        )
                    }
                }

                if (busy) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (tab) {
                        0, 1 -> {
                            if (tab == 0) {
                                TodayCard(currentSettings)
                            }

                            MonthSelector(
                                month = visibleMonth,
                                onChange = { visibleMonth = it }
                            )

                            ShiftCalendar(
                                month = visibleMonth,
                                selected = selectedDate,
                                letterFor = {
                                    shiftLetter(
                                        ShiftCycle.typeFor(
                                            it,
                                            currentSettings
                                        )
                                    )
                                },
                                markerFor = { date ->
                                    val entry = entries.firstOrNull {
                                        it.date == date.toString()
                                    }

                                    when {
                                        entry == null -> ""
                                        entry.sickDays > 0 -> "Nemoc"
                                        entry.vacationDays > 0 -> "Dovol."
                                        entry.overtimeHours > 0 ->
                                            "+${number(entry.overtimeHours)} h"
                                        else -> ""
                                    }
                                },
                                onDayClick = {
                                    selectedDate = it
                                    editDate = it
                                }
                            )

                            Text(
                                "R = ranní 6–18 • N = noční 18–6 • V = volno",
                                style = MaterialTheme.typography.bodySmall
                            )

                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    pdfLauncher.launch(
                                        "smeny-$visibleMonth.pdf"
                                    )
                                }
                            ) {
                                Text("Uložit měsíční PDF")
                            }
                        }

                        2 -> {
                            MonthlyOverview(
                                settings = currentSettings,
                                entries = entries,
                                month = visibleMonth,
                                onMonthChange = { visibleMonth = it }
                            )

                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    pdfLauncher.launch(
                                        "smeny-$visibleMonth.pdf"
                                    )
                                }
                            ) {
                                Text("Uložit PDF")
                            }
                        }

                        3 -> YearOverview(
                            settings = currentSettings,
                            entries = entries,
                            initialYear = visibleMonth.year
                        )

                        4 -> CycleEditor(
                            settings = currentSettings,
                            initialMonth = visibleMonth,
                            onSave = { updated ->
                                scope.launch {
                                    try {
                                        storage.saveSettings(updated)
                                        snackbar.showSnackbar(
                                            "28denní cyklus byl uložen."
                                        )
                                    } catch (e: Exception) {
                                        snackbar.showSnackbar(
                                            "Uložení selhalo: ${e.message}"
                                        )
                                    }
                                }
                            }
                        )

                        5 -> SettingsEditor(
                            settings = currentSettings,
                            onSave = { updated ->
                                scope.launch {
                                    try {
                                        storage.saveSettings(updated)
                                        snackbar.showSnackbar(
                                            "Nastavení bylo uloženo."
                                        )
                                    } catch (e: Exception) {
                                        snackbar.showSnackbar(
                                            "Uložení selhalo: ${e.message}"
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    editDate?.let { date ->
        EntryDialog(
            date = date,
            existing = entries.firstOrNull {
                it.date == date.toString()
            } ?: WorkEntry(date),
            onDismiss = { editDate = null },
            onSave = { entry ->
                scope.launch {
                    try {
                        storage.saveEntries(
                            storage.upsertEntry(entries, entry)
                        )
                        editDate = null
                        snackbar.showSnackbar("Záznam byl uložen.")
                    } catch (e: Exception) {
                        snackbar.showSnackbar(
                            "Uložení selhalo: ${e.message}"
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun TodayCard(settings: PaySettings) {
    val today = LocalDate.now()
    val type = ShiftCycle.typeFor(today, settings)
    val color = shiftColor(shiftLetter(type))

    val title = when (type) {
        ShiftType.MORNING -> "Ranní směna"
        ShiftType.NIGHT -> "Noční směna"
        ShiftType.OFF -> "Volno"
    }

    val time = when (type) {
        ShiftType.MORNING -> "6:00–18:00"
        ShiftType.NIGHT -> "18:00–6:00 následujícího dne"
        ShiftType.OFF -> "Dnes nemáš plánovanou směnu"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(color, color.copy(alpha = 0.75f))
                ),
                RoundedCornerShape(24.dp)
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = when (type) {
                ShiftType.MORNING -> "☀️"
                ShiftType.NIGHT -> "🌙"
                ShiftType.OFF -> "🌴"
            },
            fontSize = 60.sp
        )

        Text(
            title,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Text(time, color = Color.White)

        Text(
            today.format(
                DateTimeFormatter.ofPattern(
                    "EEEE d. MMMM yyyy",
                    Czech
                )
            ),
            color = Color.White
        )
    }
}

@Composable
private fun MonthSelector(
    month: YearMonth,
    onChange: (YearMonth) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { onChange(month.minusMonths(1)) }) {
            Text("◀")
        }

        Text(
            month.format(MonthFormat),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        TextButton(onClick = { onChange(month.plusMonths(1)) }) {
            Text("▶")
        }
    }
}

@Composable
private fun ShiftCalendar(
    month: YearMonth,
    selected: LocalDate?,
    letterFor: (LocalDate) -> Char,
    markerFor: (LocalDate) -> String = { "" },
    onDayClick: (LocalDate) -> Unit
) {
    val offset = month.atDay(1).dayOfWeek.value - 1
    val rowCount = (offset + month.lengthOfMonth() + 6) / 7

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")
                .forEach { label ->
                    Box(
                        Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
        }

        repeat(rowCount) { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(7) { column ->
                    val day = row * 7 + column - offset + 1

                    if (day !in 1..month.lengthOfMonth()) {
                        Spacer(
                            Modifier
                                .weight(1f)
                                .height(68.dp)
                        )
                    } else {
                        val date = month.atDay(day)
                        val letter = letterFor(date)
                        val color = shiftColor(letter)
                        val marker = markerFor(date)

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .height(68.dp)
                                .background(
                                    color.copy(alpha = 0.15f),
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = if (date == selected) {
                                        2.dp
                                    } else {
                                        0.dp
                                    },
                                    color = if (date == selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.Transparent
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { onDayClick(date) }
                                .padding(3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(day.toString(), fontSize = 13.sp)
                            Text(
                                letter.toString(),
                                color = color,
                                fontWeight = FontWeight.Bold
                            )
                            if (marker.isNotEmpty()) {
                                Text(
                                    marker,
                                    fontSize = 9.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CycleEditor(
    settings: PaySettings,
    initialMonth: YearMonth,
    onSave: (PaySettings) -> Unit
) {
    var month by remember { mutableStateOf(initialMonth) }
    var brush by remember { mutableStateOf('R') }
    var error by remember { mutableStateOf<String?>(null) }

    // Exactly 28 editable cycle days, starting on the first
    // day of the chosen month.
    var draft by remember(
        month,
        settings.cycleStart,
        settings.cyclePattern
    ) {
        mutableStateOf(
            List(28) { index ->
                shiftLetter(
                    ShiftCycle.typeFor(
                        month.atDay(1).plusDays(index.toLong()),
                        settings
                    )
                )
            }
        )
    }

    Text(
        "Grafické nastavení 28denního cyklu",
        style = MaterialTheme.typography.titleLarge
    )

    MonthSelector(month, onChange = {
        month = it
        error = null
    })

    Text(
        "Vyber typ směny a potom klepni na příslušné dny. " +
            "Cyklus začne prvního dne vybraného měsíce a " +
            "po 28 dnech se bude opakovat."
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf('R', 'N', 'V').forEach { letter ->
            FilterChip(
                selected = brush == letter,
                onClick = { brush = letter },
                label = {
                    Text(
                        when (letter) {
                            'R' -> "Ranní"
                            'N' -> "Noční"
                            else -> "Volno"
                        }
                    )
                }
            )
        }
    }

    ShiftCalendar(
        month = month,
        selected = null,
        letterFor = { date ->
            // Days 29–31 are a preview of the next repetition.
            draft[(date.dayOfMonth - 1) % 28]
        },
        markerFor = { date ->
            if (date.dayOfMonth > 28) "Náhled" else ""
        },
        onDayClick = { date ->
            if (date.dayOfMonth <= 28) {
                draft = draft.toMutableList().also {
                    it[date.dayOfMonth - 1] = brush
                }
            } else {
                error = "Dny 29–31 jsou pouze náhled opakování. " +
                    "Uprav odpovídající den 1–3."
            }
        }
    )

    Text(
        "Dny 1–28 určují cyklus. Dny 29–31 pouze ukazují " +
            "jeho pokračování.",
        style = MaterialTheme.typography.bodySmall
    )

    Text(
        "Pozor: současná verze datového modelu uchovává jen " +
            "jeden cyklus. Uložení přepočítá také starší rozvrhy. " +
            "Historie změn cyklu zatím není implementovaná.",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall
    )

    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
    }

    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            onSave(
                settings.copy(
                    cycleStart = month.atDay(1).toString(),
                    cyclePattern = draft.joinToString("")
                )
            )
        }
    ) {
        Text(
            "Uložit cyklus od ${month.atDay(1).format(DateFormat)}"
        )
    }
}

@Composable
private fun MonthlyOverview(
    settings: PaySettings,
    entries: List<WorkEntry>,
    month: YearMonth,
    onMonthChange: (YearMonth) -> Unit
) {
    val r = PayCalculator.calculate(
        month.monthValue,
        month.year,
        settings,
        entries
    )

    MonthSelector(month, onMonthChange)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryCard(
            "Naplánováno",
            "${number(r.plannedShifts)} směn",
            Modifier.weight(1f)
        )
        SummaryCard(
            "Odpracováno podle evidence",
            "${number(r.workedShifts)} směn",
            Modifier.weight(1f)
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryCard(
            "Přesčas",
            "${number(r.overtimeHours)} h",
            Modifier.weight(1f)
        )
        SummaryCard(
            "K výplatě – odhad",
            money(r.net),
            Modifier.weight(1f)
        )
    }

    Text(
        "Mzdový výpočet je zatím orientační. " +
            "Nejde o ověřenou mzdovou kalkulačku.",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall
    )

    Text(
        "Mzda a příplatky",
        style = MaterialTheme.typography.titleMedium
    )

    PayRow("Základní mzda", r.baseWage)
    PayRow("Pohyblivá složka", r.variablePay)
    PayRow("Noční příplatek", r.nightPremium)
    PayRow("Víkendový příplatek", r.weekendPremium)
    PayRow("Přesčasový příplatek", r.overtimePremium)
    PayRow("Sváteční příplatek", r.holidayPremium)
    PayRow("Náhrada při nemoci – odhad", r.sickPay)
    PayRow("Náhrada za dovolenou – odhad", r.vacationPay)

    HorizontalDivider()
    PayRow("Hrubá mzda – odhad", r.gross, true)

    Text(
        "Odvody podle aktuálního výpočetního modulu",
        style = MaterialTheme.typography.titleMedium
    )

    PayRow("Sociální pojištění", -r.socialInsurance)
    PayRow("Zdravotní pojištění", -r.healthInsurance)
    PayRow("Záloha na daň", -r.incomeTax)

    HorizontalDivider()

    Text(
        "Příspěvky podle zadaných firemních pravidel",
        style = MaterialTheme.typography.titleMedium
    )

    PayRow("Dojíždění", r.commuteAllowance)
    PayRow("Stravenkový paušál", r.mealAllowance)
    PayRow("Prací prostředky", r.clothesAllowance)

    HorizontalDivider()
    PayRow("Celkem k výplatě – odhad", r.net, true)
}

@Composable
private fun SummaryCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun YearOverview(
    settings: PaySettings,
    entries: List<WorkEntry>,
    initialYear: Int
) {
    var year by remember { mutableIntStateOf(initialYear) }

    val results = (1..12).map { month ->
        PayCalculator.calculate(month, year, settings, entries)
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { year-- }) { Text("◀") }
        Text(
            "Rok $year",
            style = MaterialTheme.typography.titleLarge
        )
        TextButton(onClick = { year++ }) { Text("▶") }
    }

    Text(
        "Celoroční plán / odhad",
        style = MaterialTheme.typography.titleMedium
    )

    Text(
        "Přehled zahrnuje všech 12 měsíců včetně budoucích " +
            "směn a používá aktuální nastavení mzdy. " +
            "Nejde o součet skutečně vyplacených výplat.",
        style = MaterialTheme.typography.bodySmall
    )

    SummaryCard(
        "Hrubá mzda za celý rok – odhad",
        money(results.sumOf { it.gross }),
        Modifier.fillMaxWidth()
    )

    SummaryCard(
        "K výplatě za celý rok – odhad",
        money(results.sumOf { it.net }),
        Modifier.fillMaxWidth()
    )

    SummaryCard(
        "Zapsané přesčasy",
        "${number(results.sumOf { it.overtimeHours })} h",
        Modifier.fillMaxWidth()
    )

    Row(Modifier.fillMaxWidth()) {
        Text(
            "Měsíc",
            Modifier.weight(1f),
            fontWeight = FontWeight.Bold
        )
        Text(
            "Hrubá",
            Modifier.weight(1f),
            fontWeight = FontWeight.Bold
        )
        Text(
            "K výplatě",
            Modifier.weight(1f),
            fontWeight = FontWeight.Bold
        )
    }

    results.forEach { r ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            Text(
                YearMonth.of(year, r.month).format(
                    DateTimeFormatter.ofPattern("LLL", Czech)
                ),
                Modifier.weight(1f)
            )
            Text(money(r.gross), Modifier.weight(1f))
            Text(money(r.net), Modifier.weight(1f))
        }
        HorizontalDivider()
    }
}

@Composable
private fun EntryDialog(
    date: LocalDate,
    existing: WorkEntry,
    onSave: (WorkEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var overtime by remember(date) {
        mutableStateOf(number(existing.overtimeHours))
    }
    var sick by remember(date) {
        mutableStateOf(number(existing.sickDays))
    }
    var vacation by remember(date) {
        mutableStateOf(number(existing.vacationDays))
    }
    var error by remember(date) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(date.format(DateFormat)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Nemoc a dovolenou zadávej jako část " +
                        "neodpracované směny: 1 = celá směna."
                )

                OutlinedTextField(
                    value = overtime,
                    onValueChange = { overtime = it },
                    label = { Text("Přesčas – hodiny") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = sick,
                    onValueChange = { sick = it },
                    label = { Text("Nemoc – část směny 0 až 1") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = vacation,
                    onValueChange = { vacation = it },
                    label = { Text("Dovolená – část směny 0 až 1") },
                    singleLine = true
                )

                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val ot = parseNumber(overtime)
                    val sk = parseNumber(sick)
                    val vc = parseNumber(vacation)

                    when {
                        ot == null || sk == null || vc == null ->
                            error = "Vyplň platná čísla."

                        !ot.isFinite() ||
                            !sk.isFinite() ||
                            !vc.isFinite() ->
                            error = "Čísla musí být konečná."

                        ot !in 0.0..24.0 ->
                            error = "Přesčas musí být 0 až 24 hodin."

                        sk !in 0.0..1.0 || vc !in 0.0..1.0 ->
                            error = "Nemoc a dovolená musí být 0 až 1."

                        sk + vc > 1.0 ->
                            error = "Součet absence nesmí překročit 1 směnu."

                        else -> onSave(
                            existing.copy(
                                overtimeHours = ot,
                                sickDays = sk,
                                vacationDays = vc
                            )
                        )
                    }
                }
            ) {
                Text("Uložit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Zrušit")
            }
        }
    )
}

@Composable
private fun SettingsEditor(
    settings: PaySettings,
    onSave: (PaySettings) -> Unit
) {
    val labels = listOf(
        "Smluvní měsíční mzda – Kč",
        "Pohyblivá složka měsíčně – Kč",
        "Dojíždění při docházce alespoň 85 % – Kč/měsíc",
        "Stravenkový paušál – Kč za odpracovanou směnu",
        "Prací prostředky – Kč měsíčně",
        "Noční příplatek – %",
        "Víkendový příplatek – %",
        "Přesčasový příplatek – %",
        "Sváteční příplatek – %"
    )

    var values by remember(settings) {
        mutableStateOf(
            listOf(
                settings.monthlyWage,
                settings.variablePay,
                settings.commuteAllowance,
                settings.mealAllowance,
                settings.workClothesAllowance,
                settings.nightPremium,
                settings.weekendPremium,
                settings.overtimePremium,
                settings.holidayPremium
            ).map(::number)
        )
    }

    var taxDiscount by remember(settings) {
        mutableStateOf(settings.applyTaxDiscount)
    }
    var error by remember { mutableStateOf<String?>(null) }

    Text(
        "Mzda a příplatky",
        style = MaterialTheme.typography.titleLarge
    )

    labels.forEachIndexed { index, label ->
        OutlinedTextField(
            value = values[index],
            onValueChange = { text ->
                values = values.toMutableList().also {
                    it[index] = text
                }
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = taxDiscount,
            onCheckedChange = { taxDiscount = it }
        )
        Text("Uplatnit základní slevu na poplatníka")
    }

    Text(
        "Dojíždění se přizná při alespoň 85 % směn. " +
            "Prací prostředky jsou měsíční částka. " +
            "Stravenkový paušál se násobí odpracovanými směnami.",
        style = MaterialTheme.typography.bodySmall
    )

    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
    }

    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            val parsed = values.map(::parseNumber)

            if (parsed.any {
                    it == null || !it.isFinite() || it < 0
                }
            ) {
                error = "Vyplň všechna pole nezápornými čísly."
            } else {
                val v = parsed.map { requireNotNull(it) }
                error = null

                onSave(
                    settings.copy(
                        monthlyWage = v[0],
                        variablePay = v[1],
                        commuteAllowance = v[2],
                        mealAllowance = v[3],
                        workClothesAllowance = v[4],
                        nightPremium = v[5],
                        weekendPremium = v[6],
                        overtimePremium = v[7],
                        holidayPremium = v[8],
                        applyTaxDiscount = taxDiscount
                    )
                )
            }
        }
    ) {
        Text("Uložit nastavení")
    }
}

@Composable
private fun PayRow(
    label: String,
    value: Double,
    emphasized: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontWeight = if (emphasized) {
                FontWeight.Bold
            } else {
                FontWeight.Normal
            }
        )

        Text(
            text = money(value),
            fontWeight = if (emphasized) {
                FontWeight.Bold
            } else {
                FontWeight.Normal
            }
        )
    }
}
