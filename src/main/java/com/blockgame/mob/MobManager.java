package com.blockgame.mob;

import com.blockgame.GameSystem;
import com.blockgame.player.Player;
import com.blockgame.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Manages the population of wandering {@link Mob}s.
 *
 * <p>Implements {@link GameSystem} so it can be registered in the main game
 * loop and updated every frame.  Mobs are spawned once at startup in a ring
 * around the player's initial spawn position, covering all Classic mob types.
 *
 * <p>Hostile mobs only spawn in dark areas (no direct sky access above the
 * spawn point), mirroring Minecraft's light-level spawn rules.
 */
public class MobManager implements GameSystem {

    /** Number of mobs spawned in the world (one of each Classic mob type, doubled). */
    private static final int MOB_COUNT = 12;

    /** Distance range (blocks) in which mobs are spawned from the centre. */
    private static final float SPAWN_RADIUS_MIN = 10f;
    private static final float SPAWN_RADIUS_MAX = 24f;

    /** Maximum depth below the surface to search for a dark underground spawn. */
    private static final int MAX_UNDERGROUND_SEARCH = 30;

    /** Distance at which hostile mobs begin chasing the player. */
    private static final float AGGRO_RANGE = 16f;

    /** Player melee reach distance (blocks). */
    private static final float PLAYER_ATTACK_REACH = 3.5f;

    /** Hit points the player deals per swing. */
    private static final int PLAYER_ATTACK_DAMAGE = 3;

    private final List<Mob> mobs = new ArrayList<>();
    private final World world;
    private final Random random = new Random(42L);

    /** Optional player reference – required for combat. Set via {@link #setPlayer}. */
    private Player player = null;

    public MobManager(World world) {
        this.world = world;
    }

    /** Connects the player so mobs can react to and attack it. */
    public void setPlayer(Player player) {
        this.player = player;
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
        MobType[] types = MobType.values();
        for (int i = 0; i < MOB_COUNT; i++) {
            spawnMobNear(centre, types[i % types.length]);
        }
    }

    private void spawnMobNear(Vector3f centre, MobType type) {
        float angle = random.nextFloat() * (float) (Math.PI * 2.0);
        float dist  = SPAWN_RADIUS_MIN + random.nextFloat() * (SPAWN_RADIUS_MAX - SPAWN_RADIUS_MIN);
        int   wx    = (int) (centre.x + Math.cos(angle) * dist);
        int   wz    = (int) (centre.z + Math.sin(angle) * dist);
        int   surfaceY = world.getTerrainHeight(wx, wz);

        if (type.isHostile()) {
            // Hostile mobs spawn only in dark areas (not exposed to sky).
            // Search downward from just below the surface for a cave floor.
            int spawnY = findDarkSpawn(wx, surfaceY, wz);
            if (spawnY >= 0) {
                mobs.add(new Mob(wx + 0.5f, spawnY, wz + 0.5f, random.nextLong(), type));
                return;
            }
            // No suitable dark spot – skip spawning this hostile mob.
            return;
        }

        // Passive mobs spawn on the surface.
        mobs.add(new Mob(wx + 0.5f, surfaceY + 1, wz + 0.5f, random.nextLong(), type));
    }

    /**
     * Searches downward from {@code surfaceY - 1} for a position that is
     * air above a solid floor and is not exposed to the sky.
     *
     * @return the Y coordinate of the spawn position, or {@code -1} if not found.
     */
    private int findDarkSpawn(int wx, int surfaceY, int wz) {
        for (int dy = 1; dy <= MAX_UNDERGROUND_SEARCH; dy++) {
            int wy = surfaceY - dy;
            if (wy < 2) break;
            // Position must be air and have a solid floor
            if (!world.getBlock(wx, wy, wz).solid
                    && world.getBlock(wx, wy - 1, wz).solid
                    && !world.isExposedToSky(wx, wy, wz)) {
                return wy;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------------------
    // GameSystem
    // -------------------------------------------------------------------------

    @Override
    public void update(float dt) {
        // Update all mobs, letting hostile ones chase the player when nearby.
        if (player != null) {
            Vector3f pp = player.getPosition();
            Iterator<Mob> it = mobs.iterator();
            while (it.hasNext()) {
                Mob mob = it.next();
                if (!mob.isAlive()) {
                    it.remove();
                    continue;
                }

                if (mob.type.isHostile()) {
                    float dx = pp.x - mob.position.x;
                    float dz = pp.z - mob.position.z;
                    float distSq = dx * dx + dz * dz;

                    if (distSq < AGGRO_RANGE * AGGRO_RANGE) {
                        // Mob chases and attacks player
                        boolean attacked = mob.chaseAndAttack(pp.x, pp.z, dt, world);
                        if (attacked) {
                            player.damage(mob.getAttackDamage());
                        }
                        continue;
                    }
                }
                mob.update(dt, world);
            }

            // Player melee attack: if player swings and hits a mob in range
            if (player.consumeAttackSwing()) {
                attackMobsInRange(pp);
            }
        } else {
            // No player – simple wander-only update
            mobs.removeIf(mob -> !mob.isAlive());
            for (Mob mob : mobs) {
                mob.update(dt, world);
            }
        }
    }

    /**
     * Damages any mob within {@link #PLAYER_ATTACK_REACH} that the player is
     * roughly facing (within ±90° of the camera direction).
     */
    private void attackMobsInRange(Vector3f playerPos) {
        float[] forward = player.getLookDirectionXZ();
        for (Mob mob : mobs) {
            if (!mob.isAlive()) continue;
            float dx = mob.position.x - playerPos.x;
            float dz = mob.position.z - playerPos.z;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            if (dist > PLAYER_ATTACK_REACH) continue;

            // Check that the mob is roughly in front of the player
            float dot = (dist > 0.001f) ? (dx * forward[0] + dz * forward[1]) / dist : 0f;
            if (dot > -0.3f) { // within ~107° forward arc (arccos(-0.3) ≈ 107°)
                mob.damage(PLAYER_ATTACK_DAMAGE);
            }
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
