package com.blockgame.mob;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;

/**
 * Defines the 3-D geometry for one mob type: UV mapping data and mesh-building
 * logic.  Subclasses supply a concrete {@link #buildMesh} implementation; the
 * shared box-rendering utilities live here so every model can use them without
 * depending on the renderer.
 *
 * <p>The vertex format produced by {@link #addFace} is identical to
 * {@code ChunkMesh} (9 floats: pos3 + uv2 + normal3 + skyLight1), so mob
 * geometry can be drawn with the same world shaders.
 *
 * <p>Concrete model implementations are provided as package-private nested
 * classes.  {@link MobType} holds a singleton instance of the appropriate
 * model for each mob, and {@code MobRenderer} delegates all geometry
 * construction to these instances.
 */
public abstract class MobModel {

    // -------------------------------------------------------------------------
    // Face index constants  (index into the per-part UV arrays)
    // -------------------------------------------------------------------------
    protected static final int F_NORTH  = 0; // -Z
    protected static final int F_SOUTH  = 1; // +Z
    protected static final int F_EAST   = 2; // +X
    protected static final int F_WEST   = 3; // -X
    protected static final int F_TOP    = 4; // +Y
    protected static final int F_BOTTOM = 5; // -Y

    // -------------------------------------------------------------------------
    // Mesh-building API
    // -------------------------------------------------------------------------

    /**
     * Appends geometry vertices for one mob into {@code buf}.
     *
     * @param buf  vertex accumulator (9 floats per vertex: pos3+uv2+normal3+skyLight1)
     * @param mob  the mob instance being rendered (position, yaw, swing angle)
     * @param pass 0 = primary body; 1 = translucent overlay (e.g. spider eyes, sheep wool)
     */
    public abstract void buildMesh(List<Float> buf, Mob mob, int pass);

    // -------------------------------------------------------------------------
    // UV helper (standard Minecraft cube-UV layout)
    // -------------------------------------------------------------------------

    /**
     * Computes a 6-face UV table from Minecraft texture-offset notation.
     *
     * <p>Layout ({@code texW × texH} texture):
     * <pre>
     *   top-row    [ty .. ty+sz]:
     *     [tx+sz  .. tx+sz+sx]       → F_TOP
     *     [tx+sz+sx .. tx+sz+2*sx]   → F_BOTTOM
     *   side-row   [ty+sz .. ty+sz+sy]:
     *     [tx     .. tx+sz]          → F_EAST  (+X)
     *     [tx+sz  .. tx+sz+sx]       → F_NORTH (-Z)
     *     [tx+sz+sx .. tx+2sz+sx]    → F_WEST  (-X)
     *     [tx+2sz+sx .. tx+2sz+2sx]  → F_SOUTH (+Z)
     * </pre>
     *
     * @return {@code float[6][4]} UV table indexed by {@code F_*} constants;
     *         each entry is {@code {u0, v0, u1, v1}} in normalised coordinates
     */
    protected static float[][] computeUV(int tx, int ty,
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
    // Low-level geometry primitives
    // -------------------------------------------------------------------------

    /** Emits all 6 faces of an axis-aligned box into {@code buf}. */
    protected static void addBox(List<Float> buf, Matrix4f transform,
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
     * <p>Vertex layout, UV orientation, and triangle winding match
     * {@code ChunkMesh} so the same shaders can be used unmodified.
     *
     * @param face one of the {@code F_*} constants
     * @param uv   {@code {u0, v0, u1, v1}} for the face texture region
     */
    protected static void addFace(List<Float> buf, Matrix4f transform,
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

    // =========================================================================
    // Concrete model implementations
    // =========================================================================

    /**
     * Shared humanoid geometry (head, torso, two arms, two legs) parameterised
     * by UV arrays so it can serve both Zombie (64×64) and Skeleton (64×32).
     */
    static abstract class HumanoidModel extends MobModel {

        protected abstract float[][] headUV();
        protected abstract float[][] bodyUV();
        protected abstract float[][] rArmUV();
        protected abstract float[][] lArmUV();
        protected abstract float[][] rLegUV();
        protected abstract float[][] lLegUV();

        @Override
        public void buildMesh(List<Float> buf, Mob mob, int pass) {
            float yawRad = (float) Math.toRadians(mob.yaw);
            Matrix4f base = new Matrix4f()
                .translate(mob.position.x, mob.position.y, mob.position.z)
                .rotateY(-yawRad);
            float swing = mob.getSwingAngle();

            // Head
            addBox(buf, base, -0.25f, 1.50f, -0.25f, 0.25f, 2.00f, 0.25f, headUV());

            // Body
            addBox(buf, base, -0.25f, 0.75f, -0.125f, 0.25f, 1.50f, 0.125f, bodyUV());

            // Right arm – pivot at shoulder (0.375, 1.5, 0)
            Matrix4f rArm = new Matrix4f(base)
                .translate(0.375f, 1.5f, 0f).rotateX(swing).translate(-0.375f, -1.5f, 0f);
            addBox(buf, rArm,  0.25f, 0.75f, -0.125f,  0.50f, 1.50f, 0.125f, rArmUV());

            // Left arm – pivot at shoulder (-0.375, 1.5, 0)
            Matrix4f lArm = new Matrix4f(base)
                .translate(-0.375f, 1.5f, 0f).rotateX(-swing).translate(0.375f, -1.5f, 0f);
            addBox(buf, lArm, -0.50f, 0.75f, -0.125f, -0.25f, 1.50f, 0.125f, lArmUV());

            // Right leg – pivot at hip (0.125, 0.75, 0)
            Matrix4f rLeg = new Matrix4f(base)
                .translate(0.125f, 0.75f, 0f).rotateX(-swing).translate(-0.125f, -0.75f, 0f);
            addBox(buf, rLeg,  0.00f, 0.00f, -0.125f,  0.25f, 0.75f, 0.125f, rLegUV());

            // Left leg – pivot at hip (-0.125, 0.75, 0)
            Matrix4f lLeg = new Matrix4f(base)
                .translate(-0.125f, 0.75f, 0f).rotateX(swing).translate(0.125f, -0.75f, 0f);
            addBox(buf, lLeg, -0.25f, 0.00f, -0.125f,  0.00f, 0.75f, 0.125f, lLegUV());
        }
    }

    // -------------------------------------------------------------------------

    /** Humanoid model for {@link MobType#ZOMBIE} (64×64 texture). */
    static final class ZombieModel extends HumanoidModel {
        private static final float[][] HEAD = computeUV( 0,  0, 8, 8,  8, 64, 64);
        private static final float[][] BODY = computeUV(16, 16, 8, 12, 4, 64, 64);
        private static final float[][] ARM  = computeUV(40, 16, 4, 12, 4, 64, 64);
        private static final float[][] LEG  = computeUV( 0, 16, 4, 12, 4, 64, 64);

        @Override protected float[][] headUV() { return HEAD; }
        @Override protected float[][] bodyUV() { return BODY; }
        @Override protected float[][] rArmUV() { return ARM; }
        @Override protected float[][] lArmUV() { return ARM; }
        @Override protected float[][] rLegUV() { return LEG; }
        @Override protected float[][] lLegUV() { return LEG; }
    }

    // -------------------------------------------------------------------------

    /**
     * Humanoid model for {@link MobType#SKELETON} (64×32 texture).
     * Left limbs share UV with the right (the texture mirrors them automatically).
     */
    static final class SkeletonModel extends HumanoidModel {
        private static final float[][] HEAD = computeUV( 0,  0, 8, 8,  8, 64, 32);
        private static final float[][] BODY = computeUV(16, 16, 8, 12, 4, 64, 32);
        private static final float[][] ARM  = computeUV(40, 16, 4, 12, 4, 64, 32);
        private static final float[][] LEG  = computeUV( 0, 16, 4, 12, 4, 64, 32);

        @Override protected float[][] headUV() { return HEAD; }
        @Override protected float[][] bodyUV() { return BODY; }
        @Override protected float[][] rArmUV() { return ARM; }
        @Override protected float[][] lArmUV() { return ARM; }
        @Override protected float[][] rLegUV() { return LEG; }
        @Override protected float[][] lLegUV() { return LEG; }
    }

    // -------------------------------------------------------------------------

    /**
     * Four-legged model for {@link MobType#CREEPER} (64×32 texture).
     * Head and body share the Skeleton UV offsets; legs are shorter.
     */
    static final class CreeperModel extends MobModel {
        private static final float[][] HEAD_UV = computeUV( 0,  0, 8, 8,  8, 64, 32);
        private static final float[][] BODY_UV = computeUV(16, 16, 8, 12, 4, 64, 32);
        private static final float[][] LEG_UV  = computeUV( 0, 16, 4,  6, 4, 64, 32);

        @Override
        public void buildMesh(List<Float> buf, Mob mob, int pass) {
            float yawRad = (float) Math.toRadians(mob.yaw);
            Matrix4f base = new Matrix4f()
                .translate(mob.position.x, mob.position.y, mob.position.z)
                .rotateY(-yawRad);
            float swing = mob.getSwingAngle();

            // Head
            addBox(buf, base, -0.25f, 1.125f, -0.25f, 0.25f, 1.625f, 0.25f, HEAD_UV);

            // Body
            addBox(buf, base, -0.25f, 0.375f, -0.125f, 0.25f, 1.125f, 0.125f, BODY_UV);

            // Front-right leg – pivot at top (0.125, 0.375, -0.125)
            Matrix4f frLeg = new Matrix4f(base)
                .translate(0.125f, 0.375f, -0.125f).rotateX(swing)
                .translate(-0.125f, -0.375f, 0.125f);
            addBox(buf, frLeg,  0.00f, 0.00f, -0.25f,  0.25f, 0.375f,  0.00f, LEG_UV);

            // Front-left leg – pivot at top (-0.125, 0.375, -0.125)
            Matrix4f flLeg = new Matrix4f(base)
                .translate(-0.125f, 0.375f, -0.125f).rotateX(-swing)
                .translate(0.125f, -0.375f, 0.125f);
            addBox(buf, flLeg, -0.25f, 0.00f, -0.25f,  0.00f, 0.375f,  0.00f, LEG_UV);

            // Back-right leg – pivot at top (0.125, 0.375, 0.125)
            Matrix4f brLeg = new Matrix4f(base)
                .translate(0.125f, 0.375f, 0.125f).rotateX(-swing)
                .translate(-0.125f, -0.375f, -0.125f);
            addBox(buf, brLeg,  0.00f, 0.00f,  0.00f,  0.25f, 0.375f,  0.25f, LEG_UV);

            // Back-left leg – pivot at top (-0.125, 0.375, 0.125)
            Matrix4f blLeg = new Matrix4f(base)
                .translate(-0.125f, 0.375f, 0.125f).rotateX(swing)
                .translate(0.125f, -0.375f, -0.125f);
            addBox(buf, blLeg, -0.25f, 0.00f,  0.00f,  0.00f, 0.375f,  0.25f, LEG_UV);
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Arachnid model for {@link MobType#SPIDER} (64×32 texture, two-pass).
     * Pass 0 renders head + abdomen + 8 legs; pass 1 renders the glowing eye
     * overlay (same head geometry, {@code spider_eyes.png} bound by caller).
     */
    static final class SpiderModel extends MobModel {
        private static final float[][] HEAD_UV = computeUV(32,  4,  8, 8,  8, 64, 32);
        private static final float[][] BODY_UV = computeUV( 0, 12, 10, 8, 12, 64, 32);
        private static final float[][] LEG_UV  = computeUV(18,  0, 16, 2,  2, 64, 32);

        @Override
        public void buildMesh(List<Float> buf, Mob mob, int pass) {
            float yawRad = (float) Math.toRadians(mob.yaw);
            Matrix4f base = new Matrix4f()
                .translate(mob.position.x, mob.position.y, mob.position.z)
                .rotateY(-yawRad);

            // Head (front body segment) – rendered in both passes
            addBox(buf, base, -0.25f, 0.0625f, -0.75f, 0.25f, 0.5625f, -0.25f, HEAD_UV);

            if (pass == 0) {
                // Abdomen (rear body segment)
                addBox(buf, base,
                       -0.3125f, 0.0625f, -0.375f,
                        0.3125f, 0.5625f,  0.375f, BODY_UV);

                // Eight legs (four per side), extending horizontally outward
                float legY0 = 0.25f, legY1 = 0.375f;
                float[] legZ = { -0.28125f, -0.09375f, 0.09375f, 0.28125f };
                float legDz = 0.125f;
                for (float z0 : legZ) {
                    float z1 = z0 + legDz;
                    addBox(buf, base,  0.3125f, legY0, z0,  1.3125f, legY1, z1, LEG_UV);
                    addBox(buf, base, -1.3125f, legY0, z0, -0.3125f, legY1, z1, LEG_UV);
                }
            }
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Quadruped model for {@link MobType#PIG} (64×64 texture).
     * Body is rendered with a remapped face order (90-degree X-rotation in the
     * original Minecraft model).
     */
    static final class PigModel extends MobModel {
        private static final float[][] HEAD_UV  = computeUV( 0,  0, 8, 8, 8, 64, 64);
        private static final float[][] SNOUT_UV = computeUV(16, 16, 4, 3, 1, 64, 64);
        private static final float[][] BODY_UV  = {
            // The pig body is rendered with a 90-degree X-rotation in the original
            // Minecraft model, so computeUV() cannot be used directly.  Instead, the
            // physical face directions are remapped to the correct texture regions:
            //   F_NORTH/SOUTH ← model TOP/BOTTOM, F_TOP/BOTTOM ← model SOUTH/NORTH.
            {36f/64,  8f/64, 46f/64, 16f/64},  // F_NORTH  ← model TOP
            {46f/64,  8f/64, 56f/64, 16f/64},  // F_SOUTH  ← model BOTTOM
            {28f/64, 16f/64, 36f/64, 32f/64},  // F_EAST   ← model EAST
            {46f/64, 16f/64, 54f/64, 32f/64},  // F_WEST   ← model WEST
            {54f/64, 16f/64, 64f/64, 32f/64},  // F_TOP    ← model SOUTH
            {36f/64, 16f/64, 46f/64, 32f/64},  // F_BOTTOM ← model NORTH
        };
        private static final float[][] LEG_UV   = computeUV( 0, 16, 4, 12, 4, 64, 64);

        @Override
        public void buildMesh(List<Float> buf, Mob mob, int pass) {
            float yawRad = (float) Math.toRadians(mob.yaw);
            Matrix4f base = new Matrix4f()
                .translate(mob.position.x, mob.position.y, mob.position.z)
                .rotateY(-yawRad);
            float swing = mob.getSwingAngle();

            addBox(buf, base, -0.25f, 0.85f, -0.75f, 0.25f, 1.35f, -0.25f, HEAD_UV);
            addBox(buf, base, -0.125f, 1.1f, -0.8125f, 0.125f, 1.2875f, -0.75f, SNOUT_UV);
            addBox(buf, base, -0.3125f, 0.70f, -0.5f, 0.3125f, 1.20f, 0.5f, BODY_UV);

            float px0 =  0.0625f, px1 =  0.3125f;
            float qx0 = -0.3125f, qx1 = -0.0625f;
            float fz0 = -0.4375f, fz1 = -0.1875f;
            float bz0 =  0.1875f, bz1 =  0.4375f;
            float legTop = 0.75f;

            Matrix4f frLeg = new Matrix4f(base)
                .translate(0.1875f, legTop, -0.3125f).rotateX(swing)
                .translate(-0.1875f, -legTop, 0.3125f);
            addBox(buf, frLeg, px0, 0f, fz0, px1, legTop, fz1, LEG_UV);

            Matrix4f flLeg = new Matrix4f(base)
                .translate(-0.1875f, legTop, -0.3125f).rotateX(-swing)
                .translate(0.1875f, -legTop, 0.3125f);
            addBox(buf, flLeg, qx0, 0f, fz0, qx1, legTop, fz1, LEG_UV);

            Matrix4f brLeg = new Matrix4f(base)
                .translate(0.1875f, legTop, 0.3125f).rotateX(-swing)
                .translate(-0.1875f, -legTop, -0.3125f);
            addBox(buf, brLeg, px0, 0f, bz0, px1, legTop, bz1, LEG_UV);

            Matrix4f blLeg = new Matrix4f(base)
                .translate(-0.1875f, legTop, 0.3125f).rotateX(swing)
                .translate(0.1875f, -legTop, -0.3125f);
            addBox(buf, blLeg, qx0, 0f, bz0, qx1, legTop, bz1, LEG_UV);
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Woolly quadruped model for {@link MobType#SHEEP} (64×32 texture, two-pass).
     * Pass 0 renders the bare body ({@code sheep.png}); pass 1 renders the wool
     * overlay with slightly expanded boxes ({@code sheep_wool.png}, same UVs).
     */
    static final class SheepModel extends MobModel {
        private static final float[][] HEAD_UV = computeUV(0, 0, 6, 6, 8, 64, 32);
        private static final float[][] BODY_UV = {
            // Same 90-degree X-rotation remapping as PigModel: computeUV() cannot
            // be used directly.  u-coordinates use /64 and v-coordinates use /32
            // because this texture is 64×32 (non-square); the mixed denominators
            // in the same row are intentional.
            {34f/64,  8f/32, 42f/64, 14f/32},  // F_NORTH  ← model TOP
            {42f/64,  8f/32, 50f/64, 14f/32},  // F_SOUTH  ← model BOTTOM
            {28f/64, 14f/32, 34f/64, 30f/32},  // F_EAST   ← model EAST
            {42f/64, 14f/32, 48f/64, 30f/32},  // F_WEST   ← model WEST
            {48f/64, 14f/32, 56f/64, 30f/32},  // F_TOP    ← model SOUTH
            {34f/64, 14f/32, 42f/64, 30f/32},  // F_BOTTOM ← model NORTH
        };
        private static final float[][] LEG_UV  = computeUV(0, 16, 4, 12, 4, 64, 32);

        @Override
        public void buildMesh(List<Float> buf, Mob mob, int pass) {
            float yawRad = (float) Math.toRadians(mob.yaw);
            Matrix4f base = new Matrix4f()
                .translate(mob.position.x, mob.position.y, mob.position.z)
                .rotateY(-yawRad);
            float swing = mob.getSwingAngle();

            // Expansion amounts for the wool overlay; zero on the base body pass
            float ew = (pass == 1) ? 0.1125f : 0f;  // body/leg expansion
            float eh = (pass == 1) ? 0.1875f : 0f;  // head expansion

            // Head
            addBox(buf, base,
                   -0.1875f - eh, 0.875f - eh, -0.75f - eh,
                    0.1875f + eh, 1.25f  + eh, -0.25f + eh, HEAD_UV);

            // Body
            addBox(buf, base,
                   -0.25f - ew, 0.70f - ew, -0.5f - ew,
                    0.25f + ew, 1.075f + ew,  0.5f + ew, BODY_UV);

            // Legs – only on the base body pass (wool leg overlay is not visually significant)
            if (pass == 0) {
                float px0 =  0.0625f, px1 =  0.3125f;
                float qx0 = -0.3125f, qx1 = -0.0625f;
                float fz0 = -0.4375f, fz1 = -0.1875f;
                float bz0 =  0.1875f, bz1 =  0.4375f;
                float legTop = 0.75f;

                Matrix4f frLeg = new Matrix4f(base)
                    .translate(0.1875f, legTop, -0.3125f).rotateX(swing)
                    .translate(-0.1875f, -legTop, 0.3125f);
                addBox(buf, frLeg, px0, 0f, fz0, px1, legTop, fz1, LEG_UV);

                Matrix4f flLeg = new Matrix4f(base)
                    .translate(-0.1875f, legTop, -0.3125f).rotateX(-swing)
                    .translate(0.1875f, -legTop, 0.3125f);
                addBox(buf, flLeg, qx0, 0f, fz0, qx1, legTop, fz1, LEG_UV);

                Matrix4f brLeg = new Matrix4f(base)
                    .translate(0.1875f, legTop, 0.3125f).rotateX(-swing)
                    .translate(-0.1875f, -legTop, -0.3125f);
                addBox(buf, brLeg, px0, 0f, bz0, px1, legTop, bz1, LEG_UV);

                Matrix4f blLeg = new Matrix4f(base)
                    .translate(-0.1875f, legTop, 0.3125f).rotateX(swing)
                    .translate(0.1875f, -legTop, -0.3125f);
                addBox(buf, blLeg, qx0, 0f, bz0, qx1, legTop, bz1, LEG_UV);
            }
        }
    }
}
