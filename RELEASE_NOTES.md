# Create-Railways-Dynmap-Bridge 1.0.0.1

<img width="500" height="257" alt="2026-1" src="https://github.com/user-attachments/assets/9a6f05c5-413b-469d-bc84-d290a428ef5d" />
<img width="257" height="257" alt="Create-Railways-Dynmap-Bridge" src="https://github.com/user-attachments/assets/651b2e3c-9b9c-4a76-9d9e-89bf68ac7e5e" />
## 🌟 What's New

- **Create & Dynmap Integration:** Full real-time synchronization of Create mod railway stations and train locations directly on Dynmap.  
- **Real-Time Animation:** Animated train movements across map layers for smooth live tracking.  
- **Dimension & World Mapping:** Automatic dimension registration with manual configuration overrides in config.yml.  
- **Customizable Markers:** Support for custom station and train icons using standard Dynmap icon identifiers.  
- **Selective Blacklisting:** Added blacklist-stations.yml and blacklist-trains.yml to filter out specific stations or trains from the live map.  
- **CI/CD Automation:** Configured GitHub Actions workflow for building artifacts on push/PR and publishing JAR releases on v\* tags.

## ⚙️ Technical Requirements

- Java 21+  
- Target: Minecraft 1.21.1 on hybrid NeoForge/Bukkit server platforms (Arclight, Mohist, Magma, etc.)  
- Create Mod & Dynmap (3.7-beta8 or newer)