package com.arnis.ingame;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.border.WorldBorder;

final class WorldBorderLoop {
    private static final double WRAP_MARGIN = 2.0;
    private static final double MAX_MOVEMENT_PER_TICK = 32.0;

    private final Map<UUID, Position> previousPositions = new HashMap<>();

    void tick(MinecraftServer server) {
        ServerWorld world = server.getOverworld();
        if (!ArnisWorldState.get(world).isBorderLoopEnabled()) {
            previousPositions.clear();
            return;
        }

        WorldBorder border = world.getWorldBorder();
        Set<UUID> onlinePlayers = new HashSet<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID playerId = player.getUuid();
            if (player.getWorld() != world) {
                previousPositions.remove(playerId);
                continue;
            }

            onlinePlayers.add(playerId);
            Position current = new Position(player.getX(), player.getZ());
            Position previous = previousPositions.put(playerId, current);
            if (previous == null) {
                continue;
            }

            Position wrapped = wrapPosition(
                previous,
                current,
                border.getCenterX(),
                border.getCenterZ(),
                border.getSize()
            );
            if (wrapped != null) {
                player.teleport(
                    world,
                    wrapped.x(),
                    player.getY(),
                    wrapped.z(),
                    wrapped.polarWrap() ? 180.0F - player.getYaw() : player.getYaw(),
                    player.getPitch()
                );
                if (wrapped.polarWrap()) {
                    player.setVelocity(
                        player.getVelocity().x,
                        player.getVelocity().y,
                        -player.getVelocity().z
                    );
                }
                previousPositions.put(playerId, wrapped);
            }
        }
        previousPositions.keySet().retainAll(onlinePlayers);
    }

    void reset(ServerPlayerEntity player) {
        previousPositions.put(player.getUuid(), new Position(player.getX(), player.getZ()));
    }

    void clear() {
        previousPositions.clear();
    }

    static Position wrapPosition(
        Position previous,
        Position current,
        double centerX,
        double centerZ,
        double size
    ) {
        if (size < WRAP_MARGIN * 2.0 || !Double.isFinite(size)) {
            return null;
        }
        double halfSize = size / 2.0;
        double positiveEdge = halfSize - WRAP_MARGIN;
        double negativeEdge = -positiveEdge;
        double wrapDistance = positiveEdge - negativeEdge;
        double movementZ = current.z() - previous.z();
        boolean crossedNorthPole = Math.abs(movementZ) <= MAX_MOVEMENT_PER_TICK
            && previous.z() > centerZ + negativeEdge
            && current.z() <= centerZ + negativeEdge
            && movementZ < 0.0;
        boolean crossedSouthPole = Math.abs(movementZ) <= MAX_MOVEMENT_PER_TICK
            && previous.z() < centerZ + positiveEdge
            && current.z() >= centerZ + positiveEdge
            && movementZ > 0.0;
        boolean polarWrap = crossedNorthPole || crossedSouthPole;
        double x = wrapAxis(
            previous.x(),
            current.x(),
            centerX,
            positiveEdge,
            negativeEdge,
            wrapDistance
        );
        double z = current.z();
        if (crossedNorthPole) {
            z = 2.0 * (centerZ + negativeEdge) - current.z();
            x = wrapCoordinate(
                current.x() + wrapDistance / 2.0,
                centerX,
                negativeEdge,
                positiveEdge
            );
        } else if (crossedSouthPole) {
            z = 2.0 * (centerZ + positiveEdge) - current.z();
            x = wrapCoordinate(
                current.x() + wrapDistance / 2.0,
                centerX,
                negativeEdge,
                positiveEdge
            );
        }
        if (x == current.x() && z == current.z()) {
            return null;
        }
        return new Position(x, z, polarWrap);
    }

    private static double wrapCoordinate(
        double coordinate,
        double center,
        double negativeEdge,
        double positiveEdge
    ) {
        double width = positiveEdge - negativeEdge;
        double offset = (coordinate - (center + negativeEdge)) % width;
        if (offset < 0.0) {
            offset += width;
        }
        return center + negativeEdge + offset;
    }

    private static double wrapAxis(
        double previous,
        double current,
        double center,
        double positiveEdge,
        double negativeEdge,
        double size
    ) {
        double movement = current - previous;
        if (Math.abs(movement) > MAX_MOVEMENT_PER_TICK) {
            return current;
        }
        if (previous < center + positiveEdge
            && current >= center + positiveEdge
            && movement > 0.0) {
            return current - size;
        }
        if (previous > center + negativeEdge
            && current <= center + negativeEdge
            && movement < 0.0) {
            return current + size;
        }
        return current;
    }

    record Position(double x, double z, boolean polarWrap) {
        Position(double x, double z) {
            this(x, z, false);
        }
    }
}
