package com.blockgame.mob;

import com.blockgame.GameSystem;
import com.blockgame.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Manages the population of wandering {@link Mob}s.
 *
 * <p>Implements {@link GameSystem} so it can be registered in the main game
 * loop and updated every frame.  Mobs are spawned once at startup in a ring
 * around the player's initial spawn position.
 */
public class MobManager implements GameSystem {

    /** Number of Steve mobs spawned in the world. */
    private static final int MOB_COUNT = 5;

    /** Distance range (blocks) in which mobs are spawned from the centre. */
    private static final float SPAWN_RADIUS_MIN = 10f;
    private static final float SPAWN_RADIUS_MAX = 24f;

    private final List<Mob> mobs = new ArrayList<>();
    private final World world;
    private final Random random = new Random(42L);

    public MobManager(World world) {
        this.world = world;
    }

    // -------------------------------------------------------------------------
    // Spawning
    // -------------------------------------------------------------------------

    /**
     * Spawns {@value #MOB_COUNT} mobs in a ring around {@code centre}.
     * Should be called once after the world has been generated.
     *
     * @param centre the position to spawn mobs around (typically the player's
     *               initial position)
     */
    public void spawnInitial(Vector3f centre) {
        for (int i = 0; i < MOB_COUNT; i++) {
            spawnMobNear(centre);
        }
    }

    private void spawnMobNear(Vector3f centre) {
        float angle = random.nextFloat() * (float) (Math.PI * 2.0);
        float dist  = SPAWN_RADIUS_MIN + random.nextFloat() * (SPAWN_RADIUS_MAX - SPAWN_RADIUS_MIN);
        int   wx    = (int) (centre.x + Math.cos(angle) * dist);
        int   wz    = (int) (centre.z + Math.sin(angle) * dist);
        int   wy    = world.getTerrainHeight(wx, wz) + 1;

        mobs.add(new Mob(wx + 0.5f, wy, wz + 0.5f, random.nextLong()));
    }

    // -------------------------------------------------------------------------
    // GameSystem
    // -------------------------------------------------------------------------

    @Override
    public void update(float dt) {
        for (Mob mob : mobs) {
            mob.update(dt, world);
        }
    }

    @Override
    public void cleanup() {
        mobs.clear();
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    /** Returns an unmodifiable view of all live mobs. */
    public List<Mob> getMobs() {
        return Collections.unmodifiableList(mobs);
    }
}
