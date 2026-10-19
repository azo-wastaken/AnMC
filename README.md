
# PocketHost

[![Discord Badge](https://img.shields.io/badge/Discord-Join%20Community-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/cRTyfMcg2t)

[![GitHub Release](https://img.shields.io/github/v/release/your-username/your-repo?style=for-the-badge&color=238636)](https://github.com/azo-wastaken/PocketHost/releases)

**Host a Minecraft Java server from your Android phone.**

PocketHost is an Android app that installs and runs a Minecraft Java Edition server inside [Termux](https://termux.dev) and shares it online through a [Playit](https://playit.gg) tunnel. There's no port forwarding, no PC and no paid host.

> Unofficial tool. Not affiliated with, endorsed by, or approved by Mojang or Microsoft.

---

## Features

| Area | What you get |
|---|---|
| **Install** | Paper, Fabric, Forge or Vanilla. You pick the Minecraft version and RAM, and a live progress bar shows the install. |
| **Start / Stop** | One tap starts the server and the Playit tunnel. Stop saves the world and shuts both down cleanly. |
| **Public address** | The Playit address is shown in the app, with Copy and Share. You can edit it manually if it isn't detected. |
| **Console** | A live server log and a command box. |
| **Players** | Online list (refreshes every 10 s) with whitelist, op, kick, ban and pardon actions. |
| **Mods / Plugins** | Search Modrinth or CurseForge, or use a direct link. Files install straight into the server. Modrinth installs also pull in required dependencies. Disabled on Vanilla. |
| **Backups** | Back up the world (overworld, Nether and End) while the server runs. Restore later, with an automatic pre-restore safety copy. |
| **Server properties** | Edit common `server.properties` options in the app. A timestamped backup is made on every save. |
| **Playit management** | Relink or unlink the Playit agent on the phone. |
| **UI** | Light and dark themes, plus a first-launch guide that can be reopened with the **?** button. |

---

## Requirements

- Android phone with enough free storage and a reliable internet connection
- [Termux](https://termux.dev) installed from a trusted source (F-Droid or GitHub releases). The Play Store build is outdated.
- A free [Playit](https://playit.gg) account for the public address
- *(Optional)* a free CurseForge API key for the CurseForge provider. Modrinth needs no key.

---

## First-time setup

1. **Install Termux** and open it once.
2. **Install PocketHost** and open it. The welcome guide walks through the next two steps.
3. **Enable external apps in Termux.** Tap **Set up Termux access** in the app. It copies a command and opens Termux. Paste it, press Enter, then fully close and reopen Termux.
4. **Grant the permission.** Tap **Grant permission** for *Run commands in Termux environment*. If no dialog appears, use **Open app settings**, then Permissions (or Additional permissions), and turn it on.
5. **Install a server.** Choose a type, version and RAM, accept the Minecraft EULA and tap **Install / change**. The first install takes about 5–20 minutes.
6. **Start the server.** Tap **Start**. When a Playit link appears, tap **Open playit link** and finish linking.
7. **Share the address.** Copy it from the **Public address** card. Friends join with it in Minecraft Java Edition under *Multiplayer > Add Server*.

---

## Mods and plugins

The tab is called **Mods** for Fabric and Forge and **Plugins** for Paper. It is unavailable on Vanilla.

- **Modrinth:** no key needed. Results are filtered to your server type, Minecraft version and server-compatible projects. Downloads are verified with SHA-512, and required dependencies are installed automatically.
- **CurseForge:** create a free key at [console.curseforge.com](https://console.curseforge.com), paste it into the app and tap **Save**. The key is stored only on your phone. Some authors disable third-party downloads, and the app tells you when that happens. Dependencies are not installed automatically.
- **Direct link:** paste an `https://` link to a `.jar`. Only use sources you trust.

Files are downloaded by Termux into `~/server/mods` or `~/server/plugins`. Restart the server to load changes.

---

## Backups

- Backups are saved in Termux at `~/anmc/backups/` as `.tar.gz` files.
- If you run `termux-setup-storage` in Termux once, each backup is also copied to `PocketHost-Backups` in your phone's shared storage.
- **Restore** requires the server to be stopped. The current world is saved as a `pre-restore-…` backup first.

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

3. Set the app label (`app_name` in `res/values/strings.xml`) to **PocketHost** if you want the name to match.
4. Build and run on a device.

The app is a single-activity Jetpack Compose project (Material 3). It uses only `HttpURLConnection` and the built-in `org.json`, so there are no extra networking dependencies.

> The package name and the Termux data folder still use the original `anmc` name (`com.anmc.net`, `~/anmc`). They were kept on purpose so existing installs keep their backups and settings.

---

## How it works

- The app sends shell scripts to Termux through the `RUN_COMMAND` intent.
- The server and the Playit agent run in `tmux` sessions inside Termux, and their output goes to `~/anmc/console.log`.
- A small `socat` bridge streams that log to the app on `127.0.0.1:8765`, which powers the console, install progress, backup and mod status, and player tracking.
- Mod and plugin searches run in the app. The downloads themselves run in Termux.

---

## Troubleshooting

| Problem | Try this |
|---|---|
| "Couldn't reach Termux" | Check that Termux is installed, `allow-external-apps` is enabled (restart Termux afterwards), and the *Run commands in Termux environment* permission is granted. |
| Console says "waiting for Termux" | Open Termux once, then return to the app. The bridge starts on the next status check or Start. |
| Public address not detected | Tap **Edit** and paste the address from your Playit dashboard. Detection depends on Playit's log format. |
| Install failed | Read the last lines in the console, check your internet and storage, then tap **Install / change** to retry. |
| Server stops when the screen is off | Keep Termux allowed to run in the background and exclude it from battery optimisation. |
| Mod install fails verification | Try again. If it keeps failing, use a different version or provider. |
| CurseForge 401 or 403 error | The API key is wrong or missing. Generate a new one and save it again. |

---

## Limitations

- Installs a **Java Edition** server. Bedrock players need a bridge such as [Geyser](https://geysermc.org), set up separately.
- Phones can overheat and throttle under load. Use modest RAM and view distance settings, and keep the phone charged and cool.
- Playit address detection and some player-list parsing depend on third-party log formats and may need adjusting if those change.

---

## License

Apache 2.0
