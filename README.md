# Create-Railways-Dynmap-Bridge

**Create-Railways-Dynmap-Bridge** is a lightweight bridge between the Create mod's railway system and Dynmap for Minecraft servers.

<img width="500" height="257" alt="2026-1" src="https://github.com/user-attachments/assets/9a6f05c5-413b-469d-bc84-d290a428ef5d" />
<img width="257" height="257" alt="Create-Railways-Dynmap-Bridge" src="https://github.com/user-attachments/assets/651b2e3c-9b9c-4a76-9d9e-89bf68ac7e5e" />

The plugin displays Create railway stations and trains on the interactive Dynmap map, helping players navigate the railway network. It supports configurable marker icons, automatic dimension detection, world mapping, separate blacklists, throttled file exports, and administrative commands.

## ✨ Features

- Automatically displays Create railway stations on Dynmap.
- Displays Create trains on the interactive map in real time.
- **Configurable Text Exports:** Periodically exports all active station and train names into `stations.txt` and `trains.txt` with a customizable update interval to optimize server I/O performance.
- **In-Game Administrative Commands:** Convenient `/getstations` and `/gettrains` commands to list railway objects directly in-game or from the console.
- Configurable station and train marker icons.
- Preserved YAML comments and helpful icon listings in `config.yml`.
- Automatically detects Create dimensions.
- Supports mapping Create dimensions to server worlds with manual overrides in `config.yml`.
- Separate station and train blacklists.
- Automatically updates and cleans up stale railway markers.

## 📋 Requirements

- **Minecraft:** 1.21.1 (or compatible version).
- **Java:** Java 21+ (depending on your server setup).
- **Create Mod:** Required for access to the railway system.
- **Dynmap:** Required to display railway markers on the map.
- **Compatible server environment:** A hybrid Bukkit-compatible server software with mod support (e.g., Arclight, Mohist, Magma).

## ⚙️ Installation

1. Install Create and Dynmap on a compatible server.
2. Download `Create-Railways-Dynmap-Bridge-1.0.0.4.jar`.
3. Place the JAR file in your server's `plugins` directory.
4. Start or restart the server.
5. Check the plugin's generated configuration files and data folders.
6. Open Dynmap and verify that railway markers appear on the map.

## 💻 Commands & Permissions

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/getstations` | `create.stations.admin` | Displays a list and total count of all active Create railway stations. |
| `/gettrains` | `create.stations.admin` | Displays a list and total count of all active Create trains. |

## 📁 File Exports & Throttling

The plugin periodically creates and updates two plain-text files inside the plugin folder (`plugins/Create-Railways-Dynmap-Bridge/`):

- **`stations.txt`** — Contains a clean list of all scanned railway station names (one per line).
- **`trains.txt`** — Contains a clean list of all active train names (one per line).

By default, file exports occur every **3600 seconds (1 hour)** to minimize disk reads and writes. Live markers on Dynmap continue to update every second independently.

## 🗺️ Configuration

The plugin uses `config.yml` to configure marker icons, export frequencies, and world mappings.

### Options Overview

- `station-icon` — Sets the icon used for railway stations on Dynmap.
- `train-icon` — Sets the icon used for trains on Dynmap.
- `export-interval-seconds` — Controls how frequently `stations.txt` and `trains.txt` are written to disk (default: 3600 = 1 hour).

### World Mapping

The plugin supports automatic detection of Create dimensions and allows you to manually map dimensions to server worlds in `config.yml`.

### Blacklists

- **`blacklist-stations.yml`** — Exclude specific railway stations from Dynmap.
- **`blacklist-trains.yml`** — Exclude specific trains from Dynmap.

## 📦 Version History

### Version 1.0.0.5
- **NeoForge 1.21.1 (Build 265) Compatibility Fix:** Fixed the startup crash caused by an `IllegalArgumentException` / `argument type mismatch`. Completely removed brittle Java reflection for the task scheduler and replaced it with direct, safe `BukkitScheduler` calls, ensuring stable operation on NeoForge 21.1.265 and earlier versions.

### Version 1.0.0.4
- **Configurable File Export Interval:** Added `export-interval-seconds` (default: 3600s) to control how often `stations.txt` and `trains.txt` write to disk.
- **Performance Optimization:** Separated fast live Dynmap marker scans (1s) from background file export timers to reduce disk I/O.
- **Comment Preservation:** Switched to standard default configuration saving (`saveDefaultConfig()`) so all helpful comments and icon listings remain intact in `config.yml`.

### Version 1.0.0.3
- **Train Export & Command:** Added `trains.txt` auto-export and the `/gettrains` administrative command.
- **Station Export & Command:** Auto-exports `stations.txt` and adds the `/getstations` command.
- **Improved Reliability:** Enhanced world detection and background scan routines.

## 📄 License

**Copyright © 2026 Igrobar.**

All rights reserved. Usage, redistribution, and re-upload permissions are determined by the plugin author.