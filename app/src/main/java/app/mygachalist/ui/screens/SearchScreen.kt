package app.mygachalist.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mygachalist.MainViewModel
import app.mygachalist.data.*
import app.mygachalist.ui.components.*

private enum class SortBy(val label: String) { POPULARITY("Popularity"), RELEASE("Release date"), TITLE("Title") }

@Composable
fun SearchScreen(vm: MainViewModel, onGame: (String) -> Unit) {
    val games by vm.games.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val revealed by vm.revealed.collectAsStateWithLifecycle()
    val region = settings?.region ?: Region.GLOBAL

    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SortBy.POPULARITY) }
    var descending by rememberSaveable { mutableStateOf(true) }
    var platform by rememberSaveable { mutableStateOf<String?>(null) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var statusKey by rememberSaveable { mutableStateOf<String?>(null) }
    val filters = Filters(platform, genre, statusKey?.let { GameStatus.fromKey(it) })

    val results: List<Pair<Game, RegionDate>> = remember(games, query, sort, descending, filters, region) {
        val q = query.trim().lowercase()
        val rows = games.asSequence()
            .filter { filters.matches(it) }
            .filter { g ->
                q.isEmpty() || g.title.lowercase().contains(q) || g.altTitles.all().any { it.lowercase().contains(q) } ||
                    g.developer.lowercase().contains(q) || g.publisher.lowercase().contains(q) ||
                    g.tags.any { it.lowercase().contains(q) } || g.genres.any { it.lowercase().contains(q) }
            }
            .map { it to it.primaryRelease(region) }.toList()
        when (sort) {
            SortBy.TITLE -> rows.sortedBy { it.first.title.lowercase() }.let { if (descending) it.reversed() else it }
            SortBy.POPULARITY -> rows.sortedBy { it.first.popularity }.let { if (descending) it.reversed() else it }
            SortBy.RELEASE -> {
                // Undated games always go last, whichever direction is chosen.
                val (dated, undated) = rows.partition { it.second.date.start != null }
                val sorted = dated.sortedBy { it.second.date.start }
                (if (descending) sorted.reversed() else sorted) + undated.sortedBy { it.first.title }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar("Search")
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Title, developer, tag…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear") } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        ChipRow {
            DropdownChip("Sort", sort, SortBy.entries, { it.label }, { sort = it ?: SortBy.POPULARITY }, allowAny = false)
            FilterChip(
                selected = false, onClick = { descending = !descending },
                label = { Text(if (descending) "Desc" else "Asc", fontSize = 12.5.sp) },
                leadingIcon = { Icon(if (descending) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp, null, Modifier.size(16.dp)) },
                modifier = Modifier.height(32.dp),
            )
            FilterChips(games, filters) { platform = it.platform; genre = it.genre; statusKey = it.status?.key }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${results.size} of ${games.size} games", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (results.isEmpty()) {
            EmptyState(
                Icons.Filled.Search,
                if (games.isEmpty()) "No game data yet" else "No matches",
                if (games.isEmpty()) "Open Home and refresh to download the game list."
                else "Nothing matches your search and filters. Missing a game? You can add it to games.json on GitHub.",
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.first.id }) { (g, d) ->
                    GameRow(g, entries[g.id], d, g.id in revealed, { onGame(g.id) }, showRegion = d.region != region)
                    RowDivider()
                }
            }
        }
    }
}
