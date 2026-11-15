package com.igrobar.createdynmap;

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
    /** Сколько обновлений подряд поезд может «пропасть», прежде чем его маркер удалится. */
    private static final int TRAIN_MISSES_BEFORE_REMOVAL = 3;

    private volatile boolean stopping;
    private final Set<String> warned = new HashSet<>();
    private final Map<String, String> worldOverrides = new LinkedHashMap<>();
    private final Set<String> availableWorlds = new LinkedHashSet<>();
    private final Map<String, String> worldCache = new HashMap<>();
    private final Map<String, Integer> trainMisses = new HashMap<>();
    private BlacklistFiles blacklist;
    private DynmapMarkers markers;
    private Object stationPointType;

    @Override
    public void onEnable() {
        if (this.getCommand("getstations") != null) {
            this.getCommand("getstations").setExecutor(new StationCommand(this));
        }
        if (this.getCommand("gettrains") != null) {
            this.getCommand("gettrains").setExecutor(new TrainCommand(this));
        }

        // Сохраняет config.yml из .jar со всеми комментариями, если его ещё нет
        this.saveDefaultConfig();

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

        BukkitScheduler scheduler = Bukkit.getScheduler();
        // Станции почти не меняются — обновляем редко; поезда — чаще. Всё в главном потоке, но дёшево.
        scheduler.runTaskTimer(this, this::safeStationScan, 100L, stationTicks);
        scheduler.runTaskTimer(this, this::safeTrainScan, 110L, trainTicks);
        scheduler.runTaskTimer(this, this::saveTxtFiles, 200L, exportTicks);

        this.getLogger().info("Create Railways - Dynmap Bridge " + this.getDescription().getVersion()
                + " enabled; trains every " + trainTicks / 20 + "s, stations every " + stationTicks / 20 + "s.");
    }

    @Override
    public void onDisable() {
        this.stopping = true;
        this.markers = null;
        Reflect.clear();
    }

    private long intervalTicks(String key, long defaultSeconds) {
        long seconds = this.getConfig().getLong(key, defaultSeconds);
        return Math.max(20L, seconds * 20L);
    }

    // ------------------------------------------------------------------ сканирование

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

    /** Общая подготовка к циклу: Dynmap, данные Create, миры, чёрные списки. */
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

        // Удаляем маркеры поездов, которых нет уже несколько обновлений подряд (удалённые/разобранные поезда)
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
        // Удалённые станции убираем с карты (только если скан что-то нашёл, чтобы не стереть всё при сбое)
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

    /** Создаёт/обновляет маркер. Возвращает false, если маркер в чёрном списке или Dynmap недоступен. */
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

    // ------------------------------------------------------------------ данные Create

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

    private Object stationPointType() throws Exception {
        if (this.stationPointType == null) {
            this.stationPointType = Reflect.staticField(Reflect.type("com.simibubi.create.content.trains.graph.EdgePointType"), "STATION");
        }
        return this.stationPointType;
    }

    private Object stationPosition(Object graph, Object station) throws Exception {
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

    private String stationDimension(Object station) {
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
            // упадём на "world" ниже
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

    // ------------------------------------------------------------------ миры Dynmap

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
            // список миров недоступен — используем то, что уже знаем
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
            // нет подходящих миров
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
            // без оверрайдов работает автоопределение
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
            // не критично: сопоставление всё равно запомнено в памяти
        }
    }

    // ------------------------------------------------------------------ экспорт stations.txt / trains.txt

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
                                // пропускаем станцию
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
                        // пропускаем поезд
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

    // ------------------------------------------------------------------ прочее

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

final class StationCommand implements org.bukkit.command.CommandExecutor {
    private final org.bukkit.plugin.java.JavaPlugin plugin;

    public StationCommand(org.bukkit.plugin.java.JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (!sender.hasPermission("create.stations.admin")) {
            sender.sendMessage("У вас нет прав на выполнение этой команды.");
            return true;
        }
        try {
            java.io.File file = new java.io.File(plugin.getDataFolder(), "stations.txt");
            if (!file.exists()) {
                sender.sendMessage("Файл со станциями еще не создан.");
                return true;
            }
            java.util.List<String> stations = java.nio.file.Files.readAllLines(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            sender.sendMessage("=== Список станций Create (" + stations.size() + ") ===");
            for (String station : stations) {
                sender.sendMessage("- " + station);
            }
        } catch (Exception e) {
            sender.sendMessage("Ошибка при чтении файла станций: " + e.getMessage());
        }
        return true;
    }
}

final class TrainCommand implements org.bukkit.command.CommandExecutor {
    private final org.bukkit.plugin.java.JavaPlugin plugin;

    public TrainCommand(org.bukkit.plugin.java.JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (!sender.hasPermission("create.stations.admin")) {
            sender.sendMessage("У вас нет прав на выполнение этой команды.");
            return true;
        }
        try {
            java.io.File file = new java.io.File(plugin.getDataFolder(), "trains.txt");
            if (!file.exists()) {
                sender.sendMessage("Файл с поездами еще не создан.");
                return true;
            }
            java.util.List<String> trains = java.nio.file.Files.readAllLines(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            sender.sendMessage("=== Список поездов Create (" + trains.size() + ") ===");
            for (String train : trains) {
                sender.sendMessage("- " + train);
            }
        } catch (Exception e) {
            sender.sendMessage("Ошибка при чтении файла поездов: " + e.getMessage());
        }
        return true;
    }
}
