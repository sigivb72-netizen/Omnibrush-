package app.mygachalist

import app.mygachalist.data.GameStatus
import app.mygachalist.data.GamesParser
import app.mygachalist.data.ListStatus
import app.mygachalist.data.Precision
import app.mygachalist.data.Region
import app.mygachalist.data.gameStatus
import app.mygachalist.data.release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GamesParserTest {
    private val full = """
        {"schemaVersion":1,"games":[{
          "id":"test-game","title":"Test Game",
          "altTitles":{"jp":"テスト","cn":"","kr":"테스트"},
          "developer":"Dev","publisher":"Pub",
          "genres":["RPG"],"tags":["anime"],"platforms":["Android","iOS"],
          "cover":"https://example.com/c.png","description":"Desc",
          "officialSite":"https://example.com",
          "storeLinks":{"googlePlay":"https://play.google.com/store/apps/details?id=x"},
          "status":"pre-registration",
          "releases":{
            "global":{"date":"2027-01-15","precision":"exact","confirmed":true},
            "jp":{"date":"2027-Q2","precision":"quarter","confirmed":false},
            "cn":{"date":"TBA","precision":"TBA","confirmed":false}
          },
          "sourceUrl":"https://example.com/news","lastVerified":"2026-11-01","adult":false
        }]}
    """.trimIndent()

    @Test
    fun parsesFullEntry() {
        val parsed = GamesParser.parse(full)
        assertEquals(1, parsed.games.size)
        assertEquals(0, parsed.skipped)
        val g = parsed.games[0]
        assertEquals("Test Game", g.title)
        assertEquals(listOf("テスト", "테스트"), g.altTitles.all())
        assertEquals(GameStatus.PRE_REGISTRATION, g.gameStatus)
        assertEquals(Precision.EXACT, g.release(Region.GLOBAL).date.precision)
        assertTrue(g.release(Region.GLOBAL).confirmed)
        assertEquals(Precision.QUARTER, g.release(Region.JP).date.precision)
        assertFalse(g.release(Region.JP).confirmed)
        assertTrue(g.release(Region.CN).date.isTba)
        assertTrue(g.release(Region.KR).date.isTba) // missing region
    }

    @Test
    fun missingFieldsGetDefaults() {
        val g = GamesParser.parse("""{"games":[{"id":"a","title":"A"}]}""").games.single()
        assertEquals("", g.developer)
        assertTrue(g.genres.isEmpty())
        assertEquals(GameStatus.ANNOUNCED, g.gameStatus)
        assertFalse(g.adult)
    }

    @Test
    fun unknownKeysAndNullsAreIgnored() {
        val g = GamesParser.parse("""{"games":[{"id":"a","title":"A","futureField":{"x":1},"developer":null,"genres":null}]}""").games.single()
        assertEquals("", g.developer)
        assertTrue(g.genres.isEmpty())
    }

    @Test
    fun brokenEntryIsSkippedNotFatal() {
        val text = """{"games":[
            {"id":"ok","title":"OK"},
            {"id":"bad","title":"Bad","genres":"not-a-list"},
            {"id":"","title":"No id"},
            {"title":"Also no id"},
            "just a string",
            {"id":"ok","title":"Duplicate id"},
            {"id":"ok2","title":"OK 2","releases":{"global":{"date":"someday"}}}
        ]}"""
        val parsed = GamesParser.parse(text)
        assertEquals(listOf("ok", "ok2"), parsed.games.map { it.id })
        assertEquals(5, parsed.skipped)
        assertTrue(parsed.games[1].release(Region.GLOBAL).date.isTba)
    }

    @Test
    fun acceptsBareArray() {
        assertEquals(1, GamesParser.parse("""[{"id":"a","title":"A"}]""").games.size)
    }

    @Test
    fun malformedFileThrowsTypedError() {
        for (bad in listOf("", "{", "<html>404</html>", """{"games":"nope"}""", """{"nogames":[]}""", "42")) {
            assertThrows(bad, GamesParser.MalformedDataException::class.java) { GamesParser.parse(bad) }
        }
    }

    @Test
    fun unknownStatusFallsBackToAnnounced() {
        val g = GamesParser.parse("""{"games":[{"id":"a","title":"A","status":"???"},{"id":"b","title":"B","status":"EOS"}]}""").games
        assertEquals(GameStatus.ANNOUNCED, g[0].gameStatus)
        assertEquals(GameStatus.END_OF_SERVICE, g[1].gameStatus)
    }

    @Test
    fun newsParsingNeverThrows() {
        assertTrue(GamesParser.parseNews("garbage").isEmpty())
        val items = GamesParser.parseNews("""{"items":[{"title":"T","url":"https://x.y/1"},{"title":"","url":"https://x.y/2"},{"title":"No link","url":"javascript:1"}]}""")
        assertEquals(1, items.size)
    }

    @Test
    fun listExportRoundTripAndGarbage() {
        val text = """{"app":"MyGachaList","version":1,"entries":[
            {"gameId":"a","title":"A","status":"playing","score":99,"playAmount":48,"playUnit":"hours"},
            {"gameId":"b","status":"weird"},
            {"title":"no id"}
        ]}"""
        val entries = GamesParser.parseListExport(text)!!
        assertEquals(2, entries.size)
        assertEquals(10, entries[0].score) // clamped
        assertEquals(2.0, entries[0].daysPlayed, 0.001)
        assertEquals(ListStatus.PLAN_TO_PLAY, entries[1].listStatus)
        assertNull(GamesParser.parseListExport("not json"))
        assertNull(GamesParser.parseListExport("""{"foo":1}"""))
    }

    /** The real database in this repo must always load completely. */
    @Test
    fun repositorySeedFileParsesCleanly() {
        val file = listOf(File("../data/games.json"), File("data/games.json")).first { it.exists() }
        val parsed = GamesParser.parse(file.readText())
        assertEquals("entries skipped in data/games.json", 0, parsed.skipped)
    }
}
