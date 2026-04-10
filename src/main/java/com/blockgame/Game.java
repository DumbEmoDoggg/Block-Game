package com.blockgame;

import com.blockgame.input.InputAction;
import com.blockgame.input.InputHandler;
import com.blockgame.mob.MobManager;
import com.blockgame.audio.SoundEngine;
import com.blockgame.player.Player;
import com.blockgame.rendering.ParticleSystem;
import com.blockgame.rendering.Renderer;
import com.blockgame.world.World;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Main game class. Manages the GLFW window, game loop, and top-level state.
 *
 * <p>The game loop iterates a {@link List} of {@link GameSystem}s each frame,
 * so new systems (day/night cycle, mob AI, weather, …) can be added in
 * {@link #init()} without touching the loop itself.
 */
public class Game {

    public static final int WINDOW_WIDTH  = 1280;
    public static final int WINDOW_HEIGHT = 720;
    public static final String WINDOW_TITLE = "Block Game";

    /** Save file location: {@code ~/.blockgame/world.dat} */
    private static final Path SAVE_FILE =
        Path.of(System.getProperty("user.home"), ".blockgame", "world.dat");

    /**
     * Binary save-format version.  Increment whenever the on-disk layout
     * changes in a backward-incompatible way.
     */
    private static final int SAVE_FORMAT_VERSION = 2;

    private long window;
    private World world;
    private Player player;
    private Renderer renderer;
    private InputHandler inputHandler;
    private SoundEngine soundEngine;

    /** Ordered list of all active game systems; iterated every frame. */
    private final List<GameSystem> systems = new ArrayList<>();

    private double lastTime;

    /** Whether the mouse cursor is captured for first-person look. */
    private boolean cursorCaptured = true;

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

        // Build core game objects
        inputHandler = new InputHandler(window);
        world        = new World();
        player       = new Player(world, inputHandler, (float) WINDOW_WIDTH / WINDOW_HEIGHT);
        renderer     = new Renderer(window, world, player);

        // Particle system – shared between player (spawn) and renderer (draw)
        ParticleSystem particleSystem = new ParticleSystem();
        player.setParticleSystem(particleSystem);
        renderer.setParticleSystem(particleSystem);

        // Mob manager – spawns and updates all Classic-era mobs
        MobManager mobManager = new MobManager(world);
        mobManager.setPlayer(player);
        mobManager.spawnInitial(player.getPosition());
        renderer.setMobManager(mobManager);

        // Sound engine – initialised after GL context so OpenAL can coexist
        soundEngine = new SoundEngine();
        soundEngine.init();
        player.setSoundEngine(soundEngine);
        mobManager.setSoundEngine(soundEngine);

        // Restore the last saved world (if one exists)
        if (Files.exists(SAVE_FILE)) {
            try {
                loadGame(SAVE_FILE);
                System.out.println("[BlockGame] Save loaded from " + SAVE_FILE);
            } catch (IOException e) {
                System.err.println("[BlockGame] Could not load save: " + e.getMessage());
            }
        }

        // Capture mouse cursor
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);

        // Register key events
        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            inputHandler.onKey(key, action);
        });

        lastTime = glfwGetTime();

        // ---- Register game systems (in update order) ----
        // Player physics / input
        systems.add(dt -> player.update(dt));

        // World streaming – needs player position, cleanup clears chunks
        systems.add(new GameSystem() {
            @Override public void update(float dt) { world.update(player.getPosition()); }
            @Override public void cleanup()        { world.cleanup(); }
        });

        // Particle physics update – must run before the renderer draws them
        systems.add(new GameSystem() {
            @Override public void update(float dt) { particleSystem.update(dt); }
            @Override public void cleanup()        { particleSystem.cleanup(); }
        });

        // Mob AI / physics update – must run before the renderer draws them
        systems.add(mobManager);

        // Rendering – runs last each frame, cleanup frees GPU resources
        systems.add(new GameSystem() {
            @Override public void update(float dt) {
                renderer.render();
                glfwSwapBuffers(window);
            }
            @Override public void cleanup() { renderer.cleanup(); }
        });
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

            // Escape toggles cursor capture (first-person look vs. free cursor)
            if (inputHandler.isActionJustPressed(InputAction.TOGGLE_CURSOR)) {
                cursorCaptured = !cursorCaptured;
                glfwSetInputMode(window, GLFW_CURSOR,
                    cursorCaptured ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
                player.setMouseCaptured(cursorCaptured);
            }

            // Save world when Enter is pressed
            if (inputHandler.isActionJustPressed(InputAction.SAVE_WORLD)) {
                try {
                    saveGame(SAVE_FILE);
                    System.out.println("[BlockGame] World saved to " + SAVE_FILE);
                } catch (IOException e) {
                    System.err.println("[BlockGame] Could not save world: " + e.getMessage());
                }
            }

            for (GameSystem system : systems) {
                system.update(dt);
            }

            inputHandler.endFrame();
        }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    private void cleanup() {
        // Clean up systems in reverse registration order
        for (int i = systems.size() - 1; i >= 0; i--) {
            systems.get(i).cleanup();
        }

        if (soundEngine != null) soundEngine.cleanup();

        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
        GLFWErrorCallback cb = glfwSetErrorCallback(null);
        if (cb != null) {
            cb.free();
        }
    }

    // -------------------------------------------------------------------------
    // Save / Load  (versioned, tagged-section format)
    // -------------------------------------------------------------------------

    /**
     * Writes a versioned save file containing one tagged section per
     * {@link Saveable} component.
     *
     * <p>Format:
     * <pre>
     *   int     SAVE_FORMAT_VERSION
     *   section*:
     *     UTF   key          (e.g. "world", "player")
     *     int   dataLength   (bytes in the section payload)
     *     byte[dataLength]   section payload
     *   UTF     ""           (empty-string end marker)
     * </pre>
     *
     * @param file destination file (parent directories are created if absent)
     * @throws IOException on any I/O error
     */
    private void saveGame(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(SAVE_FORMAT_VERSION);
            writeSection(out, "world",  world);
            writeSection(out, "player", player);
            out.writeUTF(""); // end marker
        }
    }

    /**
     * Reads a save file written by {@link #saveGame}.  Unknown section keys
     * are skipped so that saves written by a newer version can be read by an
     * older build without error.
     *
     * @param file source file
     * @throws IOException if the version header is unrecognised or on any I/O error
     */
    private void loadGame(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            int version = in.readInt();
            if (version != SAVE_FORMAT_VERSION) {
                throw new IOException(
                    "Unsupported save format version " + version
                    + " (expected " + SAVE_FORMAT_VERSION + ")");
            }

            // Build a map of known section handlers
            Map<String, Saveable> handlers = new LinkedHashMap<>();
            handlers.put("world",  world);
            handlers.put("player", player);

            String key;
            while (!(key = in.readUTF()).isEmpty()) {
                int length = in.readInt();
                Saveable handler = handlers.get(key);
                if (handler != null) {
                    byte[] data = new byte[length];
                    in.readFully(data);
                    handler.load(new DataInputStream(new ByteArrayInputStream(data)));
                } else {
                    // Unknown section – skip gracefully
                    in.skipBytes(length);
                }
            }
        }
    }

    /**
     * Serialises {@code saveable} into a length-prefixed section and appends
     * it to {@code out}.
     */
    private static void writeSection(DataOutputStream out, String key, Saveable saveable)
            throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        saveable.save(new DataOutputStream(buf));
        byte[] data = buf.toByteArray();
        out.writeUTF(key);
        out.writeInt(data.length);
        out.write(data);
    }
}

