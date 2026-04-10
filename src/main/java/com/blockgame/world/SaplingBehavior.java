package com.blockgame.world;

import java.util.Random;

/**
 * Growth behaviour for {@link BlockType#OAK_SAPLING}.
 *
 * <p>When a sapling is placed it schedules itself for ticking.  Each tick
 * there is a small random chance that the sapling has matured enough to grow
 * into a full tree.  If it has not yet grown the tick is rescheduled for the
 * following frame.
 *
 * <p>The tree geometry mirrors the one produced by {@link TreeFeature}:
 * 5-block trunk, 5×5 lower canopy (corners omitted), 3×3 upper canopy.
 */
public class SaplingBehavior implements BlockBehavior {

    /** Per-tick growth probability (≈0.05 % → average ~33 s at 60 fps). */
    private static final float GROW_CHANCE = 0.0005f;

    private static final int TRUNK_HEIGHT  = 5;
    private static final int CANOPY_RADIUS = 2;

    private final Random random = new Random();

    @Override
    public void onPlace(World world, int wx, int wy, int wz) {
        world.scheduleBlockTick(wx, wy, wz);
    }

    @Override
    public void onTick(World world, int wx, int wy, int wz) {
        if (world.getBlock(wx, wy, wz) != BlockType.OAK_SAPLING) return;

        if (random.nextFloat() < GROW_CHANCE) {
            growTree(world, wx, wy, wz);
        } else {
            // Reschedule for next frame
            world.scheduleBlockTick(wx, wy, wz);
        }
    }

    /**
     * Replaces the sapling with an oak tree (trunk + canopy) at the same
     * column.  The sapling occupies the first trunk position (wy), so the
     * trunk is placed from {@code wy} upward for {@link #TRUNK_HEIGHT} blocks.
     */
    private void growTree(World world, int wx, int wy, int wz) {
        int trunkTopY = wy + TRUNK_HEIGHT - 1;

        // Check there is enough vertical space
        if (trunkTopY + 2 >= Chunk.HEIGHT) return;

        // Replace sapling with trunk base, then place rest of trunk above
        for (int i = 0; i < TRUNK_HEIGHT; i++) {
            world.setBlock(wx, wy + i, wz, BlockType.WOOD);
        }

        // Lower canopy: 5×5 minus corners at trunkTop-1 and trunkTop
        for (int dy = -1; dy <= 0; dy++) {
            int cy = trunkTopY + dy;
            for (int dx = -CANOPY_RADIUS; dx <= CANOPY_RADIUS; dx++) {
                for (int dz = -CANOPY_RADIUS; dz <= CANOPY_RADIUS; dz++) {
                    if (Math.abs(dx) == CANOPY_RADIUS && Math.abs(dz) == CANOPY_RADIUS) continue;
                    placeLeaf(world, wx + dx, cy, wz + dz);
                }
            }
        }

        // Upper canopy: 3×3 at trunkTop+1 and trunkTop+2
        for (int dy = 1; dy <= 2; dy++) {
            int cy = trunkTopY + dy;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    placeLeaf(world, wx + dx, cy, wz + dz);
                }
            }
        }
    }

    private static void placeLeaf(World world, int wx, int wy, int wz) {
        if (wy < 0 || wy >= Chunk.HEIGHT) return;
        BlockType existing = world.getBlock(wx, wy, wz);
        if (existing != BlockType.AIR) return;
        world.setBlock(wx, wy, wz, BlockType.LEAVES);
    }
}
