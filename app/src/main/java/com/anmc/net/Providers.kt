package com.anmc.net

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ============================================================
//  Providers.kt - finds mods/plugins on Modrinth and CurseForge and
//  works out which files to download. No Compose or Android UI here.
// ============================================================

class ModException(message: String) : Exception(message)

data class ModHit(
    val provider: String,        // "modrinth" or "curseforge"
    val id: String,
    val title: String,
    val author: String,
    val description: String,
    val downloads: Long,
    val pageUrl: String
)

data class Resolved(val files: List<ModFile>, val warnings: List<String>)

private const val UA = "PocketHost/1.0 (unofficial Android server host)"

// The network call can be swapped out, which is how the logic is tested
var httpGetter: (String, Map<String, String>) -> String = { url, headers -> realHttpGet(url, headers) }

fun realHttpGet(url: String, headers: Map<String, String>): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 25000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json")
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code == 401 || code == 403) throw ModException("The site refused the request (HTTP $code). Check your API key.")
        if (code == 429) throw ModException("Too many requests. Wait a minute and try again.")
        if (code !in 200..299) throw ModException("The site returned an error (HTTP $code).")
        return body
    } finally {
        conn.disconnect()
    }
}

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

// org.json returns the text "null" for JSON nulls with optString, so check isNull first
private fun JSONObject.str(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

private fun checkHost(url: String, allowed: Set<String>) {
    val host = try { URL(url).host.lowercase() } catch (e: Exception) { "" }
    val protocolOk = url.startsWith("https://")
    if (!protocolOk || allowed.none { host == it || host.endsWith(".$it") }) {
        throw ModException("Refusing to download from an unexpected address ($host).")
    }
}

fun friendlyError(e: Exception): String = when (e) {
    is ModException -> e.message ?: "Something went wrong."
    is java.net.UnknownHostException -> "No internet connection."
    is java.net.SocketTimeoutException -> "The site took too long to answer. Try again."
    else -> "Request failed: ${e.message ?: e.javaClass.simpleName}"
}

fun validMcVersion(v: String): Boolean = Regex("^[0-9]+\\.[0-9]+(\\.[0-9]+)?$").matches(v)

// ---------- Modrinth ----------
object Modrinth {
    private const val BASE = "https://api.modrinth.com/v2"
    private val HOSTS = setOf("cdn.modrinth.com")

    fun loaders(type: String): List<String> = when (type) {
        "Fabric" -> listOf("fabric")
        "Forge" -> listOf("forge")
        else -> listOf("paper", "spigot", "bukkit")      // Paper plugins
    }

    private fun jsonArray(items: List<String>) = items.joinToString(",", "[", "]") { "\"$it\"" }

    fun search(query: String, type: String, mc: String): List<ModHit> {
        // Outer list = AND, inner list = OR
        val facets = "[" + jsonArray(loaders(type).map { "categories:$it" }) + ",[\"versions:$mc\"]]"
        val sort = if (query.isBlank()) "downloads" else "relevance"
        val url = "$BASE/search?query=${enc(query)}&facets=${enc(facets)}&limit=20&index=$sort"
        val hits = JSONObject(httpGetter(url, emptyMap())).getJSONArray("hits")
        val out = mutableListOf<ModHit>()
        for (i in 0 until hits.length()) {
            val h = hits.getJSONObject(i)
            if (h.str("server_side") == "unsupported") continue      // client-only mods do nothing on a server
            val id = h.str("project_id") ?: continue
            out.add(ModHit(
                "modrinth", id, h.str("title") ?: id, h.str("author") ?: "", h.str("description") ?: "",
                h.optLong("downloads"), "https://modrinth.com/project/${h.str("slug") ?: id}"
            ))
        }
        return out
    }

    // Finds the newest compatible file, plus every required dependency
    fun resolve(projectId: String, type: String, mc: String): Resolved {
        val files = LinkedHashMap<String, ModFile>()
        val warnings = mutableListOf<String>()
        val visited = mutableSetOf<String>()

        fun walk(id: String, depth: Int, isRoot: Boolean) {
            if (depth > 3 || !visited.add(id)) return
            val url = "$BASE/project/${enc(id)}/version?loaders=${enc(jsonArray(loaders(type)))}&game_versions=${enc(jsonArray(listOf(mc)))}"
            val versions = JSONArray(httpGetter(url, emptyMap()))
            if (versions.length() == 0) {
                if (isRoot) throw ModException("No version of this project supports $type $mc.")
                warnings.add("A required add-on has no version for $mc, so it was skipped.")
                return
            }
            var chosen = versions.getJSONObject(0)
            for (i in 0 until versions.length()) {
                val v = versions.getJSONObject(i)
                if (v.str("version_type") == "release") { chosen = v; break }
            }
            val fileArr = chosen.optJSONArray("files") ?: JSONArray()
            var file: JSONObject? = null
            for (i in 0 until fileArr.length()) {
                val f = fileArr.getJSONObject(i)
                if (f.optBoolean("primary") && f.str("filename")?.endsWith(".jar", true) == true) { file = f; break }
            }
            if (file == null) for (i in 0 until fileArr.length()) {
                val f = fileArr.getJSONObject(i)
                if (f.str("filename")?.endsWith(".jar", true) == true) { file = f; break }
            }
            if (file == null) {
                if (isRoot) throw ModException("This version has no .jar file to install.")
                warnings.add("A required add-on has no .jar file, so it was skipped.")
                return
            }
            val downloadUrl = file.str("url") ?: throw ModException("The file has no download address.")
            checkHost(downloadUrl, HOSTS)
            val name = safeJarName(file.str("filename") ?: "mod.jar")
            val sha512 = file.optJSONObject("hashes")?.str("sha512") ?: ""
            files[name] = ModFile(downloadUrl, name, "sha512", sha512)

            val deps = chosen.optJSONArray("dependencies")
            if (deps != null) for (i in 0 until deps.length()) {
                val d = deps.getJSONObject(i)
                if (d.str("dependency_type") == "required") {
                    d.str("project_id")?.let { walk(it, depth + 1, false) }
                }
            }
        }
        walk(projectId, 0, true)
        return Resolved(files.values.toList(), warnings)
    }
}

// ---------- CurseForge (needs the user's own free API key) ----------
object CurseForgeApi {
    private const val BASE = "https://api.curseforge.com/v1"
    private val HOSTS = setOf("forgecdn.net")

    private fun loaderType(type: String): Int? = when (type) { "Fabric" -> 4; "Forge" -> 1; else -> null }
    private fun classId(type: String): Int = if (type == "Paper") 5 else 6        // 5 = Bukkit plugins, 6 = mods

    fun search(query: String, type: String, mc: String, key: String): List<ModHit> {
        val sb = StringBuilder("$BASE/mods/search?gameId=432&classId=${classId(type)}&gameVersion=${enc(mc)}&pageSize=20")
        if (query.isNotBlank()) sb.append("&searchFilter=${enc(query)}")
        loaderType(type)?.let { sb.append("&modLoaderType=$it") }
        val data = JSONObject(httpGetter(sb.toString(), mapOf("x-api-key" to key))).getJSONArray("data")
        val out = mutableListOf<ModHit>()
        for (i in 0 until data.length()) {
            val m = data.getJSONObject(i)
            val id = m.opt("id")?.toString() ?: continue
            val authors = m.optJSONArray("authors")
            out.add(ModHit(
                "curseforge", id, m.str("name") ?: id,
                authors?.optJSONObject(0)?.str("name") ?: "", m.str("summary") ?: "",
                m.optLong("downloadCount"), m.optJSONObject("links")?.str("websiteUrl") ?: "https://www.curseforge.com/minecraft"
            ))
        }
        return out
    }

    private fun rank(f: JSONObject) = when (f.optInt("releaseType", 3)) { 1 -> 0; 2 -> 1; else -> 2 }

    fun resolve(modId: String, type: String, mc: String, key: String, pageUrl: String): Resolved {
        val files = LinkedHashMap<String, ModFile>()
        val warnings = mutableListOf<String>()
        val visited = mutableSetOf<String>()

        fun walk(id: String, depth: Int, isRoot: Boolean) {
            if (depth > 3 || !visited.add(id)) return
            if (!Regex("^[0-9]{1,12}$").matches(id)) return
            val sb = StringBuilder("$BASE/mods/$id/files?gameVersion=${enc(mc)}&pageSize=30")
            loaderType(type)?.let { sb.append("&modLoaderType=$it") }
            val data = JSONObject(httpGetter(sb.toString(), mapOf("x-api-key" to key))).getJSONArray("data")
            var best: JSONObject? = null
            for (i in 0 until data.length()) {
                val f = data.getJSONObject(i)
                if (!f.optBoolean("isAvailable", true)) continue
                val b = best
                if (b == null || rank(f) < rank(b) ||
                    (rank(f) == rank(b) && (f.str("fileDate") ?: "") > (b.str("fileDate") ?: ""))) best = f
            }
            if (best == null) {
                if (isRoot) throw ModException("No file of this project supports $type $mc.")
                warnings.add("A required add-on has no file for $mc, so it was skipped.")
                return
            }
            val url = best.str("downloadUrl")
            if (url == null) {
                if (isRoot) throw ModException("The author blocks downloads outside CurseForge. Download it from $pageUrl and place it in the folder yourself.")
                warnings.add("A required add-on blocks downloads outside CurseForge ($id). Install it manually.")
                return
            }
            checkHost(url, HOSTS)
            val name = safeJarName(best.str("fileName") ?: "mod.jar")
            var sha1 = ""
            val hashes = best.optJSONArray("hashes")
            if (hashes != null) for (i in 0 until hashes.length()) {
                val h = hashes.getJSONObject(i)
                if (h.optInt("algo") == 1) sha1 = h.str("value") ?: ""
            }
            files[name] = ModFile(url, name, "sha1", sha1)

            val deps = best.optJSONArray("dependencies")
            if (deps != null) for (i in 0 until deps.length()) {
                val d = deps.getJSONObject(i)
                if (d.optInt("relationType") == 3) walk(d.opt("modId")?.toString() ?: "", depth + 1, false)
            }
        }
        walk(modId, 0, true)
        return Resolved(files.values.toList(), warnings)
    }
}

// ---------- Entry points used by the screen ----------
fun searchMods(provider: String, query: String, type: String, mc: String, cfKey: String): List<ModHit> =
    if (provider == "CurseForge") CurseForgeApi.search(query, type, mc, cfKey) else Modrinth.search(query, type, mc)

fun resolveMod(hit: ModHit, type: String, mc: String, cfKey: String): Resolved =
    if (hit.provider == "curseforge") CurseForgeApi.resolve(hit.id, type, mc, cfKey, hit.pageUrl)
    else Modrinth.resolve(hit.id, type, mc)

// A direct link to a .jar file. Any https site is allowed because the user chose it themselves.
fun directFile(url: String): ModFile {
    val trimmed = url.trim()
    val path = trimmed.substringBefore('?').substringBefore('#')
    if (!trimmed.startsWith("https://") || !path.endsWith(".jar", ignoreCase = true)) {
        throw ModException("Paste a direct https link to a .jar file.")
    }
    return ModFile(trimmed, safeJarName(path), "none", "")
}
