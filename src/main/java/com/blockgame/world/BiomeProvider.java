package com.blockgame.world;

/**
 * Determines the {@link BiomeType} for any world-space (x, z) position.
 *
 * <p>Implement this interface to supply custom biome distributions (noise
 * maps, climate models, hand-crafted regions, …) without modifying world
 * generation logic.  The default implementation is
 * {@link DefaultBiomeProvider}.
 */
public interface BiomeProvider {

    /**
     * Returns the biome at the given world-space column.
     *
     * @param wx world X coordinate
     * @param wz world Z coordinate
     * @return the biome at that position; never {@code null}
     */
    BiomeType getBiome(int wx, int wz);
}
