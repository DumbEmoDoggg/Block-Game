package com.blockgame.rendering;

import com.blockgame.world.BlockType;
import com.blockgame.world.Chunk;
import com.blockgame.world.World;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Builds and renders the triangle mesh for a single {@link Chunk}.
 *
 * <p>Vertex layout (10 floats per vertex):
 * <pre>
 *   position (3) | color (3) | normal (3) | skyLight (1)
 * </pre>
 *
 * <p>Only the visible faces of solid blocks are emitted (faces shared with
 * another solid block are culled).  Face brightness is pre-baked into the
 * vertex color: top = 100 %, north/south = 80 %, east/west = 65 %,
 * bottom = 50 %.
 */
public class ChunkMesh {

    private static final int FLOATS_PER_VERTEX = 10;
    private static final int VERTICES_PER_FACE = 6; // 2 triangles × 3 vertices

    // Per-face brightness multipliers (simple ambient occlusion approximation)
    private static final float BRIGHT_TOP    = 1.00f;
    private static final float BRIGHT_NS     = 0.80f;
    private static final float BRIGHT_EW     = 0.65f;
    private static final float BRIGHT_BOTTOM = 0.50f;

    private int vao = 0;
    private int vbo = 0;
    private int vertexCount = 0;

    // -------------------------------------------------------------------------
    // Mesh generation
    // -------------------------------------------------------------------------

    /**
     * (Re)builds the mesh from the chunk's current block data.
     * Must be called from the OpenGL thread.
     */
    public void build(Chunk chunk, World world) {
        List<Float> buf = new ArrayList<>(4096);

        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int ly = 0; ly < Chunk.HEIGHT; ly++) {
                for (int lz = 0; lz < Chunk.SIZE; lz++) {
                    BlockType block = chunk.getBlock(lx, ly, lz);
                    if (!block.solid) continue;

                    int wx = chunk.getWorldX(lx);
                    int wz = chunk.getWorldZ(lz);

                    float skyLight = computeSkyLight(chunk, lx, ly, lz);

                    // Emit a face only when the adjacent block is transparent
                    if (isTransparent(world, chunk, lx, ly + 1, lz)) addFace(buf, wx, ly, wz, Face.TOP,    block, skyLight);
                    if (isTransparent(world, chunk, lx, ly - 1, lz)) addFace(buf, wx, ly, wz, Face.BOTTOM, block, skyLight);
                    if (isTransparent(world, chunk, lx, ly, lz - 1)) addFace(buf, wx, ly, wz, Face.NORTH,  block, skyLight);
                    if (isTransparent(world, chunk, lx, ly, lz + 1)) addFace(buf, wx, ly, wz, Face.SOUTH,  block, skyLight);
                    if (isTransparent(world, chunk, lx - 1, ly, lz)) addFace(buf, wx, ly, wz, Face.WEST,   block, skyLight);
                    if (isTransparent(world, chunk, lx + 1, ly, lz)) addFace(buf, wx, ly, wz, Face.EAST,   block, skyLight);
                }
            }
        }

        float[] data = new float[buf.size()];
        for (int i = 0; i < buf.size(); i++) data[i] = buf.get(i);
        upload(data);
    }

    // -------------------------------------------------------------------------
    // Transparency check (handles cross-chunk boundaries)
    // -------------------------------------------------------------------------

    private boolean isTransparent(World world, Chunk chunk, int lx, int ly, int lz) {
        if (lx >= 0 && lx < Chunk.SIZE && lz >= 0 && lz < Chunk.SIZE
                && ly >= 0 && ly < Chunk.HEIGHT) {
            return chunk.getBlock(lx, ly, lz).isTransparent();
        }
        int wx = chunk.getWorldX(lx);
        int wz = chunk.getWorldZ(lz);
        return world.getBlock(wx, ly, wz).isTransparent();
    }

    // -------------------------------------------------------------------------
    // Sky-light computation
    // -------------------------------------------------------------------------

    /**
     * Returns 1.0 if the block column above (lx, ly, lz) is clear all the way
     * to the top of the chunk (i.e. the block is exposed to skylight), or 0.0
     * if any solid block sits above it (i.e. the block is underground/in a cave).
     */
    private float computeSkyLight(Chunk chunk, int lx, int ly, int lz) {
        for (int y = ly + 1; y < Chunk.HEIGHT; y++) {
            if (!chunk.getBlock(lx, y, lz).isTransparent()) return 0.0f;
        }
        return 1.0f;
    }

    // -------------------------------------------------------------------------
    // Face geometry
    // -------------------------------------------------------------------------

    private enum Face { TOP, BOTTOM, NORTH, SOUTH, WEST, EAST }

    private void addFace(List<Float> buf, int x, int y, int z, Face face, BlockType block, float skyLight) {
        float br, bg, bb;   // base color
        float bright;

        switch (face) {
            case TOP:
                // For GRASS, top face uses a vivid green
                br = block.r; bg = block.g; bb = block.b;
                bright = BRIGHT_TOP;
                break;
            case BOTTOM:
                br = block.r; bg = block.g; bb = block.b;
                bright = BRIGHT_BOTTOM;
                break;
            case NORTH: case SOUTH:
                // GRASS sides show a dirt-like color
                if (block == BlockType.GRASS) { br = 0.55f; bg = 0.35f; bb = 0.15f; }
                else { br = block.r; bg = block.g; bb = block.b; }
                bright = BRIGHT_NS;
                break;
            default: // WEST / EAST
                if (block == BlockType.GRASS) { br = 0.55f; bg = 0.35f; bb = 0.15f; }
                else { br = block.r; bg = block.g; bb = block.b; }
                bright = BRIGHT_EW;
                break;
        }

        float r = br * bright;
        float g = bg * bright;
        float b = bb * bright;

        float[] quad   = quad(x, y, z, face);
        float[] normal = normal(face);

        // Two triangles: indices 0,1,2 and 0,2,3
        for (int i : new int[]{0, 1, 2, 0, 2, 3}) {
            buf.add(quad[i * 3]);
            buf.add(quad[i * 3 + 1]);
            buf.add(quad[i * 3 + 2]);
            buf.add(r); buf.add(g); buf.add(b);
            buf.add(normal[0]); buf.add(normal[1]); buf.add(normal[2]);
            buf.add(skyLight);
        }
    }

    /** Returns the 4 corner positions of a face as a flat float[12] (4 × xyz). */
    private float[] quad(int x, int y, int z, Face face) {
        switch (face) {
            case TOP:    return new float[]{ x,   y+1, z,   x,   y+1, z+1, x+1, y+1, z+1, x+1, y+1, z   };
            case BOTTOM: return new float[]{ x,   y,   z,   x+1, y,   z,   x+1, y,   z+1, x,   y,   z+1 };
            case NORTH:  return new float[]{ x,   y,   z,   x,   y+1, z,   x+1, y+1, z,   x+1, y,   z   };
            case SOUTH:  return new float[]{ x,   y,   z+1, x+1, y,   z+1, x+1, y+1, z+1, x,   y+1, z+1 };
            case WEST:   return new float[]{ x,   y,   z,   x,   y,   z+1, x,   y+1, z+1, x,   y+1, z   };
            case EAST:   return new float[]{ x+1, y,   z,   x+1, y+1, z,   x+1, y+1, z+1, x+1, y,   z+1 };
            default:     return new float[12];
        }
    }

    private float[] normal(Face face) {
        switch (face) {
            case TOP:    return new float[]{ 0,  1,  0 };
            case BOTTOM: return new float[]{ 0, -1,  0 };
            case NORTH:  return new float[]{ 0,  0, -1 };
            case SOUTH:  return new float[]{ 0,  0,  1 };
            case WEST:   return new float[]{-1,  0,  0 };
            case EAST:   return new float[]{ 1,  0,  0 };
            default:     return new float[]{ 0,  1,  0 };
        }
    }

    // -------------------------------------------------------------------------
    // GPU upload
    // -------------------------------------------------------------------------

    private void upload(float[] data) {
        cleanup();
        if (data.length == 0) return;

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        FloatBuffer fb = BufferUtils.createFloatBuffer(data.length);
        fb.put(data).flip();
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);

        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, 6L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 9L * Float.BYTES);
        glEnableVertexAttribArray(3);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        vertexCount = data.length / FLOATS_PER_VERTEX;
    }

    // -------------------------------------------------------------------------
    // Render & cleanup
    // -------------------------------------------------------------------------

    public void render() {
        if (vao == 0 || vertexCount == 0) return;
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        glBindVertexArray(0);
    }

    public void cleanup() {
        if (vao != 0) { glDeleteVertexArrays(vao); vao = 0; }
        if (vbo != 0) { glDeleteBuffers(vbo);       vbo = 0; }
        vertexCount = 0;
    }

    public boolean isEmpty() { return vertexCount == 0; }
}
