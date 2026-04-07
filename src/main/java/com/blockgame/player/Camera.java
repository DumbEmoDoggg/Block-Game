package com.blockgame.player;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * First-person camera.
 *
 * <p><b>Coordinate conventions used throughout the game:</b>
 * <ul>
 *   <li>yaw   &gt; 0 → camera rotated to the right (clockwise from above)</li>
 *   <li>yaw   = 0 → looking toward −Z (north)</li>
 *   <li>pitch &gt; 0 → camera tilted upward</li>
 * </ul>
 *
 * <p>The view matrix is built as:<br>
 * {@code V = Rx(−pitch) × Ry(yaw) × T(−position)}
 */
public class Camera {

    private final Vector3f position = new Vector3f(0, 70, 0);
    private float pitch = 0f;   // degrees; positive = look up
    private float yaw   = 0f;   // degrees; positive = look right

    private final Matrix4f viewMatrix       = new Matrix4f();
    private final Matrix4f projectionMatrix = new Matrix4f();

    public Camera(float aspectRatio) {
        updateProjection(aspectRatio);
    }

    // -------------------------------------------------------------------------
    // Matrix updates
    // -------------------------------------------------------------------------

    public void updateProjection(float aspectRatio) {
        projectionMatrix.identity().perspective(
            (float) Math.toRadians(70.0),
            aspectRatio,
            0.05f,
            800.0f
        );
    }

    public void updateView() {
        viewMatrix.identity()
            .rotateX((float) Math.toRadians(-pitch))
            .rotateY((float) Math.toRadians(yaw))
            .translate(-position.x, -position.y, -position.z);
    }

    // -------------------------------------------------------------------------
    // Forward direction
    // -------------------------------------------------------------------------

    /**
     * Returns the unit vector the camera is currently pointing toward, in
     * world space.
     */
    public Vector3f getDirection() {
        float pr = (float) Math.toRadians(pitch);
        float yr = (float) Math.toRadians(yaw);
        float cosPitch = (float) Math.cos(pr);
        return new Vector3f(
            cosPitch * (float)  Math.sin(yr),
             (float) Math.sin(pr),
            cosPitch * (float) -Math.cos(yr)
        ).normalize();
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public Vector3f getPosition()           { return position; }
    public float    getPitch()              { return pitch; }
    public float    getYaw()               { return yaw; }
    public Matrix4f getViewMatrix()         { return viewMatrix; }
    public Matrix4f getProjectionMatrix()   { return projectionMatrix; }

    public void setPosition(Vector3f pos)  { this.position.set(pos); }
    public void setPitch(float pitch)      { this.pitch = pitch; }
    public void setYaw(float yaw)         { this.yaw   = yaw;   }
}
