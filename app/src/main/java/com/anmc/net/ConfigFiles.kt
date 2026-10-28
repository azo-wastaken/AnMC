package com.anmc.net

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

// ============================================================
//  ConfigFiles.kt - lets the app list, read, edit and restore the settings
//  files that mods and plugins create (.yml, .json, .toml, .properties ...).
//  Plain Kotlin (plus the built-in org.json), so it is easy to test.
//
//  Files travel as Base64 so the exact text survives the trip through
//  the console log (blank lines, indentation, odd characters).
// ============================================================

/** Largest file the in-app editor opens. (One shell argument can't be much longer than 128 KB.) */
const val MAX_CONFIG_BYTES = 88_000L

val CONFIG_EXTENSIONS = listOf("yml", "yaml", "json", "json5", "toml", "properties", "txt", "conf", "cfg", "ini")

data class ConfigFile(val path: String, val bytes: Long)

data class DecodedConfig(val text: String, val crlf: Boolean)

// A path segment: letters, digits and . _ @ + - ; spaces are allowed inside a name but not at its ends
private val segmentRegex = Regex("^[A-Za-z0-9._@+-]+( [A-Za-z0-9._@+-]+)*$")

/** True when a path (relative to the server folder) is safe to put in a shell script. */
fun validConfigPath(path: String): Boolean {
    if (path.isEmpty() || path.length > 200 || path.startsWith("/") || path.endsWith("/")) return false
    if (CONFIG_EXTENSIONS.none { path.endsWith(".$it", ignoreCase = true) }) return false
    return path.split("/").all { it != "." && it != ".." && !it.startsWith("-") && segmentRegex.matches(it) }
}

/** Folders that hold settings files for each kind of server. */
fun configRootsFor(type: String): List<String> = when (type) {
    "Paper" -> listOf("plugins", "config")
    "Fabric" -> listOf("config")
    "Forge" -> listOf("config", "defaultconfigs")
    "PowerNukkitX" -> listOf("plugins")
    else -> emptyList()
}

fun configAvailableFor(type: String): Boolean = configRootsFor(type).isNotEmpty()

// Top-level files we do not list (the app manages them, or they are just noise)
private fun topLevelDeny(type: String): List<String> =
    listOf("usercache.json", "eula.txt", "user_jvm_args.txt", "version_history.json", "anmc-info.txt") +
        (if (type == "PowerNukkitX") emptyList() else listOf("server.properties"))

/** Groups files in the list: top-level files together, plugins by plugin name, the rest by folder. */
fun configGroup(path: String): String {
    val parts = path.split("/")
    return when {
        parts.size == 1 -> "Server files"
        parts[0] == "plugins" && parts.size >= 3 -> "plugins/${parts[1]}"
        else -> parts[0]
    }
}

// ---------- Text <-> Base64 ----------

/** Decodes a file sent as Base64. Returns null if it is not plain UTF-8 text. */
fun decodeConfig(b64: String): DecodedConfig? {
    val bytes = try { Base64.getDecoder().decode(b64) } catch (e: IllegalArgumentException) { return null }
    if (bytes.any { it.toInt() == 0 }) return null
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    var text = try { decoder.decode(ByteBuffer.wrap(bytes)).toString() } catch (e: CharacterCodingException) { return null }
    val crlf = text.contains("\r\n")
    if (crlf) text = text.replace("\r\n", "\n")
    return DecodedConfig(text, crlf)
}

/** Turns edited text back into Base64, keeping Windows line endings if the file had them. */
fun encodeConfig(text: String, crlf: Boolean): String {
    val t = if (crlf) text.replace("\r\n", "\n").replace("\n", "\r\n") else text
    return Base64.getEncoder().encodeToString(t.toByteArray(Charsets.UTF_8))
}

/** A friendly warning if the text looks broken for its file type, or null. The user can still save. */
fun configWarning(path: String, text: String): String? {
    val lower = path.lowercase()
    if (lower.endsWith(".json")) {
        val t = text.trim()
        if (t.isEmpty()) return "The file is empty, which is not valid JSON."
        try {
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
        } catch (e: Exception) {
            return "This doesn't look like valid JSON (${e.message ?: "parse error"}). The mod may fail to load it."
        }
    }
    if ((lower.endsWith(".yml") || lower.endsWith(".yaml")) && text.lineSequence().any { Regex("^ *\t").containsMatchIn(it) }) {
        return "YAML files can't be indented with tab characters. Use spaces, or the plugin may fail to load it."
    }
    return null
}

// ---------- Scripts ----------
private fun err(token: String, text: String) = "echo 'ANMC_X:CFG_ERR:$token:$text'"

private fun flatName(path: String): String = path.replace("/", "__")

private fun configBackupDir(id: String): String = "${backupDir(id)}/configs"

// Lines that refuse to continue unless the file exists inside the server folder (symbolic links can't escape it)
private fun guardLines(token: String, id: String, path: String): List<String> = listOf(
    "D=${shellQuote(serverDir(id))}",
    "F=${shellQuote("${serverDir(id)}/$path")}",
    "if [ ! -f \"\$F\" ]; then ${err(token, "That file no longer exists. Refresh the list.")}; exit 0; fi",
    "REAL=\$(readlink -f \"\$F\" 2>/dev/null); ROOT=\$(readlink -f \"\$D\" 2>/dev/null)",
    "case \"\$REAL\" in \"\$ROOT\"/*) ;; *) ${err(token, "Refusing to open a file outside the server folder.")}; exit 0 ;; esac"
)

// Lines that send the file to the app
private fun sendFileLines(token: String, path: String): List<String> = listOf(
    "SIZE=\$(stat -c %s \"\$F\" 2>/dev/null || echo 0)",
    "if [ \"\$SIZE\" -gt $MAX_CONFIG_BYTES ]; then ${err(token, "Too large to edit here (over ${MAX_CONFIG_BYTES / 1000} KB). Edit it in Termux with: nano ~/${path}")}; exit 0; fi",
    "printf 'ANMC_X:CFGDATA:%s:' '$token'; base64 -w0 \"\$F\"; echo",
    "echo 'ANMC_X:CFG_END:$token:'"
)

/** Lists the settings files for a server. */
fun listConfigsCmd(token: String, id: String, type: String): String {
    val d = serverDir(id)
    val names = CONFIG_EXTENSIONS.joinToString(" -o ") { "-iname '*.$it'" }
    val deny = topLevelDeny(type).joinToString(" ") { "! -name '$it'" }
    val roots = configRootsFor(type)
    val script = listOf(
        "cd ${shellQuote(d)} 2>/dev/null || { ${err(token, "Server folder not found.")}; exit 0; }",
        "echo 'ANMC_X:CFG_RESET:$token:'",
        "{",
        "  find . -maxdepth 1 -type f \\( $names \\) $deny",
        roots.joinToString("\n") { "  [ -d ${shellQuote(it)} ] && find ${shellQuote(it)} -maxdepth 4 -type f \\( $names \\)" },
        "} 2>/dev/null | sed 's#^\\./##' | sort | head -n 400 | while IFS= read -r p; do",
        "  echo \"ANMC_X:CFGFILE:$token:\$p|\$(stat -c %s \"\$p\" 2>/dev/null || echo 0)\"",
        "done",
        "echo 'ANMC_X:CFG_LIST_END:$token:'"
    ).joinToString("\n")
    return logged(script)
}

/** Sends one file to the app. */
fun readConfigCmd(token: String, id: String, path: String): String {
    if (!validConfigPath(path)) return logged(err(token, "That file name is not supported."))
    return logged((guardLines(token, id, path) + sendFileLines(token, path)).joinToString("\n"))
}

/** Replaces a file's content. A timestamped copy of the old file is kept (the newest 20 per file). */
fun saveConfigCmd(token: String, id: String, path: String, b64: String): String {
    if (!validConfigPath(path)) return logged(err(token, "That file name is not supported."))
    if (!Regex("^[A-Za-z0-9+/=]*$").matches(b64) || b64.length > 120_000) return logged(err(token, "The file is too large to save from here."))
    val bk = shellQuote(configBackupDir(id))
    val flat = shellQuote(flatName(path))
    val script = guardLines(token, id, path) + listOf(
        "TMP=\"\$HOME/anmc/config.tmp\"",
        "if ! printf '%s' '$b64' | base64 -d > \"\$TMP\" 2>/dev/null; then rm -f \"\$TMP\"; ${err(token, "Could not decode the new content. Nothing was changed.")}; exit 0; fi",
        "BK=$bk; FLAT=$flat; mkdir -p \"\$BK\"",
        "if ! cp \"\$F\" \"\$BK/\$FLAT.\$(date +%Y%m%d-%H%M%S-%N)\"; then rm -f \"\$TMP\"; ${err(token, "Could not make a backup copy first, so nothing was changed.")}; exit 0; fi",
        "ls -1t \"\$BK/\$FLAT\".[0-9]* 2>/dev/null | tail -n +21 | while IFS= read -r old; do rm -f -- \"\$old\"; done",
        "if cat \"\$TMP\" > \"\$F\"; then echo 'ANMC_X:CFG_SAVED:$token:'; else ${err(token, "Could not write the file.")}; fi",
        "rm -f \"\$TMP\""
    )
    return logged(script.joinToString("\n"))
}

/** Puts back the newest earlier version that differs from the current file, then sends it to the app. */
fun restoreConfigCmd(token: String, id: String, path: String): String {
    if (!validConfigPath(path)) return logged(err(token, "That file name is not supported."))
    val bk = shellQuote(configBackupDir(id))
    val flat = shellQuote(flatName(path))
    val script = guardLines(token, id, path) + listOf(
        "BK=$bk; FLAT=$flat; mkdir -p \"\$BK\"",
        "CUR=\$(md5sum < \"\$F\" | cut -d' ' -f1); FOUND=''",
        "while IFS= read -r b; do M=\$(md5sum < \"\$b\" | cut -d' ' -f1); if [ \"\$M\" != \"\$CUR\" ]; then FOUND=\$b; break; fi; done < <(ls -1t \"\$BK/\$FLAT\".[0-9]* 2>/dev/null)",
        "if [ -z \"\$FOUND\" ]; then ${err(token, "There is no earlier version to restore.")}; exit 0; fi",
        "cp \"\$F\" \"\$BK/\$FLAT.\$(date +%Y%m%d-%H%M%S-%N)\"",
        "if cat \"\$FOUND\" > \"\$F\"; then echo 'ANMC_X:CFG_RESTORED:$token:'; else ${err(token, "Could not write the file.")}; exit 0; fi"
    ) + sendFileLines(token, path)
    return logged(script.joinToString("\n"))
}
