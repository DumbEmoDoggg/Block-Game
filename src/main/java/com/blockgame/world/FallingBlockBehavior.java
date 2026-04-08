package com.blockgame.world;

/**
 * Gravity behavior for falling blocks such as sand and gravel.
 *
 * <p>When a falling block is placed ({@link #onPlace}) a tick is scheduled.
 * On each tick ({@link #onTick}) the block checks whether the block directly
 * below it is non-solid (air, water, etc.).  If so, it displaces downward:
 * the current position becomes {@link BlockType#AIR} and the block is placed
 * one position lower, where it immediately schedules another tick so it
 * continues falling until it lands on a solid surface.
 *
 * <p>This class is stateless; the same instance can be shared by multiple
 * block types.  The identity of the falling block is read from the world at
 * tick time and re-placed one position down, preserving the exact type.
 */
public class FallingBlockBehavior implements BlockBehavior {

    @Override
    public void onPlace(World world, int wx, int wy, int wz) {
        world.scheduleBlockTick(wx, wy, wz);
    }

    @Override
    public void onTick(World world, int wx, int wy, int wz) {
        BlockType current = world.getBlock(wx, wy, wz);
        if (current.behavior != this) return;

        // Fall if the block directly below is non-solid (air, water, etc.)
        if (wy > 0 && !world.getBlock(wx, wy - 1, wz).solid) {
            world.setBlock(wx, wy,     wz, BlockType.AIR);
            world.setBlock(wx, wy - 1, wz, current);
        }
    }
}
