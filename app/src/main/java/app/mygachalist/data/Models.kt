package app.mygachalist.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One game as stored in /data/games.json. Every field has a default so partial entries still load. */
@Serializable
data class Game(
    val id: String = "",
    val title: String = "",
    val altTitles: AltTitles = AltTitles(),
    val developer: String = "",
    val publisher: String = "",
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val cover: String = "",
    val description: String = "",
    val officialSite: String = "",
    val storeLinks: Map<String, String> = emptyMap(),
    val status: String = "announced",
    val releases: Map<String, RegionRelease> = emptyMap(),
    val endOfService: String = "",
    val sourceUrl: String = "",
    val lastVerified: String = "",
    val added: String = "",
    val popularity: Int = 0,
    val adult: Boolean = false,
)

@Serializable
data class AltTitles(val jp: String = "", val cn: String = "", val kr: String = "") {
    fun all(): List<String> = listOf(jp, cn, kr).filter { it.isNotBlank() }.distinct()
}

@Serializable
data class RegionRelease(
    val date: String = "TBA",
    val precision: String = "",
    val confirmed: Boolean = true,
)

@Serializable
data class NewsItem(
    val id: String = "",
    val title: String = "",
    val url: String = "",
    val source: String = "",
    val lang: String = "en",
    val published: String = "",
    val gameIds: List<String> = emptyList(),
)

@Serializable
data class NewsFile(val updated: String = "", val items: List<NewsItem> = emptyList())

enum class Region(val key: String, val label: String) {
    GLOBAL("global", "Global"),
    JP("jp", "Japan"),
    KR("kr", "Korea"),
    CN("cn", "China"),
    TW_SEA("tw_sea", "TW/SEA");

    companion object {
        fun fromKey(key: String?): Region? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

enum class GameStatus(val key: String, val label: String) {
    ANNOUNCED("announced", "Announced"),
    PRE_REGISTRATION("pre-registration", "Pre-registration"),
    BETA("beta", "Beta"),
    RELEASED("released", "Released"),
    END_OF_SERVICE("end-of-service", "End of service");

    companion object {
        fun fromKey(key: String?): GameStatus {
            val k = key.orEmpty().trim().lowercase().replace('_', '-').replace(' ', '-')
            return entries.firstOrNull { it.key == k } ?: when (k) {
                "prereg", "preregistration", "pre-register" -> PRE_REGISTRATION
                "eos", "shutdown" -> END_OF_SERVICE
                "live", "launched" -> RELEASED
                else -> ANNOUNCED
            }
        }
    }
}

val Game.gameStatus: GameStatus get() = GameStatus.fromKey(status)

enum class ListStatus(val key: String, val label: String) {
    PLAYING("playing", "Playing"),
    COMPLETED("completed", "Completed"),
    ON_HOLD("on_hold", "On Hold"),
    DROPPED("dropped", "Dropped"),
    PLAN_TO_PLAY("plan_to_play", "Plan to Play");

    companion object {
        fun fromKey(key: String?): ListStatus = entries.firstOrNull { it.key == key } ?: PLAN_TO_PLAY
    }
}

/** A row of the user's own list. Lives only on the device (plus JSON export). */
@Serializable
data class ListEntry(
    val gameId: String = "",
    @SerialName("title") val titleSnapshot: String = "",
    val status: String = ListStatus.PLAN_TO_PLAY.key,
    val score: Int = 0,
    val startDate: String = "",
    val quitDate: String = "",
    val region: String = "",
    val server: String = "",
    val playAmount: Double = 0.0,
    val playUnit: String = "hours",
    val spent: Double = 0.0,
    val currency: String = "",
    val notes: String = "",
    val updatedAt: Long = 0,
) {
    val listStatus: ListStatus get() = ListStatus.fromKey(status)
    val daysPlayed: Double get() = if (playUnit == "days") playAmount else playAmount / 24.0
}

@Serializable
data class ListExport(
    val app: String = "MyGachaList",
    val version: Int = 1,
    val exported: String = "",
    val entries: List<ListEntry> = emptyList(),
)
