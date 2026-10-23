package com.arnis.ingame;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WorldTileService implements AutoCloseable {
    static final int TILE_BLOCKS = 512;
    private static final Logger LOGGER = LoggerFactory.getLogger("arnis_ingame");
    private static final String OVERPASS_URL = "https://api.arnismc.com/overpass/api/interpreter";
    private static final int MAX_RESPONSE_CHARS = 24_000_000;
    private static final int MAX_CHUNK_UPDATES_PER_TICK = 4;
    private static final long FAILURE_BACKOFF_MILLIS = TimeUnit.MINUTES.toMillis(2);

    private final Path cacheDirectory;
    private final HttpClient httpClient;
    private final ExecutorService fetchExecutor;
    private final Map<TileKey, CompletableFuture<OsmTile>> tileLoads = new ConcurrentHashMap<>();
    private final Map<TileKey, Long> retryAfter = new ConcurrentHashMap<>();
    private final Map<ChunkKey, PendingChunk> pendingChunks = new HashMap<>();
    private final Set<ChunkKey> appliedChunks = new HashSet<>();
    private final Map<TileKey, BitSet> processedTiles = new HashMap<>();
    private final AtomicInteger cachedTileCount = new AtomicInteger();
    private final AtomicInteger failedTileCount = new AtomicInteger();
    private int processedChunkCount;

    WorldTileService(Path saveDirectory) {
        this.cacheDirectory = saveDirectory
            .resolve("arnis")
            .resolve("osm-tiles");
        this.fetchExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "arnis-osm-fetch");
            thread.setDaemon(true);
            return thread;
        });
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    void onChunkLoaded(ServerWorld world, ChunkPos position) {
        ChunkKey chunk = new ChunkKey(position.x, position.z);
        if (appliedChunks.contains(chunk) || pendingChunks.containsKey(chunk)) {
            return;
        }

        TileKey tile = TileKey.forChunk(position);
        if (tile == null) {
            return;
        }
        if (isChunkProcessed(tile, position)) {
            appliedChunks.add(chunk);
            return;
        }
        long retryTime = retryAfter.getOrDefault(tile, 0L);
        if (retryTime > System.currentTimeMillis()) {
            return;
        }
        retryAfter.remove(tile, retryTime);
        CompletableFuture<OsmTile> future = tileLoads.computeIfAbsent(
            tile,
            key -> CompletableFuture.supplyAsync(() -> loadTile(key), fetchExecutor)
        );
        pendingChunks.put(chunk, new PendingChunk(world, position, tile, future));
    }

    void onChunkUnloaded(ChunkPos position) {
        ChunkKey key = new ChunkKey(position.x, position.z);
        pendingChunks.remove(key);
        appliedChunks.remove(key);
    }

    void tick() {
        int updates = 0;
        Iterator<Map.Entry<ChunkKey, PendingChunk>> iterator =
            pendingChunks.entrySet().iterator();
        while (iterator.hasNext() && updates < MAX_CHUNK_UPDATES_PER_TICK) {
            Map.Entry<ChunkKey, PendingChunk> entry = iterator.next();
            PendingChunk pending = entry.getValue();
            if (!pending.world().isChunkLoaded(pending.position().x, pending.position().z)) {
                iterator.remove();
                continue;
            }
            if (!pending.tileFuture().isDone()) {
                continue;
            }

            iterator.remove();
            try {
                OsmTile tile = pending.tileFuture().join();
                applyTile(pending.world(), pending.position(), tile);
                persistProcessedChunk(pending.tileKey(), pending.position());
                appliedChunks.add(entry.getKey());
                processedChunkCount++;
                if (pendingChunks.values().stream().noneMatch(
                    other -> other.tileKey().equals(pending.tileKey())
                )) {
                    tileLoads.remove(pending.tileKey(), pending.tileFuture());
                }
            } catch (CompletionException exception) {
                if (tileLoads.remove(pending.tileKey(), pending.tileFuture())) {
                    retryAfter.put(
                        pending.tileKey(),
                        System.currentTimeMillis() + FAILURE_BACKOFF_MILLIS
                    );
                    failedTileCount.incrementAndGet();
                    LOGGER.error(
                        "Could not load OSM tile ({}, {}). The vanilla chunk was left unchanged; "
                            + "retry after the two-minute backoff.",
                        pending.tileKey().x(),
                        pending.tileKey().z(),
                        exception.getCause()
                    );
                }
            } catch (RuntimeException exception) {
                LOGGER.error(
                    "Could not apply OSM tile ({}, {}) to chunk ({}, {}).",
                    pending.tileKey().x(),
                    pending.tileKey().z(),
                    pending.position().x,
                    pending.position().z,
                    exception
                );
            }
            updates++;
        }
    }

    String status() {
        return "tiles loaded: " + cachedTileCount.get()
            + ", pending chunks: " + pendingChunks.size()
            + ", chunks updated: " + processedChunkCount
            + ", tile failures: " + failedTileCount.get();
    }

    void retryNearby(ServerWorld world, ChunkPos center, int radius) {
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                int chunkX = center.x + x;
                int chunkZ = center.z + z;
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    TileKey key = TileKey.forChunk(new ChunkPos(chunkX, chunkZ));
                    if (key == null) {
                        continue;
                    }
                    retryAfter.remove(key);
                    onChunkLoaded(world, new ChunkPos(chunkX, chunkZ));
                }
            }
        }
    }

    private OsmTile loadTile(TileKey key) {
        try {
            Files.createDirectories(cacheDirectory);
            Path cacheFile = cacheDirectory.resolve(key.x() + "_" + key.z() + ".json");
            if (Files.exists(cacheFile)) {
                try {
                    if (Files.size(cacheFile) > MAX_RESPONSE_CHARS) {
                        throw new IOException("Cached OSM tile exceeds the 24 MB safety limit.");
                    }
                    String cachedJson = Files.readString(cacheFile);
                    OsmTile cached = OsmTile.parse(cachedJson);
                    cachedTileCount.incrementAndGet();
                    return cached;
                } catch (IOException | RuntimeException corruptCache) {
                    LOGGER.warn(
                        "Ignoring unreadable cached OSM tile ({}, {}) and fetching it again.",
                        key.x(),
                        key.z(),
                        corruptCache
                    );
                    Path corruptFile = cacheFile.resolveSibling(cacheFile.getFileName() + ".corrupt");
                    Files.move(
                        cacheFile,
                        corruptFile,
                        StandardCopyOption.REPLACE_EXISTING
                    );
                }
            }

            TimeUnit.SECONDS.sleep(1);
            String query = buildQuery(key);
            if (query == null) {
                Files.writeString(cacheFile, "{\"elements\":[]}", StandardCharsets.UTF_8);
                cachedTileCount.incrementAndGet();
                return new OsmTile(List.of());
            }
            URI uri = URI.create(
                OVERPASS_URL + "?data=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
            );
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header(
                    "User-Agent",
                    "ArnisInGame/0.1.0 (+https://github.com/louis-e/arnis)"
                )
                .header("Accept", "application/json")
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() != 200) {
                throw new IOException("Overpass returned HTTP " + response.statusCode());
            }
            String body = response.body();
            if (body.length() > MAX_RESPONSE_CHARS) {
                throw new IOException("OSM tile exceeds the 24 MB safety limit.");
            }
            OsmTile tile = OsmTile.parse(body);

            Path temporary = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.writeString(temporary, body, StandardCharsets.UTF_8);
            try {
                Files.move(
                    temporary,
                    cacheFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
            cachedTileCount.incrementAndGet();
            return tile;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new CompletionException(exception);
        } catch (RuntimeException exception) {
            throw new CompletionException(exception);
        }
    }

    private boolean isChunkProcessed(TileKey tile, ChunkPos position) {
        try {
            return processedBits(tile).get(chunkBitIndex(tile, position));
        } catch (IOException exception) {
            LOGGER.error(
                "Could not read the processed-chunk index for OSM tile ({}, {}).",
                tile.x(),
                tile.z(),
                exception
            );
            return false;
        }
    }

    private BitSet processedBits(TileKey tile) throws IOException {
        BitSet bits = processedTiles.get(tile);
        if (bits != null) {
            return bits;
        }
        Path index = processedIndexPath(tile);
        Files.createDirectories(index.getParent());
        if (Files.exists(index)) {
            if (Files.size(index) > 128) {
                throw new IOException("Processed-chunk index exceeds its 128-byte limit.");
            }
            bits = BitSet.valueOf(Files.readAllBytes(index));
        } else {
            bits = new BitSet(1024);
        }
        processedTiles.put(tile, bits);
        return bits;
    }

    private void persistProcessedChunk(TileKey tile, ChunkPos position) {
        try {
            BitSet bits = processedBits(tile);
            bits.set(chunkBitIndex(tile, position));
            Path index = processedIndexPath(tile);
            Path temporary = index.resolveSibling(index.getFileName() + ".tmp");
            Files.write(temporary, bits.toByteArray());
            try {
                Files.move(
                    temporary,
                    index,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, index, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOGGER.error(
                "OSM features were applied to chunk ({}, {}) but its processed-chunk index "
                    + "could not be saved.",
                position.x,
                position.z,
                exception
            );
        }
    }

    private Path processedIndexPath(TileKey tile) {
        return cacheDirectory.resolve("processed")
            .resolve(tile.x() + "_" + tile.z() + ".bin");
    }

    private static int chunkBitIndex(TileKey tile, ChunkPos position) {
        int localX = Math.floorMod(position.x - tile.x() * 32, 32);
        int localZ = Math.floorMod(position.z - tile.z() * 32, 32);
        return localZ * 32 + localX;
    }

    private static String buildQuery(TileKey key) {
        double minX = key.x() * (double) TILE_BLOCKS;
        double maxX = minX + TILE_BLOCKS;
        double minZ = key.z() * (double) TILE_BLOCKS;
        double maxZ = minZ + TILE_BLOCKS;
        LondonProjection.GeoPoint northWest = LondonProjection.unproject(minX, minZ);
        LondonProjection.GeoPoint southEast = LondonProjection.unproject(maxX, maxZ);
        double south = Math.max(-LondonProjection.MAX_LATITUDE, southEast.latitude());
        double north = Math.min(LondonProjection.MAX_LATITUDE, northWest.latitude());
        double west = Math.max(
            -180.0,
            Math.min(northWest.longitude(), southEast.longitude())
        );
        double east = Math.min(
            180.0,
            Math.max(northWest.longitude(), southEast.longitude())
        );
        if (south >= north || west >= east) {
            return null;
        }
        String bbox = south + "," + west + "," + north + "," + east;
        return "[out:json][timeout:45];("
            + "way[\"building\"](" + bbox + ");"
            + "way[\"highway\"](" + bbox + ");"
            + "way[\"waterway\"](" + bbox + ");"
            + "way[\"natural\"=\"water\"](" + bbox + ");"
            + "way[\"landuse\"=\"reservoir\"](" + bbox + ");"
            + ");out body;>;out skel qt;";
    }

    private static void applyTile(ServerWorld world, ChunkPos chunk, OsmTile tile) {
        int minX = chunk.getStartX();
        int minZ = chunk.getStartZ();
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        int[][] ground = new int[16][16];
        boolean[][] groundFound = new boolean[16][16];

        for (OsmTile.Feature feature : tile.features()) {
            List<WorldPoint> points = project(feature.points());
            if (points.size() < 2 || !intersects(points, minX, minZ, maxX, maxZ)) {
                continue;
            }
            switch (feature.kind()) {
                case ROAD -> drawRoad(world, feature, points, chunk, ground, groundFound);
                case WATERWAY -> drawLine(
                    world,
                    points,
                    chunk,
                    ground,
                    groundFound,
                    Blocks.WATER.getDefaultState(),
                    1
                );
                case BUILDING -> drawBuilding(
                    world,
                    feature,
                    points,
                    chunk,
                    ground,
                    groundFound
                );
                case WATER -> fillWater(world, points, chunk, ground, groundFound);
            }
        }
    }

    private static List<WorldPoint> project(List<OsmTile.Point> points) {
        List<WorldPoint> projected = new ArrayList<>(points.size());
        for (OsmTile.Point point : points) {
            LondonProjection.WorldPoint world = LondonProjection.project(
                point.latitude(),
                point.longitude()
            );
            projected.add(new WorldPoint((int) Math.floor(world.x()), (int) Math.floor(world.z())));
        }
        return projected;
    }

    private static boolean intersects(
        List<WorldPoint> points,
        int minX,
        int minZ,
        int maxX,
        int maxZ
    ) {
        int featureMinX = Integer.MAX_VALUE;
        int featureMaxX = Integer.MIN_VALUE;
        int featureMinZ = Integer.MAX_VALUE;
        int featureMaxZ = Integer.MIN_VALUE;
        for (WorldPoint point : points) {
            featureMinX = Math.min(featureMinX, point.x());
            featureMaxX = Math.max(featureMaxX, point.x());
            featureMinZ = Math.min(featureMinZ, point.z());
            featureMaxZ = Math.max(featureMaxZ, point.z());
        }
        return featureMaxX >= minX - 5
            && featureMinX <= maxX + 5
            && featureMaxZ >= minZ - 5
            && featureMinZ <= maxZ + 5;
    }

    private static void drawRoad(
        ServerWorld world,
        OsmTile.Feature feature,
        List<WorldPoint> points,
        ChunkPos chunk,
        int[][] ground,
        boolean[][] groundFound
    ) {
        String highway = feature.tags().getOrDefault("highway", "");
        int width = switch (highway) {
            case "motorway", "trunk", "primary" -> 2;
            case "secondary", "tertiary", "residential", "unclassified" -> 1;
            default -> 0;
        };
        BlockState road = switch (highway) {
            case "footway", "path", "pedestrian", "cycleway", "track" ->
                Blocks.GRAVEL.getDefaultState();
            default -> Blocks.GRAY_CONCRETE.getDefaultState();
        };
        drawLine(world, points, chunk, ground, groundFound, road, width);
    }

    private static void drawLine(
        ServerWorld world,
        List<WorldPoint> points,
        ChunkPos chunk,
        int[][] ground,
        boolean[][] groundFound,
        BlockState state,
        int width
    ) {
        int minX = chunk.getStartX();
        int minZ = chunk.getStartZ();
        for (int i = 1; i < points.size(); i++) {
            WorldPoint start = points.get(i - 1);
            WorldPoint end = points.get(i);
            int steps = Math.max(Math.abs(end.x() - start.x()), Math.abs(end.z() - start.z()));
            if (steps > 100_000) {
                continue;
            }
            int count = Math.max(steps, 1);
            for (int step = 0; step <= count; step++) {
                int x = (int) (start.x() + (long) (end.x() - start.x()) * step / count);
                int z = (int) (start.z() + (long) (end.z() - start.z()) * step / count);
                if (x < minX - width || x >= minX + 16 + width
                    || z < minZ - width || z >= minZ + 16 + width) {
                    continue;
                }
                for (int dx = -width; dx <= width; dx++) {
                    for (int dz = -width; dz <= width; dz++) {
                        int placeX = x + dx;
                        int placeZ = z + dz;
                        if (insideChunk(placeX, placeZ, minX, minZ)) {
                            placeSurface(
                                world,
                                placeX,
                                placeZ,
                                state,
                                ground,
                                groundFound,
                                minX,
                                minZ
                            );
                        }
                    }
                }
            }
        }
    }

    private static void drawBuilding(
        ServerWorld world,
        OsmTile.Feature feature,
        List<WorldPoint> points,
        ChunkPos chunk,
        int[][] ground,
        boolean[][] groundFound
    ) {
        if (points.size() < 4) {
            return;
        }
        int minX = chunk.getStartX();
        int minZ = chunk.getStartZ();
        int lowX = Math.max(minX, points.stream().mapToInt(WorldPoint::x).min().orElse(minX));
        int highX = Math.min(minX + 15, points.stream().mapToInt(WorldPoint::x).max().orElse(minX + 15));
        int lowZ = Math.max(minZ, points.stream().mapToInt(WorldPoint::z).min().orElse(minZ));
        int highZ = Math.min(minZ + 15, points.stream().mapToInt(WorldPoint::z).max().orElse(minZ + 15));
        if (lowX > highX || lowZ > highZ || !isClosed(points)) {
            return;
        }

        int height = buildingHeight(feature.tags());
        BlockState masonry = Blocks.BRICKS.getDefaultState();
        for (int x = lowX; x <= highX; x++) {
            for (int z = lowZ; z <= highZ; z++) {
                if (!insidePolygon(x + 0.5, z + 0.5, points)) {
                    continue;
                }
                int y = surfaceY(world, x, z, ground, groundFound, minX, minZ);
                int safeHeight = Math.min(height, world.getTopY() - y);
                for (int level = 0; level < safeHeight; level++) {
                    world.setBlockState(new BlockPos(x, y + level, z), masonry, 2);
                }
            }
        }
    }

    private static void fillWater(
        ServerWorld world,
        List<WorldPoint> points,
        ChunkPos chunk,
        int[][] ground,
        boolean[][] groundFound
    ) {
        int minX = chunk.getStartX();
        int minZ = chunk.getStartZ();
        int lowX = Math.max(minX, points.stream().mapToInt(WorldPoint::x).min().orElse(minX));
        int highX = Math.min(minX + 15, points.stream().mapToInt(WorldPoint::x).max().orElse(minX + 15));
        int lowZ = Math.max(minZ, points.stream().mapToInt(WorldPoint::z).min().orElse(minZ));
        int highZ = Math.min(minZ + 15, points.stream().mapToInt(WorldPoint::z).max().orElse(minZ + 15));
        if (lowX > highX || lowZ > highZ || !isClosed(points)) {
            return;
        }

        for (int x = lowX; x <= highX; x++) {
            for (int z = lowZ; z <= highZ; z++) {
                if (insidePolygon(x + 0.5, z + 0.5, points)) {
                    int y = surfaceY(world, x, z, ground, groundFound, minX, minZ);
                    world.setBlockState(new BlockPos(x, y, z), Blocks.WATER.getDefaultState(), 2);
                }
            }
        }
    }

    private static int buildingHeight(Map<String, String> tags) {
        double heightMeters = parsePositive(tags.get("height"));
        if (heightMeters > 0.0) {
            return Math.max(3, Math.min(120, (int) Math.ceil(heightMeters * 3.0)));
        }
        double levels = parsePositive(tags.get("building:levels"));
        if (levels <= 0.0) {
            levels = parsePositive(tags.get("building:levels:aboveground"));
        }
        if (levels <= 0.0) {
            levels = 2.0;
        }
        return Math.max(3, Math.min(120, (int) Math.ceil(levels * 3.0)));
    }

    private static double parsePositive(String value) {
        if (value == null) {
            return 0.0;
        }
        String numeric = value.strip().replaceFirst("[^0-9.+-].*$", "");
        try {
            double parsed = Double.parseDouble(numeric);
            return Double.isFinite(parsed) && parsed > 0.0 ? parsed : 0.0;
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static boolean isClosed(List<WorldPoint> points) {
        WorldPoint first = points.get(0);
        WorldPoint last = points.get(points.size() - 1);
        return first.x() == last.x() && first.z() == last.z();
    }

    private static boolean insidePolygon(double x, double z, List<WorldPoint> polygon) {
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            WorldPoint a = polygon.get(i);
            WorldPoint b = polygon.get(j);
            boolean crosses = (a.z() > z) != (b.z() > z)
                && x < (double) (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z()) + a.x();
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static void placeSurface(
        ServerWorld world,
        int x,
        int z,
        BlockState state,
        int[][] ground,
        boolean[][] groundFound,
        int chunkMinX,
        int chunkMinZ
    ) {
        int y = surfaceY(world, x, z, ground, groundFound, chunkMinX, chunkMinZ);
        world.setBlockState(new BlockPos(x, y, z), state, 2);
        BlockPos above = new BlockPos(x, y + 1, z);
        if (!world.getBlockState(above).isAir()) {
            world.setBlockState(above, Blocks.AIR.getDefaultState(), 2);
        }
    }

    private static int surfaceY(
        ServerWorld world,
        int x,
        int z,
        int[][] ground,
        boolean[][] groundFound,
        int chunkMinX,
        int chunkMinZ
    ) {
        int localX = x - chunkMinX;
        int localZ = z - chunkMinZ;
        if (!groundFound[localX][localZ]) {
            ground[localX][localZ] = Math.max(
                world.getBottomY() + 1,
                world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
            );
            groundFound[localX][localZ] = true;
        }
        return ground[localX][localZ];
    }

    private static boolean insideChunk(int x, int z, int minX, int minZ) {
        return x >= minX && x < minX + 16 && z >= minZ && z < minZ + 16;
    }

    @Override
    public void close() {
        fetchExecutor.shutdownNow();
    }

    private record TileKey(int x, int z) {
        static TileKey forChunk(ChunkPos position) {
            int blockX = position.getStartX() + 8;
            int blockZ = position.getStartZ() + 8;
            LondonProjection.GeoPoint center = LondonProjection.unproject(blockX, blockZ);
            if (!LondonProjection.isSupportedLocation(center.latitude(), center.longitude())) {
                return null;
            }
            return new TileKey(
                Math.floorDiv(blockX, TILE_BLOCKS),
                Math.floorDiv(blockZ, TILE_BLOCKS)
            );
        }
    }

    private record ChunkKey(int x, int z) {}

    private record PendingChunk(
        ServerWorld world,
        ChunkPos position,
        TileKey tileKey,
        CompletableFuture<OsmTile> tileFuture
    ) {}

    private record WorldPoint(int x, int z) {}
}
