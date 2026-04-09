package com.blockgame.rendering;

import com.blockgame.mob.Mob;
import com.blockgame.mob.MobManager;
import com.blockgame.mob.MobType;
import com.blockgame.world.Chunk;
import com.blockgame.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL14.GL_FUNC_ADD;
import static org.lwjgl.opengl.GL14.glBlendEquation;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders all {@link Mob}s using the skinned model registered in each
 * {@link MobType}.
 *
 * <p>The renderer is fully data-driven: it iterates {@link MobType#values()}
 * to load textures and dispatch draw calls, so no code changes are needed
 * here when a new mob type is added.  All geometry logic lives in the
 * {@link com.blockgame.mob.MobModel} subclass associated with each type.
 *
 * <p>Vertex layout (9 floats) is identical to {@link ChunkMesh} so the
 * existing world vertex/fragment shaders can be reused with a different
 * texture bound.
 */
public class MobRenderer {

    private static final Logger LOG = Logger.getLogger(MobRenderer.class.getName());

    // -------------------------------------------------------------------------
    // Vertex buffer sizing
    //
    // Spider has the most parts: 2 body parts + 8 legs = 10 parts.
    // 10 parts × 6 faces × 6 verts = 360 verts per mob.
    // -------------------------------------------------------------------------
    private static final int FLOATS_PER_VERTEX = 9;   // pos(3)+uv(2)+normal(3)+skyLight(1)
    // Spider body pass: 2 body parts + 8 legs = 10 parts × 6 faces × 6 verts = 360.
    // This is the maximum vertex count for any single draw call (any type, any pass).
    private static final int MAX_VERTS_PER_MOB = 360;
    private static final int MAX_TOTAL_MOBS    = 32;  // upper bound for VBO pre-allocation

    // -------------------------------------------------------------------------
    // GPU resources
    // -------------------------------------------------------------------------
    private Shader shader;
    private int vao;
    private int vbo;

    /**
     * Per-type texture IDs.  {@code int[0]} = primary texture ID;
     * {@code int[1]} = overlay texture ID (0 if the type has no overlay).
     */
    private final Map<MobType, int[]> texIds = new EnumMap<>(MobType.class);

    private MobManager mobManager;

    // Directional light - matches the world renderer
    private static final Vector3f LIGHT_DIR =
        new Vector3f(-0.4f, -1.0f, -0.3f).normalize();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public MobRenderer() {
        init();
    }

    private void init() {
        shader = new Shader("shaders/vertex.glsl", "shaders/fragment.glsl");

        // Load textures for every registered mob type – no per-type code required
        for (MobType type : MobType.values()) {
            int primary = loadTexture(type.primaryTexture);
            int overlay = (type.overlayTexture != null)
                          ? loadTexture(type.overlayTexture) : 0;
            texIds.put(type, new int[]{ primary, overlay });
        }

        int bufferFloats = MAX_TOTAL_MOBS * MAX_VERTS_PER_MOB * FLOATS_PER_VERTEX;

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) bufferFloats * Float.BYTES, GL_DYNAMIC_DRAW);

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
    }

    // -------------------------------------------------------------------------
    // API
    // -------------------------------------------------------------------------

    /** Connects the mob manager whose mobs will be rendered each frame. */
    public void setMobManager(MobManager mm) {
        this.mobManager = mm;
    }

    /**
     * Renders all mobs.  Must be called from the OpenGL thread, after the world
     * pass and before the HUD pass.
     *
     * @param view       current view matrix
     * @param projection current projection matrix
     */
    public void render(Matrix4f view, Matrix4f projection) {
        if (mobManager == null) return;
        List<Mob> mobs = mobManager.getMobs();
        if (mobs.isEmpty()) return;

        // Set up shared shader uniforms once
        shader.use();
        shader.setMatrix4f("model",      new Matrix4f().identity());
        shader.setMatrix4f("view",       view);
        shader.setMatrix4f("projection", projection);
        shader.setVector3f("lightDir",   LIGHT_DIR);
        shader.setFloat("ambientStrength", 0.40f);
        shader.setInt("uTexture", 0);

        float fogEnd = (World.RENDER_DISTANCE - 3) * Chunk.SIZE;
        shader.setVector3f("uFogColor", new Vector3f(0.53f, 0.81f, 0.98f));
        shader.setFloat("uFogStart", fogEnd * 0.6f);
        shader.setFloat("uFogEnd",   fogEnd);

        // Opaque primary passes (one draw call per mob type)
        for (MobType type : MobType.values()) {
            int primaryTexId = texIds.get(type)[0];
            drawGroup(mobs, type, primaryTexId, 0);
        }

        // Alpha-blended overlay passes (only for types that have an overlay)
        glEnable(GL_BLEND);
        glBlendEquation(GL_FUNC_ADD);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        for (MobType type : MobType.values()) {
            if (!type.hasOverlay()) continue;
            int overlayTexId = texIds.get(type)[1];
            drawGroup(mobs, type, overlayTexId, 1);
        }

        glDisable(GL_BLEND);
    }

    // -------------------------------------------------------------------------
    // Grouped draw helpers
    // -------------------------------------------------------------------------

    /**
     * Builds and draws the mesh for all mobs of {@code type} in one draw call.
     *
     * @param pass 0 = primary body, 1 = overlay (spider eyes / sheep wool)
     */
    private void drawGroup(List<Mob> allMobs, MobType type, int texId, int pass) {
        if (texId == 0) return;

        List<Float> buf = new ArrayList<>();
        for (Mob mob : allMobs) {
            if (mob.type == type) {
                type.model.buildMesh(buf, mob, pass);
            }
        }
        if (buf.isEmpty()) return;

        int vertexCount = buf.size() / FLOATS_PER_VERTEX;
        FloatBuffer fb = BufferUtils.createFloatBuffer(buf.size());
        for (float f : buf) fb.put(f);
        fb.flip();

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, fb);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texId);

        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        glBindVertexArray(0);

        glBindTexture(GL_TEXTURE_2D, 0);
    }

    // -------------------------------------------------------------------------
    // Texture loading
    // -------------------------------------------------------------------------

    /**
     * Loads a PNG from the classpath and uploads it as a nearest-neighbour RGBA
     * texture.  Returns {@code 0} on any failure (gracefully degrades to invisible).
     */
    private static int loadTexture(String path) {
        try (InputStream in = MobRenderer.class.getResourceAsStream(path)) {
            if (in == null) {
                LOG.warning("Mob texture not found: " + path);
                return 0;
            }
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                LOG.warning("Failed to decode mob texture: " + path);
                return 0;
            }

            BufferedImage argb = new BufferedImage(
                img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            argb.createGraphics().drawImage(img, 0, 0, null);

            int w = argb.getWidth(), h = argb.getHeight();
            int[] pixels = argb.getRGB(0, 0, w, h, null, 0, w);

            ByteBuffer byteBuffer = BufferUtils.createByteBuffer(w * h * 4);
            for (int pixel : pixels) {
                byteBuffer.put((byte) ((pixel >> 16) & 0xFF)); // R
                byteBuffer.put((byte) ((pixel >>  8) & 0xFF)); // G
                byteBuffer.put((byte) ( pixel        & 0xFF)); // B
                byteBuffer.put((byte) ((pixel >> 24) & 0xFF)); // A
            }
            byteBuffer.flip();

            int id = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, id);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0,
                         GL_RGBA, GL_UNSIGNED_BYTE, byteBuffer);
            glBindTexture(GL_TEXTURE_2D, 0);
            return id;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error loading mob texture: " + path, e);
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    public void cleanup() {
        shader.cleanup();
        for (int[] ids : texIds.values()) {
            if (ids[0] != 0) glDeleteTextures(ids[0]);
            if (ids[1] != 0) glDeleteTextures(ids[1]);
        }
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
    }
}
