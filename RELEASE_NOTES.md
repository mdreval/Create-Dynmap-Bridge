# Create-Railways-Dynmap-Bridge v1.0.0.4

<img width="500" height="257" alt="2026-1" src="https://github.com/user-attachments/assets/9a6f05c5-413b-469d-bc84-d290a428ef5d" />
<img width="257" height="257" alt="Create-Railways-Dynmap-Bridge" src="https://github.com/user-attachments/assets/651b2e3c-9b9c-4a76-9d9e-89bf68ac7e5e" />

### 🌟 What's New

- **Configurable File Export Interval:** Added the `export-interval-seconds` setting to `config.yml` (default: 3600 seconds / 1 hour). Both `stations.txt` and `trains.txt` now export on a throttled timer instead of updating every second, significantly reducing disk I/O.
- **Optimized Background Tasks:** Split execution into two separate tasks: rapid marker updates for Dynmap (every second) and throttled file exports for text lists.
- **Preserved Config Comments:** Re-implemented default config generation using `saveDefaultConfig()`, ensuring all YAML comments, guidelines, and icon lists remain intact when `config.yml` is created on the server.
- **In-Game Admin Commands:** Full support for `/getstations` and `/gettrains` commands (requiring the `create.stations.admin` permission) to inspect active railway lists directly from the server.

### ⚙️ Technical Requirements

- Java 17+ / 21+  
- Create Mod & Dynmap