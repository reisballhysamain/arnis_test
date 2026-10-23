package com.arnis.ingame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LondonProjectionTest {
    @Test
    void centralLondonMapsToTheWorldOrigin() {
        LondonProjection.WorldPoint point = LondonProjection.project(
            LondonProjection.ORIGIN_LATITUDE,
            LondonProjection.ORIGIN_LONGITUDE
        );

        assertEquals(0.0, point.x(), 1e-9);
        assertEquals(0.0, point.z(), 1e-9);
    }

    @Test
    void eastIsPositiveXAndNorthIsNegativeZ() {
        LondonProjection.WorldPoint origin = LondonProjection.project(
            LondonProjection.ORIGIN_LATITUDE,
            LondonProjection.ORIGIN_LONGITUDE
        );
        LondonProjection.WorldPoint east = LondonProjection.project(
            LondonProjection.ORIGIN_LATITUDE,
            LondonProjection.ORIGIN_LONGITUDE + 0.01
        );
        LondonProjection.WorldPoint north = LondonProjection.project(
            LondonProjection.ORIGIN_LATITUDE + 0.01,
            LondonProjection.ORIGIN_LONGITUDE
        );

        assertTrue(east.x() > origin.x());
        assertTrue(north.z() < origin.z());
    }

    @Test
    void projectionRoundTripsGeographicCoordinates() {
        double latitude = 40.7128;
        double longitude = -74.0060;
        LondonProjection.WorldPoint point = LondonProjection.project(latitude, longitude);
        LondonProjection.GeoPoint restored = LondonProjection.unproject(point.x(), point.z());

        assertEquals(latitude, restored.latitude(), 1e-9);
        assertEquals(longitude, restored.longitude(), 1e-9);
    }

    @Test
    void clampsPolarCoordinatesToTheMercatorDomain() {
        LondonProjection.WorldPoint point = LondonProjection.project(90.0, 0.0);

        assertTrue(Double.isFinite(point.x()));
        assertTrue(Double.isFinite(point.z()));
        assertTrue(LondonProjection.unproject(point.x(), point.z()).latitude() < 90.0);
    }

    @Test
    void allEarthExtentsFitInsideTheMinecraftWorldBorder() {
        for (double latitude : new double[] {-85.05112878, 85.05112878}) {
            for (double longitude : new double[] {-180.0, 180.0}) {
                LondonProjection.WorldPoint point = LondonProjection.project(latitude, longitude);

                assertTrue(Math.abs(point.x()) <= LondonProjection.WORLD_BORDER);
                assertTrue(Math.abs(point.z()) <= LondonProjection.WORLD_BORDER);
            }
        }
    }

    @Test
    void realWorldBoundsCoverTheEntireSupportedProjection() {
        LondonProjection.WorldBounds bounds = LondonProjection.realWorldBounds();
        for (double latitude : new double[] {-LondonProjection.MAX_LATITUDE, LondonProjection.MAX_LATITUDE}) {
            for (double longitude : new double[] {-180.0, 180.0}) {
                LondonProjection.WorldPoint point = LondonProjection.project(latitude, longitude);

                assertTrue(point.x() >= bounds.minX() - 1e-6);
                assertTrue(point.x() <= bounds.maxX() + 1e-6);
                assertTrue(point.z() >= bounds.minZ() - 1e-6);
                assertTrue(point.z() <= bounds.maxZ() + 1e-6);
            }
        }
        assertTrue(bounds.borderSize() <= LondonProjection.WORLD_BORDER * 2.0);
    }

    @Test
    void excludesOnlyCoordinatesOutsideTheMercatorWorldCoverage() {
        assertFalse(LondonProjection.isSupportedLocation(90.0, 0.0));
        assertTrue(LondonProjection.isSupportedLocation(85.0, 180.0));
        assertTrue(LondonProjection.isSupportedLocation(-85.0, -180.0));
        assertFalse(LondonProjection.isSupportedLocation(0.0, 181.0));
    }

    @Test
    void rejectsInvalidGeographicInput() {
        assertThrows(IllegalArgumentException.class, () -> LondonProjection.project(91.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> LondonProjection.project(0.0, Double.NaN));
    }
}
