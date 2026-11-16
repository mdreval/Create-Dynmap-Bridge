package com.igrobar.createdynmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.igrobar.createdynmap.web.MapData;

/**
 * Collects data for the web map. All methods are called ONLY from the server main thread,
 * where Create data cannot change under our feet. The result is immutable snapshots
 * ({@link MapData}) that are processed off the main thread afterwards.
 *
 * To keep the server load low:
 * <ul>
 *   <li>a train is just two points per carriage;</li>
 *   <li>track geometry is rebuilt only when the network changes (node count / graph checksum)
 *       or every {@code network-refresh-seconds} just in case;</li>
 *   <li>all method lookups are cached in {@link Reflect}.</li>
 * </ul>
 */
final class RailwayMapCollector {
    private final CreateRailwaysDynmapBridge plugin;
    private Class<?> stationClass;
    private String lastSignature;
    private long lastNetworkBuild;

    RailwayMapCollector(CreateRailwaysDynmapBridge plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ trains

    List<MapData.TrainInfo> trains(Map<?, ?> trains) {
        List<MapData.TrainInfo> result = new ArrayList<>(trains.size());
        for (Object train : trains.values()) {
            try {
                MapData.TrainInfo info = this.train(train);
                if (info != null) {
                    result.add(info);
                }
            } catch (Throwable throwable) {
                Throwable cause = Reflect.unwrap(throwable);
                this.plugin.warnOnce("web-train:" + cause.getClass().getName(), "Web map: could not read a Create train: " + cause);
            }
        }
        return result;
    }

    private MapData.TrainInfo train(Object train) throws Exception {
        Object id = Reflect.fieldOrMethod(train, "id", "getId");
        if (id == null) {
            return null;
        }
        String trainId = id.toString();
        String name = Reflect.componentText(Reflect.fieldOrMethod(train, "name", "getName"), this.plugin.lang().get("default-train-name"));
        if (this.plugin.isBlacklisted("train_" + Reflect.safe(trainId), name)) {
            return null;
        }
        Object speedValue = Reflect.fieldOrMethod(train, "speed", "getSpeed");
        double speed = speedValue instanceof Number number ? number.doubleValue() : 0.0;
        boolean derailed = Boolean.TRUE.equals(Reflect.fieldOrMethod(train, "derailed", "isDerailed"));

        String station = null;
        Object currentStation = Reflect.optionalCall(train, "getCurrentStation");
        if (currentStation != null) {
            station = Reflect.text(Reflect.fieldOrMethod(currentStation, "name", "getName"), null);
        }
        String destination = null;
        Object navigation = Reflect.fieldOrMethod(train, "navigation", "getNavigation");
        if (navigation != null) {
            Object target = Reflect.fieldOrMethod(navigation, "destination", "getDestination");
            if (target != null) {
                destination = Reflect.text(Reflect.fieldOrMethod(target, "name", "getName"), null);
            }
        }

        Object graph = Reflect.fieldOrMethod(train, "graph", "getGraph");
        List<MapData.CarInfo> cars = new ArrayList<>();
        Object carriages = Reflect.fieldOrMethod(train, "carriages", "getCarriages");
        if (carriages instanceof Iterable<?> list) {
            for (Object carriage : list) {
                Object lead = Reflect.optionalCall(carriage, "getLeadingPoint");
                Object trail = Reflect.optionalCall(carriage, "getTrailingPoint");
                String leadDimension = this.pointDimension(lead);
                String trailDimension = this.pointDimension(trail);
                double[] leadPos = leadDimension == null ? null : this.pointPosition(lead, graph);
                double[] trailPos = trailDimension == null ? null : this.pointPosition(trail, graph);
                if (leadPos != null || trailPos != null) {
                    cars.add(new MapData.CarInfo(leadDimension, leadPos, trailDimension, trailPos));
                }
            }
        }
        if (cars.isEmpty()) {
            return null;
        }
        return new MapData.TrainInfo(trainId, name, speed, derailed, station, destination, List.copyOf(cars));
    }

    /** Dimension of a carriage point, or null if the point is not on a track. */
    private String pointDimension(Object point) throws Exception {
        if (point == null) {
            return null;
        }
        Object node1 = Reflect.fieldOrMethod(point, "node1", "getNode1");
        Object edge = Reflect.fieldOrMethod(point, "edge", "getEdge");
        if (node1 == null || edge == null) {
            return null;
        }
        Object location = Reflect.call(node1, "getLocation");
        return this.plugin.dimensionId(Reflect.fieldOrMethod(location, "dimension", "getDimension"));
    }

    private double[] pointPosition(Object point, Object graph) throws Exception {
        Object position = Reflect.call(point, "getPosition", graph);
        return position == null ? null : vec(position);
    }

    // ------------------------------------------------------------------ track network

    /** Whether the track geometry must be rebuilt (the network changed or too much time has passed). */
    boolean networkNeedsRebuild(Map<?, ?> graphs, long forceAfterMillis) throws Exception {
        String signature = this.signature(graphs);
        long now = System.currentTimeMillis();
        if (!signature.equals(this.lastSignature) || now - this.lastNetworkBuild >= forceAfterMillis) {
            this.lastSignature = signature;
            this.lastNetworkBuild = now;
            return true;
        }
        return false;
    }

    private String signature(Map<?, ?> graphs) throws Exception {
        StringBuilder sb = new StringBuilder(graphs.size() * 48);
        for (Map.Entry<?, ?> entry : graphs.entrySet()) {
            Object graph = entry.getValue();
            Object nodes = Reflect.call(graph, "getNodes");
            Object checksum = Reflect.optionalCall(graph, "getChecksum");
            sb.append(entry.getKey()).append(':')
                    .append(nodes instanceof Set<?> set ? set.size() : -1).append(':')
                    .append(checksum).append(';');
        }
        return sb.toString();
    }

    MapData.Network network(Map<?, ?> graphs) {
        List<MapData.TrackPiece> tracks = new ArrayList<>();
        List<MapData.PortalLink> portals = new ArrayList<>();
        for (Object graph : graphs.values()) {
            try {
                this.collectGraph(graph, tracks, portals);
            } catch (Throwable throwable) {
                Throwable cause = Reflect.unwrap(throwable);
                this.plugin.warnOnce("web-graph:" + cause.getClass().getName(), "Web map: could not read a track network: " + cause);
            }
        }
        return new MapData.Network(List.copyOf(tracks), List.copyOf(portals));
    }

    private void collectGraph(Object graph, List<MapData.TrackPiece> tracks, List<MapData.PortalLink> portals) throws Exception {
        Object nodeLocations = Reflect.call(graph, "getNodes");
        if (!(nodeLocations instanceof Iterable<?> locations)) {
            return;
        }
        // Create stores every edge twice (A→B and B→A). Keep one: skip it if the other node was already processed.
        Set<Object> done = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object location : List.copyOf((java.util.Collection<?>) nodeLocations)) {
            Object node = Reflect.call(graph, "locateNode", location);
            if (node == null) {
                continue;
            }
            done.add(node);
            Object connections = Reflect.call(graph, "getConnectionsFrom", node);
            if (!(connections instanceof Map<?, ?> map)) {
                continue;
            }
            for (Map.Entry<?, ?> connection : map.entrySet()) {
                if (done.contains(connection.getKey())) {
                    continue;
                }
                try {
                    this.collectEdge(connection.getValue(), tracks, portals);
                } catch (Throwable throwable) {
                    Throwable cause = Reflect.unwrap(throwable);
                    this.plugin.warnOnce("web-edge:" + cause.getClass().getName(), "Web map: could not read a track piece: " + cause);
                }
            }
        }
    }

    private void collectEdge(Object edge, List<MapData.TrackPiece> tracks, List<MapData.PortalLink> portals) throws Exception {
        if (edge == null) {
            return;
        }
        Object node1 = Reflect.fieldOrMethod(edge, "node1", "getNode1");
        Object node2 = Reflect.fieldOrMethod(edge, "node2", "getNode2");
        Object location1 = Reflect.call(node1, "getLocation");
        Object location2 = Reflect.call(node2, "getLocation");
        String dimension1 = this.plugin.dimensionId(Reflect.fieldOrMethod(location1, "dimension", "getDimension"));
        double[] start = vec(Reflect.call(location1, "getLocation"));
        double[] end = vec(Reflect.call(location2, "getLocation"));

        if (Boolean.TRUE.equals(Reflect.call(edge, "isInterDimensional"))) {
            String dimension2 = this.plugin.dimensionId(Reflect.fieldOrMethod(location2, "dimension", "getDimension"));
            portals.add(new MapData.PortalLink(dimension1, start, dimension2, end));
            return;
        }

        String type = this.trackType(edge);
        if (Boolean.TRUE.equals(Reflect.call(edge, "isTurn"))) {
            Object turn = Reflect.call(edge, "getTurn");
            Object starts = Reflect.fieldOrMethod(turn, "starts", "getStarts");
            Object axes = Reflect.fieldOrMethod(turn, "axes", "getAxes");
            double handle = Reflect.number(Reflect.call(turn, "getHandleLength"));
            double[] s = vec(first(starts));
            double[] e = vec(second(starts));
            double[] a1 = vec(first(axes));
            double[] a2 = vec(second(axes));
            tracks.add(new MapData.TrackPiece(dimension1, type, new double[] {
                s[0], s[1], s[2],
                s[0] + a1[0] * handle, s[1] + a1[1] * handle, s[2] + a1[2] * handle,
                e[0] + a2[0] * handle, e[1] + a2[1] * handle, e[2] + a2[2] * handle,
                e[0], e[1], e[2]
            }));
        } else {
            tracks.add(new MapData.TrackPiece(dimension1, type, new double[] {
                start[0], start[1], start[2], end[0], end[1], end[2]
            }));
        }
    }

    /** Track type: "create:standard", "railways:narrow_gauge", "railways:monorail" etc. (+ "|phantom" for phantom tracks). */
    private String trackType(Object edge) {
        try {
            Object material = Reflect.optionalCall(edge, "getTrackMaterial");
            if (material == null) {
                return "create:standard";
            }
            Object trackType = Reflect.fieldOrMethod(material, "trackType", "getTrackType");
            Object typeId = trackType == null ? null : Reflect.fieldOrMethod(trackType, "id", "getId");
            Object materialId = Reflect.fieldOrMethod(material, "id", "getId");
            String type = typeId == null ? "create:standard" : typeId.toString();
            if (materialId != null && materialId.toString().contains("phantom")) {
                type = type + "|phantom";
            }
            return type;
        } catch (Throwable ignored) {
            return "create:standard";
        }
    }

    // ------------------------------------------------------------------ stations

    List<MapData.StationInfo> stations(Map<?, ?> graphs) {
        Map<String, MapData.StationInfo> result = new LinkedHashMap<>();
        Object stationType;
        try {
            stationType = this.plugin.stationPointType();
        } catch (Throwable throwable) {
            this.plugin.warnOnce("web-station-type", "Web map: station type unavailable: " + Reflect.unwrap(throwable));
            return List.of();
        }
        for (Object graph : graphs.values()) {
            Object points;
            try {
                points = Reflect.call(graph, "getPoints", stationType);
            } catch (Throwable throwable) {
                continue;
            }
            if (!(points instanceof Iterable<?> stations)) {
                continue;
            }
            for (Object station : stations) {
                try {
                    Object position = this.plugin.stationPosition(graph, station);
                    if (position == null) {
                        continue;
                    }
                    double[] p = vec(position);
                    String name = Reflect.text(Reflect.fieldOrMethod(station, "name", "getName"), this.plugin.lang().get("default-station-name"));
                    Object id = Reflect.fieldOrMethod(station, "id", "getId");
                    String stationId = id == null ? name + "_" + (int) p[0] + "_" + (int) p[2] : id.toString();
                    if (this.plugin.isBlacklisted("station_" + Reflect.safe(stationId), name)) {
                        continue;
                    }
                    String dimension = this.plugin.stationDimension(station);
                    result.putIfAbsent(stationId, new MapData.StationInfo(stationId, name, dimension, p[0], p[1], p[2]));
                } catch (Throwable throwable) {
                    Throwable cause = Reflect.unwrap(throwable);
                    this.plugin.warnOnce("web-station:" + cause.getClass().getName(), "Web map: could not read a Create station: " + cause);
                }
            }
        }
        return List.copyOf(result.values());
    }

    // ------------------------------------------------------------------ helpers

    private static Object first(Object couple) throws Exception {
        Object value = Reflect.optionalCall(couple, "getFirst");
        return value != null ? value : Reflect.call(couple, "get", true);
    }

    private static Object second(Object couple) throws Exception {
        Object value = Reflect.optionalCall(couple, "getSecond");
        return value != null ? value : Reflect.call(couple, "get", false);
    }

    /** Vec3 → [x, y, z]. */
    static double[] vec(Object vec) throws Exception {
        if (vec == null) {
            throw new IllegalStateException("Missing position");
        }
        return new double[] {
            Reflect.number(Reflect.fieldOrMethod(vec, "x", "x")),
            Reflect.number(Reflect.fieldOrMethod(vec, "y", "y")),
            Reflect.number(Reflect.fieldOrMethod(vec, "z", "z"))
        };
    }
}
