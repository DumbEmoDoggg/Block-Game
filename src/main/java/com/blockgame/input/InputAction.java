package com.blockgame.input;

/**
 * Named player/game actions that can be mapped to hardware inputs.
 *
 * <p>Gameplay code queries actions by name rather than raw GLFW constants,
 * making it trivial to re-bind keys without touching multiple files.
 *
 * <p>Default bindings are established by {@link InputHandler} and can be
 * changed at any time via {@link InputHandler#bindKey} or
 * {@link InputHandler#bindMouseButton}.
 */
public enum InputAction {
    MOVE_FORWARD,
    MOVE_BACKWARD,
    MOVE_LEFT,
    MOVE_RIGHT,
    JUMP,
    SPRINT,
    BREAK_BLOCK,
    PLACE_BLOCK,
    TOGGLE_CURSOR,
    SAVE_WORLD,
    HOTBAR_1,
    HOTBAR_2,
    HOTBAR_3,
    HOTBAR_4,
    HOTBAR_5,
    HOTBAR_6,
    HOTBAR_7,
    HOTBAR_8,
    HOTBAR_9,
    OPEN_INVENTORY,
    DROP_ITEM
}
