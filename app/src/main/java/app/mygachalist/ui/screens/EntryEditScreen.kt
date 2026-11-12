package app.mygachalist.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mygachalist.MainViewModel
import app.mygachalist.data.*
import app.mygachalist.ui.components.AppTopBar
import app.mygachalist.ui.components.DropdownChip
import app.mygachalist.ui.theme.color
import app.mygachalist.work.Notifier
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val SCORE_LABELS = listOf(
    "No score", "1 · Appalling", "2 · Horrible", "3 · Very bad", "4 · Bad", "5 · Average",
    "6 · Fine", "7 · Good", "8 · Very good", "9 · Great", "10 · Masterpiece",
)

@Composable
fun EntryEditScreen(vm: MainViewModel, gameId: String, onDone: () -> Unit) {
    val gamesById by vm.gamesById.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val game = gamesById[gameId]
    val existing = entries[gameId]
    val context = LocalContext.current

    // Seed the form once from the stored entry (or sensible defaults for a new one).
    val initial = remember(gameId) {
        existing ?: ListEntry(
            gameId = gameId,
            status = if (game != null && game.gameStatus != GameStatus.RELEASED) ListStatus.PLAN_TO_PLAY.key else ListStatus.PLAYING.key,
            region = (settings?.region ?: Region.GLOBAL).key,
        )
    }
    var status by rememberSaveable { mutableStateOf(initial.status) }
    var score by rememberSaveable { mutableIntStateOf(initial.score) }
    var startDate by rememberSaveable { mutableStateOf(initial.startDate) }
    var quitDate by rememberSaveable { mutableStateOf(initial.quitDate) }
    var region by rememberSaveable { mutableStateOf(initial.region) }
    var server by rememberSaveable { mutableStateOf(initial.server) }
    var amount by rememberSaveable { mutableStateOf(if (initial.playAmount > 0) trimNumber(initial.playAmount) else "") }
    var unit by rememberSaveable { mutableStateOf(initial.playUnit) }
    var spent by rememberSaveable { mutableStateOf(if (initial.spent > 0) trimNumber(initial.spent) else "") }
    var currency by rememberSaveable { mutableStateOf(initial.currency) }
    var notes by rememberSaveable { mutableStateOf(initial.notes) }
    var confirmDelete by remember { mutableStateOf(false) }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun save() {
        vm.saveEntry(
            ListEntry(
                gameId = gameId,
                titleSnapshot = game?.title ?: existing?.titleSnapshot.orEmpty(),
                status = status, score = score, startDate = startDate, quitDate = quitDate,
                region = region, server = server.trim(),
                playAmount = amount.replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
                playUnit = unit,
                spent = spent.replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
                currency = currency.trim().uppercase().take(6),
                notes = notes.trim(),
            )
        )
        // Plan to Play is what release alerts are based on, so this is the moment to ask for permission.
        if (status == ListStatus.PLAN_TO_PLAY.key && Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(context)) {
            runCatching { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        onDone()
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        AppTopBar(if (existing == null) "Add to list" else "Edit entry", onDone) {
            IconButton(onClick = ::save) { Icon(Icons.Filled.Check, "Save") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(game?.title ?: existing?.titleSnapshot ?: gameId, fontWeight = FontWeight.Bold, fontSize = 17.sp)

            Label("Status")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ListStatus.entries.forEach { s ->
                    FilterChip(
                        selected = status == s.key, onClick = { status = s.key },
                        label = { Text(s.label, fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = s.color().copy(alpha = 0.28f),
                        ),
                    )
                }
            }

            Label("Score")
            DropdownChip("Score", score, (10 downTo 0).toList(), { SCORE_LABELS[it] }, { score = it ?: 0 }, allowAny = false)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DateField("Start date", startDate, { startDate = it }, Modifier.weight(1f))
                DateField("Quit date", quitDate, { quitDate = it }, Modifier.weight(1f))
            }

            Label("Region and server played")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                DropdownChip("Region", Region.fromKey(region), Region.entries, { it.label }, { region = it?.key.orEmpty() })
                OutlinedTextField(
                    server, { server = it.take(40) }, label = { Text("Server (optional)") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Label("Time played")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    amount, { amount = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(8) },
                    label = { Text("Amount") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                )
                listOf("hours", "days").forEach { u ->
                    FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u.replaceFirstChar { it.uppercase() }) })
                }
            }

            Label("Money spent (optional)")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    spent, { spent = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(10) },
                    label = { Text("Amount") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    currency, { currency = it.take(6) }, label = { Text("Currency") }, placeholder = { Text("USD") },
                    singleLine = true, modifier = Modifier.width(120.dp),
                )
            }

            OutlinedTextField(
                notes, { notes = it.take(2000) }, label = { Text("Notes") }, minLines = 3, maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            if (existing != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove from list", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove from list?") },
            text = { Text("Your score, dates and notes for this game will be deleted.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteEntry(gameId); onDone() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Label(text: String) =
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun DateField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = {}, readOnly = true, singleLine = true,
        label = { Text(label) }, placeholder = { Text("Not set") },
        trailingIcon = {
            if (value.isNotBlank()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Clear, "Clear $label") }
            else IconButton(onClick = { open = true }) { Icon(Icons.Filled.DateRange, "Pick $label") }
        },
        modifier = modifier,
    )
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (parseIsoDate(value) ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}
