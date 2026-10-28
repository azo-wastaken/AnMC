package com.anmc.net

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ============================================================
//  ServerScreen.kt - what you see after tapping a server card.
// ============================================================

/** A small coloured label such as "Running" or "Stopped". */
@Composable
fun StatusPill(status: ServerStatus) {
    val color = when (status) {
        ServerStatus.RUNNING -> Color(0xFF2E7D32)
        ServerStatus.STARTING, ServerStatus.INSTALLING, ServerStatus.STOPPING -> Color(0xFFEF6C00)
        ServerStatus.STOPPED -> Color(0xFF757575)
        ServerStatus.NOT_INSTALLED -> Color(0xFFC62828)
    }
    Surface(shape = MaterialTheme.shapes.small, color = color) {
        Text(
            status.label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1
        )
    }
}

/** The three-dot menu. Each entry is a label and what to do when it is tapped. */
@Composable
fun MoreMenu(entries: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Text("⋮", fontSize = 22.sp) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.first) },
                    onClick = { expanded = false; entry.second() }
                )
            }
        }
    }
}

// ---------------- server.properties state (Java and Bedrock) ----------------
class PropsState {
    var token = ""
    var defaults: Map<String, String> = serverPropertyDefaults
    var loaded by mutableStateOf(false)
    var status by mutableStateOf("Open this window to load the server's settings.")
    val values = mutableStateMapOf<String, String>().apply { putAll(serverPropertyDefaults) }
    val present = mutableSetOf<String>()     // settings that were already in the file when it was loaded

    fun beginLoad(newToken: String, newDefaults: Map<String, String>) {
        token = newToken
        defaults = newDefaults
        loaded = false
        status = "Loading server.properties…"
        values.clear()
        values.putAll(newDefaults)
        present.clear()
    }

    fun beginSave(newToken: String) {
        token = newToken
        loaded = false
        status = "Saving changes and creating a backup…"
    }

    fun handle(kind: String, tok: String, payload: String) {
        if (tok != token) return
        when (kind) {
            "PROP" -> {
                val split = payload.indexOf('=')
                if (split > 0) {
                    val key = payload.substring(0, split)
                    if (key in defaults) {
                        values[key] = payload.substring(split + 1)
                        present.add(key)
                    }
                }
            }
            "PROPS_LOADED" -> {
                loaded = true
                status = "Loaded. Saving makes a timestamped backup first. Restart the server to apply most changes."
            }
            "PROP_ERROR" -> {
                loaded = false
                status = payload
            }
            "PROP_SAVED" -> {
                loaded = true
                status = "Saved. Restart the server to apply most changes. A backup was made beside server.properties."
            }
        }
    }
}

// ---------------- the screen ----------------
@Composable
fun ServerScreen(
    c: AppController,
    cfg: ServerConfig,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val status = c.statusOf(cfg.id)
    val info = c.installedInfo[cfg.id]
    val installedType = info?.first ?: ""
    val modsAvailable = cfg.isJava && installedType in setOf("Paper", "Fabric", "Forge")
    val configsAvailable = configAvailableFor(cfg.type)

    var tab by remember(cfg.id) { mutableStateOf("server") }
    val tabs = buildList {
        add("server" to "Server")
        if (modsAvailable) add("mods" to (if (installedType == "Paper") "Plugins" else "Mods"))
        if (configsAvailable) add("configs" to "Config files")
        add("players" to "Players")
        add("backups" to "Backups")
    }
    // A Vanilla server has no mods/plugins tab at all
    val active = when {
        tab == "mods" && !modsAvailable -> "server"
        tab == "configs" && !configsAvailable -> "server"
        else -> tab
    }
    val index = tabs.indexOfFirst { it.first == active }.coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
            Column(Modifier.weight(1f)) {
                Text(cfg.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${cfg.edition} · ${cfg.summary} · ${cfg.ramGb} GB · port ${cfg.port}", style = MaterialTheme.typography.labelMedium)
            }
            StatusPill(status)
            MoreMenu(listOf("Edit" to onEdit, "Delete" to onDelete))
        }

        TabRow(selectedTabIndex = index) {
            tabs.forEach { (key, label) ->
                Tab(
                    selected = key == active,
                    onClick = { tab = key },
                    text = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (active) {
                "mods" -> ModsTab(c, cfg)
                "configs" -> ConfigsTab(c, cfg)
                "players" -> PlayersTab(c, cfg)
                "backups" -> BackupsTab(c, cfg)
                else -> ServerTab(c, cfg)
            }
        }
    }
}

@Composable
private fun ServerTab(c: AppController, cfg: ServerConfig) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val status = c.statusOf(cfg.id)
    val listState = rememberLazyListState()
    var command by remember { mutableStateOf("") }
    var showProps by remember { mutableStateOf(false) }
    var showUnlink by remember { mutableStateOf(false) }
    val claimUrl = c.claimUrl
    val canUseServer = status == ServerStatus.RUNNING || status == ServerStatus.STARTING
    val tunnelKind = if (cfg.isJava) "Minecraft Java (TCP)" else "Minecraft Bedrock (UDP)"

    LaunchedEffect(c.lineTick) {
        if (c.lines.isNotEmpty()) listState.scrollToItem(c.lines.size - 1)
    }

    if (showUnlink) {
        AlertDialog(
            onDismissRequest = { showUnlink = false },
            title = { Text("Unlink Playit?") },
            text = {
                Text("This clears this phone's saved Playit login and stops its local agent. It does not delete the agent or tunnels from your Playit account. Tap Relink to connect again.")
            },
            confirmButton = { TextButton(onClick = { showUnlink = false; c.unlinkPlayit() }) { Text("Unlink") } },
            dismissButton = { TextButton(onClick = { showUnlink = false }) { Text("Cancel") } }
        )
    }
    if (showProps) {
        ServerPropertiesDialog(c = c, cfg = cfg, onDismiss = { showProps = false })
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (c.needsReinstall(cfg) && status != ServerStatus.INSTALLING) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val info = c.installedInfo[cfg.id]
                    Text("Reinstall needed", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Installed: ${info?.first} ${info?.second}. Chosen: ${cfg.summary}. Reinstall to switch (your world is kept).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        enabled = !c.anythingRunning && c.installingId == null,
                        onClick = { c.installServer(cfg) }
                    ) { Text("Reinstall now") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Controls", style = MaterialTheme.typography.titleMedium)
                when (status) {
                    ServerStatus.NOT_INSTALLED -> {
                        Text("This server isn't installed yet.", style = MaterialTheme.typography.bodySmall)
                        Button(
                            enabled = c.installingId == null && !c.anythingRunning,
                            onClick = { c.installServer(cfg) }
                        ) { Text("Install (5 to 20 minutes)") }
                    }
                    ServerStatus.INSTALLING -> {
                        Text(
                            "Step ${c.installStep.coerceAtLeast(1)} of $SETUP_STEPS: ${c.installLabel}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Keep your phone awake and on Wi-Fi.", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = status == ServerStatus.STOPPED,
                                onClick = { c.startServer(cfg) }
                            ) { Text("Start") }
                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = status == ServerStatus.RUNNING || status == ServerStatus.STARTING,
                                onClick = { c.stopServer(cfg) }
                            ) { Text("Stop") }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = status == ServerStatus.RUNNING,
                                onClick = { c.restartServer(cfg) }
                            ) { Text("Restart") }
                        }
                        OutlinedButton(onClick = { showProps = true; c.loadProperties(cfg) }) { Text("Game settings (server.properties)") }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Public address", style = MaterialTheme.typography.titleMedium)
                Text(
                    "On playit.gg, create a $tunnelKind tunnel that points to 127.0.0.1:${cfg.port}, then paste the address it gives you here.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = cfg.address,
                    onValueChange = { c.setAddress(cfg.id, it) },
                    label = { Text("Address friends use") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (c.detectedAddress.isNotEmpty() && c.detectedAddress != cfg.address) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Found: ${c.detectedAddress}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { c.setAddress(cfg.id, c.detectedAddress) }) { Text("Use it") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        enabled = cfg.address.isNotBlank(),
                        onClick = { copyToClipboard(context, "Server address", cfg.address) }
                    ) { Text("Copy") }
                    OutlinedButton(
                        enabled = cfg.address.isNotBlank(),
                        onClick = { shareText(context, "Join my ${cfg.edition} Minecraft server: ${cfg.address}") }
                    ) { Text("Share") }
                    OutlinedButton(onClick = { c.detectAddress() }) { Text("Detect") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Playit tunnel", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = c.installingId == null,
                        onClick = { c.relinkPlayit() }
                    ) { Text("Relink") }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = c.installingId == null,
                        onClick = { showUnlink = true }
                    ) { Text("Unlink") }
                }
                if (claimUrl != null) {
                    Button(onClick = { uriHandler.openUri(claimUrl) }) { Text("Open playit link") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (c.connected) "Console: connected" else "Console: waiting for Termux",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
                Box(Modifier.fillMaxWidth().height(240.dp)) {
                    LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 2.dp)) {
                        items(c.lines) { line ->
                            Text(text = line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("Server command") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        enabled = canUseServer && command.isNotBlank(),
                        onClick = { c.sendCommand(cfg, command); command = "" }
                    ) { Text("Send") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

// ---------------- server.properties windows ----------------
@Composable
private fun ServerPropertiesDialog(c: AppController, cfg: ServerConfig, onDismiss: () -> Unit) {
    if (cfg.isJava) JavaPropertiesDialog(c, cfg, onDismiss) else BedrockPropertiesDialog(c, cfg, onDismiss)
}

@Composable
private fun BedrockPropertiesDialog(c: AppController, cfg: ServerConfig, onDismiss: () -> Unit) {
    val state = c.props
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 760.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bedrock game settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { c.loadProperties(cfg) }) { Text("Reload") }
                }
                Text(
                    "Written to server.properties in this server's folder. Restart the server to apply. " +
                        "The port is changed from Edit server. Only settings that are already in the file, that you change, " +
                        "or that are required (transport) are written.",
                    style = MaterialTheme.typography.bodySmall
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(bedrockChoiceProperties, key = { it.key }) { property ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(property.label, style = MaterialTheme.typography.bodyMedium)
                            if (property.help.isNotEmpty()) {
                                Text(property.help, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val current = state.values[property.key] ?: property.defaultValue
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                property.options.forEach { option ->
                                    val pad = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                    if (current == option) {
                                        Button(
                                            onClick = { state.values[property.key] = option },
                                            modifier = Modifier.weight(1f),
                                            contentPadding = pad
                                        ) { Text(option, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                                    } else {
                                        OutlinedButton(
                                            onClick = { state.values[property.key] = option },
                                            modifier = Modifier.weight(1f),
                                            contentPadding = pad
                                        ) { Text(option, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                                    }
                                }
                            }
                            if (state.loaded && property.key !in state.present) {
                                Text(
                                    "Not in the file yet. Saving adds it.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    items(bedrockTextProperties, key = { it.key }) { property ->
                        OutlinedTextField(
                            value = state.values[property.key] ?: property.defaultValue,
                            onValueChange = { updated ->
                                state.values[property.key] =
                                    if (property.numeric) updated.filter(Char::isDigit) else updated.replace("\n", "").replace("\r", "")
                            },
                            label = { Text(property.label) },
                            supportingText = { Text(property.key) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Text(
                    state.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.status.contains("must be", true) || state.status.contains("not installed", true) ||
                        state.status.contains("failed", true)
                    ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { c.saveProperties(cfg) }, enabled = state.loaded) { Text("Save changes") }
                }
            }
        }
    }
}

@Composable
private fun JavaPropertiesDialog(c: AppController, cfg: ServerConfig, onDismiss: () -> Unit) {
    val state = c.props
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 760.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Game settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { c.loadProperties(cfg) }) { Text("Reload") }
                }
                Text(
                    "Written to server.properties. Most settings need a restart. The port is changed from Edit server.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Turning Online authentication off lets anyone connect with any name; only do it if you understand the risk.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    val groups = listOf("Gameplay", "Access & visibility", "World & entities")
                    groups.forEach { group ->
                        item(key = "header-$group") { Text(group, style = MaterialTheme.typography.titleMedium) }
                        items(booleanServerProperties.filter { it.group == group }, key = { it.key }) { property ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(property.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(property.key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = (state.values[property.key] ?: property.defaultValue.toString()).toBoolean(),
                                    onCheckedChange = { state.values[property.key] = it.toString() }
                                )
                            }
                        }
                    }
                    item(key = "header-values") { Text("Values", style = MaterialTheme.typography.titleMedium) }
                    items(textServerProperties, key = { it.key }) { property ->
                        OutlinedTextField(
                            value = state.values[property.key] ?: property.defaultValue,
                            onValueChange = { updated ->
                                state.values[property.key] =
                                    if (property.numeric) updated.filter(Char::isDigit) else updated.replace("\n", "").replace("\r", "")
                            },
                            label = { Text(property.label) },
                            supportingText = { Text(property.key) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Text(
                    state.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.status.contains("must be", true) || state.status.contains("not installed", true) ||
                        state.status.contains("failed", true)
                    ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { c.saveProperties(cfg) }, enabled = state.loaded) { Text("Save changes") }
                }
            }
        }
    }
}
