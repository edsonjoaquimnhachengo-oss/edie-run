package com.edierun.vault.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Datas em português, como o iPhone as mostra ("Hoje", "Ontem", "Segunda-feira", "3 de novembro"). */
object Dates {
    private val pt: Locale = Locale.forLanguageTag("pt-PT")
    private val weekday = DateTimeFormatter.ofPattern("EEEE", pt)
    private val dayMonth = DateTimeFormatter.ofPattern("d 'de' MMMM", pt)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", pt)
    private val monthYear = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", pt)
    private val hm = DateTimeFormatter.ofPattern("HH:mm", pt)

    private fun date(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()

    fun dayLabel(ts: Long): String {
        val d = date(ts)
        val today = LocalDate.now()
        val diff = ChronoUnit.DAYS.between(d, today)
        return when {
            diff == 0L -> "Hoje"
            diff == 1L -> "Ontem"
            diff in 2..6 -> d.format(weekday).replaceFirstChar { it.titlecase(pt) }
            d.year == today.year -> d.format(dayMonth)
            else -> d.format(dayMonthYear)
        }
    }

    fun fullDay(ts: Long): String = date(ts).format(dayMonthYear)

    fun monthLabel(ts: Long): String = date(ts).format(monthYear).replaceFirstChar { it.titlecase(pt) }

    fun time(ts: Long): String = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(hm)
}
