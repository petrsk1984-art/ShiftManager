package cz.shiftmanager.logic

import cz.shiftmanager.model.PaySettings
import cz.shiftmanager.model.ShiftCycle
import cz.shiftmanager.model.ShiftType
import cz.shiftmanager.model.WorkEntry
import java.time.DayOfWeek
import java.time.LocalDate

data class PayResult(
    val month: Int, val year: Int, val plannedShifts: Double, val workedShifts: Double,
    val overtimeHours: Double, val baseWage: Double, val variablePay: Double,
    val nightPremium: Double, val weekendPremium: Double, val overtimePremium: Double,
    val holidayPremium: Double, val sickPay: Double, val vacationPay: Double,
    val commuteAllowance: Double, val mealAllowance: Double, val clothesAllowance: Double,
    val netAllowances: Double, val gross: Double, val socialInsurance: Double,
    val healthInsurance: Double, val incomeTax: Double, val net: Double
)

object PayCalculator {
    const val TAX_THRESHOLD = 146901.0

    fun calculate(month: Int, year: Int, s: PaySettings, entries: List<WorkEntry>): PayResult {
        val first = LocalDate.of(year, month, 1)
        val last = first.withDayOfMonth(first.lengthOfMonth())
        var scheduled = 0.0; var night = 0.0; var weekend = 0.0; var holiday = 0.0
        var d = first
        while (!d.isAfter(last)) {
            val t = ShiftCycle.typeFor(d, s); val h = ShiftCycle.hoursFor(t)
            scheduled += h
            if (t == ShiftType.NIGHT) night += 8.0
            if (t != ShiftType.OFF && (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY)) weekend += h
            if (t != ShiftType.OFF && isHoliday(d)) holiday += h
            d = d.plusDays(1)
        }
        val me = entries.filter { it.localDate.monthValue == month && it.localDate.year == year }
        val ot = me.sumOf { it.overtimeHours }; val sick = me.sumOf { it.sickDays }; val vac = me.sumOf { it.vacationDays }
        val hourly = if (scheduled > 0) s.monthlyWage / scheduled else 0.0
        val nightP = hourly * night * s.nightPremium / 100
        val weekendP = hourly * weekend * s.weekendPremium / 100
        val otP = hourly * ot * s.overtimePremium / 100
        val holP = hourly * holiday * s.holidayPremium / 100
        val daily = if (scheduled > 0) s.monthlyWage / (scheduled / 12.0) else 0.0
        val sickP = daily * sick * 0.6; val vacP = daily * vac
        val planned = scheduled / 12.0
        val worked = planned - sick - vac
        val attendance = if (planned > 0) worked / planned else 0.0
        val commute = if (attendance >= 0.85) s.commuteAllowance else 0.0
        val meals = s.mealAllowance * worked
        val clothes = s.workClothesAllowance
        val allowances = commute + meals + clothes
        val gross = s.monthlyWage + s.variablePay + nightP + weekendP + otP + holP + sickP + vacP
        val social = gross * 0.065; val health = gross * 0.045
        val discount = if (s.applyTaxDiscount) 2570.0 else 0.0
        val tax = if (gross <= TAX_THRESHOLD) maxOf(0.0, gross * 0.15 - discount)
        else maxOf(0.0, TAX_THRESHOLD * 0.15 + (gross - TAX_THRESHOLD) * 0.23 - discount)
        val net = gross - social - health - tax + allowances
        return PayResult(month, year, planned, worked, ot, s.monthlyWage, s.variablePay, nightP, weekendP,
            otP, holP, sickP, vacP, commute, meals, clothes, allowances, gross, social, health, tax, net)
    }
    fun yearResults(year: Int, s: PaySettings, entries: List<WorkEntry>) = (1..12).map { calculate(it, year, s, entries) }
    fun isHoliday(d: LocalDate): Boolean {
        val md = "%02d-%02d".format(d.monthValue, d.dayOfMonth)
        return md in setOf("01-01","05-01","05-08","07-05","07-06","09-28","10-28","11-17","12-24","12-25","12-26")
    }
}
