# Create-Railways-Dynmap-Bridge



**Create-Railways-Dynmap-Bridge** is a lightweight bridge between the Create mod's railway system and Dynmap for Minecraft servers.

<img width="500" height="257" alt="2026-1" src="https://github.com/user-attachments/assets/9a6f05c5-413b-469d-bc84-d290a428ef5d" />
<img width="257" height="257" alt="Create-Railways-Dynmap-Bridge" src="https://github.com/user-attachments/assets/651b2e3c-9b9c-4a76-9d9e-89bf68ac7e5e" />


The plugin displays Create railway stations and trains on the interactive Dynmap map, helping players navigate the railway network. It supports configurable marker icons, automatic dimension detection, world mapping, and separate blacklists for stations and trains.

## ✨ Features

- Automatically displays Create railway stations on Dynmap.
- Displays Create trains on the interactive map.
- Configurable station and train marker icons.
- Automatically detects Create dimensions.
- Supports mapping Create dimensions to server worlds.
- Allows manual world mapping overrides through `config.yml`.
- Separate station and train blacklists.
- Automatically updates railway markers.
- Makes it easier to manage railway markers without manually adding every station.
- Allows specific stations and trains to be excluded from the map.

## 📋 Requirements

- **Minecraft:** A version compatible with the installed server API and Create mod.
- **Java:** A version required by your server software.
- **Create:** Required for access to the railway system.
- **Dynmap:** Required to display railway markers on the map.
- **Compatible server environment:** Must provide the necessary Bukkit-compatible API and access to Create mod data.

**Important:** A standard Bukkit, Paper, or Spigot server does not necessarily support Create. Your server environment must provide compatibility with both the required server API and the Create mod.

## ⚙️ Installation

1. Install Create and Dynmap on a compatible server.
2. Download `Create-Railways-Dynmap-Bridge-1.0.0.1.jar`.
3. Place the JAR file in your server's `plugins` directory.
4. Start or restart the server.
5. Check the plugin's generated configuration files.
6. Open Dynmap and verify that railway markers appear on the map.

## 🗺️ Configuration

The plugin uses `config.yml` to configure marker icons and world mappings.

### Marker Icons

The default configuration uses the following settings:

```yaml
station-icon: "bank"
train-icon: "bricks"
```

- `station-icon` — Sets the icon used for railway stations.
- `train-icon` — Sets the icon used for trains.

Use icon identifiers supported by your installed Dynmap version.

### World Mapping

The plugin supports automatic detection of Create dimensions and allows you to manually map dimensions to server worlds.

This is useful for servers with multiple worlds or custom world names.

In most cases, automatic detection should reduce the need for manual configuration. If multiple server worlds could correspond to the same dimension, you can adjust the mapping in `config.yml`.

### Station Blacklist

The `blacklist-stations.yml` file allows you to exclude specific railway stations from Dynmap.

### Train Blacklist

The `blacklist-trains.yml` file allows you to exclude specific trains from Dynmap.

Separate blacklist files make it easier to control which railway objects are displayed.

## 🚆 Dynmap Markers

The plugin adds markers for the Create railway system to your Dynmap map.

### Railway Stations

- Displayed using a configurable station icon.
- Help players locate railway stations across the server.

### Trains

- Displayed using a configurable train icon.
- Help players identify railway activity and navigate the railway network.

Marker update frequency depends on the plugin's synchronization logic.

## 🧹 Marker Management

The plugin provides separate blacklists for stations and trains, allowing you to hide selected objects without disabling the entire railway map.

This helps keep Dynmap organized on servers with large railway networks.

## 📦 What's New in Version 1.0.0.1

- Integration between Create's railway system and Dynmap.
- Railway station and train markers.
- Configurable marker icons.
- Automatic dimension detection.
- Manual world mapping overrides.
- Separate blacklists for railway stations and trains.

## 📄 License

Add the actual license and distribution terms for your plugin before publishing.

If the plugin is proprietary, you can use the following notice:

**Copyright © 2026 Igrobar.**

Usage, redistribution, and re-upload permissions are determined by the plugin author.