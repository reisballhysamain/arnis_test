package com.arnis.ingame;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ArnisInGame implements ModInitializer {
    public static final String MOD_ID = "arnis_ingame";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final WorldBorderLoop WORLD_BORDER_LOOP = new WorldBorderLoop();
    private static volatile WorldTileService tileService;

    static WorldTileService tileService() {
        return tileService;
    }

    static WorldBorderLoop worldBorderLoop() {
        return WORLD_BORDER_LOOP;
    }

    @Override
    public void onInitialize() {
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (world.getRegistryKey().equals(World.OVERWORLD)) {
                WorldTileService service = tileService;
                if (service != null) {
                    service.onChunkLoaded((ServerWorld) world, chunk.getPos());
                }
            }
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            if (world.getRegistryKey().equals(World.OVERWORLD)) {
                WorldTileService service = tileService;
                if (service != null) {
                    service.onChunkUnloaded(chunk.getPos());
                }
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            WorldTileService service = tileService;
            if (service != null) {
                service.tick();
            }
            WORLD_BORDER_LOOP.tick(server);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            ArnisCommands.register(dispatcher)
        );

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ServerWorld overworld = server.getOverworld();
            ArnisWorldState worldState = ArnisWorldState.get(overworld);
            if (!worldState.isBorderInitialized()) {
                LondonProjection.WorldBounds bounds = LondonProjection.realWorldBounds();
                overworld.getWorldBorder().setCenter(bounds.centerX(), bounds.centerZ());
                overworld.getWorldBorder().setSize(bounds.borderSize());
                worldState.setBorderInitialized();
                LOGGER.info(
                    "Set the default world border to {} blocks to cover the supported real-world map.",
                    bounds.borderSize()
                );
            }
            WorldTileService previous = tileService;
            if (previous != null) {
                previous.close();
            }
            tileService = new WorldTileService(server.getSavePath(WorldSavePath.ROOT));
            BlockPos spawn = overworld.getTopPosition(
                Heightmap.Type.MOTION_BLOCKING,
                BlockPos.ORIGIN
            );
            overworld.setSpawnPos(spawn, 0.0F);
            tileService.onChunkLoaded(overworld, new ChunkPos(BlockPos.ORIGIN));
            LOGGER.info(
                "Arnis geographic origin is central London at X/Z 0; set world spawn to {}.",
                spawn
            );
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            WORLD_BORDER_LOOP.clear();
            WorldTileService service = tileService;
            tileService = null;
            if (service != null) {
                service.close();
            }
        });
    }
}
