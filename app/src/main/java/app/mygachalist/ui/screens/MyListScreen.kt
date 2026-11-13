package app.mygachalist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.mygachalist.ui.theme.color

private enum class ListSort(val label: String) { TITLE("Title"), SCORE("Score"), UPDATED("Last updated") }

@Composable
fun MyListScreen(vm: MainViewModel, onGame: (String) -> Unit, onEdit: (String) -> Unit, onBrowse: () -> Unit) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val gamesById by vm.gamesById.collectAsStateWithLifecycle()
    val revealed by vm.revealed.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableStateOf(ListSort.TITLE) }
    val status = ListStatus.entries[tab]
    val counts = remember(entries) { entries.values.groupingBy { it.listStatus }.eachCount() }
    val rows = remember(entries, tab, sort) {
        val list = entries.values.filter { it.listStatus == status }
        when (sort) {
            ListSort.TITLE -> list.sortedBy { it.titleSnapshot.lowercase() }
            ListSort.SCORE -> list.sortedWith(compareByDescending<ListEntry> { it.score }.thenBy { it.titleSnapshot.lowercase() })
            ListSort.UPDATED -> list.sortedByDescending { it.updatedAt }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar("My List")
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 4.dp, divider = {}) {
            ListStatus.entries.forEachIndexed { i, s ->
                Tab(
                    selected = tab == i, onClick = { tab = i },
                    text = { Text("${s.label} (${counts[s] ?: 0})", fontSize = 13.sp, maxLines = 1) },
                    modifier = Modifier.height(42.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(status.color()))
        if (rows.isEmpty()) {
            EmptyState(
                Icons.AutoMirrored.Filled.List,
                if (entries.isEmpty()) "Your list is empty" else "Nothing in ${status.label}",
                if (entries.isEmpty()) "Open a game and tap “Add to list” to start tracking it."
                else "Games you mark as ${status.label} show up here.",
                action = "Browse games", onAction = onBrowse,
            )
        } else {
            ChipRow {
                DropdownChip("Sort", sort, ListSort.entries, { it.label }, { sort = it ?: ListSort.TITLE }, allowAny = false)
                Text(if (rows.size == 1) "1 entry" else "${rows.size} entries", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.gameId }, contentType = { "entry" }) { e ->
                    ListRow(e, gamesById[e.gameId], e.gameId in revealed, onOpen = { onGame(e.gameId) }, onEdit = { onEdit(e.gameId) })
                    RowDivider()
                }
            }
        }
    }
}

@Composable
private fun ListRow(entry: ListEntry, game: Game?, revealed: Boolean, onOpen: () -> Unit, onEdit: () -> Unit) {
    Row(
        // A game can vanish from the database (or be hidden by the 18+ setting); the entry stays editable.
        Modifier.fillMaxWidth().clickable(onClick = if (game != null) onOpen else onEdit).height(78.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(entry.listStatus.color()))
        val coverModifier = Modifier.padding(start = 7.dp, top = 6.dp, bottom = 6.dp).size(width = 44.dp, height = 60.dp)
        if (game != null) CoverImage(game, revealed, coverModifier)
        else Box(coverModifier.background(MaterialTheme.colorScheme.surfaceContainerHighest))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 5.dp)) {
            Text(
                game?.title ?: entry.titleSnapshot.ifBlank { entry.gameId },
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 17.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val bits = listOfNotNull(
                Region.fromKey(entry.region)?.label,
                entry.server.takeIf { it.isNotBlank() },
                if (entry.playAmount > 0) "${trimNumber(entry.playAmount)} ${entry.playUnit}" else null,
                if (entry.spent > 0) "${trimNumber(entry.spent)} ${entry.currency}".trim() + " spent" else null,
            )
            if (bits.isNotEmpty()) {
                Text(bits.joinToString(" · "), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val dates = listOfNotNull(
                entry.startDate.takeIf { it.isNotBlank() }?.let { "Started $it" },
                entry.quitDate.takeIf { it.isNotBlank() }?.let { "Quit $it" },
            )
            if (dates.isNotEmpty()) {
                Text(dates.joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (game == null) Text("Not in the current database", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            else if (entry.notes.isNotBlank()) {
                Text(entry.notes, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = Color(0xFFF1B434))
        Text(if (entry.score > 0) entry.score.toString() else "-", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(start = 2.dp).widthIn(min = 16.dp))
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit entry", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
