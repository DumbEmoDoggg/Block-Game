package com.blockgame.world;

/**
 * A post-generation decoration pass applied to a newly-generated {@link Chunk}.
 *
 * <p>Features run in registration order after the base terrain generator has
 * filled the chunk.  Each feature is free to read from and write to any block
 * in the chunk and, with care, adjacent chunks via the {@link World} reference.
 *
 * <p>Examples: cave carving, ore vein placement, tree generation, structure
 * placement.
 */
public interface WorldFeature {

    /**
     * Applies this feature to the given chunk.
     *
     * @param chunk the chunk being decorated (base terrain is already present)
     * @param world the owning world
     */
    void apply(Chunk chunk, World world);
}
