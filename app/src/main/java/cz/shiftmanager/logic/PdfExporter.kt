package cz.shiftmanager.logic

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import cz.shiftmanager.model.PaySettings
import cz.shiftmanager.model.WorkEntry
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

object PdfExporter {
    fun exportMonth(context: Context, month: Int, year: Int, s: PaySettings, entries: List<WorkEntry>): File {
        val r = PayCalculator.calculate(month, year, s, entries)
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val c = page.canvas
        val title = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val text = Paint().apply { textSize = 11f }
        var y = 40f
        c.drawText("Přehled směn – $month/$year", 40f, y, title); y += 30f
        listOf("Naplánované směny: ${r.plannedShifts}", "Odpracované směny: ${r.workedShifts}",
            "Přesčas: ${r.overtimeHours} h", "HRUBÁ MZDA: ${r.gross} Kč",
            "Sociální: -${r.socialInsurance}", "Zdravotní: -${r.healthInsurance}", "Daň: -${r.incomeTax}",
            "Dojíždění: ${r.commuteAllowance}", "Stravenky: ${r.mealAllowance}",
            "Prací prostředky: ${r.clothesAllowance}", "ČISTÁ MZDA: ${r.net} Kč"
        ).forEach { c.drawText(it, 40f, y, text); y += 18f }
        y += 20f
        var dd = LocalDate.of(year, month, 1)
        while (dd.monthValue == month) {
            val t = when (cz.shiftmanager.model.ShiftCycle.typeFor(dd, s)) {
                cz.shiftmanager.model.ShiftType.MORNING -> "Ranní"
                cz.shiftmanager.model.ShiftType.NIGHT -> "Noční"
                else -> "Volno"
            }
            val e = entries.firstOrNull { it.date == dd.toString() }
            c.drawText("${dd};${t};${e?.overtimeHours ?: 0.0};${e?.sickDays ?: 0.0};${e?.vacationDays ?: 0.0}", 40f, y, text)
            y += 14f; dd = dd.plusDays(1)
        }
        pdf.finishPage(page)
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "ShiftManager").apply { mkdirs() }
        val file = File(dir, "smeny-${year}-${month}.pdf")
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close(); return file
    }
}
