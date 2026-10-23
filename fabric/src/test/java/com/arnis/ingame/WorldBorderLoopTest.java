package com.arnis.ingame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldBorderLoopTest {
    @Test
    void wrapsEastAndWestAcrossTheBorder() {
        WorldBorderLoop.Position east = WorldBorderLoop.wrapPosition(
            new WorldBorderLoop.Position(7.0, 0.0),
            new WorldBorderLoop.Position(9.0, 0.0),
            0.0,
            0.0,
            20.0
        );
        WorldBorderLoop.Position west = WorldBorderLoop.wrapPosition(
            new WorldBorderLoop.Position(-7.0, 0.0),
            new WorldBorderLoop.Position(-9.0, 0.0),
            0.0,
            0.0,
            20.0
        );

        assertEquals(-7.0, east.x(), 1e-9);
        assertEquals(7.0, west.x(), 1e-9);
    }

    @Test
    void wrapsBothAxesWhenCrossingACorner() {
        WorldBorderLoop.Position wrapped = WorldBorderLoop.wrapPosition(
            new WorldBorderLoop.Position(7.0, 7.0),
            new WorldBorderLoop.Position(9.0, 9.0),
            0.0,
            0.0,
            20.0
        );

        assertEquals(1.0, wrapped.x(), 1e-9);
        assertEquals(7.0, wrapped.z(), 1e-9);
        assertTrue(wrapped.polarWrap());
    }

    @Test
    void crossingEitherPoleReturnsAtTheSamePoleOnTheOppositeLongitude() {
        WorldBorderLoop.Position north = WorldBorderLoop.wrapPosition(
            new WorldBorderLoop.Position(0.0, -7.0),
            new WorldBorderLoop.Position(0.0, -9.0),
            0.0,
            0.0,
            20.0
        );
        WorldBorderLoop.Position south = WorldBorderLoop.wrapPosition(
            new WorldBorderLoop.Position(0.0, 7.0),
            new WorldBorderLoop.Position(0.0, 9.0),
            0.0,
            0.0,
            20.0
        );

        assertEquals(-8.0, north.x(), 1e-9);
        assertEquals(-7.0, north.z(), 1e-9);
        assertTrue(north.polarWrap());
        assertEquals(-8.0, south.x(), 1e-9);
        assertEquals(7.0, south.z(), 1e-9);
        assertTrue(south.polarWrap());
    }

    @Test
    void doesNotWrapWithoutCrossingOrAfterALargeTeleport() {
        assertNull(WorldBorderLoop.wrapPosition(
            new WorldBorderLoop.Position(8.0, 0.0),
            new WorldBorderLoop.Position(8.0, 0.0),
            0.0,
            0.0,
            20.0
        ));
        assertNull(WorldBorderLoop.wrapPosition(
            new WorldBorderLoop.Position(0.0, 0.0),
            new WorldBorderLoop.Position(40.0, 0.0),
            0.0,
            0.0,
            20.0
        ));
    }
}
