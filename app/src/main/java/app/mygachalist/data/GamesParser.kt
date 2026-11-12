package app.mygachalist.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

data class ParsedGames(val games: List<Game>, val skipped: Int)

/**
 * Tolerant reader for games.json. The file is edited by hand (often on a phone), so:
 *  - a completely broken file throws [MalformedDataException] and the caller keeps its cache;
 *  - a single broken entry is skipped instead of failing the whole file;
 *  - entries without an id or title, and duplicate ids, are skipped.
 */
object GamesParser {
    class MalformedDataException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun parse(text: String): ParsedGames {
        val root: JsonElement = try {
            AppJson.parseToJsonElement(text)
        } catch (e: Exception) {
            throw MalformedDataException("games.json is not valid JSON", e)
        }
        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> root["games"] as? JsonArray
                ?: throw MalformedDataException("games.json has no \"games\" list")
            else -> throw MalformedDataException("games.json has an unexpected shape")
        }
        val out = ArrayList<Game>(array.size)
        val seen = HashSet<String>()
        var skipped = 0
        for (el in array) {
            val game = try {
                AppJson.decodeFromJsonElement<Game>(el)
            } catch (_: Exception) {
                null
            }
            if (game == null || game.id.isBlank() || game.title.isBlank() || !seen.add(game.id)) {
                skipped++
                continue
            }
            out += game
        }
        return ParsedGames(out, skipped)
    }

    fun parseNews(text: String): List<NewsItem> = try {
        AppJson.decodeFromString<NewsFile>(text).items.filter { it.title.isNotBlank() && it.url.startsWith("http") }
    } catch (_: Exception) {
        emptyList()
    }

    /** Reads an exported list. Returns null when the text is not a list export at all. */
    fun parseListExport(text: String): List<ListEntry>? = try {
        val root = AppJson.parseToJsonElement(text)
        val arr = when (root) {
            is JsonArray -> root
            is JsonObject -> root["entries"] as? JsonArray
            else -> null
        }
        arr?.mapNotNull { el ->
            try {
                AppJson.decodeFromJsonElement<ListEntry>(el).takeIf { it.gameId.isNotBlank() }
                    ?.let { it.copy(score = it.score.coerceIn(0, 10), status = ListStatus.fromKey(it.status).key) }
            } catch (_: Exception) {
                null
            }
        }
    } catch (_: Exception) {
        null
    }
}
