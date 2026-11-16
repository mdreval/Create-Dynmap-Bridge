package com.igrobar.createdynmap.web;

import java.util.List;

/**
 * Immutable snapshots of the railway data.
 * They are collected on the server main thread (where reading Create is safe) and
 * turned into JSON on a separate web map thread.
 */
public final class MapData {
    private MapData() {
    }

    /** A track piece: 2 points (straight) or 4 points (Bezier curve: start, control 1, control 2, end). */
    public record TrackPiece(String dimension, String type, double[] points) {
    }

    /** A track link between dimensions (portal). */
    public record PortalLink(String fromDimension, double[] from, String toDimension, double[] to) {
    }

    public record StationInfo(String id, String name, String dimension, double x, double y, double z) {
    }

    /** A carriage: leading and trailing points (each may be in its own dimension or missing). */
    public record CarInfo(String leadDimension, double[] lead, String trailDimension, double[] trail) {
    }

    public record TrainInfo(String id, String name, double speed, boolean derailed, String station,
                            String destination, List<CarInfo> cars) {
    }

    public record Network(List<TrackPiece> tracks, List<PortalLink> portals) {
    }

    static String networkJson(Network network) {
        Json json = new Json(64 + network.tracks().size() * 110);
        json.beginObject();
        json.key("tracks").beginArray();
        for (TrackPiece track : network.tracks()) {
            json.beginObject().field("d", track.dimension()).field("t", track.type());
            json.key("p").beginArray();
            double[] p = track.points();
            for (int i = 0; i + 2 < p.length; i += 3) {
                json.point(p[i], p[i + 1], p[i + 2]);
            }
            json.endArray().endObject();
        }
        json.endArray();
        json.key("portals").beginArray();
        for (PortalLink portal : network.portals()) {
            json.beginObject().field("fd", portal.fromDimension());
            json.key("f").point(portal.from()[0], portal.from()[1], portal.from()[2]);
            json.field("td", portal.toDimension());
            json.key("t").point(portal.to()[0], portal.to()[1], portal.to()[2]);
            json.endObject();
        }
        json.endArray();
        json.endObject();
        return json.toString();
    }

    static String stationsJson(List<StationInfo> stations) {
        Json json = new Json(32 + stations.size() * 120);
        json.beginObject().key("stations").beginArray();
        for (StationInfo station : stations) {
            json.beginObject().field("id", station.id()).field("name", station.name()).field("d", station.dimension());
            json.key("p").point(station.x(), station.y(), station.z());
            json.endObject();
        }
        json.endArray().endObject();
        return json.toString();
    }

    static String trainsJson(List<TrainInfo> trains, long time, long intervalMs) {
        Json json = new Json(64 + trains.size() * 300);
        json.beginObject().field("time", time).field("interval", intervalMs);
        json.key("trains").beginArray();
        for (TrainInfo train : trains) {
            json.beginObject()
                    .field("id", train.id())
                    .field("name", train.name())
                    .field("speed", train.speed())
                    .field("derailed", train.derailed())
                    .field("station", train.station())
                    .field("dest", train.destination());
            json.key("cars").beginArray();
            for (CarInfo car : train.cars()) {
                json.beginObject();
                if (car.lead() != null) {
                    json.field("ld", car.leadDimension());
                    json.key("l").point(car.lead()[0], car.lead()[1], car.lead()[2]);
                }
                if (car.trail() != null) {
                    json.field("td", car.trailDimension());
                    json.key("t").point(car.trail()[0], car.trail()[1], car.trail()[2]);
                }
                json.endObject();
            }
            json.endArray().endObject();
        }
        json.endArray().endObject();
        return json.toString();
    }
}
