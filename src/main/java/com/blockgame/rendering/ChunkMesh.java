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
 * <p>Vertex layout (9 floats per vertex):
 * <pre>
 *   position (3) | texCoord (2) | normal (3) | skyLight (1)
 * </pre>
 *
 * <p>Only the visible faces of solid blocks are emitted (faces shared with
 * another solid block are culled).  Face brightness is applied in the shader
 * using pre-baked per-face multipliers: top = 100 %, north/south = 80 %,
 * east/west = 65 %, bottom = 50 %.
 *
 * <p>Water faces are built into a separate VAO/VBO ({@code waterVao}/{@code waterVbo})
 * so they can be rendered in a dedicated transparent pass.  A water face is only
 * emitted when the adjacent block is transparent <em>and</em> not water (i.e. at
 * water–air boundaries, never at water–water boundaries).
 */
public class ChunkMesh {

    private static final int FLOATS_PER_VERTEX = 9;
    private static final int VERTICES_PER_FACE = 6; // 2 triangles × 3 vertices

    /** Triangle indices for a quad (two CCW triangles sharing vertices 0 and 2). */
    private static final int[] TRIANGLE_INDICES = {0, 1, 2, 0, 2, 3};

    // Solid-block mesh
    private int vao = 0;
    private int vbo = 0;
    private int vertexCount = 0;

    // Water-face mesh (rendered separately with alpha blending)
    private int waterVao = 0;
    private int waterVbo = 0;
    private int waterVertexCount = 0;

    /**
     * UV V-range for one animation frame inside water_still.png (16 px / 512 px).
     * The water fragment shader advances through frames by offsetting V at runtime.
     */
    private static final float WATER_FRAME_V = 1.0f / 32.0f;

    // -------------------------------------------------------------------------
    // Mesh generation
    // -------------------------------------------------------------------------

    /**
     * (Re)builds the mesh from the chunk's current block data at the given
     * level of detail.
     *
     * <p>LOD 0 – full detail: every exposed face of every solid block is emitted.
     * LOD 1 – surface only: only the top face of the topmost solid block per
     * column is emitted.  This dramatically reduces the vertex count for chunks
     * that are far from the player where side-face and underground detail is not
     * perceptible.
     *
     * <p>Must be called from the OpenGL thread.
     *
     * @param chunk the chunk to mesh
     * @param world the world (used to query neighbour chunks for face culling)
     * @param lod   0 = full detail, 1 = surface only
     */
    public void build(Chunk chunk, World world, int lod) {
        if (lod >= 1) {
            buildSurface(chunk, world);
        } else {
            buildFull(chunk, world);
        }
    }

    /**
     * Convenience overload that always builds at full detail (LOD 0).
     * Must be called from the OpenGL thread.
     */
    public void build(Chunk chunk, World world) {
        build(chunk, world, 0);
    }

    // -------------------------------------------------------------------------
    // Full-detail mesh (LOD 0)
    // -------------------------------------------------------------------------

    private void buildFull(Chunk chunk, World world) {
        List<Float> buf      = new ArrayList<>(4096);
        List<Float> waterBuf = new ArrayList<>(512);

        // Pre-compute the surface height per column so that computeSkyLight can
        // determine depth-from-surface in O(1) rather than scanning upward each time.
        int[][] surfaceHeights = new int[Chunk.SIZE][Chunk.SIZE];
        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                surfaceHeights[lx][lz] = findSurfaceHeight(chunk, lx, lz);
            }
        }

        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int ly = 0; ly < Chunk.HEIGHT; ly++) {
                for (int lz = 0; lz < Chunk.SIZE; lz++) {
                    BlockType block = chunk.getBlock(lx, ly, lz);

                    int wx = chunk.getWorldX(lx);
                    int wz = chunk.getWorldZ(lz);
                    float skyLight = computeSkyLight(ly, surfaceHeights[lx][lz]);

                    if (block.solid) {
                        // Emit a solid face only when the adjacent block is transparent
                        if (isTransparent(world, chunk, lx, ly + 1, lz)) addFace(buf, wx, ly, wz, Face.TOP,    block, skyLight);
                        if (isTransparent(world, chunk, lx, ly - 1, lz)) addFace(buf, wx, ly, wz, Face.BOTTOM, block, skyLight);
                        if (isTransparent(world, chunk, lx, ly, lz - 1)) addFace(buf, wx, ly, wz, Face.NORTH,  block, skyLight);
                        if (isTransparent(world, chunk, lx, ly, lz + 1)) addFace(buf, wx, ly, wz, Face.SOUTH,  block, skyLight);
                        if (isTransparent(world, chunk, lx - 1, ly, lz)) addFace(buf, wx, ly, wz, Face.WEST,   block, skyLight);
                        if (isTransparent(world, chunk, lx + 1, ly, lz)) addFace(buf, wx, ly, wz, Face.EAST,   block, skyLight);
                    } else if (block == BlockType.WATER) {
                        // Emit a water face only at water–air boundaries (never water–water)
                        if (isOpenForWaterFace(world, chunk, lx, ly + 1, lz)) addWaterFace(waterBuf, wx, ly, wz, Face.TOP,    skyLight);
                        if (isOpenForWaterFace(world, chunk, lx, ly - 1, lz)) addWaterFace(waterBuf, wx, ly, wz, Face.BOTTOM, skyLight);
                        if (isOpenForWaterFace(world, chunk, lx, ly, lz - 1)) addWaterFace(waterBuf, wx, ly, wz, Face.NORTH,  skyLight);
                        if (isOpenForWaterFace(world, chunk, lx, ly, lz + 1)) addWaterFace(waterBuf, wx, ly, wz, Face.SOUTH,  skyLight);
                        if (isOpenForWaterFace(world, chunk, lx - 1, ly, lz)) addWaterFace(waterBuf, wx, ly, wz, Face.WEST,   skyLight);
                        if (isOpenForWaterFace(world, chunk, lx + 1, ly, lz)) addWaterFace(waterBuf, wx, ly, wz, Face.EAST,   skyLight);
                    }
                }
            }
        }

        float[] solidData = new float[buf.size()];
        for (int i = 0; i < buf.size(); i++) solidData[i] = buf.get(i);
        upload(solidData);

        float[] wData = new float[waterBuf.size()];
        for (int i = 0; i < waterBuf.size(); i++) wData[i] = waterBuf.get(i);
        uploadWater(wData);
    }

    // -------------------------------------------------------------------------
    // Surface-only mesh (LOD 1)
    // -------------------------------------------------------------------------

    /**
     * Builds a surface-only mesh: for each column in the chunk, the top face of
     * the topmost solid block is emitted.  Additionally, side faces are emitted
     * wherever a neighbouring column's surface is lower than this column's
     * surface, so that vertical terrain walls are visible rather than
     * transparent.
     *
     * <p>Cross-chunk neighbour lookups are performed via {@code world} so that
     * chunk-boundary edges are handled correctly.
     */
    private void buildSurface(Chunk chunk, World world) {
        // Pre-compute the surface height (index of the topmost solid block, or
        // -1 for a fully-air column) for every column in this chunk.
        int[][] surfaceHeights = new int[Chunk.SIZE][Chunk.SIZE];
        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                surfaceHeights[lx][lz] = findSurfaceHeight(chunk, lx, lz);
            }
        }

        List<Float> buf      = new ArrayList<>(Chunk.SIZE * Chunk.SIZE * FLOATS_PER_VERTEX * VERTICES_PER_FACE);
        List<Float> waterBuf = new ArrayList<>(512);

        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int surfaceY = surfaceHeights[lx][lz];
                if (surfaceY < 0) continue;

                int wx = chunk.getWorldX(lx);
                int wz = chunk.getWorldZ(lz);

                // Top face of the surface block (always sky-lit in the LOD mesh)
                addFace(buf, wx, surfaceY, wz, Face.TOP, chunk.getBlock(lx, surfaceY, lz), 1.0f);

                // For underwater columns emit the water surface at sea level so
                // distant ocean chunks are visible rather than showing the bare seabed.
                if (surfaceY < com.blockgame.world.DefaultWorldGenerator.SEA_LEVEL) {
                    addWaterFace(waterBuf, wx, com.blockgame.world.DefaultWorldGenerator.SEA_LEVEL - 1, wz, Face.TOP, 1.0f);
                }

                // Side faces: emit wherever the adjacent column is lower so that
                // the vertical terrain wall is not invisible.
                addLodSideFaces(buf, chunk, world, lx, lz, surfaceY, surfaceHeights, Face.NORTH);
                addLodSideFaces(buf, chunk, world, lx, lz, surfaceY, surfaceHeights, Face.SOUTH);
                addLodSideFaces(buf, chunk, world, lx, lz, surfaceY, surfaceHeights, Face.WEST);
                addLodSideFaces(buf, chunk, world, lx, lz, surfaceY, surfaceHeights, Face.EAST);
            }
        }

        float[] data = new float[buf.size()];
        for (int i = 0; i < buf.size(); i++) data[i] = buf.get(i);
        upload(data);

        float[] wData = new float[waterBuf.size()];
        for (int i = 0; i < waterBuf.size(); i++) wData[i] = waterBuf.get(i);
        uploadWater(wData);
    }

    /**
     * Returns the Y index of the topmost solid block in the given local column,
     * or {@code -1} if the column contains no solid blocks.
     */
    private int findSurfaceHeight(Chunk chunk, int lx, int lz) {
        for (int ly = Chunk.HEIGHT - 1; ly >= 0; ly--) {
            if (chunk.getBlock(lx, ly, lz).solid) return ly;
        }
        return -1;
    }

    /**
     * Returns the surface height of the neighbouring column that lies one step
     * in the direction encoded by {@code (dlx, dlz)} from the local position
     * {@code (lx, lz)}.  Uses the pre-computed {@code surfaceHeights} table for
     * in-chunk neighbours and queries {@code world} for cross-chunk neighbours.
     */
    private int neighborSurfaceHeight(Chunk chunk, World world,
                                      int nlx, int nlz, int[][] surfaceHeights) {
        // In-chunk fast path
        if (nlx >= 0 && nlx < Chunk.SIZE && nlz >= 0 && nlz < Chunk.SIZE) {
            return surfaceHeights[nlx][nlz];
        }
        // Cross-chunk: scan downward through the world
        int wx = chunk.getWorldX(nlx);
        int wz = chunk.getWorldZ(nlz);
        for (int ly = Chunk.HEIGHT - 1; ly >= 0; ly--) {
            if (world.getBlock(wx, ly, wz).solid) return ly;
        }
        return -1;
    }

    /**
     * Emits side faces on the given {@code face} side of the column at
     * {@code (lx, lz)} for each Y level that is exposed because the adjacent
     * column's surface is lower.
     */
    private void addLodSideFaces(List<Float> buf, Chunk chunk, World world,
                                  int lx, int lz, int surfaceY, int[][] surfaceHeights,
                                  Face face) {
        int nlx = lx, nlz = lz;
        switch (face) {
            case NORTH: nlz = lz - 1; break;
            case SOUTH: nlz = lz + 1; break;
            case WEST:  nlx = lx - 1; break;
            case EAST:  nlx = lx + 1; break;
            default: return;
        }

        int neighborY = neighborSurfaceHeight(chunk, world, nlx, nlz, surfaceHeights);
        if (neighborY >= surfaceY) return; // neighbour is at the same level or higher

        int wx = chunk.getWorldX(lx);
        int wz = chunk.getWorldZ(lz);
        // Emit a face for each block level that is above the neighbour's surface.
        // When neighborY == -1 (fully-air column), fromY becomes 0, which is correct:
        // all block levels from the base up to surfaceY are exposed.
        int fromY = neighborY + 1;
        for (int y = fromY; y <= surfaceY; y++) {
            BlockType block = chunk.getBlock(lx, y, lz);
            if (block.solid) {
                addFace(buf, wx, y, wz, face, block, 1.0f);
            }
        }
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

    /**
     * Returns {@code true} if a water face should be emitted on the side of a
     * water block facing the block at {@code (lx, ly, lz)}.
     *
     * <p>A face is emitted only when the neighbouring block is transparent
     * <em>and</em> is not itself water – this prevents generating internal
     * faces at water–water boundaries.
     */
    private boolean isOpenForWaterFace(World world, Chunk chunk, int lx, int ly, int lz) {
        BlockType adj = getBlockAt(world, chunk, lx, ly, lz);
        return adj.isTransparent() && adj != BlockType.WATER;
    }

    /** Returns the {@link BlockType} at local chunk position, crossing chunk boundaries via {@code world}. */
    private BlockType getBlockAt(World world, Chunk chunk, int lx, int ly, int lz) {
        if (lx >= 0 && lx < Chunk.SIZE && lz >= 0 && lz < Chunk.SIZE
                && ly >= 0 && ly < Chunk.HEIGHT) {
            return chunk.getBlock(lx, ly, lz);
        }
        int wx = chunk.getWorldX(lx);
        int wz = chunk.getWorldZ(lz);
        return world.getBlock(wx, ly, wz);
    }

    // -------------------------------------------------------------------------
    // Sky-light computation
    // -------------------------------------------------------------------------

    /**
     * Returns the sky-light level for a block at height {@code ly} whose
     * column's topmost solid block (the terrain surface) is at {@code surfaceY}.
     *
     * <p>Blocks at or above the surface receive full sky light (1.0).  Blocks
     * below the surface receive a brightness that fades linearly with depth so
     * that hillside faces transition smoothly from bright at the surface to
     * cave darkness underground, eliminating the harsh horizontal banding that a
     * binary 0/1 value produces.  Blocks more than {@code SKY_LIGHT_FADE_DEPTH}
     * levels below the surface receive 0.0 (cave darkness).
     *
     * @param ly       the Y coordinate of the block being lit
     * @param surfaceY the Y coordinate of the topmost solid block in this column
     *                 (as returned by {@link #findSurfaceHeight}), or {@code -1}
     *                 for a fully-air column
     */
    private static final int   SKY_LIGHT_FADE_DEPTH = 4;
    private static final float SKY_LIGHT_FADE_STEP  = 1.0f / SKY_LIGHT_FADE_DEPTH;

    private float computeSkyLight(int ly, int surfaceY) {
        if (surfaceY < 0 || ly > surfaceY) return 1.0f; // fully sky-exposed
        int depth = surfaceY - ly;
        return Math.max(0.0f, 1.0f - depth * SKY_LIGHT_FADE_STEP);
    }

    // -------------------------------------------------------------------------
    // Face geometry
    // -------------------------------------------------------------------------

    private enum Face { TOP, BOTTOM, NORTH, SOUTH, WEST, EAST }

    private void addFace(List<Float> buf, int x, int y, int z, Face face, BlockType block, float skyLight) {
        boolean isTop  = face == Face.TOP;
        boolean isSide = face != Face.TOP && face != Face.BOTTOM;

        int tileId = TextureAtlas.getTileId(block, isTop, isSide);
        float[] uv = TextureAtlas.getUV(tileId);
        // uv = [u0, v0, u1, v1]  (v0 = top of tile, v1 = bottom of tile in atlas space)
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];

        // UV corners per vertex (4 verts) – layout matches quad() vertex order:
        //   Horizontal faces (TOP/BOTTOM): V0=(u0,v0), V1=(u0,v1), V2=(u1,v1), V3=(u1,v0)
        //   Vertical   faces (sides):      V0=(u0,v1), V1=(u0,v0), V2=(u1,v0), V3=(u1,v1)
        float[] us, vs;
        if (!isSide) {
            us = new float[]{u0, u0, u1, u1};
            vs = new float[]{v0, v1, v1, v0};
        } else {
            us = new float[]{u0, u0, u1, u1};
            vs = new float[]{v1, v0, v0, v1};
        }

        float[] quad   = quad(x, y, z, face);
        float[] normal = normal(face);

        // Two triangles: indices 0,1,2 and 0,2,3
        for (int i : TRIANGLE_INDICES) {
            buf.add(quad[i * 3]);
            buf.add(quad[i * 3 + 1]);
            buf.add(quad[i * 3 + 2]);
            buf.add(us[i]); buf.add(vs[i]);
            buf.add(normal[0]); buf.add(normal[1]); buf.add(normal[2]);
            buf.add(skyLight);
        }
    }

    /**
     * Emits a water face into {@code buf} using UV coordinates suitable for the
     * animated water_still texture (U in [0,1], V in [0, 1/32] for one frame).
     * The water fragment shader advances through frames at runtime using {@code uTime}.
     */
    private void addWaterFace(List<Float> buf, int x, int y, int z, Face face, float skyLight) {
        boolean isSide = face != Face.TOP && face != Face.BOTTOM;

        // One frame of the water sprite sheet: U spans [0,1], V spans [0, WATER_FRAME_V]
        float u0 = 0f, v0 = 0f, u1 = 1f, v1 = WATER_FRAME_V;

        float[] us, vs;
        if (!isSide) {
            us = new float[]{u0, u0, u1, u1};
            vs = new float[]{v0, v1, v1, v0};
        } else {
            us = new float[]{u0, u0, u1, u1};
            vs = new float[]{v1, v0, v0, v1};
        }

        float[] quad   = quad(x, y, z, face);
        float[] normal = normal(face);

        for (int i : TRIANGLE_INDICES) {
            buf.add(quad[i * 3]);
            buf.add(quad[i * 3 + 1]);
            buf.add(quad[i * 3 + 2]);
            buf.add(us[i]); buf.add(vs[i]);
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
            case SOUTH:  return new float[]{ x+1, y,   z+1, x+1, y+1, z+1, x,   y+1, z+1, x,   y,   z+1 };
            case WEST:   return new float[]{ x,   y,   z+1, x,   y+1, z+1, x,   y+1, z,   x,   y,   z   };
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
        // attrib 0: position  (3 floats, offset 0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        // attrib 1: texCoord  (2 floats, offset 12)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        // attrib 2: normal    (3 floats, offset 20)
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, 5L * Float.BYTES);
        glEnableVertexAttribArray(2);
        // attrib 3: skyLight  (1 float,  offset 32)
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 8L * Float.BYTES);
        glEnableVertexAttribArray(3);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        vertexCount = data.length / FLOATS_PER_VERTEX;
    }

    /**
     * Uploads water face geometry into a dedicated VAO/VBO.
     * Uses the same vertex layout as the solid mesh.
     * Must be called <em>after</em> {@link #upload(float[])} because {@code upload}
     * calls {@link #cleanup()} which also resets the water buffers.
     */
    private void uploadWater(float[] data) {
        // Water buffers are always freed by the preceding cleanup() call in upload()
        if (data.length == 0) return;

        waterVao = glGenVertexArrays();
        waterVbo = glGenBuffers();

        glBindVertexArray(waterVao);
        glBindBuffer(GL_ARRAY_BUFFER, waterVbo);

        FloatBuffer fb = BufferUtils.createFloatBuffer(data.length);
        fb.put(data).flip();
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);

        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, 5L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 8L * Float.BYTES);
        glEnableVertexAttribArray(3);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        waterVertexCount = data.length / FLOATS_PER_VERTEX;
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

    /** Renders only the water faces (for the transparent pass). */
    public void renderWater() {
        if (waterVao == 0 || waterVertexCount == 0) return;
        glBindVertexArray(waterVao);
        glDrawArrays(GL_TRIANGLES, 0, waterVertexCount);
        glBindVertexArray(0);
    }

    public void cleanup() {
        if (vao != 0) { glDeleteVertexArrays(vao); vao = 0; }
        if (vbo != 0) { glDeleteBuffers(vbo);       vbo = 0; }
        vertexCount = 0;
        if (waterVao != 0) { glDeleteVertexArrays(waterVao); waterVao = 0; }
        if (waterVbo != 0) { glDeleteBuffers(waterVbo);       waterVbo = 0; }
        waterVertexCount = 0;
    }

    public boolean isEmpty() { return vertexCount == 0; }
}
