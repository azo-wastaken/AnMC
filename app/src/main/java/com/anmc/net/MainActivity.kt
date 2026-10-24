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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// ============================================================
//  MainActivity.kt - the app's entry point, theme, welcome guide and
//  the Termux permission helpers.
// ============================================================

const val TERMUX_RUN_PERMISSION = "com.termux.permission.RUN_COMMAND"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("anmc_preferences", Context.MODE_PRIVATE)
        setContent {
            var darkTheme by rememberSaveable { mutableStateOf(prefs.getBoolean("dark_theme_v1", true)) }
            ANMCAppTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(
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

// ---------------- Theme ----------------
@Composable
fun ANMCAppTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colors = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF7BD88F),
            onPrimary = Color(0xFF003919),
            primaryContainer = Color(0xFF1F4D2B),
            onPrimaryContainer = Color(0xFFB8F2C4)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF2E7D32),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFB8F2C4),
            onPrimaryContainer = Color(0xFF00210B)
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun ThemeToggle(darkTheme: Boolean, onThemeChange: (Boolean) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (darkTheme) "\uD83C\uDF19" else "\u2600\uFE0F",
                style = MaterialTheme.typography.labelMedium
            )
            Switch(
                checked = darkTheme,
                onCheckedChange = onThemeChange,
                modifier = Modifier.size(width = 52.dp, height = 30.dp)
            )
        }
    }
}

// ---------------- Termux permission helpers ----------------
fun isTermuxInstalled(context: Context): Boolean =
    try {
        context.packageManager.getPackageInfo("com.termux", 0)
        true
    } catch (e: Exception) {
        false
    }

fun hasTermuxPermission(context: Context): Boolean =
    context.checkSelfPermission(TERMUX_RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED

fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(context, "Open Android Settings > Apps > $APP_NAME > Permissions.", Toast.LENGTH_LONG).show()
    }
}

// Termux blocks RUN_COMMAND while allow-external-apps is false.
// This copies the setup command and opens Termux so the user can run it once.
private const val TERMUX_ENABLE_EXTERNAL_APPS_COMMAND =
    "mkdir -p ~/.termux && touch ~/.termux/termux.properties && " +
        "if grep -Eq '^[[:space:]]*#?[[:space:]]*allow-external-apps[[:space:]]*=' ~/.termux/termux.properties; then " +
        "sed -i -E 's/^[[:space:]]*#?[[:space:]]*allow-external-apps[[:space:]]*=.*/allow-external-apps = true/' ~/.termux/termux.properties; " +
        "else printf '\\nallow-external-apps = true\\n' >> ~/.termux/termux.properties; fi && " +
        "termux-reload-settings"

fun openTermuxAccessSetup(context: Context) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("$APP_NAME Termux access setup", TERMUX_ENABLE_EXTERNAL_APPS_COMMAND))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not copy the Termux setup command.", Toast.LENGTH_LONG).show()
        return
    }
    Toast.makeText(
        context,
        "Command copied. In Termux, paste it and press Enter, then fully close and reopen Termux.",
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

/** Re-checks the Termux permission every second, so the screen updates when the user allows it. */
@Composable
fun rememberTermuxGranted(): State<Boolean> {
    val context = LocalContext.current
    val granted = remember { mutableStateOf(hasTermuxPermission(context)) }
    LaunchedEffect(Unit) {
        while (isActive) {
            granted.value = hasTermuxPermission(context)
            delay(1000)
        }
    }
    return granted
}

/** Shows whether the "Run commands in Termux environment" permission is allowed, with buttons to fix it. */
@Composable
fun TermuxPermissionCard(granted: Boolean) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val termuxInstalled = remember { isTermuxInstalled(context) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                granted -> Text(
                    "\u2713 Permission allowed: Run commands in Termux environment",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                !termuxInstalled -> {
                    Text("Termux isn't installed", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    Text(
                        "Install Termux from F-Droid or GitHub (not the Play Store), open it once, then come back.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                else -> {
                    Text(
                        "Permission needed: Run commands in Termux environment",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        "$APP_NAME uses Termux to install and run servers, and Android only lets it do that if you allow this permission. " +
                            "Tap Allow. If no pop-up appears, tap App settings, open Permissions, find " +
                            "\"Run commands in Termux environment\" (on some phones under \"Additional permissions\" or \"Other permissions\") and choose Allow.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { launcher.launch(TERMUX_RUN_PERMISSION) }) { Text("Allow") }
                        OutlinedButton(onClick = { openAppSettings(context) }) { Text("App settings") }
                    }
                }
            }
        }
    }
}

// ---------------- Welcome guide ----------------
@Composable
private fun GuideStep(number: String, title: String, text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(34.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(number, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GuideScreen(granted: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("WELCOME TO ${APP_NAME.uppercase()}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Host Minecraft servers from your phone", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    "Make as many Java and Bedrock servers as you like, then run one at a time. First, three quick setup steps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Text("One-time setup", style = MaterialTheme.typography.titleLarge)

        GuideStep(
            "1", "Install Termux",
            "Get Termux from F-Droid or GitHub (not the Play Store) and open it once. $APP_NAME runs everything through it."
        )
        GuideStep(
            "2", "Let apps run commands in Termux",
            "Tap the button, paste the copied command into Termux, press Enter, then fully close and reopen Termux."
        )
        OutlinedButton(onClick = { openTermuxAccessSetup(context) }, modifier = Modifier.fillMaxWidth()) {
            Text("Copy the command and open Termux")
        }
        GuideStep(
            "3", "Allow the permission in $APP_NAME",
            "Android must also let $APP_NAME talk to Termux: allow \"Run commands in Termux environment\". " +
                "Open Settings > Apps > $APP_NAME > Permissions, find it (some phones list it under \"Additional permissions\" " +
                "or \"Other permissions\"; on Xiaomi: Manage apps > $APP_NAME > App permissions / Other permissions), and choose Allow."
        )
        TermuxPermissionCard(granted = granted)
        GuideStep(
            "4", "Keep Termux alive",
            "In Settings > Apps > Termux: set Battery saver to No restrictions and turn on Autostart (Xiaomi). Otherwise Android may stop your server in the background."
        )

        Text("Using the app", style = MaterialTheme.typography.titleLarge)
        GuideStep(
            "5", "Create a server",
            "Pick the Java or Bedrock tab and tap +. Name it, choose the software and version, accept the Minecraft EULA, and it installs (5 to 20 minutes on Wi-Fi). " +
                "Bedrock servers use PowerNukkitX because Mojang's official Bedrock server can't run on phones."
        )
        GuideStep(
            "6", "Start it and link playit",
            "Tap Start. The first time, a playit link appears: open it and log in. Then on playit.gg create a tunnel pointing at 127.0.0.1 and your server's port " +
                "(Minecraft Java for Java servers, Minecraft Bedrock for Bedrock servers) and paste the address it gives you into the server's Address card."
        )
        GuideStep(
            "7", "Manage it",
            "Tap a server card for its console, players, backups and (for Paper, Fabric and Forge) plugins or mods. " +
                "Use the three-dot menu on a card to edit or delete a server. Only one server can run at a time."
        )

        Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Good to know", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Unofficial tool, not affiliated with Mojang, Microsoft, or playit.gg. Unlinking playit only clears this phone's login; " +
                        "it doesn't delete anything from your playit account. You can reopen this guide any time with the Guide button.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("Get started") }
    }
}

// ---------------- App root ----------------
@Composable
fun AppRoot(darkTheme: Boolean, onThemeChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("anmc_preferences", Context.MODE_PRIVATE) }
    val c = remember { AppController(context.applicationContext) }
    val granted by rememberTermuxGranted()
    var showGuide by remember { mutableStateOf(!prefs.getBoolean("welcome_v2_done", false)) }
    var editTarget by remember { mutableStateOf<ServerConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<ServerConfig?>(null) }

    LaunchedEffect(Unit) { c.consoleLoop() }
    // Check what is installed and running as soon as the permission is available
    LaunchedEffect(granted) { if (granted) c.refreshStatus() }

    val open = c.server(c.openServerId)
    BackHandler(enabled = !showGuide && open != null) { c.closeServer() }

    when {
        showGuide -> GuideScreen(
            granted = granted,
            onDone = {
                prefs.edit().putBoolean("welcome_v2_done", true).apply()
                showGuide = false
            }
        )
        open != null -> ServerScreen(
            c = c,
            cfg = open,
            onBack = { c.closeServer() },
            onEdit = { editTarget = open },
            onDelete = { deleteTarget = open }
        )
        else -> HomeScreen(
            c = c,
            darkTheme = darkTheme,
            onThemeChange = onThemeChange,
            termuxGranted = granted,
            onShowGuide = { showGuide = true }
        )
    }

    // Edit / delete started from a server's own screen
    editTarget?.let { ServerEditorDialog(c, it, isNew = false, onDismiss = { editTarget = null }) }
    deleteTarget?.let { DeleteServerDialog(c, it, onDismiss = { deleteTarget = null }) }

    // Pop-up messages from the controller (installation finished, another server is running, ...)
    c.notice?.let { n ->
        AlertDialog(
            onDismissRequest = { c.notice = null },
            title = { Text(n.title) },
            text = { Text(n.text) },
            confirmButton = {
                TextButton(onClick = {
                    c.notice = null
                    n.action?.invoke()
                }) { Text(n.actionLabel ?: "OK") }
            },
            dismissButton = {
                if (n.actionLabel != null) TextButton(onClick = { c.notice = null }) { Text("Not now") }
            }
        )
    }
}
