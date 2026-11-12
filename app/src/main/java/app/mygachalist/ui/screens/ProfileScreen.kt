package app.mygachalist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mygachalist.MainViewModel
import app.mygachalist.data.*
import app.mygachalist.ui.components.*
import app.mygachalist.ui.theme.color

@Composable
fun ProfileScreen(vm: MainViewModel, onSettings: () -> Unit, onBrowse: () -> Unit) {
    val entryMap by vm.entries.collectAsStateWithLifecycle()
    val gamesById by vm.gamesById.collectAsStateWithLifecycle()
    val entries = entryMap.values

    Column(Modifier.fillMaxSize()) {
        AppTopBar("Profile") { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") } }
        if (entries.isEmpty()) {
            EmptyState(
                Icons.Filled.Person, "No statistics yet",
                "Add games to your list and your stats will appear here.",
                action = "Browse games", onAction = onBrowse,
            )
            return@Column
        }
        val counts = remember(entryMap) { entries.groupingBy { it.listStatus }.eachCount() }
        val scored = remember(entryMap) { entries.filter { it.score > 0 } }
        val mean = if (scored.isEmpty()) null else scored.map { it.score }.average()
        val days = remember(entryMap) { entries.sumOf { it.daysPlayed } }
        val spending = remember(entryMap) {
            entries.filter { it.spent > 0 }.groupBy { it.currency.ifBlank { "?" } }.mapValues { (_, v) -> v.sumOf { it.spent } }
        }
        val genres = remember(entryMap, gamesById) {
            entries.flatMap { e -> gamesById[e.gameId]?.genres.orEmpty().map { it to e } }
                .groupBy({ it.first }, { it.second })
                .map { (genre, es) -> Triple(genre, es.size, es.filter { it.score > 0 }.map { it.score }.average().takeIf { !it.isNaN() }) }
                .sortedByDescending { it.second }.take(10)
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionHeader("Game stats")
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Days played", "%.1f".format(days), Modifier.weight(1f))
                StatTile("Mean score", mean?.let { "%.2f".format(it) } ?: "-", Modifier.weight(1f))
                StatTile("Total entries", entries.size.toString(), Modifier.weight(1f))
            }

            // One stacked bar, then a legend with counts.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(18.dp).clip(RoundedCornerShape(4.dp))) {
                ListStatus.entries.forEach { s ->
                    val c = counts[s] ?: 0
                    if (c > 0) Box(Modifier.weight(c.toFloat()).fillMaxHeight().background(s.color()))
                }
            }
            Spacer(Modifier.height(6.dp))
            ListStatus.entries.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(s.color(), RoundedCornerShape(6.dp)))
                    Text(s.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    Text((counts[s] ?: 0).toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            if (spending.isNotEmpty()) {
                SectionHeader("Spending")
                Spacer(Modifier.height(4.dp))
                spending.entries.sortedByDescending { it.value }.forEachIndexed { i, (cur, total) ->
                    InfoRow(if (cur == "?") "No currency" else cur, trimNumber(total), striped = i % 2 == 0)
                }
            }

            SectionHeader("Score distribution")
            Spacer(Modifier.height(6.dp))
            if (scored.isEmpty()) {
                Text("No scored games yet.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
            } else {
                val byScore = scored.groupingBy { it.score }.eachCount()
                val max = byScore.values.max()
                (10 downTo 1).forEach { s ->
                    BarRow(s.toString(), byScore[s] ?: 0, max, "${byScore[s] ?: 0}", MaterialTheme.colorScheme.primary, labelWidth = 24)
                }
            }

            SectionHeader("Genre breakdown")
            Spacer(Modifier.height(6.dp))
            if (genres.isEmpty()) {
                Text("No genre data for your games.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
            } else {
                val max = genres.maxOf { it.second }
                genres.forEach { (genre, count, avg) ->
                    BarRow(genre, count, max, "$count" + (avg?.let { " · ★ %.1f".format(it) } ?: ""), MaterialTheme.colorScheme.secondary, labelWidth = 112)
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp)).padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(label, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Horizontal bar with the value written next to it (so it reads without relying on colour). */
@Composable
private fun BarRow(label: String, value: Int, max: Int, valueText: String, color: Color, labelWidth: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.5.sp, maxLines = 1, modifier = Modifier.width(labelWidth.dp))
        Box(Modifier.weight(1f).height(14.dp)) {
            if (value > 0) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((value.toFloat() / max).coerceIn(0.02f, 1f)).background(color, RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)))
            }
        }
        Text(valueText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
    }
}
