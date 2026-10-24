package com.anmc.net

// ============================================================
//  Properties.kt - the server.properties settings the app can edit
//  (Java and Bedrock), plus the scripts that read and write them.
// ============================================================

data class BooleanServerProperty(
    val group: String,
    val label: String,
    val key: String,
    val defaultValue: Boolean
)

data class TextServerProperty(
    val label: String,
    val key: String,
    val defaultValue: String,
    val numeric: Boolean = false
)

/** A setting with a fixed list of allowed values, shown as a row of buttons. */
data class ChoiceProperty(
    val label: String,
    val key: String,
    val defaultValue: String,
    val options: List<String>,
    val help: String = ""
)

// ---------------- Java ----------------
val booleanServerProperties = listOf(
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

val textServerProperties = listOf(
    TextServerProperty("Message of the day (MOTD)", "motd", "A Minecraft Server"),
    TextServerProperty("Default game mode", "gamemode", "survival"),
    TextServerProperty("Difficulty", "difficulty", "easy"),
    TextServerProperty("Maximum players", "max-players", "20", numeric = true),
    TextServerProperty("View distance", "view-distance", "10", numeric = true),
    TextServerProperty("Simulation distance", "simulation-distance", "10", numeric = true),
    TextServerProperty("Spawn protection radius", "spawn-protection", "16", numeric = true),
    TextServerProperty("Maximum world size", "max-world-size", "29999984", numeric = true)
)

val serverPropertyDefaults: Map<String, String> =
    booleanServerProperties.associate { it.key to it.defaultValue.toString() } +
        textServerProperties.associate { it.key to it.defaultValue }

// ---------------- Bedrock (PowerNukkitX) ----------------
// The port is edited in the server editor, not here (for both editions).
val bedrockChoiceProperties = listOf(
    ChoiceProperty(
        "Network transport", "transport", "raknet", listOf("raknet", "nethernet"),
        "playit's free Bedrock tunnel only works with raknet."
    ),
    ChoiceProperty("Game mode", "gamemode", "0", listOf("0", "1", "2", "3"), "0 survival, 1 creative, 2 adventure, 3 spectator"),
    ChoiceProperty("Difficulty", "difficulty", "1", listOf("0", "1", "2", "3"), "0 peaceful, 1 easy, 2 normal, 3 hard"),
    ChoiceProperty("Player vs player", "pvp", "on", listOf("on", "off")),
    ChoiceProperty("Whitelist", "white-list", "off", listOf("on", "off")),
    ChoiceProperty("Xbox sign-in required", "xbox-auth", "on", listOf("on", "off"), "Turn off only if you understand the risk.")
)

val bedrockTextProperties = listOf(
    TextServerProperty("Message of the day (MOTD)", "motd", "PowerNukkitX Server"),
    TextServerProperty("Maximum players", "max-players", "20", numeric = true),
    TextServerProperty("View distance", "view-distance", "12", numeric = true)
)

val bedrockPropertyDefaults: Map<String, String> =
    bedrockChoiceProperties.associate { it.key to it.defaultValue } +
        bedrockTextProperties.associate { it.key to it.defaultValue }

fun propertyDefaultsFor(edition: String): Map<String, String> =
    if (edition == BEDROCK) bedrockPropertyDefaults else serverPropertyDefaults

/**
 * Which Bedrock settings get written. We only touch settings that already exist in the file,
 * that the user changed, or that must be there (transport), so nothing else is disturbed.
 */
fun bedrockValuesToWrite(values: Map<String, String>, present: Set<String>): Map<String, String> =
    values.filter { (k, v) -> k in bedrockPropertyDefaults && (k in present || v != bedrockPropertyDefaults[k] || k == "transport") }

private val keyRegex = Regex("^[A-Za-z0-9_.-]{1,64}$")

// ---------------- Scripts ----------------
fun loadPropsCmd(id: String, token: String, keys: Collection<String>): String {
    val safeKeys = keys.filter { keyRegex.matches(it) }
    val keyPatterns = safeKeys.joinToString("|")
    val script = listOf(
        "if [ ! -f ${shellQuote("${serverDir(id)}/start.sh")} ]; then echo 'ANMC_X:PROP_ERROR:$token:Server is not installed yet.'; exit 0; fi",
        "file=${shellQuote("${serverDir(id)}/server.properties")}",
        "if [ -f \"\$file\" ]; then",
        "  while IFS= read -r line || [ -n \"\$line\" ]; do",
        "    case \"\$line\" in ''|'#'*) continue ;; esac",
        "    case \"\$line\" in *=*) key=\"\${line%%=*}\"; value=\"\${line#*=}\" ;; *) continue ;; esac",
        "    case \"\$key\" in $keyPatterns) printf 'ANMC_X:PROP:$token:%s=%s\\n' \"\$key\" \"\$value\" ;; esac",
        "  done < \"\$file\"",
        "fi",
        "echo 'ANMC_X:PROPS_LOADED:$token:'"
    ).joinToString("\n")
    return logged(script)
}

/** Writes exactly the given settings (adding any that are missing) after making a timestamped backup. */
fun savePropsCmd(id: String, values: Map<String, String>, token: String): String {
    val script = mutableListOf(
        "set -e",
        "trap 'echo \"ANMC_X:PROP_ERROR:$token:Saving failed; check the console log.\"' ERR",
        "if [ ! -f ${shellQuote("${serverDir(id)}/start.sh")} ]; then echo 'ANMC_X:PROP_ERROR:$token:Server is not installed yet.'; exit 0; fi",
        "file=${shellQuote("${serverDir(id)}/server.properties")}",
        "[ -f \"\$file\" ] || : > \"\$file\"",
        "cp \"\$file\" \"\$file.bak.\$(date +%Y%m%d-%H%M%S)\"",
        "set_prop() {",
        "  key=\"\$1\"; value=\"\$2\"; tmp=\"\$file.tmp\"",
        // ENVIRON keeps backslashes (like the section sign in a MOTD) exactly as typed
        "  PK=\"\$key\" PV=\"\$value\" awk 'BEGIN { k=ENVIRON[\"PK\"]; v=ENVIRON[\"PV\"]; found=0 } /^[[:space:]]*#/ { print; next } " +
            "{ p=index(\$0, \"=\"); if (p > 0 && substr(\$0, 1, p-1) == k) { if (!found) print k \"=\" v; found=1; next } print } " +
            "END { if (!found) print k \"=\" v }' \"\$file\" > \"\$tmp\"",
        "  mv \"\$tmp\" \"\$file\"",
        "}"
    )
    for ((key, value) in values) {
        if (!keyRegex.matches(key)) continue
        script.add("set_prop ${shellQuote(key)} ${shellQuote(value.replace("\r", " ").replace("\n", " "))}")
    }
    script.add("echo 'ANMC_X:PROP_SAVED:$token:'")
    return logged(script.joinToString("\n"))
}

// ---------------- Validation ----------------
private fun integerError(values: Map<String, String>, key: String, label: String, min: Int, max: Int): String? {
    val v = values[key]?.toIntOrNull() ?: return "$label must be a number."
    return if (v !in min..max) "$label must be between $min and $max." else null
}

private fun motdError(values: Map<String, String>): String? {
    val motd = values["motd"].orEmpty()
    if ('\n' in motd || '\r' in motd) return "MOTD must be a single line."
    if (motd.length > 256) return "MOTD must be 256 characters or fewer."
    return null
}

// Returns an error message, or null when everything is fine
fun validateServerProperties(values: Map<String, String>): String? {
    for (p in booleanServerProperties) {
        if (values[p.key] !in listOf("true", "false")) return "${p.label} must be on or off."
    }
    val mode = values["gamemode"].orEmpty().lowercase()
    if (mode !in setOf("survival", "creative", "adventure", "spectator", "0", "1", "2", "3")) {
        return "Game mode must be survival, creative, adventure, or spectator."
    }
    val difficulty = values["difficulty"].orEmpty().lowercase()
    if (difficulty !in setOf("peaceful", "easy", "normal", "hard", "0", "1", "2", "3")) {
        return "Difficulty must be peaceful, easy, normal, or hard."
    }
    integerError(values, "max-players", "Maximum players", 1, 100000)?.let { return it }
    integerError(values, "view-distance", "View distance", 2, 32)?.let { return it }
    integerError(values, "simulation-distance", "Simulation distance", 2, 32)?.let { return it }
    integerError(values, "spawn-protection", "Spawn protection radius", 0, 65535)?.let { return it }
    integerError(values, "max-world-size", "Maximum world size", 1, 29999984)?.let { return it }
    return motdError(values)
}

fun validateBedrockProperties(values: Map<String, String>): String? {
    for (p in bedrockChoiceProperties) {
        if (values[p.key] !in p.options) return "${p.label} must be one of: ${p.options.joinToString(", ")}."
    }
    integerError(values, "max-players", "Maximum players", 1, 1000)?.let { return it }
    integerError(values, "view-distance", "View distance", 2, 96)?.let { return it }
    return motdError(values)
}
