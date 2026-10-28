package com.anmc.net

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

// ============================================================
//  ToolsTabs.kt - the Players tab and the Backups tab.
// ============================================================

// ---------------- Players ----------------
private val listRegex = Regex("There are (\\d+)(?: of a max of |/)(\\d+) players online:(.*)$")
private val joinRegex = Regex("\\b([A-Za-z0-9_.]{2,17}) joined the game$")
private val leftRegex = Regex("\\b([A-Za-z0-9_.]{2,17}) left the game$")

class PlayersState {
    val online = mutableStateListOf<String>()
    var maxPlayers by mutableStateOf<Int?>(null)

    fun clear() {
        online.clear()
        maxPlayers = null
    }

    // Reads the server's own messages ("Steve joined the game", the reply to "list", ...)
    fun onConsoleLine(line: String) {
        val list = listRegex.find(line)
        if (list != null) {
            online.clear()
            maxPlayers = list.groupValues[2].toIntOrNull()
            list.groupValues[3].split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { online.add(it) }
            return
        }
        joinRegex.find(line)?.let { m -> if (m.groupValues[1] !in online) online.add(m.groupValues[1]) }
        leftRegex.find(line)?.let { m -> online.remove(m.groupValues[1]) }
    }
}

@Composable
private fun RowScope.ActionButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
    ) { Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
}

@Composable
fun PlayersTab(c: AppController, cfg: ServerConfig) {
    val state = c.players
    val running = c.statusOf(cfg.id) == ServerStatus.RUNNING
    var name by remember { mutableStateOf("") }
    val nameOk = Regex("^[A-Za-z0-9_.]{1,17}$").matches(name)
    val ok = running && nameOk
    fun cmd(text: String) = c.sendCommand(cfg, text)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val max = state.maxPlayers?.let { " / $it" } ?: ""
                    Text(
                        "Players online: ${state.online.size}$max",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(enabled = running, onClick = { cmd("list") }) { Text("Refresh") }
                }
                if (!running) {
                    Text("Start the server to see and manage players.", style = MaterialTheme.typography.bodySmall)
                } else if (state.online.isEmpty()) {
                    Text("Nobody is online (tap Refresh to check).", style = MaterialTheme.typography.bodySmall)
                }
                state.online.forEach { player ->
                    TextButton(onClick = { name = player }) { Text(player) }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Manage a player", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text("Player name (tap a name above to fill it in)") },
                    singleLine = true,
                    isError = name.isNotEmpty() && !nameOk,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionButton("Whitelist add", ok) { cmd("whitelist add $name") }
                    ActionButton("Whitelist remove", ok) { cmd("whitelist remove $name") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionButton("Make operator", ok) { cmd("op $name") }
                    ActionButton("Remove operator", ok) { cmd("deop $name") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionButton("Kick", ok) { cmd("kick $name") }
                    ActionButton("Ban", ok) { cmd("ban $name") }
                    ActionButton("Unban", ok) { cmd("pardon $name") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Server", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionButton("Save world", running) { cmd("save-all") }
                    ActionButton("Whitelist on", running) { cmd("whitelist on") }
                    ActionButton("Whitelist off", running) { cmd("whitelist off") }
                }
                Text(
                    "Results show in the console on the Server tab. Bedrock (PowerNukkitX) servers may word some replies differently.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// ---------------- Backups ----------------
data class BackupItem(val name: String, val sizeKb: Long)

class BackupsState {
    val items = mutableStateListOf<BackupItem>()
    var token by mutableStateOf("")
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)

    fun begin(newToken: String, busy: Boolean = false, message: String? = null) {
        token = newToken
        this.busy = busy
        if (message != null) status = message
    }

    fun handle(kind: String, tok: String, payload: String) {
        if (tok != token) return
        when (kind) {
            "BK_RESET" -> items.clear()
            "BK" -> {
                val p = payload.split("|")
                if (p.isNotEmpty() && validBackupName(p[0])) items.add(BackupItem(p[0], p.getOrNull(1)?.toLongOrNull() ?: 0L))
            }
            "BKOP" -> status = payload
            "BK_DONE" -> busy = false
        }
    }
}

private fun formatSize(kb: Long): String =
    if (kb >= 1024) "%.1f MB".format(kb / 1024.0) else "$kb KB"

@Composable
fun BackupsTab(c: AppController, cfg: ServerConfig) {
    val state = c.backups
    val context = LocalContext.current
    val status = c.statusOf(cfg.id)
    val serverBusy = status == ServerStatus.RUNNING || status == ServerStatus.STARTING || status == ServerStatus.STOPPING
    val installed = status != ServerStatus.NOT_INSTALLED && status != ServerStatus.INSTALLING
    var restoreTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        val t = opToken()
        state.begin(t)
        runInTermux(context, listBackupsCmd(t, cfg.id), true)
    }

    fun act(message: String, script: (String) -> String) {
        val t = opToken()
        state.begin(t, busy = true, message = message)
        runInTermux(context, script(t), true)
    }

    LaunchedEffect(cfg.id) { refresh() }
    LaunchedEffect(state.busy, state.token) {
        if (state.busy) {
            delay(300_000)
            if (state.busy) {
                state.busy = false
                state.status = "No answer from Termux. Check the console on the Server tab."
            }
        }
    }

    if (restoreTarget != null) {
        val name = restoreTarget!!
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("Restore this backup?") },
            text = {
                Text("The current world will be replaced by $name. A copy of the current world is saved first, so you can undo this. The server must be stopped.")
            },
            confirmButton = {
                TextButton(onClick = {
                    restoreTarget = null
                    act("Restoring $name…") { restoreBackupCmd(it, cfg.id, name) }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("Cancel") } }
        )
    }
    if (deleteTarget != null) {
        val name = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete this backup?") },
            text = { Text("$name will be deleted from the phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    act("Deleting $name…") { deleteBackupCmd(it, cfg.id, name) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("World backups", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Backups are kept inside Termux. Use Export to copy one to your phone's Downloads folder " +
                            "(Termux needs storage access once: run termux-setup-storage in Termux).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = installed && !state.busy,
                            onClick = { act("Creating backup… (a few seconds to a minute)") { createBackupCmd(it, cfg.id) } }
                        ) { Text("Back up now") }
                        OutlinedButton(enabled = !state.busy, onClick = { refresh() }) { Text("Refresh") }
                    }
                    if (serverBusy) {
                        Text(
                            "The server is running. Backups are safe (the world is saved first), but you must stop it to restore one.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (state.status.isNotBlank()) Text(state.status, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (state.items.isEmpty()) {
            item { Text("No backups yet.", style = MaterialTheme.typography.bodySmall) }
        }
        items(state.items, key = { it.name }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium)
                    Text(formatSize(item.sizeKb), style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = !state.busy && !serverBusy, onClick = { restoreTarget = item.name }) { Text("Restore") }
                        TextButton(
                            enabled = !state.busy,
                            onClick = { act("Exporting ${item.name}…") { exportBackupCmd(it, cfg.id, item.name) } }
                        ) { Text("Export") }
                        TextButton(enabled = !state.busy, onClick = { deleteTarget = item.name }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

// ---------------- Configs ----------------
class ConfigsState {
    var token by mutableStateOf("")
    val files = mutableStateListOf<ConfigFile>()
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")
    var editingFile by mutableStateOf<ConfigFile?>(null)
    var editingText by mutableStateOf("")
    var editingCrlf by mutableStateOf(false)
    var loadingFile by mutableStateOf(false)
    var savingFile by mutableStateOf(false)

    fun begin(t: String) {
        token = t
        busy = true
    }

    fun handle(kind: String, t: String, payload: String) {
        if (t != token) return
        when (kind) {
            "CFG_RESET" -> { files.clear(); status = "Loading settings files…" }
            "CFGFILE" -> {
                val p = payload.split("|")
                if (p.size >= 2) {
                    val size = p[1].toLongOrNull() ?: 0L
                    files.add(ConfigFile(p[0], size))
                }
            }
            "CFG_LIST_END" -> {
                busy = false
                status = if (files.isEmpty()) "No settings files found yet. Start the server once first so plugins/mods create their config files." else "${files.size} settings file(s)."
            }
            "CFGDATA" -> {
                loadingFile = false
                val decoded = decodeConfig(payload)
                if (decoded != null) {
                    editingText = decoded.text
                    editingCrlf = decoded.crlf
                    status = "File loaded."
                } else {
                    status = "Could not open this file (it may not be UTF-8 plain text)."
                    editingFile = null
                }
            }
            "CFG_SAVED" -> {
                savingFile = false
                busy = false
                status = "Saved changes. A backup of the previous version was kept."
            }
            "CFG_RESTORED" -> {
                savingFile = false
                busy = false
                status = "Restored previous version."
            }
            "CFG_ERR" -> {
                busy = false
                loadingFile = false
                savingFile = false
                status = payload
            }
        }
    }
}

@Composable
fun ConfigsTab(c: AppController, cfg: ServerConfig) {
    val state = c.configs
    var filter by remember { mutableStateOf("") }

    LaunchedEffect(cfg.id) {
        if (state.files.isEmpty() && !state.busy) c.listConfigs(cfg)
    }

    val grouped = remember(state.files, filter) {
        state.files
            .filter { filter.isEmpty() || it.path.contains(filter, ignoreCase = true) }
            .groupBy { configGroup(it.path) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Config files", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            OutlinedButton(enabled = !state.busy, onClick = { c.listConfigs(cfg) }) { Text("Refresh") }
                        }
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            label = { Text("Search configs") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        if (state.status.isNotBlank()) Text(state.status, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            grouped.forEach { (groupName, filesInGroup) ->
                item(key = "header_$groupName") {
                    Text(groupName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
                }
                items(filesInGroup, key = { it.path }) { file ->
                    Card(Modifier.fillMaxWidth().clickable { c.readConfig(cfg, file) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(file.path.substringAfterLast("/"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text("${file.path} · ${file.bytes / 1024} KB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(enabled = !state.busy, onClick = { c.readConfig(cfg, file) }) { Text("Edit") }
                        }
                    }
                }
            }
        }
    }

    state.editingFile?.let { file ->
        ConfigEditorDialog(c = c, cfg = cfg, file = file, onDismiss = { state.editingFile = null })
    }
}

@Composable
fun ConfigEditorDialog(c: AppController, cfg: ServerConfig, file: ConfigFile, onDismiss: () -> Unit) {
    val state = c.configs
    var text by remember(state.editingText) { mutableStateOf(state.editingText) }
    var confirmRestore by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        file.path.substringAfterLast("/"),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        enabled = !state.busy && !state.loadingFile,
                        onClick = { confirmRestore = true }
                    ) { Text("Restore version") }
                }
                Text(file.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                val warning = configWarning(file.path, text)
                if (warning != null) {
                    Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                if (state.loadingFile) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    )
                }

                if (state.status.isNotBlank()) {
                    Text(state.status, style = MaterialTheme.typography.bodySmall)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = !state.busy && !state.loadingFile,
                        onClick = { c.saveConfig(cfg, file, text, state.editingCrlf) }
                    ) { Text("Save changes") }
                }
            }
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore previous version?") },
            text = { Text("This will revert ${file.path} to the previous saved version. Current changes will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    c.restoreConfig(cfg, file)
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text("Cancel") }
            }
        )
    }
}
