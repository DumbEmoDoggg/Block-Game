package com.blockgame.world;

/**
 * Determines biomes from terrain height: {@link BiomeType#SNOW} for high
 * peaks, {@link BiomeType#DESERT} for low coastal areas, and
 * {@link BiomeType#PLAINS} for everything in between.
 *
 * <p>This mirrors the surface-block selection that was previously hard-coded
 * in {@code World.generateChunk()}, now factored into a replaceable
 * {@link BiomeProvider}.
 */
public class DefaultBiomeProvider implements BiomeProvider {

    private final World world;

    /**
     * Creates a biome provider that consults {@code world} for terrain height.
     *
     * @param world the world whose {@link World#getTerrainHeight} is used to
     *              derive the biome for each column
     */
    public DefaultBiomeProvider(World world) {
        this.world = world;
    }

    @Override
    public BiomeType getBiome(int wx, int wz) {
        int surfaceY = world.getTerrainHeight(wx, wz);
        if (surfaceY > 80) return BiomeType.SNOW;
        if (surfaceY < 50) return BiomeType.DESERT;
        return BiomeType.PLAINS;
    }
}
