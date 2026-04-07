package com.blockgame.world;

/**
 * A 16×16×128 region of the world.
 *
 * <p>Blocks are stored as bytes (supporting up to 255 distinct block type ids).
 * The {@code dirty} flag signals the renderer that the mesh needs to be rebuilt.
 */
public class Chunk {

    /** Horizontal size of a chunk (X and Z axes). */
    public static final int SIZE   = 16;
    /** Vertical size of a chunk (Y axis). */
    public static final int HEIGHT = 128;

    /** Chunk grid coordinates (not block coordinates). */
    public final int chunkX, chunkZ;

    /** Raw block storage: [localX][y][localZ], values are BlockType ids. */
    private final byte[][][] blocks;

    /** Whether this chunk's mesh needs to be rebuilt before the next render. */
    private boolean dirty = true;

    public Chunk(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.blocks = new byte[SIZE][HEIGHT][SIZE];
    }

    // -------------------------------------------------------------------------
    // Block accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the block at local coordinates.
     * Returns {@link BlockType#AIR} for out-of-bounds coordinates.
     */
    public BlockType getBlock(int lx, int y, int lz) {
        if (lx < 0 || lx >= SIZE || y < 0 || y >= HEIGHT || lz < 0 || lz >= SIZE) {
            return BlockType.AIR;
        }
        return BlockType.fromId(blocks[lx][y][lz] & 0xFF);
    }

    /**
     * Sets the block at local coordinates and marks the chunk dirty.
     * Out-of-bounds coordinates are silently ignored.
     */
    public void setBlock(int lx, int y, int lz, BlockType type) {
        if (lx < 0 || lx >= SIZE || y < 0 || y >= HEIGHT || lz < 0 || lz >= SIZE) {
            return;
        }
        blocks[lx][y][lz] = (byte) type.id;
        dirty = true;
    }

    // -------------------------------------------------------------------------
    // Coordinate helpers
    // -------------------------------------------------------------------------

    /** Converts a local X coordinate to the corresponding world X coordinate. */
    public int getWorldX(int localX) {
        return chunkX * SIZE + localX;
    }

    /** Converts a local Z coordinate to the corresponding world Z coordinate. */
    public int getWorldZ(int localZ) {
        return chunkZ * SIZE + localZ;
    }

    // -------------------------------------------------------------------------
    // Dirty flag
    // -------------------------------------------------------------------------

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }
}
