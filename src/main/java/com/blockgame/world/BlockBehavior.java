package com.blockgame.world;

/**
 * Optional per-block-type lifecycle callbacks for interactive or dynamic blocks.
 *
 * <p>Assign a {@code BlockBehavior} to a {@link BlockType} to receive events
 * when that block type is placed, broken, or ticked by the world.  Block types
 * with no special behaviour leave this field {@code null}.
 *
 * <p>Example uses: sand/gravel gravity, water/lava flow, crop growth,
 * furnace ticking.
 *
 * <p>All methods have no-op default implementations so implementors only
 * override the hooks they care about.
 */
public interface BlockBehavior {

    /**
     * Called immediately after a block of this type is placed in the world.
     *
     * @param world the world the block was placed in
     * @param wx    world X coordinate
     * @param wy    world Y coordinate
     * @param wz    world Z coordinate
     */
    default void onPlace(World world, int wx, int wy, int wz) {}

    /**
     * Called immediately after a block of this type is broken (removed).
     *
     * @param world the world the block was removed from
     * @param wx    world X coordinate of the removed block
     * @param wy    world Y coordinate of the removed block
     * @param wz    world Z coordinate of the removed block
     */
    default void onBreak(World world, int wx, int wy, int wz) {}

    /**
     * Called each time the world ticks this block.  Only blocks whose type
     * has a non-null behavior registered for ticking will receive this
     * callback.
     *
     * @param world the world performing the tick
     * @param wx    world X coordinate
     * @param wy    world Y coordinate
     * @param wz    world Z coordinate
     */
    default void onTick(World world, int wx, int wy, int wz) {}
}
