package com.blockgame.player;

import com.blockgame.input.InputHandler;
import com.blockgame.world.BlockType;
import com.blockgame.world.World;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;

/**
 * First-person player controller.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Mouse-look (updates {@link Camera} yaw / pitch)</li>
 *   <li>WASD + sprint movement projected onto the XZ plane</li>
 *   <li>Gravity, jumping, and simple AABB collision against the world</li>
 *   <li>Block placement (right-click) and removal (left-click)</li>
 *   <li>Hotbar selection (keys 1-7 or scroll wheel)</li>
 * </ul>
 */
public class Player {

    // Physics constants
    private static final float MOVE_SPEED    = 5.0f;
    private static final float SPRINT_SPEED  = 8.5f;
    private static final float JUMP_VELOCITY = 8.5f;
    private static final float GRAVITY       = -22.0f;

    // Player bounding-box
    private static final float EYE_HEIGHT    = 1.62f;
    private static final float PLAYER_HEIGHT = 1.8f;
    private static final float PLAYER_WIDTH  = 0.6f;

    /** Maximum block-place/break reach in blocks. */
    private static final float REACH = 5.0f;

    /** Minimum time between block actions in milliseconds. */
    private static final long BLOCK_COOLDOWN_MS = 200;

    // State
    private final Vector3f position    = new Vector3f(0, 72, 0);
    private float           velocityY  = 0f;
    private boolean         onGround   = false;

    private float yaw   = 0f;   // degrees
    private float pitch = 0f;   // degrees, positive = look up

    private float  mouseSensitivity = 0.12f;
    private double lastMouseX, lastMouseY;
    private boolean firstMouse = true;

    // Block interaction
    private long lastBlockActionMs = 0;

    // Hotbar: the 7 placeable block types
    private static final BlockType[] HOTBAR = {
        BlockType.GRASS, BlockType.DIRT, BlockType.STONE,
        BlockType.WOOD,  BlockType.LEAVES, BlockType.SAND, BlockType.SNOW
    };
    private int hotbarIndex = 0;

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
    }

    // -------------------------------------------------------------------------
    // Per-frame update
    // -------------------------------------------------------------------------

    public void update(float dt) {
        handleMouseLook();
        handleMovement(dt);
        handleBlockActions();
        handleHotbarKeys();

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
        boolean sprinting = input.isKeyDown(GLFW_KEY_LEFT_CONTROL);
        float speed = sprinting ? SPRINT_SPEED : MOVE_SPEED;

        float yr = (float) Math.toRadians(yaw);
        float sinY = (float) Math.sin(yr);
        float cosY = (float) Math.cos(yr);

        float moveX = 0, moveZ = 0;

        if (input.isKeyDown(GLFW_KEY_W)) { moveX += sinY;  moveZ -= cosY; }
        if (input.isKeyDown(GLFW_KEY_S)) { moveX -= sinY;  moveZ += cosY; }
        if (input.isKeyDown(GLFW_KEY_A)) { moveX -= cosY;  moveZ -= sinY; }
        if (input.isKeyDown(GLFW_KEY_D)) { moveX += cosY;  moveZ += sinY; }

        // Normalise diagonal movement
        float len = (float) Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (len > 1.0f) {
            moveX /= len;
            moveZ /= len;
        }

        moveX *= speed * dt;
        moveZ *= speed * dt;

        // Jump
        if (input.isKeyDown(GLFW_KEY_SPACE) && onGround) {
            velocityY = JUMP_VELOCITY;
            onGround  = false;
        }

        // Gravity
        velocityY += GRAVITY * dt;

        // Apply movement with collision
        moveWithCollision(moveX, velocityY * dt, moveZ);
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

    // -------------------------------------------------------------------------
    // Block interaction (DDA ray cast)
    // -------------------------------------------------------------------------

    private void handleBlockActions() {
        long now = System.currentTimeMillis();
        if (now - lastBlockActionMs < BLOCK_COOLDOWN_MS) return;

        if (input.isMouseButtonDown(GLFW_MOUSE_BUTTON_LEFT)) {
            int[] hit = raycast(true);
            if (hit != null) {
                world.setBlock(hit[0], hit[1], hit[2], BlockType.AIR);
                lastBlockActionMs = now;
            }
        } else if (input.isMouseButtonDown(GLFW_MOUSE_BUTTON_RIGHT)) {
            int[] adjacent = raycast(false);
            if (adjacent != null && !occupiedByPlayer(adjacent[0], adjacent[1], adjacent[2])) {
                world.setBlock(adjacent[0], adjacent[1], adjacent[2], HOTBAR[hotbarIndex]);
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
            if (input.isKeyJustPressed(GLFW_KEY_1 + i)) {
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
    // Getters
    // -------------------------------------------------------------------------

    public Vector3f  getPosition()        { return position; }
    public Camera    getCamera()           { return camera; }
    public BlockType getSelectedBlock()    { return HOTBAR[hotbarIndex]; }
    public BlockType[] getHotbar()         { return HOTBAR; }
    public int       getHotbarIndex()      { return hotbarIndex; }
}
