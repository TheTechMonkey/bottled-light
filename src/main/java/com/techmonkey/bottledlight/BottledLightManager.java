package com.techmonkey.bottledlight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

final class BottledLightManager {
    static final int SCAN_RADIUS = 48;
    static final int MAX_LIGHTS = 24;

    private static final int RESCAN_INTERVAL_TICKS = 6;
    private static final int BACKGROUND_REFRESH_TICKS = 40;
    private static final int CHUNK_SCAN_BUDGET = 3;
    private static final double MOVEMENT_REBUILD_DISTANCE_SQUARED = 16.0;

    private static final Map<Long, ChunkLightCache> CHUNK_CACHE = new HashMap<>();
    private static ClientLevel scannedLevel;
    private static long nextMaintenanceTick = Long.MIN_VALUE;
    private static Vec3 lastBuildPosition = new Vec3(Double.NaN, Double.NaN, Double.NaN);
    private static long lastCenterChunk = ChunkPos.INVALID_CHUNK_POS;
    private static List<BottledLightSource> activeLights = List.of();

    private BottledLightManager() {
    }

    static List<BottledLightSource> updateAndGet(ClientLevel level, Vec3 cameraPosition) {
        if (scannedLevel != level) {
            clear();
            scannedLevel = level;
        }

        long gameTime = level.getGameTime();
        int centerChunkX = (int) Math.floor(cameraPosition.x) >> 4;
        int centerChunkZ = (int) Math.floor(cameraPosition.z) >> 4;
        long centerChunk = ChunkPos.pack(centerChunkX, centerChunkZ);
        boolean changedCenterChunk = centerChunk != lastCenterChunk;
        boolean movedEnough = !Double.isFinite(lastBuildPosition.x)
                || lastBuildPosition.distanceToSqr(cameraPosition) >= MOVEMENT_REBUILD_DISTANCE_SQUARED;

        if (changedCenterChunk || gameTime >= nextMaintenanceTick) {
            boolean cacheChanged = maintainCache(level, centerChunkX, centerChunkZ, gameTime);
            if (cacheChanged || movedEnough || changedCenterChunk) {
                rebuildActiveLights(cameraPosition);
                lastBuildPosition = cameraPosition;
            }
            lastCenterChunk = centerChunk;
            nextMaintenanceTick = gameTime + RESCAN_INTERVAL_TICKS;
        } else if (movedEnough) {
            rebuildActiveLights(cameraPosition);
            lastBuildPosition = cameraPosition;
        }

        return activeLights;
    }

    static void clear() {
        CHUNK_CACHE.clear();
        scannedLevel = null;
        nextMaintenanceTick = Long.MIN_VALUE;
        lastBuildPosition = new Vec3(Double.NaN, Double.NaN, Double.NaN);
        lastCenterChunk = ChunkPos.INVALID_CHUNK_POS;
        activeLights = List.of();
    }

    private static boolean maintainCache(
            ClientLevel level, int centerChunkX, int centerChunkZ, long gameTime) {
        int chunkRadius = (SCAN_RADIUS + 15) >> 4;
        Set<Long> desiredChunks = new HashSet<>((chunkRadius * 2 + 1) * (chunkRadius * 2 + 1));

        for (int z = centerChunkZ - chunkRadius; z <= centerChunkZ + chunkRadius; z++) {
            for (int x = centerChunkX - chunkRadius; x <= centerChunkX + chunkRadius; x++) {
                if (level.hasChunk(x, z)) {
                    long key = ChunkPos.pack(x, z);
                    desiredChunks.add(key);
                    CHUNK_CACHE.computeIfAbsent(key, ignored -> new ChunkLightCache());
                }
            }
        }

        boolean changed = CHUNK_CACHE.keySet().removeIf(key -> !desiredChunks.contains(key));
        ArrayList<ScanCandidate> candidates = new ArrayList<>(desiredChunks.size());
        long currentChunk = ChunkPos.pack(centerChunkX, centerChunkZ);

        for (long key : desiredChunks) {
            ChunkLightCache cache = CHUNK_CACHE.get(key);
            int dx = ChunkPos.getX(key) - centerChunkX;
            int dz = ChunkPos.getZ(key) - centerChunkZ;
            int distanceSquared = dx * dx + dz * dz;
            long age = cache.lastScanTick == Long.MIN_VALUE
                    ? Long.MAX_VALUE : gameTime - cache.lastScanTick;
            boolean current = key == currentChunk;
            if (cache.lastScanTick == Long.MIN_VALUE
                    || current && age >= RESCAN_INTERVAL_TICKS
                    || age >= BACKGROUND_REFRESH_TICKS) {
                candidates.add(new ScanCandidate(key, cache.lastScanTick, distanceSquared, current));
            }
        }

        candidates.sort(Comparator.comparing(ScanCandidate::currentChunk).reversed()
                .thenComparing(candidate -> candidate.lastScanTick() != Long.MIN_VALUE)
                .thenComparingInt(ScanCandidate::chunkDistanceSquared)
                .thenComparingLong(ScanCandidate::lastScanTick));

        int scans = Math.min(CHUNK_SCAN_BUDGET, candidates.size());
        for (int index = 0; index < scans; index++) {
            ScanCandidate candidate = candidates.get(index);
            int chunkX = ChunkPos.getX(candidate.key());
            int chunkZ = ChunkPos.getZ(candidate.key());
            if (level.hasChunk(chunkX, chunkZ)) {
                scanChunk(level.getChunk(chunkX, chunkZ), CHUNK_CACHE.get(candidate.key()), gameTime);
                changed = true;
            }
        }
        return changed;
    }

    private static void scanChunk(LevelChunk chunk, ChunkLightCache cache, long gameTime) {
        ArrayList<BottledLightSource> sources = new ArrayList<>();
        chunk.findBlocks(
                state -> state.is(BottledLight.BOTTLED_LIGHT_BLOCK),
                (pos, state) -> {
                    int level = state.getValue(BottledLightBlock.LEVEL);
                    DyeColor color = state.getValue(BottledLightBlock.COLOR);
                    float radius = 4.0F + level * 0.6F;
                    float intensity = color == DyeColor.BLACK ? 0.78F : 1.0F;
                    sources.add(new BottledLightSource(
                            pos.immutable(), BottledLightColors.rgb(color), radius, intensity));
                });
        cache.sources = List.copyOf(sources);
        cache.lastScanTick = gameTime;
    }

    private static void rebuildActiveLights(Vec3 cameraPosition) {
        double radiusSquared = (double) SCAN_RADIUS * SCAN_RADIUS;
        PriorityQueue<DistanceCandidate> nearest = new PriorityQueue<>(
                Comparator.comparingDouble(DistanceCandidate::priority).reversed());

        for (ChunkLightCache cache : CHUNK_CACHE.values()) {
            for (BottledLightSource source : cache.sources) {
                double distanceSquared = source.pos().distToCenterSqr(
                        cameraPosition.x, cameraPosition.y, cameraPosition.z);
                if (distanceSquared > radiusSquared) continue;

                double influence = Math.max(0.25, source.radius() * source.intensity());
                DistanceCandidate candidate = new DistanceCandidate(
                        source, distanceSquared, distanceSquared / (influence * influence));
                if (nearest.size() < MAX_LIGHTS) {
                    nearest.add(candidate);
                } else if (candidate.priority() < nearest.peek().priority()) {
                    nearest.poll();
                    nearest.add(candidate);
                }
            }
        }

        ArrayList<DistanceCandidate> sorted = new ArrayList<>(nearest);
        sorted.sort(Comparator.comparingDouble(DistanceCandidate::distanceSquared));
        activeLights = sorted.stream().map(DistanceCandidate::source).toList();
    }

    private static final class ChunkLightCache {
        private long lastScanTick = Long.MIN_VALUE;
        private List<BottledLightSource> sources = List.of();
    }

    private record ScanCandidate(
            long key, long lastScanTick, int chunkDistanceSquared, boolean currentChunk) {
    }

    private record DistanceCandidate(
            BottledLightSource source, double distanceSquared, double priority) {
    }
}
