package com.blockgame.world;

/**
 * A single item entity lying in the world that the player can walk over to
 * pick up.
 *
 * <p>Dropped items have simple physics: they fall under gravity and come to
 * rest on the first solid block below them.  There is a short {@link
 * #PICKUP_DELAY} before the player can collect the item (prevents
 * immediately re-picking up a thrown item).
 */
public class DroppedItem {

    /** Seconds after spawning before this item can be picked up. */
    public static final float PICKUP_DELAY  = 0.5f;
    /** Distance (blocks) within which the player automatically picks up the item. */
    public static final float PICKUP_RADIUS = 1.5f;

    private static final float GRAVITY = -20f;

    /** World-space position of the item's centre. */
    public float x, y, z;

    /** Velocity components (blocks/second). */
    public float velX, velY, velZ;

    /** The type of block this item represents. */
    public final BlockType type;

    /** {@code true} once the item has been collected and should be removed. */
    public boolean pickedUp = false;

    /** Seconds elapsed since the item was spawned. */
    private float age = 0f;

    public DroppedItem(float x, float y, float z, BlockType type) {
        this.x    = x;
        this.y    = y;
        this.z    = z;
        this.type = type;
    }

    // -------------------------------------------------------------------------
    // Physics update
    // -------------------------------------------------------------------------

    /**
     * Advances the item's physics by {@code dt} seconds.
     *
     * <p>Applies gravity and basic ground collision: if the block directly
     * below the item is solid the item stops there.
     */
    public void update(float dt, World world) {
        age += dt;

        velY += GRAVITY * dt;

        x += velX * dt;
        y += velY * dt;
        z += velZ * dt;

        // Ground collision: stop on the first solid block below centre
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y - 0.05f);
        int bz = (int) Math.floor(z);

        if (velY <= 0 && world.getBlock(bx, by, bz).solid) {
            y    = by + 1.15f; // rest slightly above the surface
            velY = 0f;
            velX = 0f;
            velZ = 0f;
        }

        // Clamp to avoid falling through the world bottom
        if (y < 0) {
            y    = 0;
            velY = 0f;
        }
    }

    /** Returns {@code true} once the pickup delay has elapsed. */
    public boolean canPickup() {
        return age > PICKUP_DELAY;
    }

    /** Seconds since the item was spawned, used for bobbing animation. */
    public float getAge() {
        return age;
    }
}
