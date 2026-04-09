package com.blockgame.rendering;

import com.blockgame.world.Chunk;
import com.blockgame.world.PerlinNoise;
import com.blockgame.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders Minecraft-style volumetric clouds at a fixed altitude above the world.
 *
 * <p>Clouds are generated from a 2-D Perlin noise field and scroll slowly in the
 * +X direction over time.  The mesh is rebuilt every frame based on the player
 * position so the cloud grid is always centred on the player.
 */
public class CloudRenderer {

    /** World-space Y coordinate of the bottom face of cloud blocks. */
    private static final float CLOUD_Y = 100.0f;
    /** Height of each cloud block in world units. */
    private static final float CLOUD_HEIGHT = 4.0f;
    /** Width/depth of one cloud tile in world units (matches Minecraft beta). */
    private static final float TILE_SIZE = 12.0f;
    /** Number of cloud tiles to render in each direction from the player. */
    private static final int GRID_RADIUS = 28;
    /** Noise frequency – smaller = larger, fewer cloud blobs. */
    private static final float NOISE_SCALE = 0.065f;
    /** Noise threshold: cells with noise > threshold become cloud. ~50% coverage. */
    private static final float CLOUD_THRESHOLD = 0.0f;
    /** Horizontal drift speed in world units per second. */
    private static final float SCROLL_SPEED = 3.0f;

    // Fog parameters mirror the main world shader
    private static final float FOG_END   = (World.RENDER_DISTANCE - 3) * Chunk.SIZE;
    private static final float FOG_START = FOG_END * 0.6f;

    private final Shader      shader;
    private final int         vao;
    private final int         vbo;
    private final PerlinNoise noise = new PerlinNoise(9876543L);

    /** Maximum number of tiles that can appear in the grid. */
    private static final int MAX_TILES = (2 * GRID_RADIUS + 1) * (2 * GRID_RADIUS + 1);
    // 6 faces x 2 triangles x 3 vertices x 3 floats (xyz)
    private static final int FLOATS_PER_TILE = 6 * 2 * 3 * 3;
    /** Pre-allocated staging buffer to avoid per-frame heap allocation. */
    private final FloatBuffer meshBuffer =
            BufferUtils.createFloatBuffer(MAX_TILES * FLOATS_PER_TILE);

    public CloudRenderer() {
        shader = new Shader("shaders/cloud_vertex.glsl", "shaders/cloud_fragment.glsl");

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER,
                (long) MAX_TILES * FLOATS_PER_TILE * Float.BYTES,
                GL_DYNAMIC_DRAW);

        // layout(location = 0) in vec3 aPosition
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /**
     * Rebuilds and renders the cloud mesh for the current frame.
     *
     * @param view       current camera view matrix
     * @param projection current camera projection matrix
     * @param playerPos  world-space player position
     * @param time       elapsed time in seconds (from glfwGetTime())
     * @param skyR       sky/fog red component
     * @param skyG       sky/fog green component
     * @param skyB       sky/fog blue component
     */
    public void render(Matrix4f view, Matrix4f projection,
                       Vector3f playerPos, float time,
                       float skyR, float skyG, float skyB) {

        // Scrolling offset: clouds drift in the +X direction
        float scrollX = time * SCROLL_SPEED;

        // Centre the grid on the player (in tile units), accounting for scroll
        int centerTileX = Math.floorDiv((int) (playerPos.x + scrollX), (int) TILE_SIZE);
        int centerTileZ = Math.floorDiv((int) playerPos.z,              (int) TILE_SIZE);

        meshBuffer.clear();

        for (int dx = -GRID_RADIUS; dx <= GRID_RADIUS; dx++) {
            for (int dz = -GRID_RADIUS; dz <= GRID_RADIUS; dz++) {
                int tx = centerTileX + dx;
                int tz = centerTileZ + dz;

                double n = noise.octaveNoise(
                        tx * NOISE_SCALE, tz * NOISE_SCALE, 2, 0.5, 2.0);
                if (n < CLOUD_THRESHOLD) continue;

                // World X position (subtract scroll so clouds drift visually)
                float wx = tx * TILE_SIZE - scrollX;
                float wz = tz * TILE_SIZE;
                addCloudBox(wx, wz);
            }
        }

        meshBuffer.flip();
        int vertexCount = meshBuffer.limit() / 3;
        if (vertexCount == 0) return;

        // Upload geometry
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, meshBuffer);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        // Draw
        shader.use();
        shader.setMatrix4f("model",      new Matrix4f().identity());
        shader.setMatrix4f("view",       view);
        shader.setMatrix4f("projection", projection);
        shader.setVector3f("uFogColor",  new Vector3f(skyR, skyG, skyB));
        shader.setFloat("uFogStart", FOG_START);
        shader.setFloat("uFogEnd",   FOG_END);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_CULL_FACE);
        glDepthMask(false);

        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        glBindVertexArray(0);

        glDepthMask(true);
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
    }

    // -------------------------------------------------------------------------
    // Geometry helpers
    // -------------------------------------------------------------------------

    /**
     * Emits all six faces of a cloud box starting at world coordinates
     * (wx, CLOUD_Y, wz) with dimensions TILE_SIZE x CLOUD_HEIGHT x TILE_SIZE.
     */
    private void addCloudBox(float wx, float wz) {
        float x0 = wx,          x1 = wx + TILE_SIZE;
        float y0 = CLOUD_Y,     y1 = CLOUD_Y + CLOUD_HEIGHT;
        float z0 = wz,          z1 = wz + TILE_SIZE;

        // Top face (+Y)
        addQuad(x0, y1, z0,  x1, y1, z0,  x1, y1, z1,  x0, y1, z1);
        // Bottom face (-Y)
        addQuad(x0, y0, z1,  x1, y0, z1,  x1, y0, z0,  x0, y0, z0);
        // North face (-Z)
        addQuad(x1, y0, z0,  x1, y1, z0,  x0, y1, z0,  x0, y0, z0);
        // South face (+Z)
        addQuad(x0, y0, z1,  x0, y1, z1,  x1, y1, z1,  x1, y0, z1);
        // West face (-X)
        addQuad(x0, y0, z0,  x0, y1, z0,  x0, y1, z1,  x0, y0, z1);
        // East face (+X)
        addQuad(x1, y0, z1,  x1, y1, z1,  x1, y1, z0,  x1, y0, z0);
    }

    /**
     * Emits two CCW triangles for a quad defined by four corner vertices,
     * writing directly into meshBuffer.
     */
    private void addQuad(float x0, float y0, float z0,
                         float x1, float y1, float z1,
                         float x2, float y2, float z2,
                         float x3, float y3, float z3) {
        meshBuffer.put(x0).put(y0).put(z0);
        meshBuffer.put(x1).put(y1).put(z1);
        meshBuffer.put(x2).put(y2).put(z2);

        meshBuffer.put(x0).put(y0).put(z0);
        meshBuffer.put(x2).put(y2).put(z2);
        meshBuffer.put(x3).put(y3).put(z3);
    }
}
