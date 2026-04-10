package com.blockgame.world;

/**
 * All block types supported by the game.
 *
 * <p>Each type carries:
 * <ul>
 *   <li>a unique integer id used for compact in-chunk storage</li>
 *   <li>RGB colour components for the top face (and for solid-colour blocks, all faces)</li>
 *   <li>a {@code solid} flag controlling collision and face-culling</li>
 *   <li>an optional {@link BlockBehavior} for interactive / dynamic blocks</li>
 * </ul>
 *
 * <p>New block types can be added here without touching any other class.
 * To give a block special behaviour (gravity, fluid flow, crop growth, …),
 * pass a {@link BlockBehavior} implementation as the final constructor
 * argument.
 */
public enum BlockType {

    AIR      (0,  0.00f, 0.00f, 0.00f, false, false),
    GRASS    (1,  0.30f, 0.75f, 0.25f, true,  false),
    DIRT     (2,  0.55f, 0.35f, 0.15f, true,  false),
    STONE    (3,  0.60f, 0.60f, 0.60f, true,  false),
    WOOD     (4,  0.55f, 0.38f, 0.18f, true,  false),
    LEAVES   (5,  0.15f, 0.55f, 0.15f, true,  true),
    /** Falls under gravity into any non-solid block (air, water, etc.). */
    SAND     (6,  0.90f, 0.80f, 0.50f, true,  false, new FallingBlockBehavior()),
    SNOW     (7,  0.95f, 0.95f, 0.98f, true,  false),
    PLANKS   (8,  0.80f, 0.55f, 0.28f, true,  false),
    /** Still water – non-solid (passable) and transparent so adjacent faces are rendered. */
    WATER    (9,  0.25f, 0.46f, 0.89f, false, true,  new WaterBehavior()),
    /** Unbreakable foundation layer at the bottom of the world. */
    BEDROCK  (10, 0.18f, 0.18f, 0.18f, true,  false, null, false),
    /** Loose stone aggregate found underground and on underwater floors; falls under gravity. */
    GRAVEL   (11, 0.50f, 0.48f, 0.45f, true,  false, new FallingBlockBehavior()),
    /** Stone with black coal seams. */
    COAL_ORE (12, 0.42f, 0.42f, 0.42f, true,  false),
    /** Stone with rusty iron inclusions. */
    IRON_ORE (13, 0.62f, 0.52f, 0.40f, true,  false),
    /** Stone with shimmering gold inclusions. */
    GOLD_ORE (14, 0.65f, 0.60f, 0.28f, true,  false),
    /** Rough stone with a cracked surface; classic building material. */
    COBBLESTONE       (15, 0.55f, 0.55f, 0.55f, true,  false),
    /** Cobblestone overgrown with moss. */
    MOSSY_COBBLESTONE (16, 0.45f, 0.55f, 0.40f, true,  false),
    /** Transparent glass pane – solid for collision, see-through for rendering. */
    GLASS             (17, 0.95f, 0.95f, 1.00f, true,  true),
    /** Classic fired-clay bricks. */
    BRICKS            (18, 0.70f, 0.35f, 0.28f, true,  false),
    /** Explosive block with distinct top, side and bottom faces. */
    TNT               (19, 0.75f, 0.15f, 0.15f, true,  false),
    /** Wood planks on top/bottom, filled shelves on sides. */
    BOOKSHELF         (20, 0.70f, 0.52f, 0.30f, true,  false),
    /** Porous yellow block that absorbs water. */
    SPONGE            (21, 0.90f, 0.88f, 0.35f, true,  false),
    /** Solid block of gold. */
    GOLD_BLOCK        (22, 0.95f, 0.80f, 0.20f, true,  false),
    /** Solid block of iron. */
    IRON_BLOCK        (23, 0.85f, 0.85f, 0.85f, true,  false),
    /** Polished stone with a smooth finish; slab texture on sides. */
    SMOOTH_STONE      (24, 0.65f, 0.65f, 0.65f, true,  false),
    /** Single stone slab – full-height block with slab texture on sides. */
    STONE_SLAB        (25, 0.65f, 0.65f, 0.65f, true,  false),
    /** Yellow flower; cross-shaped, non-solid decoration. */
    DANDELION         (26, 1.00f, 0.95f, 0.10f, false, true,  null, true, true),
    /** Red flower; cross-shaped, non-solid decoration. */
    POPPY             (27, 0.95f, 0.12f, 0.12f, false, true,  null, true, true),
    /** Small brown mushroom; cross-shaped, non-solid decoration. */
    BROWN_MUSHROOM    (28, 0.60f, 0.40f, 0.20f, false, true,  null, true, true),
    /** Small red mushroom; cross-shaped, non-solid decoration. */
    RED_MUSHROOM      (29, 0.90f, 0.15f, 0.15f, false, true,  null, true, true),
    /** Extremely dense volcanic glass; very dark purple. */
    OBSIDIAN          (30, 0.12f, 0.07f, 0.18f, true,  false),
    /** Young oak tree that grows into a full tree over time. */
    OAK_SAPLING       (31, 0.20f, 0.55f, 0.10f, false, true,  new SaplingBehavior(), true, true);

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

    /**
     * Whether a player can break this block.
     * {@code false} for indestructible blocks such as {@link #BEDROCK}.
     */
    public final boolean breakable;

    /**
     * Optional lifecycle callbacks for this block type (place, break, tick).
     * {@code null} for blocks with no special behaviour (the common case).
     */
    public final BlockBehavior behavior;

    /**
     * Whether this block is a cross-shaped plant (e.g. flowers, mushrooms).
     * Plant blocks are non-solid and transparent; they are rendered as two
     * perpendicular diagonal quads rather than a solid cube.
     */
    public final boolean plant;

    BlockType(int id, float r, float g, float b, boolean solid, boolean transparent) {
        this(id, r, g, b, solid, transparent, null, true, false);
    }

    BlockType(int id, float r, float g, float b, boolean solid, boolean transparent,
              BlockBehavior behavior) {
        this(id, r, g, b, solid, transparent, behavior, true, false);
    }

    BlockType(int id, float r, float g, float b, boolean solid, boolean transparent,
              BlockBehavior behavior, boolean breakable) {
        this(id, r, g, b, solid, transparent, behavior, breakable, false);
    }

    BlockType(int id, float r, float g, float b, boolean solid, boolean transparent,
              BlockBehavior behavior, boolean breakable, boolean plant) {
        this.id          = id;
        this.r           = r;
        this.g           = g;
        this.b           = b;
        this.solid       = solid;
        this.transparent = transparent;
        this.behavior    = behavior;
        this.breakable   = breakable;
        this.plant       = plant;
    }

    /** @return {@code true} if light and geometry can pass through this block. */
    public boolean isTransparent() {
        return !solid || transparent;
    }

    /** @return {@code true} if this block is a cross-shaped plant decoration. */
    public boolean isPlant() {
        return plant;
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
