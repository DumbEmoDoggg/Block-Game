package com.blockgame.world;

import org.joml.Vector3f;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The game world: an infinite grid of {@link Chunk}s loaded around the player.
 *
 * <p>Terrain is generated procedurally using multi-octave Perlin noise (fBm).
 */
public class World {

    /** How many chunks to keep loaded in each direction from the player chunk. */
    public static final int RENDER_DISTANCE = 8;

    private final Map<Long, Chunk> chunks = new HashMap<>();
    private final PerlinNoise noise = new PerlinNoise(12345L);

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
                    BlockType surface;
                    if (surfaceY > 80) {
                        surface = BlockType.SNOW;
                    } else if (surfaceY < 50) {
                        surface = BlockType.SAND;  // beach / desert low-land
                    } else {
                        surface = BlockType.GRASS;
                    }
                    chunk.setBlock(lx, surfaceY - 1, lz, surface);
                }
            }
        }
        chunk.setDirty(true);
    }

    /**
     * Returns the terrain height (number of solid blocks) at the given world
     * XZ position.
     *
     * <p>A low-frequency continental noise is passed through a hyperbolic
     * tangent to create near-flat plateaus separated by steep cliff faces.
     * A second, higher-frequency detail noise adds surface roughness on top.
     * The combined result is mapped to a height in roughly [35, 95].
     */
    public int getTerrainHeight(int wx, int wz) {
        // Continental shape: large slow waves → tanh steepens transitions into cliffs
        double continental = noise.octaveNoise(wx * 0.003, wz * 0.003, 2, 0.5, 2.0);
        double steep = Math.tanh(continental * 2.8);
        // Fine surface detail layered on top
        double detail = noise.octaveNoise(wx * 0.018, wz * 0.018, 3, 0.4, 2.0) * 0.15;
        double h = 65.0 + (steep + detail) * 25.0;
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

    // -------------------------------------------------------------------------
    // Save / Load
    // -------------------------------------------------------------------------

    /**
     * Serialises all currently loaded chunks and the player position to a
     * binary file.
     *
     * <p>Format:
     * <pre>
     *   int   chunkCount
     *   for each chunk:
     *     int  chunkX
     *     int  chunkZ
     *     byte[SIZE * HEIGHT * SIZE]  block data (lx outer, y middle, lz inner)
     *   float  playerX
     *   float  playerY
     *   float  playerZ
     * </pre>
     *
     * @param file      destination file (parent directories are created if absent)
     * @param playerPos current player position to persist
     * @throws IOException on any I/O error
     */
    public void save(Path file, Vector3f playerPos) throws IOException {
        Files.createDirectories(file.getParent());
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(chunks.size());
            for (Chunk chunk : chunks.values()) {
                out.writeInt(chunk.chunkX);
                out.writeInt(chunk.chunkZ);
                chunk.saveToStream(out);
            }
            out.writeFloat(playerPos.x);
            out.writeFloat(playerPos.y);
            out.writeFloat(playerPos.z);
        }
    }

    /**
     * Replaces the current world state with data read from {@code file}.
     * Any previously loaded chunks are discarded.
     *
     * @param file source file written by {@link #save}
     * @return the player position that was stored in the save file
     * @throws IOException on any I/O error
     */
    public Vector3f load(Path file) throws IOException {
        chunks.clear();
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int cx = in.readInt();
                int cz = in.readInt();
                Chunk chunk = new Chunk(cx, cz);
                chunk.loadFromStream(in);
                chunks.put(chunkKey(cx, cz), chunk);
            }
            float px = in.readFloat();
            float py = in.readFloat();
            float pz = in.readFloat();
            return new Vector3f(px, py, pz);
        }
    }
}
