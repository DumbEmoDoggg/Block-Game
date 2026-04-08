package com.blockgame.world;

import com.blockgame.Saveable;
import org.joml.Vector3f;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The game world: an infinite grid of {@link Chunk}s loaded around the player.
 *
 * <p>Terrain is generated procedurally using multi-octave Perlin noise (fBm).
 * Generation is split into a {@link WorldGenerator} (base terrain) and an
 * ordered list of {@link WorldFeature}s (decoration passes such as caves).
 * The active {@link BiomeProvider} controls surface block selection.
 */
public class World implements Saveable {

    /** How many chunks to keep loaded in each direction from the player chunk. */
    public static final int RENDER_DISTANCE = 25;

    private final Map<Long, Chunk> chunks = new HashMap<>();

    // Noise generator shared with world features so identical seeds produce
    // identical terrain regardless of which object samples first.
    private final PerlinNoise noise = new PerlinNoise(12345L);

    // Modular generation pipeline
    private final BiomeProvider   biomeProvider;
    private final WorldGenerator  generator;
    private final List<WorldFeature> features;

    public World() {
        this.biomeProvider = new DefaultBiomeProvider(this);
        this.generator     = new DefaultWorldGenerator();
        this.features      = new ArrayList<>();
        this.features.add(new CaveFeature(noise));

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
        generator.generate(chunk, this);
        for (WorldFeature feature : features) {
            feature.apply(chunk, this);
        }
        chunk.setDirty(true);
    }

    /**
     * Returns the terrain height (number of solid blocks) at the given world
     * XZ position.
     *
     * <p>A low-frequency continental noise is passed through a hyperbolic
     * tangent to create near-flat plateaus separated by steep cliff faces.
     * A medium-frequency hill noise adds distinct hills on top of the
     * continental base.  A higher-frequency detail noise adds surface
     * roughness.  The combined result is mapped to a height in roughly [30, 100].
     */
    public int getTerrainHeight(int wx, int wz) {
        // Continental shape: large slow waves → tanh shapes transitions between elevations.
        double continental = noise.octaveNoise(wx * 0.003, wz * 0.003, 3, 0.5, 2.0);
        double steep = Math.tanh(continental * 1.6);
        // Medium-frequency hill noise adds prominent, distinct hills.
        double hills = noise.octaveNoise(wx * 0.008, wz * 0.008, 2, 0.5, 2.0) * 0.4;
        // Fine surface detail layered on top.
        double detail = noise.octaveNoise(wx * 0.020, wz * 0.020, 4, 0.45, 2.0) * 0.15;
        double h = 65.0 + (steep + hills + detail) * 30.0;
        return (int) Math.max(2, h);
    }

    /** Returns the active {@link BiomeProvider} used during world generation. */
    public BiomeProvider getBiomeProvider() {
        return biomeProvider;
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

        // Unload chunks that have moved out of range to bound memory usage
        int unloadDistance = RENDER_DISTANCE + 4;
        chunks.entrySet().removeIf(e -> {
            Chunk c = e.getValue();
            return Math.abs(c.chunkX - pcx) > unloadDistance
                || Math.abs(c.chunkZ - pcz) > unloadDistance;
        });
    }

    public void cleanup() {
        chunks.clear();
    }

    // -------------------------------------------------------------------------
    // Saveable – chunk data only; player position is saved separately
    // -------------------------------------------------------------------------

    /**
     * Writes all currently loaded chunks to {@code out}.
     *
     * <p>Format:
     * <pre>
     *   int  chunkCount
     *   for each chunk:
     *     int  chunkX
     *     int  chunkZ
     *     byte[SIZE * HEIGHT * SIZE]  block data
     * </pre>
     */
    @Override
    public void save(DataOutputStream out) throws IOException {
        out.writeInt(chunks.size());
        for (Chunk chunk : chunks.values()) {
            out.writeInt(chunk.chunkX);
            out.writeInt(chunk.chunkZ);
            chunk.saveToStream(out);
        }
    }

    /**
     * Replaces the current world state with chunks read from {@code in}.
     * Any previously loaded chunks are discarded.
     */
    @Override
    public void load(DataInputStream in) throws IOException {
        chunks.clear();
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            int cx = in.readInt();
            int cz = in.readInt();
            Chunk chunk = new Chunk(cx, cz);
            chunk.loadFromStream(in);
            chunks.put(chunkKey(cx, cz), chunk);
        }
    }
}

