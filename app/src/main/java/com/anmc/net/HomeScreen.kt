package com.anmc.net

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ============================================================
//  HomeScreen.kt - the list of servers (Java and Bedrock tabs), the
//  server cards with their three-dot menu, and the create/edit/delete pop-ups.
// ============================================================

@Composable
fun HomeScreen(
    c: AppController,
    darkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
    termuxGranted: Boolean,
    onShowGuide: () -> Unit
) {
    var tab by remember { mutableStateOf(JAVA) }
    var creating by remember { mutableStateOf<ServerConfig?>(null) }
    var editing by remember { mutableStateOf<ServerConfig?>(null) }
    var deleting by remember { mutableStateOf<ServerConfig?>(null) }

    val shown = c.servers.filter { it.edition == tab }
    val javaCount = c.servers.count { it.edition == JAVA }
    val bedrockCount = c.servers.count { it.edition == BEDROCK }

    creating?.let { ServerEditorDialog(c, it, isNew = true, onDismiss = { creating = null }) }
    editing?.let { ServerEditorDialog(c, it, isNew = false, onDismiss = { editing = null }) }
    deleting?.let { DeleteServerDialog(c, it, onDismiss = { deleting = null }) }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(APP_NAME, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onShowGuide) { Text("Guide") }
                IconButton(onClick = { c.refreshStatus() }) { Text("⟳", fontSize = 22.sp) }
                ThemeToggle(darkTheme = darkTheme, onThemeChange = onThemeChange)
            }

            if (!termuxGranted) TermuxPermissionCard(granted = false)

            TabRow(selectedTabIndex = if (tab == JAVA) 0 else 1) {
                Tab(selected = tab == JAVA, onClick = { tab = JAVA }, text = { Text("Java ($javaCount)") })
                Tab(selected = tab == BEDROCK, onClick = { tab = BEDROCK }, text = { Text("Bedrock ($bedrockCount)") })
            }

            if (shown.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("No $tab servers yet", style = MaterialTheme.typography.titleMedium)
                        Text("Tap the + button to create one.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(shown, key = { it.id }) { cfg ->
                        ServerCard(c, cfg, onEdit = { editing = cfg }, onDelete = { deleting = cfg })
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { creating = newServer(tab) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(16.dp)
        ) { Text("+", fontSize = 28.sp) }
    }
}

@Composable
private fun ServerCard(c: AppController, cfg: ServerConfig, onEdit: () -> Unit, onDelete: () -> Unit) {
    val status = c.statusOf(cfg.id)
    val otherBusy = c.runningIds.any { it != cfg.id } || (c.startingId != null && c.startingId != cfg.id)

    Card(Modifier.fillMaxWidth().clickable { c.openServer(cfg.id) }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(cfg.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${cfg.summary} · ${cfg.ramGb} GB · port ${cfg.port}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (cfg.address.isNotBlank()) {
                        Text(cfg.address, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                MoreMenu(listOf("Open" to { c.openServer(cfg.id) }, "Edit" to onEdit, "Delete" to onDelete))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(status)
                if (c.needsReinstall(cfg)) {
                    Text("Reinstall needed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                when (status) {
                    ServerStatus.NOT_INSTALLED -> Button(
                        enabled = c.installingId == null && !c.anythingRunning,
                        onClick = { c.installServer(cfg) }
                    ) { Text("Install") }
                    ServerStatus.STOPPED -> Button(
                        enabled = !otherBusy && c.installingId == null,
                        onClick = { c.startServer(cfg) }
                    ) { Text("Start") }
                    ServerStatus.RUNNING, ServerStatus.STARTING -> OutlinedButton(onClick = { c.stopServer(cfg) }) { Text("Stop") }
                    else -> {}
                }
            }
            if (status == ServerStatus.INSTALLING) {
                Text(
                    "Step ${c.installStep.coerceAtLeast(1)} of $SETUP_STEPS: ${c.installLabel}",
                    style = MaterialTheme.typography.bodySmall
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (status == ServerStatus.STOPPED && otherBusy) {
                Text("Stop the running server to start this one.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------------- create / edit ----------------
@Composable
fun ServerEditorDialog(c: AppController, initial: ServerConfig, isNew: Boolean, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var edition by remember { mutableStateOf(initial.edition) }
    var type by remember { mutableStateOf(initial.type) }
    var version by remember { mutableStateOf(initial.version) }
    var ram by remember { mutableStateOf(initial.ramGb.toString()) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var eula by remember { mutableStateOf(initial.eula) }
    var address by remember { mutableStateOf(initial.address) }
    var installNow by remember { mutableStateOf(true) }
    var reinstall by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    val info = c.installedInfo[initial.id]
    val reinstallNeeded = !isNew && info != null &&
        (info.first != type || (edition == JAVA && info.second.isNotEmpty() && info.second != version.trim()))
    val installingThis = c.installingId == initial.id

    fun pickEdition(e: String) {
        if (e == edition) return
        val oldDefault = defaultPort(edition)
        edition = e
        type = typesFor(e).first()
        version = if (e == JAVA) "1.21.1" else "latest"
        if (port.toIntOrNull() == oldDefault) port = defaultPort(e).toString()
        error = ""
    }

    fun save() {
        val cfg = initial.copy(
            name = name.trim(),
            edition = edition,
            type = type,
            version = if (edition == JAVA) version.trim() else "latest",
            ramGb = ram.toIntOrNull() ?: 0,
            port = port.toIntOrNull() ?: 0,
            eula = eula,
            address = address.trim()
        )
        val problem = validateServer(cfg) ?: if (!cfg.eula) "Accept the Minecraft EULA to continue." else null
        if (problem != null) { error = problem; return }
        if (isNew) c.addServer(cfg, installNow) else c.updateServer(initial, cfg, reinstall = reinstallNeeded && reinstall)
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).heightIn(max = 760.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (isNew) "New server" else "Edit server", style = MaterialTheme.typography.titleLarge)

                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text("Server name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Edition", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = edition == JAVA, onClick = { pickEdition(JAVA) })
                        Text("Java", modifier = Modifier.padding(end = 16.dp))
                        RadioButton(selected = edition == BEDROCK, onClick = { pickEdition(BEDROCK) })
                        Text("Bedrock")
                    }
                    if (edition == BEDROCK) {
                        Text(
                            "Bedrock servers use PowerNukkitX, a Bedrock server written in Java. Mojang's own Bedrock server " +
                                "only runs on PCs, so it can't run on a phone. Some features differ from the official one. " +
                                "Players join from Bedrock Edition (phone, console, Windows) on UDP port ${port.ifBlank { "19132" }}.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Text("Server software", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        typesFor(edition).forEach { t ->
                            RadioButton(selected = type == t, onClick = { type = t })
                            Text(t, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 6.dp), maxLines = 1)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = if (edition == JAVA) version else "latest",
                            onValueChange = { version = it.trim() },
                            label = { Text("Minecraft version") },
                            singleLine = true,
                            enabled = edition == JAVA,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = ram,
                            onValueChange = { ram = it.filter(Char::isDigit).take(2) },
                            label = { Text("RAM (GB)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port (1024-65535)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it.trim() },
                        label = { Text("Public address (optional, from playit)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = eula, onCheckedChange = { eula = it })
                        Text("I accept the Minecraft EULA (minecraft.net/eula)", style = MaterialTheme.typography.bodySmall)
                    }

                    if (isNew) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = installNow, onCheckedChange = { installNow = it })
                            Text("Install it right away (5 to 20 minutes)", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        if (reinstallNeeded) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = reinstall, onCheckedChange = { reinstall = it })
                                Text(
                                    "Software or version changed: reinstall now (your world is kept)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (c.statusOf(initial.id) == ServerStatus.RUNNING) {
                            Text(
                                "This server is running. RAM and port changes apply the next time it starts.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (installingThis) {
                            Text("This server is installing right now. Wait for it to finish before saving.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (error.isNotEmpty()) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(enabled = !installingThis, onClick = { save() }) { Text(if (isNew) "Create" else "Save") }
                }
            }
        }
    }
}

// ---------------- delete ----------------
@Composable
fun DeleteServerDialog(c: AppController, cfg: ServerConfig, onDismiss: () -> Unit) {
    var deleteFiles by remember { mutableStateOf(true) }
    val running = c.statusOf(cfg.id).let { it == ServerStatus.RUNNING || it == ServerStatus.STARTING }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${cfg.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) Text("This server is running. It will be stopped (and the world saved) first.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                    Text("Also delete its world, files and backups from the phone", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    if (deleteFiles) "This can't be undone." else "The files stay in Termux, but the server disappears from this list.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); c.deleteServer(cfg, deleteFiles) }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
