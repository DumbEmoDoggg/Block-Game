package com.blockgame.player;

import com.blockgame.Saveable;
import com.blockgame.input.InputAction;
import com.blockgame.input.InputHandler;
import com.blockgame.rendering.ParticleSystem;
import com.blockgame.world.BlockType;
import com.blockgame.world.DroppedItemManager;
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
 *   <li>Block placement (right-click) and removal (left-click with inventory pickup)</li>
 *   <li>Item dropping (Q) and inventory toggle (E)</li>
 *   <li>Hotbar selection (keys 1-9 or scroll wheel)</li>
 * </ul>
 */
public class Player implements Saveable {

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

    // Ordered hotbar input actions (9 slots)
    private static final InputAction[] HOTBAR_ACTIONS = {
        InputAction.HOTBAR_1, InputAction.HOTBAR_2, InputAction.HOTBAR_3,
        InputAction.HOTBAR_4, InputAction.HOTBAR_5, InputAction.HOTBAR_6,
        InputAction.HOTBAR_7, InputAction.HOTBAR_8, InputAction.HOTBAR_9
    };

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
    private int hotbarIndex = 0;

    // Inventory
    private final Inventory inventory = new Inventory();

    // Whether the inventory screen is open (pauses 3-D interaction)
    private boolean inventoryOpen = false;

    // Targeted block for highlight rendering (updated every frame)
    private boolean hasTargetedBlock    = false;
    private final int[] targetedBlock      = new int[3];
    private final int[] targetedFaceNormal = new int[3];

    // Whether the mouse cursor is captured (first-person look active)
    private boolean mouseCaptured = true;

    // Optional particle system – set after construction via setParticleSystem()
    private ParticleSystem particleSystem = null;

    // Optional dropped-item manager – set after construction
    private DroppedItemManager droppedItemManager = null;

    // Dependencies
    private final World        world;
    private final InputHandler input;
    private final Camera       camera;

    public Player(World world, InputHandler input, float aspectRatio) {
        this.world  = world;
        this.input  = input;
        this.camera = new Camera(aspectRatio);

        inventory.fillDefaultHotbar();

        // Spawn on top of terrain at world origin
        int spawnY = world.getTerrainHeight(0, 0) + 1;
        position.set(0, spawnY, 0);
    }

    // -------------------------------------------------------------------------
    // Per-frame update
    // -------------------------------------------------------------------------

    public void update(float dt) {
        handleHotbarKeys();
        handleInventoryToggle();

        if (!inventoryOpen) {
            handleMouseLook();
            handleMovement(dt);
            updateTargetedBlock();
            handleBlockActions();
            handleDropItem();
        }

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
        boolean sprinting = input.isActionDown(InputAction.SPRINT);
        float speed = sprinting ? SPRINT_SPEED : MOVE_SPEED;

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

        // Jump
        if (input.isActionDown(InputAction.JUMP) && onGround) {
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
                // Try to add to inventory; spawn dropped item if full
                if (!inventory.addItem(broken) && droppedItemManager != null) {
                    droppedItemManager.spawn(hit[0], hit[1], hit[2], broken);
                }
                lastBlockActionMs = now;
            }
        } else if (input.isActionDown(InputAction.PLACE_BLOCK)) {
            int[] adjacent = raycast(false);
            if (adjacent != null && !occupiedByPlayer(adjacent[0], adjacent[1], adjacent[2])) {
                BlockType placed = inventory.getHotbarBlock(hotbarIndex);
                if (placed != BlockType.AIR) {
                    world.setBlock(adjacent[0], adjacent[1], adjacent[2], placed);
                    if (placed.behavior != null) {
                        placed.behavior.onPlace(world, adjacent[0], adjacent[1], adjacent[2]);
                    }
                    inventory.consumeHotbar(hotbarIndex);
                    lastBlockActionMs = now;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Item drop (Q key)
    // -------------------------------------------------------------------------

    private void handleDropItem() {
        if (!input.isActionJustPressed(InputAction.DROP_ITEM)) return;
        BlockType held = inventory.getHotbarBlock(hotbarIndex);
        if (held == BlockType.AIR || droppedItemManager == null) return;

        if (inventory.consumeHotbar(hotbarIndex)) {
            Vector3f dir = camera.getDirection();
            float dropX  = position.x + dir.x * 0.5f;
            float dropY  = position.y + EYE_HEIGHT - 0.3f;
            float dropZ  = position.z + dir.z * 0.5f;
            droppedItemManager.spawnThrown(dropX, dropY, dropZ, dir.x, dir.z, held);
        }
    }

    // -------------------------------------------------------------------------
    // Inventory toggle (E key)
    // -------------------------------------------------------------------------

    private void handleInventoryToggle() {
        if (input.isActionJustPressed(InputAction.OPEN_INVENTORY)) {
            inventoryOpen = !inventoryOpen;
            setMouseCaptured(!inventoryOpen);
        }
    }

    /**
     * Externally toggle the inventory state (e.g. called from Game when
     * Escape is pressed while inventory is open).
     */
    public void closeInventory() {
        if (inventoryOpen) {
            inventoryOpen = false;
            setMouseCaptured(true);
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
        if (inventoryOpen) return;
        for (int i = 0; i < HOTBAR_ACTIONS.length; i++) {
            if (input.isActionJustPressed(HOTBAR_ACTIONS[i])) {
                hotbarIndex = i;
            }
        }
        // Scroll wheel
        double scroll = input.consumeScroll();
        if (scroll != 0) {
            hotbarIndex = Math.floorMod(
                hotbarIndex - (int) Math.signum(scroll), Inventory.HOTBAR_SIZE);
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
    public Inventory getInventory()          { return inventory; }
    public boolean   isInventoryOpen()       { return inventoryOpen; }
    public BlockType getSelectedBlock()      { return inventory.getHotbarBlock(hotbarIndex); }

    /** Returns a snapshot of all 9 hotbar block types (AIR for empty slots). */
    public BlockType[] getHotbar() {
        BlockType[] result = new BlockType[Inventory.HOTBAR_SIZE];
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            result[i] = inventory.getHotbarBlock(i);
        }
        return result;
    }

    public int       getHotbarIndex()        { return hotbarIndex; }
    public int[]     getTargetedBlock()      { return hasTargetedBlock ? targetedBlock : null; }
    public int[]     getTargetedFaceNormal() { return hasTargetedBlock ? targetedFaceNormal : null; }

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

    /** Connects the dropped-item manager for block pickup/drop. */
    public void setDroppedItemManager(DroppedItemManager manager) {
        this.droppedItemManager = manager;
    }
}
