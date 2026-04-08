package com.blockgame.world;

/**
 * Default terrain generator.
 *
 * <p>For each column in the chunk it:
 * <ol>
 *   <li>Queries {@link World#getTerrainHeight} for the surface Y.</li>
 *   <li>Fills the base with {@link BlockType#STONE}.</li>
 *   <li>Adds a {@link BlockType#DIRT} sub-surface layer.</li>
 *   <li>Places the biome-appropriate surface block via the world's
 *       {@link BiomeProvider}.</li>
 * </ol>
 *
 * <p>Cave carving and other decoration is deferred to registered
 * {@link WorldFeature} passes.
 */
public class DefaultWorldGenerator implements WorldGenerator {

    @Override
    public void generate(Chunk chunk, World world) {
        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int wx = chunk.getWorldX(lx);
                int wz = chunk.getWorldZ(lz);

                int surfaceY = world.getTerrainHeight(wx, wz);

                // Bedrock / stone base
                for (int y = 0; y < surfaceY - 4; y++) {
                    chunk.setBlock(lx, y, lz, BlockType.STONE);
                }
                // Dirt layer
                for (int y = Math.max(0, surfaceY - 4); y < surfaceY - 1; y++) {
                    chunk.setBlock(lx, y, lz, BlockType.DIRT);
                }
                // Surface block chosen by biome
                if (surfaceY > 0) {
                    BiomeType biome = world.getBiomeProvider().getBiome(wx, wz);
                    BlockType surface = switch (biome) {
                        case SNOW   -> BlockType.SNOW;
                        case DESERT -> BlockType.SAND;
                        default     -> BlockType.GRASS;
                    };
                    chunk.setBlock(lx, surfaceY - 1, lz, surface);
                }
            }
        }
    }
}
