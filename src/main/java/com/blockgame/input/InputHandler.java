package com.blockgame.input;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Collects raw GLFW input events and exposes a clean per-frame query API.
 *
 * <p>The {@link com.blockgame.Game} class registers the GLFW key callback;
 * mouse-button and cursor-position callbacks are registered here in the
 * constructor.
 *
 * <p>In addition to raw key/button queries, this class maintains a
 * {@link InputAction} binding table so that gameplay code can query named
 * actions (e.g. {@link InputAction#JUMP}) instead of raw GLFW constants.
 * Default bindings are set up automatically; use {@link #bindKey} or
 * {@link #bindMouseButton} to remap them at runtime.
 */
public class InputHandler {

    private final Set<Integer> keysDown        = new HashSet<>();
    private final Set<Integer> keysJustPressed = new HashSet<>();
    private final Set<Integer> mouseDown       = new HashSet<>();

    private double mouseX, mouseY;
    private double pendingScroll;

    // Action → GLFW key / mouse-button binding tables
    private final Map<InputAction, Integer> keyBindings   = new EnumMap<>(InputAction.class);
    private final Map<InputAction, Integer> mouseBindings = new EnumMap<>(InputAction.class);

    public InputHandler(long window) {
        glfwSetMouseButtonCallback(window, (win, btn, action, mods) -> {
            if (action == GLFW_PRESS)        mouseDown.add(btn);
            else if (action == GLFW_RELEASE) mouseDown.remove(btn);
        });

        glfwSetCursorPosCallback(window, (win, x, y) -> {
            mouseX = x;
            mouseY = y;
        });

        glfwSetScrollCallback(window, (win, xOff, yOff) -> pendingScroll += yOff);

        setupDefaultBindings();
    }

    // -------------------------------------------------------------------------
    // Default action bindings
    // -------------------------------------------------------------------------

    private void setupDefaultBindings() {
        keyBindings.put(InputAction.MOVE_FORWARD,  GLFW_KEY_W);
        keyBindings.put(InputAction.MOVE_BACKWARD, GLFW_KEY_S);
        keyBindings.put(InputAction.MOVE_LEFT,     GLFW_KEY_A);
        keyBindings.put(InputAction.MOVE_RIGHT,    GLFW_KEY_D);
        keyBindings.put(InputAction.JUMP,          GLFW_KEY_SPACE);
        keyBindings.put(InputAction.SPRINT,        GLFW_KEY_LEFT_CONTROL);
        keyBindings.put(InputAction.TOGGLE_CURSOR, GLFW_KEY_ESCAPE);
        keyBindings.put(InputAction.SAVE_WORLD,    GLFW_KEY_ENTER);
        keyBindings.put(InputAction.HOTBAR_1,      GLFW_KEY_1);
        keyBindings.put(InputAction.HOTBAR_2,      GLFW_KEY_2);
        keyBindings.put(InputAction.HOTBAR_3,      GLFW_KEY_3);
        keyBindings.put(InputAction.HOTBAR_4,      GLFW_KEY_4);
        keyBindings.put(InputAction.HOTBAR_5,      GLFW_KEY_5);
        keyBindings.put(InputAction.HOTBAR_6,      GLFW_KEY_6);
        keyBindings.put(InputAction.HOTBAR_7,      GLFW_KEY_7);
        keyBindings.put(InputAction.HOTBAR_8,      GLFW_KEY_8);

        mouseBindings.put(InputAction.BREAK_BLOCK, GLFW_MOUSE_BUTTON_LEFT);
        mouseBindings.put(InputAction.PLACE_BLOCK, GLFW_MOUSE_BUTTON_RIGHT);
    }

    // -------------------------------------------------------------------------
    // Runtime rebinding
    // -------------------------------------------------------------------------

    /**
     * Binds {@code action} to a keyboard key, removing any prior mouse binding.
     *
     * @param action  the action to rebind
     * @param glfwKey the GLFW key constant (e.g. {@code GLFW_KEY_F})
     */
    public void bindKey(InputAction action, int glfwKey) {
        keyBindings.put(action, glfwKey);
        mouseBindings.remove(action);
    }

    /**
     * Binds {@code action} to a mouse button, removing any prior key binding.
     *
     * @param action      the action to rebind
     * @param glfwButton  the GLFW mouse-button constant (e.g.
     *                    {@code GLFW_MOUSE_BUTTON_MIDDLE})
     */
    public void bindMouseButton(InputAction action, int glfwButton) {
        mouseBindings.put(action, glfwButton);
        keyBindings.remove(action);
    }

    // -------------------------------------------------------------------------
    // Called by Game.init() key callback
    // -------------------------------------------------------------------------

    public void onKey(int key, int action) {
        if (action == GLFW_PRESS) {
            keysDown.add(key);
            keysJustPressed.add(key);
        } else if (action == GLFW_RELEASE) {
            keysDown.remove(key);
        }
    }

    // -------------------------------------------------------------------------
    // Per-frame state (call endFrame() once per game loop iteration)
    // -------------------------------------------------------------------------

    /** Clear one-shot state; call once at the end of each game-loop iteration. */
    public void endFrame() {
        keysJustPressed.clear();
    }

    // -------------------------------------------------------------------------
    // Action query methods
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} while the key or mouse button bound to {@code action}
     * is held down.
     */
    public boolean isActionDown(InputAction action) {
        Integer key = keyBindings.get(action);
        if (key != null) return keysDown.contains(key);
        Integer btn = mouseBindings.get(action);
        if (btn != null) return mouseDown.contains(btn);
        return false;
    }

    /**
     * Returns {@code true} only in the frame the key bound to {@code action}
     * was first pressed.  Mouse-button actions always return {@code false}
     * here; use {@link #isActionDown} for continuous mouse input.
     */
    public boolean isActionJustPressed(InputAction action) {
        Integer key = keyBindings.get(action);
        if (key != null) return keysJustPressed.contains(key);
        return false;
    }

    // -------------------------------------------------------------------------
    // Raw query methods (kept for low-level use)
    // -------------------------------------------------------------------------

    /** Returns {@code true} while the key is held down. */
    public boolean isKeyDown(int key) {
        return keysDown.contains(key);
    }

    /** Returns {@code true} only in the frame the key was first pressed. */
    public boolean isKeyJustPressed(int key) {
        return keysJustPressed.contains(key);
    }

    /** Returns {@code true} while the mouse button is held down. */
    public boolean isMouseButtonDown(int button) {
        return mouseDown.contains(button);
    }

    public double getMouseX()  { return mouseX; }
    public double getMouseY()  { return mouseY; }

    /**
     * Returns the accumulated scroll delta since the last call, then resets it.
     */
    public double consumeScroll() {
        double v = pendingScroll;
        pendingScroll = 0;
        return v;
    }
}

