package com.blockgame.rendering;

import com.blockgame.mob.Mob;
import com.blockgame.mob.MobManager;
import com.blockgame.mob.MobType;
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
import static org.lwjgl.opengl.GL14.GL_FUNC_ADD;
import static org.lwjgl.opengl.GL14.glBlendEquation;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders all {@link Mob}s using per-type skinned models sourced from
 * Minecraft Classic textures.
 *
 * <p>Supported mob types and their textures:
 * <ul>
 *   <li>Zombie   - zombie.png (64x64, humanoid)</li>
 *   <li>Skeleton - skeleton.png (64x32, humanoid, mirrored limbs)</li>
 *   <li>Creeper  - creeper.png (64x32, four-legged)</li>
 *   <li>Spider   - spider.png + spider_eyes.png (64x32, two-body + eight legs)</li>
 *   <li>Pig      - pig_temperate.png (64x64, quadruped)</li>
 *   <li>Sheep    - sheep.png + sheep_wool.png (64x32, quadruped + wool overlay)</li>
 * </ul>
 *
 * <p>Vertex layout (9 floats) is identical to {@link ChunkMesh} so the existing
 * world vertex/fragment shaders can be reused with a different texture bound.
 */
public class MobRenderer {

    private static final Logger LOG = Logger.getLogger(MobRenderer.class.getName());

    // -------------------------------------------------------------------------
    // Face index constants  (index into the per-part UV arrays)
    // -------------------------------------------------------------------------
    private static final int F_NORTH  = 0; // -Z
    private static final int F_SOUTH  = 1; // +Z
    private static final int F_EAST   = 2; // +X
    private static final int F_WEST   = 3; // -X
    private static final int F_TOP    = 4; // +Y
    private static final int F_BOTTOM = 5; // -Y

    // -------------------------------------------------------------------------
    // UV helper
    //
    // Computes the standard Minecraft cube-UV layout from texture offsets.
    // Layout (Minecraft convention, texW x texH texture):
    //
    //   [top-row] at rows [ty .. ty+sz]:
    //     cols [tx+sz .. tx+sz+sx]       -> F_TOP
    //     cols [tx+sz+sx .. tx+sz+2*sx]  -> F_BOTTOM
    //
    //   [side-row] at rows [ty+sz .. ty+sz+sy]:
    //     cols [tx       .. tx+sz]          -> F_EAST  (+X)
    //     cols [tx+sz    .. tx+sz+sx]       -> F_NORTH (-Z)
    //     cols [tx+sz+sx .. tx+2sz+sx]      -> F_WEST  (-X)
    //     cols [tx+2sz+sx.. tx+2sz+2sx]     -> F_SOUTH (+Z)
    //
    // Returns float[6][4], indexed by F_* constants: {u0, v0, u1, v1}.
    // -------------------------------------------------------------------------
    private static float[][] computeUV(int tx, int ty,
                                       int sx, int sy, int sz,
                                       int texW, int texH) {
        float tw = texW, th = texH;
        return new float[][] {
            { (tx + sz)          / tw, (ty + sz)         / th,
              (tx + sz + sx)     / tw, (ty + sz + sy)    / th }, // F_NORTH
            { (tx + 2*sz + sx)   / tw, (ty + sz)         / th,
              (tx + 2*sz + 2*sx) / tw, (ty + sz + sy)    / th }, // F_SOUTH
            { tx                 / tw, (ty + sz)         / th,
              (tx + sz)          / tw, (ty + sz + sy)    / th }, // F_EAST
            { (tx + sz + sx)     / tw, (ty + sz)         / th,
              (tx + 2*sz + sx)   / tw, (ty + sz + sy)    / th }, // F_WEST
            { (tx + sz)          / tw,  ty               / th,
              (tx + sz + sx)     / tw, (ty + sz)         / th }, // F_TOP
            { (tx + sz + sx)     / tw,  ty               / th,
              (tx + sz + 2*sx)   / tw, (ty + sz)         / th }, // F_BOTTOM
        };
    }

    // -------------------------------------------------------------------------
    // UV arrays - Zombie (64x64, identical to the classic Steve skin layout)
    // -------------------------------------------------------------------------
    private static final float[][] Z_HEAD_UV = computeUV( 0,  0, 8, 8,  8, 64, 64);
    private static final float[][] Z_BODY_UV = computeUV(16, 16, 8, 12, 4, 64, 64);
    private static final float[][] Z_RARM_UV = computeUV(40, 16, 4, 12, 4, 64, 64);
    private static final float[][] Z_LARM_UV = {  // dedicated left-arm region in 64x64
        {36f/64, 52f/64, 40f/64, 64f/64},
        {44f/64, 52f/64, 48f/64, 64f/64},
        {40f/64, 52f/64, 44f/64, 64f/64},
        {32f/64, 52f/64, 36f/64, 64f/64},
        {36f/64, 48f/64, 40f/64, 52f/64},
        {40f/64, 48f/64, 44f/64, 52f/64},
    };
    private static final float[][] Z_RLEG_UV = computeUV( 0, 16, 4, 12, 4, 64, 64);
    private static final float[][] Z_LLEG_UV = {  // dedicated left-leg region in 64x64
        {20f/64, 52f/64, 24f/64, 64f/64},
        {28f/64, 52f/64, 32f/64, 64f/64},
        {24f/64, 52f/64, 28f/64, 64f/64},
        {16f/64, 52f/64, 20f/64, 64f/64},
        {20f/64, 48f/64, 24f/64, 52f/64},
        {24f/64, 48f/64, 28f/64, 52f/64},
    };

    // -------------------------------------------------------------------------
    // UV arrays - Skeleton (64x32, left limbs share UV with right)
    // -------------------------------------------------------------------------
    private static final float[][] SK_HEAD_UV = computeUV( 0,  0, 8, 8,  8, 64, 32);
    private static final float[][] SK_BODY_UV = computeUV(16, 16, 8, 12, 4, 64, 32);
    private static final float[][] SK_ARM_UV  = computeUV(40, 16, 4, 12, 4, 64, 32);
    private static final float[][] SK_LEG_UV  = computeUV( 0, 16, 4, 12, 4, 64, 32);

    // -------------------------------------------------------------------------
    // UV arrays - Creeper (64x32, head+body share Skeleton offsets; legs shorter)
    // -------------------------------------------------------------------------
    private static final float[][] CR_LEG_UV = computeUV(0, 16, 4, 6, 4, 64, 32);

    // -------------------------------------------------------------------------
    // UV arrays - Spider (64x32)
    // -------------------------------------------------------------------------
    private static final float[][] SP_HEAD_UV = computeUV(32,  4,  8, 8,  8, 64, 32);
    private static final float[][] SP_BODY_UV = computeUV( 0, 12, 10, 8, 12, 64, 32);
    private static final float[][] SP_LEG_UV  = computeUV(18,  0, 16, 2,  2, 64, 32);

    // -------------------------------------------------------------------------
    // UV arrays - Pig (64x64)
    //
    // Head and legs reuse the zombie (64x64) offsets.
    // Body: textureOffset(28,8), size(sx=10, sy=16, sz=8) in Minecraft model space.
    // The pig body is rendered with a 90-degree X-rotation in the original Minecraft
    // model, so the physical-to-UV face mapping is remapped here:
    //   Physical F_NORTH <- model TOP,    Physical F_SOUTH <- model BOTTOM
    //   Physical F_TOP   <- model SOUTH,  Physical F_BOTTOM<- model NORTH
    //   Physical F_EAST/WEST unchanged.
    // -------------------------------------------------------------------------
    private static final float[][] PIG_HEAD_UV = computeUV(0, 0, 8, 8, 8, 64, 64);
    private static final float[][] PIG_BODY_UV = {
        {36f/64,  8f/64, 46f/64, 16f/64},  // F_NORTH  <- model TOP
        {46f/64,  8f/64, 56f/64, 16f/64},  // F_SOUTH  <- model BOTTOM
        {28f/64, 16f/64, 36f/64, 32f/64},  // F_EAST   <- model EAST
        {46f/64, 16f/64, 54f/64, 32f/64},  // F_WEST   <- model WEST
        {54f/64, 16f/64, 64f/64, 32f/64},  // F_TOP    <- model SOUTH
        {36f/64, 16f/64, 46f/64, 32f/64},  // F_BOTTOM <- model NORTH
    };
    private static final float[][] PIG_LEG_UV = computeUV(0, 16, 4, 12, 4, 64, 64);

    // -------------------------------------------------------------------------
    // UV arrays - Sheep (64x32)
    //
    // Body uses the same 90-degree rotation remapping as the pig.
    // Body: textureOffset(28,8), size(sx=8, sy=16, sz=6).
    // Head: textureOffset(0,0),  size(sx=6, sy=6,  sz=8).
    // Legs: textureOffset(0,16), size(sx=4, sy=12, sz=4).
    // Sheep wool (sheep_wool.png) uses the same UV regions with expanded boxes.
    //
    // Note: each entry is {u0, v0, u1, v1}. Because the texture is 64x32
    // (non-square), u-coordinates are divided by 64 and v-coordinates by 32.
    // The mixed denominators (/64 and /32) in the same row are intentional.
    // -------------------------------------------------------------------------
    private static final float[][] SH_HEAD_UV = computeUV(0, 0, 6, 6, 8, 64, 32);
    private static final float[][] SH_BODY_UV = {
        // {u0/64, v0/32, u1/64, v1/32}
        {34f/64,  8f/32, 42f/64, 14f/32},  // F_NORTH  <- model TOP
        {42f/64,  8f/32, 50f/64, 14f/32},  // F_SOUTH  <- model BOTTOM
        {28f/64, 14f/32, 34f/64, 30f/32},  // F_EAST   <- model EAST
        {42f/64, 14f/32, 48f/64, 30f/32},  // F_WEST   <- model WEST
        {48f/64, 14f/32, 56f/64, 30f/32},  // F_TOP    <- model SOUTH
        {34f/64, 14f/32, 42f/64, 30f/32},  // F_BOTTOM <- model NORTH
    };
    private static final float[][] SH_LEG_UV = computeUV(0, 16, 4, 12, 4, 64, 32);

    // -------------------------------------------------------------------------
    // Vertex buffer sizing
    //
    // Spider has the most parts: 2 body parts + 8 legs = 10 parts.
    // 10 parts x 6 faces x 6 verts = 360 verts per mob.
    // -------------------------------------------------------------------------
    private static final int FLOATS_PER_VERTEX  = 9;   // pos(3)+uv(2)+normal(3)+skyLight(1)
    // Spider body pass: 2 body parts + 8 legs = 10 parts x 6 faces x 6 verts = 360.
    // This is the maximum vertex count for any single draw call (any type, any pass).
    private static final int MAX_VERTS_PER_MOB  = 360;
    private static final int MAX_TOTAL_MOBS     = 32;  // upper bound for VBO pre-allocation

    // -------------------------------------------------------------------------
    // GPU resources
    // -------------------------------------------------------------------------
    private Shader shader;
    private int vao;
    private int vbo;

    // One texture per mob type (0 = not loaded)
    private int zombieTexId;
    private int skeletonTexId;
    private int creeperTexId;
    private int spiderTexId;
    private int spiderEyesTexId;
    private int pigTexId;
    private int sheepTexId;
    private int sheepWoolTexId;

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

        zombieTexId     = loadTexture("/textures/Mobs/zombie.png");
        skeletonTexId   = loadTexture("/textures/Mobs/skeleton.png");
        creeperTexId    = loadTexture("/textures/Mobs/creeper.png");
        spiderTexId     = loadTexture("/textures/Mobs/spider.png");
        spiderEyesTexId = loadTexture("/textures/Mobs/spider_eyes.png");
        pigTexId        = loadTexture("/textures/Mobs/pig_temperate.png");
        sheepTexId      = loadTexture("/textures/Mobs/sheep.png");
        sheepWoolTexId  = loadTexture("/textures/Mobs/sheep_wool.png");

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

        // Opaque passes (one draw call per mob type)
        drawGroup(mobs, MobType.ZOMBIE,   zombieTexId,   0);
        drawGroup(mobs, MobType.SKELETON, skeletonTexId, 0);
        drawGroup(mobs, MobType.CREEPER,  creeperTexId,  0);
        drawGroup(mobs, MobType.SPIDER,   spiderTexId,   0);
        drawGroup(mobs, MobType.PIG,      pigTexId,      0);
        drawGroup(mobs, MobType.SHEEP,    sheepTexId,    0);

        // Alpha-blended overlay passes
        glEnable(GL_BLEND);
        glBlendEquation(GL_FUNC_ADD);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // Spider eyes overlay (pass 1)
        drawGroup(mobs, MobType.SPIDER, spiderEyesTexId, 1);

        // Sheep wool overlay (pass 1)
        drawGroup(mobs, MobType.SHEEP, sheepWoolTexId, 1);

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
                buildMesh(buf, mob, pass);
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

    private void buildMesh(List<Float> buf, Mob mob, int pass) {
        switch (mob.type) {
            case ZOMBIE:   buildHumanoidMesh(buf, mob, false); break;
            case SKELETON: buildHumanoidMesh(buf, mob, true);  break;
            case CREEPER:  buildCreeperMesh(buf, mob);         break;
            case SPIDER:   buildSpiderMesh(buf, mob, pass);    break;
            case PIG:      buildPigMesh(buf, mob);             break;
            case SHEEP:    buildSheepMesh(buf, mob, pass);     break;
        }
    }

    // -------------------------------------------------------------------------
    // Per-type mesh builders
    // -------------------------------------------------------------------------

    /**
     * Humanoid mesh reused by Zombie and Skeleton.
     *
     * <p>Model: head, torso, two arms, two legs.  For Skeleton (64x32) the left
     * limbs are rendered with the same UV as the right limbs (the texture mirrors
     * them automatically in 64x32 format).
     *
     * @param skeleton {@code true} use 64x32 skeleton UVs;
     *                 {@code false} use 64x64 zombie UVs
     */
    private void buildHumanoidMesh(List<Float> buf, Mob mob, boolean skeleton) {
        float[][] headUV = skeleton ? SK_HEAD_UV : Z_HEAD_UV;
        float[][] bodyUV = skeleton ? SK_BODY_UV : Z_BODY_UV;
        float[][] rArmUV = skeleton ? SK_ARM_UV  : Z_RARM_UV;
        float[][] lArmUV = skeleton ? SK_ARM_UV  : Z_LARM_UV;
        float[][] rLegUV = skeleton ? SK_LEG_UV  : Z_RLEG_UV;
        float[][] lLegUV = skeleton ? SK_LEG_UV  : Z_LLEG_UV;

        float yawRad  = (float) Math.toRadians(mob.yaw);
        Matrix4f base = new Matrix4f()
            .translate(mob.position.x, mob.position.y, mob.position.z)
            .rotateY(-yawRad);

        float swing = mob.getSwingAngle();

        // Head
        addBox(buf, base, -0.25f, 1.50f, -0.25f, 0.25f, 2.00f, 0.25f, headUV);

        // Body
        addBox(buf, base, -0.25f, 0.75f, -0.125f, 0.25f, 1.50f, 0.125f, bodyUV);

        // Right arm - pivot at shoulder (0.375, 1.5, 0)
        Matrix4f rArm = new Matrix4f(base)
            .translate(0.375f, 1.5f, 0f).rotateX(swing).translate(-0.375f, -1.5f, 0f);
        addBox(buf, rArm,  0.25f, 0.75f, -0.125f,  0.50f, 1.50f, 0.125f, rArmUV);

        // Left arm - pivot at shoulder (-0.375, 1.5, 0)
        Matrix4f lArm = new Matrix4f(base)
            .translate(-0.375f, 1.5f, 0f).rotateX(-swing).translate(0.375f, -1.5f, 0f);
        addBox(buf, lArm, -0.50f, 0.75f, -0.125f, -0.25f, 1.50f, 0.125f, lArmUV);

        // Right leg - pivot at hip (0.125, 0.75, 0)
        Matrix4f rLeg = new Matrix4f(base)
            .translate(0.125f, 0.75f, 0f).rotateX(-swing).translate(-0.125f, -0.75f, 0f);
        addBox(buf, rLeg,  0.00f, 0.00f, -0.125f,  0.25f, 0.75f, 0.125f, rLegUV);

        // Left leg - pivot at hip (-0.125, 0.75, 0)
        Matrix4f lLeg = new Matrix4f(base)
            .translate(-0.125f, 0.75f, 0f).rotateX(swing).translate(0.125f, -0.75f, 0f);
        addBox(buf, lLeg, -0.25f, 0.00f, -0.125f,  0.00f, 0.75f, 0.125f, lLegUV);
    }

    /**
     * Creeper mesh: head, body, four legs (no arms).
     *
     * <p>Diagonal leg pairs walk together: front-right + back-left swing forward
     * while front-left + back-right swing backward.
     */
    private void buildCreeperMesh(List<Float> buf, Mob mob) {
        float yawRad  = (float) Math.toRadians(mob.yaw);
        Matrix4f base = new Matrix4f()
            .translate(mob.position.x, mob.position.y, mob.position.z)
            .rotateY(-yawRad);

        float swing = mob.getSwingAngle();

        // Head
        addBox(buf, base, -0.25f, 1.125f, -0.25f, 0.25f, 1.625f, 0.25f, SK_HEAD_UV);

        // Body
        addBox(buf, base, -0.25f, 0.375f, -0.125f, 0.25f, 1.125f, 0.125f, SK_BODY_UV);

        // Front-right leg - pivot at top (0.125, 0.375, -0.125)
        Matrix4f frLeg = new Matrix4f(base)
            .translate(0.125f, 0.375f, -0.125f).rotateX(swing)
            .translate(-0.125f, -0.375f, 0.125f);
        addBox(buf, frLeg,  0.00f, 0.00f, -0.25f,  0.25f, 0.375f,  0.00f, CR_LEG_UV);

        // Front-left leg - pivot at top (-0.125, 0.375, -0.125)
        Matrix4f flLeg = new Matrix4f(base)
            .translate(-0.125f, 0.375f, -0.125f).rotateX(-swing)
            .translate(0.125f, -0.375f, 0.125f);
        addBox(buf, flLeg, -0.25f, 0.00f, -0.25f,  0.00f, 0.375f,  0.00f, CR_LEG_UV);

        // Back-right leg - pivot at top (0.125, 0.375, 0.125)
        Matrix4f brLeg = new Matrix4f(base)
            .translate(0.125f, 0.375f, 0.125f).rotateX(-swing)
            .translate(-0.125f, -0.375f, -0.125f);
        addBox(buf, brLeg,  0.00f, 0.00f,  0.00f,  0.25f, 0.375f,  0.25f, CR_LEG_UV);

        // Back-left leg - pivot at top (-0.125, 0.375, 0.125)
        Matrix4f blLeg = new Matrix4f(base)
            .translate(-0.125f, 0.375f, 0.125f).rotateX(swing)
            .translate(0.125f, -0.375f, -0.125f);
        addBox(buf, blLeg, -0.25f, 0.00f,  0.00f,  0.00f, 0.375f,  0.25f, CR_LEG_UV);
    }

    /**
     * Spider mesh.
     *
     * <p>Pass 0 renders the body (head + abdomen + 8 legs).  Pass 1 renders the
     * glowing eye overlay (same head geometry, spider_eyes.png bound by caller).
     */
    private void buildSpiderMesh(List<Float> buf, Mob mob, int pass) {
        float yawRad  = (float) Math.toRadians(mob.yaw);
        Matrix4f base = new Matrix4f()
            .translate(mob.position.x, mob.position.y, mob.position.z)
            .rotateY(-yawRad);

        // Head (front body segment)
        addBox(buf, base, -0.25f, 0.0625f, -0.75f, 0.25f, 0.5625f, -0.25f, SP_HEAD_UV);

        if (pass == 0) {
            // Abdomen (rear body segment)
            addBox(buf, base,
                   -0.3125f, 0.0625f, -0.375f,
                    0.3125f, 0.5625f,  0.375f, SP_BODY_UV);

            // Eight legs (four per side), extending horizontally outward
            float legY0 = 0.25f, legY1 = 0.375f;
            float[] legZ = { -0.28125f, -0.09375f, 0.09375f, 0.28125f };
            float   legDz = 0.125f;
            for (float z0 : legZ) {
                float z1 = z0 + legDz;
                // Right legs (+X)
                addBox(buf, base,  0.3125f, legY0, z0,  1.3125f, legY1, z1, SP_LEG_UV);
                // Left legs (-X)
                addBox(buf, base, -1.3125f, legY0, z0, -0.3125f, legY1, z1, SP_LEG_UV);
            }
        }
    }

    /**
     * Pig mesh: head, body (wide quadruped), four legs.
     *
     * <p>Diagonal leg pairs animate together (standard quadruped gait).
     */
    private void buildPigMesh(List<Float> buf, Mob mob) {
        float yawRad  = (float) Math.toRadians(mob.yaw);
        Matrix4f base = new Matrix4f()
            .translate(mob.position.x, mob.position.y, mob.position.z)
            .rotateY(-yawRad);

        float swing = mob.getSwingAngle();

        // Head (forward-facing, textured with pig head UV)
        addBox(buf, base, -0.25f, 0.85f, -0.75f, 0.25f, 1.35f, -0.25f, PIG_HEAD_UV);

        // Body (horizontal slab above the legs)
        addBox(buf, base, -0.3125f, 0.70f, -0.5f, 0.3125f, 1.20f, 0.5f, PIG_BODY_UV);

        // Legs - front-right and back-left use +swing; front-left and back-right use -swing
        float px0 =  0.0625f, px1 =  0.3125f;  // right X extent
        float qx0 = -0.3125f, qx1 = -0.0625f;  // left  X extent
        float fz0 = -0.4375f, fz1 = -0.1875f;  // front Z extent
        float bz0 =  0.1875f, bz1 =  0.4375f;  // back  Z extent
        float legTop = 0.75f;

        Matrix4f frLeg = new Matrix4f(base)
            .translate(0.1875f, legTop, -0.3125f).rotateX(swing)
            .translate(-0.1875f, -legTop, 0.3125f);
        addBox(buf, frLeg, px0, 0f, fz0, px1, legTop, fz1, PIG_LEG_UV);

        Matrix4f flLeg = new Matrix4f(base)
            .translate(-0.1875f, legTop, -0.3125f).rotateX(-swing)
            .translate(0.1875f, -legTop, 0.3125f);
        addBox(buf, flLeg, qx0, 0f, fz0, qx1, legTop, fz1, PIG_LEG_UV);

        Matrix4f brLeg = new Matrix4f(base)
            .translate(0.1875f, legTop, 0.3125f).rotateX(-swing)
            .translate(-0.1875f, -legTop, -0.3125f);
        addBox(buf, brLeg, px0, 0f, bz0, px1, legTop, bz1, PIG_LEG_UV);

        Matrix4f blLeg = new Matrix4f(base)
            .translate(-0.1875f, legTop, 0.3125f).rotateX(swing)
            .translate(0.1875f, -legTop, -0.3125f);
        addBox(buf, blLeg, qx0, 0f, bz0, qx1, legTop, bz1, PIG_LEG_UV);
    }

    /**
     * Sheep mesh.
     *
     * <p>Pass 0 renders the bare body (sheep.png).  Pass 1 renders the wool
     * overlay with slightly expanded boxes (sheep_wool.png, same UVs).
     */
    private void buildSheepMesh(List<Float> buf, Mob mob, int pass) {
        float yawRad  = (float) Math.toRadians(mob.yaw);
        Matrix4f base = new Matrix4f()
            .translate(mob.position.x, mob.position.y, mob.position.z)
            .rotateY(-yawRad);

        float swing = mob.getSwingAngle();

        // Expansion amounts for the wool overlay; zero on the base body pass
        float ew = (pass == 1) ? 0.1125f : 0f;   // body/leg expansion
        float eh = (pass == 1) ? 0.1875f : 0f;   // head expansion

        // Head
        addBox(buf, base,
               -0.1875f - eh, 0.875f - eh, -0.75f - eh,
                0.1875f + eh, 1.25f  + eh, -0.25f + eh, SH_HEAD_UV);

        // Body
        addBox(buf, base,
               -0.25f - ew, 0.70f - ew, -0.5f - ew,
                0.25f + ew, 1.075f + ew,  0.5f + ew, SH_BODY_UV);

        // Legs - only on the base body pass (wool legs are not visually significant)
        if (pass == 0) {
            float px0 =  0.0625f, px1 =  0.3125f;
            float qx0 = -0.3125f, qx1 = -0.0625f;
            float fz0 = -0.4375f, fz1 = -0.1875f;
            float bz0 =  0.1875f, bz1 =  0.4375f;
            float legTop = 0.75f;

            Matrix4f frLeg = new Matrix4f(base)
                .translate(0.1875f, legTop, -0.3125f).rotateX(swing)
                .translate(-0.1875f, -legTop, 0.3125f);
            addBox(buf, frLeg, px0, 0f, fz0, px1, legTop, fz1, SH_LEG_UV);

            Matrix4f flLeg = new Matrix4f(base)
                .translate(-0.1875f, legTop, -0.3125f).rotateX(-swing)
                .translate(0.1875f, -legTop, 0.3125f);
            addBox(buf, flLeg, qx0, 0f, fz0, qx1, legTop, fz1, SH_LEG_UV);

            Matrix4f brLeg = new Matrix4f(base)
                .translate(0.1875f, legTop, 0.3125f).rotateX(-swing)
                .translate(-0.1875f, -legTop, -0.3125f);
            addBox(buf, brLeg, px0, 0f, bz0, px1, legTop, bz1, SH_LEG_UV);

            Matrix4f blLeg = new Matrix4f(base)
                .translate(-0.1875f, legTop, 0.3125f).rotateX(swing)
                .translate(0.1875f, -legTop, -0.3125f);
            addBox(buf, blLeg, qx0, 0f, bz0, qx1, legTop, bz1, SH_LEG_UV);
        }
    }

    // -------------------------------------------------------------------------
    // Low-level mesh primitives (unchanged from original Steve renderer)
    // -------------------------------------------------------------------------

    /** Emits all 6 faces of an axis-aligned box into {@code buf}. */
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
     * @param uv   {u0, v0, u1, v1} for the face texture region
     */
    private void addFace(List<Float> buf, Matrix4f transform,
                         float x0, float y0, float z0,
                         float x1, float y1, float z1,
                         int face, float[] uv) {
        float[][] c;
        float[]   n;
        switch (face) {
            case F_NORTH:
                c = new float[][]{{x0,y0,z0},{x0,y1,z0},{x1,y1,z0},{x1,y0,z0}};
                n = new float[]{0,0,-1}; break;
            case F_SOUTH:
                c = new float[][]{{x1,y0,z1},{x1,y1,z1},{x0,y1,z1},{x0,y0,z1}};
                n = new float[]{0,0,1}; break;
            case F_EAST:
                c = new float[][]{{x1,y0,z0},{x1,y1,z0},{x1,y1,z1},{x1,y0,z1}};
                n = new float[]{1,0,0}; break;
            case F_WEST:
                c = new float[][]{{x0,y0,z1},{x0,y1,z1},{x0,y1,z0},{x0,y0,z0}};
                n = new float[]{-1,0,0}; break;
            case F_TOP:
                c = new float[][]{{x0,y1,z0},{x0,y1,z1},{x1,y1,z1},{x1,y1,z0}};
                n = new float[]{0,1,0}; break;
            default: // F_BOTTOM
                c = new float[][]{{x0,y0,z0},{x1,y0,z0},{x1,y0,z1},{x0,y0,z1}};
                n = new float[]{0,-1,0}; break;
        }

        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        float[] us, vs;
        if (face == F_TOP || face == F_BOTTOM) {
            us = new float[]{ u0, u0, u1, u1 };
            vs = new float[]{ v0, v1, v1, v0 };
        } else {
            us = new float[]{ u0, u0, u1, u1 };
            vs = new float[]{ v1, v0, v0, v1 };
        }

        Vector3f normal = new Vector3f(n[0], n[1], n[2]);
        transform.transformDirection(normal);

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
            buf.add(1.0f); // skyLight - mobs are always surface-lit
        }
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
        int[] texIds = {
            zombieTexId, skeletonTexId, creeperTexId,
            spiderTexId, spiderEyesTexId,
            pigTexId, sheepTexId, sheepWoolTexId
        };
        for (int id : texIds) {
            if (id != 0) glDeleteTextures(id);
        }
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
    }
}
