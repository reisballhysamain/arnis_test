package com.arnis.ingame;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

record OsmTile(List<Feature> features) {
    static OsmTile parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("remark")) {
            throw new IllegalArgumentException(
                "Overpass returned an incomplete result: " + root.get("remark").getAsString()
            );
        }
        Map<Long, Point> nodes = new HashMap<>();
        for (JsonElement element : root.getAsJsonArray("elements")) {
            JsonObject value = element.getAsJsonObject();
            if (value.get("type").getAsString().equals("node")) {
                nodes.put(
                    value.get("id").getAsLong(),
                    new Point(value.get("lat").getAsDouble(), value.get("lon").getAsDouble())
                );
            }
        }

        List<Feature> features = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("elements")) {
            JsonObject value = element.getAsJsonObject();
            if (!value.get("type").getAsString().equals("way")) {
                continue;
            }

            Map<String, String> tags = readTags(value.getAsJsonObject("tags"));
            Kind kind = classify(tags);
            if (kind == null) {
                continue;
            }

            List<Point> points = new ArrayList<>();
            for (JsonElement node : value.getAsJsonArray("nodes")) {
                Point point = nodes.get(node.getAsLong());
                if (point != null) {
                    points.add(point);
                }
            }
            if (points.size() >= 2) {
                features.add(new Feature(kind, List.copyOf(points), Map.copyOf(tags)));
            }
        }
        return new OsmTile(List.copyOf(features));
    }

    private static Map<String, String> readTags(JsonObject object) {
        Map<String, String> tags = new HashMap<>();
        if (object != null) {
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    tags.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        return tags;
    }

    private static Kind classify(Map<String, String> tags) {
        String building = tags.get("building");
        if (building != null && !building.equals("no") && !building.equals("false")) {
            return Kind.BUILDING;
        }
        if (tags.containsKey("highway")) {
            return Kind.ROAD;
        }
        if (tags.containsKey("waterway")) {
            return Kind.WATERWAY;
        }
        if ("water".equals(tags.get("natural"))
            || "reservoir".equals(tags.get("landuse"))
            || tags.containsKey("water")) {
            return Kind.WATER;
        }
        return null;
    }

    enum Kind {
        BUILDING,
        ROAD,
        WATERWAY,
        WATER
    }

    record Point(double latitude, double longitude) {}

    record Feature(Kind kind, List<Point> points, Map<String, String> tags) {}
}
