package app.mygachalist.data

import android.content.Context
import app.mygachalist.data.db.AppDatabase
import app.mygachalist.data.db.GameEntity
import app.mygachalist.data.db.ListEntryEntity
import app.mygachalist.data.db.NewsEntity
import app.mygachalist.data.db.NotifiedEntity
import app.mygachalist.data.remote.GachaApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.time.LocalDate

sealed interface RefreshResult {
    data class Ok(val games: Int, val skipped: Int) : RefreshResult
    data class Failed(val message: String) : RefreshResult
}

class Repository(
    private val context: Context,
    private val db: AppDatabase,
    private val api: GachaApi,
    val prefs: Prefs,
) {
    private val refreshLock = Mutex()

    val games: Flow<List<Game>> = db.games().observeAll()
        .distinctUntilChanged()
        .map { rows -> rows.mapNotNull { it.toGame() } }
        .flowOn(Dispatchers.Default)

    val news: Flow<List<NewsItem>> = db.news().observeAll().map { rows ->
        rows.map { NewsItem(it.id, it.title, it.url, it.source, it.lang, it.published, it.gameIds.split(',').filter(String::isNotBlank)) }
    }.flowOn(Dispatchers.Default)

    val entries: Flow<List<ListEntry>> = db.list().observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun allGames(): List<Game> = withContext(Dispatchers.Default) { db.games().getAll().mapNotNull { it.toGame() } }
    suspend fun allEntries(): List<ListEntry> = db.list().getAll().map { it.toModel() }

    /** First launch: load the games.json bundled in the APK so the app is useful before any download. */
    suspend fun seedIfEmpty() = withContext(Dispatchers.IO) {
        if (db.games().count() > 0) return@withContext
        try {
            val text = context.assets.open("games.json").bufferedReader().use { it.readText() }
            val parsed = GamesParser.parse(text)
            if (parsed.games.isNotEmpty()) db.games().replaceAll(parsed.games.map { it.toEntity() })
        } catch (_: Exception) {
            // No seed or a broken seed: the app simply starts empty and waits for a refresh.
        }
        try {
            val text = context.assets.open("news.json").bufferedReader().use { it.readText() }
            val items = GamesParser.parseNews(text)
            if (items.isNotEmpty()) db.news().replaceAll(items.map { it.toEntity() })
        } catch (_: Exception) {
        }
    }

    /** Downloads games.json (+ news.json). The cache is only replaced by a file that parsed and is non-empty. */
    suspend fun refresh(): RefreshResult = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val result = try {
                val text = api.games().use { it.string() }
                val parsed = GamesParser.parse(text)
                if (parsed.games.isEmpty()) {
                    RefreshResult.Failed("The online game list is empty. Keeping your saved copy.")
                } else {
                    db.games().replaceAll(parsed.games.map { it.toEntity() })
                    RefreshResult.Ok(parsed.games.size, parsed.skipped)
                }
            } catch (e: GamesParser.MalformedDataException) {
                RefreshResult.Failed("The online game list is damaged (${e.message}). Keeping your saved copy.")
            } catch (e: IOException) {
                RefreshResult.Failed("No connection. Showing saved data.")
            } catch (e: Exception) {
                RefreshResult.Failed("Could not update (${e.javaClass.simpleName}). Showing saved data.")
            }
            if (result is RefreshResult.Ok) {
                try {
                    val items = api.news().items.filter { it.title.isNotBlank() && it.url.startsWith("http") }
                    if (items.isNotEmpty()) db.news().replaceAll(items.map { it.toEntity() })
                } catch (_: Exception) {
                    // News is optional; keep whatever is cached.
                }
            }
            prefs.setRefreshResult(result is RefreshResult.Ok, (result as? RefreshResult.Failed)?.message.orEmpty())
            result
        }
    }

    suspend fun saveEntry(entry: ListEntry, game: Game?) {
        db.list().upsert(entry.copy(updatedAt = System.currentTimeMillis()).toEntity())
        // Don't alert about a pre-registration the user could already see when adding the game.
        if (game != null && game.gameStatus == GameStatus.PRE_REGISTRATION) markNotified("prereg:${game.id}")
    }

    suspend fun deleteEntry(gameId: String) = db.list().delete(gameId)

    suspend fun exportJson(): String {
        val export = ListExport(exported = LocalDate.now().toString(), entries = allEntries().sortedBy { it.titleSnapshot })
        return PrettyJson.encodeToString(export)
    }

    /** Returns the number of imported entries, or null if the text is not a valid export. */
    suspend fun importJson(text: String, replace: Boolean): Int? {
        val items = GamesParser.parseListExport(text) ?: return null
        val now = System.currentTimeMillis()
        val rows = items.distinctBy { it.gameId }.map { it.copy(updatedAt = if (it.updatedAt > 0) it.updatedAt else now).toEntity() }
        if (replace) db.list().replaceAll(rows) else db.list().upsertAll(rows)
        return rows.size
    }

    suspend fun wasNotified(key: String): Boolean = db.notified().has(key)
    suspend fun markNotified(key: String) = db.notified().mark(NotifiedEntity(key, System.currentTimeMillis()))

    private fun GameEntity.toGame(): Game? = try {
        AppJson.decodeFromString<Game>(json)
    } catch (_: Exception) {
        null
    }

    private fun Game.toEntity() = GameEntity(id, title, status, adult, AppJson.encodeToString(this))

    private fun NewsItem.toEntity() =
        NewsEntity(id.ifBlank { url }, title, url, source, lang, published, gameIds.joinToString(","))

    private fun ListEntryEntity.toModel() = ListEntry(
        gameId, title, status, score, startDate, quitDate, region, server, playAmount, playUnit, spent, currency, notes, updatedAt,
    )

    private fun ListEntry.toEntity() = ListEntryEntity(
        gameId, titleSnapshot, status, score, startDate, quitDate, region, server, playAmount, playUnit, spent, currency, notes, updatedAt,
    )

    private companion object {
        val PrettyJson = kotlinx.serialization.json.Json(AppJson) { prettyPrint = true }
    }
}
