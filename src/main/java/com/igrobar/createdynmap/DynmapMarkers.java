package com.igrobar.createdynmap;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.plugin.Plugin;

/**
 * Dynmap Marker API access (through cached reflection, so Dynmap is not a build dependency).
 * A marker is only updated when its label, world, position or description really changed.
 */
final class DynmapMarkers {
    static final String SET_ID = "create_railways";
    private static final String SET_LABEL = "Create / Railways";
    private static final double MOVE_EPSILON = 0.05;

    private final Object api;
    private final Object set;
    private final Map<String, Object> icons = new HashMap<>();

    private DynmapMarkers(Object api, Object set) {
        this.api = api;
        this.set = set;
    }

    static DynmapMarkers connect(Plugin dynmap) throws Exception {
        Object api = Reflect.call(dynmap, "getMarkerAPI");
        if (api == null) {
            return null;
        }
        Object set = Reflect.call(api, "getMarkerSet", SET_ID);
        if (set == null) {
            set = Reflect.call(api, "createMarkerSet", SET_ID, SET_LABEL, null, false);
        }
        if (set == null) {
            return null;
        }
        Reflect.optionalCall(set, "setMarkerSetLabel", SET_LABEL);
        Reflect.optionalCall(set, "setLayerPriority", 5);
        Reflect.optionalCall(set, "setHideByDefault", false);
        return new DynmapMarkers(api, set);
    }

    /** After /dynmap reload the API and the marker set are recreated, so we have to reconnect. */
    boolean isCurrent(Plugin dynmap) {
        Object currentApi = Reflect.optionalCall(dynmap, "getMarkerAPI");
        return currentApi == this.api && Reflect.optionalCall(currentApi, "getMarkerSet", SET_ID) == this.set;
    }

    void upsert(String id, String label, String world, double x, double y, double z, String description, String iconName) throws Exception {
        Object marker = Reflect.call(this.set, "findMarker", id);
        if (marker == null) {
            marker = Reflect.call(this.set, "createMarker", id, label, true, world, x, y, z, this.icon(iconName), false);
            if (marker != null) {
                Reflect.optionalCall(marker, "setDescription", description);
            }
            return;
        }
        if (!Objects.equals(Reflect.optionalCall(marker, "getLabel"), label)) {
            Reflect.call(marker, "setLabel", label);
        }
        if (!Objects.equals(Reflect.optionalCall(marker, "getWorld"), world)
                || moved(Reflect.optionalCall(marker, "getX"), x)
                || moved(Reflect.optionalCall(marker, "getY"), y)
                || moved(Reflect.optionalCall(marker, "getZ"), z)) {
            Reflect.call(marker, "setLocation", world, x, y, z);
        }
        if (!Objects.equals(Reflect.optionalCall(marker, "getDescription"), description)) {
            Reflect.optionalCall(marker, "setDescription", description);
        }
    }

    void delete(String id) {
        Object marker = Reflect.optionalCall(this.set, "findMarker", id);
        if (marker != null) {
            Reflect.optionalCall(marker, "deleteMarker");
        }
    }

    /** IDs of all markers in the set with the given prefix. getMarkers() returns Set&lt;Marker&gt;. */
    Set<String> markerIds(String prefix) {
        Set<String> ids = new LinkedHashSet<>();
        Object markers = Reflect.optionalCall(this.set, "getMarkers");
        if (markers instanceof Iterable<?> iterable) {
            for (Object marker : iterable) {
                Object id = Reflect.optionalCall(marker, "getMarkerID");
                if (id != null && id.toString().startsWith(prefix)) {
                    ids.add(id.toString());
                }
            }
        }
        return ids;
    }

    private Object icon(String name) {
        return this.icons.computeIfAbsent(name, key -> Reflect.optionalCall(this.api, "getMarkerIcon", key));
    }

    private static boolean moved(Object current, double target) {
        return !(current instanceof Number number) || Math.abs(number.doubleValue() - target) > MOVE_EPSILON;
    }
}
