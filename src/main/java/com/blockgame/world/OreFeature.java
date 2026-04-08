package com.blockgame.world;

/**
 * Scatters underground ores and gravel patches into generated chunks using
 * 3-D Perlin noise.
 *
 * <p>A block of stone is replaced with an ore (or gravel) wherever the
 * absolute value of a dedicated noise sample falls below a per-resource
 * threshold.  Because Perlin noise produces smooth, connected blobs near
 * zero, this naturally creates realistic vein and patch shapes.  Each
 * resource uses a different noise offset to prevent their distributions from
 * overlapping.
 *
 * <p>Ore depth ranges (y=0 is bedrock):
 * <ul>
 *   <li><b>Coal</b>    – y 1 – 55 (most common)</li>
 *   <li><b>Iron</b>    – y 1 – 40</li>
 *   <li><b>Gold</b>    – y 1 – 25 (rarest)</li>
 *   <li><b>Gravel</b>  – y 1 – 60 (underground patches)</li>
 * </ul>
 */
public class OreFeature implements WorldFeature {

    // Noise frequency: higher = smaller, more frequent blobs
    private static final double FREQ = 0.12;

    // Carving threshold for each resource – the smaller the value,
    // the rarer the resource (probability ≈ 2 * threshold for Perlin noise).
    private static final double THRESH_COAL   = 0.010;
    private static final double THRESH_IRON   = 0.007;
    private static final double THRESH_GOLD   = 0.004;
    private static final double THRESH_GRAVEL = 0.018;

    // Noise-space offsets so each resource has an independent distribution
    private static final double OFF_COAL   =    0;
    private static final double OFF_IRON   =  500;
    private static final double OFF_GOLD   = 1000;
    private static final double OFF_GRAVEL = 1500;

    private final PerlinNoise noise;

    /**
     * @param noise the world's shared noise generator – using the same
     *              instance keeps ore placement consistent with the seed
     */
    public OreFeature(PerlinNoise noise) {
        this.noise = noise;
    }

    @Override
    public void apply(Chunk chunk, World world) {
        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int wx = chunk.getWorldX(lx);
                int wz = chunk.getWorldZ(lz);

                for (int y = 1; y < Chunk.HEIGHT; y++) {
                    // Only replace stone blocks
                    if (chunk.getBlock(lx, y, lz) != BlockType.STONE) continue;

                    double nx = wx * FREQ;
                    double ny = y  * FREQ;
                    double nz = wz * FREQ;

                    // Coal — y 1–55
                    if (y <= 55) {
                        double n = noise.noise3(nx + OFF_COAL, ny, nz + OFF_COAL);
                        if (Math.abs(n) < THRESH_COAL) {
                            chunk.setBlock(lx, y, lz, BlockType.COAL_ORE);
                            continue;
                        }
                    }

                    // Iron — y 1–40
                    if (y <= 40) {
                        double n = noise.noise3(nx + OFF_IRON, ny, nz + OFF_IRON);
                        if (Math.abs(n) < THRESH_IRON) {
                            chunk.setBlock(lx, y, lz, BlockType.IRON_ORE);
                            continue;
                        }
                    }

                    // Gold — y 1–25
                    if (y <= 25) {
                        double n = noise.noise3(nx + OFF_GOLD, ny, nz + OFF_GOLD);
                        if (Math.abs(n) < THRESH_GOLD) {
                            chunk.setBlock(lx, y, lz, BlockType.GOLD_ORE);
                            continue;
                        }
                    }

                    // Gravel patches — y 1–60
                    if (y <= 60) {
                        double n = noise.noise3(nx + OFF_GRAVEL, ny, nz + OFF_GRAVEL);
                        if (Math.abs(n) < THRESH_GRAVEL) {
                            chunk.setBlock(lx, y, lz, BlockType.GRAVEL);
                        }
                    }
                }
            }
        }
    }
}
