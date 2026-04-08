package com.blockgame.world;

/**
 * World feature that carves cave tunnels into the terrain.
 *
 * <p>A block is hollowed out wherever two independent 3-D noise fields are
 * simultaneously near zero, producing worm-like tunnels.  Carving is skipped
 * below y = 4 to leave a solid bedrock layer.  When a tunnel reaches the
 * surface the noise condition naturally opens a cave entrance.
 *
 * <p>To keep entrances from looking unnatural, the carving threshold is
 * scaled down quadratically within the top {@value #SURFACE_TAPER_DEPTH}
 * blocks below the surface, making surface openings rare rather than
 * pervasive.
 *
 * <p>This feature is registered by default in every {@link World} instance
 * and was previously implemented as {@code World.carveCaves()}.
 */
public class CaveFeature implements WorldFeature {

    /**
     * Number of blocks below the surface over which the carving threshold
     * is tapered down to zero.  Caves can still open at the surface, but
     * only in the small fraction of spots where the noise values are very
     * close to zero even at full scale.
     */
    private static final int SURFACE_TAPER_DEPTH = 8;

    private final PerlinNoise noise;

    /**
     * @param noise the noise generator to use for cave shape; must be the
     *              same instance (or an identically-seeded one) used by the
     *              terrain height function to avoid artifacts at the surface
     *              threshold.
     */
    public CaveFeature(PerlinNoise noise) {
        this.noise = noise;
    }

    @Override
    public void apply(Chunk chunk, World world) {
        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int wx = chunk.getWorldX(lx);
                int wz = chunk.getWorldZ(lz);
                int surfaceY = world.getTerrainHeight(wx, wz);

                for (int y = 4; y < surfaceY; y++) {
                    if (chunk.getBlock(lx, y, lz) == BlockType.AIR) continue;

                    double n1 = noise.octaveNoise3(
                            wx * 0.04, y * 0.04, wz * 0.04,
                            2, 0.5, 2.0);
                    double n2 = noise.octaveNoise3(
                            wx * 0.04 + 100, y * 0.04 + 100, wz * 0.04 + 100,
                            2, 0.5, 2.0);

                    // Near the surface, scale the threshold down quadratically so
                    // cave entrances are rare rather than appearing everywhere.
                    int depth = surfaceY - y;
                    double depthRatio = Math.min(1.0, (double) depth / SURFACE_TAPER_DEPTH);
                    double depthFactor = depthRatio * depthRatio;

                    // Carve when both noise values are near zero (worm-tunnel condition)
                    if (n1 * n1 + n2 * n2 < 0.04 * depthFactor) {
                        chunk.setBlock(lx, y, lz, BlockType.AIR);
                    }
                }
            }
        }
    }
}
