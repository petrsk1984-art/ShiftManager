package cz.shiftmanager.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

enum class ShiftType { MORNING, NIGHT, OFF }

@Serializable
data class WorkEntry(
    val date: String,
    val overtimeHours: Double = 0.0,
    val sickDays: Double = 0.0,
    val vacationDays: Double = 0.0
) {
    constructor(date: LocalDate, overtimeHours: Double = 0.0, sickDays: Double = 0.0, vacationDays: Double = 0.0) :
        this(date.toString(), overtimeHours, sickDays, vacationDays)
    val localDate: LocalDate get() = LocalDate.parse(date)
}

@Serializable
data class PaySettings(
    val monthlyWage: Double = 35000.0,
    val variablePay: Double = 0.0,
    val commuteAllowance: Double = 1500.0,
    val mealAllowance: Double = 0.0,
    val workClothesAllowance: Double = 100.0,
    val nightPremium: Double = 10.0,
    val weekendPremium: Double = 10.0,
    val overtimePremium: Double = 25.0,
    val holidayPremium: Double = 100.0,
    val applyTaxDiscount: Boolean = true,
    val cycleStart: String = "2026-10-01",
    val cyclePattern: String = "RRRVVNNVVVRRVVNNNVVRRVVVNNVRRR"
)

object ShiftCycle {
    fun typeFor(date: LocalDate, s: PaySettings): ShiftType {
        val start = LocalDate.parse(s.cycleStart)
        val p = s.cyclePattern.uppercase().filter { it in "RVN" }
        if (p.isEmpty()) return ShiftType.OFF
        val days = java.time.temporal.ChronoUnit.DAYS.between(start, date).toInt()
        val i = ((days % p.length) + p.length) % p.length
        return when (p[i]) { 'R' -> ShiftType.MORNING; 'N' -> ShiftType.NIGHT; else -> ShiftType.OFF }
    }
    fun hoursFor(t: ShiftType) = if (t == ShiftType.OFF) 0.0 else 12.0
}
