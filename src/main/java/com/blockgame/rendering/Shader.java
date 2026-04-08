package com.blockgame.rendering;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL20.*;

/**
 * Compiles and links a GLSL vertex + fragment shader program and exposes
 * uniform-setting helpers.
 */
public class Shader {

    private final int programId;

    /**
     * Loads shader source from the classpath, compiles and links the program.
     *
     * @param vertexPath   classpath-relative path to the vertex shader
     * @param fragmentPath classpath-relative path to the fragment shader
     */
    public Shader(String vertexPath, String fragmentPath) {
        int vert = compile(GL_VERTEX_SHADER,   load(vertexPath));
        int frag = compile(GL_FRAGMENT_SHADER, load(fragmentPath));

        programId = glCreateProgram();
        glAttachShader(programId, vert);
        glAttachShader(programId, frag);
        glLinkProgram(programId);

        if (glGetProgrami(programId, GL_LINK_STATUS) == 0) {
            String log = glGetProgramInfoLog(programId);
            glDeleteShader(vert);
            glDeleteShader(frag);
            throw new RuntimeException("Shader link error: " + log);
        }

        glDeleteShader(vert);
        glDeleteShader(frag);
    }

    // -------------------------------------------------------------------------
    // Uniform helpers
    // -------------------------------------------------------------------------

    public void use() {
        glUseProgram(programId);
    }

    public void setMatrix4f(String name, Matrix4f m) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(16);
            m.get(buf);
            glUniformMatrix4fv(location(name), false, buf);
        }
    }

    public void setVector3f(String name, Vector3f v) {
        glUniform3f(location(name), v.x, v.y, v.z);
    }

    public void setFloat(String name, float v) {
        glUniform1f(location(name), v);
    }

    public void setInt(String name, int v) {
        glUniform1i(location(name), v);
    }

    public void setVector4f(String name, float x, float y, float z, float w) {
        glUniform4f(location(name), x, y, z, w);
    }

    public void cleanup() {
        glDeleteProgram(programId);
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private int location(String name) {
        return glGetUniformLocation(programId, name);
    }

    private int compile(int type, String source) {
        int id = glCreateShader(type);
        glShaderSource(id, source);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == 0) {
            String log = glGetShaderInfoLog(id);
            glDeleteShader(id);
            throw new RuntimeException("Shader compile error: " + log);
        }
        return id;
    }

    private String load(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new RuntimeException("Shader resource not found on classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader: " + path, e);
        }
    }
}
