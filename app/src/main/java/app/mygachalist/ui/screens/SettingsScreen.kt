package app.mygachalist.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mygachalist.BuildConfig
import app.mygachalist.MainViewModel
import app.mygachalist.data.Region
import app.mygachalist.data.Settings
import app.mygachalist.data.ThemeMode
import app.mygachalist.ui.components.AppTopBar
import app.mygachalist.ui.components.SectionHeader
import app.mygachalist.ui.components.rememberLinkOpener
import app.mygachalist.work.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

private const val MAX_IMPORT_BYTES = 5 * 1024 * 1024

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settingsOrNull by vm.settings.collectAsStateWithLifecycle()
    val settings = settingsOrNull ?: Settings()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openLink = rememberLinkOpener()

    var regionDialog by remember { mutableStateOf(false) }
    var themeDialog by remember { mutableStateOf(false) }
    var adultDialog by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<String?>(null) }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.checkAlerts() else vm.say("Notifications are blocked. Allow them in Android settings to get alerts.")
    }
    fun ensurePermission() {
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(context)) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else if (!Notifier.canNotify(context)) vm.say("Notifications are blocked. Allow them in Android settings to get alerts.")
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching {
                val json = vm.exportJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) }
                }
            }.isSuccess
            vm.say(if (ok) "List exported." else "Could not write the export file.")
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        val bytes = input.readNBytesCompat(MAX_IMPORT_BYTES + 1)
                        require(bytes.size <= MAX_IMPORT_BYTES)
                        bytes.toString(Charsets.UTF_8)
                    }
                }
            }.getOrNull()
            if (text == null) vm.say("Could not read that file.") else pendingImport = text
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar("Settings", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionHeader("General")
            Item("Default region", settings.region.label + " · used for dates, countdowns and alerts") { regionDialog = true }
            Item("Theme", settings.theme.label) { themeDialog = true }

            SectionHeader("Notifications")
            Toggle("Release-day alerts", "When a game on Plan to Play launches", settings.notifyRelease) {
                vm.setNotifyRelease(it); if (it) ensurePermission()
            }
            Toggle("Pre-registration alerts", "When a game on Plan to Play opens pre-registration", settings.notifyPrereg) {
                vm.setNotifyPrereg(it); if (it) ensurePermission()
            }

            SectionHeader("Content")
            Toggle("Show 18+ games", "Adult titles are hidden unless this is on. Their covers stay blurred until tapped.", settings.showAdult) {
                if (it) adultDialog = true else vm.setShowAdult(false)
            }

            SectionHeader("My list backup")
            Item("Export my list", "Save your list as a JSON file") { exporter.launch("mygachalist-${LocalDate.now()}.json") }
            Item("Import my list", "Load a JSON file exported from this app") { importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }

            SectionHeader("Data")
            Item(
                if (refreshing) "Refreshing…" else "Refresh now",
                if (settings.lastRefresh > 0) "Last updated " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(settings.lastRefresh))
                else "Not downloaded yet; showing the list bundled with the app",
            ) { vm.refresh() }
            if (settings.lastError.isNotBlank()) {
                Text(settings.lastError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            }
            Item("Fix or add a game", "Open the project on GitHub to edit data/games.json") { openLink(BuildConfig.REPO_URL) }

            SectionHeader("About")
            Item("MyGachaList ${BuildConfig.VERSION_NAME}", "Release info is community-maintained and can be wrong. Dates marked rumoured or TBA are not confirmed.", null)
        }
    }

    if (regionDialog) {
        ChoiceDialog("Default region", Region.entries, settings.region, { it.label }, { vm.setRegion(it) }) { regionDialog = false }
    }
    if (themeDialog) {
        ChoiceDialog("Theme", ThemeMode.entries, settings.theme, { it.label }, { vm.setTheme(it) }) { themeDialog = false }
    }
    if (adultDialog) {
        AlertDialog(
            onDismissRequest = { adultDialog = false },
            title = { Text("Show 18+ games?") },
            text = { Text("This shows games intended for adults only. Confirm that you are 18 or older.") },
            confirmButton = { TextButton(onClick = { adultDialog = false; vm.setShowAdult(true) }) { Text("I am 18 or older") } },
            dismissButton = { TextButton(onClick = { adultDialog = false }) { Text("Cancel") } },
        )
    }
    pendingImport?.let { text ->
        fun run(replace: Boolean) {
            pendingImport = null
            scope.launch {
                val n = vm.importJson(text, replace)
                vm.say(if (n == null) "That file is not a MyGachaList export." else "Imported $n entries.")
            }
        }
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import list") },
            text = { Text("Merge keeps your current entries and updates the ones in the file. Replace deletes your current list first.") },
            confirmButton = { TextButton(onClick = { run(false) }) { Text("Merge") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
                    TextButton(onClick = { run(true) }) { Text("Replace", color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

@Composable
private fun Item(title: String, subtitle: String, onClick: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(title, fontSize = 15.sp)
        if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 15.sp)
            Text(subtitle, fontSize = 12.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { o ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = o == selected) { onPick(o); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = o == selected, onClick = null)
                        Text(label(o), modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
