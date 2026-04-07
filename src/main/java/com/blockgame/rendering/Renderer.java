package com.blockgame.rendering;

import com.blockgame.player.Player;
import com.blockgame.world.BlockType;
import com.blockgame.world.Chunk;
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
    private TextureAtlas textureAtlas;

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
    private static final int HOTBAR_SLOTS = 8;

    // Hotbar icon geometry (textured quads over each slot)
    private int iconVao, iconVbo;

    // Block-face highlight geometry
    private int highlightVao, highlightVbo;
    private final FloatBuffer highlightBuf = BufferUtils.createFloatBuffer(6 * 3);

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
        textureAtlas = new TextureAtlas();

        buildCrosshair();
        buildHotbar();
        buildIconHotbar();
        buildHighlight();

        // Load GUI overlay images (crosshair + hotbar background)
        crosshairTexId = loadGuiTexture("crosshair");
        hotbarTexId    = loadGuiTexture("hotbar");
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
        renderHighlight();
        renderHud();
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
        textureAtlas.cleanup();

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
    }
}
