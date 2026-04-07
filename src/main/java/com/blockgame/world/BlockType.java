package com.blockgame.world;

/**
 * All block types supported by the game.
 *
 * <p>Each type carries:
 * <ul>
 *   <li>a unique integer id used for compact in-chunk storage</li>
 *   <li>RGB colour components for the top face (and for solid-colour blocks, all faces)</li>
 *   <li>a {@code solid} flag controlling collision and face-culling</li>
 * </ul>
 *
 * <p>New block types can be added here without touching any other class.
 */
public enum BlockType {

    AIR   (0, 0.00f, 0.00f, 0.00f, false),
    GRASS (1, 0.30f, 0.75f, 0.25f, true),
    DIRT  (2, 0.55f, 0.35f, 0.15f, true),
    STONE (3, 0.60f, 0.60f, 0.60f, true),
    WOOD  (4, 0.55f, 0.38f, 0.18f, true),
    LEAVES(5, 0.15f, 0.55f, 0.15f, true),
    SAND  (6, 0.90f, 0.80f, 0.50f, true),
    SNOW  (7, 0.95f, 0.95f, 0.98f, true);

    /** Compact id stored in chunk byte arrays. Max 255 types. */
    public final int id;

    /** Base RGB colour of this block (top face uses full brightness). */
    public final float r, g, b;

    /** Whether this block has a physical volume (used for collision and face culling). */
    public final boolean solid;

    BlockType(int id, float r, float g, float b, boolean solid) {
        this.id    = id;
        this.r     = r;
        this.g     = g;
        this.b     = b;
        this.solid = solid;
    }

    /** @return {@code true} if light and geometry can pass through this block. */
    public boolean isTransparent() {
        return !solid;
    }

    /**
     * Look up a BlockType by its numeric id.
     * Returns {@link #AIR} for any unknown id.
     */
    public static BlockType fromId(int id) {
        for (BlockType t : values()) {
            if (t.id == id) return t;
        }
        return AIR;
    }
}
