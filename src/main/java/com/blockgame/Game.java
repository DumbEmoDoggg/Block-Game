package com.blockgame;

import com.blockgame.input.InputHandler;
import com.blockgame.player.Player;
import com.blockgame.rendering.Renderer;
import com.blockgame.world.World;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Main game class. Manages the GLFW window, game loop, and top-level state.
 */
public class Game {

    public static final int WINDOW_WIDTH  = 1280;
    public static final int WINDOW_HEIGHT = 720;
    public static final String WINDOW_TITLE = "Block Game";

    /** Save file location: {@code ~/.blockgame/world.dat} */
    private static final Path SAVE_FILE =
        Path.of(System.getProperty("user.home"), ".blockgame", "world.dat");

    private long window;
    private World world;
    private Player player;
    private Renderer renderer;
    private InputHandler inputHandler;

    private double lastTime;

    public void run() {
        init();
        loop();
        cleanup();
    }

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    private void init() {
        // Error callback to stderr
        GLFWErrorCallback.createPrint(System.err).set();

        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        // Require OpenGL 3.3 core profile
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        window = glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT, WINDOW_TITLE, NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create the GLFW window");
        }

        // Centre on primary monitor
        try (MemoryStack stack = stackPush()) {
            IntBuffer pWidth  = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            glfwGetWindowSize(window, pWidth, pHeight);
            GLFWVidMode vidMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            if (vidMode != null) {
                glfwSetWindowPos(
                    window,
                    (vidMode.width()  - pWidth.get(0))  / 2,
                    (vidMode.height() - pHeight.get(0)) / 2
                );
            }
        }

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1); // enable VSync
        glfwShowWindow(window);

        // Create OpenGL capabilities
        GL.createCapabilities();

        // Build game objects
        inputHandler = new InputHandler(window);
        world        = new World();
        player       = new Player(world, inputHandler, (float) WINDOW_WIDTH / WINDOW_HEIGHT);
        renderer     = new Renderer(window, world, player);

        // Restore the last saved world (if one exists)
        if (Files.exists(SAVE_FILE)) {
            try {
                Vector3f savedPos = world.load(SAVE_FILE);
                player.setPosition(savedPos.x, savedPos.y, savedPos.z);
                System.out.println("[BlockGame] Save loaded from " + SAVE_FILE);
            } catch (IOException e) {
                System.err.println("[BlockGame] Could not load save: " + e.getMessage());
            }
        }

        // Capture mouse cursor
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);

        // Escape = close window
        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            inputHandler.onKey(key, action);
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
                glfwSetWindowShouldClose(win, true);
            }
        });

        lastTime = glfwGetTime();
    }

    // -------------------------------------------------------------------------
    // Game loop
    // -------------------------------------------------------------------------

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            double now = glfwGetTime();
            float  dt  = (float) (now - lastTime);
            lastTime   = now;

            // Cap delta time to avoid the spiral-of-death on lag spikes
            dt = Math.min(dt, 0.05f);

            glfwPollEvents();

            // Save world when Enter is pressed
            if (inputHandler.isKeyJustPressed(GLFW_KEY_ENTER)) {
                try {
                    world.save(SAVE_FILE, player.getPosition());
                    System.out.println("[BlockGame] World saved to " + SAVE_FILE);
                } catch (IOException e) {
                    System.err.println("[BlockGame] Could not save world: " + e.getMessage());
                }
            }

            player.update(dt);
            world.update(player.getPosition());

            renderer.render();
            glfwSwapBuffers(window);

            inputHandler.endFrame();
        }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    private void cleanup() {
        renderer.cleanup();
        world.cleanup();

        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
        GLFWErrorCallback cb = glfwSetErrorCallback(null);
        if (cb != null) {
            cb.free();
        }
    }
}
