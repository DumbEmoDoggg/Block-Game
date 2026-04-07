package com.blockgame.rendering;

import com.blockgame.player.Player;
import com.blockgame.world.BlockType;
import com.blockgame.world.Chunk;
import com.blockgame.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Main renderer.  Each frame it:
 * <ol>
 *   <li>Rebuilds any dirty chunk meshes.</li>
 *   <li>Renders the 3-D world with a simple directional light.</li>
 *   <li>Renders the 2-D HUD (crosshair + block-selection hotbar).</li>
 * </ol>
 */
public class Renderer {

    // Sky colour (light blue)
    private static final float SKY_R = 0.53f, SKY_G = 0.81f, SKY_B = 0.98f;

    // Sun direction (normalised at setup time)
    private static final Vector3f LIGHT_DIR =
        new Vector3f(-0.4f, -1.0f, -0.3f).normalize();

    private final long   window;
    private final World  world;
    private final Player player;

    private Shader worldShader;
    private Shader hudShader;

    private final Map<Long, ChunkMesh> chunkMeshes = new HashMap<>();

    // Crosshair geometry
    private int crosshairVao, crosshairVbo;

    // Hotbar quad geometry
    private int hotbarVao, hotbarVbo;
    private static final int HOTBAR_SLOTS = 7;

    private int viewportW, viewportH;

    public Renderer(long window, World world, Player player) {
        this.window = window;
        this.world  = world;
        this.player = player;
        init();
    }

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    private void init() {
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glFrontFace(GL_CCW);

        worldShader = new Shader("shaders/vertex.glsl",             "shaders/fragment.glsl");
        hudShader   = new Shader("shaders/hud_vertex.glsl",  "shaders/hud_fragment.glsl");

        buildCrosshair();
        buildHotbar();

        // Read initial framebuffer size
        IntBuffer w = BufferUtils.createIntBuffer(1);
        IntBuffer h = BufferUtils.createIntBuffer(1);
        glfwGetFramebufferSize(window, w, h);
        viewportW = w.get(0);
        viewportH = h.get(0);
        glViewport(0, 0, viewportW, viewportH);

        player.getCamera().updateProjection((float) viewportW / viewportH);

        // Resize callback
        glfwSetFramebufferSizeCallback(window, (win, width, height) -> {
            viewportW = width;
            viewportH = height;
            glViewport(0, 0, width, height);
            player.getCamera().updateProjection((float) width / height);
        });
    }

    // -------------------------------------------------------------------------
    // Per-frame render
    // -------------------------------------------------------------------------

    public void render() {
        glClearColor(SKY_R, SKY_G, SKY_B, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        rebuildDirtyMeshes();
        renderWorld();
        renderHud();
    }

    // -------------------------------------------------------------------------
    // World pass
    // -------------------------------------------------------------------------

    private void rebuildDirtyMeshes() {
        for (Map.Entry<Long, Chunk> e : world.getChunks().entrySet()) {
            Chunk chunk = e.getValue();
            if (chunk.isDirty()) {
                chunkMeshes.computeIfAbsent(e.getKey(), k -> new ChunkMesh())
                           .build(chunk, world);
                chunk.setDirty(false);
            }
        }
        // Drop meshes for chunks that were unloaded
        chunkMeshes.keySet().retainAll(world.getChunks().keySet());
    }

    private void renderWorld() {
        worldShader.use();

        Matrix4f identity = new Matrix4f().identity();
        worldShader.setMatrix4f("model",      identity);
        worldShader.setMatrix4f("view",       player.getCamera().getViewMatrix());
        worldShader.setMatrix4f("projection", player.getCamera().getProjectionMatrix());
        worldShader.setVector3f("lightDir",   LIGHT_DIR);
        worldShader.setFloat("ambientStrength", 0.40f);

        for (ChunkMesh mesh : chunkMeshes.values()) {
            mesh.render();
        }
    }

    // -------------------------------------------------------------------------
    // HUD pass
    // -------------------------------------------------------------------------

    private void renderHud() {
        glDisable(GL_DEPTH_TEST);
        hudShader.use();

        // --- Crosshair ---
        glBindVertexArray(crosshairVao);
        glDrawArrays(GL_LINES, 0, 4);

        // --- Hotbar ---
        updateHotbarColors();
        glBindVertexArray(hotbarVao);
        glDrawArrays(GL_TRIANGLES, 0, HOTBAR_SLOTS * 2 * 3);

        glBindVertexArray(0);
        glEnable(GL_DEPTH_TEST);
    }

    // -------------------------------------------------------------------------
    // HUD geometry builders
    // -------------------------------------------------------------------------

    private void buildCrosshair() {
        float s = 0.022f;
        float[] v = {
            -s, 0f,   // horizontal left
             s, 0f,   // horizontal right
             0f, -s,  // vertical bottom
             0f,  s   // vertical top
        };
        crosshairVao = glGenVertexArrays();
        crosshairVbo = glGenBuffers();
        uploadHudGeometry(crosshairVao, crosshairVbo, v, 2);
    }

    /**
     * Builds a static template for the hotbar; colors are updated every frame
     * via {@link #updateHotbarColors()}.
     */
    private void buildHotbar() {
        hotbarVao = glGenVertexArrays();
        hotbarVbo = glGenBuffers();

        // Allocate buffer: each slot = 2 triangles × 3 vertices × (2 pos + 3 color) floats
        int floatsPerSlot = 2 * 3 * (2 + 3);
        FloatBuffer fb = BufferUtils.createFloatBuffer(HOTBAR_SLOTS * floatsPerSlot);

        float slotSize  = 0.07f;
        float gap       = 0.01f;
        float totalW    = HOTBAR_SLOTS * slotSize + (HOTBAR_SLOTS - 1) * gap;
        float startX    = -totalW / 2f;
        float bottomY   = -0.92f;

        BlockType[] hotbar = player.getHotbar();
        int selected = player.getHotbarIndex();

        for (int i = 0; i < HOTBAR_SLOTS; i++) {
            float x0 = startX + i * (slotSize + gap);
            float x1 = x0 + slotSize;
            float y0 = bottomY;
            float y1 = bottomY + slotSize;

            BlockType bt = hotbar[i];
            float r = bt.r, g = bt.g, b = bt.b;
            // Highlight selected slot
            if (i == selected) { r = Math.min(r + 0.3f, 1f); g = Math.min(g + 0.3f, 1f); b = Math.min(b + 0.3f, 1f); }

            // Two triangles
            putVertex(fb, x0, y0, r, g, b);
            putVertex(fb, x1, y0, r, g, b);
            putVertex(fb, x1, y1, r, g, b);

            putVertex(fb, x0, y0, r, g, b);
            putVertex(fb, x1, y1, r, g, b);
            putVertex(fb, x0, y1, r, g, b);
        }
        fb.flip();

        glBindVertexArray(hotbarVao);
        glBindBuffer(GL_ARRAY_BUFFER, hotbarVbo);
        glBufferData(GL_ARRAY_BUFFER, fb, GL_DYNAMIC_DRAW);

        int stride = (2 + 3) * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /** Re-uploads hotbar vertex colors (position stays the same). */
    private void updateHotbarColors() {
        float slotSize = 0.07f;
        float gap      = 0.01f;
        float totalW   = HOTBAR_SLOTS * slotSize + (HOTBAR_SLOTS - 1) * gap;
        float startX   = -totalW / 2f;
        float bottomY  = -0.92f;

        BlockType[] hotbar  = player.getHotbar();
        int         selected = player.getHotbarIndex();

        int floatsPerSlot = 2 * 3 * (2 + 3);
        FloatBuffer fb = BufferUtils.createFloatBuffer(HOTBAR_SLOTS * floatsPerSlot);

        for (int i = 0; i < HOTBAR_SLOTS; i++) {
            float x0 = startX + i * (slotSize + gap);
            float x1 = x0 + slotSize;
            float y0 = bottomY;
            float y1 = bottomY + slotSize;

            BlockType bt = hotbar[i];
            float r = bt.r, g = bt.g, b = bt.b;
            if (i == selected) { r = Math.min(r + 0.3f, 1f); g = Math.min(g + 0.3f, 1f); b = Math.min(b + 0.3f, 1f); }

            putVertex(fb, x0, y0, r, g, b);
            putVertex(fb, x1, y0, r, g, b);
            putVertex(fb, x1, y1, r, g, b);

            putVertex(fb, x0, y0, r, g, b);
            putVertex(fb, x1, y1, r, g, b);
            putVertex(fb, x0, y1, r, g, b);
        }
        fb.flip();

        glBindBuffer(GL_ARRAY_BUFFER, hotbarVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, fb);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    private static void putVertex(FloatBuffer fb, float x, float y, float r, float g, float b) {
        fb.put(x).put(y).put(r).put(g).put(b);
    }

    // -------------------------------------------------------------------------
    // Generic HUD VAO helper (position-only, 2D)
    // -------------------------------------------------------------------------

    private void uploadHudGeometry(int vao, int vbo, float[] data, int components) {
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        FloatBuffer fb = BufferUtils.createFloatBuffer(data.length);
        fb.put(data).flip();
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);

        glVertexAttribPointer(0, components, GL_FLOAT, false, components * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    public void cleanup() {
        worldShader.cleanup();
        hudShader.cleanup();

        for (ChunkMesh m : chunkMeshes.values()) m.cleanup();
        chunkMeshes.clear();

        glDeleteVertexArrays(crosshairVao);
        glDeleteBuffers(crosshairVbo);
        glDeleteVertexArrays(hotbarVao);
        glDeleteBuffers(hotbarVbo);
    }
}
