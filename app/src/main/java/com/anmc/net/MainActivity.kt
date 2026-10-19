package com.anmc.net

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder

// ---------- Termux paths ----------
const val HOME = "/data/data/com.termux/files/home"
const val PFX = "/data/data/com.termux/files/usr"
const val LOG = "$HOME/anmc/console.log"
const val SOCK = "$HOME/anmc/playit.sock"
const val PB = "$HOME/playit-bin"
// playit can't find DNS settings on Android, so it runs under proot with Termux's resolv.conf
const val PROOT = "$PFX/bin/proot -b $PFX/etc/resolv.conf:/etc/resolv.conf"
const val ENV = "export HOME=$HOME PREFIX=$PFX PATH=$PFX/bin:\$PATH; "

// Streams the log file to the app on localhost:8765. Extra copies just exit if the port is taken.
const val BRIDGE =
    "setsid nohup socat TCP-LISTEN:8765,bind=127.0.0.1,reuseaddr,fork " +
            "EXEC:\"tail -n 300 -F $LOG\" >/dev/null 2>&1 &"

// The console stream needs socat, so make sure it exists even on a brand-new Termux
const val ENSURE_SOCAT =
    "export DEBIAN_FRONTEND=noninteractive; command -v socat >/dev/null 2>&1 || pkg install -y socat >/dev/null 2>&1 || true"

private const val PLAYIT_TAG = "sed -u 's/^/[playit] /'"
private const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"

// Termux blocks RUN_COMMAND while allow-external-apps is false.
// This copies the setup command and opens Termux so the user can run it once.
private const val TERMUX_ENABLE_EXTERNAL_APPS_COMMAND =
    "mkdir -p ~/.termux && touch ~/.termux/termux.properties && " +
            "if grep -Eq '^[[:space:]]*#?[[:space:]]*allow-external-apps[[:space:]]*=' ~/.termux/termux.properties; then " +
            "sed -i -E 's/^[[:space:]]*#?[[:space:]]*allow-external-apps[[:space:]]*=.*/allow-external-apps = true/' ~/.termux/termux.properties; " +
            "else printf '\\nallow-external-apps = true\\n' >> ~/.termux/termux.properties; fi && " +
            "termux-reload-settings"

fun hasTermuxPermission(context: Context): Boolean =
    context.checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED

fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(context, "Open Android Settings > Apps > PocketHost manually.", Toast.LENGTH_LONG).show()
    }
}

fun copyText(context: Context, label: String, text: String) {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Could not copy.", Toast.LENGTH_SHORT).show()
    }
}

fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share server address").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun openTermuxAccessSetup(context: Context) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Termux access setup", TERMUX_ENABLE_EXTERNAL_APPS_COMMAND))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not copy the Termux setup command.", Toast.LENGTH_LONG).show()
        return
    }
    Toast.makeText(
        context,
        "Setup command copied. In Termux, paste it and press Enter, then return to PocketHost.",
        Toast.LENGTH_LONG
    ).show()
    try {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .setClassName("com.termux", "com.termux.app.TermuxActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(context, "Open Termux manually and paste the copied command.", Toast.LENGTH_LONG).show()
    }
}

// ---------- Theme ----------
@Composable
fun PocketHostTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colors = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF7BD88F),
            onPrimary = Color(0xFF003919),
            primaryContainer = Color(0xFF1F4D2B),
            onPrimaryContainer = Color(0xFFB8F2C4),
            secondary = Color(0xFF9CCFA8),
            secondaryContainer = Color(0xFF2D4A37),
            onSecondaryContainer = Color(0xFFCBEBD3),
            tertiary = Color(0xFF7FD1E0),
            background = Color(0xFF0E1511),
            onBackground = Color(0xFFE0E6E1),
            surface = Color(0xFF141B17),
            onSurface = Color(0xFFE0E6E1),
            surfaceVariant = Color(0xFF212C26),
            onSurfaceVariant = Color(0xFFBFCBC2),
            outline = Color(0xFF55655B)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF2E7D32),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFB8F2C4),
            onPrimaryContainer = Color(0xFF00210B),
            secondary = Color(0xFF4E6B55),
            secondaryContainer = Color(0xFFD3E8D8),
            onSecondaryContainer = Color(0xFF0F2016),
            tertiary = Color(0xFF006874),
            background = Color(0xFFF4F9F4),
            onBackground = Color(0xFF181D19),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF181D19),
            surfaceVariant = Color(0xFFE3ECE4),
            onSurfaceVariant = Color(0xFF414B44),
            outline = Color(0xFF717C73)
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("anmc_preferences", Context.MODE_PRIVATE)
        setContent {
            var darkTheme by rememberSaveable { mutableStateOf(prefs.getBoolean("dark_theme_v1", true)) }
            PocketHostTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ControllerScreen(
                        darkTheme = darkTheme,
                        onThemeChange = { enabled ->
                            darkTheme = enabled
                            prefs.edit().putBoolean("dark_theme_v1", enabled).apply()
                        }
                    )
                }
            }
        }
    }
}

// ---------- Parsing ----------
private val markerRegex = Regex(
    "^(POCKETHOST_NOT_INSTALLED|SETUP_DONE|SETUP_FAILED|SERVER_PRESENT|SERVER_INSTALLED|" +
            "SERVER_NOT_INSTALLED|SERVER_RUNNING|SERVER_STOPPED|SERVER_START_REQUESTED):(\\d+)$"
)
// Generic status lines from helper scripts: POCKETHOST_OP:<token>:<KIND>:<payload>
private val opRegex = Regex("^POCKETHOST_OP:(\\d+):([A-Z_]+):(.*)$")
private val claimRegex = Regex("https://playit\\.gg/\\S+", RegexOption.IGNORE_CASE)
private val publicAddressRegex =
    Regex("(?i)\\b((?:[a-z0-9-]+\\.)+(?:ply\\.gg|joinmc\\.link|playit\\.plus))(?::(\\d{2,5}))?")
private val listRegex = Regex("There are (\\d+)(?: of a max of |/)(\\d+) players online:?\\s*(.*)$")
private val joinLeaveRegex = Regex(":\\s(\\S+) (joined|left) the game$")
private val playerNameRegex = Regex("^[A-Za-z0-9_.]{1,20}$")
private val backupNameRegex = Regex("^[A-Za-z0-9._-]+\\.tar\\.gz$")

// Best effort: playit prints the tunnel address in its log. If the format changes, users can paste it manually.
private fun detectPublicAddress(line: String): String? {
    val cleaned = line.replace(Regex("https?://\\S+"), " ")
    return publicAddressRegex.find(cleaned)?.value?.trim()
}

// ---------- server.properties settings shown in the configuration window ----------
private data class BooleanServerProperty(
    val group: String,
    val label: String,
    val key: String,
    val defaultValue: Boolean
)

private data class TextServerProperty(
    val label: String,
    val key: String,
    val defaultValue: String,
    val numeric: Boolean = false
)

private val booleanServerProperties = listOf(
    BooleanServerProperty("Gameplay", "Player vs player", "pvp", true),
    BooleanServerProperty("Gameplay", "Allow Nether", "allow-nether", true),
    BooleanServerProperty("Gameplay", "Enable command blocks", "enable-command-block", false),
    BooleanServerProperty("Gameplay", "Force gamemode", "force-gamemode", false),
    BooleanServerProperty("Gameplay", "Hardcore mode", "hardcore", false),
    BooleanServerProperty("Gameplay", "Allow flight", "allow-flight", false),

    BooleanServerProperty("Access & visibility", "Online authentication", "online-mode", true),
    BooleanServerProperty("Access & visibility", "Whitelist enabled", "white-list", false),
    BooleanServerProperty("Access & visibility", "Enforce whitelist", "enforce-whitelist", false),
    BooleanServerProperty("Access & visibility", "Advertise server status", "enable-status", true),
    BooleanServerProperty("Access & visibility", "Hide online player list", "hide-online-players", false),
    BooleanServerProperty("Access & visibility", "Enable query protocol", "enable-query", false),
    BooleanServerProperty("Access & visibility", "Prevent proxy connections", "prevent-proxy-connections", false),
    BooleanServerProperty("Access & visibility", "Enforce secure profiles", "enforce-secure-profile", true),

    BooleanServerProperty("World & entities", "Spawn animals", "spawn-animals", true),
    BooleanServerProperty("World & entities", "Spawn monsters", "spawn-monsters", true),
    BooleanServerProperty("World & entities", "Spawn villagers and other NPCs", "spawn-npcs", true),
    BooleanServerProperty("World & entities", "Generate structures", "generate-structures", true),
    BooleanServerProperty("World & entities", "Synchronize chunk writes", "sync-chunk-writes", true),
    BooleanServerProperty("World & entities", "Broadcast console to operators", "broadcast-console-to-ops", true),
    BooleanServerProperty("World & entities", "Use native transport", "use-native-transport", true)
)

private val textServerProperties = listOf(
    TextServerProperty("Message of the day (MOTD)", "motd", "A Minecraft Server"),
    TextServerProperty("Default game mode", "gamemode", "survival"),
    TextServerProperty("Difficulty", "difficulty", "easy"),
    TextServerProperty("Maximum players", "max-players", "20", numeric = true),
    TextServerProperty("Server port", "server-port", "25565", numeric = true),
    TextServerProperty("View distance", "view-distance", "10", numeric = true),
    TextServerProperty("Simulation distance", "simulation-distance", "10", numeric = true),
    TextServerProperty("Spawn protection radius", "spawn-protection", "16", numeric = true),
    TextServerProperty("Maximum world size", "max-world-size", "29999984", numeric = true)
)

private val serverPropertyDefaults: Map<String, String> =
    booleanServerProperties.associate { it.key to it.defaultValue.toString() } +
            textServerProperties.associate { it.key to it.defaultValue }

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private fun loadServerPropertiesCommand(token: String): String {
    val keyPatterns = serverPropertyDefaults.keys.joinToString("|")
    val script = listOf(
        "if [ ! -f \"$HOME/server/start.sh\" ]; then echo 'POCKETHOST_PROP_ERROR:$token:Server is not installed yet.'; exit 0; fi",
        "file=\"$HOME/server/server.properties\"",
        "if [ -f \"\$file\" ]; then",
        "  while IFS= read -r line || [ -n \"\$line\" ]; do",
        "    case \"\$line\" in ''|'#'*) continue ;; esac",
        "    case \"\$line\" in *=*) key=\"\${line%%=*}\"; value=\"\${line#*=}\" ;; *) continue ;; esac",
        "    case \"\$key\" in $keyPatterns) printf 'POCKETHOST_PROP:$token:%s=%s\\n' \"\$key\" \"\$value\" ;; esac",
        "  done < \"\$file\"",
        "fi",
        "echo 'POCKETHOST_PROPS_LOADED:$token'"
    ).joinToString("\n")
    return logged(script)
}

private fun saveServerPropertiesCommand(values: Map<String, String>, token: String): String {
    val script = mutableListOf(
        "set -e",
        "trap 'echo \"POCKETHOST_PROP_ERROR:$token:Saving failed; check console log.\"' ERR",
        "if [ ! -f \"$HOME/server/start.sh\" ]; then echo 'POCKETHOST_PROP_ERROR:$token:Server is not installed yet.'; exit 0; fi",
        "file=\"$HOME/server/server.properties\"",
        "[ -f \"\$file\" ] || : > \"\$file\"",
        "cp \"\$file\" \"\$file.bak.\$(date +%Y%m%d-%H%M%S)\"",
        "set_prop() {",
        "  key=\"\$1\"; value=\"\$2\"; tmp=\"\$file.tmp\"",
        // ENVIRON keeps backslashes (like \u00a7 in a MOTD) exactly as typed
        "  PK=\"\$key\" PV=\"\$value\" awk 'BEGIN { k=ENVIRON[\"PK\"]; v=ENVIRON[\"PV\"]; found=0 } /^[[:space:]]*#/ { print; next } { p=index(\$0, \"=\"); if (p > 0 && substr(\$0, 1, p-1) == k) { if (!found) print k \"=\" v; found=1; next } print } END { if (!found) print k \"=\" v }' \"\$file\" > \"\$tmp\"",
        "  mv \"\$tmp\" \"\$file\"",
        "}"
    )
    for (key in serverPropertyDefaults.keys) {
        val rawValue = (values[key] ?: serverPropertyDefaults.getValue(key))
            .replace("\r", " ").replace("\n", " ")
        script.add("set_prop ${shellQuote(key)} ${shellQuote(rawValue)}")
    }
    script.add("echo 'POCKETHOST_PROP_SAVED:$token'")
    return logged(script.joinToString("\n"))
}

private fun validateServerProperties(values: Map<String, String>): String? {
    for (property in booleanServerProperties) {
        if (values[property.key] !in listOf("true", "false")) {
            return "${property.label} must be on or off."
        }
    }
    val mode = values["gamemode"].orEmpty().lowercase()
    if (mode !in setOf("survival", "creative", "adventure", "spectator", "0", "1", "2", "3")) {
        return "Game mode must be survival, creative, adventure, or spectator."
    }
    val difficulty = values["difficulty"].orEmpty().lowercase()
    if (difficulty !in setOf("peaceful", "easy", "normal", "hard", "0", "1", "2", "3")) {
        return "Difficulty must be peaceful, easy, normal, or hard."
    }
    fun integer(key: String, label: String, min: Int, max: Int): String? {
        val value = values[key]?.toIntOrNull() ?: return "$label must be a number."
        return if (value !in min..max) "$label must be between $min and $max." else null
    }
    integer("max-players", "Maximum players", 1, 100000)?.let { return it }
    integer("server-port", "Server port", 1, 65535)?.let { return it }
    integer("view-distance", "View distance", 2, 32)?.let { return it }
    integer("simulation-distance", "Simulation distance", 2, 32)?.let { return it }
    integer("spawn-protection", "Spawn protection radius", 0, 65535)?.let { return it }
    integer("max-world-size", "Maximum world size", 1, 29999984)?.let { return it }
    val motd = values["motd"].orEmpty()
    if ('\n' in motd || '\r' in motd) return "MOTD must be a single line."
    if (motd.length > 256) return "MOTD must be 256 characters or fewer."
    return null
}

// ---------- UI state holders ----------
private data class BackupItem(val name: String, val sizeKb: Long)
private data class InstalledFile(val name: String, val sizeKb: Long)
private data class ModResult(
    val id: String,
    val title: String,
    val summary: String,
    val downloads: Long,
    val author: String
)
private data class DownloadFile(val name: String, val url: String, val hashAlgo: String, val hash: String)

private class BackupUiState {
    val items = mutableStateListOf<BackupItem>()
    var busy by mutableStateOf(false)
    var status by mutableStateOf("Backups are stored inside Termux. Tap Refresh to load them.")
}

private class PlayersUiState {
    val players = mutableStateListOf<String>()
    var max by mutableStateOf("?")
}

private class ModsUiState {
    var provider by mutableStateOf("Modrinth")
    var query by mutableStateOf("")
    var directUrl by mutableStateOf("")
    var directName by mutableStateOf("")
    var cfKey by mutableStateOf("")
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    val results = mutableStateListOf<ModResult>()
    val installed = mutableStateListOf<InstalledFile>()
}

// ---------- Mod providers (Modrinth, CurseForge, direct links) ----------
private object ModService {
    private const val UA = "anmc-app/1.0 (Pocket Server Hosting)"

    fun dirFor(type: String) = if (type == "Paper") "plugins" else "mods"

    private fun loadersFor(type: String): List<String> = when (type) {
        "Paper" -> listOf("paper", "spigot", "bukkit")
        "Fabric" -> listOf("fabric")
        "Forge" -> listOf("forge")
        else -> emptyList()
    }

    // CurseForge: Bukkit plugins are class 5, mods are class 6. Loader ids: Forge 1, Fabric 4.
    private fun curseClass(type: String): Pair<Int, Int> = when (type) {
        "Paper" -> 5 to 0
        "Fabric" -> 6 to 4
        else -> 6 to 1
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun validMc(mc: String) = Regex("^[0-9]+\\.[0-9]+(\\.[0-9]+)?$").matches(mc)
    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", UA)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // Returns a safe file name ending in .jar, or null
    fun safeFileName(raw: String): String? {
        val n = raw.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._+\\- ]"), "_").trim()
        return if (n.length > 4 && n.endsWith(".jar", ignoreCase = true) && !n.startsWith(".")) n else null
    }

    private fun cleanHash(h: String) = if (Regex("^[0-9a-fA-F]{40,128}$").matches(h)) h.lowercase() else ""

    // ----- Modrinth -----
    fun modrinthSearch(query: String, type: String, mc: String): List<ModResult> {
        val loaders = loadersFor(type).joinToString(",") { "\"categories:$it\"" }
        val facets = StringBuilder("[[$loaders],[\"project_type:mod\"],[\"server_side:required\",\"server_side:optional\"]")
        if (validMc(mc)) facets.append(",[\"versions:$mc\"]")
        facets.append("]")
        val index = if (query.isBlank()) "downloads" else "relevance"
        val body = httpGet(
            "https://api.modrinth.com/v2/search?query=${enc(query)}&limit=20&index=$index&facets=${enc(facets.toString())}"
        )
        val hits = JSONObject(body).getJSONArray("hits")
        return (0 until hits.length()).map {
            val h = hits.getJSONObject(it)
            ModResult(h.str("project_id"), h.str("title"), h.str("description"), h.optLong("downloads"), h.str("author"))
        }
    }

    private fun modrinthVersionFor(projectId: String, type: String, mc: String): JSONObject? {
        val loaderJson = loadersFor(type).joinToString(",", "[", "]") { "\"$it\"" }
        var url = "https://api.modrinth.com/v2/project/${enc(projectId)}/version?loaders=${enc(loaderJson)}"
        if (validMc(mc)) url += "&game_versions=${enc("[\"$mc\"]")}"
        val arr = JSONArray(httpGet(url))
        return if (arr.length() > 0) arr.getJSONObject(0) else null
    }

    private fun collectModrinth(
        version: JSONObject, type: String, mc: String, depth: Int,
        visited: MutableSet<String>, out: MutableList<DownloadFile>
    ) {
        val files = version.getJSONArray("files")
        var file = files.getJSONObject(0)
        for (i in 0 until files.length()) {
            if (files.getJSONObject(i).optBoolean("primary")) { file = files.getJSONObject(i); break }
        }
        val name = safeFileName(file.str("filename")) ?: throw IOException("Unsafe or non-jar file name from provider.")
        val url = file.str("url")
        if (!url.startsWith("https://")) throw IOException("Provider returned a non-HTTPS download link.")
        val hash = cleanHash(file.optJSONObject("hashes")?.str("sha512").orEmpty())
        if (out.none { it.name == name }) out.add(DownloadFile(name, url, "sha512", hash))
        if (depth >= 3) return
        val deps = version.optJSONArray("dependencies") ?: return
        for (i in 0 until deps.length()) {
            val d = deps.getJSONObject(i)
            if (d.str("dependency_type") != "required") continue
            val pid = d.str("project_id")
            if (pid.isBlank() || !visited.add(pid)) continue
            val vid = d.str("version_id")
            val depVersion = if (vid.isNotBlank()) {
                JSONObject(httpGet("https://api.modrinth.com/v2/version/${enc(vid)}"))
            } else modrinthVersionFor(pid, type, mc)
            if (depVersion != null) collectModrinth(depVersion, type, mc, depth + 1, visited, out)
        }
    }

    fun modrinthResolve(projectId: String, type: String, mc: String): List<DownloadFile> {
        val version = modrinthVersionFor(projectId, type, mc)
            ?: throw IOException("No ${type} build of this project for Minecraft $mc.")
        val out = mutableListOf<DownloadFile>()
        collectModrinth(version, type, mc, 0, mutableSetOf(projectId), out)
        return out
    }

    // ----- CurseForge (needs the user's own API key from console.curseforge.com) -----
    fun curseSearch(key: String, query: String, type: String, mc: String): List<ModResult> {
        val (classId, loader) = curseClass(type)
        var url = "https://api.curseforge.com/v1/mods/search?gameId=432&classId=$classId&pageSize=20" +
                "&sortField=2&sortOrder=desc&searchFilter=${enc(query)}"
        if (validMc(mc)) url += "&gameVersion=$mc"
        if (loader > 0) url += "&modLoaderType=$loader"
        val data = JSONObject(httpGet(url, mapOf("x-api-key" to key, "Accept" to "application/json"))).getJSONArray("data")
        return (0 until data.length()).map {
            val m = data.getJSONObject(it)
            val author = m.optJSONArray("authors")?.optJSONObject(0)?.str("name").orEmpty()
            ModResult(m.optLong("id").toString(), m.str("name"), m.str("summary"), m.optLong("downloadCount"), author)
        }
    }

    fun curseResolve(key: String, modId: String, type: String, mc: String): List<DownloadFile> {
        val (_, loader) = curseClass(type)
        var url = "https://api.curseforge.com/v1/mods/${enc(modId)}/files?pageSize=1"
        if (validMc(mc)) url += "&gameVersion=$mc"
        if (loader > 0) url += "&modLoaderType=$loader"
        val data = JSONObject(httpGet(url, mapOf("x-api-key" to key, "Accept" to "application/json"))).getJSONArray("data")
        if (data.length() == 0) throw IOException("No ${type} build of this project for Minecraft $mc.")
        val f = data.getJSONObject(0)
        val dl = f.str("downloadUrl")
        if (dl.isBlank()) throw IOException("The author disabled third-party downloads. Get it from curseforge.com instead.")
        if (!dl.startsWith("https://")) throw IOException("Provider returned a non-HTTPS download link.")
        val name = safeFileName(f.str("fileName")) ?: throw IOException("Unsafe or non-jar file name from provider.")
        var sha1 = ""
        val hashes = f.optJSONArray("hashes")
        if (hashes != null) for (i in 0 until hashes.length()) {
            val h = hashes.getJSONObject(i)
            if (h.optInt("algo") == 1) sha1 = cleanHash(h.str("value"))
        }
        return listOf(DownloadFile(name, dl, "sha1", sha1))
    }
}

// ---------- Screens ----------

/** First-launch guide. Completion is saved so it only shows once (it can be reopened from the header). */
@Composable
fun ControllerScreen(darkTheme: Boolean, onThemeChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("anmc_preferences", Context.MODE_PRIVATE)
    }
    var showWelcome by remember {
        mutableStateOf(!preferences.getBoolean("welcome_instructions_completed_v1", false))
    }

    if (showWelcome) {
        WelcomeInstructionsScreen(
            onGetStarted = {
                preferences.edit().putBoolean("welcome_instructions_completed_v1", true).apply()
                showWelcome = false
            }
        )
    } else {
        ControllerScreenContent(
            darkTheme = darkTheme,
            onThemeChange = onThemeChange,
            onShowGuide = { showWelcome = true }
        )
    }
}

@Composable
private fun WelcomeInstructionsScreen(onGetStarted: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasTermuxPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = hasTermuxPermission(context)
    }
    // Re-check regularly so the status updates after the user returns from Android Settings
    LaunchedEffect(Unit) {
        while (isActive) {
            granted = hasTermuxPermission(context)
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "WELCOME TO POCKETHOST",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "Host your Minecraft server",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "This quick guide explains the permissions PocketHost needs, the first setup, how to connect Playit, and where to find the main controls.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Text("Before you start", style = MaterialTheme.typography.titleLarge)
        Text(
            "Install Termux from a trusted source and open it once. PocketHost uses Termux to install and run the server, so keep Termux installed. Make sure the phone has a reliable internet connection and enough free storage.",
            style = MaterialTheme.typography.bodyMedium
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Allow PocketHost to control Termux (2 parts)", style = MaterialTheme.typography.titleMedium)

                Text("Part A: enable allow-external-apps in Termux", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Termux blocks PocketHost from running commands until allow-external-apps is enabled. Tap the button to copy a setup command and open Termux. Paste the command into the Termux terminal and press Enter, then fully close and reopen Termux. This setting lets apps with the required Android permission run commands in Termux, so only enable it for apps you trust.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = { openTermuxAccessSetup(context) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Set up Termux access") }

                Text("Part B: grant \"Run commands in Termux environment\" to PocketHost", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Tap Grant permission and choose Allow. If no Android dialog appears, tap Open app settings, then go to Permissions (or Additional permissions) and turn on \"Run commands in Termux environment\". Termux must already be installed for this option to show up.",
                    style = MaterialTheme.typography.bodySmall
                )
                StatusPill(
                    text = if (granted) "Permission granted" else "Permission not granted yet",
                    color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { permissionLauncher.launch(TERMUX_PERMISSION) },
                        enabled = !granted,
                        modifier = Modifier.weight(1f)
                    ) { Text("Grant permission", maxLines = 1) }
                    OutlinedButton(
                        onClick = { openAppSettings(context) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Open app settings", maxLines = 1) }
                }
            }
        }

        WelcomeInstructionStep(
            number = "1",
            title = "Choose and install a server",
            description = "Select Paper, Fabric, Forge, or Vanilla, enter a Minecraft version and RAM amount, then accept the Minecraft EULA. Tap Install / change and watch the progress bar. When it finishes, choose Run server now or Later. The first setup may take 5–20 minutes; keep PocketHost and Termux available."
        )
        WelcomeInstructionStep(
            number = "2",
            title = "Connect Playit",
            description = "When PocketHost displays a Playit link, tap Open playit link and complete the linking steps. Keep the Playit agent running while friends connect. The Public address card shows the address to share (copy it or tap Share). Use that address and port, not the claim URL, localhost, or your phone's private IP."
        )
        WelcomeInstructionStep(
            number = "3",
            title = "Join your server",
            description = "Use the full Playit address, including its port, in Minecraft Java Edition. This app installs a Java server. To join from Minecraft Bedrock on Android or console, you need a compatible bridge such as Geyser configured separately."
        )
        WelcomeInstructionStep(
            number = "4",
            title = "Start, stop, and manage settings",
            description = "Tap Start to run the server and Playit, and Stop to shut both down cleanly and save the world. Open Server properties to edit gameplay toggles and values. Save changes creates a backup of server.properties; most changes need a restart."
        )
        WelcomeInstructionStep(
            number = "5",
            title = "Players, mods, and backups",
            description = "The Players tab lists who is online and lets you whitelist, op, kick, or ban. The Mods tab (Plugins for Paper) searches Modrinth, CurseForge, or a direct link and installs straight into your server; it is unavailable on Vanilla servers. The Backups tab saves and restores your world."
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Playit unlink warning", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Unlink Playit clears this phone's saved Playit login and stops its local agent. It does not delete the cloud agent or tunnels from your Playit account. Use Relink playit only when you want to connect again.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Text(
            "Unofficial tool. Not affiliated with, endorsed by, or approved by Mojang or Microsoft.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = onGetStarted,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
        ) { Text("Get started") }
        Text(
            "You can reopen this guide any time with the ? button at the top of the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WelcomeInstructionStep(number: String, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    number,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThemeToggle(darkTheme: Boolean, onThemeChange: (Boolean) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (darkTheme) "\uD83C\uDF19 Dark" else "\u2600\uFE0F Light",
                style = MaterialTheme.typography.labelMedium
            )
            Switch(
                checked = darkTheme,
                onCheckedChange = onThemeChange,
                modifier = Modifier.height(30.dp)
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = color.copy(alpha = 0.18f)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun ControllerScreenContent(
    darkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
    onShowGuide: () -> Unit
) {
    var eulaAccepted by remember { mutableStateOf(false) }
    var serverType by remember { mutableStateOf("Paper") }
    var mcVersion by remember { mutableStateOf("1.21.1") }
    var ramGb by remember { mutableStateOf("2") }
    var command by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var runToken by remember { mutableStateOf("") }
    var setupTried by remember { mutableStateOf(false) }
    var serverInstalled by remember { mutableStateOf(false) }
    var serverStatusChecked by remember { mutableStateOf(false) }
    var installationInProgress by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf(0f) }
    var installStage by remember { mutableStateOf("") }
    var serverRunning by remember { mutableStateOf(false) }
    var serverStartPending by remember { mutableStateOf(false) }
    var stopRequested by remember { mutableStateOf(false) }
    var showInstallCompleteDialog by remember { mutableStateOf(false) }
    var showInstallFailedDialog by remember { mutableStateOf(false) }
    var showServerStartedDialog by remember { mutableStateOf(false) }
    var showServerStoppedDialog by remember { mutableStateOf(false) }
    var showUnlinkPlayitConfirm by remember { mutableStateOf(false) }
    var showServerProperties by remember { mutableStateOf(false) }
    var showAddressEdit by remember { mutableStateOf(false) }
    var addressDraft by remember { mutableStateOf("") }
    var serverPropertiesLoaded by remember { mutableStateOf(false) }
    var serverPropertiesStatus by remember {
        mutableStateOf("Open this window to load the installed server's settings.")
    }
    var serverPropertiesToken by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var tab by rememberSaveable { mutableStateOf(0) }

    var backupToken by remember { mutableStateOf("") }
    var modsToken by remember { mutableStateOf("") }
    var modInstallToken by remember { mutableStateOf("") }
    var pollPending by remember { mutableStateOf(false) }
    var pendingType by remember { mutableStateOf("Paper") }
    var pendingMc by remember { mutableStateOf("") }

    val lines = remember { mutableStateListOf<String>() }
    val serverProperties = remember {
        mutableStateMapOf<String, String>().apply { putAll(serverPropertyDefaults) }
    }
    // Status lines are replayed whenever the console reconnects; each one is handled only once.
    val handledMarkers = remember { mutableSetOf<String>() }
    val bk = remember { BackupUiState() }
    val ps = remember { PlayersUiState() }
    val ms = remember { ModsUiState() }

    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val preferences = remember(context) {
        context.getSharedPreferences("anmc_preferences", Context.MODE_PRIVATE)
    }
    var initialInstallSequenceShown by remember {
        mutableStateOf(preferences.getBoolean("initial_install_sequence_shown_v1", false))
    }
    // Only the very first successful installation shows the "Run server now?" popup.
    var showPostInstallSequenceForCurrentInstall by remember { mutableStateOf(false) }

    var publicAddress by remember {
        mutableStateOf(preferences.getString("public_address_v1", "") ?: "")
    }
    var installedType by remember { mutableStateOf(preferences.getString("installed_type_v1", null)) }
    var installedMc by remember { mutableStateOf(preferences.getString("installed_mc_v1", "") ?: "") }

    LaunchedEffect(Unit) { ms.cfKey = preferences.getString("curseforge_key_v1", "") ?: "" }

    val modsAvailable = serverInstalled && installedType != null && installedType != "Vanilla"
    val modsDir = ModService.dirFor(installedType ?: "Fabric")
    val modsLabel = if (installedType == "Paper") "Plugins" else "Mods"
    val mcForMods = installedMc.ifBlank { mcVersion }

    fun nowToken() = System.currentTimeMillis().toString()

    fun markInitialSequenceShown() {
        if (!initialInstallSequenceShown) {
            initialInstallSequenceShown = true
            preferences.edit().putBoolean("initial_install_sequence_shown_v1", true).apply()
        }
    }

    fun saveInstalledInfo(type: String, mc: String) {
        installedType = type
        if (mc.isNotBlank()) installedMc = mc
        preferences.edit()
            .putString("installed_type_v1", type)
            .putString("installed_mc_v1", installedMc)
            .apply()
    }

    fun beginInstall(token: String): Boolean {
        val ram = (ramGb.toIntOrNull() ?: 2).coerceIn(1, 8)
        val script = setupScript(serverType, mcVersion, ram, token)
        if (script == null) {
            lines.add("Invalid version. Use something like 1.21.1 (1.17 or newer).")
            return false
        }
        pendingType = serverType
        pendingMc = mcVersion
        installProgress = 0f
        installStage = "Starting"
        runInTermux(context, logged(script), background = true)
        return true
    }

    fun startServer() {
        if (!serverInstalled || installationInProgress) {
            lines.add("Server is not ready yet. Finish installation first.")
            return
        }
        runToken = nowToken()
        setupTried = false
        serverStartPending = false
        showServerStartedDialog = false
        lines.add("Starting Minecraft server and Playit...")
        runInTermux(context, startAllCmd(runToken), background = true)
    }

    fun loadProperties() {
        val token = nowToken()
        serverPropertiesToken = token
        serverPropertiesLoaded = false
        serverPropertiesStatus = "Loading server.properties..."
        serverProperties.clear()
        serverProperties.putAll(serverPropertyDefaults)
        runInTermux(context, loadServerPropertiesCommand(token), background = true)
    }

    fun saveProperties() {
        val snapshot = serverProperties.toMap()
        val error = validateServerProperties(snapshot)
        if (error != null) {
            serverPropertiesStatus = error
            return
        }
        val token = nowToken()
        serverPropertiesToken = token
        serverPropertiesLoaded = false
        serverPropertiesStatus = "Saving changes and creating a backup..."
        runInTermux(context, saveServerPropertiesCommand(snapshot, token), background = true)
    }

    fun sendCommand(cmd: String) {
        lines.add("> $cmd")
        sendToServer(context, cmd)
    }

    // ----- Backups -----
    fun refreshBackups() {
        val t = nowToken()
        backupToken = t
        bk.items.clear()
        runInTermux(context, listBackupsCmd(t), background = true)
    }

    fun createBackup() {
        val t = nowToken()
        backupToken = t
        bk.busy = true
        bk.status = if (serverRunning) "Saving the world and creating a backup (about 10–30 s)..."
        else "Creating a backup..."
        runInTermux(context, backupCmd(t), background = true)
    }

    fun restoreBackup(item: BackupItem) {
        if (serverRunning || serverStartPending) {
            bk.status = "Stop the server before restoring a backup."
            return
        }
        if (!backupNameRegex.matches(item.name)) {
            bk.status = "That backup file name is not supported."
            return
        }
        val t = nowToken()
        backupToken = t
        bk.busy = true
        bk.status = "Restoring ${item.name} (your current world is saved as a pre-restore backup first)..."
        runInTermux(context, restoreBackupCmd(t, item.name), background = true)
    }

    fun deleteBackup(item: BackupItem) {
        if (!backupNameRegex.matches(item.name)) return
        val t = nowToken()
        backupToken = t
        bk.items.clear()
        runInTermux(context, deleteBackupCmd(t, item.name), background = true)
    }

    // ----- Mods / plugins -----
    fun refreshMods() {
        if (!modsAvailable) return
        val t = nowToken()
        modsToken = t
        ms.installed.clear()
        runInTermux(context, listModsCmd(modsDir, t), background = true)
    }

    fun sendInstall(files: List<DownloadFile>) {
        val t = nowToken()
        modInstallToken = t
        ms.busy = true
        ms.status = "Downloading ${files.size} file(s) into the server's $modsDir folder..."
        runInTermux(context, installFilesCmd(modsDir, files, t), background = true)
    }

    fun searchMods() {
        val type = installedType ?: return
        if (ms.provider == "CurseForge" && ms.cfKey.isBlank()) {
            ms.status = "Enter your CurseForge API key first (free at console.curseforge.com)."
            return
        }
        ms.busy = true
        ms.status = "Searching ${ms.provider}..."
        ms.results.clear()
        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    if (ms.provider == "CurseForge") ModService.curseSearch(ms.cfKey.trim(), ms.query, type, mcForMods)
                    else ModService.modrinthSearch(ms.query, type, mcForMods)
                }
                ms.results.addAll(res)
                ms.status = if (res.isEmpty()) "No results for Minecraft $mcForMods."
                else "${res.size} results for $type $mcForMods. Tap Install to add one to the server."
            } catch (e: Exception) {
                ms.status = "Search failed: ${e.message ?: e.javaClass.simpleName}"
            }
            ms.busy = false
        }
    }

    fun installMod(result: ModResult) {
        val type = installedType ?: return
        ms.busy = true
        ms.status = "Finding a compatible version of ${result.title}..."
        scope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    if (ms.provider == "CurseForge") ModService.curseResolve(ms.cfKey.trim(), result.id, type, mcForMods)
                    else ModService.modrinthResolve(result.id, type, mcForMods)
                }
                sendInstall(files)
            } catch (e: Exception) {
                ms.busy = false
                ms.status = "Could not install ${result.title}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun installDirect() {
        val url = ms.directUrl.trim()
        if (!url.startsWith("https://")) {
            ms.status = "Direct links must start with https://"
            return
        }
        val name = ModService.safeFileName(ms.directName.ifBlank { url.substringBefore('?').substringAfterLast('/') })
        if (name == null) {
            ms.status = "Could not work out a .jar file name. Type one in the file name box (it must end in .jar)."
            return
        }
        sendInstall(listOf(DownloadFile(name, url, "", "")))
    }

    fun removeInstalled(file: InstalledFile) {
        if (file.name.contains('/') || !file.name.endsWith(".jar")) return
        val t = nowToken()
        modsToken = t
        ms.installed.clear()
        ms.status = "Removed ${file.name}. Restart the server to apply."
        runInTermux(context, removeModCmd(modsDir, file.name, t), background = true)
    }

    // Reacts to status markers written by the Termux helper scripts.
    fun handleMarker(kind: String, token: String) {
        if (token != runToken) return
        if (!handledMarkers.add("$kind:$token")) return
        when (kind) {
            "POCKETHOST_NOT_INSTALLED" -> {
                if (setupTried) {
                    installationInProgress = false
                    lines.add("Required files are missing. Use Install / change to repair the installation.")
                    showInstallFailedDialog = true
                    return
                }
                if (!eulaAccepted) {
                    lines.add("Not installed yet. Tick the EULA box, then tap Install / change.")
                    return
                }
                setupTried = true
                installationInProgress = true
                showPostInstallSequenceForCurrentInstall = !serverInstalled && !initialInstallSequenceShown
                lines.add("First run: installing everything (5–20 min). Wait for the completion message.")
                val installToken = nowToken()
                runToken = installToken
                if (!beginInstall(installToken)) {
                    installationInProgress = false
                    showPostInstallSequenceForCurrentInstall = false
                }
            }
            "SETUP_DONE" -> {
                setupTried = true
                installationInProgress = false
                installProgress = 1f
                installStage = "Done"
                serverInstalled = true
                serverStatusChecked = true
                serverRunning = false
                serverStartPending = false
                saveInstalledInfo(pendingType, pendingMc)
                val firstInstall = showPostInstallSequenceForCurrentInstall && !initialInstallSequenceShown
                showPostInstallSequenceForCurrentInstall = false
                if (firstInstall) {
                    markInitialSequenceShown()
                    lines.add("First server installation completed. Choose Run server now when ready.")
                    showInstallCompleteDialog = true
                } else {
                    lines.add("Installation/update completed. Tap Start when you want to run the server.")
                    Toast.makeText(
                        context,
                        "Installation/update complete. Start the server when you're ready.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            "SETUP_FAILED" -> {
                installationInProgress = false
                showPostInstallSequenceForCurrentInstall = false
                lines.add("Installation failed. See the console for details, then tap Install / change to retry.")
                showInstallFailedDialog = true
            }
            "SERVER_PRESENT" -> {
                // A server already exists on disk, so never show the first-install popup for it.
                if (!installationInProgress) markInitialSequenceShown()
            }
            "SERVER_INSTALLED" -> {
                serverInstalled = true
                serverStatusChecked = true
                if (!installationInProgress) markInitialSequenceShown()
            }
            "SERVER_NOT_INSTALLED" -> {
                serverInstalled = false
                serverStatusChecked = true
            }
            "SERVER_RUNNING" -> {
                serverRunning = true
                serverStartPending = false
            }
            "SERVER_STOPPED" -> serverRunning = false
            "SERVER_START_REQUESTED" -> serverStartPending = true
        }
    }

    // Generic POCKETHOST_OP lines: install progress, backups, mod listing and mod installs.
    fun handleOp(token: String, kind: String, payload: String) {
        when {
            token == runToken && kind == "PROGRESS" -> {
                payload.substringBefore('|').toIntOrNull()?.let { installProgress = (it / 100f).coerceIn(0f, 1f) }
                installStage = payload.substringAfter('|', "")
            }
            token == backupToken -> when (kind) {
                "BK_ITEM" -> {
                    val cut = payload.lastIndexOf('|')
                    if (cut > 0) bk.items.add(BackupItem(payload.substring(0, cut), payload.substring(cut + 1).toLongOrNull() ?: 0L))
                }
                "BK_END" -> {
                    bk.busy = false
                    bk.status = if (bk.items.isEmpty()) "No backups yet. Tap Create backup." else "${bk.items.size} backup(s)."
                }
                "BK_DONE" -> {
                    bk.busy = false
                    val name = payload.substringBefore('|')
                    val exported = payload.substringAfter('|', "0") == "1"
                    bk.status = if (name == "restored") "World restored. You can start the server now."
                    else "Backup saved: $name" + if (exported) " (also copied to phone storage > PocketHost-Backups)"
                    else ". Tip: run termux-setup-storage in Termux to also copy backups to phone storage."
                    refreshBackups()
                }
                "BK_ERR" -> {
                    bk.busy = false
                    bk.status = payload
                }
            }
            token == modsToken -> when (kind) {
                "MODFILE" -> {
                    val cut = payload.lastIndexOf('|')
                    if (cut > 0) ms.installed.add(InstalledFile(payload.substring(0, cut), payload.substring(cut + 1).toLongOrNull() ?: 0L))
                }
            }
            token == modInstallToken -> when (kind) {
                "MODINSTALL_DONE" -> {
                    ms.busy = false
                    ms.status = "Installed $payload file(s). Restart the server to load them."
                    refreshMods()
                }
                "MODINSTALL_ERR" -> {
                    ms.busy = false
                    ms.status = payload
                    refreshMods()
                }
            }
        }
    }

    // Normal console lines: player tracking, address detection, start/stop detection
    fun handleLogLine(clean: String) {
        val lm = listRegex.find(clean)
        if (lm != null) {
            ps.max = lm.groupValues[2]
            val names = lm.groupValues[3].split(",").map { it.trim() }.filter { it.isNotEmpty() }
            ps.players.clear()
            ps.players.addAll(names)
            if (pollPending) {
                pollPending = false
                return // hide our own background "list" polling from the console
            }
        } else {
            val jm = joinLeaveRegex.find(clean)
            if (jm != null) {
                val name = jm.groupValues[1]
                if (jm.groupValues[2] == "joined") {
                    if (name !in ps.players) ps.players.add(name)
                } else ps.players.remove(name)
            }
        }
        if (clean.startsWith("[playit]")) {
            detectPublicAddress(clean)?.let { found ->
                if (found != publicAddress) {
                    publicAddress = found
                    preferences.edit().putString("public_address_v1", found).apply()
                }
            }
        }
        lines.add(clean)
        if (lines.size > 500) lines.removeAt(0)
        if (serverStartPending && clean.contains("Done (") && clean.contains("For help")) {
            serverStartPending = false
            serverRunning = true
            showServerStartedDialog = true
        }
        if (clean.contains("Server and playit stopped.")) {
            serverRunning = false
            serverStartPending = false
            // Old lines are replayed on reconnect, so only react to a stop we asked for
            if (stopRequested) {
                stopRequested = false
                showServerStoppedDialog = true
            }
        }
    }

    // When PocketHost opens, ask Termux whether everything is already installed and running.
    LaunchedEffect(Unit) {
        val token = nowToken()
        runToken = token
        runInTermux(context, checkServerStatusCmd(token), background = true)
    }

    // If Termux never answers (fresh phone, or access not set up), don't leave Install locked.
    LaunchedEffect(Unit) {
        delay(8000)
        if (!serverStatusChecked) {
            serverStatusChecked = true
            lines.add("No answer from Termux yet. For a fresh install, tick the EULA and tap Install / change. Otherwise check Termux access.")
        }
    }

    // Live console: connect to the log stream running inside Termux, retry forever
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            while (isActive) {
                val s = Socket()
                val handle = coroutineContext[Job]?.invokeOnCompletion { runCatching { s.close() } }
                try {
                    s.connect(InetSocketAddress("127.0.0.1", 8765), 1000)
                    withContext(Dispatchers.Main) { lines.clear(); connected = true }
                    val reader = s.getInputStream().bufferedReader()
                    while (isActive) {
                        val raw = reader.readLine() ?: break
                        val clean = cleanLine(raw)
                        if (clean.isBlank()) continue
                        val m = markerRegex.matchEntire(clean)
                        val op = opRegex.matchEntire(clean)
                        withContext(Dispatchers.Main) {
                            val propToken = serverPropertiesToken
                            when {
                                clean.startsWith("POCKETHOST_PROP:$propToken:") -> {
                                    val payload = clean.removePrefix("POCKETHOST_PROP:$propToken:")
                                    val split = payload.indexOf('=')
                                    if (split > 0) {
                                        val key = payload.substring(0, split)
                                        if (key in serverPropertyDefaults) {
                                            serverProperties[key] = payload.substring(split + 1)
                                        }
                                    }
                                }
                                clean == "POCKETHOST_PROPS_LOADED:$propToken" -> {
                                    serverPropertiesLoaded = true
                                    serverPropertiesStatus =
                                        "Loaded. Save creates a timestamped backup before updating the file. Restart the server to apply most changes."
                                }
                                clean.startsWith("POCKETHOST_PROP_ERROR:$propToken:") -> {
                                    serverPropertiesLoaded = false
                                    serverPropertiesStatus = clean.substringAfter("POCKETHOST_PROP_ERROR:$propToken:")
                                }
                                clean == "POCKETHOST_PROP_SAVED:$propToken" -> {
                                    serverPropertiesLoaded = true
                                    serverPropertiesStatus =
                                        "Saved successfully. Restart the server to apply most changes. A backup was created beside server.properties."
                                    lines.add("Server properties saved. Restart the Minecraft server for changes to take effect.")
                                }
                                clean.startsWith("POCKETHOST_INFO:") -> {
                                    // "Type|version" of the installed server
                                    val info = clean.removePrefix("POCKETHOST_INFO:").split("|")
                                    val type = info.getOrNull(0).orEmpty()
                                    if (type in listOf("Paper", "Fabric", "Forge", "Vanilla")) {
                                        saveInstalledInfo(type, info.getOrNull(1).orEmpty())
                                    }
                                }
                                op != null -> {
                                    if (handledMarkers.add(clean)) {
                                        handleOp(op.groupValues[1], op.groupValues[2], op.groupValues[3])
                                    }
                                }
                                m != null -> handleMarker(m.groupValues[1], m.groupValues[2])
                                else -> handleLogLine(clean)
                            }
                            tick++
                        }
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

    LaunchedEffect(tick) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    // Vanilla servers have no mods, so the tab can never stay selected there
    LaunchedEffect(modsAvailable) {
        if (!modsAvailable && tab == 2) tab = 0
    }
    // Keep the player list fresh while the Players tab is open
    LaunchedEffect(tab, serverRunning) {
        if (!serverRunning) ps.players.clear()
        if (tab == 1 && serverRunning) {
            while (isActive) {
                pollPending = true
                sendToServer(context, "list")
                delay(10_000)
            }
        }
    }
    LaunchedEffect(tab) { if (tab == 3) refreshBackups() }
    LaunchedEffect(tab, modsAvailable) { if (tab == 2 && modsAvailable) refreshMods() }

    val claimUrl = lines.lastOrNull { claimRegex.containsMatchIn(it) }
        ?.let { claimRegex.find(it)?.value }

    // ---------- Dialogs ----------
    if (showInstallCompleteDialog) {
        AlertDialog(
            onDismissRequest = { showInstallCompleteDialog = false },
            title = { Text("Installation complete") },
            text = { Text("Your $serverType server is installed. Do you want to run it now?") },
            confirmButton = {
                TextButton(onClick = {
                    showInstallCompleteDialog = false
                    startServer()
                }) { Text("Run server now") }
            },
            dismissButton = {
                TextButton(onClick = { showInstallCompleteDialog = false }) { Text("Later") }
            }
        )
    }

    if (showInstallFailedDialog) {
        AlertDialog(
            onDismissRequest = { showInstallFailedDialog = false },
            title = { Text("Installation failed") },
            text = { Text("Something went wrong during installation. Check the console for the last error, then tap Install / change to try again.") },
            confirmButton = {
                TextButton(onClick = { showInstallFailedDialog = false }) { Text("OK") }
            }
        )
    }

    if (showServerStartedDialog) {
        AlertDialog(
            onDismissRequest = { showServerStartedDialog = false },
            title = { Text("Server is running") },
            text = {
                Text("Minecraft has started. If Playit shows a link in the console, tap Open playit link to connect your account. Friends join with the address shown in the Public address card.")
            },
            confirmButton = {
                TextButton(onClick = { showServerStartedDialog = false }) { Text("OK") }
            }
        )
    }

    if (showServerStoppedDialog) {
        AlertDialog(
            onDismissRequest = { showServerStoppedDialog = false },
            title = { Text("Server stopped") },
            text = { Text("The Minecraft server and Playit have stopped.") },
            confirmButton = {
                TextButton(onClick = { showServerStoppedDialog = false }) { Text("OK") }
            }
        )
    }

    if (showUnlinkPlayitConfirm) {
        AlertDialog(
            onDismissRequest = { showUnlinkPlayitConfirm = false },
            title = { Text("Unlink Playit?") },
            text = {
                Text("This clears this phone's saved Playit login and stops its local agent. It does not delete the agent or tunnels from your Playit account. Tap Relink playit to connect again.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showUnlinkPlayitConfirm = false
                    lines.add("Unlinking Playit on this phone...")
                    runInTermux(context, unlinkPlayitCmd(), background = true)
                }) { Text("Unlink") }
            },
            dismissButton = {
                TextButton(onClick = { showUnlinkPlayitConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showAddressEdit) {
        AlertDialog(
            onDismissRequest = { showAddressEdit = false },
            title = { Text("Public address") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "PocketHost tries to detect the address from the Playit log. If it is missing or wrong, paste the address and port from your Playit dashboard (for example name.gl.at.ply.gg:12345).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = addressDraft,
                        onValueChange = { addressDraft = it.trim() },
                        singleLine = true,
                        label = { Text("Address:port") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    publicAddress = addressDraft
                    preferences.edit().putString("public_address_v1", addressDraft).apply()
                    showAddressEdit = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddressEdit = false }) { Text("Cancel") }
            }
        )
    }

    if (showServerProperties) {
        ServerPropertiesDialog(
            values = serverProperties,
            loaded = serverPropertiesLoaded,
            status = serverPropertiesStatus,
            onChange = { key, value -> serverProperties[key] = value },
            onReload = { loadProperties() },
            onSave = { saveProperties() },
            onDismiss = { showServerProperties = false }
        )
    }

    // ---------- Main screen ----------
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Pocket Server Hosting",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(
                onClick = onShowGuide,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("?", style = MaterialTheme.typography.titleMedium) }
            ThemeToggle(darkTheme = darkTheme, onThemeChange = onThemeChange)
        }

        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Server", maxLines = 1) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Players", maxLines = 1) })
            Tab(
                selected = tab == 2,
                onClick = { tab = 2 },
                enabled = modsAvailable,
                text = {
                    Text(
                        if (installedType == "Vanilla") "Mods (n/a)" else modsLabel,
                        maxLines = 1
                    )
                }
            )
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Backups", maxLines = 1) })
        }

        when (tab) {
            0 -> Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Install
                SectionCard(
                    if (installedType != null && serverInstalled)
                        "Install  •  current: $installedType ${installedMc}".trim()
                    else "Install"
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        listOf("Paper", "Fabric", "Forge", "Vanilla").forEach { type ->
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(1.dp)
                            ) {
                                RadioButton(
                                    selected = serverType == type,
                                    onClick = { serverType = type },
                                    modifier = Modifier.size(30.dp)
                                )
                                Text(type, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = mcVersion,
                            onValueChange = { mcVersion = it.trim() },
                            label = { Text("MC version") },
                            singleLine = true,
                            modifier = Modifier.weight(2f),
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = ramGb,
                            onValueChange = { ramGb = it.filter(Char::isDigit) },
                            label = { Text("RAM (GB)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = eulaAccepted,
                            onCheckedChange = { eulaAccepted = it },
                            modifier = Modifier.size(30.dp)
                        )
                        Text("I accept the Minecraft EULA", style = MaterialTheme.typography.bodySmall)
                    }
                    if (installationInProgress) {
                        LinearProgressIndicator(
                            progress = installProgress,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${(installProgress * 100).toInt()}%  •  $installStage",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Start and Stop are disabled until the installation finishes.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        enabled = eulaAccepted && serverStatusChecked && !installationInProgress &&
                                !serverRunning && !serverStartPending,
                        onClick = {
                            runToken = nowToken()
                            setupTried = true
                            installationInProgress = true
                            showInstallFailedDialog = false
                            // Only a truly new server install can trigger the one-time completion popup.
                            showPostInstallSequenceForCurrentInstall = !serverInstalled && !initialInstallSequenceShown
                            lines.add("Installing $serverType $mcVersion (5–20 min). The server will not start automatically.")
                            if (!beginInstall(runToken)) {
                                installationInProgress = false
                                showPostInstallSequenceForCurrentInstall = false
                            }
                        }
                    ) {
                        Text(
                            when {
                                installationInProgress -> "Installing…"
                                !serverStatusChecked -> "Checking installation…"
                                else -> "Install / change"
                            },
                            maxLines = 1
                        )
                    }
                }

                // Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val (statusText, statusColor) = when {
                        installationInProgress -> "Installing" to MaterialTheme.colorScheme.tertiary
                        serverStartPending -> "Starting…" to MaterialTheme.colorScheme.tertiary
                        serverRunning -> "Running" to MaterialTheme.colorScheme.primary
                        serverInstalled -> "Stopped" to MaterialTheme.colorScheme.outline
                        else -> "Not installed" to MaterialTheme.colorScheme.outline
                    }
                    StatusPill(statusText, statusColor)
                    StatusPill(
                        if (connected) "Console connected" else "Console waiting",
                        if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { openTermuxAccessSetup(context) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) { Text("Termux access", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                }

                // Public address
                SectionCard("Public address") {
                    Text(
                        if (publicAddress.isBlank()) "Not detected yet" else publicAddress,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        if (publicAddress.isBlank())
                            "Start the server and link Playit. The address usually appears here automatically; otherwise tap Edit and paste it from your Playit dashboard."
                        else "Friends enter this in Minecraft Java Edition > Multiplayer > Add Server.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.weight(1f).height(40.dp),
                            enabled = publicAddress.isNotBlank(),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            onClick = { copyText(context, "Server address", publicAddress) }
                        ) { Text("Copy", style = MaterialTheme.typography.labelMedium) }
                        OutlinedButton(
                            modifier = Modifier.weight(1f).height(40.dp),
                            enabled = publicAddress.isNotBlank(),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            onClick = { shareText(context, "Join my Minecraft server: $publicAddress") }
                        ) { Text("Share", style = MaterialTheme.typography.labelMedium) }
                        TextButton(
                            modifier = Modifier.height(40.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            onClick = {
                                addressDraft = publicAddress
                                showAddressEdit = true
                            }
                        ) { Text("Edit", style = MaterialTheme.typography.labelMedium) }
                    }
                }

                // Controls
                SectionCard("Controls") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.weight(1f).height(46.dp),
                            enabled = serverInstalled && !installationInProgress && !serverRunning && !serverStartPending,
                            onClick = { startServer() }
                        ) { Text("▶  Start", maxLines = 1) }
                        Button(
                            modifier = Modifier.weight(1f).height(46.dp),
                            enabled = serverInstalled && !installationInProgress,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            onClick = {
                                stopRequested = true
                                lines.add("Stopping server (saving world), then playit...")
                                runInTermux(context, stopAllCmd(), background = true)
                            }
                        ) { Text("■  Stop", maxLines = 1) }
                    }
                    Text(
                        "Start runs Minecraft and the Playit tunnel. Stop saves the world and shuts both down.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f).height(40.dp),
                            enabled = !installationInProgress,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            onClick = {
                                lines.add("Relinking playit. A link will appear below.")
                                runInTermux(context, relinkCmd(), background = true)
                            }
                        ) { Text("Relink playit", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                        OutlinedButton(
                            modifier = Modifier.weight(1f).height(40.dp),
                            enabled = !installationInProgress,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            onClick = { showUnlinkPlayitConfirm = true }
                        ) { Text("Unlink Playit", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f).height(40.dp),
                            enabled = serverInstalled && !installationInProgress,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            onClick = {
                                showServerProperties = true
                                loadProperties()
                            }
                        ) { Text("Server properties", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                        if (claimUrl != null) {
                            Button(
                                modifier = Modifier.weight(1f).height(40.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                onClick = { uriHandler.openUri(claimUrl) }
                            ) { Text("Open playit link", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                        }
                    }
                }

                // Console
                SectionCard("Console") {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        state = listState,
                        contentPadding = PaddingValues(vertical = 2.dp)
                    ) {
                        items(lines) { line ->
                            Text(text = line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            label = { Text("Server command") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            modifier = Modifier.height(48.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            onClick = {
                                if (command.isNotBlank()) {
                                    sendCommand(command)
                                    command = ""
                                }
                            }
                        ) { Text("Send", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }

            1 -> PlayersTab(
                ps = ps,
                serverRunning = serverRunning,
                onSend = { sendCommand(it) },
                onRefresh = {
                    pollPending = true
                    sendToServer(context, "list")
                }
            )

            2 -> if (modsAvailable) {
                ModsTab(
                    ms = ms,
                    label = modsLabel,
                    type = installedType ?: "",
                    mc = mcForMods,
                    dir = modsDir,
                    onSearch = { searchMods() },
                    onInstall = { installMod(it) },
                    onInstallDirect = { installDirect() },
                    onRefresh = { refreshMods() },
                    onRemove = { removeInstalled(it) },
                    onSaveKey = {
                        preferences.edit().putString("curseforge_key_v1", ms.cfKey.trim()).apply()
                        ms.status = "CurseForge key saved on this phone."
                    }
                )
            }

            3 -> BackupsTab(
                bk = bk,
                serverRunning = serverRunning || serverStartPending,
                serverInstalled = serverInstalled,
                onCreate = { createBackup() },
                onRefresh = { refreshBackups() },
                onRestore = { restoreBackup(it) },
                onDelete = { deleteBackup(it) }
            )
        }
    }
}

// ---------- Players tab ----------
@Composable
private fun PlayersTab(
    ps: PlayersUiState,
    serverRunning: Boolean,
    onSend: (String) -> Unit,
    onRefresh: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var confirmBan by remember { mutableStateOf<String?>(null) }
    val validName = playerNameRegex.matches(name)

    confirmBan?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmBan = null },
            title = { Text("Ban $target?") },
            text = { Text("They will be unable to join until you pardon them.") },
            confirmButton = {
                TextButton(onClick = { onSend("ban $target"); confirmBan = null }) { Text("Ban") }
            },
            dismissButton = { TextButton(onClick = { confirmBan = null }) { Text("Cancel") } }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionCard("Online players (${ps.players.size} / ${ps.max})") {
            if (!serverRunning) {
                Text("Start the server to see players.", style = MaterialTheme.typography.bodySmall)
            } else if (ps.players.isEmpty()) {
                Text("Nobody is online right now. This list refreshes every 10 seconds.", style = MaterialTheme.typography.bodySmall)
            }
            ps.players.forEach { player ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(player, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val pad = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        TextButton(onClick = { onSend("whitelist add $player") }, contentPadding = pad) { Text("WL", style = MaterialTheme.typography.labelMedium) }
                        TextButton(onClick = { onSend("op $player") }, contentPadding = pad) { Text("Op", style = MaterialTheme.typography.labelMedium) }
                        TextButton(onClick = { onSend("kick $player") }, contentPadding = pad) { Text("Kick", style = MaterialTheme.typography.labelMedium) }
                        TextButton(onClick = { confirmBan = player }, contentPadding = pad) {
                            Text("Ban", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            OutlinedButton(onClick = onRefresh, enabled = serverRunning) { Text("Refresh now") }
        }

        SectionCard("Manage a player by name") {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.trim() },
                label = { Text("Player name") },
                singleLine = true,
                isError = name.isNotEmpty() && !validName,
                supportingText = { if (name.isNotEmpty() && !validName) Text("Letters, numbers, _ and . only (max 20)") },
                modifier = Modifier.fillMaxWidth()
            )
            val enabled = serverRunning && validName
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), enabled = enabled, onClick = { onSend("whitelist add $name") }) {
                    Text("Whitelist +", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(modifier = Modifier.weight(1f), enabled = enabled, onClick = { onSend("whitelist remove $name") }) {
                    Text("Whitelist −", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), enabled = enabled, onClick = { onSend("op $name") }) {
                    Text("Make op", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(modifier = Modifier.weight(1f), enabled = enabled, onClick = { onSend("deop $name") }) {
                    Text("Remove op", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), enabled = enabled, onClick = { onSend("kick $name") }) {
                    Text("Kick", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(modifier = Modifier.weight(1f), enabled = enabled, onClick = { confirmBan = name }) {
                    Text("Ban", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(modifier = Modifier.weight(1f), enabled = enabled, onClick = { onSend("pardon $name") }) {
                    Text("Pardon", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                "Commands are sent to the running server and appear in the Server tab console. The whitelist only blocks other players when \"Whitelist enabled\" is on in Server properties.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------- Mods / plugins tab ----------
@Composable
private fun ModsTab(
    ms: ModsUiState,
    label: String,
    type: String,
    mc: String,
    dir: String,
    onSearch: () -> Unit,
    onInstall: (ModResult) -> Unit,
    onInstallDirect: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: (InstalledFile) -> Unit,
    onSaveKey: () -> Unit
) {
    var confirmRemove by remember { mutableStateOf<InstalledFile?>(null) }

    confirmRemove?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${f.name}?") },
            text = { Text("The file is deleted from the server's $dir folder. Restart the server to apply.") },
            confirmButton = { TextButton(onClick = { onRemove(f); confirmRemove = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionCard("Add $label for $type $mc") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Modrinth", "CurseForge", "Direct link").forEach { p ->
                    val modifier = Modifier.weight(1f).height(38.dp)
                    val pad = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    if (ms.provider == p) {
                        Button(onClick = { ms.provider = p }, modifier = modifier, contentPadding = pad) {
                            Text(p, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        OutlinedButton(onClick = { ms.provider = p }, modifier = modifier, contentPadding = pad) {
                            Text(p, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            if (ms.provider == "Direct link") {
                Text(
                    "Paste an https link to a .jar file. Only use links from sources you trust: the file runs with full access to your server.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = ms.directUrl,
                    onValueChange = { ms.directUrl = it },
                    label = { Text("https:// link to .jar") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ms.directName,
                    onValueChange = { ms.directName = it },
                    label = { Text("File name (optional, must end in .jar)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = onInstallDirect,
                    enabled = !ms.busy && ms.directUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Download and install") }
            } else {
                if (ms.provider == "CurseForge") {
                    Text(
                        "CurseForge requires a free API key from console.curseforge.com. It is stored only on this phone.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = ms.cfKey,
                            onValueChange = { ms.cfKey = it.trim() },
                            label = { Text("API key") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = onSaveKey) { Text("Save") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = ms.query,
                        onValueChange = { ms.query = it },
                        label = { Text("Search $label") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = onSearch, enabled = !ms.busy, modifier = Modifier.height(48.dp)) { Text("Search") }
                }
                Text(
                    "Leave the box empty to browse the most popular. Results are filtered to $type $mc and to ones that work on servers. Required dependencies are installed automatically (Modrinth).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (ms.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (ms.status.isNotBlank()) {
                Text(ms.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }

        if (ms.provider != "Direct link" && ms.results.isNotEmpty()) {
            SectionCard("Results") {
                ms.results.forEach { r ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    r.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    (if (r.author.isNotBlank()) "by ${r.author}  •  " else "") + "%,d downloads".format(r.downloads),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { onInstall(r) },
                                enabled = !ms.busy,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) { Text("Install") }
                        }
                    }
                }
            }
        }

        SectionCard("Installed in $dir (${ms.installed.size})") {
            if (ms.installed.isEmpty()) {
                Text("Nothing installed yet.", style = MaterialTheme.typography.bodySmall)
            }
            ms.installed.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${f.sizeKb} KB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { confirmRemove = f }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            OutlinedButton(onClick = onRefresh) { Text("Refresh list") }
            Text(
                "Files live in Termux's private storage (~/server/$dir), so Android's file manager cannot open it. Manage them here. Restart the server after adding or removing.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------- Backups tab ----------
@Composable
private fun BackupsTab(
    bk: BackupUiState,
    serverRunning: Boolean,
    serverInstalled: Boolean,
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
    onRestore: (BackupItem) -> Unit,
    onDelete: (BackupItem) -> Unit
) {
    var confirm by remember { mutableStateOf<Pair<Boolean, BackupItem>?>(null) } // true = restore, false = delete

    confirm?.let { (isRestore, item) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (isRestore) "Restore this backup?" else "Delete this backup?") },
            text = {
                Text(
                    if (isRestore) "${item.name}\n\nThe current world is saved as a pre-restore backup first, then replaced with this one."
                    else "${item.name} will be permanently deleted from Termux storage."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isRestore) onRestore(item) else onDelete(item)
                    confirm = null
                }) { Text(if (isRestore) "Restore" else "Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionCard("World backups") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f).height(44.dp),
                    enabled = serverInstalled && !bk.busy,
                    onClick = onCreate
                ) { Text("Create backup", maxLines = 1) }
                OutlinedButton(
                    modifier = Modifier.weight(1f).height(44.dp),
                    enabled = !bk.busy,
                    onClick = onRefresh
                ) { Text("Refresh", maxLines = 1) }
            }
            if (bk.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(bk.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "Backups include the overworld, Nether, and End folders. You can back up while the server runs (it briefly pauses saving). Restoring requires the server to be stopped.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard("Saved backups (${bk.items.size})") {
            bk.items.sortedByDescending { it.name }.forEach { item ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "%.1f MB".format(item.sizeKb / 1024.0),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            enabled = !bk.busy && !serverRunning,
                            onClick = { confirm = true to item },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("Restore", style = MaterialTheme.typography.labelMedium) }
                        TextButton(
                            enabled = !bk.busy,
                            onClick = { confirm = false to item },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("Delete", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            if (serverRunning) {
                Text("Stop the server to enable Restore.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ServerPropertiesDialog(
    values: Map<String, String>,
    loaded: Boolean,
    status: String,
    onChange: (String, String) -> Unit,
    onReload: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 760.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Server properties",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onReload) { Text("Reload") }
                }
                Text(
                    "Changes are written to ~/server/server.properties. Most settings need a server restart. If you change the server port, the Playit tunnel must point to the same port.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Turning Online authentication off allows unauthenticated/offline-mode connections; use it only if you understand the security impact.",
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
                        item(key = "header-$group") {
                            Text(group, style = MaterialTheme.typography.titleMedium)
                        }
                        items(booleanServerProperties.filter { it.group == group }, key = { it.key }) { property ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(property.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        property.key,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = (values[property.key] ?: property.defaultValue.toString()).toBoolean(),
                                    onCheckedChange = { onChange(property.key, it.toString()) }
                                )
                            }
                        }
                    }
                    item(key = "header-text-properties") {
                        Text("Server values", style = MaterialTheme.typography.titleMedium)
                    }
                    items(textServerProperties, key = { it.key }) { property ->
                        OutlinedTextField(
                            value = values[property.key] ?: property.defaultValue,
                            onValueChange = { updated ->
                                val safe = if (property.numeric) updated.filter(Char::isDigit)
                                else updated.replace("\n", "").replace("\r", "")
                                onChange(property.key, safe)
                            },
                            label = { Text(property.label) },
                            supportingText = { Text(property.key) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.contains("must be", ignoreCase = true) ||
                        status.contains("not installed", ignoreCase = true) ||
                        status.contains("failed", ignoreCase = true)
                    ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onSave, enabled = loaded) { Text("Save changes") }
                }
            }
        }
    }
}

// ---------- Helpers and Termux scripts ----------

// Removes terminal color/control codes so the log is readable
private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007|\u001B[()][A-Z0-9]")
fun cleanLine(s: String): String = s.replace(ansi, "").replace("\r", "").trimEnd()

// Returns "openjdk-17" or "openjdk-21", or null if the version isn't valid/supported
fun javaPackageFor(mc: String): String? {
    if (!Regex("^[0-9]+\\.[0-9]+(\\.[0-9]+)?$").matches(mc)) return null
    val parts = mc.split(".").map { it.toInt() }
    if (parts[0] != 1) return "openjdk-21"
    val minor = parts[1]
    val patch = parts.getOrElse(2) { 0 }
    return when {
        minor < 17 -> null
        minor < 20 || (minor == 20 && patch < 5) -> "openjdk-17"
        else -> "openjdk-21"
    }
}

// Runs a script in the background and sends all of its output to the log the app is reading
fun logged(script: String): String =
    ENV + "mkdir -p $HOME/anmc; touch $LOG; {\n$script\n} >> $LOG 2>&1"

private fun progress(token: String, percent: Int, label: String) =
    "echo 'POCKETHOST_OP:$token:PROGRESS:$percent|$label'"

fun setupScript(type: String, mc: String, ramGb: Int, token: String): String? {
    val java = javaPackageFor(mc) ?: return null   // also blocks unsafe input
    val c = "curl -fsSL -A anmc-app"
    val download = when (type) {
        "Paper" -> listOf(
            "URL=\$($c https://fill.papermc.io/v3/projects/paper/versions/$mc/builds/latest | jq -r '.downloads.\"server:default\".url')",
            "wget -O server.jar \"\$URL\""
        )
        "Fabric" -> listOf(
            "LOADER=\$($c https://meta.fabricmc.net/v2/versions/loader | jq -r '[.[]|select(.stable)][0].version')",
            "INSTALLER=\$($c https://meta.fabricmc.net/v2/versions/installer | jq -r '[.[]|select(.stable)][0].version')",
            "wget -O server.jar \"https://meta.fabricmc.net/v2/versions/loader/$mc/\$LOADER/\$INSTALLER/server/jar\"",
            "mkdir -p mods"
        )
        "Forge" -> listOf(
            "FV=\$($c https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json | jq -r '.promos[\"$mc-recommended\"] // .promos[\"$mc-latest\"] // empty')",
            "[ -n \"\$FV\" ] || { echo 'No Forge build found for $mc'; exit 1; }",
            "wget -O installer.jar \"https://maven.minecraftforge.net/net/minecraftforge/forge/$mc-\$FV/forge-$mc-\$FV-installer.jar\"",
            "java -jar installer.jar --installServer",
            "rm -f installer.jar installer.jar.log",
            "mkdir -p mods"
        )
        else -> listOf(
            "VURL=\$($c https://piston-meta.mojang.com/mc/game/version_manifest_v2.json | jq -r '.versions[]|select(.id==\"$mc\")|.url')",
            "wget -O server.jar \"\$($c \$VURL | jq -r .downloads.server.url)\""
        )
    }
    val jvmFlags = "-Xms512M -Xmx${ramGb}G -XX:+UseSerialGC -XX:MaxMetaspaceSize=256M"
    val launch = if (type == "Forge") listOf(
        "echo '$jvmFlags' > user_jvm_args.txt",
        "printf '%s\\n' '#!$PFX/bin/bash' 'cd ~/server' 'termux-wake-lock' " +
                "'exec java @user_jvm_args.txt @\$(ls libraries/net/minecraftforge/forge/*/unix_args.txt) nogui' > start.sh"
    ) else listOf(
        "printf '%s\\n' '#!$PFX/bin/bash' 'cd ~/server' 'termux-wake-lock' " +
                "'exec java $jvmFlags -jar server.jar nogui' > start.sh"
    )

    val apt = "apt-get -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold"
    val gh = "https://github.com/playit-cloud/playit-agent/releases/latest/download"
    val playit = listOf(
        "mkdir -p $PB && cd $PB",
        "wget -O playit.new $gh/playit-linux-aarch64 && mv -f playit.new playit",
        "wget -O playit-cli.new $gh/playit-cli-linux-aarch64 && mv -f playit-cli.new playit-cli",
        "chmod +x playit playit-cli"
    )
    val lines = listOf(
        "set -e",
        "trap 'echo SETUP_FAILED:$token' ERR",
        "export DEBIAN_FRONTEND=noninteractive",
        "termux-wake-lock || true",
        "mkdir -p $HOME/anmc",
        progress(token, 3, "Updating package lists"),
        "$apt update",
        progress(token, 8, "Installing console bridge"),
        "$apt install socat",
        BRIDGE,
        progress(token, 12, "Updating Termux packages (slowest step)"),
        "echo 'Updating Termux packages (this can take a few minutes)...'",
        "$apt upgrade",
        progress(token, 45, "Installing Java and tools"),
        "$apt install $java proot tmux wget curl jq",
        progress(token, 65, "Downloading playit"),
        "echo 'Downloading playit...'"
    ) + playit + listOf(
        progress(token, 78, "Downloading $type server"),
        "mkdir -p ~/server && cd ~/server"
    ) + download + listOf(
        progress(token, 95, "Finishing setup"),
        "echo 'eula=true' > eula.txt"
    ) + launch + listOf(
        "echo '$type|$mc' > anmc-info.txt",
        "echo 'POCKETHOST_INFO:$type|$mc'",
        "chmod +x start.sh",
        progress(token, 100, "Done"),
        "echo SETUP_DONE:$token"
    )
    return lines.joinToString("\n")
}

// Writes a small helper script file inside Termux
fun writeFile(path: String, vararg lines: String): String =
    "printf '%s\\n' " + lines.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" } +
            " > $path; chmod +x $path"

// Helper scripts for playit, rewritten on every start so they stay current
fun wrappers(): String = listOf(
    writeFile("$HOME/anmc/playitd.sh",
        "#!$PFX/bin/bash",
        "mkdir -p $HOME/.config/playit_gg",
        "cd $PB",
        "exec $PROOT ./playit --socket-path $SOCK --secret-path $HOME/.config/playit_gg/playit.toml"),
    writeFile("$HOME/anmc/pcli.sh",
        "#!$PFX/bin/bash",
        "cd $PB",
        "exec $PROOT ./playit-cli --socket-path $SOCK \"\$@\""),
    writeFile("$HOME/anmc/link.sh",
        "#!$PFX/bin/bash",
        "for i in \$(seq 1 40); do [ -S $SOCK ] && break; sleep 1; done",
        "[ -S $SOCK ] || { echo 'Playit daemon did not create its socket at $SOCK; check earlier [playit] log lines.'; exit 1; }",
        "[ \"\$1\" = reset ] && $HOME/anmc/pcli.sh reset",
        "exec $HOME/anmc/pcli.sh setup")
).joinToString("\n")

private val startServerLine =
    "tmux has-session -t mc 2>/dev/null || { tmux new-session -d -s mc 'sleep 1; exec $HOME/server/start.sh' " +
            "&& tmux pipe-pane -t mc 'cat >> $LOG'; }"

private val startPlayitLine =
    "tmux has-session -t playit 2>/dev/null || { rm -f $SOCK; " +
            "tmux new-session -d -s playit 'sleep 1; exec $HOME/anmc/playitd.sh' " +
            "&& tmux pipe-pane -t playit \"$PLAYIT_TAG >> $LOG\"; }"

private fun linkLine(arg: String) =
    "tmux kill-session -t pcli 2>/dev/null; " +
            "tmux new-session -d -s pcli 'sleep 1; exec $HOME/anmc/link.sh $arg' " +
            "&& tmux pipe-pane -t pcli \"$PLAYIT_TAG >> $LOG\""

private val notInstalledCheck =
    "[ ! -x $HOME/server/start.sh ] || [ ! -x $PB/playit ]"

// Checks installation and runtime status when PocketHost opens, so the buttons are right after a relaunch
fun checkServerStatusCmd(token: String): String = logged(listOf(
    ENSURE_SOCAT,
    BRIDGE,
    "if [ -x $HOME/server/start.sh ] || [ -f $HOME/server/server.jar ] || [ -d $HOME/server/libraries/net/minecraftforge/forge ]; then echo SERVER_PRESENT:$token; fi",
    "if [ -x $HOME/server/start.sh ] && [ -x $PB/playit ] && [ -x $PB/playit-cli ]; then echo SERVER_INSTALLED:$token; else echo SERVER_NOT_INSTALLED:$token; fi",
    // Tell the app which server type/version is installed (used by the Mods tab)
    "if [ -f $HOME/server/anmc-info.txt ]; then echo \"POCKETHOST_INFO:\$(cat $HOME/server/anmc-info.txt)\"; " +
            "elif [ -d $HOME/server/libraries/net/minecraftforge/forge ]; then echo 'POCKETHOST_INFO:Forge|'; " +
            "elif [ -d $HOME/server/mods ]; then echo 'POCKETHOST_INFO:Fabric|'; " +
            "elif [ -d $HOME/server/plugins ]; then echo 'POCKETHOST_INFO:Paper|'; " +
            "elif [ -f $HOME/server/server.jar ]; then echo 'POCKETHOST_INFO:Vanilla|'; fi",
    "if tmux has-session -t mc 2>/dev/null; then echo SERVER_RUNNING:$token; else echo SERVER_STOPPED:$token; fi"
).joinToString("\n"))

// Everything in one go: log stream, server, playit daemon, and account linking if it's the first time
fun startAllCmd(token: String): String = listOf(
    ENV + "mkdir -p $HOME/anmc; touch $LOG",
    ENSURE_SOCAT,
    BRIDGE,
    wrappers(),
    "if $notInstalledCheck; then echo POCKETHOST_NOT_INSTALLED:$token >> $LOG; exit 0; fi",
    "if tmux has-session -t mc 2>/dev/null; then echo SERVER_RUNNING:$token >> $LOG; else echo SERVER_START_REQUESTED:$token >> $LOG; fi",
    startServerLine,
    "if [ -f $HOME/anmc/playit-unlinked ]; then echo 'Playit is intentionally unlinked. Tap Relink playit to connect it again.' >> $LOG; else",
    startPlayitLine,
    "if [ ! -s $HOME/.config/playit_gg/playit.toml ]; then ${linkLine("")}; fi",
    "fi"
).joinToString("\n")

// Resets the saved playit account and gives a fresh link
fun relinkCmd(): String = listOf(
    ENV + "mkdir -p $HOME/anmc $HOME/.config/playit_gg; touch $LOG",
    ENSURE_SOCAT,
    BRIDGE,
    wrappers(),
    "if [ ! -x $PB/playit ] || [ ! -x $PB/playit-cli ]; then echo 'Playit binaries are missing. Tap Install / change to reinstall them.' >> $LOG; exit 1; fi",
    // Remove stale sessions so the relink flow always starts a fresh daemon and socket.
    "tmux kill-session -t pcli 2>/dev/null || true",
    "tmux kill-session -t playit 2>/dev/null || true",
    "pkill -x playit 2>/dev/null || true",
    "rm -f $SOCK",
    "rm -f $HOME/anmc/playit-unlinked",
    startPlayitLine,
    linkLine("reset")
).joinToString("\n")

// Disconnects this phone's Playit agent without stopping the Minecraft server.
// Delete the agent/tunnels on the Playit dashboard separately if you want them gone.
fun unlinkPlayitCmd(): String = listOf(
    ENV + "mkdir -p $HOME/anmc; touch $LOG",
    "tmux kill-session -t pcli 2>/dev/null || true",
    "tmux kill-session -t playit 2>/dev/null || true",
    "pkill -x playit 2>/dev/null || true",
    "rm -f $SOCK",
    "rm -f $HOME/.config/playit_gg/playit.toml",
    "touch $HOME/anmc/playit-unlinked",
    "echo 'Playit agent unlinked on this phone. The Minecraft server is not affected. To remove the agent/tunnels from your account, use the Playit dashboard.' >> $LOG"
).joinToString("\n")

// Saves the world, waits for the server to exit, then stops playit
fun stopAllCmd(): String = listOf(
    ENV + "tmux send-keys -t mc stop Enter",
    "for i in \$(seq 1 90); do tmux has-session -t mc 2>/dev/null || break; sleep 1; done",
    "tmux kill-session -t playit 2>/dev/null",
    "tmux kill-session -t pcli 2>/dev/null",
    "pkill -x playit",
    "termux-wake-unlock || true",
    "echo 'Server and playit stopped.' >> $LOG"
).joinToString("\n")

// ---------- Backup scripts ----------

// Finds the world folders (level-name, plus _nether and _the_end) into the DIRS array
private fun worldDirsLines(token: String): List<String> = listOf(
    "cd \"$HOME/server\" 2>/dev/null || { echo 'POCKETHOST_OP:$token:BK_ERR:Server is not installed.'; exit 0; }",
    "LN=\$(grep -E '^level-name=' server.properties 2>/dev/null | head -n1 | cut -d= -f2-)",
    "[ -n \"\$LN\" ] || LN=world",
    "case \"\$LN\" in */*|..*) echo 'POCKETHOST_OP:$token:BK_ERR:Unsupported level-name in server.properties.'; exit 0 ;; esac",
    "DIRS=(); for d in \"\$LN\" \"\${LN}_nether\" \"\${LN}_the_end\"; do if [ -d \"\$d\" ]; then DIRS+=(\"\$d\"); fi; done"
)

private fun listBackupsLines(token: String): List<String> = listOf(
    "mkdir -p \"$HOME/anmc/backups\"",
    "for f in \"$HOME/anmc/backups\"/*.tar.gz; do if [ -f \"\$f\" ]; then echo \"POCKETHOST_OP:$token:BK_ITEM:\$(basename \"\$f\")|\$(du -k \"\$f\" | cut -f1)\"; fi; done",
    "echo 'POCKETHOST_OP:$token:BK_END:ok'"
)

fun listBackupsCmd(token: String): String = logged(listBackupsLines(token).joinToString("\n"))

fun deleteBackupCmd(token: String, name: String): String = logged(
    (listOf("rm -f -- ${shellQuote("$HOME/anmc/backups/$name")}") + listBackupsLines(token)).joinToString("\n")
)

fun backupCmd(token: String): String = logged(
    (worldDirsLines(token) + listOf(
        "if [ \${#DIRS[@]} -eq 0 ]; then echo 'POCKETHOST_OP:$token:BK_ERR:No world folder yet. Start the server once first.'; exit 0; fi",
        "mkdir -p \"$HOME/anmc/backups\"",
        "OUT=\"$HOME/anmc/backups/world-\$(date +%Y%m%d-%H%M%S).tar.gz\"",
        // Pause world saving while the archive is made, so the copy is consistent
        "RUN=0; if tmux has-session -t mc 2>/dev/null; then RUN=1; tmux send-keys -t mc -l 'save-off'; tmux send-keys -t mc Enter; sleep 1; tmux send-keys -t mc -l 'save-all flush'; tmux send-keys -t mc Enter; sleep 8; fi",
        "if tar -czf \"\$OUT\" \"\${DIRS[@]}\"; then R=ok; else R=fail; rm -f \"\$OUT\"; fi",
        "if [ \$RUN = 1 ]; then tmux send-keys -t mc -l 'save-on'; tmux send-keys -t mc Enter; fi",
        // Optional copy to phone storage if the user ran termux-setup-storage
        "EXP=0; if [ \$R = ok ] && [ -d \"$HOME/storage/shared\" ]; then mkdir -p \"$HOME/storage/shared/PocketHost-Backups\" && cp \"\$OUT\" \"$HOME/storage/shared/PocketHost-Backups/\" && EXP=1; fi",
        "if [ \$R = ok ]; then echo \"POCKETHOST_OP:$token:BK_DONE:\$(basename \"\$OUT\")|\$EXP\"; else echo 'POCKETHOST_OP:$token:BK_ERR:Backup failed. Check free storage.'; fi"
    )).joinToString("\n")
)

fun restoreBackupCmd(token: String, name: String): String = logged(
    (worldDirsLines(token) + listOf(
        "F=${shellQuote("$HOME/anmc/backups/$name")}",
        "[ -f \"\$F\" ] || { echo 'POCKETHOST_OP:$token:BK_ERR:Backup file not found.'; exit 0; }",
        "if tmux has-session -t mc 2>/dev/null; then echo 'POCKETHOST_OP:$token:BK_ERR:Stop the server before restoring.'; exit 0; fi",
        "tar -tzf \"\$F\" >/dev/null 2>&1 || { echo 'POCKETHOST_OP:$token:BK_ERR:That backup file is damaged.'; exit 0; }",
        // Keep the current world as a safety copy, then replace it
        "if [ \${#DIRS[@]} -gt 0 ]; then tar -czf \"$HOME/anmc/backups/pre-restore-\$(date +%Y%m%d-%H%M%S).tar.gz\" \"\${DIRS[@]}\" && rm -rf -- \"\${DIRS[@]}\" || { echo 'POCKETHOST_OP:$token:BK_ERR:Could not save the current world first, so nothing was changed.'; exit 0; }; fi",
        "if tar -xzf \"\$F\"; then echo 'POCKETHOST_OP:$token:BK_DONE:restored|0'; else echo 'POCKETHOST_OP:$token:BK_ERR:Restore failed part-way. Your previous world is in the newest pre-restore backup.'; fi"
    )).joinToString("\n")
)

// ---------- Mod / plugin scripts ----------

private fun listModsLines(dir: String, token: String): List<String> = listOf(
    "mkdir -p \"$HOME/server/$dir\"",
    "for f in \"$HOME/server/$dir\"/*.jar; do if [ -f \"\$f\" ]; then echo \"POCKETHOST_OP:$token:MODFILE:\$(basename \"\$f\")|\$(du -k \"\$f\" | cut -f1)\"; fi; done",
    "echo 'POCKETHOST_OP:$token:MODS_END:ok'"
)

fun listModsCmd(dir: String, token: String): String = logged(listModsLines(dir, token).joinToString("\n"))

fun removeModCmd(dir: String, name: String, token: String): String = logged(
    (listOf("rm -f -- ${shellQuote("$HOME/server/$dir/$name")}") + listModsLines(dir, token)).joinToString("\n")
)

// Downloads each file inside Termux straight into the server's mods/plugins folder, verifying the hash when known
private fun installFilesCmd(dir: String, files: List<DownloadFile>, token: String): String {
    val script = mutableListOf(
        "mkdir -p \"$HOME/server/$dir\"",
        "cd \"$HOME/server/$dir\" || { echo 'POCKETHOST_OP:$token:MODINSTALL_ERR:Server folder not found.'; exit 0; }",
        "OK=0; FAIL=\"\""
    )
    for (f in files) {
        val part = shellQuote(f.name + ".part")
        val final = shellQuote(f.name)
        val check = if (f.hash.isNotBlank())
            "echo ${shellQuote(f.hash + "  " + f.name + ".part")} | ${f.hashAlgo}sum -c - >/dev/null 2>&1"
        else "true"
        script.add(
            "if curl -fsSL --retry 2 -A anmc-app -o $part ${shellQuote(f.url)} && $check && mv -f $part $final; " +
                    "then OK=\$((OK+1)); else rm -f $part; FAIL=\"\$FAIL ${f.name.replace("\"", "")}\"; fi"
        )
    }
    script.add(
        "if [ -z \"\$FAIL\" ]; then echo \"POCKETHOST_OP:$token:MODINSTALL_DONE:\$OK\"; " +
                "else echo \"POCKETHOST_OP:$token:MODINSTALL_ERR:Download or verification failed for:\$FAIL\"; fi"
    )
    return logged(script.joinToString("\n"))
}

// Types the command into the server's tmux console (-l sends it literally, so names like "Enter" are safe)
fun sendToServer(context: Context, cmd: String) {
    runInTermux(
        context,
        ENV + "tmux send-keys -t mc -l ${shellQuote(cmd)}; tmux send-keys -t mc Enter",
        background = true
    )
}

fun runInTermux(context: Context, script: String, background: Boolean) {
    try {
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "$PFX/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
        }
        context.startForegroundService(intent)
    } catch (e: Exception) {
        android.util.Log.e("pocket", "Termux failed", e)
        Toast.makeText(
            context,
            "Couldn't reach Termux. Check Termux access and the 'Run commands in Termux' permission.",
            Toast.LENGTH_LONG
        ).show()
    }
}
