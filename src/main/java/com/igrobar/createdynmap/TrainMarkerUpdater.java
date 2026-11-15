package com.igrobar.createdynmap;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Маркеры поездов.
 *
 * Для каждого поезда берётся первый вагон с сохранённой позицией в измерении
 * (getPresentDimensions / getPositionInDimension — работает и для поездов в незагруженных чанках).
 * Если сохранённой позиции у вагона нет, используется позиция его сущности (anyAvailableEntity).
 * На каждый мир — один маркер "train_&lt;id&gt;_&lt;мир&gt;", как и раньше.
 */
final class TrainMarkerUpdater {
    private TrainMarkerUpdater() {
    }

    static int update(CreateRailwaysDynmapBridge plugin, Map<?, ?> trains, Set<String> seen) {
        int markers = 0;
        for (Object train : trains.values()) {
            try {
                markers += updateTrain(plugin, train, seen);
            } catch (Throwable throwable) {
                plugin.warnOnce("train:" + Reflect.unwrap(throwable).getClass().getName(),
                        "Could not read a Create train position: " + Reflect.unwrap(throwable));
            }
        }
        return markers;
    }

    private static int updateTrain(CreateRailwaysDynmapBridge plugin, Object train, Set<String> seen) throws Exception {
        Object id = Reflect.fieldOrMethod(train, "id", "getId");
        if (id == null) {
            return 0;
        }
        String trainId = Reflect.safe(id.toString());
        String label = Reflect.componentText(Reflect.fieldOrMethod(train, "name", "getName"), "Train " + trainId);
        Object currentStation = Reflect.optionalCall(train, "getCurrentStation");
        if (currentStation != null) {
            String stationName = Reflect.componentText(Reflect.fieldOrMethod(currentStation, "name", "getName"), "");
            if (!stationName.isBlank()) {
                label = label + " → " + stationName;
            }
        }
        Object carriages = Reflect.fieldOrMethod(train, "carriages", "getCarriages");
        if (!(carriages instanceof Iterable<?> carriageList)) {
            return 0;
        }

        Set<String> doneWorlds = new HashSet<>();
        int count = 0;
        for (Object carriage : carriageList) {
            double[] position = null;
            String world = null;

            Object dimensions = Reflect.optionalCall(carriage, "getPresentDimensions");
            if (dimensions instanceof Iterable<?> dimensionList) {
                for (Object dimension : dimensionList) {
                    Object pos = Reflect.optionalValue(Reflect.optionalCall(carriage, "getPositionInDimension", dimension));
                    if (pos == null) {
                        continue;
                    }
                    position = new double[] {coord(pos, "getX", "x"), coord(pos, "getY", "y"), coord(pos, "getZ", "z")};
                    world = plugin.dynmapWorld(plugin.dimensionId(dimension));
                    break;
                }
            }

            if (position == null) {
                Object entity = Reflect.optionalCall(carriage, "anyAvailableEntity");
                if (entity == null) {
                    continue;
                }
                position = new double[] {
                    Reflect.number(Reflect.call(entity, "getX")),
                    Reflect.number(Reflect.call(entity, "getY")),
                    Reflect.number(Reflect.call(entity, "getZ"))
                };
                Object level = Reflect.optionalCall(entity, "level");
                if (level == null) {
                    level = Reflect.optionalCall(entity, "getLevel");
                }
                world = plugin.dynmapWorld(plugin.dimensionId(Reflect.optionalCall(level, "dimension")));
            }

            if (!doneWorlds.add(world)) {
                continue;
            }
            String markerId = "train_" + trainId + "_" + Reflect.safe(world);
            if (plugin.upsert(markerId, label, "Create train", world, position[0], position[1], position[2])) {
                seen.add(markerId);
                count++;
            }
        }
        return count;
    }

    private static double coord(Object pos, String method, String field) throws Exception {
        Object value = Reflect.optionalCall(pos, method);
        if (value == null) {
            value = Reflect.fieldOrMethod(pos, field, method);
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalStateException("Missing coordinate " + method + " on " + pos.getClass().getName());
    }
}
