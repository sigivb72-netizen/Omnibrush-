package app.mygachalist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mygachalist.MainViewModel
import app.mygachalist.data.*
import app.mygachalist.ui.components.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class CalMode(val label: String) { SEASON("Season"), MONTH("Month"), TBA("TBA") }

private val MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

/** Shared filter state for the calendar and search screens. */
data class Filters(
    val platform: String? = null,
    val genre: String? = null,
    val status: GameStatus? = null,
) {
    fun matches(g: Game): Boolean =
        (platform == null || g.platforms.any { it.equals(platform, true) }) &&
            (genre == null || g.genres.any { it.equals(genre, true) }) &&
            (status == null || g.gameStatus == status)
}

@Composable
fun FilterChips(games: List<Game>, filters: Filters, onChange: (Filters) -> Unit) {
    val platforms = remember(games) { games.flatMap { it.platforms }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    val genres = remember(games) { games.flatMap { it.genres }.distinct().sorted() }
    DropdownChip("Status", filters.status, GameStatus.entries, { it.label }, { onChange(filters.copy(status = it)) })
    DropdownChip("Platform", filters.platform, platforms, { it }, { onChange(filters.copy(platform = it)) })
    DropdownChip("Genre", filters.genre, genres, { it }, { onChange(filters.copy(genre = it)) })
}

@Composable
fun SeasonalScreen(vm: MainViewModel, onGame: (String) -> Unit) {
    val games by vm.games.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val revealed by vm.revealed.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }

    var mode by rememberSaveable { mutableStateOf(CalMode.SEASON) }
    // Months since year 0; a season is the quarter containing that month.
    var cursor by rememberSaveable { mutableIntStateOf(today.year * 12 + today.monthValue - 1) }
    var regionKey by rememberSaveable { mutableStateOf<String?>("default") }
    var platform by rememberSaveable { mutableStateOf<String?>(null) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var statusKey by rememberSaveable { mutableStateOf<String?>(null) }
    val filters = Filters(platform, genre, statusKey?.let { GameStatus.fromKey(it) })
    // "default" follows the Settings region; null means any region (earliest date wins).
    val region: Region? = if (regionKey == "default") settings?.region ?: Region.GLOBAL else Region.fromKey(regionKey)

    val dated: List<Pair<Game, RegionDate>> = remember(games, region, filters) {
        games.filter { filters.matches(it) }.map { g ->
            g to (if (region != null) g.release(region) else g.earliestRelease() ?: g.release(Region.GLOBAL))
        }
    }

    val ym = YearMonth.of(cursor / 12, cursor % 12 + 1)
    val season = Season.ofMonth(ym.monthValue)

    Column(Modifier.fillMaxSize()) {
        AppTopBar("Seasonal")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            CalMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m, onClick = { mode = m },
                    shape = SegmentedButtonDefaults.itemShape(i, CalMode.entries.size),
                    icon = {},
                ) { Text(m.label, fontSize = 13.sp) }
            }
        }
        if (mode != CalMode.TBA) {
            val step = if (mode == CalMode.SEASON) 3 else 1
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { cursor -= step }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (mode == CalMode.SEASON) "${season.label} ${ym.year}" else ym.format(MONTH_TITLE),
                        fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    )
                    if (mode == CalMode.SEASON) {
                        Text(
                            listOf("Jan – Mar", "Apr – Jun", "Jul – Sep", "Oct – Dec")[season.ordinal],
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { cursor = today.year * 12 + today.monthValue - 1 }) { Text("Now", fontSize = 13.sp) }
                IconButton(onClick = { cursor += step }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
            }
        }
        ChipRow {
            DropdownChip(
                "Region", regionKey ?: "any",
                listOf("default", "any") + Region.entries.map { it.key },
                {
                    when (it) {
                        "default" -> "Default (${(settings?.region ?: Region.GLOBAL).label})"
                        "any" -> "Any"
                        else -> Region.fromKey(it)?.label ?: it
                    }
                },
                { regionKey = if (it == "any") null else it ?: "default" },
                allowAny = false,
            )
            FilterChips(games, filters) { platform = it.platform; genre = it.genre; statusKey = it.status?.key }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // (header, rows) groups for the selected period.
        val groups: List<Pair<String, List<Pair<Game, RegionDate>>>> = remember(dated, mode, cursor) {
            val byStart = compareBy<Pair<Game, RegionDate>>({ it.second.date.start }, { it.first.title })
            when (mode) {
                CalMode.MONTH -> listOf("" to dated.filter { it.second.date.yearMonth == ym }.sortedWith(byStart))
                CalMode.SEASON -> {
                    val inSeason = dated.filter { it.second.date.season == season && it.second.date.year == ym.year }
                    val months = inSeason.filter { it.second.date.yearMonth != null }
                        .groupBy { it.second.date.yearMonth!! }.toSortedMap()
                        .map { (m, rows) -> m.format(MONTH_TITLE) to rows.sortedWith(byStart) }
                    val loose = inSeason.filter { it.second.date.yearMonth == null }.sortedBy { it.first.title }
                    months + if (loose.isEmpty()) emptyList() else listOf("Sometime this season" to loose)
                }
                CalMode.TBA -> {
                    val undated = dated.filter { it.second.date.precision == Precision.YEAR || it.second.date.isTba }
                        .filter { it.second.date.isTba || it.second.date.isUpcoming(today) || it.first.gameStatus != GameStatus.RELEASED }
                    val years = undated.filter { !it.second.date.isTba }.groupBy { it.second.date.year!! }.toSortedMap()
                        .map { (y, rows) -> "$y, date not fixed" to rows.sortedBy { it.first.title } }
                    val tba = undated.filter { it.second.date.isTba && it.first.gameStatus !in setOf(GameStatus.RELEASED, GameStatus.END_OF_SERVICE) }
                        .sortedByDescending { it.first.popularity }
                    years + if (tba.isEmpty()) emptyList() else listOf("To be announced" to tba)
                }
            }.filter { it.second.isNotEmpty() }
        }

        if (groups.isEmpty()) {
            EmptyState(
                Icons.Filled.DateRange,
                if (games.isEmpty()) "No game data yet" else "Nothing here",
                when {
                    games.isEmpty() -> "Open Home and refresh to download the game list."
                    mode == CalMode.TBA -> "No undated games match these filters."
                    else -> "No releases match this period and these filters. Try another region or the TBA tab."
                },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { (header, rows) ->
                    if (header.isNotBlank()) {
                        item(key = "h-$header") {
                            Text(
                                "$header  (${rows.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                        }
                    }
                    items(rows, key = { header + it.first.id }, contentType = { "game" }) { (g, d) ->
                        GameRow(g, entries[g.id], d, g.id in revealed, { onGame(g.id) }, showRegion = region == null)
                        RowDivider()
                    }
                }
            }
        }
    }
}
