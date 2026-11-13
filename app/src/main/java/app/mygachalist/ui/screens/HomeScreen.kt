package app.mygachalist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mygachalist.MainViewModel
import app.mygachalist.data.*
import app.mygachalist.ui.components.*
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

private const val JUST_LAUNCHED_DAYS = 120L

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onGame: (String) -> Unit,
    onSeasonal: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val games by vm.games.collectAsStateWithLifecycle()
    val gamesById by vm.gamesById.collectAsStateWithLifecycle()
    val news by vm.news.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val revealed by vm.revealed.collectAsStateWithLifecycle()
    val region = settings?.region ?: Region.GLOBAL
    val today = remember { LocalDate.now() }
    val openLink = rememberLinkOpener()

    val soon = remember(games, region) {
        games.asSequence()
            .filter { it.gameStatus != GameStatus.END_OF_SERVICE }
            // A game that is already out only belongs here if it is still coming to *your* region.
            .filter { it.gameStatus != GameStatus.RELEASED || it.release(region).date.isUpcoming(today) }
            // Soonest upcoming launch in any region (the card names the region); ties go to the default region.
            .mapNotNull { g ->
                g.allReleases().filter { it.date.isUpcoming(today) }
                    .minWithOrNull(compareBy<RegionDate>({ it.date.start }, { it.region != region }))
                    ?.let { g to it }
            }
            .sortedWith(compareBy({ it.second.date.start }, { it.second.date.precision.ordinal }))
            .take(15).toList()
    }
    val launched = remember(games, region) {
        val floor = today.minusDays(JUST_LAUNCHED_DAYS)
        games.asSequence().mapNotNull { g ->
            val recent = g.allReleases().filter {
                (it.date.precision == Precision.EXACT || it.date.precision == Precision.MONTH) &&
                    it.date.isReleased(today) && !it.date.end!!.isBefore(floor)
            }
            (recent.firstOrNull { it.region == region } ?: recent.maxByOrNull { it.date.start!! })?.let { g to it }
        }.sortedByDescending { it.second.date.start }.take(8).toList()
    }
    val announced = remember(games) {
        games.filter { it.gameStatus in setOf(GameStatus.ANNOUNCED, GameStatus.PRE_REGISTRATION, GameStatus.BETA) }
            .sortedWith(compareByDescending<Game> { it.added }.thenByDescending { it.popularity })
            .take(8)
    }

    // Games with an announced closing date that is still ahead, or that closed within the last year.
    val closing = remember(games) {
        val floor = today.minusDays(365)
        games.mapNotNull { g -> g.eosDate()?.let { g to it } }
            .filter { it.second.end!!.isAfter(floor) }
            .sortedWith(compareBy<Pair<Game, ReleaseDate>>({ !it.second.isUpcoming(today) }, { if (it.second.isUpcoming(today)) it.second.start!!.toEpochDay() else -it.second.start!!.toEpochDay() }))
            .take(8)
    }
    val shutdownNews = remember(news) { news.filter { it.isShutdownNews }.take(6) }

    Column(Modifier.fillMaxSize()) {
        AppTopBar("MyGachaList") {
            if (refreshing) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh data") }
            }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
        }
        val error = settings?.lastError.orEmpty()
        if (error.isNotBlank() && !refreshing) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(error, fontSize = 12.5.sp, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                TextButton(onClick = { vm.refresh() }) { Text("Retry") }
            }
        }
        when {
            loading && games.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            games.isEmpty() -> EmptyState(
                Icons.Filled.Info, "No game data yet",
                "The game list could not be loaded. Check your connection and try again.",
                action = "Try again", onAction = { vm.refresh() },
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "h-soon") { SectionHeader("Releasing soon", "Calendar", onSeasonal) }
                item(key = "soon") {
                    if (soon.isEmpty()) {
                        Text("No dated releases coming up.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                    } else {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(soon, key = { it.first.id }) { (g, d) ->
                                SoonCard(g, d, today, g.id in revealed) { onGame(g.id) }
                            }
                        }
                    }
                }
                if (launched.isNotEmpty()) {
                    item(key = "h-launched") { SectionHeader("Just launched") }
                    items(launched, key = { "l-" + it.first.id }, contentType = { "game" }) { (g, d) ->
                        GameRow(g, entries[g.id], d, g.id in revealed, { onGame(g.id) }, showRegion = true)
                        RowDivider()
                    }
                }
                if (announced.isNotEmpty()) {
                    item(key = "h-announced") { SectionHeader("Recently announced", "Browse", onSearch) }
                    items(announced, key = { "a-" + it.id }, contentType = { "game" }) { g ->
                        GameRow(g, entries[g.id], g.primaryRelease(region), g.id in revealed, { onGame(g.id) }, showRegion = true)
                        RowDivider()
                    }
                }
                if (closing.isNotEmpty() || shutdownNews.isNotEmpty()) {
                    item(key = "h-closing", contentType = "header") { SectionHeader("Shutting down") }
                    items(closing, key = { "c-" + it.first.id }, contentType = { "game" }) { (g, d) ->
                        val days = d.daysUntil(today)
                        val note = when {
                            days != null -> "Service ends in $days days · ${d.display()}"
                            d.isUpcoming(today) -> "Service ends ${d.display()}"
                            else -> "Service ended ${d.display()}"
                        }
                        GameRow(g, entries[g.id], null, g.id in revealed, { onGame(g.id) }, note = note)
                        RowDivider()
                    }
                    items(shutdownNews, key = { "cn-" + it.id }, contentType = { "news" }) { n ->
                        NewsRow(n, gamesById) { openLink(n.url) }
                        RowDivider()
                    }
                }
                item(key = "h-news") { SectionHeader("Latest news") }
                if (news.isEmpty()) {
                    item(key = "no-news") {
                        Text(
                            "No headlines yet. They appear after the news scraper has run.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp),
                        )
                    }
                } else {
                    items(news.take(15), key = { "n-" + it.id }, contentType = { "news" }) { n ->
                        NewsRow(n, gamesById) { openLink(n.url) }
                        RowDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SoonCard(game: Game, date: RegionDate, today: LocalDate, revealed: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(104.dp).clickable(onClick = onClick)) {
        Box {
            CoverImage(game, revealed, Modifier.size(width = 104.dp, height = 138.dp))
            val days = date.date.daysUntil(today)
            Text(
                when {
                    days == null -> date.date.display()
                    days == 1L -> "Tomorrow"
                    else -> "$days days"
                },
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xCC1C2F66)).padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        Text(game.title, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        Text(
            date.date.display() + " · " + date.region.label + if (date.confirmed) "" else " ?",
            fontSize = 10.5.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun NewsRow(item: NewsItem, gamesById: Map<String, Game>, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(item.title, fontSize = 13.5.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val tagged = item.gameIds.mapNotNull { gamesById[it]?.title }.take(2).joinToString(", ")
        Text(
            listOf(item.source, relativeTime(item.published), tagged).filter { it.isNotBlank() }.joinToString(" · "),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

fun relativeTime(iso: String): String = try {
    val then = OffsetDateTime.parse(iso)
    val hours = ChronoUnit.HOURS.between(then, OffsetDateTime.now())
    when {
        hours < 1 -> "just now"
        hours < 24 -> "${hours}h ago"
        hours < 24 * 30 -> "${hours / 24}d ago"
        else -> then.toLocalDate().toString()
    }
} catch (_: Exception) {
    ""
}
