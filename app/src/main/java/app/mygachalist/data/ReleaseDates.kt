package app.mygachalist.data

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class Precision { EXACT, MONTH, QUARTER, YEAR, TBA }

enum class Season(val label: String, val quarter: Int) {
    WINTER("Winter", 1), SPRING("Spring", 2), SUMMER("Summer", 3), FALL("Fall", 4);

    companion object {
        fun ofMonth(month: Int): Season = entries[(month - 1) / 3]
    }
}

/**
 * A release date with honest precision. [start]..[end] is the window the date can fall in;
 * both are null for TBA.
 */
data class ReleaseDate(
    val precision: Precision,
    val start: LocalDate? = null,
    val end: LocalDate? = null,
) {
    val isTba: Boolean get() = precision == Precision.TBA
    val year: Int? get() = start?.year
    val yearMonth: YearMonth? get() =
        if (precision == Precision.EXACT || precision == Precision.MONTH) start?.let { YearMonth.from(it) } else null
    val season: Season? get() = when (precision) {
        Precision.EXACT, Precision.MONTH, Precision.QUARTER -> start?.let { Season.ofMonth(it.monthValue) }
        else -> null
    }

    fun display(): String = when (precision) {
        Precision.EXACT -> start!!.format(DAY_FMT)
        Precision.MONTH -> start!!.format(MONTH_FMT)
        Precision.QUARTER -> "Q${(start!!.monthValue - 1) / 3 + 1} ${start.year}"
        Precision.YEAR -> start!!.year.toString()
        Precision.TBA -> "TBA"
    }

    /** True once the whole window is in the past (or today for exact dates). */
    fun isReleased(today: LocalDate): Boolean = end != null && !end.isAfter(today)

    fun isUpcoming(today: LocalDate): Boolean = end != null && end.isAfter(today)

    /** Days until an exact date; null when not exact or already out. */
    fun daysUntil(today: LocalDate): Long? =
        if (precision == Precision.EXACT && start != null && start.isAfter(today)) ChronoUnit.DAYS.between(today, start) else null

    companion object {
        val TBA = ReleaseDate(Precision.TBA)
        private val DAY_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
        private val MONTH_FMT = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
        private val EXACT_RE = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")
        private val MONTH_RE = Regex("""^(\d{4})-(\d{1,2})$""")
        private val QUARTER_RE = Regex("""^(\d{4})-?[Qq]([1-4])$""")
        private val YEAR_RE = Regex("""^(\d{4})$""")

        /**
         * Parses "2026-03-12", "2026-03", "2026-Q2", "2026" or "TBA". Anything unparseable is TBA.
         * An explicit [precisionHint] can only make the date coarser, never invent detail.
         */
        fun parse(raw: String?, precisionHint: String? = null): ReleaseDate {
            val s = raw.orEmpty().trim()
            val parsed = try {
                parseShape(s)
            } catch (_: Exception) {
                TBA
            }
            val hint = when (precisionHint.orEmpty().trim().lowercase()) {
                "exact", "day" -> Precision.EXACT
                "month" -> Precision.MONTH
                "quarter", "season" -> Precision.QUARTER
                "year" -> Precision.YEAR
                "tba", "tbd", "unknown" -> Precision.TBA
                else -> null
            }
            if (hint == null || parsed.isTba || hint.ordinal <= parsed.precision.ordinal) return parsed
            val d = parsed.start!!
            return when (hint) {
                Precision.MONTH -> month(d.year, d.monthValue)
                Precision.QUARTER -> quarter(d.year, (d.monthValue - 1) / 3 + 1)
                Precision.YEAR -> year(d.year)
                else -> TBA
            }
        }

        private fun parseShape(s: String): ReleaseDate {
            EXACT_RE.matchEntire(s)?.let { m ->
                val d = LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                return ReleaseDate(Precision.EXACT, d, d)
            }
            MONTH_RE.matchEntire(s)?.let { m -> return month(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
            QUARTER_RE.matchEntire(s)?.let { m -> return quarter(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
            YEAR_RE.matchEntire(s)?.let { m -> return year(m.groupValues[1].toInt()) }
            return TBA
        }

        private fun month(y: Int, m: Int): ReleaseDate {
            val ym = YearMonth.of(y, m)
            return ReleaseDate(Precision.MONTH, ym.atDay(1), ym.atEndOfMonth())
        }

        private fun quarter(y: Int, q: Int): ReleaseDate {
            val first = YearMonth.of(y, (q - 1) * 3 + 1)
            return ReleaseDate(Precision.QUARTER, first.atDay(1), first.plusMonths(2).atEndOfMonth())
        }

        private fun year(y: Int) = ReleaseDate(Precision.YEAR, LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31))
    }
}

data class RegionDate(val region: Region, val date: ReleaseDate, val confirmed: Boolean)

fun Game.release(region: Region): RegionDate {
    val r = releases[region.key] ?: return RegionDate(region, ReleaseDate.TBA, false)
    return RegionDate(region, ReleaseDate.parse(r.date, r.precision), r.confirmed)
}

fun Game.allReleases(): List<RegionDate> = Region.entries.map { release(it) }

/** Earliest dated release in any region (null when everything is TBA). */
fun Game.earliestRelease(): RegionDate? =
    allReleases().filter { !it.date.isTba }.minByOrNull { it.date.start!! }

/** The date to show in lists: the preferred region if it has one, else Global, else the earliest. */
fun Game.primaryRelease(preferred: Region): RegionDate {
    val p = release(preferred)
    if (!p.date.isTba) return p
    val g = release(Region.GLOBAL)
    if (!g.date.isTba) return g
    return earliestRelease() ?: p
}

/** Next not-yet-released date: preferred region first, otherwise the soonest in any region. */
fun Game.nextUpcoming(today: LocalDate, preferred: Region): RegionDate? {
    val p = release(preferred)
    if (p.date.isUpcoming(today)) return p
    if (p.date.isReleased(today)) return null
    return allReleases().filter { it.date.isUpcoming(today) }.minByOrNull { it.date.start!! }
}

fun parseIsoDate(s: String?): LocalDate? = try {
    if (s.isNullOrBlank()) null else LocalDate.parse(s.trim().take(10))
} catch (_: Exception) {
    null
}
