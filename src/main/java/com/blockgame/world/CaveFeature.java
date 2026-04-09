package com.blockgame.world;

/**
 * World feature that carves cave tunnels into the terrain.
 *
 * <p>A block is hollowed out wherever two independent 3-D noise fields are
 * simultaneously near zero, producing worm-like tunnels.  Carving always
 * starts from y=1 so the {@link BlockType#BEDROCK} layer at y=0 is
 * preserved.  When a tunnel reaches the surface the noise condition naturally
 * opens a cave entrance.
 *
 * <p>To keep entrances from looking unnatural, the carving threshold is
 * scaled down quadratically within the top {@value #SURFACE_TAPER_DEPTH}
 * blocks below the surface, making surface openings rare rather than
 * pervasive.  For underwater columns (surface below sea level) the taper
 * zone is fully suppressed so that cave tunnels never break through the
 * ocean or lake floor.
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
    private static final int SURFACE_TAPER_DEPTH = 12;

    /** Noise sampling scale.  Higher values produce thinner tunnels. */
    private static final double NOISE_SCALE = 0.05;

    /**
     * Squared-distance threshold in noise space.  A block is carved when
     * {@code n1² + n2² < CARVE_THRESHOLD}.  Reducing this value lowers
     * both the frequency and the cross-sectional size of cave tunnels.
     */
    private static final double CARVE_THRESHOLD = 0.02;

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
                boolean underwater = surfaceY < DefaultWorldGenerator.SEA_LEVEL;

                // Start from y=1 so bedrock at y=0 is always preserved
                for (int y = 1; y < surfaceY; y++) {
                    if (chunk.getBlock(lx, y, lz) == BlockType.AIR) continue;

                    double n1 = noise.octaveNoise3(
                            wx * NOISE_SCALE, y * NOISE_SCALE, wz * NOISE_SCALE,
                            2, 0.5, 2.0);
                    double n2 = noise.octaveNoise3(
                            wx * NOISE_SCALE + 100, y * NOISE_SCALE + 100, wz * NOISE_SCALE + 100,
                            2, 0.5, 2.0);

                    // Near the surface, taper down the threshold so cave openings
                    // are rare.  For underwater columns suppress the taper zone
                    // entirely so that caves never break through the ocean floor.
                    int depth = surfaceY - y;
                    double depthFactor;
                    if (underwater && depth <= SURFACE_TAPER_DEPTH) {
                        // No cave entrances into the ocean floor
                        depthFactor = 0.0;
                    } else {
                        double depthRatio = Math.min(1.0, (double) depth / SURFACE_TAPER_DEPTH);
                        depthFactor = depthRatio * depthRatio;
                    }

                    // Carve when both noise values are near zero (worm-tunnel condition)
                    if (n1 * n1 + n2 * n2 < CARVE_THRESHOLD * depthFactor) {
                        chunk.setBlock(lx, y, lz, BlockType.AIR);
                    }
                }
            }
        }
    }
}
