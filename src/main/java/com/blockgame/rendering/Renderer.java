package com.blockgame.rendering;

import com.blockgame.mob.MobManager;
import com.blockgame.player.Inventory;
import com.blockgame.player.Player;
import com.blockgame.world.BlockType;
import com.blockgame.world.Chunk;
import com.blockgame.world.DroppedItem;
import com.blockgame.world.DroppedItemManager;
import com.blockgame.world.World;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.*;
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
    private Shader iconShader;
    private Shader highlightShader;
    private Shader waterShader;
    private TextureAtlas textureAtlas;
    // Animated water texture (water_still.png – 16×512, 32 frames of 16×16)
    private int waterTextureId;

    private ParticleSystem particleSystem = null;
    private MobRenderer    mobRenderer    = null;

    private final Map<Long, ChunkMesh> chunkMeshes = new HashMap<>();

    // LOD thresholds (in chunk-grid units from the player chunk)
    /** Chunks within this radius get the full-detail mesh (LOD 0). */
    private static final int LOD_FULL_RADIUS    = 10;
    /** Squared version used to avoid sqrt in the hot path. */
    private static final int LOD_FULL_RADIUS_SQ = LOD_FULL_RADIUS * LOD_FULL_RADIUS;

    /** Tracks the LOD level at which each chunk's mesh was last built. */
    private final Map<Long, Integer> chunkMeshLod = new HashMap<>();

    /** Reused each frame for frustum culling. */
    private final FrustumIntersection frustum = new FrustumIntersection();

    // Crosshair geometry
    private int crosshairVao, crosshairVbo;
    // OpenGL texture loaded from GUI/crosshair.png
    private int crosshairTexId;

    // Hotbar slot-background geometry (textured quad using GUI/hotbar.png)
    private int hotbarVao, hotbarVbo;
    private int hotbarImageVao, hotbarImageVbo;
    // OpenGL texture loaded from GUI/hotbar.png
    private int hotbarTexId;
    private static final int HOTBAR_SLOTS = 9;

    // Hotbar icon geometry (textured quads over each slot)
    private int iconVao, iconVbo;

    // Block-face highlight geometry
    private int highlightVao, highlightVbo;
    private final FloatBuffer highlightBuf = BufferUtils.createFloatBuffer(6 * 3);

    // Inventory overlay
    private int inventoryTexId;
    private int inventoryOverlayVao, inventoryOverlayVbo;
    private int inventoryBgVao, inventoryBgVbo;
    private int inventoryIconVao, inventoryIconVbo;
    // Number of inventory icon quads to draw (filled each frame)
    private int inventoryIconCount = 0;

    // Dropped-item billboard geometry (dynamic, rebuilt each frame)
    private int droppedItemVao, droppedItemVbo;
    private static final int MAX_DROPPED_ITEM_VERTS = 2048 * 6; // up to 2048 items × 2 tris
    private DroppedItemManager droppedItemManager = null;

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
        iconShader  = new Shader("shaders/icon_vertex.glsl", "shaders/icon_fragment.glsl");
        highlightShader = new Shader("shaders/highlight_vertex.glsl", "shaders/highlight_fragment.glsl");
        waterShader = new Shader("shaders/water_vertex.glsl", "shaders/water_fragment.glsl");
        textureAtlas = new TextureAtlas();
        waterTextureId = loadBlockTexture("water_still");

        buildCrosshair();
        buildHotbar();
        buildIconHotbar();
        buildHighlight();
        buildInventoryOverlay();
        buildDroppedItemVao();

        // Load GUI overlay images (crosshair + hotbar background)
        crosshairTexId  = loadGuiTexture("crosshair");
        hotbarTexId     = loadGuiTexture("hotbar");
        inventoryTexId  = loadGuiTexture("inventory");
        buildHotbarImage();

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
        renderWater();
        renderParticles();
        renderMobs();
        renderDroppedItems();
        renderHighlight();
        renderHud();

        if (player.isInventoryOpen()) {
            renderInventory();
        }
    }

    // -------------------------------------------------------------------------
    // World pass
    // -------------------------------------------------------------------------

    private void rebuildDirtyMeshes() {
        Vector3f pos = player.getPosition();
        int pcx = Math.floorDiv((int) pos.x, Chunk.SIZE);
        int pcz = Math.floorDiv((int) pos.z, Chunk.SIZE);

        for (Map.Entry<Long, Chunk> e : world.getChunks().entrySet()) {
            Chunk chunk = e.getValue();
            int dx = chunk.chunkX - pcx;
            int dz = chunk.chunkZ - pcz;
            int distSq = dx * dx + dz * dz;
            int lod = (distSq <= LOD_FULL_RADIUS_SQ) ? 0 : 1;

            Integer prevLod = chunkMeshLod.get(e.getKey());
            if (chunk.isDirty() || prevLod == null || prevLod != lod) {
                chunkMeshes.computeIfAbsent(e.getKey(), k -> new ChunkMesh())
                           .build(chunk, world, lod);
                chunkMeshLod.put(e.getKey(), lod);
                chunk.setDirty(false);
            }
        }
        // Drop meshes for chunks that were unloaded
        chunkMeshes.keySet().retainAll(world.getChunks().keySet());
        chunkMeshLod.keySet().retainAll(world.getChunks().keySet());
    }

    private void renderWorld() {
        worldShader.use();

        Matrix4f identity = new Matrix4f().identity();
        Matrix4f view       = player.getCamera().getViewMatrix();
        Matrix4f projection = player.getCamera().getProjectionMatrix();
        worldShader.setMatrix4f("model",      identity);
        worldShader.setMatrix4f("view",       view);
        worldShader.setMatrix4f("projection", projection);
        worldShader.setVector3f("lightDir",   LIGHT_DIR);
        worldShader.setFloat("ambientStrength", 0.40f);
        worldShader.setInt("uTexture", 0);

        // Distance fog – fade terrain to sky colour before the render-distance
        // edge to hide the dark ring of exposed underground chunk faces.
        float fogEnd   = (World.RENDER_DISTANCE - 3) * Chunk.SIZE;  // ~352 blocks
        float fogStart = fogEnd * 0.6f;                              // ~211 blocks
        worldShader.setVector3f("uFogColor", new Vector3f(SKY_R, SKY_G, SKY_B));
        worldShader.setFloat("uFogStart", fogStart);
        worldShader.setFloat("uFogEnd",   fogEnd);

        glActiveTexture(GL_TEXTURE0);
        textureAtlas.bind();

        // Update the frustum from the current projection × view matrix
        frustum.set(new Matrix4f(projection).mul(view));

        for (Map.Entry<Long, ChunkMesh> e : chunkMeshes.entrySet()) {
            long key = e.getKey();
            int cx = (int) (key >> 32);
            int cz = (int) (key & 0xFFFFFFFFL);

            // Chunk AABB in world space
            float minX = cx * Chunk.SIZE;
            float minZ = cz * Chunk.SIZE;
            float maxX = minX + Chunk.SIZE;
            float maxZ = minZ + Chunk.SIZE;

            // Test the AABB against the frustum; skip if not visible
            if (!frustum.testAab(minX, 0f, minZ, maxX, Chunk.HEIGHT, maxZ)) continue;

            e.getValue().render();
        }

        glBindTexture(GL_TEXTURE_2D, 0);
    }

    // -------------------------------------------------------------------------
    // Water pass (transparent, after solid world)
    // -------------------------------------------------------------------------

    private void renderWater() {
        if (waterTextureId == 0) return;

        waterShader.use();

        Matrix4f identity   = new Matrix4f().identity();
        Matrix4f view       = player.getCamera().getViewMatrix();
        Matrix4f projection = player.getCamera().getProjectionMatrix();
        waterShader.setMatrix4f("model",      identity);
        waterShader.setMatrix4f("view",       view);
        waterShader.setMatrix4f("projection", projection);
        waterShader.setVector3f("lightDir",   LIGHT_DIR);
        waterShader.setFloat("ambientStrength", 0.40f);
        waterShader.setFloat("uTime", (float) glfwGetTime());
        waterShader.setInt("uWaterTexture", 0);

        float fogEnd   = (World.RENDER_DISTANCE - 3) * Chunk.SIZE;
        float fogStart = fogEnd * 0.6f;
        waterShader.setVector3f("uFogColor", new Vector3f(SKY_R, SKY_G, SKY_B));
        waterShader.setFloat("uFogStart", fogStart);
        waterShader.setFloat("uFogEnd",   fogEnd);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, waterTextureId);

        // Transparent pass: blend, disable back-face culling so water is
        // visible from below (e.g. looking up through the bottom of a lake).
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_CULL_FACE);

        for (Map.Entry<Long, ChunkMesh> e : chunkMeshes.entrySet()) {
            long key = e.getKey();
            int cx = (int) (key >> 32);
            int cz = (int) (key & 0xFFFFFFFFL);

            float minX = cx * Chunk.SIZE;
            float minZ = cz * Chunk.SIZE;
            float maxX = minX + Chunk.SIZE;
            float maxZ = minZ + Chunk.SIZE;

            if (!frustum.testAab(minX, 0f, minZ, maxX, Chunk.HEIGHT, maxZ)) continue;

            e.getValue().renderWater();
        }

        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    // -------------------------------------------------------------------------
    // Particle pass
    // -------------------------------------------------------------------------

    /** Connects the particle system so it is rendered each frame. */
    public void setParticleSystem(ParticleSystem ps) {
        this.particleSystem = ps;
    }

    private void renderParticles() {
        if (particleSystem == null) return;
        particleSystem.render(
            player.getCamera().getViewMatrix(),
            player.getCamera().getProjectionMatrix()
        );
    }

    // -------------------------------------------------------------------------
    // Mob pass
    // -------------------------------------------------------------------------

    /**
     * Connects the mob manager.  Creates a {@link MobRenderer} on first call
     * so that it is initialised on the OpenGL thread.
     */
    public void setMobManager(MobManager mm) {
        if (mobRenderer == null) {
            mobRenderer = new MobRenderer();
        }
        mobRenderer.setMobManager(mm);
    }

    private void renderMobs() {
        if (mobRenderer == null) return;
        mobRenderer.render(
            player.getCamera().getViewMatrix(),
            player.getCamera().getProjectionMatrix()
        );
    }

    // -------------------------------------------------------------------------
    // Block-face highlight pass
    // -------------------------------------------------------------------------

    private void buildHighlight() {
        highlightVao = glGenVertexArrays();
        highlightVbo = glGenBuffers();

        glBindVertexArray(highlightVao);
        glBindBuffer(GL_ARRAY_BUFFER, highlightVbo);

        // Allocate space for one face: 6 vertices × 3 floats each (highlightBuf is pre-allocated)
        glBufferData(GL_ARRAY_BUFFER, highlightBuf, GL_DYNAMIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    private void renderHighlight() {
        int[] block  = player.getTargetedBlock();
        int[] normal = player.getTargetedFaceNormal();
        if (block == null || normal == null) return;

        float[] verts = buildHighlightFace(block[0], block[1], block[2],
                                           normal[0], normal[1], normal[2]);

        highlightBuf.clear();
        highlightBuf.put(verts).flip();

        glBindBuffer(GL_ARRAY_BUFFER, highlightVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, highlightBuf);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        highlightShader.use();
        highlightShader.setMatrix4f("view",       player.getCamera().getViewMatrix());
        highlightShader.setMatrix4f("projection", player.getCamera().getProjectionMatrix());
        highlightShader.setFloat("uTime", (float) glfwGetTime());

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-1f, -1f);

        glBindVertexArray(highlightVao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindVertexArray(0);

        glDisable(GL_POLYGON_OFFSET_FILL);
        glDisable(GL_BLEND);
    }

    /**
     * Returns 6 vertex positions (2 triangles) for the given block face,
     * slightly offset outward along the face normal to avoid z-fighting.
     */
    private float[] buildHighlightFace(int bx, int by, int bz, int nx, int ny, int nz) {
        final float OFF = 0.001f;
        float ox = nx * OFF, oy = ny * OFF, oz = nz * OFF;
        float x = bx, y = by, z = bz;

        // 4 corners of the face – winding matches ChunkMesh (CCW from outside)
        float[][] c;
        if      (ny ==  1) c = new float[][]{ {x+ox,   y+1+oy, z+oz  }, {x+ox,   y+1+oy, z+1+oz}, {x+1+ox, y+1+oy, z+1+oz}, {x+1+ox, y+1+oy, z+oz  } };
        else if (ny == -1) c = new float[][]{ {x+ox,   y+oy,   z+oz  }, {x+1+ox, y+oy,   z+oz  }, {x+1+ox, y+oy,   z+1+oz}, {x+ox,   y+oy,   z+1+oz} };
        else if (nz == -1) c = new float[][]{ {x+ox,   y+oy,   z+oz  }, {x+ox,   y+1+oy, z+oz  }, {x+1+ox, y+1+oy, z+oz  }, {x+1+ox, y+oy,   z+oz  } };
        else if (nz ==  1) c = new float[][]{ {x+1+ox, y+oy,   z+1+oz}, {x+1+ox, y+1+oy, z+1+oz}, {x+ox,   y+1+oy, z+1+oz}, {x+ox,   y+oy,   z+1+oz} };
        else if (nx == -1) c = new float[][]{ {x+ox,   y+oy,   z+1+oz}, {x+ox,   y+1+oy, z+1+oz}, {x+ox,   y+1+oy, z+oz  }, {x+ox,   y+oy,   z+oz  } };
        else               c = new float[][]{ {x+1+ox, y+oy,   z+oz  }, {x+1+ox, y+1+oy, z+oz  }, {x+1+ox, y+1+oy, z+1+oz}, {x+1+ox, y+oy,   z+1+oz} };

        // Emit triangles: 0,1,2 and 0,2,3
        float[] v = new float[6 * 3];
        int[] idx = {0, 1, 2, 0, 2, 3};
        for (int i = 0; i < 6; i++) {
            v[i*3]   = c[idx[i]][0];
            v[i*3+1] = c[idx[i]][1];
            v[i*3+2] = c[idx[i]][2];
        }
        return v;
    }

    // -------------------------------------------------------------------------
    // HUD pass
    // -------------------------------------------------------------------------

    private void renderHud() {
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // --- Hotbar image background (textured quad using GUI/hotbar.png) ---
        if (hotbarTexId != 0) {
            iconShader.use();
            iconShader.setInt("uIcons", 0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, hotbarTexId);
            glBindVertexArray(hotbarImageVao);
            glDrawArrays(GL_TRIANGLES, 0, 6);
        }

        // --- Selected slot highlight (single semi-transparent white quad) ---
        updateHotbarColors();
        hudShader.use();
        glBindVertexArray(hotbarVao);
        glDrawArrays(GL_TRIANGLES, 0, 6);

        // --- Hotbar block icons ---
        updateIconHotbar();
        iconShader.use();
        iconShader.setInt("uIcons", 0);
        glActiveTexture(GL_TEXTURE0);
        textureAtlas.bindIcons();
        glBindVertexArray(iconVao);
        glDrawArrays(GL_TRIANGLES, 0, HOTBAR_SLOTS * 2 * 3);

        // --- Crosshair image (textured quad using GUI/crosshair.png) ---
        if (crosshairTexId != 0) {
            iconShader.use();
            iconShader.setInt("uIcons", 0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, crosshairTexId);
            glBindVertexArray(crosshairVao);
            glDrawArrays(GL_TRIANGLES, 0, 6);
        }

        glBindTexture(GL_TEXTURE_2D, 0);
        glBindVertexArray(0);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
    }

    // -------------------------------------------------------------------------
    // HUD geometry builders
    // -------------------------------------------------------------------------

    private void buildCrosshair() {
        // Textured quad centred at the screen origin.  UV convention matches fillIconBuffer:
        // screen-bottom → v=1, screen-top → v=0.
        float s = 0.022f;  // half-size in NDC (same visible extent as the old line crosshair)
        float[] v = {
            // pos x,  pos y,  u,   v
            -s, -s,  0f, 1f,
             s, -s,  1f, 1f,
             s,  s,  1f, 0f,
            -s, -s,  0f, 1f,
             s,  s,  1f, 0f,
            -s,  s,  0f, 0f
        };
        crosshairVao = glGenVertexArrays();
        crosshairVbo = glGenBuffers();

        glBindVertexArray(crosshairVao);
        glBindBuffer(GL_ARRAY_BUFFER, crosshairVbo);
        FloatBuffer fb = BufferUtils.createFloatBuffer(v.length);
        fb.put(v).flip();
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);

        int stride = 4 * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /**
     * Builds the VAO for the selected-slot highlight quad.
     * Only a single slot-sized quad is emitted; it is updated every frame in
     * {@link #updateHotbarColors()} to follow the currently selected slot.
     */
    private void buildHotbar() {
        hotbarVao = glGenVertexArrays();
        hotbarVbo = glGenBuffers();

        // 1 slot × 2 triangles × 3 vertices × (2 pos + 3 color) floats
        int floatsPerSlot = 2 * 3 * (2 + 3);
        FloatBuffer fb = BufferUtils.createFloatBuffer(floatsPerSlot);

        fillHotbarBuffer(fb, player.getHotbarIndex());
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

    /** Re-uploads the selected-slot highlight quad each frame. */
    private void updateHotbarColors() {
        int floatsPerSlot = 2 * 3 * (2 + 3);
        FloatBuffer fb = BufferUtils.createFloatBuffer(floatsPerSlot);

        fillHotbarBuffer(fb, player.getHotbarIndex());
        fb.flip();

        glBindBuffer(GL_ARRAY_BUFFER, hotbarVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, fb);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Fills {@code fb} with a single white quad covering the selected slot.
     * Rendered at low alpha by the HUD shader to act as a highlight overlay.
     */
    private void fillHotbarBuffer(FloatBuffer fb, int selected) {
        float slotSize = 0.07f;
        float gap      = 0.01f;
        float totalW   = HOTBAR_SLOTS * slotSize + (HOTBAR_SLOTS - 1) * gap;
        float startX   = -totalW / 2f;
        float bottomY  = -0.92f;

        float x0 = startX + selected * (slotSize + gap);
        float x1 = x0 + slotSize;
        float y0 = bottomY;
        float y1 = bottomY + slotSize;

        float r = 1f, g = 1f, b = 1f;  // white selection highlight
        putVertex(fb, x0, y0, r, g, b);
        putVertex(fb, x1, y0, r, g, b);
        putVertex(fb, x1, y1, r, g, b);

        putVertex(fb, x0, y0, r, g, b);
        putVertex(fb, x1, y1, r, g, b);
        putVertex(fb, x0, y1, r, g, b);
    }

    private static void putVertex(FloatBuffer fb, float x, float y, float r, float g, float b) {
        fb.put(x).put(y).put(r).put(g).put(b);
    }

    /**
     * Builds a textured quad that covers the entire hotbar area, rendered using
     * the {@code GUI/hotbar.png} image as the background.
     */
    private void buildHotbarImage() {
        float slotSize = 0.07f;
        float gap      = 0.01f;
        float totalW   = HOTBAR_SLOTS * slotSize + (HOTBAR_SLOTS - 1) * gap;
        float pad      = 0.01f;  // small border around the slot area

        float x0 = -totalW / 2f - pad;
        float x1 =  totalW / 2f + pad;
        float y0 = -0.92f - pad;
        float y1 = -0.92f + slotSize + pad;

        float[] v = {
            x0, y0,  0f, 1f,
            x1, y0,  1f, 1f,
            x1, y1,  1f, 0f,
            x0, y0,  0f, 1f,
            x1, y1,  1f, 0f,
            x0, y1,  0f, 0f
        };

        hotbarImageVao = glGenVertexArrays();
        hotbarImageVbo = glGenBuffers();

        glBindVertexArray(hotbarImageVao);
        glBindBuffer(GL_ARRAY_BUFFER, hotbarImageVbo);
        FloatBuffer fb = BufferUtils.createFloatBuffer(v.length);
        fb.put(v).flip();
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);

        int stride = 4 * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    // -------------------------------------------------------------------------
    // Icon hotbar (textured block icons)
    // -------------------------------------------------------------------------

    /**
     * Builds the VAO for the icon hotbar quads (position + UV).
     * The actual UV values are filled in per-frame by {@link #updateIconHotbar()}.
     */
    private void buildIconHotbar() {
        iconVao = glGenVertexArrays();
        iconVbo = glGenBuffers();

        // Each slot: 2 triangles × 3 vertices × (2 pos + 2 uv) = 24 floats
        int floatsPerSlot = 2 * 3 * (2 + 2);
        FloatBuffer fb = BufferUtils.createFloatBuffer(HOTBAR_SLOTS * floatsPerSlot);
        fillIconBuffer(fb);
        fb.flip();

        glBindVertexArray(iconVao);
        glBindBuffer(GL_ARRAY_BUFFER, iconVbo);
        glBufferData(GL_ARRAY_BUFFER, fb, GL_DYNAMIC_DRAW);

        int stride = (2 + 2) * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /** Re-uploads icon quad UV + position data each frame. */
    private void updateIconHotbar() {
        int floatsPerSlot = 2 * 3 * (2 + 2);
        FloatBuffer fb = BufferUtils.createFloatBuffer(HOTBAR_SLOTS * floatsPerSlot);
        fillIconBuffer(fb);
        fb.flip();

        glBindBuffer(GL_ARRAY_BUFFER, iconVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, fb);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Fills {@code fb} with icon quad vertex data.
     * Each slot gets a quad slightly inset from the slot background, textured
     * with the corresponding block's icon from the icon atlas.
     *
     * <p>The icon NDC width is scaled by {@code viewportH / viewportW} so that
     * the icon renders as a square in screen pixels regardless of window dimensions.
     */
    private void fillIconBuffer(FloatBuffer fb) {
        BlockType[] hotbar = player.getHotbar();

        float slotSize = 0.07f;
        float gap      = 0.01f;
        float inset    = 0.005f; // slight inset so background border is visible
        float totalW   = HOTBAR_SLOTS * slotSize + (HOTBAR_SLOTS - 1) * gap;
        float startX   = -totalW / 2f;
        float bottomY  = -0.92f;

        // To make icon square in screen pixels:
        //   pixelW = ndcW * viewportW / 2  must equal  pixelH = ndcH * viewportH / 2
        //   → ndcW = ndcH * viewportH / viewportW
        float iconNdcH = slotSize - 2 * inset;
        float iconNdcW = (viewportW > 0 && viewportH > 0)
                         ? iconNdcH * (float) viewportH / viewportW
                         : iconNdcH;

        for (int i = 0; i < HOTBAR_SLOTS; i++) {
            float slotCenterX = startX + i * (slotSize + gap) + slotSize / 2f;
            float x0 = slotCenterX - iconNdcW / 2f;
            float x1 = slotCenterX + iconNdcW / 2f;
            float y0 = bottomY + inset;
            float y1 = y0 + iconNdcH;

            int iconIdx = TextureAtlas.getIconIndex(hotbar[i]);
            float[] uv;
            if (iconIdx >= 0) {
                uv = TextureAtlas.getIconUV(iconIdx);
            } else {
                uv = new float[]{0f, 0f, 0f, 0f}; // invisible for unknown blocks
            }
            float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];

            // Two triangles (CCW winding)
            putIconVertex(fb, x0, y0, u0, v1);
            putIconVertex(fb, x1, y0, u1, v1);
            putIconVertex(fb, x1, y1, u1, v0);

            putIconVertex(fb, x0, y0, u0, v1);
            putIconVertex(fb, x1, y1, u1, v0);
            putIconVertex(fb, x0, y1, u0, v0);
        }
    }

    private static void putIconVertex(FloatBuffer fb, float x, float y, float u, float v) {
        fb.put(x).put(y).put(u).put(v);
    }

    // -------------------------------------------------------------------------
    // GUI texture loader
    // -------------------------------------------------------------------------

    /**
     * Loads a PNG image from {@code /GUI/<name>.png} on the classpath and
     * uploads it to the GPU as an RGBA texture with nearest-neighbour filtering.
     *
     * @param name filename without extension, e.g. {@code "crosshair"}
     * @return OpenGL texture ID, or {@code 0} if the resource could not be loaded
     */
    private static int loadGuiTexture(String name) {
        String path = "/GUI/" + name + ".png";
        try (InputStream in = Renderer.class.getResourceAsStream(path)) {
            if (in == null) {
                Logger.getLogger(Renderer.class.getName())
                      .warning("GUI texture not found: " + path);
                return 0;
            }
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                Logger.getLogger(Renderer.class.getName())
                      .warning("Failed to decode GUI texture: " + path);
                return 0;
            }

            int w = img.getWidth();
            int h = img.getHeight();
            int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
            for (int pixel : pixels) {
                buf.put((byte) ((pixel >> 16) & 0xFF)); // R
                buf.put((byte) ((pixel >>  8) & 0xFF)); // G
                buf.put((byte) ( pixel        & 0xFF)); // B
                buf.put((byte) ((pixel >> 24) & 0xFF)); // A
            }
            buf.flip();

            int id = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, id);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
            glBindTexture(GL_TEXTURE_2D, 0);
            return id;
        } catch (IOException e) {
            Logger.getLogger(Renderer.class.getName())
                  .log(Level.WARNING, "Error loading GUI texture: " + path, e);
            return 0;
        }
    }

    /**
     * Loads a PNG image from {@code /textures/<name>.png} on the classpath and
     * uploads it to the GPU as an RGBA texture with nearest-neighbour filtering.
     * Used for block textures that need to be bound separately (e.g. animated water).
     *
     * @param name filename without extension, e.g. {@code "water_still"}
     * @return OpenGL texture ID, or {@code 0} if the resource could not be loaded
     */
    private static int loadBlockTexture(String name) {
        String path = "/textures/" + name + ".png";
        try (InputStream in = Renderer.class.getResourceAsStream(path)) {
            if (in == null) {
                Logger.getLogger(Renderer.class.getName())
                      .warning("Block texture not found: " + path);
                return 0;
            }
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                Logger.getLogger(Renderer.class.getName())
                      .warning("Failed to decode block texture: " + path);
                return 0;
            }

            int w = img.getWidth();
            int h = img.getHeight();
            int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
            for (int pixel : pixels) {
                buf.put((byte) ((pixel >> 16) & 0xFF)); // R
                buf.put((byte) ((pixel >>  8) & 0xFF)); // G
                buf.put((byte) ( pixel        & 0xFF)); // B
                buf.put((byte) ((pixel >> 24) & 0xFF)); // A
            }
            buf.flip();

            int id = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, id);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
            glBindTexture(GL_TEXTURE_2D, 0);
            return id;
        } catch (IOException e) {
            Logger.getLogger(Renderer.class.getName())
                  .log(Level.WARNING, "Error loading block texture: " + path, e);
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Inventory overlay
    // -------------------------------------------------------------------------

    /** Connects the dropped-item manager so dropped items are rendered each frame. */
    public void setDroppedItemManager(DroppedItemManager m) {
        this.droppedItemManager = m;
    }

    /**
     * Builds a full-screen quad (for the semi-transparent dimming overlay),
     * a centered quad for inventory.png, and a dynamic VAO for 36 icon slots.
     */
    private void buildInventoryOverlay() {
        // Full-screen dim quad
        float[] overlay = {
            -1f, -1f,  0f, 1f,
             1f, -1f,  1f, 1f,
             1f,  1f,  1f, 0f,
            -1f, -1f,  0f, 1f,
             1f,  1f,  1f, 0f,
            -1f,  1f,  0f, 0f
        };
        inventoryOverlayVao = glGenVertexArrays();
        inventoryOverlayVbo = glGenBuffers();
        glBindVertexArray(inventoryOverlayVao);
        glBindBuffer(GL_ARRAY_BUFFER, inventoryOverlayVbo);
        FloatBuffer fb = BufferUtils.createFloatBuffer(overlay.length);
        fb.put(overlay).flip();
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        int stride = 4 * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        // Centered inventory.png background quad (built in buildInventoryBg)
        inventoryBgVao = glGenVertexArrays();
        inventoryBgVbo = glGenBuffers();

        // Dynamic icon quads for 36 slots
        inventoryIconVao = glGenVertexArrays();
        inventoryIconVbo = glGenBuffers();
        glBindVertexArray(inventoryIconVao);
        glBindBuffer(GL_ARRAY_BUFFER, inventoryIconVbo);
        // 36 slots × 6 verts × 4 floats each
        glBufferData(GL_ARRAY_BUFFER, (long) Inventory.SIZE * 6 * 4 * Float.BYTES,
                     GL_DYNAMIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /**
     * Renders the inventory screen: dark overlay + inventory.png + block icons.
     *
     * <p>The inventory.png is 256×256.  Slot pixel centres (measured in the
     * 256×256 image space) are:
     * <ul>
     *   <li>Main grid (3 rows × 9 cols): column centres 14, 32, 50, 68, 86, 104, 122, 140, 158 px;
     *       row centres 83, 101, 119 px.</li>
     *   <li>Hotbar row (1 row × 9): same column centres, row centre 141 px.</li>
     * </ul>
     * The image is centred in the viewport and scaled so its height is 85 % of
     * the smaller viewport dimension.
     */
    private void renderInventory() {
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // --- Dark overlay ---
        // Re-use iconShader but bind a 1×1 black-semi-transparent texture is too
        // complex; instead use hudShader to draw a solid dark rectangle.
        hudShader.use();
        // Draw full-screen quad with rgba(0,0,0,0.55) – pass colour via vertex data
        {
            float[] quad = {
                -1f, -1f,  0f, 0f, 0f,
                 1f, -1f,  0f, 0f, 0f,
                 1f,  1f,  0f, 0f, 0f,
                -1f, -1f,  0f, 0f, 0f,
                 1f,  1f,  0f, 0f, 0f,
                -1f,  1f,  0f, 0f, 0f
            };
            glBindVertexArray(inventoryOverlayVao);
            // We repurpose the overlay VAO (pos2+uv2) – the hud shader wants pos2+col3.
            // Instead rebuild on the overlay VBO with hud-shader layout (2+3 floats):
            FloatBuffer fb = BufferUtils.createFloatBuffer(quad.length);
            fb.put(quad).flip();
            // Use a temporary VBO approach: just draw a manual quad via GL_TRIANGLES
            // Upload through the highlight VBO trick (pos+col per vertex):
            // Actually, let's just use a simple separate immediate-style draw.
        }

        // Simple approach: draw dark semi-transparent overlay via hudShader with
        // the hotbarVao trick (it already handles 2+3 float stride):
        float[] darkQuad = new float[6 * 5];
        float[] corners = {-1f,-1f, 1f,-1f, 1f,1f, -1f,-1f, 1f,1f, -1f,1f};
        for (int v = 0; v < 6; v++) {
            darkQuad[v*5]   = corners[v*2];
            darkQuad[v*5+1] = corners[v*2+1];
            darkQuad[v*5+2] = 0f; // r
            darkQuad[v*5+3] = 0f; // g
            darkQuad[v*5+4] = 0f; // b
        }
        // Upload into hotbarVbo (it's already DYNAMIC_DRAW)
        FloatBuffer darkFb = BufferUtils.createFloatBuffer(darkQuad.length);
        darkFb.put(darkQuad).flip();
        glBindBuffer(GL_ARRAY_BUFFER, hotbarVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, darkFb);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        hudShader.use();
        glBindVertexArray(hotbarVao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindVertexArray(0);

        // Restore hotbar highlight (will be re-uploaded next frame by updateHotbarColors)

        // --- Inventory image background ---
        if (inventoryTexId != 0) {
            float scale   = Math.min(viewportW, viewportH) * 0.85f / 256f;
            float ndcW    = (256f * scale) / viewportW;
            float ndcH    = (256f * scale) / viewportH;

            float x0 = -ndcW, y0 = -ndcH, x1 = ndcW, y1 = ndcH;
            float[] bg = {
                x0, y0,  0f, 1f,
                x1, y0,  1f, 1f,
                x1, y1,  1f, 0f,
                x0, y0,  0f, 1f,
                x1, y1,  1f, 0f,
                x0, y1,  0f, 0f
            };
            FloatBuffer bgFb = BufferUtils.createFloatBuffer(bg.length);
            bgFb.put(bg).flip();

            glBindVertexArray(inventoryBgVao);
            glBindBuffer(GL_ARRAY_BUFFER, inventoryBgVbo);
            glBufferData(GL_ARRAY_BUFFER, bgFb, GL_DYNAMIC_DRAW);
            int stride = 4 * Float.BYTES;
            glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
            glEnableVertexAttribArray(1);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            iconShader.use();
            iconShader.setInt("uIcons", 0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, inventoryTexId);
            glDrawArrays(GL_TRIANGLES, 0, 6);
            glBindVertexArray(0);

            // --- Inventory item icons ---
            float scale2  = scale;
            float imgNdcW = ndcW;
            float imgNdcH = ndcH;

            // Slot pixel centres in 256×256 image space
            // Main grid: cols 14,32,50,68,86,104,122,140,158  rows 83,101,119
            // Hotbar:    same cols, row 141
            int[] colPx = {14, 32, 50, 68, 86, 104, 122, 140, 158};
            int[] rowPx = {83, 101, 119, 141}; // rows 0-2 = main, row 3 = hotbar

            // Inventory slot mapping: slots 9..35 = main grid (row 0..2, col 0..8)
            //                         slots 0..8  = hotbar (row 3, col 0..8)
            Inventory inv = player.getInventory();

            FloatBuffer iconFb = BufferUtils.createFloatBuffer(Inventory.SIZE * 6 * 4);

            // Icon half-size in NDC: slot is ~16px in 256 image → 16/256 * imgNdcW
            float iconHalfW = (8f / 256f) * imgNdcW;
            float iconHalfH = (8f / 256f) * imgNdcH;

            int drawCount = 0;
            // Main inventory rows (slots 9..35 → rows 0..2)
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    int slotIdx = 9 + row * 9 + col;
                    BlockType bt = inv.getSlot(slotIdx) != null ? inv.getSlot(slotIdx).type : BlockType.AIR;
                    int iconIdx = TextureAtlas.getIconIndex(bt);
                    if (iconIdx < 0) continue;

                    float cx = ((float) colPx[col] / 256f - 0.5f) * 2f * imgNdcW;
                    float cy = (0.5f - (float) rowPx[row] / 256f) * 2f * imgNdcH;
                    float[] uv = TextureAtlas.getIconUV(iconIdx);
                    putInvIconQuad(iconFb, cx, cy, iconHalfW, iconHalfH, uv);
                    drawCount++;
                }
            }
            // Hotbar row (slots 0..8 → row index 3)
            for (int col = 0; col < 9; col++) {
                BlockType bt = inv.getHotbarBlock(col);
                int iconIdx = TextureAtlas.getIconIndex(bt);
                if (iconIdx < 0) continue;

                float cx = ((float) colPx[col] / 256f - 0.5f) * 2f * imgNdcW;
                float cy = (0.5f - (float) rowPx[3] / 256f) * 2f * imgNdcH;
                float[] uv = TextureAtlas.getIconUV(iconIdx);
                putInvIconQuad(iconFb, cx, cy, iconHalfW, iconHalfH, uv);
                drawCount++;
            }
            iconFb.flip();

            glBindVertexArray(inventoryIconVao);
            glBindBuffer(GL_ARRAY_BUFFER, inventoryIconVbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, iconFb);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            iconShader.use();
            iconShader.setInt("uIcons", 0);
            glActiveTexture(GL_TEXTURE0);
            textureAtlas.bindIcons();
            glDrawArrays(GL_TRIANGLES, 0, drawCount * 6);
            glBindVertexArray(0);
        }

        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
    }

    private static void putInvIconQuad(FloatBuffer fb,
                                       float cx, float cy,
                                       float hw, float hh,
                                       float[] uv) {
        float x0 = cx - hw, x1 = cx + hw;
        float y0 = cy - hh, y1 = cy + hh;
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        putIconVertex(fb, x0, y0, u0, v1);
        putIconVertex(fb, x1, y0, u1, v1);
        putIconVertex(fb, x1, y1, u1, v0);
        putIconVertex(fb, x0, y0, u0, v1);
        putIconVertex(fb, x1, y1, u1, v0);
        putIconVertex(fb, x0, y1, u0, v0);
    }

    // -------------------------------------------------------------------------
    // Dropped-item billboard rendering
    // -------------------------------------------------------------------------

    private void buildDroppedItemVao() {
        droppedItemVao = glGenVertexArrays();
        droppedItemVbo = glGenBuffers();

        glBindVertexArray(droppedItemVao);
        glBindBuffer(GL_ARRAY_BUFFER, droppedItemVbo);
        // Allocate max buffer (pos3+uv2+normal3 = 8 floats per vertex)
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_DROPPED_ITEM_VERTS * 8 * Float.BYTES,
                     GL_DYNAMIC_DRAW);
        int stride = 8 * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, 5L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /**
     * Renders all dropped items as Y-axis billboards facing the player.
     * Uses the world shader with the icon atlas bound as the texture.
     */
    private void renderDroppedItems() {
        if (droppedItemManager == null) return;
        List<DroppedItem> items = droppedItemManager.getItems();
        if (items.isEmpty()) return;

        Vector3f camPos = player.getCamera().getPosition();
        float time = (float) glfwGetTime();

        FloatBuffer buf = BufferUtils.createFloatBuffer(
                Math.min(items.size(), MAX_DROPPED_ITEM_VERTS / 6) * 6 * 8);

        int count = 0;
        for (DroppedItem item : items) {
            if (count >= MAX_DROPPED_ITEM_VERTS / 6) break;

            int iconIdx = TextureAtlas.getIconIndex(item.type);
            if (iconIdx < 0) continue;

            // Slight bobbing animation
            float bob = (float) Math.sin(time * 2.5f + item.getAge() * 3f) * 0.06f;

            float ix = item.x;
            float iy = item.y + bob;
            float iz = item.z;

            // Billboard: Y-axis facing player (ignore vertical component)
            float fdx = camPos.x - ix;
            float fdz = camPos.z - iz;
            float flen = (float) Math.sqrt(fdx * fdx + fdz * fdz);
            if (flen < 0.001f) { fdx = 1; flen = 1; }
            fdx /= flen; fdz /= flen;
            // right = cross((0,1,0), forward) = (fdz, 0, -fdx)
            float rx = fdz, rz = -fdx;

            float s = 0.25f;  // half-width of the item quad

            float[] uv = TextureAtlas.getIconUV(iconIdx);
            float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];

            // 4 corners: bottom-left, bottom-right, top-right, top-left
            float[][] pos = {
                {ix - rx*s, iy,     iz - rz*s},
                {ix + rx*s, iy,     iz + rz*s},
                {ix + rx*s, iy+s*2, iz + rz*s},
                {ix - rx*s, iy+s*2, iz - rz*s}
            };
            // normal points toward player (in XZ plane)
            float nx = fdx, ny = 0f, nz = fdz;

            // Triangle 1: 0,1,2
            putBillboardVertex(buf, pos[0], u0, v1, nx, ny, nz);
            putBillboardVertex(buf, pos[1], u1, v1, nx, ny, nz);
            putBillboardVertex(buf, pos[2], u1, v0, nx, ny, nz);
            // Triangle 2: 0,2,3
            putBillboardVertex(buf, pos[0], u0, v1, nx, ny, nz);
            putBillboardVertex(buf, pos[2], u1, v0, nx, ny, nz);
            putBillboardVertex(buf, pos[3], u0, v0, nx, ny, nz);
            count++;
        }

        if (count == 0) return;

        buf.flip();
        glBindBuffer(GL_ARRAY_BUFFER, droppedItemVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, buf);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        // Use worldShader with icon atlas
        worldShader.use();
        worldShader.setMatrix4f("model",      new Matrix4f().identity());
        worldShader.setMatrix4f("view",       player.getCamera().getViewMatrix());
        worldShader.setMatrix4f("projection", player.getCamera().getProjectionMatrix());
        worldShader.setVector3f("lightDir",   LIGHT_DIR);
        worldShader.setFloat("ambientStrength", 1.0f); // full brightness for items
        worldShader.setInt("uTexture", 0);
        float fogEnd = (World.RENDER_DISTANCE - 3) * Chunk.SIZE;
        worldShader.setVector3f("uFogColor", new Vector3f(SKY_R, SKY_G, SKY_B));
        worldShader.setFloat("uFogStart", fogEnd * 0.6f);
        worldShader.setFloat("uFogEnd",   fogEnd);

        glActiveTexture(GL_TEXTURE0);
        textureAtlas.bindIcons();

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_CULL_FACE);

        glBindVertexArray(droppedItemVao);
        glDrawArrays(GL_TRIANGLES, 0, count * 6);
        glBindVertexArray(0);

        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private static void putBillboardVertex(FloatBuffer buf, float[] pos,
                                           float u, float v,
                                           float nx, float ny, float nz) {
        buf.put(pos[0]).put(pos[1]).put(pos[2]);
        buf.put(u).put(v);
        buf.put(nx).put(ny).put(nz);
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
        iconShader.cleanup();
        highlightShader.cleanup();
        waterShader.cleanup();
        textureAtlas.cleanup();
        if (waterTextureId != 0) glDeleteTextures(waterTextureId);
        if (mobRenderer != null) mobRenderer.cleanup();

        for (ChunkMesh m : chunkMeshes.values()) m.cleanup();
        chunkMeshes.clear();

        glDeleteVertexArrays(crosshairVao);
        glDeleteBuffers(crosshairVbo);
        if (crosshairTexId != 0) glDeleteTextures(crosshairTexId);
        glDeleteVertexArrays(hotbarVao);
        glDeleteBuffers(hotbarVbo);
        glDeleteVertexArrays(hotbarImageVao);
        glDeleteBuffers(hotbarImageVbo);
        if (hotbarTexId != 0) glDeleteTextures(hotbarTexId);
        glDeleteVertexArrays(iconVao);
        glDeleteBuffers(iconVbo);
        glDeleteVertexArrays(highlightVao);
        glDeleteBuffers(highlightVbo);
        glDeleteVertexArrays(inventoryOverlayVao);
        glDeleteBuffers(inventoryOverlayVbo);
        glDeleteVertexArrays(inventoryBgVao);
        glDeleteBuffers(inventoryBgVbo);
        glDeleteVertexArrays(inventoryIconVao);
        glDeleteBuffers(inventoryIconVbo);
        if (inventoryTexId != 0) glDeleteTextures(inventoryTexId);
        glDeleteVertexArrays(droppedItemVao);
        glDeleteBuffers(droppedItemVbo);
    }
}
