package com.arnis.ingame;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.server.network.ServerPlayerEntity;

final class ArnisCommands {
    private static final double MIN_WORLD_BORDER_SIZE = 16.0;
    private static final double MAX_WORLD_BORDER_SIZE = LondonProjection.WORLD_BORDER * 2.0;

    private ArnisCommands() {}

    static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            net.minecraft.server.command.CommandManager.literal("arnis")
                .then(net.minecraft.server.command.CommandManager.literal("border")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(net.minecraft.server.command.CommandManager.argument(
                        "size",
                        DoubleArgumentType.doubleArg(
                            MIN_WORLD_BORDER_SIZE,
                            MAX_WORLD_BORDER_SIZE
                        )
                    ).executes(context -> setBorder(
                        context.getSource(),
                        DoubleArgumentType.getDouble(context, "size")
                    )))
                    .then(net.minecraft.server.command.CommandManager.literal("loop")
                        .then(net.minecraft.server.command.CommandManager.argument(
                            "enabled",
                            BoolArgumentType.bool()
                        ).executes(context -> setBorderLoop(
                            context.getSource(),
                            BoolArgumentType.getBool(context, "enabled")
                        )))))
                .then(net.minecraft.server.command.CommandManager.literal("coordinates")
                    .executes(context -> showCoordinates(context.getSource())))
                .then(net.minecraft.server.command.CommandManager.literal("teleport")
                    .then(net.minecraft.server.command.CommandManager.argument(
                        "latitude",
                        DoubleArgumentType.doubleArg(
                            -LondonProjection.MAX_LATITUDE,
                            LondonProjection.MAX_LATITUDE
                        )
                    ).then(net.minecraft.server.command.CommandManager.argument(
                        "longitude",
                        DoubleArgumentType.doubleArg(-180.0, 180.0)
                    ).executes(context -> teleportToCoordinates(
                        context.getSource(),
                        DoubleArgumentType.getDouble(context, "latitude"),
                        DoubleArgumentType.getDouble(context, "longitude")
                    )))))
                .then(net.minecraft.server.command.CommandManager.literal("status")
                    .executes(context -> showStatus(context.getSource())))
                .then(net.minecraft.server.command.CommandManager.literal("attribution")
                    .executes(context -> showAttribution(context.getSource())))
                .then(net.minecraft.server.command.CommandManager.literal("retry")
                    .executes(context -> retryNearby(context.getSource())))
        );
    }

    private static int setBorder(ServerCommandSource source, double size) {
        ServerWorld world = source.getWorld();
        if (!world.getRegistryKey().equals(World.OVERWORLD)) {
            source.sendError(Text.literal(
                "The Arnis world border can only be changed in the Overworld."
            ));
            return 0;
        }

        LondonProjection.WorldBounds bounds = LondonProjection.realWorldBounds();
        world.getWorldBorder().setCenter(bounds.centerX(), bounds.centerZ());
        world.getWorldBorder().setSize(size);
        ArnisWorldState.get(world).setBorderInitialized();
        source.sendFeedback(
            () -> Text.literal(String.format(
                java.util.Locale.ROOT,
                "Arnis world border set to %.1f blocks, centered at X/Z %.1f, %.1f.",
                size,
                bounds.centerX(),
                bounds.centerZ()
            )),
            true
        );
        return 1;
    }

    private static int setBorderLoop(ServerCommandSource source, boolean enabled) {
        ServerWorld world = source.getWorld();
        if (!world.getRegistryKey().equals(World.OVERWORLD)) {
            source.sendError(Text.literal(
                "World border looping can only be changed in the Overworld."
            ));
            return 0;
        }

        ArnisWorldState.get(world).setBorderLoopEnabled(enabled);
        source.sendFeedback(
            () -> Text.literal("Arnis world border looping " + (enabled ? "enabled." : "disabled.")),
            true
        );
        return 1;
    }

    private static int teleportToCoordinates(
        ServerCommandSource source,
        double latitude,
        double longitude
    ) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        if (!(player.getWorld() instanceof ServerWorld world)
            || !world.getRegistryKey().equals(World.OVERWORLD)) {
            source.sendError(Text.literal(
                "Arnis coordinate teleport is only available in the Overworld."
            ));
            return 0;
        }
        if (!LondonProjection.isSupportedLocation(latitude, longitude)) {
            source.sendError(Text.literal("That location is outside the supported map projection."));
            return 0;
        }

        LondonProjection.WorldPoint target = LondonProjection.project(latitude, longitude);
        if (!world.getWorldBorder().contains(target.x(), target.z())) {
            source.sendError(Text.literal("That location is outside the current world border."));
            return 0;
        }

        BlockPos surface = world.getTopPosition(
            Heightmap.Type.MOTION_BLOCKING,
            BlockPos.ofFloored(target.x(), 0.0, target.z())
        );
        player.teleport(
            world,
            target.x(),
            surface.getY(),
            target.z(),
            player.getYaw(),
            player.getPitch()
        );
        ArnisInGame.worldBorderLoop().reset(player);
        source.sendFeedback(
            () -> Text.literal(String.format(
                java.util.Locale.ROOT,
                "Teleported to %.6f, %.6f.",
                latitude,
                longitude
            )),
            false
        );
        return 1;
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
