package com.blockgame.player;

import com.blockgame.Saveable;
import com.blockgame.input.InputAction;
import com.blockgame.input.InputHandler;
import com.blockgame.rendering.ParticleSystem;
import com.blockgame.world.BlockType;
import com.blockgame.world.World;
import org.joml.Vector3f;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * First-person player controller.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Mouse-look (updates {@link Camera} yaw / pitch)</li>
 *   <li>WASD + sprint movement projected onto the XZ plane</li>
 *   <li>Gravity, jumping, and simple AABB collision against the world</li>
 *   <li>Fall damage and death / respawn</li>
 *   <li>Block placement (right-click) and removal (left-click)</li>
 *   <li>Hotbar selection (keys 1-8 or scroll wheel)</li>
 *   <li>Drowning (air / oxygen) system</li>
 *   <li>Hunger system</li>
 * </ul>
 *
 * <p>All input is queried via named {@link InputAction}s so that key bindings
 * can be changed in {@link InputHandler} without touching this class.
 */
public class Player implements Saveable {

    // Physics constants
    private static final float MOVE_SPEED        = 5.0f;
    private static final float SPRINT_SPEED      = 8.5f;
    private static final float JUMP_VELOCITY     = 8.5f;
    /** Gravity stronger than original (was -22) to reduce floatiness. */
    private static final float GRAVITY           = -28.0f;

    // Swimming physics constants
    /** Horizontal movement speed while in water (70 % of land speed). */
    private static final float SWIM_SPEED        = MOVE_SPEED  * 0.7f;
    /** Sprint-swim speed while in water. */
    private static final float SPRINT_SWIM_SPEED = SPRINT_SPEED * 0.7f;
    /** Gentle downward pull while submerged (replaces full gravity). */
    private static final float WATER_GRAVITY     = -4.0f;
    /** Maximum downward speed while submerged (terminal velocity in water). */
    private static final float WATER_SINK_MAX    = -2.0f;
    /** Upward velocity applied each frame the JUMP key is held while swimming. */
    private static final float SWIM_UP_SPEED     = 4.0f;

    // Player bounding-box
    private static final float EYE_HEIGHT    = 1.62f;
    private static final float PLAYER_HEIGHT = 1.8f;
    private static final float PLAYER_WIDTH  = 0.6f;

    /** Maximum block-place/break reach in blocks. */
    private static final float REACH = 5.0f;

    /** Minimum time between block actions in milliseconds. */
    private static final long BLOCK_COOLDOWN_MS = 200;

    // Hotbar: the 8 placeable block types
    private static final BlockType[] HOTBAR = {
        BlockType.GRASS, BlockType.COBBLESTONE, BlockType.STONE,
        BlockType.WOOD,  BlockType.PLANKS, BlockType.BRICKS,
        BlockType.TNT,   BlockType.OAK_SAPLING
    };

    // Ordered hotbar input actions matching the HOTBAR array indices
    private static final InputAction[] HOTBAR_ACTIONS = {
        InputAction.HOTBAR_1, InputAction.HOTBAR_2, InputAction.HOTBAR_3,
        InputAction.HOTBAR_4, InputAction.HOTBAR_5, InputAction.HOTBAR_6,
        InputAction.HOTBAR_7, InputAction.HOTBAR_8
    };

    // Fall-damage constants
    /** Fall distance (blocks) before damage is applied. */
    private static final float SAFE_FALL_DISTANCE = 3.0f;

    // Drowning constants
    /** Maximum air supply (same as Minecraft's 300 bubbles). */
    public static final int MAX_AIR = 300;
    /** Seconds between drowning damage ticks (1 HP per second). */
    private static final float DROWN_DAMAGE_INTERVAL = 1.0f;

    // Hunger constants
    /** Maximum food level (20 = 10 food icons). */
    public static final int MAX_FOOD = 20;
    /** Seconds to deplete 1 food point while active. */
    private static final float FOOD_DRAIN_INTERVAL = 80.0f;
    /** Seconds to lose 1 HP from starvation (food = 0). */
    private static final float STARVE_DAMAGE_INTERVAL = 4.0f;
    /** Seconds to regen 1 HP when well-fed (food >= 18). */
    private static final float REGEN_INTERVAL = 2.0f;

    // State
    private final Vector3f position    = new Vector3f(0, 72, 0);
    private float           velocityY  = 0f;
    private boolean         onGround   = false;
    /** True when at least part of the player AABB overlaps a water block. */
    private boolean         inWater    = false;

    private float yaw   = 0f;   // degrees
    private float pitch = 0f;   // degrees, positive = look up

    private float  mouseSensitivity = 0.12f;
    private double lastMouseX, lastMouseY;
    private boolean firstMouse = true;

    // Block interaction
    private long lastBlockActionMs = 0;
    private int hotbarIndex = 0;

    // Targeted block for highlight rendering (updated every frame)
    private boolean hasTargetedBlock    = false;
    private final int[] targetedBlock      = new int[3];
    private final int[] targetedFaceNormal = new int[3];

    // Health system
    private static final int MAX_HEALTH = 20; // 20 HP = 10 hearts
    private int health = MAX_HEALTH;

    // Drowning / air system
    private int air = MAX_AIR;
    private float drownDamageTimer = 0f;

    // Hunger system
    private int food = MAX_FOOD;
    private float foodDrainTimer  = 0f;
    private float starveDmgTimer  = 0f;
    private float regenTimer      = 0f;

    // Fall damage tracking
    /** Y position when the player was last on the ground or left the ground. */
    private float fallStartY = 0f;
    /** Whether the player was airborne and falling last frame. */
    private boolean wasFalling = false;

    // Combat: set when the player left-clicks without a block target
    private boolean attackSwingPending = false;

    // Spawn position (used for respawn after death)
    private float spawnX = 0f, spawnY = 72f, spawnZ = 0f;

    // Whether the mouse cursor is captured (first-person look active)
    private boolean mouseCaptured = true;

    // Optional particle system – set after construction via setParticleSystem()
    private ParticleSystem particleSystem = null;

    // Dependencies
    private final World        world;
    private final InputHandler input;
    private final Camera       camera;

    public Player(World world, InputHandler input, float aspectRatio) {
        this.world  = world;
        this.input  = input;
        this.camera = new Camera(aspectRatio);

        // Spawn on top of terrain at world origin
        int spawnY = world.getTerrainHeight(0, 0) + 1;
        position.set(0, spawnY, 0);
        this.spawnX = 0f;
        this.spawnY = spawnY;
        this.spawnZ = 0f;
        this.fallStartY = spawnY;
    }

    // -------------------------------------------------------------------------
    // Per-frame update
    // -------------------------------------------------------------------------

    public void update(float dt) {
        if (health <= 0) {
            respawn();
            return;
        }
        handleMouseLook();
        handleMovement(dt);
        updateTargetedBlock();
        handleBlockActions();
        handleHotbarKeys();
        updateDrowning(dt);
        updateHunger(dt);

        // Sync camera position and orientation
        camera.setPosition(new Vector3f(position.x, position.y + EYE_HEIGHT, position.z));
        camera.setPitch(pitch);
        camera.setYaw(yaw);
        camera.updateView();
    }

    // -------------------------------------------------------------------------
    // Mouse look
    // -------------------------------------------------------------------------

    private void handleMouseLook() {
        if (!mouseCaptured) return;

        double mx = input.getMouseX();
        double my = input.getMouseY();

        if (firstMouse) {
            lastMouseX = mx;
            lastMouseY = my;
            firstMouse = false;
            return;
        }

        double dx = mx - lastMouseX;
        double dy = my - lastMouseY;
        lastMouseX = mx;
        lastMouseY = my;

        yaw   += (float) (dx * mouseSensitivity);
        pitch -= (float) (dy * mouseSensitivity);   // invert Y: move down → look down
        pitch  = Math.max(-89.9f, Math.min(89.9f, pitch));
    }

    // -------------------------------------------------------------------------
    // Movement & physics
    // -------------------------------------------------------------------------

    private void handleMovement(float dt) {
        inWater = checkInWater();

        boolean sprinting = input.isActionDown(InputAction.SPRINT);
        float speed;
        if (inWater) {
            speed = sprinting ? SPRINT_SWIM_SPEED : SWIM_SPEED;
        } else {
            speed = sprinting ? SPRINT_SPEED : MOVE_SPEED;
        }

        float yr = (float) Math.toRadians(yaw);
        float sinY = (float) Math.sin(yr);
        float cosY = (float) Math.cos(yr);

        float moveX = 0, moveZ = 0;

        if (input.isActionDown(InputAction.MOVE_FORWARD))  { moveX += sinY;  moveZ -= cosY; }
        if (input.isActionDown(InputAction.MOVE_BACKWARD)) { moveX -= sinY;  moveZ += cosY; }
        if (input.isActionDown(InputAction.MOVE_LEFT))     { moveX -= cosY;  moveZ -= sinY; }
        if (input.isActionDown(InputAction.MOVE_RIGHT))    { moveX += cosY;  moveZ += sinY; }

        // Normalise diagonal movement
        float len = (float) Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (len > 1.0f) {
            moveX /= len;
            moveZ /= len;
        }

        moveX *= speed * dt;
        moveZ *= speed * dt;

        if (inWater) {
            // Swimming: JUMP key propels the player upward; otherwise water slows the fall.
            if (input.isActionDown(InputAction.JUMP)) {
                velocityY = SWIM_UP_SPEED;
            } else {
                velocityY += WATER_GRAVITY * dt;
                if (velocityY < WATER_SINK_MAX) velocityY = WATER_SINK_MAX;
            }
        } else {
            // Normal land physics
            if (input.isActionDown(InputAction.JUMP) && onGround) {
                velocityY = JUMP_VELOCITY;
                onGround  = false;
            }
            velocityY += GRAVITY * dt;
        }

        // Track fall for fall-damage calculation
        boolean fallingBeforeMove = !onGround && velocityY < 0f && !inWater;
        if (fallingBeforeMove && !wasFalling) {
            fallStartY = position.y;
        }

        // Apply movement with collision
        float prevY = position.y;
        moveWithCollision(moveX, velocityY * dt, moveZ);

        // Fall damage: player just landed (was falling, now on ground)
        if (!inWater && wasFalling && fallingBeforeMove && onGround) {
            float fallen = fallStartY - prevY;
            if (fallen > SAFE_FALL_DISTANCE) {
                int dmg = (int) (fallen - SAFE_FALL_DISTANCE);
                if (dmg > 0) damage(dmg);
            }
            wasFalling = false;
        } else {
            wasFalling = fallingBeforeMove;
        }
    }

    private void moveWithCollision(float dx, float dy, float dz) {
        // --- Y axis ---
        position.y += dy;
        if (dy < 0) {
            if (collidesWithWorld(position.x, position.y, position.z)) {
                position.y = (float) Math.ceil(position.y - 0.001f);
                velocityY  = 0f;
                onGround   = true;
            } else {
                onGround = false;
            }
        } else if (dy > 0) {
            if (collidesWithWorld(position.x, position.y, position.z)) {
                position.y = (float) Math.floor(position.y + PLAYER_HEIGHT) - PLAYER_HEIGHT;
                velocityY  = 0f;
            }
        }

        // --- X axis ---
        position.x += dx;
        if (collidesWithWorld(position.x, position.y, position.z)) {
            position.x -= dx;
        }

        // --- Z axis ---
        position.z += dz;
        if (collidesWithWorld(position.x, position.y, position.z)) {
            position.z -= dz;
        }
    }

    /**
     * Returns {@code true} if the player AABB (at the given feet position)
     * overlaps any solid block.
     */
    private boolean collidesWithWorld(float x, float y, float z) {
        float hw = PLAYER_WIDTH / 2f - 0.001f; // half-width minus tiny epsilon
        float[] xs = {x - hw, x + hw};
        float[] zs = {z - hw, z + hw};

        for (float bx : xs) {
            for (float bz : zs) {
                // Check each block cell the player occupies vertically
                for (float by = y; by < y + PLAYER_HEIGHT; by += 0.5f) {
                    if (isSolid(bx, by, bz)) return true;
                }
                // Make sure the top of the head is also checked
                if (isSolid(bx, y + PLAYER_HEIGHT - 0.001f, bz)) return true;
            }
        }
        return false;
    }

    private boolean isSolid(float x, float y, float z) {
        return world.getBlock(
            (int) Math.floor(x),
            (int) Math.floor(y),
            (int) Math.floor(z)
        ).solid;
    }

    /**
     * Returns {@code true} if the player AABB overlaps at least one water block.
     * Used to switch between land and water physics each frame.
     */
    private boolean checkInWater() {
        float hw = PLAYER_WIDTH / 2f - 0.001f; // half-width minus tiny epsilon (matches collidesWithWorld)
        float[] xs = {position.x - hw, position.x + hw};
        float[] zs = {position.z - hw, position.z + hw};

        for (float bx : xs) {
            for (float bz : zs) {
                for (float by = position.y; by < position.y + PLAYER_HEIGHT; by += 0.5f) {
                    if (isWater(bx, by, bz)) return true;
                }
                if (isWater(bx, position.y + PLAYER_HEIGHT - 0.001f, bz)) return true; // check head
            }
        }
        return false;
    }

    private boolean isWater(float x, float y, float z) {
        return world.getBlock(
            (int) Math.floor(x),
            (int) Math.floor(y),
            (int) Math.floor(z)
        ) == BlockType.WATER;
    }

    /**
     * Returns {@code true} when the player's eye level is inside a water block.
     * Used by the renderer to apply the underwater visual effect (blue fog tint).
     */
    public boolean isEyeUnderwater() {
        return world.getBlock(
            (int) Math.floor(position.x),
            (int) Math.floor(position.y + EYE_HEIGHT),
            (int) Math.floor(position.z)
        ) == BlockType.WATER;
    }

    // -------------------------------------------------------------------------
    // Drowning system
    // -------------------------------------------------------------------------

    private void updateDrowning(float dt) {
        if (isEyeUnderwater()) {
            // Drain air while submerged
            float drain = (float) MAX_AIR / 15.0f * dt; // 15 seconds to fully drain
            air = Math.max(0, air - (int) Math.ceil(drain));

            if (air <= 0) {
                // Drown damage: 1 HP per DROWN_DAMAGE_INTERVAL seconds
                drownDamageTimer += dt;
                if (drownDamageTimer >= DROWN_DAMAGE_INTERVAL) {
                    drownDamageTimer -= DROWN_DAMAGE_INTERVAL;
                    damage(1);
                }
            }
        } else {
            // Refill air quickly when surfaced
            float refill = (float) MAX_AIR / 3.0f * dt; // refill in ~3 seconds
            air = Math.min(MAX_AIR, air + (int) Math.ceil(refill));
            drownDamageTimer = 0f;
        }
    }

    // -------------------------------------------------------------------------
    // Hunger system
    // -------------------------------------------------------------------------

    private void updateHunger(float dt) {
        // Drain food over time
        foodDrainTimer += dt;
        if (foodDrainTimer >= FOOD_DRAIN_INTERVAL) {
            foodDrainTimer -= FOOD_DRAIN_INTERVAL;
            food = Math.max(0, food - 1);
        }

        if (food == 0) {
            // Starvation: lose 1 HP every STARVE_DAMAGE_INTERVAL seconds.
            // Starvation stops at 1 HP (it will not kill the player directly –
            // only hostile mobs or drowning can deliver the final blow).
            starveDmgTimer += dt;
            if (starveDmgTimer >= STARVE_DAMAGE_INTERVAL) {
                starveDmgTimer -= STARVE_DAMAGE_INTERVAL;
                if (health > 1) damage(1);
            }
        } else {
            starveDmgTimer = 0f;
        }

        // Health regeneration when well-fed (food >= 18) and not at max health
        if (food >= 18 && health < MAX_HEALTH) {
            regenTimer += dt;
            if (regenTimer >= REGEN_INTERVAL) {
                regenTimer -= REGEN_INTERVAL;
                heal(1);
            }
        } else {
            regenTimer = 0f;
        }
    }

    // -------------------------------------------------------------------------
    // Death & respawn
    // -------------------------------------------------------------------------

    private void respawn() {
        health = MAX_HEALTH;
        food   = MAX_FOOD;
        air    = MAX_AIR;
        velocityY = 0f;
        onGround  = false;
        wasFalling = false;
        position.set(spawnX, spawnY, spawnZ);
        foodDrainTimer  = 0f;
        starveDmgTimer  = 0f;
        regenTimer      = 0f;
        drownDamageTimer = 0f;
    }

    // -------------------------------------------------------------------------
    // Block interaction (DDA ray cast)
    // -------------------------------------------------------------------------

    private void handleBlockActions() {
        long now = System.currentTimeMillis();
        if (now - lastBlockActionMs < BLOCK_COOLDOWN_MS) return;

        if (input.isActionDown(InputAction.BREAK_BLOCK)) {
            int[] hit = raycast(true);
            if (hit != null) {
                BlockType broken = world.getBlock(hit[0], hit[1], hit[2]);
                if (!broken.breakable) return;
                world.setBlock(hit[0], hit[1], hit[2], BlockType.AIR);
                if (broken.behavior != null) {
                    broken.behavior.onBreak(world, hit[0], hit[1], hit[2]);
                }
                if (particleSystem != null) {
                    particleSystem.spawn(hit[0], hit[1], hit[2], broken);
                }
                lastBlockActionMs = now;
            } else {
                // No block in range – flag a melee attack swing
                attackSwingPending = true;
            }
        } else if (input.isActionDown(InputAction.PLACE_BLOCK)) {
            int[] adjacent = raycast(false);
            if (adjacent != null && !occupiedByPlayer(adjacent[0], adjacent[1], adjacent[2])) {
                BlockType placed = HOTBAR[hotbarIndex];
                world.setBlock(adjacent[0], adjacent[1], adjacent[2], placed);
                if (placed.behavior != null) {
                    placed.behavior.onPlace(world, adjacent[0], adjacent[1], adjacent[2]);
                }
                lastBlockActionMs = now;
            }
        }
    }

    /**
     * DDA ray cast from the player's eye.
     *
     * @param hitSolid if {@code true}, returns the first solid block hit;
     *                 if {@code false}, returns the last air block before the hit.
     * @return block coordinates, or {@code null} if nothing was hit within reach.
     */
    private int[] raycast(boolean hitSolid) {
        Vector3f eye = new Vector3f(position.x, position.y + EYE_HEIGHT, position.z);
        Vector3f dir = camera.getDirection();

        int bx = (int) Math.floor(eye.x);
        int by = (int) Math.floor(eye.y);
        int bz = (int) Math.floor(eye.z);

        int stepX = dir.x >= 0 ? 1 : -1;
        int stepY = dir.y >= 0 ? 1 : -1;
        int stepZ = dir.z >= 0 ? 1 : -1;

        float dtX = (dir.x == 0) ? Float.MAX_VALUE : Math.abs(1f / dir.x);
        float dtY = (dir.y == 0) ? Float.MAX_VALUE : Math.abs(1f / dir.y);
        float dtZ = (dir.z == 0) ? Float.MAX_VALUE : Math.abs(1f / dir.z);

        float tX = (dir.x == 0) ? Float.MAX_VALUE
            : ((dir.x > 0 ? (bx + 1 - eye.x) : (eye.x - bx)) / Math.abs(dir.x));
        float tY = (dir.y == 0) ? Float.MAX_VALUE
            : ((dir.y > 0 ? (by + 1 - eye.y) : (eye.y - by)) / Math.abs(dir.y));
        float tZ = (dir.z == 0) ? Float.MAX_VALUE
            : ((dir.z > 0 ? (bz + 1 - eye.z) : (eye.z - bz)) / Math.abs(dir.z));

        int prevBx = bx, prevBy = by, prevBz = bz;

        for (int step = 0; step < 100; step++) {
            if (world.getBlock(bx, by, bz).solid) {
                return hitSolid
                    ? new int[]{bx, by, bz}
                    : new int[]{prevBx, prevBy, prevBz};
            }

            // Check reach distance
            float t = Math.min(tX, Math.min(tY, tZ));
            if (t > REACH) return null;

            prevBx = bx; prevBy = by; prevBz = bz;

            if (tX <= tY && tX <= tZ) { bx += stepX; tX += dtX; }
            else if (tY <= tZ)         { by += stepY; tY += dtY; }
            else                       { bz += stepZ; tZ += dtZ; }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Targeted block tracking (used for the face highlight)
    // -------------------------------------------------------------------------

    private void updateTargetedBlock() {
        int[] result = raycastFull();
        if (result != null) {
            hasTargetedBlock = true;
            targetedBlock[0] = result[0]; targetedBlock[1] = result[1]; targetedBlock[2] = result[2];
            targetedFaceNormal[0] = result[3]; targetedFaceNormal[1] = result[4]; targetedFaceNormal[2] = result[5];
        } else {
            hasTargetedBlock = false;
        }
    }

    /**
     * DDA ray cast that returns the first solid block hit together with its
     * face normal (the side the ray entered from).
     *
     * @return int[6] = {bx, by, bz, nx, ny, nz}, or {@code null} if nothing
     *         was hit within reach.
     */
    private int[] raycastFull() {
        Vector3f eye = new Vector3f(position.x, position.y + EYE_HEIGHT, position.z);
        Vector3f dir = camera.getDirection();

        int bx = (int) Math.floor(eye.x);
        int by = (int) Math.floor(eye.y);
        int bz = (int) Math.floor(eye.z);

        int stepX = dir.x >= 0 ? 1 : -1;
        int stepY = dir.y >= 0 ? 1 : -1;
        int stepZ = dir.z >= 0 ? 1 : -1;

        float dtX = (dir.x == 0) ? Float.MAX_VALUE : Math.abs(1f / dir.x);
        float dtY = (dir.y == 0) ? Float.MAX_VALUE : Math.abs(1f / dir.y);
        float dtZ = (dir.z == 0) ? Float.MAX_VALUE : Math.abs(1f / dir.z);

        float tX = (dir.x == 0) ? Float.MAX_VALUE
            : ((dir.x > 0 ? (bx + 1 - eye.x) : (eye.x - bx)) / Math.abs(dir.x));
        float tY = (dir.y == 0) ? Float.MAX_VALUE
            : ((dir.y > 0 ? (by + 1 - eye.y) : (eye.y - by)) / Math.abs(dir.y));
        float tZ = (dir.z == 0) ? Float.MAX_VALUE
            : ((dir.z > 0 ? (bz + 1 - eye.z) : (eye.z - bz)) / Math.abs(dir.z));

        // -1 = none yet,  0 = X axis,  1 = Y axis,  2 = Z axis
        int lastAxis = -1;

        for (int step = 0; step < 100; step++) {
            if (world.getBlock(bx, by, bz).solid) {
                int nx = 0, ny = 0, nz = 0;
                if      (lastAxis == 0) nx = -stepX;
                else if (lastAxis == 1) ny = -stepY;
                else if (lastAxis == 2) nz = -stepZ;
                return new int[]{bx, by, bz, nx, ny, nz};
            }

            float t = Math.min(tX, Math.min(tY, tZ));
            if (t > REACH) return null;

            if (tX <= tY && tX <= tZ) { bx += stepX; tX += dtX; lastAxis = 0; }
            else if (tY <= tZ)         { by += stepY; tY += dtY; lastAxis = 1; }
            else                       { bz += stepZ; tZ += dtZ; lastAxis = 2; }
        }
        return null;
    }

    /** Returns true if the block position overlaps the player's current AABB. */
    private boolean occupiedByPlayer(int bx, int by, int bz) {
        float hw = PLAYER_WIDTH / 2f;
        return bx >= (int) Math.floor(position.x - hw)
            && bx <= (int) Math.floor(position.x + hw)
            && by >= (int) Math.floor(position.y)
            && by <  (int) Math.floor(position.y + PLAYER_HEIGHT)
            && bz >= (int) Math.floor(position.z - hw)
            && bz <= (int) Math.floor(position.z + hw);
    }

    // -------------------------------------------------------------------------
    // Hotbar
    // -------------------------------------------------------------------------

    private void handleHotbarKeys() {
        for (int i = 0; i < HOTBAR.length; i++) {
            if (input.isActionJustPressed(HOTBAR_ACTIONS[i])) {
                hotbarIndex = i;
            }
        }
        // Scroll wheel
        double scroll = input.consumeScroll();
        if (scroll != 0) {
            hotbarIndex = Math.floorMod(hotbarIndex - (int) Math.signum(scroll), HOTBAR.length);
        }
    }

    // -------------------------------------------------------------------------
    // Saveable – player position persisted across sessions
    // -------------------------------------------------------------------------

    /** Writes the player's current position to {@code out}. */
    @Override
    public void save(DataOutputStream out) throws IOException {
        out.writeFloat(position.x);
        out.writeFloat(position.y);
        out.writeFloat(position.z);
    }

    /** Reads and applies a previously saved player position from {@code in}. */
    @Override
    public void load(DataInputStream in) throws IOException {
        float px = in.readFloat();
        float py = in.readFloat();
        float pz = in.readFloat();
        position.set(px, py, pz);
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public Vector3f  getPosition()          { return position; }
    public Camera    getCamera()             { return camera; }
    public BlockType getSelectedBlock()      { return HOTBAR[hotbarIndex]; }
    public BlockType[] getHotbar()           { return HOTBAR; }
    public int       getHotbarIndex()        { return hotbarIndex; }
    public int[]     getTargetedBlock()      { return hasTargetedBlock ? targetedBlock : null; }
    public int[]     getTargetedFaceNormal() { return hasTargetedBlock ? targetedFaceNormal : null; }
    /** Returns {@code true} when the player is at least partially submerged in water. */
    public boolean   isInWater()             { return inWater; }

    // -------------------------------------------------------------------------
    // Health system
    // -------------------------------------------------------------------------

    /** Returns the player's current health (0–{@link #MAX_HEALTH}). */
    public int getHealth() { return health; }

    /** Returns the player's maximum health. */
    public int getMaxHealth() { return MAX_HEALTH; }

    /**
     * Reduces the player's health by {@code amount} (clamped to 0).
     *
     * @param amount positive damage value
     */
    public void damage(int amount) {
        health = Math.max(0, health - amount);
    }

    /**
     * Restores the player's health by {@code amount} (clamped to {@link #MAX_HEALTH}).
     *
     * @param amount positive heal value
     */
    public void heal(int amount) {
        health = Math.min(MAX_HEALTH, health + amount);
    }

    // -------------------------------------------------------------------------
    // Drowning / air system
    // -------------------------------------------------------------------------

    /** Returns the current air supply (0–{@link #MAX_AIR}). */
    public int getAir() { return air; }

    /** Returns the maximum air supply ({@link #MAX_AIR}). */
    public int getMaxAir() { return MAX_AIR; }

    // -------------------------------------------------------------------------
    // Hunger system
    // -------------------------------------------------------------------------

    /** Returns the current food level (0–{@link #MAX_FOOD}). */
    public int getFood() { return food; }

    /** Returns the maximum food level ({@link #MAX_FOOD}). */
    public int getMaxFood() { return MAX_FOOD; }

    // -------------------------------------------------------------------------
    // Combat
    // -------------------------------------------------------------------------

    /**
     * Consumes and returns the pending attack-swing flag.  Returns {@code true}
     * once per left-click when no block was in range, then resets to
     * {@code false}.  Called by {@link com.blockgame.mob.MobManager}.
     */
    public boolean consumeAttackSwing() {
        boolean result = attackSwingPending;
        attackSwingPending = false;
        return result;
    }

    /**
     * Returns the player's normalised look direction projected onto the XZ plane
     * as a float[2] {x, z}.  Used by mob combat for aim checking.
     */
    public float[] getLookDirectionXZ() {
        Vector3f dir = camera.getDirection();
        float len = (float) Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (len < 0.001f) return new float[]{0f, -1f};
        return new float[]{dir.x / len, dir.z / len};
    }

    // -------------------------------------------------------------------------
    // Mouse capture
    // -------------------------------------------------------------------------

    /**
     * Enables or disables first-person mouse look (cursor capture).
     * When re-enabling, resets the first-mouse flag to avoid a jump.
     */
    public void setMouseCaptured(boolean captured) {
        if (captured && !this.mouseCaptured) {
            firstMouse = true;
        }
        this.mouseCaptured = captured;
    }

    /** Teleports the player to the given world-space feet position. */
    public void setPosition(float x, float y, float z) {
        position.set(x, y, z);
    }

    /** Connects the particle system so block-break events spawn particles. */
    public void setParticleSystem(ParticleSystem ps) {
        this.particleSystem = ps;
    }
}

