# Create-Railways-Dynmap-Bridge

**Create-Railways-Dynmap-Bridge** is a lightweight bridge between the Create mod's railway system and Dynmap for Minecraft servers.

<img width="500" height="257" alt="2026-1" src="https://github.com/user-attachments/assets/9a6f05c5-413b-469d-bc84-d290a428ef5d" />
<img width="257" height="257" alt="Create-Railways-Dynmap-Bridge" src="https://github.com/user-attachments/assets/651b2e3c-9b9c-4a76-9d9e-89bf68ac7e5e" />

The plugin displays Create railway stations and trains on the interactive Dynmap map, helping players navigate the railway network. It supports configurable marker icons, automatic dimension detection, world mapping, separate blacklists, real-time file exports, and administrative commands.

## ✨ Features

- Automatically displays Create railway stations on Dynmap.
- Displays Create trains on the interactive map in real time.
- **Automatic Text Exports:** Periodically exports all active station and train names into `stations.txt` and `trains.txt` for easy copying or external usage.
- **In-Game Administrative Commands:** Convenient `/getstations` and `/gettrains` commands to list railway objects directly in-game or from the console.
- Configurable station and train marker icons.
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
2. Download `Create-Railways-Dynmap-Bridge-1.0.0.3.jar`.
3. Place the JAR file in your server's `plugins` directory.
4. Start or restart the server.
5. Check the plugin's generated configuration files and data folders.
6. Open Dynmap and verify that railway markers appear on the map.

## 💻 Commands & Permissions

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/getstations` | `create.stations.admin` | Displays a list and total count of all active Create railway stations. |
| `/gettrains` | `create.stations.admin` | Displays a list and total count of all active Create trains. |

## 📁 File Exports

During each scan cycle, the plugin automatically creates and updates two plain-text files inside the plugin folder (`plugins/Create-Railways-Dynmap-Bridge/`):

- **`stations.txt`** — Contains a clean list of all scanned railway station names (one per line).
- **`trains.txt`** — Contains a clean list of all active train names (one per line).

## 🗺️ Configuration

The plugin uses `config.yml` to configure marker icons and world mappings.

### Marker Icons

The default configuration uses the following settings:

```yaml
station-icon: "bank"
train-icon: "bricks"