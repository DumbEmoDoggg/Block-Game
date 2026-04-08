package com.blockgame.rendering;

import com.blockgame.mob.Mob;
import com.blockgame.mob.MobManager;
import com.blockgame.world.Chunk;
import com.blockgame.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders all {@link Mob}s as skinned Steve models.
 *
 * <p>The model is built from six body-part cuboids (head, torso, two arms, two
 * legs) UV-mapped to the standard 64×64 Minecraft skin texture format.  Arm
 * and leg swing is animated with a sine wave whose phase is driven by the
 * mob's {@link Mob#limbSwing} accumulator.
 *
 * <p>Vertex layout (9 floats) is identical to {@link ChunkMesh} so the existing
 * world vertex/fragment shaders can be reused with a different texture bound.
 */
public class MobRenderer {

    private static final Logger LOG = Logger.getLogger(MobRenderer.class.getName());

    // -------------------------------------------------------------------------
    // Steve model dimensions (in block-units, 16 px = 1 block)
    // -------------------------------------------------------------------------

    // Body parts (half-sizes):
    //   Head  :  8 × 8 × 8  pixels  →  0.50 × 0.50 × 0.50 blocks
    //   Body  :  8 × 12 × 4 pixels  →  0.50 × 0.75 × 0.25 blocks
    //   Arms  :  4 × 12 × 4 pixels  →  0.25 × 0.75 × 0.25 blocks
    //   Legs  :  4 × 12 × 4 pixels  →  0.25 × 0.75 × 0.25 blocks

    // Body-part boxes in Steve's LOCAL space (feet at y=0, Steve faces −Z):
    //   Head   : x[−0.25, 0.25]  y[1.50, 2.00]  z[−0.25, 0.25]
    //   Body   : x[−0.25, 0.25]  y[0.75, 1.50]  z[−0.125, 0.125]
    //   R arm  : x[ 0.25, 0.50]  y[0.75, 1.50]  z[−0.125, 0.125]
    //   L arm  : x[−0.50,−0.25]  y[0.75, 1.50]  z[−0.125, 0.125]
    //   R leg  : x[ 0.00, 0.25]  y[0.00, 0.75]  z[−0.125, 0.125]
    //   L leg  : x[−0.25, 0.00]  y[0.00, 0.75]  z[−0.125, 0.125]

    // -------------------------------------------------------------------------
    // Steve skin UV regions (64×64 texture, face order: N,S,E,W,Top,Bottom)
    // Standard Minecraft skin layout:
    //   N = front (−Z), S = back (+Z), E = right (+X), W = left (−X)
    // -------------------------------------------------------------------------

    // Each entry: {u0, v0, u1, v1} in normalised [0,1] texture coordinates
    private static final float[][] HEAD_UV = {
        { 8f/64,  8f/64, 16f/64, 16f/64 }, // N: face front
        {24f/64,  8f/64, 32f/64, 16f/64 }, // S: face back
        {16f/64,  8f/64, 24f/64, 16f/64 }, // E: right side of head
        { 0f/64,  8f/64,  8f/64, 16f/64 }, // W: left  side of head
        { 8f/64,  0f/64, 16f/64,  8f/64 }, // Top
        {16f/64,  0f/64, 24f/64,  8f/64 }, // Bottom
    };

    private static final float[][] BODY_UV = {
        {20f/64, 20f/64, 28f/64, 32f/64 }, // N: shirt front
        {32f/64, 20f/64, 40f/64, 32f/64 }, // S: shirt back
        {16f/64, 20f/64, 20f/64, 32f/64 }, // E: shirt right
        {28f/64, 20f/64, 32f/64, 32f/64 }, // W: shirt left
        {20f/64, 16f/64, 28f/64, 20f/64 }, // Top
        {28f/64, 16f/64, 36f/64, 20f/64 }, // Bottom
    };

    private static final float[][] RARM_UV = {
        {44f/64, 20f/64, 48f/64, 32f/64 }, // N: front
        {52f/64, 20f/64, 56f/64, 32f/64 }, // S: back
        {40f/64, 20f/64, 44f/64, 32f/64 }, // E: outer (Steve's right)
        {48f/64, 20f/64, 52f/64, 32f/64 }, // W: inner
        {44f/64, 16f/64, 48f/64, 20f/64 }, // Top
        {48f/64, 16f/64, 52f/64, 20f/64 }, // Bottom
    };

    private static final float[][] LARM_UV = {
        {36f/64, 52f/64, 40f/64, 64f/64 }, // N: front
        {44f/64, 52f/64, 48f/64, 64f/64 }, // S: back
        {40f/64, 52f/64, 44f/64, 64f/64 }, // E: inner (toward body)
        {32f/64, 52f/64, 36f/64, 64f/64 }, // W: outer (Steve's left)
        {36f/64, 48f/64, 40f/64, 52f/64 }, // Top
        {40f/64, 48f/64, 44f/64, 52f/64 }, // Bottom
    };

    private static final float[][] RLEG_UV = {
        { 4f/64, 20f/64,  8f/64, 32f/64 }, // N: front
        {12f/64, 20f/64, 16f/64, 32f/64 }, // S: back
        { 0f/64, 20f/64,  4f/64, 32f/64 }, // E: outer (Steve's right)
        { 8f/64, 20f/64, 12f/64, 32f/64 }, // W: inner
        { 4f/64, 16f/64,  8f/64, 20f/64 }, // Top
        { 8f/64, 16f/64, 12f/64, 20f/64 }, // Bottom
    };

    private static final float[][] LLEG_UV = {
        {20f/64, 52f/64, 24f/64, 64f/64 }, // N: front
        {28f/64, 52f/64, 32f/64, 64f/64 }, // S: back
        {24f/64, 52f/64, 28f/64, 64f/64 }, // E: inner (toward body)
        {16f/64, 52f/64, 20f/64, 64f/64 }, // W: outer (Steve's left)
        {20f/64, 48f/64, 24f/64, 52f/64 }, // Top
        {24f/64, 48f/64, 28f/64, 52f/64 }, // Bottom
    };

    // -------------------------------------------------------------------------
    // Face indices in the UV arrays above
    // -------------------------------------------------------------------------
    private static final int F_NORTH  = 0; // −Z
    private static final int F_SOUTH  = 1; // +Z
    private static final int F_EAST   = 2; // +X
    private static final int F_WEST   = 3; // −X
    private static final int F_TOP    = 4; // +Y
    private static final int F_BOTTOM = 5; // −Y

    // Floats per vertex: pos(3) + uv(2) + normal(3) + skyLight(1) = 9
    private static final int FLOATS_PER_VERTEX = 9;
    // 6 parts × 6 faces × 6 verts = 216 verts per mob
    private static final int VERTS_PER_MOB = 6 * 6 * 6;

    // -------------------------------------------------------------------------
    // GPU resources
    // -------------------------------------------------------------------------

    private Shader  shader;
    private int     skinTexId;
    private int     vao;
    private int     vbo;

    private MobManager mobManager;

    // Directional light – matches the world renderer
    private static final Vector3f LIGHT_DIR =
        new Vector3f(-0.4f, -1.0f, -0.3f).normalize();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public MobRenderer() {
        init();
    }

    private void init() {
        // Reuse the same world vertex/fragment shaders
        shader = new Shader("shaders/vertex.glsl", "shaders/fragment.glsl");

        skinTexId = loadSkinTexture(
            "/textures/Mobs/2026_04_01_steve-in-a-suit-23963928.png");

        // Create a dynamic VAO/VBO sized for the maximum expected mob count
        int maxMobs = 20;
        int bufferFloats = maxMobs * VERTS_PER_MOB * FLOATS_PER_VERTEX;

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
        if (mobManager == null || skinTexId == 0) return;

        List<Mob> mobs = mobManager.getMobs();
        if (mobs.isEmpty()) return;

        // Build CPU-side vertex data
        List<Float> buf = new ArrayList<>(mobs.size() * VERTS_PER_MOB * FLOATS_PER_VERTEX);
        for (Mob mob : mobs) {
            buildSteveMesh(buf, mob);
        }

        int vertexCount = buf.size() / FLOATS_PER_VERTEX;
        FloatBuffer fb = BufferUtils.createFloatBuffer(buf.size());
        for (float f : buf) fb.put(f);
        fb.flip();

        // Upload to GPU
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, fb);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        // Draw
        shader.use();
        shader.setMatrix4f("model",      new Matrix4f().identity());
        shader.setMatrix4f("view",       view);
        shader.setMatrix4f("projection", projection);
        shader.setVector3f("lightDir",   LIGHT_DIR);
        shader.setFloat("ambientStrength", 0.40f);
        shader.setInt("uTexture", 0);

        float fogEnd   = (World.RENDER_DISTANCE - 3) * Chunk.SIZE;
        float fogStart = fogEnd * 0.6f;
        shader.setVector3f("uFogColor", new Vector3f(0.53f, 0.81f, 0.98f));
        shader.setFloat("uFogStart", fogStart);
        shader.setFloat("uFogEnd",   fogEnd);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, skinTexId);

        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        glBindVertexArray(0);

        glBindTexture(GL_TEXTURE_2D, 0);
    }

    // -------------------------------------------------------------------------
    // Mesh construction
    // -------------------------------------------------------------------------

    /**
     * Appends all Steve body-part faces for one mob to {@code buf}.
     *
     * <p>Steve's local space: feet at origin, facing −Z, +X = Steve's right,
     * +Y = up.  The mob's world transform (translate + rotateY) is baked into
     * each vertex position so the single draw call requires a model = identity
     * matrix.
     */
    private void buildSteveMesh(List<Float> buf, Mob mob) {
        float yawRad = (float) Math.toRadians(mob.yaw);
        // When yaw=0 Steve faces −Z; rotateY(−yaw) maps −Z → facing direction.
        Matrix4f mobModel = new Matrix4f()
            .translate(mob.position.x, mob.position.y, mob.position.z)
            .rotateY(-yawRad);

        float swing = mob.getSwingAngle();

        // --- Head (no swing) ---
        addBox(buf, mobModel,
               -0.25f, 1.50f, -0.25f,  0.25f, 2.00f,  0.25f, HEAD_UV);

        // --- Body (no swing) ---
        addBox(buf, mobModel,
               -0.25f, 0.75f, -0.125f,  0.25f, 1.50f,  0.125f, BODY_UV);

        // --- Right arm (Steve's right at +X), pivots at shoulder (0.375, 1.5, 0) ---
        Matrix4f rArmMat = new Matrix4f(mobModel)
            .translate( 0.375f, 1.5f, 0f)
            .rotateX(swing)
            .translate(-0.375f, -1.5f, 0f);
        addBox(buf, rArmMat,
               0.25f, 0.75f, -0.125f,  0.50f, 1.50f,  0.125f, RARM_UV);

        // --- Left arm (Steve's left at −X), pivots at shoulder (−0.375, 1.5, 0) ---
        Matrix4f lArmMat = new Matrix4f(mobModel)
            .translate(-0.375f, 1.5f, 0f)
            .rotateX(-swing)
            .translate( 0.375f, -1.5f, 0f);
        addBox(buf, lArmMat,
               -0.50f, 0.75f, -0.125f, -0.25f, 1.50f,  0.125f, LARM_UV);

        // --- Right leg, pivots at hip (0.125, 0.75, 0) ---
        Matrix4f rLegMat = new Matrix4f(mobModel)
            .translate( 0.125f, 0.75f, 0f)
            .rotateX(-swing)
            .translate(-0.125f, -0.75f, 0f);
        addBox(buf, rLegMat,
                0.00f, 0.00f, -0.125f,  0.25f, 0.75f,  0.125f, RLEG_UV);

        // --- Left leg, pivots at hip (−0.125, 0.75, 0) ---
        Matrix4f lLegMat = new Matrix4f(mobModel)
            .translate(-0.125f, 0.75f, 0f)
            .rotateX(swing)
            .translate( 0.125f, -0.75f, 0f);
        addBox(buf, lLegMat,
               -0.25f, 0.00f, -0.125f,  0.00f, 0.75f,  0.125f, LLEG_UV);
    }

    /** Emits all 6 faces of a box into {@code buf} using the given transforms. */
    private void addBox(List<Float> buf, Matrix4f transform,
                        float x0, float y0, float z0,
                        float x1, float y1, float z1,
                        float[][] uvFaces) {
        addFace(buf, transform, x0, y0, z0, x1, y1, z1, F_NORTH,  uvFaces[F_NORTH]);
        addFace(buf, transform, x0, y0, z0, x1, y1, z1, F_SOUTH,  uvFaces[F_SOUTH]);
        addFace(buf, transform, x0, y0, z0, x1, y1, z1, F_EAST,   uvFaces[F_EAST]);
        addFace(buf, transform, x0, y0, z0, x1, y1, z1, F_WEST,   uvFaces[F_WEST]);
        addFace(buf, transform, x0, y0, z0, x1, y1, z1, F_TOP,    uvFaces[F_TOP]);
        addFace(buf, transform, x0, y0, z0, x1, y1, z1, F_BOTTOM, uvFaces[F_BOTTOM]);
    }

    /**
     * Emits one face (2 triangles = 6 vertices) into {@code buf}.
     *
     * <p>Vertex layout, UV orientation and triangle winding match
     * {@link ChunkMesh} so the same shaders can be used unmodified.
     *
     * @param face one of the F_* constants
     * @param uv   {u0, v0, u1, v1} for the face's texture region
     */
    private void addFace(List<Float> buf, Matrix4f transform,
                         float x0, float y0, float z0,
                         float x1, float y1, float z1,
                         int face, float[] uv) {
        // 4 corner positions in Steve's local space (CCW winding from outside)
        float[][] c;
        float[]   n;
        switch (face) {
            case F_NORTH:  // −Z
                c = new float[][]{{x0,y0,z0},{x0,y1,z0},{x1,y1,z0},{x1,y0,z0}};
                n = new float[]{0,0,-1}; break;
            case F_SOUTH:  // +Z
                c = new float[][]{{x1,y0,z1},{x1,y1,z1},{x0,y1,z1},{x0,y0,z1}};
                n = new float[]{0,0,1}; break;
            case F_EAST:   // +X
                c = new float[][]{{x1,y0,z0},{x1,y1,z0},{x1,y1,z1},{x1,y0,z1}};
                n = new float[]{1,0,0}; break;
            case F_WEST:   // −X
                c = new float[][]{{x0,y0,z1},{x0,y1,z1},{x0,y1,z0},{x0,y0,z0}};
                n = new float[]{-1,0,0}; break;
            case F_TOP:    // +Y
                c = new float[][]{{x0,y1,z0},{x0,y1,z1},{x1,y1,z1},{x1,y1,z0}};
                n = new float[]{0,1,0}; break;
            default:       // F_BOTTOM, −Y
                c = new float[][]{{x0,y0,z0},{x1,y0,z0},{x1,y0,z1},{x0,y0,z1}};
                n = new float[]{0,-1,0}; break;
        }

        // UV per corner – matches ChunkMesh conventions:
        //   side faces: u varies left→right, v1=bottom v0=top
        //   top/bottom: u varies left→right, v0=near  v1=far
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        float[] us, vs;
        if (face == F_TOP || face == F_BOTTOM) {
            us = new float[]{ u0, u0, u1, u1 };
            vs = new float[]{ v0, v1, v1, v0 };
        } else {
            us = new float[]{ u0, u0, u1, u1 };
            vs = new float[]{ v1, v0, v0, v1 };
        }

        // Transform the face normal (rotation only, no translation)
        Vector3f normal = new Vector3f(n[0], n[1], n[2]);
        transform.transformDirection(normal);

        // Emit 2 triangles: indices 0,1,2 and 0,2,3
        for (int i : new int[]{ 0, 1, 2, 0, 2, 3 }) {
            Vector4f pos = new Vector4f(c[i][0], c[i][1], c[i][2], 1f);
            transform.transform(pos);

            buf.add(pos.x);
            buf.add(pos.y);
            buf.add(pos.z);
            buf.add(us[i]);
            buf.add(vs[i]);
            buf.add(normal.x);
            buf.add(normal.y);
            buf.add(normal.z);
            buf.add(1.0f); // skyLight – mobs are always surface-lit
        }
    }

    // -------------------------------------------------------------------------
    // Texture loading
    // -------------------------------------------------------------------------

    /**
     * Loads a skin PNG from the classpath and uploads it as a nearest-neighbour
     * RGBA texture.
     *
     * @param path classpath resource path, e.g.
     *             {@code "/textures/Mobs/steve.png"}
     * @return OpenGL texture ID, or {@code 0} on failure
     */
    private static int loadSkinTexture(String path) {
        try (InputStream in = MobRenderer.class.getResourceAsStream(path)) {
            if (in == null) {
                LOG.warning("Skin texture not found: " + path);
                return 0;
            }
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                LOG.warning("Failed to decode skin texture: " + path);
                return 0;
            }

            // Convert any colour model (including paletted) to ARGB
            BufferedImage argb = new BufferedImage(
                img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            argb.createGraphics().drawImage(img, 0, 0, null);

            int w = argb.getWidth();
            int h = argb.getHeight();
            int[] pixels = argb.getRGB(0, 0, w, h, null, 0, w);

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
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0,
                         GL_RGBA, GL_UNSIGNED_BYTE, buf);
            glBindTexture(GL_TEXTURE_2D, 0);
            return id;

        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error loading skin texture: " + path, e);
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    public void cleanup() {
        shader.cleanup();
        if (skinTexId != 0) glDeleteTextures(skinTexId);
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
    }
}
