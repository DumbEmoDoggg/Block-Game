package com.blockgame;

/**
 * A discrete unit of per-frame game logic.
 *
 * <p>Implementations cover all independently-tickable subsystems: player
 * physics, world streaming, rendering, etc.  The main game loop iterates a
 * {@link java.util.List} of {@code GameSystem}s each frame, which means new
 * systems (day/night cycle, mob AI, weather, …) can be added without touching
 * the loop itself.
 */
public interface GameSystem {

    /**
     * Advances this system by {@code dt} seconds.
     *
     * @param dt elapsed time in seconds since the last frame (capped to avoid
     *           spiral-of-death on lag spikes)
     */
    void update(float dt);

    /**
     * Releases all resources owned by this system.
     * Called once when the game is shutting down, in reverse registration order.
     */
    default void cleanup() {}
}
