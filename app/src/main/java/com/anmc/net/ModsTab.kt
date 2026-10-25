package com.anmc.net

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================
//  ModsTab.kt - find mods (Fabric/Forge) or plugins (Paper) and install
//  them straight into the server's folder.
// ============================================================

class ModsState {
    val installed = androidx.compose.runtime.mutableStateListOf<String>()
    var token by mutableStateOf("")
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var needsRestart by mutableStateOf(false)
    var warnings by mutableStateOf<List<String>>(emptyList())
    private val ok = mutableListOf<String>()
    private val failed = mutableListOf<String>()

    fun begin(newToken: String) {
        token = newToken
        ok.clear()
        failed.clear()
    }

    // Called with the messages that the Termux scripts print
    fun handle(kind: String, tok: String, payload: String) {
        if (tok != token) return
        when (kind) {
            "MODOP" -> {
                val name = payload.substringAfter('|')
                if (payload.startsWith("OK|")) ok.add(name) else failed.add(name)
            }
            "MODS_RESET" -> installed.clear()
            "MOD" -> installed.add(payload)
            "MODS_DONE" -> {
                busy = false
                if (ok.isNotEmpty() || failed.isNotEmpty()) {
                    val parts = mutableListOf<String>()
                    if (ok.isNotEmpty()) {
                        parts.add("Installed ${ok.size}: ${ok.joinToString(", ")}.")
                        needsRestart = true
                    }
                    if (failed.isNotEmpty()) parts.add("Failed (download or checksum): ${failed.joinToString(", ")}.")
                    status = parts.joinToString(" ")
                }
            }
        }
    }
}

private val providerNames = listOf("Modrinth", "CurseForge", "Direct link")

@Composable
fun ModsTab(c: AppController, cfg: ServerConfig) {
    val state = c.mods
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences("anmc_preferences", Context.MODE_PRIVATE) }

    val type = c.installedInfo[cfg.id]?.first ?: cfg.type
    val isPlugins = type == "Paper"
    val dirName = if (isPlugins) "plugins" else "mods"
    val noun = if (isPlugins) "plugin" else "mod"
    val knownVersion = c.installedInfo[cfg.id]?.second?.takeIf { it.isNotEmpty() } ?: cfg.version

    var provider by remember { mutableStateOf("Modrinth") }
    var query by remember { mutableStateOf("") }
    var version by remember(knownVersion) { mutableStateOf(knownVersion) }
    var directUrl by remember { mutableStateOf("") }
    var cfKey by remember { mutableStateOf(prefs.getString("curseforge_key", "") ?: "") }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var removeTarget by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateListOf<ModHit>() }

    fun refreshList() {
        val t = opToken()
        state.begin(t)
        runInTermux(context, listModsCmd(t, cfg.id, dirName), true)
    }

    fun startInstall(resolved: Resolved) {
        val t = opToken()
        state.begin(t)
        state.busy = true
        state.warnings = resolved.warnings
        state.status = "Downloading ${resolved.files.size} file(s) in Termux…"
        runInTermux(context, installModsCmd(t, cfg.id, dirName, resolved.files), true)
    }

    fun search() {
        if (!validMcVersion(version)) { message = "Enter the Minecraft version first (like 1.21.1)."; return }
        if (provider == "CurseForge" && cfKey.isBlank()) { message = "Paste your CurseForge API key first."; return }
        scope.launch {
            searching = true
            message = ""
            try {
                val hits = withContext(Dispatchers.IO) { searchMods(provider, query.trim(), type, version, cfKey.trim()) }
                results.clear()
                results.addAll(hits)
                if (hits.isEmpty()) message = "Nothing found for $type $version. Try another search."
            } catch (e: Exception) {
                message = friendlyError(e)
            }
            searching = false
        }
    }

    fun installHit(hit: ModHit) {
        if (!validMcVersion(version)) { message = "Enter the Minecraft version first (like 1.21.1)."; return }
        scope.launch {
            state.busy = true
            state.status = "Looking up ${hit.title}…"
            try {
                val resolved = withContext(Dispatchers.IO) { resolveMod(hit, type, version, cfKey.trim()) }
                startInstall(resolved)
            } catch (e: Exception) {
                state.busy = false
                state.status = friendlyError(e)
            }
        }
    }

    fun installDirect() {
        try {
            startInstall(Resolved(listOf(directFile(directUrl)), emptyList()))
        } catch (e: Exception) {
            message = friendlyError(e)
        }
    }

    LaunchedEffect(cfg.id) { refreshList() }

    // If Termux never answers, don't stay "busy" forever
    LaunchedEffect(state.busy, state.token) {
        if (state.busy) {
            delay(240_000)
            if (state.busy) {
                state.busy = false
                state.status = "No answer from Termux. Check the console on the Server tab."
            }
        }
    }

    if (removeTarget != null) {
        val name = removeTarget!!
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove $name?") },
            text = { Text("This deletes the file from the server. Restart the server afterwards.") },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget = null
                    val t = opToken()
                    state.begin(t)
                    state.status = "Removed $name."
                    state.needsRestart = true
                    runInTermux(context, removeModCmd(t, cfg.id, dirName, name), true)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } }
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
                    Text("Add ${noun}s", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${noun.replaceFirstChar { it.uppercase() }}s run code on your server. Only install ones you trust.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        providerNames.forEach { name ->
                            val pad = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            if (provider == name) {
                                Button(
                                    onClick = { provider = name; message = "" },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = pad
                                ) { Text(name, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                            } else {
                                OutlinedButton(
                                    onClick = { provider = name; message = "" },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = pad
                                ) { Text(name, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = version,
                        onValueChange = { version = it.trim() },
                        label = { Text("Minecraft version ($type)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (provider == "Direct link") {
                        OutlinedTextField(
                            value = directUrl,
                            onValueChange = { directUrl = it },
                            label = { Text("Link to a .jar file (https)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(enabled = !state.busy && directUrl.isNotBlank(), onClick = { installDirect() }) {
                            Text("Download and install")
                        }
                    } else {
                        if (provider == "CurseForge") {
                            OutlinedTextField(
                                value = cfKey,
                                onValueChange = {
                                    cfKey = it.trim()
                                    prefs.edit().putString("curseforge_key", cfKey).apply()
                                },
                                label = { Text("CurseForge API key") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            TextButton(onClick = { uriHandler.openUri("https://console.curseforge.com/") }) {
                                Text("Get a free key at console.curseforge.com")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text("Search (leave empty for popular)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(enabled = !searching, onClick = { search() }) { Text("Search") }
                        }
                    }
                    if (message.isNotBlank()) {
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (searching || state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (state.status.isNotBlank() || state.warnings.isNotEmpty() || state.needsRestart) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (state.status.isNotBlank()) Text(state.status, style = MaterialTheme.typography.bodyMedium)
                        state.warnings.forEach {
                            Text("Note: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (state.needsRestart) {
                            if (c.statusOf(cfg.id) == ServerStatus.RUNNING) {
                                Text("Restart the server to load the changes.", style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { state.needsRestart = false; c.restartServer(cfg) }) { Text("Restart server") }
                            } else {
                                Text("The changes load the next time you start the server.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        items(results) { hit ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(hit.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "by ${hit.author.ifBlank { "unknown" }} · ${"%,d".format(hit.downloads)} downloads",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(hit.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !state.busy, onClick = { installHit(hit) }) { Text("Install") }
                        TextButton(onClick = { uriHandler.openUri(hit.pageUrl) }) { Text("Open page") }
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Installed in /$dirName (${state.installed.size})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { refreshList() }) { Text("Refresh") }
            }
        }
        if (state.installed.isEmpty()) {
            item { Text("Nothing installed yet.", style = MaterialTheme.typography.bodySmall) }
        }
        items(state.installed) { name ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(enabled = !state.busy, onClick = { removeTarget = name }) { Text("Remove") }
            }
        }
    }
}
