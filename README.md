# AnMC

[![Discord Badge](https://img.shields.io/badge/Discord-Join%20Community-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/cRTyfMcg2t)
[![GitHub Releases](https://img.shields.io/badge/GitHub-Releases-238636?style=for-the-badge&logo=github&logoColor=white)](https://github.com/azo-wastaken/AnMC/releases)

**Host Minecraft Java and Bedrock servers from your Android phone.**

AnMC is an Android app that installs and runs Minecraft servers inside [Termux](https://termux.dev) and shares them online through a [Playit](https://playit.gg) tunnel. Create as many servers as you like, run one at a time, and manage the console, players, mods, backups and settings from the app. There is no PC, no port forwarding and no paid host.

> Unofficial tool. Not affiliated with, endorsed by, or approved by Mojang or Microsoft.

<!-- Add screenshots here, for example: ![Home screen](docs/home.png) -->

---

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [First-time setup](#first-time-setup)
- [Creating a server](#creating-a-server)
- [Sharing your server with friends](#sharing-your-server-with-friends)
- [Java and Bedrock](#java-and-bedrock)
- [Mods and plugins](#mods-and-plugins)
- [Backups](#backups)
- [Where files live in Termux](#where-files-live-in-termux)
- [Building from source](#building-from-source)
- [How it works](#how-it-works)
- [Privacy and security](#privacy-and-security)
- [Troubleshooting](#troubleshooting)
- [Limitations](#limitations)
- [Contributing](#contributing)
- [Credits](#credits)
- [License](#license)

---

## Features

| Area | What you get |
|---|---|
| **Multiple servers** | A home screen with **Java** and **Bedrock** tabs. Each server is a card showing its name, software, RAM, port and status (Not installed, Installing, Stopped, Starting, Running, Stopping). Tap **+** to create one. |
| **Card menu** | A three-dot menu on every card: **Open**, **Edit** and **Delete**. |
| **Edit anything** | Change the name, edition, software, version, RAM, port and public address after creation. Changing the software or version offers a reinstall, and your world is kept. |
| **Install** | Java: Paper, Fabric, Forge or Vanilla, including the 2026 year-based versions (26.1, 26.2 and later). Bedrock: PowerNukkitX. A step-by-step progress bar shows the install. |
| **Start / Stop / Restart** | Start runs the server and the Playit tunnel. Stop saves the world and shuts down cleanly. Restart restarts only the server. |
| **Console** | A live server log and a command box. |
| **Public address** | Store the address friends use per server, with Copy, Share and Detect buttons. |
| **Players** | Online list with whitelist, op, kick, ban and pardon actions. |
| **Mods / Plugins** | Search Modrinth or CurseForge, or use a direct link. Files install straight into the server with their required dependencies. Available for Paper, Fabric and Forge only. |
| **Backups** | Back up the world while the server runs, restore it later (with an automatic safety copy), export to your Downloads folder, or delete old backups. |
| **Game settings** | Edit `server.properties` in the app for Java and Bedrock, including the Bedrock network transport. A timestamped backup is made on every save. |
| **Playit management** | Relink or unlink the Playit agent on the phone. |
| **Guide and themes** | A first-launch guide you can reopen with the **Guide** button, plus light and dark themes. |

---

## Requirements

- An Android 8.0 (API 26) or newer phone with enough free storage and a reliable internet connection.
- [Termux](https://termux.dev) installed from F-Droid or GitHub releases. The Play Store build is outdated.
- A free [Playit](https://playit.gg) account for the public address.
- *Optional:* a free CurseForge API key for the CurseForge provider. Modrinth needs no key.

**RAM guidance:** the app lets you give a server 1 to 8 GB. As a rough rule, give a server about half of your phone's RAM. Java servers run fine on 2 GB for a few players. Bedrock (PowerNukkitX) is heavier, so a phone with 6 GB or more is recommended.

---

## First-time setup

The welcome guide in the app walks through these steps, and the **Guide** button reopens it at any time.

1. **Install Termux** and open it once.
2. **Install AnMC** and open it.
3. **Let apps run commands in Termux.** Tap **Copy the command and open Termux**, paste the command into Termux, press Enter, then fully close and reopen Termux. (This sets `allow-external-apps = true`.)
4. **Allow the permission in AnMC.** Tap **Allow** for *Run commands in Termux environment*. If no dialog appears, open **App settings**, go to **Permissions** (some phones list it under *Additional permissions* or *Other permissions*; on Xiaomi try *Manage apps > AnMC > App permissions*), and turn it on. The app shows a warning on the home screen until it is allowed.
5. **Keep Termux alive.** In Android settings for Termux, set battery saver to **No restrictions** and turn on **Autostart** (Xiaomi). Otherwise Android can stop your server in the background.

---

## Creating a server

1. On the home screen pick the **Java** or **Bedrock** tab and tap **+**.
2. Fill in:
   - **Server name**
   - **Edition** and **server software**
   - **Minecraft version** (Java only)
   - **RAM** (1 to 8 GB)
   - **Port** (1024 to 65535; defaults are 25565 for Java and 19132 for Bedrock)
   - **Public address** (optional, you can add it later)
3. Accept the Minecraft EULA and tap **Create**. With *Install it right away* ticked, the install starts immediately and takes about 5 to 20 minutes on Wi-Fi. The first install also sets up Termux packages and Playit, so it is the slowest.
4. When the install finishes, tap **Start**. Only one server can run at a time, so stop the running one first if you want to start another.

**Editing and deleting.** Use the three-dot menu on a card (or on the server screen). Name, RAM, port and address changes are saved straight away; RAM and port apply the next time the server starts. Delete asks whether to remove the server's files and backups too.

### Supported Java versions

| Minecraft version | Java used (installed automatically) |
|---|---|
| 1.17 to 1.20.4 | Java 17 |
| 1.20.5 to 1.21.x | Java 21 |
| 26.1 and newer (year-based versions) | Java 25 |
| Bedrock (PowerNukkitX) | Java 21 |

Each server's launcher script checks for the Java version it needs every time it starts and installs it if it is missing, so servers with different versions can live side by side. Versions older than 1.17 are not supported. If you installed a server under an older AnMC, just press **Start**; the launcher is refreshed automatically.

---

## Sharing your server with friends

AnMC does not open ports on your router. Friends reach your phone through a Playit tunnel.

1. Press **Start**. The first time, a Playit link appears in the console. Tap **Open playit link** and finish logging in.
2. On [playit.gg](https://playit.gg), create a tunnel that points at your phone:
   - **Java servers:** a *Minecraft Java (TCP)* tunnel to `127.0.0.1` and your server's port.
   - **Bedrock servers:** a *Minecraft Bedrock (UDP)* tunnel to `127.0.0.1` and your server's port.
3. Paste the address Playit gives you into the server's **Public address** card (the app also tries to detect it; the **Detect** button asks Playit directly). Use **Copy** or **Share** to send it to friends.
4. Friends join in Minecraft under *Multiplayer > Add Server* (Java) or *Play > Servers > Add Server* (Bedrock, where the address and port go in separate boxes).

The Playit agent is shared by all servers, so you only link it once.

---

## Java and Bedrock

### Java

Paper, Fabric, Forge and Vanilla servers are downloaded from their official sources (PaperMC, Fabric, MinecraftForge and Mojang). The **Game settings** window edits common `server.properties` options.

### Bedrock

Mojang's official Bedrock Dedicated Server only runs on PCs (x86), so it cannot run on an Android phone. AnMC installs [PowerNukkitX](https://github.com/PowerNukkitX/PowerNukkitX) instead, a Bedrock server written in Java. Things to know:

- It follows the newest Bedrock version PowerNukkitX supports, and players need a matching client version.
- Some features and behaviours differ from the official server.
- **Network transport.** Playit's tunnel needs `transport=raknet` in the server's `server.properties`. AnMC adds it automatically at install and at start if it is missing, and **Game settings** lets you switch between `raknet` and `nethernet`. PowerNukkitX is a different server from the official one, so if Playit still cannot connect, check that the setting is being picked up and report what you find.
- Bedrock servers do not have a Mods/Plugins tab. The Players tab sends the same commands, but some replies may be worded differently.
- It is heavier than a Java server. A phone with 6 GB of RAM or more is recommended.

---

## Mods and plugins

The tab is called **Plugins** for Paper and **Mods** for Fabric and Forge. It is hidden on Vanilla and Bedrock servers.

- **Modrinth:** no key needed. Results are filtered to your server type, Minecraft version and projects that work on servers. Downloads are checked against SHA-512, and required dependencies are installed automatically.
- **CurseForge:** create a free key at [console.curseforge.com](https://console.curseforge.com), paste it into the app and tap **Save**. The key is stored only on your phone. Downloads are checked against SHA-1 where CurseForge provides it, and required dependencies are installed automatically. Some authors block downloads from outside CurseForge, and the app tells you when that happens.
- **Direct link:** paste an `https://` link to a `.jar`. Only use sources you trust.
- **Managing files:** the tab lists what is installed and lets you remove files. Restart the server after adding or removing anything.

Mods and plugins run code with full access to your server. Only install ones you trust.

---

## Backups

- **Back up now** archives the world folders as a `.tar.gz`. For a running server the world is saved first, and saving is paused briefly so the copy is consistent.
- **Restore** needs the server to be stopped. The current world is first saved as a `before-restore-...` backup, so a restore can be undone.
- **Export** copies a backup to your phone's **Downloads** folder. Run `termux-setup-storage` in Termux once and allow access for this to work.
- **Delete** removes a backup from the phone.

---

## Where files live in Termux

```
~/servers/<id>/        each server's files: world, jar, start.sh, server.properties
~/server/              a server made by an older AnMC (imported as "My server")
~/anmc-backups/<id>/   world backups for each server
~/anmc/console.log     the shared console log
~/anmc/*.sh            small helper scripts for Playit
~/playit-bin/          the Playit agent and CLI
~/.config/playit_gg/   your saved Playit login
```

Termux's storage is private to Termux, so Android's file manager cannot open it. Use the app's **Export** for backups. The package name (`com.anmc.net`) and folder names (`~/anmc`) keep the original project name so existing installs keep their data.

---

## Building from source

1. Clone the repo and open it in Android Studio.
2. Make sure `AndroidManifest.xml` includes:

   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   <uses-permission android:name="com.termux.permission.RUN_COMMAND" />

   <queries>
       <package android:name="com.termux" />
   </queries>
   ```

   On the activity, it is also recommended to add the attribute below so the screen is not rebuilt (and the install progress lost) when the phone rotates or changes theme:

   ```xml
   android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden|uiMode|density|fontScale|layoutDirection|locale"
   ```

3. Set the app label (`app_name` in `res/values/strings.xml`) to **AnMC**.
4. Build and run on a real device. Termux has to be installed on the same phone, so the emulator is not much use.

The app is a single-activity Jetpack Compose project (Material 3) with no extra libraries. It uses only `HttpURLConnection` and the built-in `org.json`.

### Project layout

| File | What it does |
|---|---|
| `MainActivity.kt` | Entry point, theme, welcome guide, Termux permission helpers and the app root. |
| `HomeScreen.kt` | Java/Bedrock tabs, server cards, the **+** button, and the create/edit/delete dialogs. |
| `ServerScreen.kt` | A server's screen: controls, address, console, game settings, and the tab bar. |
| `ModsTab.kt` | The mods/plugins tab. |
| `ToolsTabs.kt` | The Players and Backups tabs. |
| `AppController.kt` | All app state and logic. It reads the messages the Termux scripts print. No UI. |
| `Scripts.kt` | Builds every shell script sent to Termux (install, start, stop, backups, mods). Plain Kotlin. |
| `ServerModel.kt` | What a server is, how the list is saved, and validation. Plain Kotlin. |
| `Properties.kt` | The `server.properties` settings for Java and Bedrock, with their load/save scripts. |
| `Providers.kt` | Modrinth, CurseForge and direct-link lookups. |

`Scripts.kt`, `ServerModel.kt`, `Properties.kt` and `Providers.kt` contain no Android or Compose code, so they can be compiled and tested on a normal JVM.

---

## How it works

- The app sends shell scripts to Termux through the `RUN_COMMAND` intent.
- Each server runs in its own `tmux` session (`mc_<id>`). Playit runs in a `playit` session. Their output goes to `~/anmc/console.log`.
- A small `socat` bridge streams that log to the app on `127.0.0.1:8765`. This powers the console, install progress, backup and mod status, and player tracking.
- Scripts report back by printing lines of the form `ANMC_X:KIND:TOKEN:PAYLOAD`. The token ties each reply to the action that caused it, so old log lines are ignored.
- Playit runs under `proot` so it can find Termux's DNS settings on Android.
- Mod and plugin searches run in the app. The downloads run in Termux.

---

## Privacy and security

- There are no accounts, ads or analytics. The app only contacts Modrinth and CurseForge, and only when you search or install.
- Everything else (downloads, installs, the server itself) happens inside Termux on your phone.
- The console bridge listens on `127.0.0.1` only, so other devices cannot reach it. Other apps on the same phone technically could.
- `allow-external-apps` lets apps that hold the *Run commands in Termux environment* permission run commands in Termux. Only enable it if you trust the apps you install.
- Server names, versions, file names and similar inputs are validated before they reach a shell script. Mod downloads come only from the providers' official download hosts (unless you choose a direct link), over HTTPS, and are checked against the provider's checksum when one is given.
- Turning off online authentication in Game settings lets anyone join with any name. Only do that if you understand the risk.

---

## Troubleshooting

| Problem | Try this |
|---|---|
| "Couldn't reach Termux" | Check that Termux is installed, `allow-external-apps` is enabled (restart Termux afterwards), and the *Run commands in Termux environment* permission is granted. |
| Console says "waiting for Termux" | Open Termux once, then return to the app and tap the refresh button on the home screen. The bridge starts on the next status check or Start. |
| "Another server is running" | Only one server can run at a time. Stop the running one first. |
| A 26.x server will not start | Java 25 is installed automatically on the first Start, which can take a few minutes. Watch the console. Make sure you have enough free storage. |
| Server stops when the screen is off | Set Termux battery saver to **No restrictions** and turn on **Autostart** (Xiaomi). |
| Bedrock friends cannot connect | Use a *Minecraft Bedrock (UDP)* tunnel, make sure the transport is `raknet` in Game settings, and check that the players' client version matches the server. |
| Public address not detected | Tap **Edit** and paste it from your Playit dashboard, or tap **Detect**. Detection depends on Playit's log format. |
| Install failed | Read the last lines in the console, check your internet and storage, then tap **Install** to retry. |
| Mod install fails verification | Try again. If it keeps failing, use a different version or provider. |
| CurseForge 401 or 403 error | The API key is wrong or missing. Generate a new one and save it again. |
| Export to Downloads fails | Run `termux-setup-storage` in Termux once and allow access. |

---

## Limitations

- Only one server can run at a time.
- Bedrock uses PowerNukkitX, not Mojang's official server. Behaviour can differ and it targets the newest Bedrock version only.
- Playit tunnels are created on the Playit website, not in the app.
- Phones can overheat and throttle under load. Use modest RAM and view distance settings, and keep the phone charged and cool.
- Playit address detection and player-list parsing depend on log formats from third parties and may need adjusting if those change.
- The app and its scripts are young. Expect rough edges and please report them.

---

## Contributing

Bug requests and pull requests are welcome. Open an issue on GitHub, or ask in the [Discord](https://discord.gg/cRTyfMcg2t). When reporting a bug, include your phone model and Android version, the server type and version, and the last lines of the in-app console.

---

## Credits

[Termux](https://termux.dev), [Playit](https://playit.gg), [PaperMC](https://papermc.io), [Fabric](https://fabricmc.net), [MinecraftForge](https://minecraftforge.net), [PowerNukkitX](https://github.com/PowerNukkitX/PowerNukkitX), [Modrinth](https://modrinth.com) and [CurseForge](https://www.curseforge.com). All trademarks belong to their owners.

---

## License

Apache 2.0
