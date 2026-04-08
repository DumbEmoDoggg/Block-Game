package com.blockgame.world;

/**
 * The set of biomes that can appear in the world.
 *
 * <p>Each biome controls which block appears on the terrain surface and can
 * be used by {@link WorldFeature} implementations to apply biome-specific
 * decoration (tree types, ore distributions, structure placement, etc.).
 */
public enum BiomeType {
    /** Mid-elevation grassland – default biome. */
    PLAINS,
    /** Low coastal / arid terrain with sand surface. */
    DESERT,
    /** High-altitude terrain with snow surface. */
    SNOW
}
