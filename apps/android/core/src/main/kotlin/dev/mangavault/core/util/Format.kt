package dev.mangavault.core.util

import dev.mangavault.core.model.Chapter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object Format {
    /** 112.0 → "112", 112.5 → "112.5". */
    fun number(n: Double?): String {
        if (n == null) return "?"
        return if (n % 1.0 == 0.0) n.toLong().toString() else n.toBigDecimal().stripTrailingZeros().toPlainString()
    }

    fun chapter(n: Double?): String = "Ch. ${number(n)}"

    fun chapterLabel(c: Chapter): String = if (c.number != null) chapter(c.number) else c.title ?: "Chapter ${c.index + 1}"

    /** "Ch. 112 · The Ash Garden" */
    fun chapterWithTitle(c: Chapter): String =
        listOfNotNull(chapterLabel(c), c.title?.takeIf { c.number != null && it.isNotBlank() }).joinToString(" · ")

    /** The chapter row subtitle: "Vol. 12 · The Ash Garden · Oct 10". */
    fun chapterSub(c: Chapter, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String =
        listOfNotNull(
            c.volume?.takeIf { it.isNotBlank() }?.let { "Vol. $it" },
            c.title?.takeIf { it.isNotBlank() && c.number != null },
            c.publishedAt?.let { date(it, zone, today) },
        ).joinToString(" · ")

    fun parse(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** "Oct 10" this year, "Oct 10, 2024" otherwise. */
    fun date(iso: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String? {
        val d = parse(iso)?.atZone(zone)?.toLocalDate() ?: return null
        val pattern = if (d.year == today.year) "MMM d" else "MMM d, yyyy"
        return d.format(DateTimeFormatter.ofPattern(pattern, Locale.US))
    }

    /** "manhwa" → "Manhwa" */
    fun label(v: String?): String? = v?.replaceFirstChar { it.titlecase(Locale.US) }

    /** History section for a read time: Today, Yesterday, This week or Earlier. */
    fun historyGroup(iso: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        val d = parse(iso)?.atZone(zone)?.toLocalDate() ?: return "Earlier"
        val days = ChronoUnit.DAYS.between(d, today)
        return when {
            days <= 0L -> "Today"
            days == 1L -> "Yesterday"
            days < 7L -> "This week"
            else -> "Earlier"
        }
    }

    /** "11:48 PM" today and yesterday, the weekday this week, else "Oct 2". */
    fun historyTime(iso: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        val t = parse(iso)?.atZone(zone) ?: return ""
        return when (historyGroup(iso, zone, today)) {
            "Today", "Yesterday" -> t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
            "This week" -> t.format(DateTimeFormatter.ofPattern("EEEE", Locale.US))
            else -> t.format(DateTimeFormatter.ofPattern(if (t.year == today.year) "MMM d" else "MMM d, yyyy", Locale.US))
        }
    }

    /** 12345 → "12,345" */
    fun count(n: Long): String = String.format(Locale.US, "%,d", n)

    fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
}

object ReaderModes {
    const val VERTICAL = "vertical"
    const val RTL = "rtl"
    const val LTR = "ltr"
    const val PAGED_VERTICAL = "pagedV"
    const val AUTO = "auto"

    val all = listOf(VERTICAL, RTL, LTR, PAGED_VERTICAL)

    fun name(mode: String) = when (mode) {
        VERTICAL -> "Webtoon scroll"
        RTL -> "Right to left"
        LTR -> "Left to right"
        PAGED_VERTICAL -> "Vertical pages"
        else -> "By title type"
    }

    /** Manga reads right to left, comics left to right, manhwa and manhua scroll. */
    fun forType(type: String?) = when (type) {
        "manga" -> RTL
        "comic" -> LTR
        else -> VERTICAL
    }

    fun resolve(perTitle: String?, default: String, type: String?): String =
        perTitle ?: if (default == AUTO || default !in all) forType(type) else default
}

/** Chapter ranges for the Jump sheet: up to 12 buckets of 10, 20 or 50. */
fun jumpBuckets(count: Int): List<IntRange> {
    if (count <= 0) return emptyList()
    val size = if (count > 200) 50 else if (count > 60) 20 else 10
    return (1..count step size).take(12).map { it..minOf(count, it + size - 1) }
}
