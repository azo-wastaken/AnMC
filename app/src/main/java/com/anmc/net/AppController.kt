package com.anmc.net

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

// ============================================================
//  AppController.kt - the brain of the app. It owns every piece of state
//  (servers, who is running, the console) and reacts to the messages that
//  the Termux scripts print. It contains no UI.
// ============================================================

/** A pop-up message. If actionLabel is set, the pop-up gets an extra button. */
data class Notice(
    val title: String,
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null
)

enum class ServerStatus(val label: String) {
    NOT_INSTALLED("Not installed"),
    INSTALLING("Installing…"),
    STOPPED("Stopped"),
    STARTING("Starting…"),
    RUNNING("Running"),
    STOPPING("Stopping…")
}

private val extraRegex = Regex("^ANMC_X:([A-Z_]+):(\\d+):(.*)$")
private val claimRegex = Regex("https://playit\\.gg/\\S+", RegexOption.IGNORE_CASE)
// Looks for playit addresses such as name.gl.joinmc.link or name.gl.at.ply.gg:12345 (not links with a path)
private val addressRegex =
    Regex("(?<![\\w./-])([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.(?:joinmc\\.link|ply\\.gg)(?::\\d{2,5})?)(?![\\w/-])")

class AppController(private val context: Context) {
    private val prefs = context.getSharedPreferences("anmc_preferences", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    // ----- servers -----
    val servers = mutableStateListOf<ServerConfig>()
    var installedIds by mutableStateOf<Set<String>>(emptySet())
    var runningIds by mutableStateOf<Set<String>>(emptySet())
    val installedInfo = mutableStateMapOf<String, Pair<String, String>>()   // id -> (type, version) found on disk
    var startingId by mutableStateOf<String?>(null)
    var stoppingId by mutableStateOf<String?>(null)
    var installingId by mutableStateOf<String?>(null)
    var installStep by mutableStateOf(0)
    var installLabel by mutableStateOf("")
    var toolsInstalled by mutableStateOf(false)
    var statusChecked by mutableStateOf(false)

    // ----- screens, pop-ups, console -----
    var openServerId by mutableStateOf<String?>(null)
    var notice by mutableStateOf<Notice?>(null)
    val lines = mutableStateListOf<String>()
    var lineTick by mutableStateOf(0)
    var connected by mutableStateOf(false)
    var detectedAddress by mutableStateOf("")

    // ----- per-server helpers (recreated whenever a server is opened) -----
    var mods by mutableStateOf(ModsState())
    var players by mutableStateOf(PlayersState())
    var backups by mutableStateOf(BackupsState())
    var props by mutableStateOf(PropsState())

    // ----- internal bookkeeping -----
    private val issued = mutableSetOf<String>()     // tokens this app session created
    private val handled = mutableSetOf<String>()    // messages already processed (the console replays old lines)
    private var statusToken = ""
    private var installTarget: ServerConfig? = null
    private var startArmed = false                  // true once the script said the server is starting
    private val pInstalled = mutableSetOf<String>()
    private val pRunning = mutableSetOf<String>()
    private var pTools = false

    init {
        servers.addAll(decodeServers(prefs.getString("servers_v1", "") ?: ""))
    }

    // ============ small helpers ============
    private fun issue(): String { val t = opToken(); issued.add(t); return t }
    private fun run(script: String) = runInTermux(context, script, true)
    private fun persist() { prefs.edit().putString("servers_v1", encodeServers(servers.toList())).apply() }

    fun showNotice(title: String, text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        notice = Notice(title, text, actionLabel, action)
    }

    fun addLine(text: String) {
        lines.add(text)
        if (lines.size > 500) lines.removeAt(0)
        lineTick++
    }

    fun server(id: String?): ServerConfig? = servers.firstOrNull { it.id == id }

    fun statusOf(id: String): ServerStatus = when {
        installingId == id -> ServerStatus.INSTALLING
        id !in installedIds -> ServerStatus.NOT_INSTALLED
        stoppingId == id -> ServerStatus.STOPPING
        startingId == id -> ServerStatus.STARTING
        id in runningIds -> ServerStatus.RUNNING
        else -> ServerStatus.STOPPED
    }

    /** True when the installed server no longer matches the type/version chosen in the settings. */
    fun needsReinstall(cfg: ServerConfig): Boolean {
        val info = installedInfo[cfg.id] ?: return false
        return info.first != cfg.type || (cfg.isJava && info.second.isNotEmpty() && info.second != cfg.version)
    }

    val anythingRunning: Boolean get() = runningIds.isNotEmpty() || startingId != null

    val claimUrl: String?
        get() = lines.lastOrNull { claimRegex.containsMatchIn(it) }?.let { claimRegex.find(it)?.value }

    // ============ status ============
    fun refreshStatus() {
        val token = issue()
        statusToken = token
        pInstalled.clear(); pRunning.clear(); pTools = false
        run(checkStatusCmd(token, servers.map { it.id }))
        // If Termux never answers, don't leave the screen waiting forever
        main.postDelayed({
            if (!statusChecked) {
                statusChecked = true
                addLine("No answer from Termux yet. Check the Termux steps on the Welcome screen.")
            }
        }, 8000)
    }

    // ============ opening a server ============
    fun openServer(id: String) {
        mods = ModsState(); players = PlayersState(); backups = BackupsState(); props = PropsState()
        openServerId = id
    }

    fun closeServer() { openServerId = null }

    // ============ create / edit / delete ============
    fun addServer(cfg: ServerConfig, installNow: Boolean) {
        servers.add(cfg)
        persist()
        if (installNow) installServer(cfg)
    }

    fun updateServer(old: ServerConfig, new: ServerConfig, reinstall: Boolean) {
        val i = servers.indexOfFirst { it.id == old.id }
        if (i < 0) return
        servers[i] = new
        persist()
        if (reinstall) {
            installServer(new)
        } else if (new.id in installedIds && (old.ramGb != new.ramGb || old.port != new.port)) {
            val info = installedInfo[new.id]
            run(applySettingsCmd(new, info?.first ?: "", info?.second ?: ""))
            addLine("Saving settings for ${new.name}…")
        }
    }

    fun setAddress(id: String, address: String) {
        val i = servers.indexOfFirst { it.id == id }
        if (i >= 0) { servers[i] = servers[i].copy(address = address.trim()); persist() }
    }

    fun deleteServer(cfg: ServerConfig, deleteFiles: Boolean) {
        if (installingId == cfg.id) {
            showNotice("Installation in progress", "Wait for ${cfg.name} to finish installing before deleting it.")
            return
        }
        run(deleteServerCmd(cfg, issue(), deleteFiles))
        servers.removeAll { it.id == cfg.id }
        persist()
        installedIds = installedIds - cfg.id
        runningIds = runningIds - cfg.id
        installedInfo.remove(cfg.id)
        if (startingId == cfg.id) startingId = null
        if (openServerId == cfg.id) openServerId = null
        if (cfg.id == LEGACY_ID) prefs.edit().putBoolean("legacy_handled", true).apply()
        addLine("Deleted ${cfg.name}" + if (deleteFiles) " and its files." else " (its files were kept on the phone).")
    }

    // ============ install / start / stop ============
    fun installServer(cfg: ServerConfig) {
        if (installingId != null) {
            showNotice("Another installation is running", "Wait for it to finish, then try again."); return
        }
        if (anythingRunning) {
            showNotice("Stop the running server first", "Installing changes Java and other tools, which can break a server that is running."); return
        }
        if (!cfg.eula) {
            showNotice("Accept the EULA", "Edit the server and tick the Minecraft EULA box first."); return
        }
        val token = issue()
        val script = setupScript(cfg, token)
        if (script == null) {
            showNotice("Can't install", validateServer(cfg) ?: "Those settings are not valid."); return
        }
        installTarget = cfg
        installingId = cfg.id
        installStep = 0
        installLabel = "Starting…"
        addLine("Installing ${cfg.name} (${cfg.summary}). This can take 5 to 20 minutes.")
        run(logged(script))
    }

    fun startServer(cfg: ServerConfig) {
        if (installingId != null) {
            showNotice("Installation in progress", "Wait for the installation to finish first."); return
        }
        server(runningIds.firstOrNull { it != cfg.id })?.let {
            showNotice("Another server is running", "Stop \"${it.name}\" first. Only one server can run at a time on a phone."); return
        }
        startingId = cfg.id
        startArmed = false
        addLine("Starting ${cfg.name}…")
        val info = installedInfo[cfg.id]
        run(startAllCmd(cfg, issue(), info?.first ?: "", info?.second ?: ""))
        // Safety net: if the "Done" line never shows up, stop showing "Starting…"
        main.postDelayed({
            if (startingId == cfg.id) startingId = null
        }, 180_000)
    }

    fun restartServer(cfg: ServerConfig) {
        startingId = cfg.id
        startArmed = false
        addLine("Restarting ${cfg.name}…")
        val info = installedInfo[cfg.id]
        run(restartServerCmd(cfg, issue(), info?.first ?: "", info?.second ?: ""))
        main.postDelayed({
            if (startingId == cfg.id) startingId = null
        }, 240_000)
    }

    fun stopServer(cfg: ServerConfig) {
        stoppingId = cfg.id
        addLine("Stopping ${cfg.name} (saving the world)…")
        run(stopAllCmd(cfg, issue()))
    }

    fun sendCommand(cfg: ServerConfig, text: String) {
        addLine("> $text")
        run(sendCommandCmd(cfg, text))
    }

    // ============ playit ============
    fun relinkPlayit() { addLine("Relinking playit. A link will appear below."); run(relinkCmd()) }
    fun unlinkPlayit() { detectedAddress = ""; addLine("Unlinking playit on this phone…"); run(unlinkPlayitCmd()) }
    fun detectAddress() { addLine("Asking playit for your address…"); run(detectAddressCmd()) }

    // ============ server.properties ============
    fun loadProperties(cfg: ServerConfig) {
        val t = opToken()
        val defaults = propertyDefaultsFor(cfg.edition)
        props.beginLoad(t, defaults)
        run(loadPropsCmd(cfg.id, t, defaults.keys))
    }

    fun saveProperties(cfg: ServerConfig) {
        val snapshot = props.values.toMap()
        val error = if (cfg.isJava) validateServerProperties(snapshot) else validateBedrockProperties(snapshot)
        if (error != null) { props.status = error; return }
        val toWrite = if (cfg.isJava) snapshot else bedrockValuesToWrite(snapshot, props.present)
        val t = opToken()
        props.beginSave(t)
        run(savePropsCmd(cfg.id, toWrite, t))
    }

    // ============ the console ============
    suspend fun consoleLoop() {
        withContext(Dispatchers.IO) {
            while (isActive) {
                val s = Socket()
                val handle = coroutineContext[Job]?.invokeOnCompletion { runCatching { s.close() } }
                try {
                    s.connect(InetSocketAddress("127.0.0.1", 8765), 1000)
                    withContext(Dispatchers.Main) { lines.clear(); players.clear(); connected = true }
                    val reader = s.getInputStream().bufferedReader()
                    while (isActive) {
                        val raw = reader.readLine() ?: break
                        val clean = cleanLine(raw)
                        if (clean.isBlank()) continue
                        withContext(Dispatchers.Main) { onLine(clean) }
                    }
                } catch (_: Exception) {
                } finally {
                    handle?.dispose()
                    runCatching { s.close() }
                }
                withContext(Dispatchers.Main) { connected = false }
                delay(2000)
            }
        }
    }

    private fun onLine(clean: String) {
        val x = extraRegex.matchEntire(clean)
        if (x != null) {
            handleExtra(x.groupValues[1], x.groupValues[2], x.groupValues[3])
            lineTick++
            return
        }
        addLine(clean)
        players.onConsoleLine(clean)
        // Java and Bedrock servers both print "Done (...)" when they are ready
        if (startArmed && startingId != null && clean.contains("Done (")) {
            val id = startingId!!
            startingId = null
            startArmed = false
            runningIds = runningIds + id
            val name = server(id)?.name ?: "The server"
            showNotice("$name is running", "Friends can join once your playit tunnel points at this server's port. Check the Address card for the public address.")
        }
        if (clean.startsWith("[playit]")) {
            addressRegex.find(clean)?.let { detectedAddress = it.groupValues[1] }
        }
    }

    private fun handleExtra(kind: String, token: String, payload: String) {
        when (kind) {
            "MODS_RESET", "MOD", "MODS_DONE", "MODOP" -> mods.handle(kind, token, payload)
            "BK_RESET", "BK", "BKOP", "BK_DONE" -> backups.handle(kind, token, payload)
            "PROP", "PROPS_LOADED", "PROP_ERROR", "PROP_SAVED" -> props.handle(kind, token, payload)
            else -> {
                if (token !in issued) return                         // not ours (an old session)
                if (!handled.add("$kind:$token:$payload")) return    // already processed (replayed line)
                lifecycle(kind, token, payload)
            }
        }
    }

    private fun lifecycle(kind: String, token: String, payload: String) {
        when (kind) {
            "STEP" -> {
                val p = payload.split("|", limit = 2)
                installStep = p[0].toIntOrNull() ?: installStep
                installLabel = p.getOrElse(1) { "" }
            }
            "SETUP_DONE" -> if (installingId == payload) {
                val cfg = server(payload) ?: installTarget
                installingId = null
                installedIds = installedIds + payload
                toolsInstalled = true
                installTarget?.let { installedInfo[payload] = it.type to it.version }
                if (cfg != null) {
                    showNotice("Installation complete", "${cfg.name} is ready.", "Start now") { startServer(cfg) }
                }
            }
            "SETUP_FAILED" -> if (installingId == payload) {
                installingId = null
                showNotice("Installation failed", "Something went wrong. Open the server and check the console for the last error, then try again.")
            }
            // ----- answers to a status check -----
            "TOOLS" -> if (token == statusToken) pTools = true
            "INST" -> if (token == statusToken) pInstalled.add(payload)
            "INFO" -> if (token == statusToken) {
                val p = payload.split("|")
                if (p.size >= 3 && validId(p[0])) installedInfo[p[0]] = p[1] to p[2]
            }
            "LEGACY" -> if (token == statusToken) importLegacy(payload)
            "STATUS_DONE" -> if (token == statusToken) {
                installedIds = pInstalled.toSet()
                runningIds = pRunning.toSet() + listOfNotNull(startingId)
                toolsInstalled = pTools
                statusChecked = true
            }
            // ----- answers to start / stop -----
            "RUN" -> if (token == statusToken) pRunning.add(payload) else {
                runningIds = runningIds + payload
                if (startingId == payload && !startArmed) startingId = null     // it was already running
            }
            "STARTING" -> { startingId = payload; startArmed = true; runningIds = runningIds + payload }
            "BUSY" -> {
                if (startingId == payload) startingId = null
                showNotice("Another server is running", "Stop it first. Only one server can run at a time on a phone.")
            }
            "NEEDS_INSTALL" -> {
                if (startingId == payload) startingId = null
                installedIds = installedIds - payload
                val cfg = server(payload)
                if (cfg != null) showNotice("Needs installing", "${cfg.name} (or its tools) is not installed yet.", "Install") { installServer(cfg) }
            }
            "STOPPED" -> {
                runningIds = runningIds - payload
                if (startingId == payload) startingId = null
                if (stoppingId == payload) {
                    stoppingId = null
                    showNotice("Server stopped", "${server(payload)?.name ?: "The server"} and playit have stopped.")
                }
                players.clear()
            }
        }
    }

    // A server made by an older version of the app (in ~/server) becomes a normal card
    private fun importLegacy(payload: String) {
        if (prefs.getBoolean("legacy_handled", false) || servers.any { it.id == LEGACY_ID }) return
        val p = payload.split("|")
        val type = p.getOrNull(0).orEmpty().let { if (it in JAVA_TYPES) it else "Paper" }
        val version = p.getOrNull(1).orEmpty().let { if (javaPackageFor(it) != null) it else "1.21.1" }
        servers.add(ServerConfig(LEGACY_ID, "My server", JAVA, type, version, 2, 25565, true))
        persist()
        prefs.edit().putBoolean("legacy_handled", true).apply()
        showNotice("Found your existing server", "The server you made with an earlier version of this app was added as \"My server\". You can rename or edit it from its menu.")
        main.post { refreshStatus() }
    }
}

// ============================================================
//  Talking to Termux and the phone
// ============================================================

fun runInTermux(context: Context, script: String, background: Boolean) {
    try {
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "$PFX/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    } catch (e: Exception) {
        android.util.Log.e("anmc", "Termux failed", e)
        Toast.makeText(
            context,
            "Couldn't reach Termux. Check Termux access and the 'Run commands in Termux' permission.",
            Toast.LENGTH_LONG
        ).show()
    }
}

fun copyToClipboard(context: Context, label: String, text: String) {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't copy.", Toast.LENGTH_SHORT).show()
    }
}

fun shareText(context: Context, text: String) {
    try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't open the share menu.", Toast.LENGTH_SHORT).show()
    }
}
