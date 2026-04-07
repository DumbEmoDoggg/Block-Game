package com.blockgame.input;

import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Collects raw GLFW input events and exposes a clean per-frame query API.
 *
 * <p>The {@link com.blockgame.Game} class registers the GLFW key callback;
 * mouse-button and cursor-position callbacks are registered here in the
 * constructor.
 */
public class InputHandler {

    private final Set<Integer> keysDown      = new HashSet<>();
    private final Set<Integer> keysJustPressed = new HashSet<>();
    private final Set<Integer> mouseDown     = new HashSet<>();

    private double mouseX, mouseY;
    private double pendingScroll;

    public InputHandler(long window) {
        glfwSetMouseButtonCallback(window, (win, btn, action, mods) -> {
            if (action == GLFW_PRESS)   mouseDown.add(btn);
            else if (action == GLFW_RELEASE) mouseDown.remove(btn);
        });

        glfwSetCursorPosCallback(window, (win, x, y) -> {
            mouseX = x;
            mouseY = y;
        });

        glfwSetScrollCallback(window, (win, xOff, yOff) -> pendingScroll += yOff);
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
    // Query methods
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
