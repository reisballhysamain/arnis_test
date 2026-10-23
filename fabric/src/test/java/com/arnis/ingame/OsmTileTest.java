package com.arnis.ingame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class OsmTileTest {
    @Test
    void parsesRoadsAndBuildingFootprintsFromOverpassJson() {
        String json = """
            {
              "elements": [
                {"type":"node","id":1,"lat":51.5,"lon":-0.12},
                {"type":"node","id":2,"lat":51.5,"lon":-0.11},
                {"type":"node","id":3,"lat":51.51,"lon":-0.11},
                {"type":"way","id":10,"nodes":[1,2],"tags":{"highway":"residential"}},
                {"type":"way","id":11,"nodes":[1,2,3,1],"tags":{"building":"yes"}}
              ]
            }
            """;

        OsmTile tile = OsmTile.parse(json);

        assertEquals(2, tile.features().size());
        assertEquals(OsmTile.Kind.ROAD, tile.features().get(0).kind());
        assertEquals(OsmTile.Kind.BUILDING, tile.features().get(1).kind());
        assertEquals(4, tile.features().get(1).points().size());
    }

    @Test
    void rejectsIncompleteOverpassResponses() {
        assertThrows(
            IllegalArgumentException.class,
            () -> OsmTile.parse("{\"remark\":\"runtime error: query timed out\",\"elements\":[]}")
        );
    }

    @Test
    void ignoresExplicitlyUnbuiltFeatures() {
        String json = """
            {
              "elements": [
                {"type":"node","id":1,"lat":51.5,"lon":-0.12},
                {"type":"node","id":2,"lat":51.5,"lon":-0.11},
                {"type":"way","id":10,"nodes":[1,2],"tags":{"building":"no"}}
              ]
            }
            """;

        assertEquals(0, OsmTile.parse(json).features().size());
    }
}
