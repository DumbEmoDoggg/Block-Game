package com.blockgame.world;

/**
 * Responsible for filling a freshly-created {@link Chunk} with base terrain.
 *
 * <p>Implementations lay down the stone/dirt/surface-block columns for the
 * entire chunk.  Post-generation decoration (caves, ores, trees, structures,
 * …) is handled by {@link WorldFeature} passes that run afterwards.
 *
 * <p>The default implementation is {@link DefaultWorldGenerator}.
 */
public interface WorldGenerator {

    /**
     * Fills {@code chunk} with initial block data.
     *
     * @param chunk the empty chunk to generate into
     * @param world the owning world (used to query terrain height, biome
     *              provider, adjacent blocks, etc.)
     */
    void generate(Chunk chunk, World world);
}
