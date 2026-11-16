package com.igrobar.createdynmap;

import com.igrobar.createdynmap.web.Json;
import com.igrobar.createdynmap.web.MapData;
import com.igrobar.createdynmap.web.WebMapServer;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

public final class CreateRailwaysDynmapBridge extends JavaPlugin {
    /** How many updates in a row a train may be missing before its marker is removed. */
    private static final int TRAIN_MISSES_BEFORE_REMOVAL = 3;
    /** Default port of the live web map. */
    private static final int DEFAULT_WEB_PORT = 25850;

    private volatile boolean stopping;
    private final Set<String> warned = new HashSet<>();
    private final Map<String, String> worldOverrides = new LinkedHashMap<>();
    private final Set<String> availableWorlds = new LinkedHashSet<>();
    private final Map<String, String> worldCache = new HashMap<>();
    private final Map<String, Integer> trainMisses = new HashMap<>();
    private BlacklistFiles blacklist;
    private DynmapMarkers markers;
    private Object stationPointType;
    private boolean dynmapStations;
    private boolean dynmapTrains;
    private WebMapServer webMap;
    private RailwayMapCollector webCollector;
    private long webTrainIntervalMs;
    private long webNetworkRefreshMs;
    private Lang lang = Lang.of("en");

    @Override
    public void onEnable() {
        if (this.getCommand("getstations") != null) {
            this.getCommand("getstations").setExecutor(new StationCommand(this));
        }
        if (this.getCommand("gettrains") != null) {
            this.getCommand("gettrains").setExecutor(new TrainCommand(this));
        }
        if (this.getCommand("railmap") != null) {
            this.getCommand("railmap").setExecutor(new RailMapCommand(this));
        }

        // Saves config.yml from the jar with all comments if it does not exist yet
        this.saveDefaultConfig();
        // Append new sections (with comments) to config.yml from older versions without removing anything
        ConfigUpgrade.apply(this);
        this.reloadConfig();
        this.lang = Lang.of(this.getConfig().getString("language", "en"));

        File dataFolder = this.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs() && !dataFolder.isDirectory()) {
            throw new IllegalStateException("Cannot create plugin data folder: " + dataFolder);
        }
        this.blacklist = new BlacklistFiles(dataFolder.toPath());
        this.blacklist.reloadIfChanged();
        this.loadWorldOverrides();
        this.refreshAvailableWorlds();

        long trainTicks = this.intervalTicks("train-update-interval-seconds", 5L);
        long stationTicks = this.intervalTicks("station-update-interval-seconds", 60L);
        long exportTicks = this.intervalTicks("export-interval-seconds", 3600L);
        this.dynmapStations = this.getConfig().getBoolean("dynmap-stations", true);
        this.dynmapTrains = this.getConfig().getBoolean("dynmap-trains", true);
        // Dynmap is optional: without it only the Dynmap markers are skipped, everything else keeps working.
        if ((this.dynmapStations || this.dynmapTrains) && Bukkit.getPluginManager().getPlugin("dynmap") == null) {
            this.dynmapStations = false;
            this.dynmapTrains = false;
            this.getLogger().info("Dynmap is not installed — Dynmap markers are off. The web map and commands work without it.");
        }

        BukkitScheduler scheduler = Bukkit.getScheduler();
        // Stations rarely change, so they are updated rarely; trains more often. Main thread, but cheap.
        if (this.dynmapStations) {
            scheduler.runTaskTimer(this, this::safeStationScan, 100L, stationTicks);
        }
        if (this.dynmapTrains) {
            scheduler.runTaskTimer(this, this::safeTrainScan, 110L, trainTicks);
        }
        scheduler.runTaskTimer(this, this::saveTxtFiles, 200L, exportTicks);

        this.getLogger().info("Create Railways - Dynmap Bridge " + this.getDescription().getVersion() + " enabled; Dynmap stations: "
                + (this.dynmapStations ? "every " + stationTicks / 20 + "s" : "off") + ", Dynmap trains: "
                + (this.dynmapTrains ? "every " + trainTicks / 20 + "s" : "off") + ".");

        this.startWebMap(scheduler);
    }

    @Override
    public void onDisable() {
        this.stopping = true;
        this.markers = null;
        if (this.webMap != null) {
            this.webMap.stop();
            this.webMap = null;
        }
        Reflect.clear();
    }

    // ------------------------------------------------------------------ web map

    private void startWebMap(BukkitScheduler scheduler) {
        if (!this.getConfig().getBoolean("web-map.enabled", true)) {
            this.getLogger().info("Web map is disabled (web-map.enabled: false).");
            return;
        }
        String bind = this.getConfig().getString("web-map.bind-address", "0.0.0.0");
        int port = this.getConfig().getInt("web-map.port", DEFAULT_WEB_PORT);
        int maxViewers = this.getConfig().getInt("web-map.max-viewers", 50);
        double trainSeconds = Math.max(0.25, this.getConfig().getDouble("web-map.train-update-interval-seconds", 1.0));
        long trainTicks = Math.max(5L, Math.round(trainSeconds * 20.0));
        long networkTicks = this.intervalTicks("web-map.network-check-interval-seconds", 10L);
        this.webTrainIntervalMs = trainTicks * 50L;
        this.webNetworkRefreshMs = Math.max(30L, this.getConfig().getLong("web-map.network-refresh-seconds", 300L)) * 1000L;

        File webFolder = new File(this.getDataFolder(), "web");
        try {
            this.webMap = new WebMapServer(this.getLogger(), this.getClassLoader(), webFolder.toPath().toAbsolutePath().normalize(),
                    maxViewers, this.webConfigJson());
            this.webMap.start(bind, port);
        } catch (Throwable throwable) {
            if (this.webMap != null) {
                this.webMap.stop();
            }
            this.webMap = null;
            this.getLogger().warning("Web map could not start on " + bind + ":" + port + " — " + throwable
                    + ". Is the port already in use? Change web-map.port in config.yml.");
            return;
        }
        this.webCollector = new RailwayMapCollector(this);
        scheduler.runTaskTimer(this, this::safeWebNetworkScan, 60L, networkTicks);
        scheduler.runTaskTimer(this, this::safeWebTrainScan, 80L, trainTicks);
        this.getLogger().info("Web map started on port " + port + ": " + this.webMapUrl()
                + " (trains every " + this.webTrainIntervalMs + " ms).");
    }

    /** Map link for the console: web-map.public-url or http://<server-ip>:<port>/. */
    String webMapUrl() {
        return this.webMapUrl(null);
    }

    /**
     * Map link for a command sender.
     * Order: web-map.public-url → the address the player used to join the server
     * (e.g. "play.example.com") → server-ip from server.properties → "<server-ip>".
     */
    String webMapUrl(org.bukkit.command.CommandSender sender) {
        String url = this.getConfig().getString("web-map.public-url", "");
        if (url != null && !url.isBlank()) {
            return url;
        }
        String host = sender == null ? null : joinHost(sender);
        if (host == null) {
            host = Bukkit.getIp();
        }
        if (host == null || host.isBlank() || host.equals("0.0.0.0")) {
            host = "<server-ip>";
        }
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]"; // IPv6 literal
        }
        return "http://" + host + ":" + this.getConfig().getInt("web-map.port", DEFAULT_WEB_PORT) + "/";
    }

    /** The host name a player typed to join (Paper's Player#getVirtualHost), or null if unknown. */
    private static String joinHost(org.bukkit.command.CommandSender sender) {
        try {
            Object address = Reflect.optionalCall(sender, "getVirtualHost");
            if (!(address instanceof java.net.InetSocketAddress socket)) {
                return null;
            }
            String host = socket.getHostString();
            if (host == null) {
                return null;
            }
            int marker = host.indexOf('\0'); // modded clients may append "\0FML…\0" to the host
            if (marker >= 0) {
                host = host.substring(0, marker);
            }
            while (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            host = host.trim();
            boolean useless = host.isEmpty() || (host.equalsIgnoreCase("localhost") && !isLocalSender(sender));
            return useless ? null : host;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isLocalSender(org.bukkit.command.CommandSender sender) {
        Object address = Reflect.optionalCall(sender, "getAddress");
        return address instanceof java.net.InetSocketAddress socket && socket.getAddress() != null
                && socket.getAddress().isLoopbackAddress();
    }

    boolean isWebMapRunning() {
        return this.webMap != null;
    }

    int webMapViewers() {
        return this.webMap == null ? 0 : this.webMap.viewers();
    }

    private String webConfigJson() {
        Json json = new Json(512);
        json.beginObject()
                .field("language", this.lang.code())
                .field("title", this.getConfig().getString("web-map.title", ""))
                .field("defaultDimension", this.getConfig().getString("web-map.default-dimension", "minecraft:overworld"))
                .field("stationLabels", this.getConfig().getBoolean("web-map.show-station-labels", true))
                .field("dynmapUrl", this.getConfig().getString("web-map.dynmap-url", ""))
                .field("version", this.getDescription().getVersion());
        json.key("dimensions").beginObject();
        ConfigurationSection dimensions = this.getConfig().getConfigurationSection("web-map.dimensions");
        if (dimensions != null) {
            for (String key : dimensions.getKeys(false)) {
                json.field(key, dimensions.getString(key, key));
            }
        }
        json.endObject().endObject();
        return json.toString();
    }

    private void safeWebTrainScan() {
        if (this.stopping || this.webMap == null) {
            return;
        }
        try {
            RailwayDataSnapshot snapshot = this.railwayDataSnapshot();
            if (snapshot == null || !(snapshot.trains instanceof Map<?, ?> trains)) {
                return;
            }
            this.blacklist.reloadIfChanged();
            List<MapData.TrainInfo> list = this.webCollector.trains(trains);
            this.webMap.publishTrains(list, System.currentTimeMillis(), this.webTrainIntervalMs);
        } catch (Throwable throwable) {
            Throwable cause = Reflect.unwrap(throwable);
            this.warnOnce("web-trains:" + cause.getClass().getName(), "Web map train scan failed: " + cause);
        }
    }

    private void safeWebNetworkScan() {
        if (this.stopping || this.webMap == null) {
            return;
        }
        try {
            RailwayDataSnapshot snapshot = this.railwayDataSnapshot();
            if (snapshot == null || !(snapshot.graphs instanceof Map<?, ?> graphs)) {
                return;
            }
            this.blacklist.reloadIfChanged();
            if (this.webCollector.networkNeedsRebuild(graphs, this.webNetworkRefreshMs)) {
                this.webMap.publishNetwork(this.webCollector.network(graphs));
            }
            this.webMap.publishStations(this.webCollector.stations(graphs));
        } catch (Throwable throwable) {
            Throwable cause = Reflect.unwrap(throwable);
            this.warnOnce("web-network:" + cause.getClass().getName(), "Web map track scan failed: " + cause);
        }
    }

    Lang lang() {
        return this.lang;
    }

    boolean isBlacklisted(String markerId, String label) {
        return this.blacklist != null && this.blacklist.isBlacklisted(markerId, label);
    }

    private long intervalTicks(String key, long defaultSeconds) {
        long seconds = this.getConfig().getLong(key, defaultSeconds);
        return Math.max(20L, seconds * 20L);
    }

    // ------------------------------------------------------------------ scanning

    private void safeTrainScan() {
        if (this.stopping) {
            return;
        }
        try {
            this.trainScan();
        } catch (Throwable throwable) {
            Throwable cause = Reflect.unwrap(throwable);
            this.warnOnce("train-scan:" + cause.getClass().getName(), "Create train scan failed: " + cause);
        }
    }

    private void safeStationScan() {
        if (this.stopping) {
            return;
        }
        try {
            this.stationScan();
        } catch (Throwable throwable) {
            Throwable cause = Reflect.unwrap(throwable);
            this.warnOnce("station-scan:" + cause.getClass().getName(), "Create station scan failed: " + cause);
        }
    }

    /** Common cycle preparation: Dynmap, Create data, worlds, blacklists. */
    private RailwayDataSnapshot beginCycle() throws Exception {
        if (this.connectDynmap() == null) {
            return null;
        }
        RailwayDataSnapshot snapshot = this.railwayDataSnapshot();
        if (snapshot == null) {
            this.warnOnce("create", "Create railway manager/data unavailable. Is Create loaded and railway data initialized?");
            return null;
        }
        this.refreshAvailableWorlds();
        this.worldCache.clear();
        this.blacklist.reloadIfChanged();
        return snapshot;
    }

    private void trainScan() throws Exception {
        RailwayDataSnapshot snapshot = this.beginCycle();
        if (snapshot == null || !(snapshot.trains instanceof Map<?, ?> trains)) {
            return;
        }
        Set<String> seen = new HashSet<>();
        int count = TrainMarkerUpdater.update(this, trains, seen);
        if (count == 0 && !trains.isEmpty()) {
            this.warnOnce("no-train-positions", "Create has " + trains.size() + " train(s), but no carriage returned a position. Train markers will be retried.");
        } else if (count > 0) {
            this.warned.remove("no-train-positions");
        }

        // Remove markers of trains missing for several updates in a row (removed/disassembled trains)
        Set<String> existing = this.markers.markerIds("train_");
        this.trainMisses.keySet().retainAll(existing);
        for (String markerId : existing) {
            if (seen.contains(markerId)) {
                this.trainMisses.remove(markerId);
                continue;
            }
            int misses = this.trainMisses.merge(markerId, 1, Integer::sum);
            if (misses >= TRAIN_MISSES_BEFORE_REMOVAL) {
                this.markers.delete(markerId);
                this.trainMisses.remove(markerId);
            }
        }
    }

    private void stationScan() throws Exception {
        RailwayDataSnapshot snapshot = this.beginCycle();
        if (snapshot == null || !(snapshot.graphs instanceof Map<?, ?> graphs)) {
            return;
        }
        Object stationType = this.stationPointType();
        Set<String> seen = new HashSet<>();
        for (Object graph : graphs.values()) {
            Object points = Reflect.call(graph, "getPoints", stationType);
            if (!(points instanceof Iterable<?> stations)) {
                continue;
            }
            for (Object station : stations) {
                try {
                    Object position = this.stationPosition(graph, station);
                    if (position == null) {
                        throw new IllegalStateException("Could not resolve station position from its track edge");
                    }
                    double x = Reflect.number(Reflect.fieldOrMethod(position, "x", "x"));
                    double y = Reflect.number(Reflect.fieldOrMethod(position, "y", "y"));
                    double z = Reflect.number(Reflect.fieldOrMethod(position, "z", "z"));
                    String world = this.dynmapWorld(this.stationDimension(station));
                    String name = Reflect.text(Reflect.fieldOrMethod(station, "name", "getName"), "Unnamed station");
                    Object id = Reflect.fieldOrMethod(station, "id", "getId");
                    String markerId = "station_" + Reflect.safe(id == null ? name + "_" + world + "_" + (int) x + "_" + (int) z : id.toString());
                    if (this.upsert(markerId, name, "Create station", world, x, y, z)) {
                        seen.add(markerId);
                    }
                } catch (Throwable throwable) {
                    Throwable cause = Reflect.unwrap(throwable);
                    this.warnOnce("station:" + cause.getClass().getName(), "Could not read a Create station: " + cause);
                }
            }
        }
        // Remove deleted stations from the map (only if the scan found something, so a failure does not wipe everything)
        if (!seen.isEmpty()) {
            for (String markerId : this.markers.markerIds("station_")) {
                if (!seen.contains(markerId)) {
                    this.markers.delete(markerId);
                }
            }
        }
    }

    // ------------------------------------------------------------------ Dynmap

    private DynmapMarkers connectDynmap() {
        Plugin dynmap = Bukkit.getPluginManager().getPlugin("dynmap");
        if (dynmap == null || !dynmap.isEnabled()) {
            this.markers = null;
            this.warnOnce("dynmap", "Dynmap Marker API unavailable; retrying.");
            return null;
        }
        if (this.markers != null && this.markers.isCurrent(dynmap)) {
            return this.markers;
        }
        try {
            this.markers = DynmapMarkers.connect(dynmap);
        } catch (Throwable throwable) {
            this.markers = null;
        }
        if (this.markers == null) {
            this.warnOnce("dynmap", "Dynmap Marker API unavailable; retrying.");
        }
        return this.markers;
    }

    /** Creates/updates a marker. Returns false if the marker is blacklisted or Dynmap is unavailable. */
    boolean upsert(String markerId, String label, String kind, String world, double x, double y, double z) throws Exception {
        if (this.markers == null) {
            return false;
        }
        if (this.blacklist.isBlacklisted(markerId, label)) {
            this.markers.delete(markerId);
            return false;
        }
        String description = kind + "<br/>" + world + " @ " + (int) x + ", " + (int) y + ", " + (int) z;
        String icon = markerId.startsWith("train_") ? this.configuredTrainIcon() : this.configuredStationIcon();
        this.markers.upsert(markerId, label, world, x, y, z, description, icon);
        return true;
    }

    // ------------------------------------------------------------------ Create data

    private RailwayDataSnapshot railwayDataSnapshot() throws Exception {
        try {
            Object manager = Reflect.staticField(Reflect.type("com.simibubi.create.Create"), "RAILWAYS");
            Object graphs = Reflect.fieldOrMethod(manager, "trackNetworks", "getTrackNetworks");
            Object trains = Reflect.fieldOrMethod(manager, "trains", "getTrains");
            if (mapSize(graphs) > 0 || mapSize(trains) > 0) {
                return new RailwayDataSnapshot(graphs, trains);
            }
        } catch (Throwable throwable) {
            this.warnOnce("live-manager", "Could not read Create.RAILWAYS directly; trying RailwaySavedData: " + Reflect.unwrap(throwable));
        }
        Object savedData = this.railwaySavedData();
        if (savedData == null) {
            return null;
        }
        return new RailwayDataSnapshot(Reflect.call(savedData, "getTrackNetworks"), Reflect.call(savedData, "getTrains"));
    }

    private static int mapSize(Object value) {
        return value instanceof Map<?, ?> map ? map.size() : 0;
    }

    private Object railwaySavedData() throws Exception {
        Object craftServer = Bukkit.getServer();
        Object minecraftServer = Reflect.optionalCall(craftServer, "getServer");
        if (minecraftServer == null) {
            minecraftServer = craftServer;
        }
        return Reflect.callStatic(Reflect.type("com.simibubi.create.content.trains.RailwaySavedData"), "load", minecraftServer);
    }

    Object stationPointType() throws Exception {
        if (this.stationPointType == null) {
            this.stationPointType = Reflect.staticField(Reflect.type("com.simibubi.create.content.trains.graph.EdgePointType"), "STATION");
        }
        return this.stationPointType;
    }

    Object stationPosition(Object graph, Object station) throws Exception {
        Object edgeLocation = Reflect.fieldOrMethod(station, "edgeLocation", "getEdgeLocation");
        if (edgeLocation == null) {
            return null;
        }
        Object first = Reflect.call(graph, "locateNode", Reflect.call(edgeLocation, "getFirst"));
        Object second = Reflect.call(graph, "locateNode", Reflect.call(edgeLocation, "getSecond"));
        if (first == null || second == null) {
            return null;
        }
        Object nodes = Reflect.callStatic(Reflect.type("net.createmod.catnip.data.Couple"), "create", first, second);
        Object edge = Reflect.call(graph, "getConnection", nodes);
        if (edge == null) {
            return null;
        }
        double length = Reflect.number(Reflect.call(edge, "getLength"));
        double location = Reflect.number(Reflect.call(station, "getLocationOn", edge));
        double t = length == 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, location / length));
        return Reflect.call(edge, "getPosition", graph, t);
    }

    String stationDimension(Object station) {
        try {
            Object edgeLocation = Reflect.fieldOrMethod(station, "edgeLocation", "getEdgeLocation");
            Object first = Reflect.optionalCall(edgeLocation, "getFirst");
            if (first == null) {
                first = Reflect.optionalCall(edgeLocation, "getFirstValue");
            }
            Object dimension = first == null ? null : Reflect.fieldOrMethod(first, "dimension", "getDimension");
            if (dimension != null) {
                return this.dimensionId(dimension);
            }
        } catch (Throwable ignored) {
            // fall back to "world" below
        }
        return "world";
    }

    String dimensionId(Object dimension) {
        if (dimension == null) {
            return "world";
        }
        Object location = Reflect.optionalCall(dimension, "location");
        if (location != null) {
            return String.valueOf(location);
        }
        String text = String.valueOf(dimension);
        int index = text.lastIndexOf(" / ");
        if (index >= 0 && text.endsWith("]")) {
            return text.substring(index + 3, text.length() - 1);
        }
        return text;
    }

    // ------------------------------------------------------------------ Dynmap worlds

    String dynmapWorld(String dimension) {
        String key = dimension == null ? "" : dimension;
        String cached = this.worldCache.get(key);
        if (cached != null) {
            return cached;
        }
        String world = this.resolveWorld(dimension);
        this.worldCache.put(key, world);
        return world;
    }

    private String resolveWorld(String dimension) {
        String normalized = normalizeDimension(dimension);
        String override = this.worldOverrides.get(normalized);
        if (override != null && this.availableWorlds.contains(override)) {
            return override;
        }
        String path = normalized.contains("/") ? normalized.substring(normalized.indexOf('/') + 1) : normalized;
        for (String world : this.availableWorlds) {
            if (world.equalsIgnoreCase(dimension) || world.equalsIgnoreCase(normalized)
                    || world.equalsIgnoreCase(path) || world.equalsIgnoreCase(path.replace('/', '_'))) {
                this.saveDetectedMapping(normalized, world);
                return world;
            }
        }
        String environment = switch (normalized) {
            case "minecraft/overworld" -> "NORMAL";
            case "minecraft/the_nether" -> "NETHER";
            case "minecraft/the_end" -> "THE_END";
            default -> null;
        };
        if (environment != null) {
            List<String> candidates = this.worldsForEnvironment(environment);
            if (!candidates.isEmpty()) {
                this.saveDetectedMapping(normalized, candidates.get(0));
                return candidates.get(0);
            }
        }
        return this.availableWorlds.isEmpty() ? "world" : this.availableWorlds.iterator().next();
    }

    private void refreshAvailableWorlds() {
        try {
            for (World world : Bukkit.getWorlds()) {
                this.availableWorlds.add(world.getName());
            }
        } catch (Throwable ignored) {
            // world list unavailable — use what we already know
        }
    }

    private List<String> worldsForEnvironment(String environment) {
        List<String> result = new ArrayList<>();
        try {
            for (World world : Bukkit.getWorlds()) {
                if (world.getEnvironment().name().equalsIgnoreCase(environment) && this.availableWorlds.contains(world.getName())) {
                    result.add(world.getName());
                }
            }
        } catch (Throwable ignored) {
            // no matching worlds
        }
        return result;
    }

    private static String normalizeDimension(String dimension) {
        if (dimension == null || dimension.isBlank() || dimension.equals("world")) {
            return "minecraft/overworld";
        }
        return dimension.replace(':', '/');
    }

    private void loadWorldOverrides() {
        try {
            ConfigurationSection section = this.getConfig().getConfigurationSection("worlds");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    String mapped = section.getString(key);
                    if (mapped != null) {
                        this.worldOverrides.put(key, mapped);
                    }
                }
            }
        } catch (Throwable ignored) {
            // without overrides auto-detection is used
        }
    }

    private void saveDetectedMapping(String dimension, String world) {
        if (this.worldOverrides.containsKey(dimension)) {
            return;
        }
        try {
            this.worldOverrides.put(dimension, world);
            this.getConfig().set("worlds." + dimension, world);
            this.saveConfig();
        } catch (Throwable ignored) {
            // not critical: the mapping is still kept in memory
        }
    }

    // ------------------------------------------------------------------ stations.txt / trains.txt export

    private void saveTxtFiles() {
        if (this.stopping) {
            return;
        }
        try {
            RailwayDataSnapshot snapshot = this.railwayDataSnapshot();
            if (snapshot == null) {
                return;
            }
            Set<String> stationNames = new LinkedHashSet<>();
            Set<String> trainNames = new LinkedHashSet<>();
            if (snapshot.graphs instanceof Map<?, ?> graphs) {
                Object stationType = this.stationPointType();
                for (Object graph : graphs.values()) {
                    Object points = Reflect.call(graph, "getPoints", stationType);
                    if (points instanceof Iterable<?> stations) {
                        for (Object station : stations) {
                            try {
                                stationNames.add(Reflect.text(Reflect.fieldOrMethod(station, "name", "getName"), "Unnamed station"));
                            } catch (Throwable ignored) {
                                // skip this station
                            }
                        }
                    }
                }
            }
            if (snapshot.trains instanceof Map<?, ?> trains) {
                for (Object train : trains.values()) {
                    try {
                        trainNames.add(Reflect.componentText(Reflect.fieldOrMethod(train, "name", "getName"), "Train"));
                    } catch (Throwable ignored) {
                        // skip this train
                    }
                }
            }
            File dataFolder = this.getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            Files.write(new File(dataFolder, "stations.txt").toPath(), stationNames, StandardCharsets.UTF_8);
            Files.write(new File(dataFolder, "trains.txt").toPath(), trainNames, StandardCharsets.UTF_8);
        } catch (Throwable throwable) {
            this.getLogger().log(Level.WARNING, "Could not auto-save stations.txt / trains.txt: " + Reflect.unwrap(throwable));
        }
    }

    // ------------------------------------------------------------------ misc

    void warnOnce(String key, String message) {
        if (this.warned.add(key)) {
            this.getLogger().warning(message);
        }
    }

    private String configuredStationIcon() {
        return this.getConfig().getString("station-icon", "bank");
    }

    private String configuredTrainIcon() {
        return this.getConfig().getString("train-icon", "bricks");
    }

    private static final class RailwayDataSnapshot {
        final Object graphs;
        final Object trains;

        RailwayDataSnapshot(Object graphs, Object trains) {
            this.graphs = graphs;
            this.trains = trains;
        }
    }
}

/** /getstations — prints the exported station list. */
final class StationCommand implements org.bukkit.command.CommandExecutor {
    private final CreateRailwaysDynmapBridge plugin;

    StationCommand(CreateRailwaysDynmapBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        ListFileCommand.print(this.plugin, sender, "stations.txt", "stations");
        return true;
    }
}

/** /gettrains — prints the exported train list. */
final class TrainCommand implements org.bukkit.command.CommandExecutor {
    private final CreateRailwaysDynmapBridge plugin;

    TrainCommand(CreateRailwaysDynmapBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        ListFileCommand.print(this.plugin, sender, "trains.txt", "trains");
        return true;
    }
}

/** Shared code of /getstations and /gettrains. */
final class ListFileCommand {
    private ListFileCommand() {
    }

    static void print(CreateRailwaysDynmapBridge plugin, org.bukkit.command.CommandSender sender, String fileName, String kind) {
        Lang lang = plugin.lang();
        if (!sender.hasPermission("create.stations.admin")) {
            sender.sendMessage(lang.get("no-permission"));
            return;
        }
        try {
            java.io.File file = new java.io.File(plugin.getDataFolder(), fileName);
            if (!file.exists()) {
                sender.sendMessage(lang.get(kind + "-file-missing"));
                return;
            }
            java.util.List<String> lines = java.nio.file.Files.readAllLines(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            sender.sendMessage(lang.get(kind + "-header", lines.size()));
            for (String line : lines) {
                sender.sendMessage("- " + line);
            }
        } catch (Exception e) {
            sender.sendMessage(lang.get(kind + "-read-error", e.getMessage()));
        }
    }
}

/** /railmap — shows a clickable link to the live web map. */
final class RailMapCommand implements org.bukkit.command.CommandExecutor {
    private final CreateRailwaysDynmapBridge plugin;

    RailMapCommand(CreateRailwaysDynmapBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        Lang lang = this.plugin.lang();
        if (!this.plugin.isWebMapRunning()) {
            sender.sendMessage(lang.get("map-disabled"));
            return true;
        }
        String url = this.plugin.webMapUrl(sender);
        if (!(sender instanceof org.bukkit.entity.Player player) || !sendClickable(player, lang, url)) {
            sender.sendMessage(lang.get("map-link", url));
        }
        if (sender.hasPermission("create.stations.admin")) {
            sender.sendMessage(lang.get("map-viewers", this.plugin.webMapViewers()));
        }
        return true;
    }

    /**
     * Sends the link as a clickable chat message through the vanilla /tellraw command.
     * This works on every server type (Paper, Spigot, hybrids) without extra APIs.
     */
    private static boolean sendClickable(org.bukkit.entity.Player player, Lang lang, String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://") || url.contains("<")) {
            return false; // not a real link (e.g. "<server-ip>"), plain text is clearer
        }
        try {
            com.igrobar.createdynmap.web.Json json = new com.igrobar.createdynmap.web.Json(256);
            json.beginArray();
            json.value("");
            json.beginObject().field("text", lang.get("map-link-prefix")).field("color", "gold").endObject();
            json.beginObject()
                    .field("text", url)
                    .field("color", "aqua")
                    .field("underlined", true);
            json.key("clickEvent").beginObject().field("action", "open_url").field("value", url).endObject();
            json.key("hoverEvent").beginObject().field("action", "show_text").field("contents", lang.get("map-link-hover")).endObject();
            json.endObject();
            json.endArray();
            return org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(),
                    // The player name, not the UUID: vanilla treats a UUID as an entity selector,
                    // which /tellraw rejects ("only players").
                    "minecraft:tellraw " + player.getName() + " " + json);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
