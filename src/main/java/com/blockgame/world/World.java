package com.blockgame.world;

import org.joml.Vector3f;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The game world: an infinite grid of {@link Chunk}s loaded around the player.
 *
 * <p>Terrain is generated procedurally using overlapping sine waves so there
 * are no external library dependencies for noise.  The generation strategy is
 * easy to replace with a proper noise function (e.g. Perlin/Simplex) later.
 */
public class World {

    /** How many chunks to keep loaded in each direction from the player chunk. */
    public static final int RENDER_DISTANCE = 4;

    private final Map<Long, Chunk> chunks = new HashMap<>();

    public World() {
        // Pre-generate the initial area so the player lands on solid ground
        for (int cx = -RENDER_DISTANCE; cx <= RENDER_DISTANCE; cx++) {
            for (int cz = -RENDER_DISTANCE; cz <= RENDER_DISTANCE; cz++) {
                loadChunk(cx, cz);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Chunk management
    // -------------------------------------------------------------------------

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public Chunk getChunk(int cx, int cz) {
        return chunks.get(chunkKey(cx, cz));
    }

    public Chunk getOrLoadChunk(int cx, int cz) {
        Chunk c = chunks.get(chunkKey(cx, cz));
        if (c == null) {
            c = loadChunk(cx, cz);
        }
        return c;
    }

    private Chunk loadChunk(int cx, int cz) {
        Chunk chunk = new Chunk(cx, cz);
        generateChunk(chunk);
        chunks.put(chunkKey(cx, cz), chunk);
        return chunk;
    }

    /** Unmodifiable view of all loaded chunks (used by the renderer). */
    public Map<Long, Chunk> getChunks() {
        return Collections.unmodifiableMap(chunks);
    }

    // -------------------------------------------------------------------------
    // World generation
    // -------------------------------------------------------------------------

    private void generateChunk(Chunk chunk) {
        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int wx = chunk.getWorldX(lx);
                int wz = chunk.getWorldZ(lz);

                int surfaceY = getTerrainHeight(wx, wz);

                // Bedrock / stone base
                for (int y = 0; y < surfaceY - 4; y++) {
                    chunk.setBlock(lx, y, lz, BlockType.STONE);
                }
                // Dirt layer
                for (int y = Math.max(0, surfaceY - 4); y < surfaceY - 1; y++) {
                    chunk.setBlock(lx, y, lz, BlockType.DIRT);
                }
                // Surface block
                if (surfaceY > 0) {
                    BlockType surface = (surfaceY > 80) ? BlockType.SNOW : BlockType.GRASS;
                    chunk.setBlock(lx, surfaceY - 1, lz, surface);
                }
            }
        }
        chunk.setDirty(true);
    }

    /**
     * Returns the terrain height (number of solid blocks) at the given world
     * XZ position using layered sine/cosine waves.
     */
    public int getTerrainHeight(int wx, int wz) {
        double h = 64.0;
        h += 10.0 * Math.sin(wx * 0.06)  * Math.cos(wz * 0.05);
        h +=  5.0 * Math.sin(wx * 0.13 + 1.1) * Math.cos(wz * 0.11 + 0.7);
        h +=  2.5 * Math.sin(wx * 0.28 + 2.3) * Math.cos(wz * 0.25 + 1.5);
        return (int) Math.max(2, h);
    }

    // -------------------------------------------------------------------------
    // Block accessors (world-space coordinates)
    // -------------------------------------------------------------------------

    public BlockType getBlock(int wx, int wy, int wz) {
        if (wy < 0 || wy >= Chunk.HEIGHT) return BlockType.AIR;
        int cx = Math.floorDiv(wx, Chunk.SIZE);
        int cz = Math.floorDiv(wz, Chunk.SIZE);
        int lx = Math.floorMod(wx, Chunk.SIZE);
        int lz = Math.floorMod(wz, Chunk.SIZE);
        Chunk chunk = getChunk(cx, cz);
        return (chunk == null) ? BlockType.AIR : chunk.getBlock(lx, wy, lz);
    }

    public void setBlock(int wx, int wy, int wz, BlockType type) {
        if (wy < 0 || wy >= Chunk.HEIGHT) return;
        int cx = Math.floorDiv(wx, Chunk.SIZE);
        int cz = Math.floorDiv(wz, Chunk.SIZE);
        int lx = Math.floorMod(wx, Chunk.SIZE);
        int lz = Math.floorMod(wz, Chunk.SIZE);

        Chunk chunk = getOrLoadChunk(cx, cz);
        chunk.setBlock(lx, wy, lz, type);

        // Also dirty adjacent chunks if we modified a border block
        if (lx == 0)            markDirty(cx - 1, cz);
        if (lx == Chunk.SIZE-1) markDirty(cx + 1, cz);
        if (lz == 0)            markDirty(cx, cz - 1);
        if (lz == Chunk.SIZE-1) markDirty(cx, cz + 1);
    }

    private void markDirty(int cx, int cz) {
        Chunk c = getChunk(cx, cz);
        if (c != null) c.setDirty(true);
    }

    // -------------------------------------------------------------------------
    // Per-frame update: load chunks close to the player
    // -------------------------------------------------------------------------

    public void update(Vector3f playerPos) {
        int pcx = Math.floorDiv((int) playerPos.x, Chunk.SIZE);
        int pcz = Math.floorDiv((int) playerPos.z, Chunk.SIZE);

        for (int cx = pcx - RENDER_DISTANCE; cx <= pcx + RENDER_DISTANCE; cx++) {
            for (int cz = pcz - RENDER_DISTANCE; cz <= pcz + RENDER_DISTANCE; cz++) {
                getOrLoadChunk(cx, cz);
            }
        }
    }

    public void cleanup() {
        chunks.clear();
    }
}
