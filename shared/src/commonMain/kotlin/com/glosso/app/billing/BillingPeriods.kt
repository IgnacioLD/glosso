package com.glosso.app.billing

/** A parsed billing period reduced to a count and a unit, for localization. */
data class PeriodParts(val count: Int, val unit: PeriodUnit)

enum class PeriodUnit { DAY, WEEK, MONTH, YEAR }

/**
 * An ISO-8601 date-based period as `java.time.Period` reads it: years, months
 * and days, with weeks folded into days and a leading sign applied to all.
 */
data class BillingPeriod(val years: Int, val months: Int, val days: Int)

/**
 * Parses the ISO-8601 billing periods Play returns (e.g. `P1M`, `P7D`, `P1Y`)
 * into a count and a unit. Kept in one place so the paywall never invents a
 * duration Play did not return, and the wording is localized at the UI.
 */
object BillingPeriods {

    // The grammar of java.time.Period.parse: an optional sign, then P and at least
    // one of the Y, M, W and D parts in that order, each with its own optional sign.
    private val PATTERN = Regex(
        "([-+]?)P(?:([-+]?[0-9]+)Y)?(?:([-+]?[0-9]+)M)?(?:([-+]?[0-9]+)W)?(?:([-+]?[0-9]+)D)?",
        RegexOption.IGNORE_CASE,
    )

    /** A period, or null when [iso] is blank or cannot be parsed. */
    fun parse(iso: String?): BillingPeriod? {
        if (iso.isNullOrBlank()) return null
        val match = PATTERN.matchEntire(iso) ?: return null
        val (sign, y, m, w, d) = match.destructured
        if (y.isEmpty() && m.isEmpty() && w.isEmpty() && d.isEmpty()) return null
        return runCatching {
            val negate = sign == "-"
            fun part(text: String): Int {
                if (text.isEmpty()) return 0
                val value = text.removePrefix("+").toInt()
                return if (negate) exactInt(-value.toLong()) else value
            }
            val days = exactInt(part(d).toLong() + part(w).toLong() * 7)
            BillingPeriod(years = part(y), months = part(m), days = days)
        }.getOrNull()
    }

    /** The period as a count and a unit, or null when empty/unparseable. */
    fun parts(iso: String?): PeriodParts? {
        val period = parse(iso) ?: return null
        val years = period.years
        val months = period.months
        val days = period.days
        return when {
            years > 0 -> PeriodParts(years, PeriodUnit.YEAR)
            months > 0 -> PeriodParts(months, PeriodUnit.MONTH)
            days % 7 == 0 && days >= 7 -> PeriodParts(days / 7, PeriodUnit.WEEK)
            days > 0 -> PeriodParts(days, PeriodUnit.DAY)
            else -> null
        }
    }

    /**
     * The instant a trial starting at [startMillis] ends, or null without a trial.
     * Calendar arithmetic in UTC, as `ZonedDateTime.plus(Period)`: months first
     * (a day past the end of the month clamps to its last day), then days.
     */
    fun endMillis(startMillis: Long, trialPeriodIso: String?): Long? {
        val period = parse(trialPeriodIso) ?: return null
        return runCatching {
            val epochDay = startMillis.floorDiv(MILLIS_PER_DAY)
            val timeOfDay = startMillis.mod(MILLIS_PER_DAY)
            val date = CivilDate.ofEpochDay(epochDay)
            val totalMonths = period.years.toLong() * 12 + period.months
            val shifted = if (totalMonths == 0L) date else date.plusMonths(totalMonths)
            val end = shifted.toEpochDay() + period.days
            require(end in MIN_EPOCH_DAY..MAX_EPOCH_DAY) { "out of range" }
            end * MILLIS_PER_DAY + timeOfDay
        }.getOrNull()
    }

    private const val MILLIS_PER_DAY = 86_400_000L

    // The epoch days whose millis fit in a Long, so the product cannot overflow.
    private const val MAX_EPOCH_DAY = Long.MAX_VALUE / MILLIS_PER_DAY - 1
    private const val MIN_EPOCH_DAY = -MAX_EPOCH_DAY

    private fun exactInt(value: Long): Int {
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "overflow" }
        return value.toInt()
    }
}

/** A proleptic Gregorian date, enough for [BillingPeriods.endMillis]. */
private data class CivilDate(val year: Long, val month: Int, val day: Int) {

    fun plusMonths(months: Long): CivilDate {
        val index = year * 12 + (month - 1) + months
        val newYear = index.floorDiv(12L)
        val newMonth = index.mod(12L).toInt() + 1
        return CivilDate(newYear, newMonth, minOf(day, lengthOf(newYear, newMonth)))
    }

    // Howard Hinnant's days_from_civil.
    fun toEpochDay(): Long {
        val y = if (month <= 2) year - 1 else year
        val era = y.floorDiv(400L)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    companion object {
        // Howard Hinnant's civil_from_days.
        fun ofEpochDay(epochDay: Long): CivilDate {
            val z = epochDay + 719_468
            val era = z.floorDiv(146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val month = if (mp < 10) (mp + 3).toInt() else (mp - 9).toInt()
            val year = yoe + era * 400 + if (month <= 2) 1 else 0
            return CivilDate(year, month, day)
        }

        private fun lengthOf(year: Long, month: Int): Int = when (month) {
            2 -> if (year % 4 == 0L && (year % 100 != 0L || year % 400 == 0L)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }
}
