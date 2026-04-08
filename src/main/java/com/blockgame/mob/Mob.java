package com.blockgame.mob;

import com.blockgame.world.World;
import org.joml.Vector3f;

import java.util.Random;

/**
 * A simple wandering mob that behaves like Steve from Minecraft's pre-classic era.
 *
 * <p>The mob walks in a random direction for a few seconds, pauses, then picks a
 * new random direction.  Gravity and basic AABB collision against the world are
 * applied so it walks on terrain naturally.
 */
public class Mob {

    // Physical dimensions – same proportions as the player
    public static final float HEIGHT = 2.0f;  // feet → top of head (2 block-units)
    public static final float WIDTH  = 0.6f;

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

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

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

    private float wanderTimer;
    private float wanderTargetYaw;

    private final Random random;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public Mob(float x, float y, float z, long seed) {
        this.position = new Vector3f(x, y, z);
        this.random   = new Random(seed);
        // Randomise initial wander state so mobs don't all start together
        this.wanderTargetYaw = random.nextFloat() * 360f;
        this.wanderTimer     = random.nextFloat() * WANDER_MOVE_MAX;
        this.isMoving        = true;
        this.yaw             = wanderTargetYaw;
    }

    // -------------------------------------------------------------------------
    // Per-frame update
    // -------------------------------------------------------------------------

    public void update(float dt, World world) {
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
                position.y = (float) Math.floor(position.y + HEIGHT) - HEIGHT;
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
                for (float by = position.y; by < position.y + HEIGHT; by += 0.5f) {
                    if (isSolid(world, bx, by, bz)) return true;
                }
                if (isSolid(world, bx, position.y + HEIGHT - 0.001f, bz)) return true;
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
