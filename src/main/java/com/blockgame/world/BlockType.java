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

    AIR   (0, 0.00f, 0.00f, 0.00f, false, false),
    GRASS (1, 0.30f, 0.75f, 0.25f, true,  false),
    DIRT  (2, 0.55f, 0.35f, 0.15f, true,  false),
    STONE (3, 0.60f, 0.60f, 0.60f, true,  false),
    WOOD  (4, 0.55f, 0.38f, 0.18f, true,  false),
    LEAVES(5, 0.15f, 0.55f, 0.15f, true,  true),
    SAND  (6, 0.90f, 0.80f, 0.50f, true,  false),
    SNOW  (7, 0.95f, 0.95f, 0.98f, true,  false),
    PLANKS(8, 0.80f, 0.55f, 0.28f, true,  false);

    /** Compact id stored in chunk byte arrays. Max 255 types. */
    public final int id;

    /** Base RGB colour of this block (top face uses full brightness). */
    public final float r, g, b;

    /** Whether this block has a physical volume (used for collision and face culling). */
    public final boolean solid;

    /**
     * Whether this block renders transparently (e.g. leaves with gaps).
     * Transparent blocks are treated as see-through for face-culling so that
     * adjacent faces are still emitted, and their texture may contain
     * fully-transparent pixels that are discarded by the fragment shader.
     * Note: a block can be {@code solid} and {@code transparent} simultaneously
     * (e.g. leaves block collisions but renders with see-through gaps).
     */
    public final boolean transparent;

    BlockType(int id, float r, float g, float b, boolean solid, boolean transparent) {
        this.id          = id;
        this.r           = r;
        this.g           = g;
        this.b           = b;
        this.solid       = solid;
        this.transparent = transparent;
    }

    /** @return {@code true} if light and geometry can pass through this block. */
    public boolean isTransparent() {
        return !solid || transparent;
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
