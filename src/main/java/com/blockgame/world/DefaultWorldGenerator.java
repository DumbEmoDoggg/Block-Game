package com.blockgame.world;

/**
 * Default terrain generator.
 *
 * <p>For each column in the chunk it:
 * <ol>
 *   <li>Queries {@link World#getTerrainHeight} for the surface Y.</li>
 *   <li>Places {@link BlockType#BEDROCK} at y=0 (the absolute world floor).</li>
 *   <li>Fills the base with {@link BlockType#STONE}.</li>
 *   <li>For columns above sea level: adds a {@link BlockType#DIRT} sub-surface
 *       layer and the biome-appropriate surface block.</li>
 *   <li>For columns at or below sea level (underwater): uses
 *       {@link BlockType#SAND} or {@link BlockType#GRAVEL} for the floor and
 *       fills open air with {@link BlockType#WATER}.</li>
 * </ol>
 *
 * <p>Cave carving and other decoration is deferred to registered
 * {@link WorldFeature} passes.
 */
public class DefaultWorldGenerator implements WorldGenerator {

    /** Y level below which open air is filled with water (matches Minecraft's sea level). */
    public static final int SEA_LEVEL = 63;

    @Override
    public void generate(Chunk chunk, World world) {
        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int wx = chunk.getWorldX(lx);
                int wz = chunk.getWorldZ(lz);

                int surfaceY = world.getTerrainHeight(wx, wz);
                boolean underwater = surfaceY < SEA_LEVEL;

                // Bedrock at y=0 — the absolute bottom of the world
                chunk.setBlock(lx, 0, lz, BlockType.BEDROCK);

                if (!underwater) {
                    // --- Above-water column ---
                    // Stone base
                    for (int y = 1; y < surfaceY - 4; y++) {
                        chunk.setBlock(lx, y, lz, BlockType.STONE);
                    }
                    // Dirt sub-surface layer.  Replace with SAND below sea level so that
                    // shallow-water shores don't expose dirt blocks visible through water.
                    for (int y = Math.max(1, surfaceY - 4); y < surfaceY - 1; y++) {
                        chunk.setBlock(lx, y, lz, y < SEA_LEVEL ? BlockType.SAND : BlockType.DIRT);
                    }
                    // Biome-appropriate surface block
                    if (surfaceY > 0) {
                        BiomeType biome = world.getBiomeProvider().getBiome(wx, wz);
                        BlockType surface = switch (biome) {
                            case SNOW   -> BlockType.SNOW;
                            case DESERT -> BlockType.SAND;
                            default     -> BlockType.GRASS;
                        };
                        chunk.setBlock(lx, surfaceY - 1, lz, surface);
                    }
                } else {
                    // --- Underwater column ---
                    // Stone base up to near the floor
                    for (int y = 1; y < surfaceY - 2; y++) {
                        chunk.setBlock(lx, y, lz, BlockType.STONE);
                    }
                    // Mix SAND and GRAVEL on the floor using a scatter hash so there
                    // are no visible diagonal stripe patterns (roughly 25% gravel).
                    int h = (wx * 1013904223) ^ (wz * 1664525);
                    h ^= (h >>> 14);
                    BlockType floorBlock = (h & 3) == 0 ? BlockType.GRAVEL : BlockType.SAND;
                    for (int y = Math.max(1, surfaceY - 2); y < surfaceY; y++) {
                        chunk.setBlock(lx, y, lz, floorBlock);
                    }
                    // Fill air above floor up to sea level with water
                    for (int y = surfaceY; y < SEA_LEVEL; y++) {
                        chunk.setBlock(lx, y, lz, BlockType.WATER);
                    }
                }
            }
        }
    }
}
