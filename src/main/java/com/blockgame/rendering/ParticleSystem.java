package com.blockgame.rendering;

import com.blockgame.world.BlockType;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.Random;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Simple CPU-driven particle system that spawns small colored billboard quads
 * when a block is broken, mimicking the Minecraft block-break effect.
 *
 * <p>Particles are axis-aligned billboards that always face the camera.
 * Each particle carries its own color (sampled from the broken block type with
 * a small random variation), velocity, and lifetime.
 *
 * <p>No world collision is performed – particles fall through geometry for
 * simplicity.  This is consistent with many lightweight particle systems and
 * keeps frame-time cost negligible.
 */
public class ParticleSystem {

    /** Maximum simultaneous live particles. */
    private static final int MAX_PARTICLES = 500;

    /** Number of particles spawned per block break. */
    private static final int PARTICLES_PER_BREAK = 12;

    /** World-space gravity applied to particles (m/s²). */
    private static final float GRAVITY = -20.0f;

    /** Base particle lifetime in seconds (actual lifetime is randomised). */
    private static final float BASE_LIFETIME = 0.9f;

    /** Half-size in world units (particle sizes are randomised around this). */
    private static final float BASE_HALF_SIZE = 0.065f;

    // -------------------------------------------------------------------------
    // Per-particle state (struct-of-arrays for cache efficiency)
    // -------------------------------------------------------------------------

    private final float[] posX  = new float[MAX_PARTICLES];
    private final float[] posY  = new float[MAX_PARTICLES];
    private final float[] posZ  = new float[MAX_PARTICLES];
    private final float[] velX  = new float[MAX_PARTICLES];
    private final float[] velY  = new float[MAX_PARTICLES];
    private final float[] velZ  = new float[MAX_PARTICLES];
    private final float[] colorR = new float[MAX_PARTICLES];
    private final float[] colorG = new float[MAX_PARTICLES];
    private final float[] colorB = new float[MAX_PARTICLES];
    private final float[] age   = new float[MAX_PARTICLES];
    private final float[] life  = new float[MAX_PARTICLES];
    private final float[] halfSize = new float[MAX_PARTICLES];

    /** Number of currently live particles. */
    private int count = 0;

    private final Random random = new Random();

    // -------------------------------------------------------------------------
    // Rendering resources
    // -------------------------------------------------------------------------

    private Shader shader;
    private int vao, vbo;

    /** Per-vertex layout: position(3) + color-with-alpha(4) = 7 floats. */
    private static final int FLOATS_PER_VERTEX = 7;
    private static final int VERTS_PER_PARTICLE = 6; // 2 triangles

    private final FloatBuffer vertexBuf =
        BufferUtils.createFloatBuffer(MAX_PARTICLES * VERTS_PER_PARTICLE * FLOATS_PER_VERTEX);

    // -------------------------------------------------------------------------
    // Construction / cleanup
    // -------------------------------------------------------------------------

    public ParticleSystem() {
        shader = new Shader("shaders/particle_vertex.glsl", "shaders/particle_fragment.glsl");

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        // Allocate GPU buffer (updated every frame with live particle data)
        glBufferData(GL_ARRAY_BUFFER,
            (long) MAX_PARTICLES * VERTS_PER_PARTICLE * FLOATS_PER_VERTEX * Float.BYTES,
            GL_DYNAMIC_DRAW);

        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        // Attribute 0: position (xyz)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        // Attribute 1: color + alpha (rgba)
        glVertexAttribPointer(1, 4, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    public void cleanup() {
        shader.cleanup();
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
    }

    // -------------------------------------------------------------------------
    // Spawning
    // -------------------------------------------------------------------------

    /**
     * Spawns a burst of particles centred on the broken block.
     *
     * @param bx    world X of the broken block
     * @param by    world Y of the broken block
     * @param bz    world Z of the broken block
     * @param type  block type (used for base colour)
     */
    public void spawn(int bx, int by, int bz, BlockType type) {
        for (int i = 0; i < PARTICLES_PER_BREAK && count < MAX_PARTICLES; i++) {
            // Random position within the interior of the broken block
            posX[count] = bx + 0.1f + random.nextFloat() * 0.8f;
            posY[count] = by + 0.1f + random.nextFloat() * 0.8f;
            posZ[count] = bz + 0.1f + random.nextFloat() * 0.8f;

            // Burst outward + upward, like Minecraft
            velX[count] = (random.nextFloat() - 0.5f) * 4.5f;
            velY[count] =  random.nextFloat() * 3.0f + 1.0f;
            velZ[count] = (random.nextFloat() - 0.5f) * 4.5f;

            // Colour: block base colour with a small random tint
            colorR[count] = clamp01(type.r + (random.nextFloat() - 0.5f) * 0.12f);
            colorG[count] = clamp01(type.g + (random.nextFloat() - 0.5f) * 0.12f);
            colorB[count] = clamp01(type.b + (random.nextFloat() - 0.5f) * 0.12f);

            age[count]      = 0f;
            life[count]     = BASE_LIFETIME * (0.5f + random.nextFloat() * 0.5f);
            halfSize[count] = BASE_HALF_SIZE * (0.7f + random.nextFloat() * 0.6f);

            count++;
        }
    }

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    /**
     * Advances all live particles by {@code dt} seconds, applying gravity and
     * removing expired particles.
     */
    public void update(float dt) {
        for (int i = 0; i < count; ) {
            age[i] += dt;
            if (age[i] >= life[i]) {
                // Remove by swapping with the last particle (order-independent)
                int last = --count;
                posX[i] = posX[last]; posY[i] = posY[last]; posZ[i] = posZ[last];
                velX[i] = velX[last]; velY[i] = velY[last]; velZ[i] = velZ[last];
                colorR[i] = colorR[last]; colorG[i] = colorG[last]; colorB[i] = colorB[last];
                age[i]  = age[last];  life[i] = life[last]; halfSize[i] = halfSize[last];
                // don't increment i – re-check the swapped-in particle
            } else {
                velY[i] += GRAVITY * dt;
                posX[i] += velX[i] * dt;
                posY[i] += velY[i] * dt;
                posZ[i] += velZ[i] * dt;
                i++;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    /**
     * Renders all live particles as camera-facing billboard quads.
     *
     * <p>Must be called between world rendering and the HUD pass.  The caller
     * is responsible for having the depth test enabled; this method temporarily
     * enables alpha blending and disables depth writes while drawing.
     *
     * @param view       current view matrix
     * @param projection current projection matrix
     */
    public void render(Matrix4f view, Matrix4f projection) {
        if (count == 0) return;

        // Extract camera right and up vectors from the view matrix rows.
        // JOML column-major: mXY = column X, row Y.
        // Row 0 = right vector: (m00, m10, m20)
        // Row 1 = up    vector: (m01, m11, m21)
        float rx = view.m00(), ry = view.m10(), rz = view.m20();
        float ux = view.m01(), uy = view.m11(), uz = view.m21();

        vertexBuf.clear();

        for (int i = 0; i < count; i++) {
            float t     = age[i] / life[i];      // 0 → 1 over the particle's life
            float alpha = 1.0f - t;               // fade out
            float dim   = 1.0f - t * 0.25f;      // slight darkening
            float r     = colorR[i] * dim;
            float g     = colorG[i] * dim;
            float b     = colorB[i] * dim;
            float h     = halfSize[i];

            float px = posX[i], py = posY[i], pz = posZ[i];

            // Four corners of the billboard quad (camera-aligned)
            //   bl = bottom-left,  br = bottom-right
            //   tl = top-left,     tr = top-right
            float blX = px - rx * h - ux * h,  blY = py - ry * h - uy * h,  blZ = pz - rz * h - uz * h;
            float brX = px + rx * h - ux * h,  brY = py + ry * h - uy * h,  brZ = pz + rz * h - uz * h;
            float trX = px + rx * h + ux * h,  trY = py + ry * h + uy * h,  trZ = pz + rz * h + uz * h;
            float tlX = px - rx * h + ux * h,  tlY = py - ry * h + uy * h,  tlZ = pz - rz * h + uz * h;

            // Triangle 1: bl, br, tr
            putVertex(blX, blY, blZ, r, g, b, alpha);
            putVertex(brX, brY, brZ, r, g, b, alpha);
            putVertex(trX, trY, trZ, r, g, b, alpha);
            // Triangle 2: bl, tr, tl
            putVertex(blX, blY, blZ, r, g, b, alpha);
            putVertex(trX, trY, trZ, r, g, b, alpha);
            putVertex(tlX, tlY, tlZ, r, g, b, alpha);
        }

        vertexBuf.flip();

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexBuf);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        shader.use();
        shader.setMatrix4f("view",       view);
        shader.setMatrix4f("projection", projection);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false); // don't write to depth buffer – particles should blend cleanly

        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, count * VERTS_PER_PARTICLE);
        glBindVertexArray(0);

        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void putVertex(float x, float y, float z, float r, float g, float b, float a) {
        vertexBuf.put(x).put(y).put(z).put(r).put(g).put(b).put(a);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
