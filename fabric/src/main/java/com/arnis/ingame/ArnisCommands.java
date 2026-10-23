package com.arnis.ingame;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.server.network.ServerPlayerEntity;

final class ArnisCommands {
    private ArnisCommands() {}

    static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            net.minecraft.server.command.CommandManager.literal("arnis")
                .then(net.minecraft.server.command.CommandManager.literal("coordinates")
                    .executes(context -> showCoordinates(context.getSource())))
                .then(net.minecraft.server.command.CommandManager.literal("status")
                    .executes(context -> showStatus(context.getSource())))
                .then(net.minecraft.server.command.CommandManager.literal("attribution")
                    .executes(context -> showAttribution(context.getSource())))
                .then(net.minecraft.server.command.CommandManager.literal("retry")
                    .executes(context -> retryNearby(context.getSource())))
        );
    }

    private static int showCoordinates(ServerCommandSource source)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        LondonProjection.GeoPoint point = LondonProjection.unproject(
            player.getX(),
            player.getZ()
        );
        if (!LondonProjection.isSupportedLocation(point.latitude(), point.longitude())) {
            source.sendError(Text.literal("This location is outside the supported map projection."));
            return 0;
        }
        source.sendFeedback(
            () -> Text.literal(
                String.format(
                    java.util.Locale.ROOT,
                    "OSM location: %.6f, %.6f",
                    point.latitude(),
                    point.longitude()
                )
            ),
            false
        );
        return 1;
    }

    private static int showAttribution(ServerCommandSource source) {
        source.sendFeedback(
            () -> Text.literal(
                "Map data © OpenStreetMap contributors, available under ODbL 1.0: "
                    + "https://www.openstreetmap.org/copyright"
            ),
            false
        );
        return 1;
    }

    private static int showStatus(ServerCommandSource source) {
        WorldTileService service = ArnisInGame.tileService();
        source.sendFeedback(
            () -> Text.literal(service == null ? "Arnis is not connected to a world." : service.status()),
            false
        );
        return 1;
    }

    private static int retryNearby(ServerCommandSource source)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        if (!(player.getWorld() instanceof ServerWorld world)
            || !world.getRegistryKey().equals(World.OVERWORLD)) {
            source.sendError(Text.literal("Arnis tile retry is only available in the Overworld."));
            return 0;
        }

        WorldTileService service = ArnisInGame.tileService();
        if (service == null) {
            source.sendError(Text.literal("Arnis is not connected to a world."));
            return 0;
        }
        service.retryNearby(world, new ChunkPos(player.getBlockPos()), 3);
        source.sendFeedback(
            () -> Text.literal("Retried OSM data for loaded chunks within 3 chunks."),
            false
        );
        return 1;
    }
}
