package com.blockgame.world;

import com.blockgame.player.Inventory;
import com.blockgame.player.Player;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Manages all {@link DroppedItem} entities in the world.
 *
 * <p>Call {@link #spawn} to drop an item at a world position with a random
 * scatter velocity.  Call {@link #update} every frame to advance physics and
 * check for player pickup.
 */
public class DroppedItemManager {

    private final List<DroppedItem> items = new ArrayList<>();
    private final World world;
    private final Random random = new Random();

    public DroppedItemManager(World world) {
        this.world = world;
    }

    // -------------------------------------------------------------------------
    // Spawning
    // -------------------------------------------------------------------------

    /**
     * Spawns a dropped item at the given block position with a random outward
     * scatter velocity so multiple items from the same break event spread apart.
     *
     * @param bx block X of the broken block
     * @param by block Y of the broken block
     * @param bz block Z of the broken block
     * @param type the block type to drop
     */
    public void spawn(int bx, int by, int bz, BlockType type) {
        // Centre the item on top of the block
        float ix = bx + 0.5f;
        float iy = by + 0.8f;
        float iz = bz + 0.5f;

        DroppedItem item = new DroppedItem(ix, iy, iz, type);

        float angle = random.nextFloat() * (float) (Math.PI * 2.0);
        float speed = 1.0f + random.nextFloat() * 1.5f;
        item.velX = (float) Math.cos(angle) * speed;
        item.velZ = (float) Math.sin(angle) * speed;
        item.velY = 3.0f + random.nextFloat() * 1.5f;

        items.add(item);
    }

    /**
     * Spawns an item thrown from a specific world position with a forward
     * impulse (used when the player drops an item with Q).
     *
     * @param wx    world X (float)
     * @param wy    world Y (float)
     * @param wz    world Z (float)
     * @param dirX  normalised forward direction X
     * @param dirZ  normalised forward direction Z
     * @param type  the block type to drop
     */
    public void spawnThrown(float wx, float wy, float wz,
                            float dirX, float dirZ, BlockType type) {
        DroppedItem item = new DroppedItem(wx, wy, wz, type);
        float speed = 4.5f;
        item.velX = dirX * speed;
        item.velZ = dirZ * speed;
        item.velY = 2.5f;
        items.add(item);
    }

    // -------------------------------------------------------------------------
    // Per-frame update
    // -------------------------------------------------------------------------

    /**
     * Advances all item physics and checks for player pickup.
     *
     * @param dt     delta time in seconds
     * @param player the player (used for position and inventory access)
     */
    public void update(float dt, Player player) {
        Inventory inv = player.getInventory();
        Vector3f  pos = player.getPosition();
        // Player "centre" for pickup distance check (roughly mid-torso)
        float px = pos.x;
        float py = pos.y + 1.0f;
        float pz = pos.z;

        Iterator<DroppedItem> iter = items.iterator();
        while (iter.hasNext()) {
            DroppedItem item = iter.next();
            if (item.pickedUp) {
                iter.remove();
                continue;
            }

            item.update(dt, world);

            if (item.canPickup()) {
                float dx   = item.x - px;
                float dy   = item.y - py;
                float dz   = item.z - pz;
                float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist < DroppedItem.PICKUP_RADIUS) {
                    if (inv.addItem(item.type)) {
                        item.pickedUp = true;
                        iter.remove();
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns an unmodifiable view of all active dropped items. */
    public List<DroppedItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
