package com.anmc.net

// ============================================================
//  Scripts.kt - everything that builds shell scripts for Termux.
//  Plain Kotlin (no Android or Compose), so it is easy to test.
//  Every script reports back by printing lines that start with
//  "ANMC_X:KIND:TOKEN:PAYLOAD"; the app reads them from the log.
// ============================================================

const val APP_NAME = "AnMC"

// ---------- Termux paths ----------
const val HOME = "/data/data/com.termux/files/home"
const val PFX = "/data/data/com.termux/files/usr"
const val LOG = "$HOME/anmc/console.log"
const val SOCK = "$HOME/anmc/playit.sock"
const val PB = "$HOME/playit-bin"
const val BACKUP_ROOT = "$HOME/anmc-backups"

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

const val PLAYIT_TAG = "sed -u 's/^/[playit] /'"
const val SETUP_STEPS = 6

// ---------- Where each server lives (ids are checked, so these paths are safe in shell commands) ----------
private fun safeId(id: String): String = if (validId(id)) id else throw IllegalArgumentException("bad server id")

fun serverDir(id: String): String = if (safeId(id) == LEGACY_ID) "$HOME/server" else "$HOME/servers/$id"
fun backupDir(id: String): String = "$BACKUP_ROOT/${safeId(id)}"
fun session(id: String): String = "mc_${safeId(id)}"

// ---------- Small helpers ----------
private var lastToken = 0L

// A unique, ever-increasing token that identifies one action and its replies
@Synchronized
fun opToken(): String {
    val now = System.currentTimeMillis()
    lastToken = if (now > lastToken) now else lastToken + 1
    return lastToken.toString()
}

fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

// Removes terminal color/control codes so the log is readable
private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007|\u001B[()][A-Z0-9]")
fun cleanLine(s: String): String = s.replace(ansi, "").replace("\r", "").trimEnd()

// Runs a script in the background and sends all of its output to the log the app is reading
fun logged(script: String): String =
    ENV + "mkdir -p $HOME/anmc; touch $LOG; {\n$script\n} >> $LOG 2>&1"

// Returns the Termux Java package a Minecraft version needs, or null if the version isn't valid/supported.
// Since 2026 Minecraft uses year-based versions (26.1, 26.2, ...) and they need Java 25.
fun javaPackageFor(mc: String): String? {
    if (!Regex("^[0-9]+\\.[0-9]+(\\.[0-9]+)?$").matches(mc)) return null
    val parts = mc.split(".").map { it.toIntOrNull() ?: return null }
    if (parts[0] >= 26) return "openjdk-25"
    if (parts[0] != 1) return null
    val minor = parts[1]
    val patch = parts.getOrElse(2) { 0 }
    return when {
        minor < 17 -> null
        minor < 20 || (minor == 20 && patch < 5) -> "openjdk-17"
        else -> "openjdk-21"
    }
}

// Makes any file name safe to use in a shell command and on disk
fun safeJarName(raw: String): String {
    var n = raw.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._+-]"), "_").trimStart('.', '-', '_')
    n = if (n.endsWith(".jar", ignoreCase = true)) n.dropLast(4) else n
    n = n.take(110)
    return if (n.isEmpty()) "download.jar" else "$n.jar"
}

fun validBackupName(name: String): Boolean =
    Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,100}\\.tar\\.gz$").matches(name)

// A name that is safe to print from a shell script
fun safeLabel(name: String): String = name.replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().take(40)

private fun step(token: String, n: Int, label: String) = "echo 'ANMC_X:STEP:$token:$n|$label'"
private fun marker(kind: String, token: String, payload: String) = "echo 'ANMC_X:$kind:$token:$payload'"

// ---------- start.sh ----------
fun writeFile(path: String, vararg lines: String): String =
    "printf '%s\\n' " + lines.joinToString(" ") { shellQuote(it) } + " > $path; chmod +x $path"

// True when we know enough about an installed server to rewrite its start.sh
fun canRewriteLaunch(type: String, version: String): Boolean =
    type == "PowerNukkitX" || (type in JAVA_TYPES && javaPackageFor(version) != null)

// Commands that (re)write a server's start.sh (and Forge's user_jvm_args.txt)
fun launchLines(type: String, mc: String, ramGb: Int, dir: String): List<String> {
    val need = if (type == "PowerNukkitX") 21 else (javaPackageFor(mc)?.removePrefix("openjdk-")?.toInt() ?: 21)
    val flags = "-Xms512M -Xmx${ramGb}G -XX:+UseSerialGC -XX:MaxMetaspaceSize=256M"
    val run = when (type) {
        "Forge" -> "exec \"\$J\" @user_jvm_args.txt @\$(ls libraries/net/minecraftforge/forge/*/unix_args.txt) nogui"
        "PowerNukkitX" -> "exec \"\$J\" $flags --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED -jar powernukkitx.jar"
        else -> "exec \"\$J\" $flags -jar server.jar nogui"
    }
    val script = writeFile(
        "$dir/start.sh",
        "#!$PFX/bin/bash",
        "cd $dir",
        "termux-wake-lock",
        // Different servers need different Java versions (17, 21, 25). Use the right one, installing it if needed.
        "need=$need",
        "J=$PFX/lib/jvm/java-\$need-openjdk/bin/java",
        "if [ ! -x \"\$J\" ]; then",
        "  cur=\$(java -version 2>&1 | sed -n 's/.*version \"\\([0-9]*\\).*/\\1/p' | head -n1)",
        "  if [ \"\$cur\" != \"\$need\" ]; then echo \"Installing Java \$need ...\"; " +
            "apt-get -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold install openjdk-\$need; fi",
        "  [ -x \"\$J\" ] || J=java",
        "fi",
        run
    )
    return if (type == "Forge") listOf("echo '$flags' > $dir/user_jvm_args.txt", script) else listOf(script)
}

// Adds "key=value" to server.properties only if the key is missing
private fun ensureKeyLines(dir: String, key: String, value: String): List<String> = listOf(
    "touch $dir/server.properties",
    "grep -q '^$key=' $dir/server.properties || echo '$key=$value' >> $dir/server.properties"
)

private fun setPortLines(dir: String, port: Int): List<String> = listOf(
    "touch $dir/server.properties",
    "if grep -q '^server-port=' $dir/server.properties; then sed -i 's/^server-port=.*/server-port=$port/' $dir/server.properties; " +
        "else echo 'server-port=$port' >> $dir/server.properties; fi"
)

// ---------- Install ----------
fun setupScript(s: ServerConfig, token: String): String? {
    if (validateServer(s) != null) return null          // also blocks unsafe input
    val d = serverDir(s.id)
    val mc = s.version
    val java = if (s.isJava) javaPackageFor(mc)!! else "openjdk-21"
    val c = "curl -fsSL -A anmc-app"
    val download = when (s.type) {
        "Paper" -> listOf(
            "URL=\$($c https://fill.papermc.io/v3/projects/paper/versions/$mc/builds/latest | jq -r '.downloads.\"server:default\".url')",
            "wget -O server.jar \"\$URL\"",
            "mkdir -p plugins"
        )
        "Fabric" -> listOf(
            "LOADER=\$($c https://meta.fabricmc.net/v2/versions/loader | jq -r '[.[]|select(.stable)][0].version')",
            "INSTALLER=\$($c https://meta.fabricmc.net/v2/versions/installer | jq -r '[.[]|select(.stable)][0].version')",
            "wget -O server.jar \"https://meta.fabricmc.net/v2/versions/loader/$mc/\$LOADER/\$INSTALLER/server/jar\"",
            "mkdir -p mods"
        )
        "Forge" -> listOf(
            "FV=\$($c https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json | jq -r '.promos[\"$mc-recommended\"] // .promos[\"$mc-latest\"] // empty')",
            "if [ -z \"\$FV\" ]; then echo 'No Forge build found for $mc'; false; fi",
            "wget -O installer.jar \"https://maven.minecraftforge.net/net/minecraftforge/forge/$mc-\$FV/forge-$mc-\$FV-installer.jar\"",
            "java -jar installer.jar --installServer",
            "rm -f installer.jar installer.jar.log",
            "mkdir -p mods"
        )
        "PowerNukkitX" -> listOf(
            "wget -O powernukkitx.jar https://github.com/PowerNukkitX/PowerNukkitX/releases/latest/download/powernukkitx.jar"
        )
        else -> listOf(
            "VURL=\$($c https://piston-meta.mojang.com/mc/game/version_manifest_v2.json | jq -r '.versions[]|select(.id==\"$mc\")|.url')",
            "wget -O server.jar \"\$($c \$VURL | jq -r .downloads.server.url)\""
        )
    }

    val apt = "apt-get -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold"
    val gh = "https://github.com/playit-cloud/playit-agent/releases/latest/download"
    val lines = listOf(
        "set -eo pipefail",
        "trap 'echo ANMC_X:SETUP_FAILED:$token:${s.id}' ERR",
        "export DEBIAN_FRONTEND=noninteractive",
        "termux-wake-lock || true",
        "mkdir -p $HOME/anmc",
        step(token, 1, "Updating package lists"),
        "$apt update",
        "$apt install socat",
        BRIDGE,
        step(token, 2, "Upgrading Termux packages (a few minutes)"),
        "$apt upgrade",
        step(token, 3, "Installing Java and tools"),
        "$apt install $java proot tmux wget curl jq",
        step(token, 4, "Downloading playit"),
        "mkdir -p $PB && cd $PB",
        "wget -O playit.new $gh/playit-linux-aarch64 && mv -f playit.new playit",
        "wget -O playit-cli.new $gh/playit-cli-linux-aarch64 && mv -f playit-cli.new playit-cli",
        "chmod +x playit playit-cli",
        "mkdir -p $d && cd $d",
        step(token, 5, "Downloading ${s.summary}")
    ) + download +
        (if (s.isJava && s.eula) listOf("echo 'eula=true' > eula.txt") else emptyList()) +
        setPortLines(d, s.port) +
        (if (!s.isJava) ensureKeyLines(d, "transport", "raknet") else emptyList()) +
        launchLines(s.type, mc, s.ramGb, d) + listOf(
        "echo '${s.type}|$mc' > $d/.anmc-info",
        step(token, 6, "Finishing up"),
        marker("SETUP_DONE", token, s.id)
    )
    return lines.joinToString("\n")
}

// Applies RAM and port changes to an already installed server (no reinstall needed)
fun applySettingsCmd(s: ServerConfig, installedType: String, installedVersion: String): String {
    val d = serverDir(s.id)
    val out = mutableListOf("cd $d || exit 0")
    val canRewrite = installedType in typesFor(s.edition) && canRewriteLaunch(installedType, installedVersion)
    if (canRewrite) out += launchLines(installedType, installedVersion, s.ramGb, d)
    out += setPortLines(d, s.port)
    out += "echo 'Settings for ${safeLabel(s.name)} saved. Restart the server to apply them.'"
    return logged(out.joinToString("\n"))
}

// ---------- playit helper scripts (rewritten on every start so they stay current) ----------
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

private fun startServerLine(id: String): String =
    "tmux has-session -t ${session(id)} 2>/dev/null || { tmux new-session -d -s ${session(id)} 'sleep 1; exec ${serverDir(id)}/start.sh' " +
        "&& tmux pipe-pane -t ${session(id)} 'cat >> $LOG'; }"

private const val START_PLAYIT_LINE =
    "tmux has-session -t playit 2>/dev/null || { rm -f $SOCK; " +
        "tmux new-session -d -s playit 'sleep 1; exec $HOME/anmc/playitd.sh' " +
        "&& tmux pipe-pane -t playit \"$PLAYIT_TAG >> $LOG\"; }"

private fun linkLine(arg: String) =
    "tmux kill-session -t pcli 2>/dev/null; " +
        "tmux new-session -d -s pcli 'sleep 1; exec $HOME/anmc/link.sh $arg' " +
        "&& tmux pipe-pane -t pcli \"$PLAYIT_TAG >> $LOG\""

// ---------- Status, start, stop ----------
// Reports which servers are installed and which are running (and whether the playit tools exist)
fun checkStatusCmd(token: String, ids: List<String>): String {
    val out = mutableListOf(
        ENSURE_SOCAT,
        BRIDGE,
        "if [ -x $PB/playit ] && [ -x $PB/playit-cli ]; then ${marker("TOOLS", token, "1")}; fi"
    )
    for (id in ids.filter { validId(it) }.distinct()) {
        val d = serverDir(id)
        out += "if [ -x $d/start.sh ]; then ${marker("INST", token, id)}; " +
            "if [ -f $d/.anmc-info ]; then echo \"ANMC_X:INFO:$token:$id|\$(head -n1 $d/.anmc-info)\"; fi; fi"
        out += "if tmux has-session -t ${session(id)} 2>/dev/null; then ${marker("RUN", token, id)}; fi"
    }
    // A server created by an older version of the app lives in ~/server: offer it to the app
    out += "if [ -x $HOME/server/start.sh ]; then " +
        "if [ ! -f $HOME/server/.anmc-info ]; then " +
        "if [ -f $HOME/anmc/server-info ]; then cp $HOME/anmc/server-info $HOME/server/.anmc-info; " +
        "else T=Vanilla; [ -d $HOME/server/plugins ] && T=Paper; [ -d $HOME/server/mods ] && T=Fabric; " +
        "[ -d $HOME/server/libraries/net/minecraftforge ] && T=Forge; echo \"\$T|\" > $HOME/server/.anmc-info; fi; fi; " +
        "echo \"ANMC_X:LEGACY:$token:\$(head -n1 $HOME/server/.anmc-info)\"; fi"
    out += marker("STATUS_DONE", token, "")
    return logged(out.joinToString("\n"))
}

// Starts the log stream, the server, the playit daemon, and links playit if it's the first time
// Lines that refresh a server's launcher (right Java, current RAM) and Bedrock's transport before it starts
private fun prepareLines(s: ServerConfig, installedType: String, installedVersion: String): List<String> {
    val d = serverDir(s.id)
    val out = mutableListOf<String>()
    if (canRewriteLaunch(installedType, installedVersion)) out += launchLines(installedType, installedVersion, s.ramGb, d)
    if (!s.isJava) out += ensureKeyLines(d, "transport", "raknet")
    return out
}

fun startAllCmd(s: ServerConfig, token: String, installedType: String = "", installedVersion: String = ""): String {
    val d = serverDir(s.id)
    val sess = session(s.id)
    return listOf(
        ENV + "mkdir -p $HOME/anmc; touch $LOG",
        ENSURE_SOCAT,
        BRIDGE,
        wrappers(),
        "if [ ! -x $d/start.sh ] || [ ! -x $PB/playit ]; then ${marker("NEEDS_INSTALL", token, s.id)} >> $LOG; exit 0; fi",
        // Only one server can run at a time (RAM and ports)
        "if tmux list-sessions -F '#S' 2>/dev/null | grep '^mc_' | grep -qv '^$sess\$'; then ${marker("BUSY", token, s.id)} >> $LOG; exit 0; fi",
        "if tmux has-session -t $sess 2>/dev/null; then ${marker("RUN", token, s.id)} >> $LOG; " +
            "else ${marker("STARTING", token, s.id)} >> $LOG; echo '=== Starting ${safeLabel(s.name)} ===' >> $LOG; fi",
        *prepareLines(s, installedType, installedVersion).toTypedArray(),
        startServerLine(s.id),
        "if [ -f $HOME/anmc/playit-unlinked ]; then echo 'Playit is intentionally unlinked. Tap Relink playit to connect it again.' >> $LOG; else",
        START_PLAYIT_LINE,
        "if [ ! -s $HOME/.config/playit_gg/playit.toml ]; then ${linkLine("")}; fi",
        "fi"
    ).joinToString("\n")
}

// Restarts only the Minecraft server (playit keeps running), e.g. to load new mods
fun restartServerCmd(s: ServerConfig, token: String, installedType: String = "", installedVersion: String = ""): String = listOf(
    ENV + "mkdir -p $HOME/anmc; touch $LOG",
    "tmux send-keys -t ${session(s.id)} stop Enter 2>/dev/null",
    "for i in \$(seq 1 90); do tmux has-session -t ${session(s.id)} 2>/dev/null || break; sleep 1; done",
    "${marker("STARTING", token, s.id)} >> $LOG",
    "echo '=== Restarting ${safeLabel(s.name)} ===' >> $LOG",
    *prepareLines(s, installedType, installedVersion).toTypedArray(),
    startServerLine(s.id)
).joinToString("\n")

// Saves the world, waits for the server to exit, then stops playit
fun stopAllCmd(s: ServerConfig, token: String): String = listOf(
    ENV + "tmux send-keys -t ${session(s.id)} stop Enter 2>/dev/null",
    "for i in \$(seq 1 90); do tmux has-session -t ${session(s.id)} 2>/dev/null || break; sleep 1; done",
    "tmux kill-session -t ${session(s.id)} 2>/dev/null",
    "tmux kill-session -t playit 2>/dev/null",
    "tmux kill-session -t pcli 2>/dev/null",
    "pkill -x playit",
    "termux-wake-unlock || true",
    "${marker("STOPPED", token, s.id)} >> $LOG"
).joinToString("\n")

// Stops the server if it is running, then optionally deletes its files and backups
fun deleteServerCmd(s: ServerConfig, token: String, deleteFiles: Boolean): String = listOf(
    ENV + "mkdir -p $HOME/anmc; touch $LOG",
    "tmux send-keys -t ${session(s.id)} stop Enter 2>/dev/null",
    "for i in \$(seq 1 90); do tmux has-session -t ${session(s.id)} 2>/dev/null || break; sleep 1; done",
    "tmux kill-session -t ${session(s.id)} 2>/dev/null",
    if (deleteFiles) "rm -rf -- ${shellQuote(serverDir(s.id))} ${shellQuote(backupDir(s.id))}" else "true",
    "${marker("DELETED", token, s.id)} >> $LOG"
).joinToString("\n")

// Types a line into the server console (-l makes it literal, so text like "Enter" is not treated as a key)
fun sendCommandCmd(s: ServerConfig, cmd: String): String {
    val t = session(s.id)
    return ENV + "tmux send-keys -t $t -l -- ${shellQuote(cmd)}; tmux send-keys -t $t Enter"
}

// Resets the saved playit account and gives a fresh link
fun relinkCmd(): String = listOf(
    ENV + "mkdir -p $HOME/anmc $HOME/.config/playit_gg; touch $LOG",
    ENSURE_SOCAT,
    BRIDGE,
    wrappers(),
    "if [ ! -x $PB/playit ] || [ ! -x $PB/playit-cli ]; then echo 'Playit binaries are missing. Install a server first.' >> $LOG; exit 1; fi",
    "tmux kill-session -t pcli 2>/dev/null || true",
    "tmux kill-session -t playit 2>/dev/null || true",
    "pkill -x playit 2>/dev/null || true",
    "rm -f $SOCK",
    "rm -f $HOME/anmc/playit-unlinked",
    START_PLAYIT_LINE,
    linkLine("reset")
).joinToString("\n")

// Disconnects this phone's Playit agent without stopping any Minecraft server.
fun unlinkPlayitCmd(): String = listOf(
    ENV + "mkdir -p $HOME/anmc; touch $LOG",
    "tmux kill-session -t pcli 2>/dev/null || true",
    "tmux kill-session -t playit 2>/dev/null || true",
    "pkill -x playit 2>/dev/null || true",
    "rm -f $SOCK",
    "rm -f $HOME/.config/playit_gg/playit.toml",
    "touch $HOME/anmc/playit-unlinked",
    "echo 'Playit agent unlinked on this phone. Your servers are not affected. To remove the agent/tunnels from your account, use the Playit dashboard.' >> $LOG"
).joinToString("\n")

// Asks the playit CLI for its status; the app looks for the public address in the output
fun detectAddressCmd(): String = listOf(
    ENV + "mkdir -p $HOME/anmc; touch $LOG",
    ENSURE_SOCAT,
    BRIDGE,
    wrappers(),
    "if [ ! -S $SOCK ]; then echo 'Start the server first so playit is running, then try again.' >> $LOG; exit 0; fi",
    "timeout 15 $HOME/anmc/pcli.sh status 2>&1 | $PLAYIT_TAG >> $LOG"
).joinToString("\n")

// ---------- Mods and plugins ----------
data class ModFile(val url: String, val fileName: String, val algo: String, val hash: String)

private fun listModsSnippet(token: String, id: String, dirName: String): String = listOf(
    marker("MODS_RESET", token, ""),
    "for f in ${shellQuote("${serverDir(id)}/$dirName")}/*.jar; do [ -f \"\$f\" ] && echo \"ANMC_X:MOD:$token:\$(basename \"\$f\")\"; done",
    marker("MODS_DONE", token, "")
).joinToString("\n")

fun listModsCmd(token: String, id: String, dirName: String): String =
    logged(listModsSnippet(token, id, dirName))

fun installModsCmd(token: String, id: String, dirName: String, files: List<ModFile>): String {
    val dir = "${serverDir(id)}/$dirName"
    val out = mutableListOf("mkdir -p ${shellQuote(dir)}")
    for (f in files) {
        val name = safeJarName(f.fileName)
        val tmp = shellQuote("$dir/.download.part")
        val target = shellQuote("$dir/$name")
        val hashOk = Regex("^[0-9a-fA-F]{40,128}$").matches(f.hash)
        val verify = when {
            hashOk && f.algo == "sha512" -> "printf '%s  %s\\n' ${shellQuote(f.hash)} $tmp | sha512sum -c - >/dev/null 2>&1"
            hashOk && f.algo == "sha1" -> "printf '%s  %s\\n' ${shellQuote(f.hash)} $tmp | sha1sum -c - >/dev/null 2>&1"
            else -> "true"
        }
        out.add("rm -f $tmp")
        out.add(
            "if curl -fsSL -A anmc-app -o $tmp ${shellQuote(f.url)} && $verify; then mv -f $tmp $target; " +
                "${marker("MODOP", token, "OK|$name")}; else rm -f $tmp; ${marker("MODOP", token, "FAIL|$name")}; fi"
        )
    }
    out.add(listModsSnippet(token, id, dirName))
    return logged(out.joinToString("\n"))
}

fun removeModCmd(token: String, id: String, dirName: String, fileName: String): String =
    logged("rm -f -- ${shellQuote("${serverDir(id)}/$dirName/${safeJarName(fileName)}")}\n" + listModsSnippet(token, id, dirName))

// ---------- Backups ----------
private fun msg(token: String, text: String) = marker("BKOP", token, text)
private fun done(token: String) = marker("BK_DONE", token, "")

// Goes to the server folder and finds the world folder name (L). Stops if anything looks unsafe.
private fun levelNameSnippet(token: String, id: String): List<String> = listOf(
    "cd ${shellQuote(serverDir(id))} 2>/dev/null || { ${msg(token, "Server folder not found. Install the server first.")}; ${done(token)}; exit 0; }",
    "L=\$(grep -m1 '^level-name=' server.properties 2>/dev/null | cut -d= -f2- | tr -d '\\r' || true)",
    "[ -n \"\$L\" ] || L=world",
    "if ! printf '%s' \"\$L\" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._ -]*\$'; then ${msg(token, "Unsupported world folder name in server.properties.")}; ${done(token)}; exit 0; fi",
    "dirs=(); for d in \"\$L\" \"\${L}_nether\" \"\${L}_the_end\" worlds; do [ -d \"\$d\" ] && dirs+=(\"\$d\"); done"
)

private fun listBackupsSnippet(token: String, id: String): String = listOf(
    marker("BK_RESET", token, ""),
    "for f in \$(ls -1t ${shellQuote(backupDir(id))}/*.tar.gz 2>/dev/null); do echo \"ANMC_X:BK:$token:\$(basename \"\$f\")|\$(du -k \"\$f\" | cut -f1)\"; done",
    done(token)
).joinToString("\n")

fun listBackupsCmd(token: String, id: String): String =
    logged("mkdir -p ${shellQuote(backupDir(id))}\n" + listBackupsSnippet(token, id))

fun createBackupCmd(token: String, id: String): String {
    val q = shellQuote(backupDir(id))
    val sess = session(id)
    val body = listOf("mkdir -p $q") + levelNameSnippet(token, id) + listOf(
        "RUN=''",
        "if tmux has-session -t $sess 2>/dev/null; then RUN=1; tmux send-keys -t $sess 'save-off' Enter; sleep 1; tmux send-keys -t $sess 'save-all flush' Enter; sleep 8; fi",
        "if [ \${#dirs[@]} -eq 0 ]; then ${msg(token, "World folder not found. Start the server once so it creates the world.")}",
        "else F=\"world-backup-\$(date +%Y%m%d-%H%M%S).tar.gz\"; rc=0; tar -czf $q/\"\$F\" \"\${dirs[@]}\" || rc=\$?",
        "  if [ \$rc -le 1 ]; then echo \"ANMC_X:BKOP:$token:Backup saved: \$F\"; else rm -f $q/\"\$F\"; ${msg(token, "Backup failed (not enough space?).")}; fi",
        "fi",
        "if [ -n \"\$RUN\" ]; then tmux send-keys -t $sess 'save-on' Enter; fi",
        listBackupsSnippet(token, id)
    )
    return logged(body.joinToString("\n"))
}

fun restoreBackupCmd(token: String, id: String, name: String): String {
    if (!validBackupName(name)) return logged(msg(token, "Invalid backup name.") + "\n" + done(token))
    val q = shellQuote(backupDir(id))
    val file = shellQuote("${backupDir(id)}/$name")
    val body = listOf(
        "if tmux has-session -t ${session(id)} 2>/dev/null; then ${msg(token, "Stop the server before restoring.")}; ${done(token)}; exit 0; fi",
        "if ! tar -tzf $file >/dev/null 2>&1; then ${msg(token, "That backup file is damaged or missing.")}; ${done(token)}; exit 0; fi"
    ) + levelNameSnippet(token, id) + listOf(
        "P=\"before-restore-\$(date +%Y%m%d-%H%M%S).tar.gz\"",
        "if [ \${#dirs[@]} -gt 0 ]; then tar -czf $q/\"\$P\" \"\${dirs[@]}\" || { ${msg(token, "Could not save the current world first. Restore cancelled.")}; ${done(token)}; exit 0; }; fi",
        "rm -rf -- \"\$L\" \"\${L}_nether\" \"\${L}_the_end\" worlds",
        "if tar -xzf $file -C ${shellQuote(serverDir(id))}; then echo \"ANMC_X:BKOP:$token:Restored $name. Your previous world was saved as \$P\"; " +
            "else echo \"ANMC_X:BKOP:$token:Restore failed. Your previous world was saved as \$P\"; fi",
        listBackupsSnippet(token, id)
    )
    return logged(body.joinToString("\n"))
}

fun deleteBackupCmd(token: String, id: String, name: String): String {
    if (!validBackupName(name)) return logged(msg(token, "Invalid backup name.") + "\n" + done(token))
    return logged(
        "rm -f -- ${shellQuote("${backupDir(id)}/$name")}\n" + msg(token, "Deleted $name.") + "\n" + listBackupsSnippet(token, id)
    )
}

fun exportBackupCmd(token: String, id: String, name: String): String {
    if (!validBackupName(name)) return logged(msg(token, "Invalid backup name.") + "\n" + done(token))
    return logged(listOf(
        "D=\"\$HOME/storage/downloads\"",
        "if [ -d \"\$D\" ] && cp ${shellQuote("${backupDir(id)}/$name")} \"\$D/\"; then echo \"ANMC_X:BKOP:$token:Copied to your Downloads folder: $name\"",
        "else ${msg(token, "Storage access is not set up. Open Termux, run termux-setup-storage, allow it, then try again.")}; fi",
        done(token)
    ).joinToString("\n"))
}
