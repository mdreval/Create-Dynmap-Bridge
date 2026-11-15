# Create-Railways-Dynmap-Bridge v1.0.0.6

<img width="500" height="257" alt="2026-1" src="https://github.com/user-attachments/assets/9a6f05c5-413b-469d-bc84-d290a428ef5d" />
<img width="257" height="257" alt="Create-Railways-Dynmap-Bridge" src="https://github.com/user-attachments/assets/651b2e3c-9b9c-4a76-9d9e-89bf68ac7e5e" />

### 🌟 What's New

- **Fixed once-per-second server lag spikes (trains and mobs stuttering).** The scan ran every second on the main thread and looked up every Create/Dynmap method through reflection again and again. On Youer each lookup goes through the server's remapper, so a single scan took 200–470 ms. Reflection lookups are now cached once per class, Bukkit calls are direct, and the blacklist files are checked once per update instead of once per marker.
- **Separate update intervals:** trains every 5 s (`train-update-interval-seconds`), stations every 60 s (`station-update-interval-seconds`).
- **Markers are only touched when something changed** (label, world, position or description).
- **Removed trains and stations now disappear from the map.** Stale-marker cleanup never ran before (Dynmap returns a `Set`, the old code expected a `Map`). Train markers are removed after 3 missed updates.

### ⚙️ Technical Requirements

- Java 21+
- Minecraft 1.21.1 (NeoForge 21.1.265 / Youer / Paper)
- Create Mod & Dynmap
