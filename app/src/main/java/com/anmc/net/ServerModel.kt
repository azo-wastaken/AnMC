package com.anmc.net

import java.net.URLDecoder
import java.net.URLEncoder

// ============================================================
//  ServerModel.kt - what a "server" is, how it is saved, how it is checked.
//  Plain Kotlin (no Android), so it is easy to test.
// ============================================================

const val JAVA = "Java"
const val BEDROCK = "Bedrock"
const val LEGACY_ID = "legacy"   // a server made by an older version of the app, stored in ~/server

val JAVA_TYPES = listOf("Paper", "Fabric", "Forge", "Vanilla")
val BEDROCK_TYPES = listOf("PowerNukkitX")

data class ServerConfig(
    val id: String,
    val name: String,
    val edition: String,      // JAVA or BEDROCK
    val type: String,         // Paper / Fabric / Forge / Vanilla / PowerNukkitX
    val version: String,      // Minecraft version for Java, "latest" for Bedrock
    val ramGb: Int,
    val port: Int,
    val eula: Boolean,
    val address: String = ""  // the public address friends use (from playit)
) {
    val isJava: Boolean get() = edition == JAVA
    val summary: String get() = if (isJava) "$type $version" else type
}

fun typesFor(edition: String): List<String> = if (edition == BEDROCK) BEDROCK_TYPES else JAVA_TYPES

fun defaultPort(edition: String): Int = if (edition == BEDROCK) 19132 else 25565

fun newServerId(): String = "s" + java.lang.Long.toString(System.currentTimeMillis(), 36)

fun validId(id: String): Boolean = Regex("^[a-z0-9]{2,24}$").matches(id)

fun newServer(edition: String): ServerConfig = ServerConfig(
    id = newServerId(),
    name = "",
    edition = edition,
    type = typesFor(edition).first(),
    version = if (edition == JAVA) "1.21.1" else "latest",
    ramGb = 2,
    port = defaultPort(edition),
    eula = false
)

// Returns an error message, or null when the server settings are fine
fun validateServer(s: ServerConfig): String? {
    if (!validId(s.id)) return "Internal error: bad server id."
    val n = s.name.trim()
    if (n.isEmpty()) return "Give the server a name."
    if (n.length > 40) return "The name must be 40 characters or fewer."
    if (s.edition != JAVA && s.edition != BEDROCK) return "Pick Java or Bedrock."
    if (s.type !in typesFor(s.edition)) return "Pick a server type."
    if (s.isJava && javaPackageFor(s.version) == null) return "Minecraft version must look like 1.21.1 or 26.1 (1.17 or newer)."
    if (s.ramGb !in 1..8) return "RAM must be between 1 and 8 GB."
    if (s.port !in 1024..65535) return "Port must be between 1024 and 65535."
    return null
}

// ---------- Saving (a simple text format, no extra libraries) ----------
private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
private fun dec(s: String): String = URLDecoder.decode(s, "UTF-8")

fun encodeServers(list: List<ServerConfig>): String = list.joinToString(";") { s ->
    listOf(s.id, s.name, s.edition, s.type, s.version, s.ramGb.toString(), s.port.toString(), s.eula.toString(), s.address)
        .joinToString(",") { enc(it) }
}

fun decodeServers(text: String): List<ServerConfig> =
    text.split(";").filter { it.isNotBlank() }.mapNotNull { record ->
        try {
            val f = record.split(",").map { dec(it) }
            if (f.size < 9) null
            else ServerConfig(f[0], f[1], f[2], f[3], f[4], f[5].toInt(), f[6].toInt(), f[7].toBoolean(), f[8])
                .takeIf { validId(it.id) }
        } catch (e: Exception) {
            null
        }
    }
