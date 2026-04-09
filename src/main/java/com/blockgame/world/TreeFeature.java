package com.blockgame.world;

/**
 * Places oak-style trees on {@link BlockType#GRASS} surfaces in
 * {@link BiomeType#PLAINS} biomes above sea level.
 *
 * <p>Each world-column is assigned a stable pseudo-random value derived from
 * its XZ coordinates.  When that value falls below a low probability
 * threshold the column is designated a tree origin and a tree is grown
 * upward from the surface.  Tree structure:
 * <ul>
 *   <li>Trunk – 5 {@link BlockType#WOOD} blocks from the surface up</li>
 *   <li>Lower canopy – 5×5 ring of {@link BlockType#LEAVES} at the top two
 *       trunk levels (corners omitted)</li>
 *   <li>Upper canopy – 3×3 {@link BlockType#LEAVES} at one and two blocks
 *       above the trunk top</li>
 * </ul>
 *
 * <p>To avoid leaves being cut off at chunk borders each chunk scans a
 * {@code CANOPY_RADIUS}-wide border around itself.  Tree origins found in
 * that border belong to neighbouring chunks; their trunks are skipped (the
 * neighbour places them) but any of their leaf blocks that land inside this
 * chunk are placed normally.
 */
public class TreeFeature implements WorldFeature {

    /** Probability (0–1) that any given GRASS column spawns a tree. */
    private static final double TREE_PROBABILITY = 0.04;

    /** Trunk height in blocks. */
    private static final int TRUNK_HEIGHT = 5;

    /** Maximum horizontal reach of the lower canopy (2 blocks from the trunk centre). */
    private static final int CANOPY_RADIUS = 2;

    @Override
    public void apply(Chunk chunk, World world) {
        // Expand the scan by CANOPY_RADIUS in every direction so that trees
        // rooted in neighbouring chunks can still contribute leaves to this chunk.
        for (int lx = -CANOPY_RADIUS; lx < Chunk.SIZE + CANOPY_RADIUS; lx++) {
            for (int lz = -CANOPY_RADIUS; lz < Chunk.SIZE + CANOPY_RADIUS; lz++) {
                int wx = chunk.getWorldX(lx);
                int wz = chunk.getWorldZ(lz);

                int surfaceY = world.getTerrainHeight(wx, wz);

                // Trees only in plains biomes above sea level
                if (surfaceY < DefaultWorldGenerator.SEA_LEVEL) continue;
                BiomeType biome = world.getBiomeProvider().getBiome(wx, wz);
                if (biome != BiomeType.PLAINS) continue;

                // Stable per-column pseudo-random value in [0, 1).
                // Multiply-xor hash the XZ coordinates, then extract 15 bits
                // (mask 0x7FFFL) and normalise by the 15-bit range (0x8000L).
                long h = (long) wx * 3129871L ^ (long) wz * 116129781L;
                h = h * h * 42317861L + h * 11L;
                double r = ((h >> 16) & 0x7FFFL) / (double) 0x8000L;
                if (r >= TREE_PROBABILITY) continue;

                int grassY = surfaceY - 1;

                boolean inChunk = lx >= 0 && lx < Chunk.SIZE && lz >= 0 && lz < Chunk.SIZE;

                // For columns inside this chunk also verify the surface block
                // (guards against sand/snow surfaces introduced by biome transitions).
                // For border columns the block lives in a neighbouring chunk that may
                // not be loaded yet; the biome check above is sufficient there.
                if (inChunk && chunk.getBlock(lx, grassY, lz) != BlockType.GRASS) continue;

                // Ensure enough vertical space for the full tree
                int treeTop = grassY + TRUNK_HEIGHT + 2;
                if (treeTop >= Chunk.HEIGHT) continue;

                // --- Trunk (only for origins inside this chunk) ---
                if (inChunk) {
                    for (int i = 1; i <= TRUNK_HEIGHT; i++) {
                        chunk.setBlock(lx, grassY + i, lz, BlockType.WOOD);
                    }
                }

                int trunkTopY = grassY + TRUNK_HEIGHT;

                // --- Canopy ---
                // Lower canopy: 5×5 minus corners at trunkTop-1 and trunkTop
                for (int dy = -1; dy <= 0; dy++) {
                    int cy = trunkTopY + dy;
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue; // skip corners
                            placeLeaf(chunk, lx + dx, cy, lz + dz);
                        }
                    }
                }
                // Upper canopy: 3×3 at trunkTop+1 and trunkTop+2
                for (int dy = 1; dy <= 2; dy++) {
                    int cy = trunkTopY + dy;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            placeLeaf(chunk, lx + dx, cy, lz + dz);
                        }
                    }
                }
            }
        }
    }

    /**
     * Places a leaf block at the given chunk-local coordinates if the position
     * is within bounds and not already occupied by a wood block.
     */
    private static void placeLeaf(Chunk chunk, int lx, int y, int lz) {
        if (lx < 0 || lx >= Chunk.SIZE || lz < 0 || lz >= Chunk.SIZE) return;
        if (y < 0 || y >= Chunk.HEIGHT) return;
        if (chunk.getBlock(lx, y, lz) == BlockType.WOOD) return;
        chunk.setBlock(lx, y, lz, BlockType.LEAVES);
    }
}
