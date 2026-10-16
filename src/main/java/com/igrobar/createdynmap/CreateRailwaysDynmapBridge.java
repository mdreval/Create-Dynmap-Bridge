package com.igrobar.createdynmap;

import com.igrobar.createdynmap.BlacklistFiles;
import com.igrobar.createdynmap.TrainMarkerUpdater;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

public final class CreateRailwaysDynmapBridge
extends JavaPlugin {
    private static final String SET_ID = "create_railways";
    private volatile boolean stopping;
    private final Set<String> warned = new HashSet<String>();
    private final Map<String, String> worldOverrides = new LinkedHashMap<String, String>();
    private final Set<String> availableWorlds = new LinkedHashSet<String>();

    public void onEnable() {
        if (this.getCommand("getstations") != null) {
            this.getCommand("getstations").setExecutor(new StationCommand(this));
        }
        this.getConfig().addDefault("station-icon", (Object)"bank");
        this.getConfig().addDefault("train-icon", (Object)"bricks");
        this.getConfig().options().copyDefaults(true);
        this.saveConfig();
        this.getLogger().info("Create/Railways Dynmap Bridge 1.0.0.2 enabled; live Create railway manager integration.");
        try {
            this.loadAndDetectWorldMappings();
            Object object = CreateRailwaysDynmapBridge.staticBukkitCall("getScheduler", new Object[0]);
            Method method = CreateRailwaysDynmapBridge.publicMethod(Class.forName("org.bukkit.scheduler.BukkitScheduler"), "runTaskTimer", 4);
            method.invoke(object, new Object[]{this, (Runnable) this::safeScan, 100L, 20L});
        }
        catch (Throwable throwable) {
            this.getLogger().log(Level.SEVERE, "Could not schedule Create railway scan", CreateRailwaysDynmapBridge.unwrap(throwable));
            throw new IllegalStateException(throwable);
        }
    }

    public void onDisable() {
        this.stopping = true;
    }

    private void safeScan() {
        if (this.stopping) {
            return;
        }
        try {
            this.scan();
        }
        catch (Throwable throwable) {
            this.getLogger().log(Level.WARNING, "Create railway data scan failed: " + String.valueOf(CreateRailwaysDynmapBridge.unwrap(throwable)), CreateRailwaysDynmapBridge.unwrap(throwable));
        }
    }

    private void scan() throws Exception {
        Object object;
        Object object2;
        double d;
        double d2;
        Object object3;
        Object object4;
        Object object5;
        Object object6;
        Map map;
        Object object7 = this.dynmapApi();
        if (object7 == null) {
            this.warnOnce("dynmap", "Dynmap Marker API unavailable; retrying.");
            return;
        }
        Object object8 = this.markerSet(object7);
        if (object8 == null) {
            return;
        }
        RailwayDataSnapshot railwayDataSnapshot = this.railwayDataSnapshot();
        if (railwayDataSnapshot == null) {
            this.warnOnce("create", "Create railway manager/data unavailable. Is Create loaded and railway data initialized?");
            return;
        }
        Object object9 = railwayDataSnapshot.graphs;
        Object object10 = railwayDataSnapshot.trains;
        HashSet<String> hashSet = new HashSet<String>();
        Set<String> collectedStationNames = new LinkedHashSet<String>();
        int n = 0;
        int n2 = 0;
        int n3 = 0;
        int n4 = 0;
        if (object9 instanceof Map) {
            map = (Map)object9;
            n3 = map.size();
            Class<?> clazz = Class.forName("com.simibubi.create.content.trains.graph.EdgePointType");
            Object object11 = clazz.getField("STATION").get(null);
            for (Object object12 : map.values()) {
                object6 = CreateRailwaysDynmapBridge.call(object12, "getPoints", object11);
                if (!(object6 instanceof Iterable)) continue;
                Iterable<?> stationPoints = (Iterable<?>) object6;
                Iterator<?> stationIterator = stationPoints.iterator();
                while (stationIterator.hasNext()) {
                    Object e = stationIterator.next();
                    ++n4;
                    try {
                        object3 = this.stationPosition(object12, e);
                        if (object3 == null) {
                            throw new IllegalStateException("Could not resolve station position from its track edge");
                        }
                        double d3 = CreateRailwaysDynmapBridge.number(CreateRailwaysDynmapBridge.fieldOrMethod(object3, "x", "x"));
                        d2 = CreateRailwaysDynmapBridge.number(CreateRailwaysDynmapBridge.fieldOrMethod(object3, "y", "y"));
                        d = CreateRailwaysDynmapBridge.number(CreateRailwaysDynmapBridge.fieldOrMethod(object3, "z", "z"));
                        String string = this.dynmapWorld(this.stationDimension(e));
                        String string2 = CreateRailwaysDynmapBridge.text(CreateRailwaysDynmapBridge.fieldOrMethod(e, "name", "getName"), "Unnamed station");
                        
                        collectedStationNames.add(string2);

                        object2 = CreateRailwaysDynmapBridge.fieldOrMethod(e, "id", "getId");
                        object = "station_" + CreateRailwaysDynmapBridge.safe((String)(object2 == null ? string2 + "_" + string + "_" + (int)d3 + "_" + (int)d : object2.toString()));
                        this.upsert(object7, object8, (String)object, string2, "Create station", string, d3, d2, d);
                        hashSet.add((String)object);
                        ++n;
                    }
                    catch (Throwable throwable) {
                        this.warnOnce("station:" + throwable.getClass().getName(), "Could not read a Create station: " + String.valueOf(CreateRailwaysDynmapBridge.unwrap(throwable)));
                    }
                }
            }
        }
        
        // Автосохранение списка станций в stations.txt
        try {
            File dataFolder = (File) CreateRailwaysDynmapBridge.call((Object)this, "getDataFolder", new Object[0]);
            if (dataFolder != null) {
                if (!dataFolder.exists()) {
                    dataFolder.mkdirs();
                }
                File stationsFile = new File(dataFolder, "stations.txt");
                Files.write(stationsFile.toPath(), collectedStationNames, StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            this.getLogger().warning("Could not save stations.txt: " + t.getMessage());
        }

        if (object10 instanceof Map) {
            map = (Map)object10;
            for (Object object11 : map.values()) {
                try {
                    Object object12;
                    Object object13 = CreateRailwaysDynmapBridge.fieldOrMethod(object11, "id", "getId");
                    object12 = CreateRailwaysDynmapBridge.fieldOrMethod(object11, "name", "getName");
                    object6 = CreateRailwaysDynmapBridge.componentText(object12, "Train");
                    object5 = CreateRailwaysDynmapBridge.fieldOrMethod(object11, "carriages", "getCarriages");
                    if (!(object5 instanceof Iterable)) continue;
                    Iterable<?> carriages = (Iterable<?>) object5;
                    boolean bl = false;
                    Iterator<?> carriageIterator = carriages.iterator();
                    while (carriageIterator.hasNext()) {
                        Object object14;
                        Object t = carriageIterator.next();
                        Object object15 = CreateRailwaysDynmapBridge.optionalCall(t, "anyAvailableEntity", new Object[0]);
                        if (object15 == null) continue;
                        d2 = CreateRailwaysDynmapBridge.number(CreateRailwaysDynmapBridge.call(object15, "getX", new Object[0]));
                        d = CreateRailwaysDynmapBridge.number(CreateRailwaysDynmapBridge.call(object15, "getY", new Object[0]));
                        double d4 = CreateRailwaysDynmapBridge.number(CreateRailwaysDynmapBridge.call(object15, "getZ", new Object[0]));
                        object2 = CreateRailwaysDynmapBridge.optionalCall(object15, "level", new Object[0]);
                        if (object2 == null) {
                            object2 = CreateRailwaysDynmapBridge.optionalCall(object15, "getLevel", new Object[0]);
                        }
                        object = CreateRailwaysDynmapBridge.optionalCall(object2, "dimension", new Object[0]);
                        String string = this.dynmapWorld(this.dimensionId(object));
                        Object object16 = CreateRailwaysDynmapBridge.optionalCall(object11, "getCurrentStation", new Object[0]);
                        Object object17 = object6;
                        if (object16 != null && !((String)(object14 = CreateRailwaysDynmapBridge.text(CreateRailwaysDynmapBridge.fieldOrMethod(object16, "name", "getName"), ""))).isBlank()) {
                            object17 = (String)object17 + " \u2014 at " + (String)object14;
                        }
                        object14 = "train_" + CreateRailwaysDynmapBridge.safe(String.valueOf(object13)) + "_" + CreateRailwaysDynmapBridge.safe(string);
                        this.upsert(object7, object8, (String)object14, (String)object17, "Create train", string, d2, d, d4);
                        hashSet.add((String)object14);
                        ++n2;
                        bl = true;
                    }
                }
                catch (Throwable throwable) {
                    this.warnOnce("train:" + throwable.getClass().getName(), "Could not read a Create train position: " + String.valueOf(CreateRailwaysDynmapBridge.unwrap(throwable)));
                }
            }
        }
        TrainMarkerUpdater.update((Object)this, object7, object8, hashSet);
        if (!hashSet.isEmpty()) {
            this.removeStale(object8, hashSet);
        }
    }

    private RailwayDataSnapshot railwayDataSnapshot() throws Exception {
        Object object;
        Object object2;
        Object object3;
        try {
            object3 = Class.forName("com.simibubi.create.Create");
            object2 = ((Class)object3).getField("RAILWAYS").get(null);
            object = CreateRailwaysDynmapBridge.fieldOrMethod(object2, "trackNetworks", "getTrackNetworks");
            Object object4 = CreateRailwaysDynmapBridge.fieldOrMethod(object2, "trains", "getTrains");
            if (CreateRailwaysDynmapBridge.mapSize(object) > 0 || CreateRailwaysDynmapBridge.mapSize(object4) > 0) {
                return new RailwayDataSnapshot(object, object4, "Create.RAILWAYS");
            }
        }
        catch (Throwable throwable) {
            this.warnOnce("live-manager", "Could not read Create.RAILWAYS directly; trying RailwaySavedData: " + String.valueOf(CreateRailwaysDynmapBridge.unwrap(throwable)));
        }
        object3 = this.railwaySavedData();
        if (object3 == null) {
            return null;
        }
        object2 = CreateRailwaysDynmapBridge.call(object3, "getTrackNetworks", new Object[0]);
        object = CreateRailwaysDynmapBridge.call(object3, "getTrains", new Object[0]);
        return new RailwayDataSnapshot(object2, object, "RailwaySavedData");
    }

    private static int mapSize(Object object) {
        int n;
        if (object instanceof Map) {
            Map map = (Map)object;
            n = map.size();
        } else {
            n = 0;
        }
        return n;
    }

    private Object railwaySavedData() throws Exception {
        Object object = CreateRailwaysDynmapBridge.staticBukkitCall("getServer", new Object[0]);
        Object object2 = CreateRailwaysDynmapBridge.optionalCall(object, "getServer", new Object[0]);
        if (object2 == null) {
            object2 = object;
        }
        Class<?> clazz = Class.forName("com.simibubi.create.content.trains.RailwaySavedData");
        for (Method method : clazz.getMethods()) {
            if (!method.getName().equals("load") || method.getParameterCount() != 1 || !method.getParameterTypes()[0].isInstance(object2)) continue;
            return method.invoke(null, object2);
        }
        throw new NoSuchMethodException("RailwaySavedData.load(MinecraftServer)");
    }

    private Object stationPosition(Object object, Object object2) throws Exception {
        Object object3 = CreateRailwaysDynmapBridge.fieldOrMethod(object2, "edgeLocation", "getEdgeLocation");
        if (object3 == null) {
            return null;
        }
        Object object4 = CreateRailwaysDynmapBridge.call(object3, "getFirst", new Object[0]);
        Object object5 = CreateRailwaysDynmapBridge.call(object3, "getSecond", new Object[0]);
        Object object6 = CreateRailwaysDynmapBridge.call(object, "locateNode", object4);
        Object object7 = CreateRailwaysDynmapBridge.call(object, "locateNode", object5);
        if (object6 == null || object7 == null) {
            return null;
        }
        Class<?> clazz = Class.forName("net.createmod.catnip.data.Couple");
        Object object8 = null;
        for (Method d2 : clazz.getMethods()) {
            if (!Modifier.isStatic(d2.getModifiers()) || !d2.getName().equals("create") || d2.getParameterCount() != 2 || !d2.getParameterTypes()[0].isInstance(object6) || !d2.getParameterTypes()[1].isInstance(object7)) continue;
            object8 = d2.invoke(null, object6, object7);
            break;
        }
        if (object8 == null) {
            return null;
        }
        Object object9 = CreateRailwaysDynmapBridge.call(object, "getConnection", object8);
        if (object9 == null) {
            return null;
        }
        double d = CreateRailwaysDynmapBridge.number(CreateRailwaysDynmapBridge.call(object9, "getLength", new Object[0]));
        double d2 = CreateRailwaysDynmapBridge.number(CreateRailwaysDynmapBridge.call(object2, "getLocationOn", object9));
        double d3 = d == 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, d2 / d));
        return CreateRailwaysDynmapBridge.call(object9, "getPosition", object, d3);
    }

    private String stationDimension(Object object) {
        try {
            Object object2;
            Object object3 = CreateRailwaysDynmapBridge.fieldOrMethod(object, "edgeLocation", "getEdgeLocation");
            Object object4 = CreateRailwaysDynmapBridge.optionalCall(object3, "getFirst", new Object[0]);
            if (object4 == null) {
                object4 = CreateRailwaysDynmapBridge.optionalCall(object3, "getFirstValue", new Object[0]);
            }
            if (object4 != null && (object2 = CreateRailwaysDynmapBridge.fieldOrMethod(object4, "dimension", "getDimension")) != null) {
                return this.dimensionId(object2);
            }
        }
        catch (Throwable throwable) {}
        return "world";
    }

    private String dynmapWorld(String string) {
        List<String> list;
        String string2 = this.normalizeDimension(string);
        this.refreshAvailableWorlds();
        String string3 = this.worldOverrides.get(string2);
        if (string3 != null && this.availableWorlds.contains(string3)) {
            return string3;
        }
        String string4 = string2.contains("/") ? string2.substring(string2.indexOf(47) + 1) : string2;
        for (String object2 : this.availableWorlds) {
            if (!object2.equalsIgnoreCase(string) && !object2.equalsIgnoreCase(string2) && !object2.equalsIgnoreCase(string4) && !object2.equalsIgnoreCase(string4.replace('/', '_'))) continue;
            this.saveDetectedMapping(string2, object2);
            return object2;
        }
        Object object3 = null;
        if (string2.equals("minecraft/overworld")) {
            object3 = "NORMAL";
        } else if (string2.equals("minecraft/the_nether")) {
            object3 = "NETHER";
        } else if (string2.equals("minecraft/the_end")) {
            object3 = "THE_END";
        }
        if (object3 != null && !(list = this.worldsForEnvironment((String)object3)).isEmpty()) {
            this.saveDetectedMapping(string2, list.get(0));
            return list.get(0);
        }
        return this.availableWorlds.isEmpty() ? "world" : this.availableWorlds.iterator().next();
    }

    private void refreshAvailableWorlds() {
        try {
            Object object = CreateRailwaysDynmapBridge.staticBukkitCall("getServer", new Object[0]);
            Object object2 = CreateRailwaysDynmapBridge.call(object, "getWorlds", new Object[0]);
            if (object2 instanceof Iterable) {
                for (Object t : (Iterable)object2) {
                    Object object3 = CreateRailwaysDynmapBridge.optionalCall(t, "getName", new Object[0]);
                    if (object3 == null) continue;
                    this.availableWorlds.add(String.valueOf(object3));
                }
            }
        }
        catch (Throwable throwable) {}
    }

    private List<String> worldsForEnvironment(String string) {
        ArrayList<String> arrayList = new ArrayList<String>();
        try {
            Object object = CreateRailwaysDynmapBridge.staticBukkitCall("getServer", new Object[0]);
            Object object2 = CreateRailwaysDynmapBridge.call(object, "getWorlds", new Object[0]);
            if (object2 instanceof Iterable) {
                for (Object t : (Iterable)object2) {
                    String string2 = String.valueOf(CreateRailwaysDynmapBridge.optionalCall(t, "getEnvironment", new Object[0]));
                    String string3 = String.valueOf(CreateRailwaysDynmapBridge.optionalCall(t, "getName", new Object[0]));
                    if (!string2.equalsIgnoreCase(string) || !this.availableWorlds.contains(string3)) continue;
                    arrayList.add(string3);
                }
            }
        }
        catch (Throwable throwable) {}
        return arrayList;
    }

    private String normalizeDimension(String string) {
        if (string == null || string.isBlank() || string.equals("world")) {
            return "minecraft/overworld";
        }
        return string.replace(':', '/');
    }

    private void loadAndDetectWorldMappings() throws Exception {
        File file = (File)CreateRailwaysDynmapBridge.call((Object)this, "getDataFolder", new Object[0]);
        if (!(file.exists() || file.mkdirs() || file.isDirectory())) {
            throw new IllegalStateException("Cannot create plugin data folder: " + String.valueOf(file));
        }
        File file2 = new File(file, "config.yml");
        if (!file2.isFile()) {
            String object = "# Create-Railways-Dynmap-Bridge 1.0.0.2\nworlds:\n";
            Files.writeString(file2.toPath(), (CharSequence)object, StandardCharsets.UTF_8, new OpenOption[0]);
        }
        this.refreshAvailableWorlds();
    }

    private void saveDetectedMapping(String string2, String string3) {}

    private String dimensionId(Object object) {
        if (object == null) {
            return "world";
        }
        Object object2 = CreateRailwaysDynmapBridge.optionalCall(object, "location", new Object[0]);
        if (object2 != null) {
            return String.valueOf(object2);
        }
        String string = String.valueOf(object);
        int n = string.lastIndexOf(" / ");
        if (n >= 0 && string.endsWith("]")) {
            return string.substring(n + 3, string.length() - 1);
        }
        return string;
    }

    private Object dynmapApi() {
        try {
            Object object = CreateRailwaysDynmapBridge.staticBukkitCall("getPluginManager", new Object[0]);
            Object object2 = CreateRailwaysDynmapBridge.call(object, "getPlugin", "dynmap");
            if (object2 == null || !Boolean.TRUE.equals(CreateRailwaysDynmapBridge.call(object2, "isEnabled", new Object[0]))) {
                return null;
            }
            return CreateRailwaysDynmapBridge.call(object2, "getMarkerAPI", new Object[0]);
        }
        catch (Throwable throwable) {
            return null;
        }
    }

    private Object markerSet(Object object) throws Exception {
        Object object2 = CreateRailwaysDynmapBridge.call(object, "getMarkerSet", SET_ID);
        if (object2 == null) {
            object2 = CreateRailwaysDynmapBridge.call(object, "createMarkerSet", SET_ID, "Create / Railways", null, false);
        }
        if (object2 != null) {
            CreateRailwaysDynmapBridge.optionalCall(object2, "setMarkerSetLabel", "Create / Railways");
            CreateRailwaysDynmapBridge.optionalCall(object2, "setLayerPriority", 5);
            CreateRailwaysDynmapBridge.optionalCall(object2, "setHideByDefault", false);
        }
        return object2;
    }

    private void upsert(Object object, Object object2, String string, String string2, String string3, String string4, double d, double d2, double d3) throws Exception {
        if (BlacklistFiles.isBlacklisted((Object)this, string, string2)) {
            Object object3 = CreateRailwaysDynmapBridge.optionalCall(object2, "findMarker", string);
            if (object3 != null) {
                CreateRailwaysDynmapBridge.optionalCall(object3, "deleteMarker", new Object[0]);
            }
            return;
        }
        Object object4 = CreateRailwaysDynmapBridge.call(object2, "findMarker", string);
        if (object4 == null) {
            Object object5 = CreateRailwaysDynmapBridge.optionalCall(object, "getMarkerIcon", string.startsWith("train_") ? this.configuredTrainIcon() : this.configuredStationIcon());
            object4 = CreateRailwaysDynmapBridge.call(object2, "createMarker", string, string2, true, string4, d, d2, d3, object5, false);
        } else {
            CreateRailwaysDynmapBridge.call(object4, "setLabel", string2);
            CreateRailwaysDynmapBridge.call(object4, "setLocation", string4, d, d2, d3);
        }
        CreateRailwaysDynmapBridge.optionalCall(object4, "setDescription", string3 + "<br/>" + string4 + " @ " + (int)d + ", " + (int)d2 + ", " + (int)d3);
    }

    private void removeStale(Object object, Set<String> set) {
        try {
            Object object2 = CreateRailwaysDynmapBridge.call(object, "getMarkers", new Object[0]);
            if (!(object2 instanceof Map)) {
                return;
            }
            Map map = (Map)object2;
            for (Object v : new ArrayList(map.values())) {
                String string = String.valueOf(CreateRailwaysDynmapBridge.call(v, "getMarkerID", new Object[0]));
                if (!string.startsWith("station_") && !string.startsWith("train_") || set.contains(string)) continue;
                CreateRailwaysDynmapBridge.optionalCall(v, "deleteMarker", new Object[0]);
            }
        }
        catch (Throwable throwable) {}
    }

    private static Object fieldOrMethod(Object object, String string, String string2) throws Exception {
        if (object == null) {
            return null;
        }
        try {
            Field field = object.getClass().getField(string);
            return field.get(object);
        }
        catch (NoSuchFieldException noSuchFieldException) {
            try {
                return CreateRailwaysDynmapBridge.call(object, string2, new Object[0]);
            }
            catch (NoSuchMethodException noSuchMethodException) {
                return null;
            }
        }
    }

    private static Object optionalCall(Object object, String string, Object ... objectArray) {
        try {
            return CreateRailwaysDynmapBridge.call(object, string, objectArray);
        }
        catch (Throwable throwable) {
            return null;
        }
    }

    private static Object call(Object object, String string, Object ... objectArray) throws Exception {
        if (object == null) {
            return null;
        }
        Method method = CreateRailwaysDynmapBridge.findPublicMethod(object.getClass(), string, objectArray);
        if (method == null) {
            throw new NoSuchMethodException(object.getClass().getName() + "." + string + "/" + objectArray.length);
        }
        return method.invoke(object, objectArray);
    }

    private static Method findPublicMethod(Class<?> clazz, String string, Object[] objectArray) {
        for (Class<?> clazz2 = clazz; clazz2 != null; clazz2 = clazz2.getSuperclass()) {
            for (Class<?> clazz3 : clazz2.getInterfaces()) {
                Method method = CreateRailwaysDynmapBridge.findInInterface(clazz3, string, objectArray);
                if (method == null) continue;
                return method;
            }
            for (Method method : clazz2.getDeclaredMethods()) {
                if (!CreateRailwaysDynmapBridge.matches(method, string, objectArray) || !Modifier.isPublic(method.getModifiers()) || !Modifier.isPublic(clazz2.getModifiers())) continue;
                return method;
            }
        }
        return null;
    }

    private static Method findInInterface(Class<?> clazz, String string, Object[] objectArray) {
        for (Method genericDeclaration : clazz.getMethods()) {
            if (!CreateRailwaysDynmapBridge.matches(genericDeclaration, string, objectArray)) continue;
            return genericDeclaration;
        }
        for (Class<?> interfaceClass : clazz.getInterfaces()) {
            Method method = CreateRailwaysDynmapBridge.findInInterface(interfaceClass, string, objectArray);
            if (method == null) continue;
            return method;
        }
        return null;
    }

    private static Object staticBukkitCall(String string, Object ... objectArray) throws Exception {
        Class<?> clazz = Class.forName("org.bukkit.Bukkit");
        Method method = CreateRailwaysDynmapBridge.findPublicMethod(clazz, string, objectArray);
        if (method == null) {
            throw new NoSuchMethodException("org.bukkit.Bukkit." + string + "/" + objectArray.length);
        }
        return method.invoke(null, objectArray);
    }

    private static Method publicMethod(Class<?> clazz, String string, int n) throws NoSuchMethodException {
        for (Method method : clazz.getMethods()) {
            if (!method.getName().equals(string) || method.getParameterCount() != n) continue;
            return method;
        }
        throw new NoSuchMethodException(clazz.getName() + "." + string);
    }

    private static boolean matches(Method method, String string, Object[] objectArray) {
        if (!method.getName().equals(string) || method.getParameterCount() != objectArray.length) {
            return false;
        }
        Class<?>[] classArray = method.getParameterTypes();
        for (int i = 0; i < classArray.length; ++i) {
            if (objectArray[i] == null || CreateRailwaysDynmapBridge.wrap(classArray[i]).isAssignableFrom(objectArray[i].getClass())) continue;
            return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> clazz) {
        if (!clazz.isPrimitive()) {
            return clazz;
        }
        if (clazz == Boolean.TYPE) return Boolean.class;
        if (clazz == Integer.TYPE) return Integer.class;
        if (clazz == Long.TYPE) return Long.class;
        if (clazz == Double.TYPE) return Double.class;
        if (clazz == Float.TYPE) return Float.class;
        if (clazz == Short.TYPE) return Short.class;
        if (clazz == Byte.TYPE) return Byte.class;
        if (clazz == Character.TYPE) return Character.class;
        return clazz;
    }

    private void warnOnce(String string, String string2) {
        if (this.warned.add(string)) {
            this.getLogger().warning(string2);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        while (throwable instanceof InvocationTargetException && ((InvocationTargetException)throwable).getCause() != null) {
            throwable = ((InvocationTargetException)throwable).getCause();
        }
        return throwable;
    }

    private static double number(Object object) {
        return ((Number)object).doubleValue();
    }

    private static String text(Object object, String string) {
        return object == null ? string : String.valueOf(object);
    }

    private static String componentText(Object object, String string) {
        if (object == null) {
            return string;
        }
        Object object2 = CreateRailwaysDynmapBridge.optionalCall(object, "getString", new Object[0]);
        return object2 == null ? String.valueOf(object) : String.valueOf(object2);
    }

    private static String safe(String string) {
        return string.replaceAll("[^A-Za-z0-9_.-]", "_");
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
        final String source;

        RailwayDataSnapshot(Object object, Object object2, String string) {
            this.graphs = object;
            this.trains = object2;
            this.source = string;
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
                sender.sendMessage("Файл со станциями еще не создан. Подождите пару секунд после запуска сканирования.");
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