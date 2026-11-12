package app.mygachalist

import app.mygachalist.data.Game
import app.mygachalist.data.ListEntry
import app.mygachalist.data.Precision
import app.mygachalist.data.Region
import app.mygachalist.data.RegionRelease
import app.mygachalist.data.ReleaseDate
import app.mygachalist.data.Season
import app.mygachalist.data.Settings
import app.mygachalist.data.nextUpcoming
import app.mygachalist.data.primaryRelease
import app.mygachalist.work.Notifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReleaseDateTest {
    private val today = LocalDate.of(2026, 11, 12)

    @Test
    fun parsesEveryPrecision() {
        assertEquals(Precision.EXACT, ReleaseDate.parse("2026-11-26").precision)
        assertEquals("Nov 26, 2026", ReleaseDate.parse("2026-11-26").display())
        assertEquals("Mar 2026", ReleaseDate.parse("2026-03").display())
        assertEquals("Q2 2027", ReleaseDate.parse("2027-Q2").display())
        assertEquals("2027", ReleaseDate.parse("2027").display())
        for (tba in listOf("TBA", "", "soon", "2026-13-40", "2026-02-30", null)) {
            assertTrue("$tba", ReleaseDate.parse(tba).isTba)
        }
    }

    @Test
    fun precisionHintOnlyCoarsens() {
        assertEquals(Precision.MONTH, ReleaseDate.parse("2026-03-12", "month").precision)
        assertEquals(Precision.MONTH, ReleaseDate.parse("2026-03", "exact").precision)
        assertTrue(ReleaseDate.parse("2026-03-12", "TBA").isTba)
    }

    @Test
    fun seasonsFollowQuarters() {
        assertEquals(Season.WINTER, ReleaseDate.parse("2026-01-22").season)
        assertEquals(Season.SPRING, ReleaseDate.parse("2026-04").season)
        assertEquals(Season.SUMMER, ReleaseDate.parse("2026-Q3").season)
        assertEquals(Season.FALL, ReleaseDate.parse("2026-11-26").season)
        assertNull(ReleaseDate.parse("2026").season)
    }

    @Test
    fun upcomingAndCountdown() {
        assertEquals(14L, ReleaseDate.parse("2026-11-26").daysUntil(today))
        assertNull(ReleaseDate.parse("2026-12").daysUntil(today))
        assertTrue(ReleaseDate.parse("2026-11").isUpcoming(today)) // month not over yet
        assertTrue(ReleaseDate.parse("2026-11-12").isReleased(today))
        assertTrue(ReleaseDate.parse("2026-10").isReleased(today))
    }

    private fun game(vararg rel: Pair<String, String>, status: String = "announced") = Game(
        id = "g", title = "G", status = status,
        releases = rel.associate { it.first to RegionRelease(it.second) },
    )

    @Test
    fun primaryAndNextRelease() {
        val g = game("jp" to "2026-11-26", "global" to "2027-Q3", "kr" to "TBA")
        assertEquals(Region.JP, g.primaryRelease(Region.JP).region)
        assertEquals(Region.GLOBAL, g.primaryRelease(Region.KR).region) // falls back to Global
        assertEquals(Region.JP, g.nextUpcoming(today, Region.CN)!!.region) // soonest anywhere
        assertEquals(Region.GLOBAL, g.nextUpcoming(today, Region.GLOBAL)!!.region)
        assertNull(game("global" to "2020-09-28").nextUpcoming(today, Region.GLOBAL))
    }

    @Test
    fun alertsOnlyForPlanToPlay() {
        val out = game("global" to today.toString())
        val prereg = game("global" to "TBA", status = "pre-registration").copy(id = "p", title = "P")
        val entries = listOf(
            ListEntry(gameId = "g", status = "plan_to_play"),
            ListEntry(gameId = "p", status = "plan_to_play"),
        )
        val alerts = Notifier.planAlerts(listOf(out, prereg), entries, Settings(), today)
        assertEquals(setOf("release:g:global:$today", "prereg:p"), alerts.map { it.key }.toSet())

        val playing = entries.map { it.copy(status = "playing") }
        assertTrue(Notifier.planAlerts(listOf(out, prereg), playing, Settings(), today).isEmpty())
        assertTrue(Notifier.planAlerts(listOf(out, prereg), entries, Settings(notifyRelease = false, notifyPrereg = false), today).isEmpty())
        assertTrue(Notifier.planAlerts(listOf(out), entries, Settings(), today.plusDays(1)).isEmpty())
    }
}
