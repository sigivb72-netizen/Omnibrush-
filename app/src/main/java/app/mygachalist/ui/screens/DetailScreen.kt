package app.mygachalist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mygachalist.MainViewModel
import app.mygachalist.data.*
import app.mygachalist.ui.components.*
import app.mygachalist.ui.theme.color
import java.time.LocalDate

private val STORE_LABELS = mapOf(
    "googlePlay" to "Google Play", "appStore" to "App Store", "steam" to "Steam", "epic" to "Epic Games",
    "playstation" to "PlayStation Store", "taptap" to "TapTap", "qooapp" to "QooApp", "dmm" to "DMM Games",
    "official" to "Official launcher",
)

@Composable
fun DetailScreen(vm: MainViewModel, gameId: String, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val gamesById by vm.gamesById.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val news by vm.news.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val revealedSet by vm.revealed.collectAsStateWithLifecycle()
    val game = gamesById[gameId]
    val openLink = rememberLinkOpener()

    Column(Modifier.fillMaxSize()) {
        AppTopBar(game?.title ?: "Game", onBack)
        if (game == null) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                EmptyState(
                    Icons.Filled.Info, "Game not available",
                    "This game is no longer in the database, or it is an 18+ title and the 18+ setting is off.",
                    action = "Go back", onAction = onBack,
                )
            }
            return@Column
        }
        val region = settings?.region ?: Region.GLOBAL
        val today = remember { LocalDate.now() }
        val entry = entries[game.id]
        val revealed = game.id in revealedSet

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                CoverImage(
                    game, revealed,
                    Modifier.size(width = 112.dp, height = 150.dp).clickable(enabled = game.adult && !revealed) { vm.reveal(game.id) },
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(game.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 22.sp)
                    game.altTitles.all().forEach {
                        Text(it, fontSize = 12.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(game.gameStatus)
                        if (game.adult) {
                            Text(
                                "18+", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 6.dp).background(Color(0xFFB71C1C), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Countdown(game, region, today)
                    if (game.adult && !revealed) {
                        Text("Tap the cover to show it.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            // Add to list / current list state
            if (entry == null) {
                Button(onClick = { onEdit(game.id) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(6.dp)) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add to list")
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp))
                        .clickable { onEdit(game.id) }.height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(6.dp).fillMaxHeight().background(entry.listStatus.color()))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(entry.listStatus.label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        val bits = listOfNotNull(
                            Region.fromKey(entry.region)?.label,
                            entry.server.takeIf { it.isNotBlank() },
                            if (entry.playAmount > 0) "${trimNumber(entry.playAmount)} ${entry.playUnit}" else null,
                        )
                        if (bits.isNotEmpty()) Text(bits.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.Star, null, Modifier.size(16.dp), tint = Color(0xFFF1B434))
                    Text(if (entry.score > 0) entry.score.toString() else "-", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 2.dp, end = 10.dp))
                    Icon(Icons.Filled.Edit, "Edit list entry", Modifier.padding(end = 12.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }

            SectionHeader("Information")
            Spacer(Modifier.height(4.dp))
            val info = buildList {
                add(Triple("Developer", game.developer.ifBlank { "Unknown" }, null))
                add(Triple("Publisher", game.publisher.ifBlank { "Unknown" }, null))
                if (game.genres.isNotEmpty()) add(Triple("Genres", game.genres.joinToString(", "), null))
                if (game.tags.isNotEmpty()) add(Triple("Tags", game.tags.joinToString(", "), null))
                if (game.platforms.isNotEmpty()) add(Triple("Platforms", game.platforms.joinToString(", "), null))
                add(Triple("Status", game.gameStatus.label, null))
                if (game.endOfService.isNotBlank()) add(Triple("Service ended", ReleaseDate.parse(game.endOfService).display(), null))
                add(Triple("Last verified", game.lastVerified.ifBlank { "Not verified yet" }, null))
                if (game.sourceUrl.isNotBlank()) add(Triple("Source", hostOf(game.sourceUrl), game.sourceUrl))
            }
            info.forEachIndexed { i, (label, value, link) ->
                InfoRow(label, value, striped = i % 2 == 0, onClick = link?.let { { openLink(it) } })
            }

            if (game.description.isNotBlank()) {
                SectionHeader("Synopsis")
                Text(game.description, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }

            SectionHeader("Release dates")
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 12.dp, vertical = 4.dp)) {
                val head = @Composable { t: String, m: Modifier -> Text(t, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = m) }
                head("Region", Modifier.width(84.dp)); head("Date", Modifier.weight(1f)); head("Source", Modifier.width(84.dp))
            }
            game.allReleases().forEachIndexed { i, r ->
                Row(
                    Modifier.fillMaxWidth().background(if (i % 2 == 1) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent).padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(r.region.label, fontSize = 12.5.sp, fontWeight = if (r.region == region) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.width(84.dp))
                    Text(r.date.display(), fontSize = 12.5.sp, modifier = Modifier.weight(1f), color = if (r.date.isTba) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    Text(
                        when {
                            r.date.isTba -> "–"
                            r.confirmed -> "Confirmed"
                            else -> "Rumoured"
                        },
                        fontSize = 12.sp, modifier = Modifier.width(84.dp),
                        color = if (r.confirmed || r.date.isTba) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFEF6C00),
                    )
                }
            }

            val links = buildList {
                if (game.officialSite.isNotBlank()) add("Official site" to game.officialSite)
                game.storeLinks.forEach { (k, v) -> if (v.startsWith("http")) add((STORE_LABELS[k] ?: k) to v) }
            }
            if (links.isNotEmpty()) {
                SectionHeader("Links")
                FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    links.forEach { (label, url) ->
                        OutlinedButton(onClick = { openLink(url) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(6.dp)) {
                            Text(label, fontSize = 13.sp)
                        }
                    }
                }
            }

            val related = remember(news, game.id) { news.filter { game.id in it.gameIds }.take(8) }
            SectionHeader("Related news")
            if (related.isEmpty()) {
                Text("No recent headlines mention this game.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
            } else {
                related.forEach { n ->
                    NewsRow(n, emptyMap()) { openLink(n.url) }
                    RowDivider()
                }
            }
        }
    }
}

@Composable
private fun Countdown(game: Game, region: Region, today: LocalDate) {
    val next = game.nextUpcoming(today, region)
    val (headline, sub) = when {
        game.gameStatus == GameStatus.END_OF_SERVICE ->
            "Service ended" to game.endOfService.takeIf { it.isNotBlank() }?.let { ReleaseDate.parse(it).display() }.orEmpty()
        next != null -> {
            val days = next.date.daysUntil(today)
            val where = next.region.label + if (next.confirmed) "" else ", rumoured"
            when {
                days == null -> "Expected ${next.date.display()}" to where
                days == 1L -> "Releases tomorrow" to "${next.date.display()} · $where"
                else -> "Releases in $days days" to "${next.date.display()} · $where"
            }
        }
        else -> {
            val p = game.primaryRelease(region)
            when {
                p.date.isTba -> "Release date TBA" to ""
                p.date.start == today -> "Out today" to p.region.label
                else -> "Released ${p.date.display()}" to p.region.label
            }
        }
    }
    Column(
        Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Text(headline, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
        if (sub.isNotBlank()) Text(sub, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').removePrefix("www.")

fun trimNumber(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
