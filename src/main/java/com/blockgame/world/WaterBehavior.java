package com.blockgame.world;

/**
 * Water flow behaviour: water falls down and spreads horizontally into air.
 *
 * <p>When a water block is placed ({@link #onPlace}) a tick is scheduled.
 * On each tick ({@link #onTick}) the water first tries to flow straight down
 * into an air block directly below; if that is not possible it spreads
 * horizontally into every adjacent air block at the same Y level.  Each
 * newly-placed water block schedules its own tick so the flow propagates
 * outward over subsequent frames.
 *
 * <p>World-generator water (placed via {@code Chunk.setBlock}) does not
 * trigger {@link #onPlace} and therefore starts static; it begins to flow
 * only when a neighbouring block is changed via {@link World#setBlock},
 * which automatically schedules a tick for every adjacent block that has a
 * behaviour (including water).
 */
public class WaterBehavior implements BlockBehavior {

    private static final int[][] HORIZONTAL = {
        { 1, 0,  0}, {-1, 0,  0},
        { 0, 0,  1}, { 0, 0, -1}
    };

    @Override
    public void onPlace(World world, int wx, int wy, int wz) {
        world.scheduleBlockTick(wx, wy, wz);
    }

    @Override
    public void onTick(World world, int wx, int wy, int wz) {
        if (world.getBlock(wx, wy, wz) != BlockType.WATER) return;

        // Prefer flowing straight down
        if (wy > 0 && world.getBlock(wx, wy - 1, wz) == BlockType.AIR) {
            world.setBlock(wx, wy - 1, wz, BlockType.WATER);
            return;
        }

        // If blocked below, spread horizontally into adjacent air blocks
        for (int[] dir : HORIZONTAL) {
            int nx = wx + dir[0];
            int nz = wz + dir[2];
            if (world.getBlock(nx, wy, nz) == BlockType.AIR) {
                world.setBlock(nx, wy, nz, BlockType.WATER);
            }
        }
    }
}
