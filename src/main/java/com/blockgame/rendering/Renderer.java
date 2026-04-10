package com.blockgame.rendering;

import com.blockgame.mob.MobManager;
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

    // Underwater fog colour (deep blue-green)
    private static final float UW_FOG_R = 0.0f, UW_FOG_G = 0.18f, UW_FOG_B = 0.38f;
    /** Fog start distance (in blocks) when the player's eye is underwater. */
    private static final float UW_FOG_START = 0.5f;
    /** Fog end distance (in blocks) when the player's eye is underwater. */
    private static final float UW_FOG_END   = 8.0f;

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
    private CloudRenderer  cloudRenderer  = null;

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

    // GUI scale: each texture pixel occupies this many screen pixels
    private static final float GUI_SCALE = 2.0f;
    // Minecraft-style hotbar texture dimensions (hotbar.png is 182×22, 9 slots à 20px + 1px borders)
    private static final float SLOT_TEX_PX    = 20.0f; // one slot = 20×20 px in texture
    private static final float BORDER_TEX_PX  =  1.0f; // outer border thickness in texture pixels
    private static final float HOTBAR_TEX_PX_H = 22.0f; // hotbar.png height in texture pixels
    /** Distance from the bottom of the screen to the bottom of the hotbar, in screen pixels. */
    private static final float HOTBAR_BOTTOM_PX = 6.0f;
    // UV end for 8-slot crop of the 9-slot hotbar.png: (1 + 8*20 + 1) / 182 = 162/182
    private static final float HOTBAR_UV_END = 162.0f / 182.0f;

    // Hotbar icon geometry (textured quads over each slot)
    private int iconVao, iconVbo;

    // Block-face highlight geometry
    private int highlightVao, highlightVbo;
    private final FloatBuffer highlightBuf = BufferUtils.createFloatBuffer(6 * 3);

    // Heart textures (full.png = whole heart, half.png = half heart)
    private int fullHeartTexId, halfHeartTexId;
    // VAO/VBO for the heart bar (rendered every frame – dynamic)
    private int heartVao, heartVbo;
    // Number of heart icons displayed (Minecraft standard = 10)
    private static final int HEART_COUNT = 10;
    // Natural size of each heart sprite in texture pixels (9×9)
    private static final float HEART_TEX_PX = 9.0f;
    // Gap between consecutive heart sprites, in texture pixels
    private static final float HEART_GAP_PX = 1.0f;

    // Air bubble textures (bubble_full.png, bubble_empty.png)
    private int bubbleFullTexId, bubbleEmptyTexId;
    // VAO/VBO for the air-bubble bar (re-uploaded each frame when underwater)
    private int bubbleVao, bubbleVbo;
    // Number of air-bubble icons (10 bubbles = MAX_AIR / 30)
    private static final int BUBBLE_COUNT = 10;
    // Natural size of each bubble sprite in texture pixels (9×9)
    private static final float BUBBLE_TEX_PX = 9.0f;
    // Gap between bubbles, in texture pixels
    private static final float BUBBLE_GAP_PX = 1.0f;

    // Food bar textures (food_full.png, food_half.png, food_empty.png)
    private int foodFullTexId, foodHalfTexId, foodEmptyTexId;
    // VAO/VBO for the food bar (re-uploaded each frame)
    private int foodVao, foodVbo;
    // Number of food icons (10 = MAX_FOOD / 2)
    private static final int FOOD_COUNT = 10;
    // Natural size of each food sprite in texture pixels (9×9)
    private static final float FOOD_TEX_PX = 9.0f;
    // Gap between food icons, in texture pixels
    private static final float FOOD_GAP_PX = 1.0f;

    // Underwater overlay: a full-screen quad tinted with the underwater fog colour
    private int underwaterVao, underwaterVbo;

    // Arm / held-item rendering
    private int armTexId;
    private int armVao, armVbo;
    private int heldItemVao, heldItemVbo;
    // Arm display dimensions (in logical pixels; multiply by GUI_SCALE for screen pixels)
    private static final float ARM_W_PX          = 40.0f;  // arm width
    // Total parallelogram height; visible on-screen = (ARM_H_PX - ARM_BELOW_PX) × GUI_SCALE pixels
    private static final float ARM_H_PX          = 85.0f;
    private static final float ARM_SKEW_PX       = 25.0f;  // top shifts left by this much (parallelogram lean)
    private static final float ARM_DEPTH_PX      = 12.0f;  // depth of the 3D arm box (right-side/top faces)
    private static final float ARM_RIGHT_PX      =  4.0f;  // gap from right edge of screen
    private static final float ARM_BELOW_PX      = 20.0f;  // arm bottom extends this far below screen
    // Held-item block icon size (logical pixels; × GUI_SCALE = screen pixels)
    private static final float HELD_ICON_PX      = 32.0f;
    // Vertical gap between arm top and held-item icon bottom (logical pixels)
    private static final float HELD_ICON_RAISE_PX =  4.0f;
    // Pre-allocated per-frame buffers for arm and held-item geometry (6 vertices × 4 floats each)
    private final FloatBuffer armBuf      = BufferUtils.createFloatBuffer(6 * 4);
    private final FloatBuffer heldItemBuf = BufferUtils.createFloatBuffer(6 * 4);

    private int viewportW, viewportH;
    // Tracks the viewport dimensions at which the hotbar background geometry was last built
    private int hotbarGeomViewportW, hotbarGeomViewportH;

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
        cloudRenderer = new CloudRenderer();

        buildCrosshair();
        buildHotbar();
        buildIconHotbar();
        buildHighlight();
        buildUnderwaterOverlay();
        buildArm();

        // Load GUI overlay images (crosshair, hotbar background, hearts)
        crosshairTexId  = loadGuiTexture("crosshair");
        hotbarTexId     = loadGuiTexture("hotbar");
        fullHeartTexId  = loadGuiTexture("full");
        halfHeartTexId  = loadGuiTexture("half");
        bubbleFullTexId  = loadGuiTexture("bubble_full");
        bubbleEmptyTexId = loadGuiTexture("bubble_empty");
        foodFullTexId    = loadGuiTexture("food_full");
        foodHalfTexId    = loadGuiTexture("food_half");
        foodEmptyTexId   = loadGuiTexture("food_empty");
        armTexId         = buildArmTexture();

        // Read initial framebuffer size BEFORE building viewport-dependent geometry
        IntBuffer w = BufferUtils.createIntBuffer(1);
        IntBuffer h = BufferUtils.createIntBuffer(1);
        glfwGetFramebufferSize(window, w, h);
        viewportW = w.get(0);
        viewportH = h.get(0);
        glViewport(0, 0, viewportW, viewportH);

        // Build viewport-dependent geometry now that viewportW/H are set
        buildHotbarImage();
        buildHearts();
        buildBubbleBar();
        buildFoodBar();

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
        boolean underwater = player.isEyeUnderwater();
        if (underwater) {
            glClearColor(UW_FOG_R, UW_FOG_G, UW_FOG_B, 1f);
        } else {
            glClearColor(SKY_R, SKY_G, SKY_B, 1f);
        }
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        rebuildDirtyMeshes();
        renderWorld();
        renderClouds();
        // Render mobs BEFORE water so they are visible through the transparent
        // water surface (water written to depth buffer would otherwise occlude
        // submerged mobs if drawn afterward).
        renderMobs(underwater);
        renderWater();
        renderParticles();
        renderHighlight();
        if (underwater) renderUnderwaterOverlay();
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

        // Distance fog – when underwater, use a short blue fog to simulate murky water.
        Vector3f fogColor;
        float fogStart, fogEnd;
        if (player.isEyeUnderwater()) {
            fogColor = new Vector3f(UW_FOG_R, UW_FOG_G, UW_FOG_B);
            fogStart = UW_FOG_START;
            fogEnd   = UW_FOG_END;
        } else {
            fogColor = new Vector3f(SKY_R, SKY_G, SKY_B);
            fogEnd   = (World.RENDER_DISTANCE - 3) * Chunk.SIZE;  // ~352 blocks
            fogStart = fogEnd * 0.6f;                              // ~211 blocks
        }
        worldShader.setVector3f("uFogColor", fogColor);
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
    // Cloud pass (after solid world, before transparent water)
    // -------------------------------------------------------------------------

    private void renderClouds() {
        cloudRenderer.render(
            player.getCamera().getViewMatrix(),
            player.getCamera().getProjectionMatrix(),
            player.getPosition(),
            (float) glfwGetTime(),
            SKY_R, SKY_G, SKY_B
        );
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

        Vector3f fogColor;
        float fogStart, fogEnd;
        if (player.isEyeUnderwater()) {
            fogColor = new Vector3f(UW_FOG_R, UW_FOG_G, UW_FOG_B);
            fogStart = UW_FOG_START;
            fogEnd   = UW_FOG_END;
        } else {
            fogColor = new Vector3f(SKY_R, SKY_G, SKY_B);
            fogEnd   = (World.RENDER_DISTANCE - 3) * Chunk.SIZE;
            fogStart = fogEnd * 0.6f;
        }
        waterShader.setVector3f("uFogColor", fogColor);
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
    // Underwater overlay pass (full-screen blue tint when eye is submerged)
    // -------------------------------------------------------------------------

    /**
     * Allocates a full-screen quad VAO used for the underwater colour overlay.
     * The quad covers NDC [−1, 1] × [−1, 1] and is rendered as a translucent
     * blue rectangle using the existing HUD shader (which outputs a fixed 0.40
     * alpha, giving a tasteful water tint).
     *
     * <p>Vertex format: (x, y, r, g, b) – 5 floats per vertex, matching the
     * layout expected by {@code hud_vertex.glsl}.
     */
    private void buildUnderwaterOverlay() {
        underwaterVao = glGenVertexArrays();
        underwaterVbo = glGenBuffers();

        glBindVertexArray(underwaterVao);
        glBindBuffer(GL_ARRAY_BUFFER, underwaterVbo);

        // Allocate space for 6 vertices × 5 floats (pos2 + col3); filled in renderUnderwaterOverlay
        glBufferData(GL_ARRAY_BUFFER, 6L * 5 * Float.BYTES, GL_DYNAMIC_DRAW);

        int stride = 5 * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /**
     * Renders a semi-transparent blue overlay over the entire screen to simulate
     * the visual effect of being submerged in water.
     *
     * <p>Uses the HUD shader which outputs the per-vertex colour at a fixed
     * alpha of 0.40, providing a visible but not overwhelming tint.
     */
    private void renderUnderwaterOverlay() {
        // Full-screen quad in NDC, coloured with the underwater fog tint.
        // Two triangles (6 vertices) cover the entire NDC range [−1,1]×[−1,1]:
        //   triangle 1: bottom-left, bottom-right, top-right
        //   triangle 2: bottom-left, top-right,   top-left
        FloatBuffer fb = BufferUtils.createFloatBuffer(6 * 5);
        float r = UW_FOG_R, g = UW_FOG_G, b = UW_FOG_B;
        float[][] corners = { {-1f,-1f}, {1f,-1f}, {1f,1f}, {-1f,-1f}, {1f,1f}, {-1f,1f} };
        for (float[] c : corners) {
            fb.put(c[0]).put(c[1]).put(r).put(g).put(b);
        }
        fb.flip();

        glBindBuffer(GL_ARRAY_BUFFER, underwaterVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, fb);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        hudShader.use();
        glBindVertexArray(underwaterVao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindVertexArray(0);

        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
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

    private void renderMobs(boolean playerUnderwater) {
        if (mobRenderer == null) return;

        // Pass the correct fog to the mob shader based on player eye state
        if (playerUnderwater) {
            mobRenderer.setFogParams(
                new Vector3f(UW_FOG_R, UW_FOG_G, UW_FOG_B),
                UW_FOG_START, UW_FOG_END);
        } else {
            float skyFogEnd = (World.RENDER_DISTANCE - 3) * Chunk.SIZE;
            mobRenderer.setFogParams(
                new Vector3f(SKY_R, SKY_G, SKY_B),
                skyFogEnd * 0.6f, skyFogEnd);
        }

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

        iconShader.use();
        iconShader.setInt("uIcons", 0);

        // --- Hotbar image background (textured quad using GUI/hotbar.png) ---
        if (hotbarTexId != 0) {
            // Re-upload hotbar geometry only when the viewport dimensions change
            if (viewportW != hotbarGeomViewportW || viewportH != hotbarGeomViewportH) {
                updateHotbarImage();
                hotbarGeomViewportW = viewportW;
                hotbarGeomViewportH = viewportH;
            }
            iconShader.setVector4f("uColor", 1f, 1f, 1f, 1f);
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
        iconShader.setVector4f("uColor", 1f, 1f, 1f, 1f);
        glActiveTexture(GL_TEXTURE0);
        textureAtlas.bindIcons();
        glBindVertexArray(iconVao);
        glDrawArrays(GL_TRIANGLES, 0, HOTBAR_SLOTS * 2 * 3);

        // --- Health hearts (above the hotbar, left-aligned) ---
        renderHearts();

        // --- Food bar (above the hotbar, right-aligned) ---
        renderFoodBar();

        // --- Air bubbles (above the food bar, when drowning) ---
        if (player.isEyeUnderwater() || player.getAir() < Player.MAX_AIR) {
            renderAirBubbles();
        }

        // --- Player arm / held-item (bottom-right corner) ---
        renderArm();

        // --- Crosshair image (textured quad using GUI/crosshair.png) ---
        if (crosshairTexId != 0) {
            iconShader.use();
            iconShader.setInt("uIcons", 0);
            iconShader.setVector4f("uColor", 1f, 1f, 1f, 1f);
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
     * Fills {@code fb} with a single white quad covering the selected slot,
     * using viewport-aware NDC coordinates so each slot appears square on screen.
     * Rendered at low alpha by the HUD shader to act as a highlight overlay.
     */
    private void fillHotbarBuffer(FloatBuffer fb, int selected) {
        // Slot size in screen pixels (square: SLOT_TEX_PX × GUI_SCALE)
        float slotPx   = SLOT_TEX_PX * GUI_SCALE;
        float borderPx = BORDER_TEX_PX * GUI_SCALE;
        float hotbarPx = HOTBAR_SLOTS * slotPx + 2 * borderPx;

        // Convert pixels → NDC (origin at screen centre; 2 NDC units = viewport dimension)
        float slotW    = (viewportW > 0) ? 2.0f * slotPx / viewportW : 0.09375f;
        float slotH    = (viewportH > 0) ? 2.0f * slotPx / viewportH : 0.1111f;
        float borderW  = (viewportW > 0) ? 2.0f * borderPx / viewportW : 0.003125f;
        float borderH  = (viewportH > 0) ? 2.0f * borderPx / viewportH : 0.005556f;
        float hotbarW  = (viewportW > 0) ? 2.0f * hotbarPx / viewportW : 0.50625f;
        float hotbarH  = (viewportH > 0) ? 2.0f * (HOTBAR_TEX_PX_H * GUI_SCALE) / viewportH : 0.1222f;
        float bottomY  = (viewportH > 0) ? -1.0f + 2.0f * HOTBAR_BOTTOM_PX / viewportH : -0.9833f;

        float startX = -hotbarW / 2.0f + borderW;
        float x0 = startX + selected * slotW;
        float x1 = x0 + slotW;
        float y0 = bottomY + borderH;
        float y1 = y0 + slotH;

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
     * Builds the VAO/VBO for the hotbar background image ({@code GUI/hotbar.png}).
     * The buffer is populated immediately using {@link #fillHotbarImageBuffer(FloatBuffer)}.
     */
    private void buildHotbarImage() {
        hotbarImageVao = glGenVertexArrays();
        hotbarImageVbo = glGenBuffers();

        glBindVertexArray(hotbarImageVao);
        glBindBuffer(GL_ARRAY_BUFFER, hotbarImageVbo);

        FloatBuffer fb = BufferUtils.createFloatBuffer(6 * 4);
        fillHotbarImageBuffer(fb);
        fb.flip();
        glBufferData(GL_ARRAY_BUFFER, fb, GL_DYNAMIC_DRAW);

        int stride = 4 * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /** Re-uploads the hotbar background quad each frame to keep it aspect-ratio correct. */
    private void updateHotbarImage() {
        FloatBuffer fb = BufferUtils.createFloatBuffer(6 * 4);
        fillHotbarImageBuffer(fb);
        fb.flip();
        glBindBuffer(GL_ARRAY_BUFFER, hotbarImageVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, fb);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Computes the NDC quad for the hotbar background, maintaining the hotbar.png
     * aspect ratio.  Uses UV from 0→{@link #HOTBAR_UV_END} to crop the 9-slot
     * texture to show only the 8 slots used by the hotbar.
     */
    private void fillHotbarImageBuffer(FloatBuffer fb) {
        float slotPx   = SLOT_TEX_PX * GUI_SCALE;
        float borderPx = BORDER_TEX_PX * GUI_SCALE;
        float hotbarWpx = HOTBAR_SLOTS * slotPx + 2 * borderPx; // 324px at scale 2
        float hotbarHpx = HOTBAR_TEX_PX_H * GUI_SCALE;           // 44px at scale 2

        float hotbarW  = (viewportW > 0) ? 2.0f * hotbarWpx / viewportW : 0.50625f;
        float hotbarH  = (viewportH > 0) ? 2.0f * hotbarHpx / viewportH : 0.1222f;
        float bottomY  = (viewportH > 0) ? -1.0f + 2.0f * HOTBAR_BOTTOM_PX / viewportH : -0.9833f;

        float x0 = -hotbarW / 2.0f;
        float x1 =  hotbarW / 2.0f;
        float y0 = bottomY;
        float y1 = bottomY + hotbarH;

        // v-flip: texture origin (0,0) is top-left; NDC y0 is bottom of screen
        fb.put(x0).put(y0).put(0f).put(1f);
        fb.put(x1).put(y0).put(HOTBAR_UV_END).put(1f);
        fb.put(x1).put(y1).put(HOTBAR_UV_END).put(0f);

        fb.put(x0).put(y0).put(0f).put(1f);
        fb.put(x1).put(y1).put(HOTBAR_UV_END).put(0f);
        fb.put(x0).put(y1).put(0f).put(0f);
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
     * Slot positions match the viewport-aware hotbar layout.
     */
    private void fillIconBuffer(FloatBuffer fb) {
        BlockType[] hotbar = player.getHotbar();

        float slotPx   = SLOT_TEX_PX * GUI_SCALE;
        float borderPx = BORDER_TEX_PX * GUI_SCALE;
        float hotbarWpx = HOTBAR_SLOTS * slotPx + 2 * borderPx;

        float slotW   = (viewportW > 0) ? 2.0f * slotPx / viewportW : 0.0625f;
        float slotH   = (viewportH > 0) ? 2.0f * slotPx / viewportH : 0.1111f;
        float borderW = (viewportW > 0) ? 2.0f * borderPx / viewportW : 0.003125f;
        float borderH = (viewportH > 0) ? 2.0f * borderPx / viewportH : 0.005556f;
        float hotbarW = (viewportW > 0) ? 2.0f * hotbarWpx / viewportW : 0.50625f;
        float bottomY = (viewportH > 0) ? -1.0f + 2.0f * HOTBAR_BOTTOM_PX / viewportH : -0.9833f;

        // Icon inset: 2 texture-px × GUI_SCALE on each side, leaving 32×32 px for the icon
        float insetPx = 2.0f * GUI_SCALE;
        float insetW  = (viewportW > 0) ? 2.0f * insetPx / viewportW : 0.0125f;
        float insetH  = (viewportH > 0) ? 2.0f * insetPx / viewportH : 0.0222f;
        float iconW   = slotW - 2 * insetW;
        float iconH   = slotH - 2 * insetH;

        float startX = -hotbarW / 2.0f + borderW;

        for (int i = 0; i < HOTBAR_SLOTS; i++) {
            float slotCenterX = startX + i * slotW + slotW / 2.0f;
            float x0 = slotCenterX - iconW / 2.0f;
            float x1 = slotCenterX + iconW / 2.0f;
            float y0 = bottomY + borderH + insetH;
            float y1 = y0 + iconH;

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
    // Health heart bar
    // -------------------------------------------------------------------------

    /**
     * Allocates the VAO/VBO used for heart rendering.
     * Each frame the buffer is completely refilled in {@link #renderHearts()}.
     *
     * <p>Layout per heart quad: 6 vertices × 4 floats (x, y, u, v).
     * We allocate for 2 × {@link #HEART_COUNT} quads (empty containers + filled/half).
     */
    private void buildHearts() {
        heartVao = glGenVertexArrays();
        heartVbo = glGenBuffers();

        glBindVertexArray(heartVao);
        glBindBuffer(GL_ARRAY_BUFFER, heartVbo);

        // 2 passes × HEART_COUNT hearts × 6 vertices × 4 floats
        glBufferData(GL_ARRAY_BUFFER, (long)(2 * HEART_COUNT * 6 * 4) * Float.BYTES, GL_DYNAMIC_DRAW);

        int stride = 4 * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /**
     * Renders the health bar above the hotbar.
     *
     * <p>Pass 1: draw all {@link #HEART_COUNT} heart positions using {@code half.png}
     * at a dark grey tint to represent empty containers.
     * Pass 2: draw filled hearts (full.png) and the half heart (half.png) in full
     * colour on top.
     */
    private void renderHearts() {
        if (fullHeartTexId == 0 || halfHeartTexId == 0) return;

        iconShader.use();
        iconShader.setInt("uIcons", 0);

        // --- Compute heart layout ---
        float heartPx  = HEART_TEX_PX * GUI_SCALE;                     // sprite size in pixels
        float gapPx    = HEART_GAP_PX * GUI_SCALE;                     // gap between hearts
        float stepPx   = heartPx + gapPx;                              // per-heart stride in pixels
        float heartW   = (viewportW > 0) ? 2.0f * heartPx / viewportW : 0.028125f;
        float heartH   = (viewportH > 0) ? 2.0f * heartPx / viewportH : 0.05f;
        float stepW    = (viewportW > 0) ? 2.0f * stepPx  / viewportW : 0.031250f;

        // Hotbar dimensions (must match fillHotbarBuffer / fillHotbarImageBuffer)
        float slotPx   = SLOT_TEX_PX * GUI_SCALE;
        float borderPx = BORDER_TEX_PX * GUI_SCALE;
        float hotbarWpx = HOTBAR_SLOTS * slotPx + 2 * borderPx;
        float hotbarHpx = HOTBAR_TEX_PX_H * GUI_SCALE;
        float hotbarW  = (viewportW > 0) ? 2.0f * hotbarWpx / viewportW : 0.50625f;
        float hotbarH  = (viewportH > 0) ? 2.0f * hotbarHpx / viewportH : 0.1222f;
        float bottomY  = (viewportH > 0) ? -1.0f + 2.0f * HOTBAR_BOTTOM_PX / viewportH : -0.9833f;

        // Hearts sit 2px above the top of the hotbar
        float heartGapToPx = 2.0f * GUI_SCALE;
        float heartsBottomY = bottomY + hotbarH
            + ((viewportH > 0) ? 2.0f * heartGapToPx / viewportH : 0.011f);
        float heartsStartX  = -hotbarW / 2.0f;

        int health    = player.getHealth();
        int maxHealth = player.getMaxHealth();
        int numHearts = maxHealth / 2; // each heart = 2 HP

        // --- Pass 1: empty heart containers (half.png, grey tint) ---
        FloatBuffer emptyBuf = BufferUtils.createFloatBuffer(numHearts * 6 * 4);
        for (int i = 0; i < numHearts; i++) {
            float x0 = heartsStartX + i * stepW;
            float y0 = heartsBottomY;
            emptyBuf.put(x0).put(y0).put(0f).put(1f);
            emptyBuf.put(x0 + heartW).put(y0).put(1f).put(1f);
            emptyBuf.put(x0 + heartW).put(y0 + heartH).put(1f).put(0f);
            emptyBuf.put(x0).put(y0).put(0f).put(1f);
            emptyBuf.put(x0 + heartW).put(y0 + heartH).put(1f).put(0f);
            emptyBuf.put(x0).put(y0 + heartH).put(0f).put(0f);
        }
        emptyBuf.flip();
        glBindBuffer(GL_ARRAY_BUFFER, heartVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, emptyBuf);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, halfHeartTexId);
        iconShader.setVector4f("uColor", 0.30f, 0.30f, 0.30f, 1.0f);
        glBindVertexArray(heartVao);
        glDrawArrays(GL_TRIANGLES, 0, numHearts * 6);

        // --- Pass 2: filled/half hearts on top ---
        int filledHearts = health / 2;
        boolean hasHalf  = (health % 2) == 1;
        int activeDraw   = filledHearts + (hasHalf ? 1 : 0);
        if (activeDraw > 0) {
            // Build one buffer: full-heart quads followed by the optional half-heart quad
            FloatBuffer filledBuf = BufferUtils.createFloatBuffer(activeDraw * 6 * 4);
            for (int i = 0; i < filledHearts; i++) {
                float x0 = heartsStartX + i * stepW;
                float y0 = heartsBottomY;
                filledBuf.put(x0).put(y0).put(0f).put(1f);
                filledBuf.put(x0 + heartW).put(y0).put(1f).put(1f);
                filledBuf.put(x0 + heartW).put(y0 + heartH).put(1f).put(0f);
                filledBuf.put(x0).put(y0).put(0f).put(1f);
                filledBuf.put(x0 + heartW).put(y0 + heartH).put(1f).put(0f);
                filledBuf.put(x0).put(y0 + heartH).put(0f).put(0f);
            }
            if (hasHalf) {
                float x0 = heartsStartX + filledHearts * stepW;
                float y0 = heartsBottomY;
                filledBuf.put(x0).put(y0).put(0f).put(1f);
                filledBuf.put(x0 + heartW).put(y0).put(1f).put(1f);
                filledBuf.put(x0 + heartW).put(y0 + heartH).put(1f).put(0f);
                filledBuf.put(x0).put(y0).put(0f).put(1f);
                filledBuf.put(x0 + heartW).put(y0 + heartH).put(1f).put(0f);
                filledBuf.put(x0).put(y0 + heartH).put(0f).put(0f);
            }
            filledBuf.flip();
            glBindBuffer(GL_ARRAY_BUFFER, heartVbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, filledBuf);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            // Draw full hearts (vertices 0 … filledHearts*6)
            if (filledHearts > 0) {
                glBindTexture(GL_TEXTURE_2D, fullHeartTexId);
                iconShader.setVector4f("uColor", 1f, 1f, 1f, 1f);
                glBindVertexArray(heartVao);
                glDrawArrays(GL_TRIANGLES, 0, filledHearts * 6);
            }
            // Draw half heart from its position in the already-uploaded buffer
            if (hasHalf) {
                glBindTexture(GL_TEXTURE_2D, halfHeartTexId);
                iconShader.setVector4f("uColor", 1f, 1f, 1f, 1f);
                glBindVertexArray(heartVao);
                glDrawArrays(GL_TRIANGLES, filledHearts * 6, 6);
            }
        }

        glBindTexture(GL_TEXTURE_2D, 0);
    }

    // -------------------------------------------------------------------------
    // Air bubble bar
    // -------------------------------------------------------------------------

    /**
     * Allocates the VAO/VBO used for the air-bubble bar.
     * Layout: 2 passes × BUBBLE_COUNT bubbles × 6 verts × 4 floats.
     */
    private void buildBubbleBar() {
        bubbleVao = glGenVertexArrays();
        bubbleVbo = glGenBuffers();

        glBindVertexArray(bubbleVao);
        glBindBuffer(GL_ARRAY_BUFFER, bubbleVbo);
        glBufferData(GL_ARRAY_BUFFER, (long)(2 * BUBBLE_COUNT * 6 * 4) * Float.BYTES, GL_DYNAMIC_DRAW);

        int stride = 4 * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /**
     * Renders 10 air-bubble icons above the food bar on the right side of the
     * hotbar.  Shown whenever the player is underwater or air is not full.
     */
    private void renderAirBubbles() {
        if (bubbleFullTexId == 0 || bubbleEmptyTexId == 0) return;

        iconShader.use();
        iconShader.setInt("uIcons", 0);

        float bubblePx = BUBBLE_TEX_PX * GUI_SCALE;
        float gapPx    = BUBBLE_GAP_PX * GUI_SCALE;
        float stepPx   = bubblePx + gapPx;
        float bubbleW  = (viewportW > 0) ? 2.0f * bubblePx / viewportW : 0.028125f;
        float bubbleH  = (viewportH > 0) ? 2.0f * bubblePx / viewportH : 0.05f;
        float stepW    = (viewportW > 0) ? 2.0f * stepPx  / viewportW : 0.031250f;

        // Layout mirrors heart positions but on the RIGHT side of the hotbar
        float hotbarWpx   = HOTBAR_SLOTS * (SLOT_TEX_PX * GUI_SCALE) + 2 * (BORDER_TEX_PX * GUI_SCALE);
        float hotbarHpx   = HOTBAR_TEX_PX_H * GUI_SCALE;
        float hotbarW     = (viewportW > 0) ? 2.0f * hotbarWpx / viewportW : 0.50625f;
        float hotbarH     = (viewportH > 0) ? 2.0f * hotbarHpx / viewportH : 0.1222f;
        float bottomY     = (viewportH > 0) ? -1.0f + 2.0f * HOTBAR_BOTTOM_PX / viewportH : -0.9833f;

        // Bubbles sit one row ABOVE the food bar icons (food icons row + bubbleH + gap)
        float foodRowGap  = (viewportH > 0) ? 2.0f * (2.0f * GUI_SCALE) / viewportH : 0.011f;
        float foodRowH    = bubbleH;
        float bubblesBottomY = bottomY + hotbarH + foodRowGap + foodRowH + foodRowGap;

        // Align right edge with the right edge of the hotbar
        float totalBubblesW = BUBBLE_COUNT * stepW - (stepW - bubbleW);
        float bubblesStartX = hotbarW / 2.0f - totalBubblesW;

        // Determine how many bubbles are full
        int airBubbles = (int) Math.ceil((double) player.getAir() / Player.MAX_AIR * BUBBLE_COUNT);
        airBubbles = Math.max(0, Math.min(BUBBLE_COUNT, airBubbles));

        // Pass 1: empty bubbles
        FloatBuffer emptyBuf = BufferUtils.createFloatBuffer(BUBBLE_COUNT * 6 * 4);
        for (int i = 0; i < BUBBLE_COUNT; i++) {
            float x0 = bubblesStartX + i * stepW;
            float y0 = bubblesBottomY;
            emptyBuf.put(x0).put(y0).put(0f).put(1f);
            emptyBuf.put(x0+bubbleW).put(y0).put(1f).put(1f);
            emptyBuf.put(x0+bubbleW).put(y0+bubbleH).put(1f).put(0f);
            emptyBuf.put(x0).put(y0).put(0f).put(1f);
            emptyBuf.put(x0+bubbleW).put(y0+bubbleH).put(1f).put(0f);
            emptyBuf.put(x0).put(y0+bubbleH).put(0f).put(0f);
        }
        emptyBuf.flip();
        glBindBuffer(GL_ARRAY_BUFFER, bubbleVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, emptyBuf);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, bubbleEmptyTexId);
        iconShader.setVector4f("uColor", 1f, 1f, 1f, 1f);
        glBindVertexArray(bubbleVao);
        glDrawArrays(GL_TRIANGLES, 0, BUBBLE_COUNT * 6);

        // Pass 2: full bubbles on top
        if (airBubbles > 0) {
            FloatBuffer fullBuf = BufferUtils.createFloatBuffer(airBubbles * 6 * 4);
            for (int i = 0; i < airBubbles; i++) {
                float x0 = bubblesStartX + i * stepW;
                float y0 = bubblesBottomY;
                fullBuf.put(x0).put(y0).put(0f).put(1f);
                fullBuf.put(x0+bubbleW).put(y0).put(1f).put(1f);
                fullBuf.put(x0+bubbleW).put(y0+bubbleH).put(1f).put(0f);
                fullBuf.put(x0).put(y0).put(0f).put(1f);
                fullBuf.put(x0+bubbleW).put(y0+bubbleH).put(1f).put(0f);
                fullBuf.put(x0).put(y0+bubbleH).put(0f).put(0f);
            }
            fullBuf.flip();
            glBindBuffer(GL_ARRAY_BUFFER, bubbleVbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, fullBuf);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            glBindTexture(GL_TEXTURE_2D, bubbleFullTexId);
            iconShader.setVector4f("uColor", 1f, 1f, 1f, 1f);
            glBindVertexArray(bubbleVao);
            glDrawArrays(GL_TRIANGLES, 0, airBubbles * 6);
        }

        glBindTexture(GL_TEXTURE_2D, 0);
    }

    // -------------------------------------------------------------------------
    // Food bar
    // -------------------------------------------------------------------------

    /**
     * Allocates the VAO/VBO used for the food bar.
     */
    private void buildFoodBar() {
        foodVao = glGenVertexArrays();
        foodVbo = glGenBuffers();

        glBindVertexArray(foodVao);
        glBindBuffer(GL_ARRAY_BUFFER, foodVbo);
        glBufferData(GL_ARRAY_BUFFER, (long)(2 * FOOD_COUNT * 6 * 4) * Float.BYTES, GL_DYNAMIC_DRAW);

        int stride = 4 * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /**
     * Renders 10 food icons above the hotbar on the right side, mirroring the
     * hearts on the left.
     */
    private void renderFoodBar() {
        if (foodFullTexId == 0 || foodHalfTexId == 0 || foodEmptyTexId == 0) return;

        iconShader.use();
        iconShader.setInt("uIcons", 0);

        float foodPx   = FOOD_TEX_PX * GUI_SCALE;
        float gapPx    = FOOD_GAP_PX * GUI_SCALE;
        float stepPx   = foodPx + gapPx;
        float foodW    = (viewportW > 0) ? 2.0f * foodPx / viewportW : 0.028125f;
        float foodH    = (viewportH > 0) ? 2.0f * foodPx / viewportH : 0.05f;
        float stepW    = (viewportW > 0) ? 2.0f * stepPx / viewportW : 0.031250f;

        float hotbarWpx   = HOTBAR_SLOTS * (SLOT_TEX_PX * GUI_SCALE) + 2 * (BORDER_TEX_PX * GUI_SCALE);
        float hotbarHpx   = HOTBAR_TEX_PX_H * GUI_SCALE;
        float hotbarW     = (viewportW > 0) ? 2.0f * hotbarWpx / viewportW : 0.50625f;
        float hotbarH     = (viewportH > 0) ? 2.0f * hotbarHpx / viewportH : 0.1222f;
        float bottomY     = (viewportH > 0) ? -1.0f + 2.0f * HOTBAR_BOTTOM_PX / viewportH : -0.9833f;

        float gapToPx     = 2.0f * GUI_SCALE;
        float foodBottomY = bottomY + hotbarH
            + ((viewportH > 0) ? 2.0f * gapToPx / viewportH : 0.011f);

        // Right-align food icons with the right edge of the hotbar
        float totalFoodW   = FOOD_COUNT * stepW - (stepW - foodW);
        float foodStartX   = hotbarW / 2.0f - totalFoodW;

        int food    = player.getFood();
        int maxFood = player.getMaxFood();
        int numIcons = maxFood / 2;

        // Pass 1: empty containers
        FloatBuffer emptyBuf = BufferUtils.createFloatBuffer(numIcons * 6 * 4);
        for (int i = 0; i < numIcons; i++) {
            float x0 = foodStartX + i * stepW;
            float y0 = foodBottomY;
            emptyBuf.put(x0).put(y0).put(0f).put(1f);
            emptyBuf.put(x0+foodW).put(y0).put(1f).put(1f);
            emptyBuf.put(x0+foodW).put(y0+foodH).put(1f).put(0f);
            emptyBuf.put(x0).put(y0).put(0f).put(1f);
            emptyBuf.put(x0+foodW).put(y0+foodH).put(1f).put(0f);
            emptyBuf.put(x0).put(y0+foodH).put(0f).put(0f);
        }
        emptyBuf.flip();
        glBindBuffer(GL_ARRAY_BUFFER, foodVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, emptyBuf);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, foodEmptyTexId);
        iconShader.setVector4f("uColor", 0.35f, 0.35f, 0.35f, 1.0f);
        glBindVertexArray(foodVao);
        glDrawArrays(GL_TRIANGLES, 0, numIcons * 6);

        // Pass 2: filled and half food icons
        int filledIcons = food / 2;
        boolean hasHalf = food % 2 == 1;
        int activeDraw  = filledIcons + (hasHalf ? 1 : 0);
        if (activeDraw > 0) {
            FloatBuffer filledBuf = BufferUtils.createFloatBuffer(activeDraw * 6 * 4);
            for (int i = 0; i < filledIcons; i++) {
                float x0 = foodStartX + i * stepW;
                float y0 = foodBottomY;
                filledBuf.put(x0).put(y0).put(0f).put(1f);
                filledBuf.put(x0+foodW).put(y0).put(1f).put(1f);
                filledBuf.put(x0+foodW).put(y0+foodH).put(1f).put(0f);
                filledBuf.put(x0).put(y0).put(0f).put(1f);
                filledBuf.put(x0+foodW).put(y0+foodH).put(1f).put(0f);
                filledBuf.put(x0).put(y0+foodH).put(0f).put(0f);
            }
            if (hasHalf) {
                float x0 = foodStartX + filledIcons * stepW;
                float y0 = foodBottomY;
                filledBuf.put(x0).put(y0).put(0f).put(1f);
                filledBuf.put(x0+foodW).put(y0).put(1f).put(1f);
                filledBuf.put(x0+foodW).put(y0+foodH).put(1f).put(0f);
                filledBuf.put(x0).put(y0).put(0f).put(1f);
                filledBuf.put(x0+foodW).put(y0+foodH).put(1f).put(0f);
                filledBuf.put(x0).put(y0+foodH).put(0f).put(0f);
            }
            filledBuf.flip();
            glBindBuffer(GL_ARRAY_BUFFER, foodVbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, filledBuf);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            // Draw full food icons
            if (filledIcons > 0) {
                glBindTexture(GL_TEXTURE_2D, foodFullTexId);
                iconShader.setVector4f("uColor", 1f, 1f, 1f, 1f);
                glBindVertexArray(foodVao);
                glDrawArrays(GL_TRIANGLES, 0, filledIcons * 6);
            }
            // Draw half food icon
            if (hasHalf) {
                glBindTexture(GL_TEXTURE_2D, foodHalfTexId);
                iconShader.setVector4f("uColor", 1f, 1f, 1f, 1f);
                glBindVertexArray(foodVao);
                glDrawArrays(GL_TRIANGLES, filledIcons * 6, 6);
            }
        }

        glBindTexture(GL_TEXTURE_2D, 0);
    }

    // -------------------------------------------------------------------------
    // Player arm / held-item
    // -------------------------------------------------------------------------

    /**
     * Allocates the VAO/VBOs used to render the player's arm and held block.
     * Both quads are fully re-uploaded each frame in {@link #renderArm()}.
     */
    private void buildArm() {
        int stride = 4 * Float.BYTES; // 2 pos + 2 uv

        armVao = glGenVertexArrays();
        armVbo = glGenBuffers();
        glBindVertexArray(armVao);
        glBindBuffer(GL_ARRAY_BUFFER, armVbo);
        glBufferData(GL_ARRAY_BUFFER, (long)(6 * 4) * Float.BYTES, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        heldItemVao = glGenVertexArrays();
        heldItemVbo = glGenBuffers();
        glBindVertexArray(heldItemVao);
        glBindBuffer(GL_ARRAY_BUFFER, heldItemVbo);
        glBufferData(GL_ARRAY_BUFFER, (long)(6 * 4) * Float.BYTES, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /**
     * Renders the player's arm in the lower-right corner of the screen.
     * The arm is a parallelogram that leans toward the center (Minecraft-style).
     * If the selected hotbar slot contains a block that has an icon, the block's
     * icon is rendered at the hand position (top of the arm).
     */
    private void renderArm() {
        if (armTexId == 0) return;

        iconShader.use();
        iconShader.setInt("uIcons", 0);
        glActiveTexture(GL_TEXTURE0);

        // --- Compute arm parallelogram geometry ---
        float armWpx    = ARM_W_PX    * GUI_SCALE;
        float armHpx    = ARM_H_PX    * GUI_SCALE;
        float armSkewPx = ARM_SKEW_PX * GUI_SCALE;
        float rightPx   = ARM_RIGHT_PX * GUI_SCALE;
        float belowPx   = ARM_BELOW_PX * GUI_SCALE;

        // NDC fallback values below assume a 1280×720 viewport.
        // Note: armWpx/armHpx/armSkewPx already include the GUI_SCALE factor.
        // e.g. armH fallback = 2 * (85*2) / 720 = 0.47222
        float armW    = (viewportW > 0) ? 2.0f * armWpx    / viewportW : 0.125f;
        float armH    = (viewportH > 0) ? 2.0f * armHpx    / viewportH : 0.47222f;
        float skewX   = (viewportW > 0) ? 2.0f * armSkewPx / viewportW : 0.078125f;
        float rightOff = (viewportW > 0) ? 2.0f * rightPx   / viewportW : 0.00625f;
        float belowOff = (viewportH > 0) ? 2.0f * belowPx   / viewportH : 0.05556f;

        // Arm corners in NDC (screen right=+1.0, screen bottom=-1.0).
        // Bottom edge is partially below the visible screen so the arm appears
        // to extend naturally from outside the frame.
        float brX =  1.0f - rightOff;          // bottom-right x
        float brY = -1.0f - belowOff;          // bottom-right y (below screen)
        float blX = brX - armW;                // bottom-left x
        float blY = brY;                       // bottom-left y
        float trX = brX - skewX;              // top-right x (lean toward center)
        float trY = brY + armH;               // top-right y (visible)
        float tlX = trX - armW;               // top-left x
        float tlY = trY;                       // top-left y

        // 3-D depth offset in NDC: simulates the arm box extending into the scene
        // (right face goes right and slightly upward, top face is the upper end-cap).
        // Fallback values assume a 1280×720 viewport:
        //   depthX = 2 * (12 * 2) / 1280 = 48/1280 = 0.0375
        //   depthY = 2 * (12 * 2 * 0.5) / 720 = 24/720 ≈ 0.03333
        float depthPxScaled = ARM_DEPTH_PX * GUI_SCALE;
        float depthX = (viewportW > 0) ? 2.0f * depthPxScaled          / viewportW : 0.0375f;
        float depthY = (viewportH > 0) ? 2.0f * (depthPxScaled * 0.5f) / viewportH : 0.03333f;

        // Back corners (depth offset applied to the right edge and top edge)
        float brRX = brX + depthX, brRY = brY + depthY;   // bottom-right back
        float trRX = trX + depthX, trRY = trY + depthY;   // top-right back
        float tlTX = tlX + depthX, tlTY = tlY + depthY;   // top-left back

        glBindTexture(GL_TEXTURE_2D, armTexId);
        glBindVertexArray(armVao);

        // --- 1. Right side face (dark shadow – drawn first so front face covers its left edge) ---
        // UV uses the right half of the texture (u 0.5→1) with a dark tint to simulate shadow.
        armBuf.clear();
        armBuf.put(brX).put(brY).put(0.5f).put(1f);
        armBuf.put(brRX).put(brRY).put(1f).put(1f);
        armBuf.put(trRX).put(trRY).put(1f).put(0f);
        armBuf.put(brX).put(brY).put(0.5f).put(1f);
        armBuf.put(trRX).put(trRY).put(1f).put(0f);
        armBuf.put(trX).put(trY).put(0.5f).put(0f);
        armBuf.flip();
        glBindBuffer(GL_ARRAY_BUFFER, armVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, armBuf);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        iconShader.setVector4f("uColor", 0.55f, 0.55f, 0.55f, 1f);
        glDrawArrays(GL_TRIANGLES, 0, 6);

        // --- 2. Top face (upper end-cap, slightly darker than front) ---
        // Samples the top row of the texture (v=0) to pick up the hand colour.
        armBuf.clear();
        armBuf.put(tlX).put(tlY).put(0f).put(0f);
        armBuf.put(trX).put(trY).put(1f).put(0f);
        armBuf.put(trRX).put(trRY).put(1f).put(0f);
        armBuf.put(tlX).put(tlY).put(0f).put(0f);
        armBuf.put(trRX).put(trRY).put(1f).put(0f);
        armBuf.put(tlTX).put(tlTY).put(0f).put(0f);
        armBuf.flip();
        glBindBuffer(GL_ARRAY_BUFFER, armVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, armBuf);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        iconShader.setVector4f("uColor", 0.80f, 0.80f, 0.80f, 1f);
        glDrawArrays(GL_TRIANGLES, 0, 6);

        // --- 3. Front face (full brightness, drawn last so it covers the side/top edges) ---
        // UV: v=0 → top of arm texture (hand), v=1 → bottom (sleeve)
        armBuf.clear();
        armBuf.put(blX).put(blY).put(0f).put(1f);
        armBuf.put(brX).put(brY).put(1f).put(1f);
        armBuf.put(trX).put(trY).put(1f).put(0f);
        armBuf.put(blX).put(blY).put(0f).put(1f);
        armBuf.put(trX).put(trY).put(1f).put(0f);
        armBuf.put(tlX).put(tlY).put(0f).put(0f);
        armBuf.flip();
        glBindBuffer(GL_ARRAY_BUFFER, armVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, armBuf);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        iconShader.setVector4f("uColor", 1f, 1f, 1f, 1f);
        glDrawArrays(GL_TRIANGLES, 0, 6);

        // --- Draw held block icon at the hand (top of arm) ---
        BlockType selected = player.getSelectedBlock();
        int iconIdx = TextureAtlas.getIconIndex(selected);
        if (iconIdx >= 0) {
            float heldPx = HELD_ICON_PX * GUI_SCALE;
            // NDC fallback values assume a 1280×720 viewport
            float heldW  = (viewportW > 0) ? 2.0f * heldPx / viewportW : 0.1f;
            float heldH  = (viewportH > 0) ? 2.0f * heldPx / viewportH : 0.17778f;

            // Position: centred on the arm's top edge, raised slightly above it
            float armTopCenterX = (tlX + trX) / 2.0f;
            float raiseOff = (viewportH > 0) ? 2.0f * (HELD_ICON_RAISE_PX * GUI_SCALE) / viewportH : 0.02222f;
            float iy1 = trY + raiseOff;       // top of icon
            float iy0 = iy1 - heldH;          // bottom of icon
            float ix0 = armTopCenterX - heldW / 2.0f;
            float ix1 = armTopCenterX + heldW / 2.0f;

            float[] uv = TextureAtlas.getIconUV(iconIdx);
            float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];

            heldItemBuf.clear();
            heldItemBuf.put(ix0).put(iy0).put(u0).put(v1);
            heldItemBuf.put(ix1).put(iy0).put(u1).put(v1);
            heldItemBuf.put(ix1).put(iy1).put(u1).put(v0);
            heldItemBuf.put(ix0).put(iy0).put(u0).put(v1);
            heldItemBuf.put(ix1).put(iy1).put(u1).put(v0);
            heldItemBuf.put(ix0).put(iy1).put(u0).put(v0);
            heldItemBuf.flip();

            glBindBuffer(GL_ARRAY_BUFFER, heldItemVbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, heldItemBuf);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            iconShader.setVector4f("uColor", 1f, 1f, 1f, 1f);
            textureAtlas.bindIcons();
            glBindVertexArray(heldItemVao);
            glDrawArrays(GL_TRIANGLES, 0, 6);
        }

        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Generates a simple brown arm texture programmatically.
     * The arm is 16×48 pixels with horizontal shading to give a rounded look.
     *
     * @return OpenGL texture ID
     */
    private static int buildArmTexture() {
        int w = 16, h = 48;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        // Base skin colour (warm brown, similar to a Minecraft character)
        int mainR = 0xC7, mainG = 0x85, mainB = 0x40;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Horizontal shading: dark shadow on the left quarter,
                // full brightness in the middle, slight shadow on the right quarter.
                float hFactor;
                if (x < w / 4) {
                    hFactor = 0.55f + 0.15f * ((float) x / (w / 4));
                } else if (x > w * 3 / 4) {
                    hFactor = 0.85f - 0.10f * ((float)(x - w * 3 / 4) / (w / 4));
                } else {
                    hFactor = 1.0f;
                }
                // Vertical shading: slightly darker toward the bottom (down-light)
                float vFactor = 1.0f - 0.12f * ((float) y / h);
                float factor = hFactor * vFactor;

                int r = Math.min(255, (int)(mainR * factor));
                int g = Math.min(255, (int)(mainG * factor));
                int b = Math.min(255, (int)(mainB * factor));

                img.setRGB(x, y, (255 << 24) | (r << 16) | (g << 8) | b);
            }
        }

        int tw = img.getWidth();
        int th = img.getHeight();
        int[] pixels = img.getRGB(0, 0, tw, th, null, 0, tw);
        ByteBuffer buf = BufferUtils.createByteBuffer(tw * th * 4);
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
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, tw, th, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glBindTexture(GL_TEXTURE_2D, 0);
        return id;
    }

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
        String path = "/textures/Blocks/" + name + ".png";
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
        glDeleteVertexArrays(heartVao);
        glDeleteBuffers(heartVbo);
        if (fullHeartTexId != 0) glDeleteTextures(fullHeartTexId);
        if (halfHeartTexId != 0) glDeleteTextures(halfHeartTexId);
        glDeleteVertexArrays(bubbleVao);
        glDeleteBuffers(bubbleVbo);
        if (bubbleFullTexId  != 0) glDeleteTextures(bubbleFullTexId);
        if (bubbleEmptyTexId != 0) glDeleteTextures(bubbleEmptyTexId);
        glDeleteVertexArrays(foodVao);
        glDeleteBuffers(foodVbo);
        if (foodFullTexId  != 0) glDeleteTextures(foodFullTexId);
        if (foodHalfTexId  != 0) glDeleteTextures(foodHalfTexId);
        if (foodEmptyTexId != 0) glDeleteTextures(foodEmptyTexId);
        glDeleteVertexArrays(highlightVao);
        glDeleteBuffers(highlightVbo);
        glDeleteVertexArrays(armVao);
        glDeleteBuffers(armVbo);
        glDeleteVertexArrays(heldItemVao);
        glDeleteBuffers(heldItemVbo);
        if (armTexId != 0) glDeleteTextures(armTexId);
    }
}
