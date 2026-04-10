package com.blockgame.mob;

import com.blockgame.world.World;
import org.joml.Vector3f;

import java.util.Random;

/**
 * A wandering mob with physics and a wander AI.
 *
 * <p>The mob walks in a random direction for a few seconds, pauses, then picks a
 * new random direction.  Gravity and basic AABB collision against the world are
 * applied so it walks on terrain naturally.
 *
 * <p>Physical height and the rendered model are determined by {@link MobType}.
 */
public class Mob {

    // Horizontal hitbox half-width – the same for all mob types
    public static final float WIDTH = 0.6f;

    // Physics
    private static final float GRAVITY    = -22.0f;
    private static final float WALK_SPEED = 2.0f;   // blocks per second

    // Animation limb swing
    private static final float SWING_SPEED = 5.0f;  // radians per second of travel
    private static final float SWING_MAX   = 0.5f;  // max swing angle (radians, ~28°)

    // Wandering AI thresholds
    private static final float WANDER_MOVE_MIN = 2.0f;
    private static final float WANDER_MOVE_MAX = 5.0f;
    private static final float WANDER_IDLE_MIN = 1.0f;
    private static final float WANDER_IDLE_MAX = 3.0f;

    // Combat constants
    /** Distance at which a hostile mob can deal damage to the player (blocks). */
    private static final float ATTACK_RANGE   = 1.8f;
    /** Seconds between attacks. */
    private static final float ATTACK_COOLDOWN = 1.0f;
    /** Hit points dealt per attack. */
    private static final int   ATTACK_DAMAGE   = 1;
    /** Chase speed multiplier (hostile mobs walk faster when chasing). */
    private static final float CHASE_SPEED     = WALK_SPEED * 1.5f;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** Identifies the kind of mob (texture, model, behaviour). */
    public final MobType type;

    /** Physical height in block-units from feet to top of head. */
    public final float height;

    /** Feet position in world space. */
    public final Vector3f position;

    /** Current facing direction in degrees (yaw). 0 = faces −Z, 90 = faces +X. */
    public float yaw;

    public float  velocityY;
    public boolean onGround;

    /** Accumulated limb-swing value (radians); drives arm/leg animation. */
    public float limbSwing;

    /** Whether the mob is currently walking (used for animation). */
    public boolean isMoving;

    // Health
    private int health;
    private boolean alive = true;

    private float wanderTimer;
    private float wanderTargetYaw;

    /** Cooldown timer between mob attacks (seconds). */
    private float attackTimer = 0f;

    /** Seconds until the next ambient "say" sound. Randomised on construction. */
    float ambientSoundTimer;

    /**
     * Accumulated horizontal distance (blocks) since the last footstep sound.
     * Managed externally by {@link com.blockgame.mob.MobManager}.
     */
    float stepSoundAccumulator = 0f;

    private final Random random;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public Mob(float x, float y, float z, long seed, MobType type) {
        this.type   = type;
        this.height = type.height;
        this.health = type.maxHealth;
        this.position = new Vector3f(x, y, z);
        this.random   = new Random(seed);
        // Randomise initial wander state so mobs don't all start together
        this.wanderTargetYaw = random.nextFloat() * 360f;
        this.wanderTimer     = random.nextFloat() * WANDER_MOVE_MAX;
        this.isMoving        = true;
        this.yaw             = wanderTargetYaw;
        // Stagger ambient sounds so mobs don't all speak at the same time
        this.ambientSoundTimer = 5f + random.nextFloat() * 15f;
    }

    // -------------------------------------------------------------------------
    // Per-frame update
    // -------------------------------------------------------------------------

    public void update(float dt, World world) {
        if (!alive) return;
        if (attackTimer > 0f) attackTimer -= dt;
        tickWanderAI(dt);
        applyMovement(dt, world);
    }

    // -------------------------------------------------------------------------
    // Wandering AI
    // -------------------------------------------------------------------------

    private void tickWanderAI(float dt) {
        wanderTimer -= dt;
        if (wanderTimer > 0f) return;

        if (isMoving) {
            // Was moving – pause for a moment
            isMoving     = false;
            wanderTimer  = WANDER_IDLE_MIN + random.nextFloat() * (WANDER_IDLE_MAX - WANDER_IDLE_MIN);
        } else {
            // Was idle – pick a new random direction and walk
            wanderTargetYaw = random.nextFloat() * 360f;
            isMoving        = true;
            wanderTimer     = WANDER_MOVE_MIN + random.nextFloat() * (WANDER_MOVE_MAX - WANDER_MOVE_MIN);
        }
    }

    // -------------------------------------------------------------------------
    // Physics & movement
    // -------------------------------------------------------------------------

    private void applyMovement(float dt, World world) {
        float moveX = 0f, moveZ = 0f;

        if (isMoving) {
            // Smoothly rotate yaw toward the target direction
            float diff = wanderTargetYaw - yaw;
            // Normalise difference to [-180, 180]
            while (diff >  180f) diff -= 360f;
            while (diff < -180f) diff += 360f;
            float turnSpeed = 200f; // degrees per second
            yaw += Math.signum(diff) * Math.min(Math.abs(diff), turnSpeed * dt);

            float yr = (float) Math.toRadians(yaw);
            moveX =  (float) Math.sin(yr) * WALK_SPEED * dt;
            moveZ = -(float) Math.cos(yr) * WALK_SPEED * dt;

            limbSwing += SWING_SPEED * dt;
        }

        // Gravity
        velocityY += GRAVITY * dt;

        moveWithCollision(moveX, velocityY * dt, moveZ, world);
    }

    /**
     * Steers this mob toward {@code target} and returns whether it is within
     * attack range.  Called by {@link com.blockgame.mob.MobManager} each frame
     * for hostile mobs when a player target is known.
     *
     * @param targetX  target world X
     * @param targetZ  target world Z
     * @param dt       frame delta time in seconds
     * @param world    the world (for movement collision)
     * @return {@code true} if the mob is within melee range this frame
     */
    public boolean chaseAndAttack(float targetX, float targetZ, float dt, World world) {
        float dx = targetX - position.x;
        float dz = targetZ - position.z;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);

        // Turn to face target
        wanderTargetYaw = (float) Math.toDegrees(Math.atan2(dx, -dz));
        float diff = wanderTargetYaw - yaw;
        while (diff >  180f) diff -= 360f;
        while (diff < -180f) diff += 360f;
        yaw += Math.signum(diff) * Math.min(Math.abs(diff), 360f * dt);

        float moveX = 0f, moveZ = 0f;
        if (dist > ATTACK_RANGE) {
            float yr = (float) Math.toRadians(yaw);
            moveX =  (float) Math.sin(yr) * CHASE_SPEED * dt;
            moveZ = -(float) Math.cos(yr) * CHASE_SPEED * dt;
            isMoving = true;
            limbSwing += SWING_SPEED * dt;
        } else {
            isMoving = false;
        }

        velocityY += GRAVITY * dt;
        moveWithCollision(moveX, velocityY * dt, moveZ, world);

        // Return true if within melee range and attack cooldown is ready
        if (dist <= ATTACK_RANGE && attackTimer <= 0f) {
            attackTimer = ATTACK_COOLDOWN;
            return true;
        }
        return false;
    }

    /**
     * Returns the hit points this mob deals per attack.
     */
    public int getAttackDamage() {
        return ATTACK_DAMAGE;
    }

    private void moveWithCollision(float dx, float dy, float dz, World world) {
        // Y axis
        position.y += dy;
        if (dy < 0) {
            if (collidesWithWorld(world)) {
                position.y = (float) Math.ceil(position.y - 0.001f);
                velocityY  = 0f;
                onGround   = true;
            } else {
                onGround = false;
            }
        } else if (dy > 0) {
            if (collidesWithWorld(world)) {
                position.y = (float) Math.floor(position.y + height) - height;
                velocityY  = 0f;
            }
        }

        // X axis
        position.x += dx;
        if (collidesWithWorld(world)) position.x -= dx;

        // Z axis
        position.z += dz;
        if (collidesWithWorld(world)) position.z -= dz;
    }

    private boolean collidesWithWorld(World world) {
        float hw = WIDTH / 2f - 0.001f;
        float[] xs = { position.x - hw, position.x + hw };
        float[] zs = { position.z - hw, position.z + hw };

        for (float bx : xs) {
            for (float bz : zs) {
                for (float by = position.y; by < position.y + height; by += 0.5f) {
                    if (isSolid(world, bx, by, bz)) return true;
                }
                if (isSolid(world, bx, position.y + height - 0.001f, bz)) return true;
            }
        }
        return false;
    }

    private static boolean isSolid(World world, float x, float y, float z) {
        return world.getBlock(
            (int) Math.floor(x),
            (int) Math.floor(y),
            (int) Math.floor(z)
        ).solid;
    }

    // -------------------------------------------------------------------------
    // Health & combat
    // -------------------------------------------------------------------------

    /**
     * Deals {@code amount} damage to this mob.  If health drops to 0 the mob
     * is marked dead and will be removed by {@link com.blockgame.mob.MobManager}.
     */
    public void damage(int amount) {
        if (!alive) return;
        health = Math.max(0, health - amount);
        if (health == 0) alive = false;
    }

    /** Returns {@code true} while this mob has HP remaining. */
    public boolean isAlive() { return alive; }

    /** Returns current hit points. */
    public int getHealth() { return health; }

    /** Returns maximum hit points (from {@link MobType#maxHealth}). */
    public int getMaxHealth() { return type.maxHealth; }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    /**
     * Returns the current arm/leg swing angle in radians, limited to
     * [-SWING_MAX, +SWING_MAX], ready to feed into the renderer.
     */
    public float getSwingAngle() {
        return isMoving ? SWING_MAX * (float) Math.sin(limbSwing) : 0f;
    }
}
