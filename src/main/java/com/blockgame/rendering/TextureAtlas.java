package com.blockgame.rendering;

import com.blockgame.world.BlockType;
import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;

/**
 * Builds and uploads a block texture atlas to the GPU.
 *
 * <p>Each tile is loaded from {@code textures/<name>.png} on the classpath.
 * The mapping from tile key to PNG filename is read from
 * {@code textures/block_textures.json} at startup, so textures can be
 * remapped simply by editing that file.  If a PNG is missing or cannot be
 * decoded, the tile falls back to a procedurally generated image.
 *
 * <p>The atlas is a grid of {@value #TILE_SIZE}×{@value #TILE_SIZE} pixel tiles
 * arranged in {@value #ATLAS_COLS} columns and {@value #ATLAS_ROWS} rows.
 * Each tile index is documented by the {@code TILE_*} constants below.
 *
 * <p>Call {@link #getUV(int)} to obtain the (u0, v0, u1, v1) atlas coordinates
 * for any tile, and {@link #getTileId(BlockType, boolean, boolean)} to map a
 * block face to its tile.
 */
public class TextureAtlas {

    private static final Logger LOGGER = Logger.getLogger(TextureAtlas.class.getName());

    // -------------------------------------------------------------------------
    // Atlas layout
    // -------------------------------------------------------------------------

    public static final int TILE_SIZE  = 16;
    public static final int ATLAS_COLS = 4;
    public static final int ATLAS_ROWS = 3;
    public static final int ATLAS_W    = TILE_SIZE * ATLAS_COLS; // 64
    public static final int ATLAS_H    = TILE_SIZE * ATLAS_ROWS; // 48

    // Tile IDs (row-major: tile N is at column N%ATLAS_COLS, row N/ATLAS_COLS)
    public static final int TILE_GRASS_TOP  = 0;
    public static final int TILE_GRASS_SIDE = 1;
    public static final int TILE_DIRT       = 2;
    public static final int TILE_STONE      = 3;
    public static final int TILE_WOOD_TOP   = 4;
    public static final int TILE_WOOD_SIDE  = 5;
    public static final int TILE_LEAVES     = 6;
    public static final int TILE_SAND       = 7;
    public static final int TILE_SNOW       = 8;

    private final int textureId;

    /** Tile-key → PNG filename (without extension), loaded from block_textures.json. */
    private static final Map<String, String> TEXTURE_MAP = loadTextureMap();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public TextureAtlas() {
        BufferedImage atlas = buildAtlas();
        textureId = uploadToGpu(atlas);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Binds the atlas to the currently-active texture unit. */
    public void bind() {
        glBindTexture(GL_TEXTURE_2D, textureId);
    }

    /** Releases OpenGL resources. */
    public void cleanup() {
        glDeleteTextures(textureId);
    }

    /**
     * Returns [u0, v0, u1, v1] atlas UV coordinates for the given tile ID.
     * Coordinates are in the range [0, 1].
     */
    public static float[] getUV(int tileId) {
        int col = tileId % ATLAS_COLS;
        int row = tileId / ATLAS_COLS;
        float u0 = (float)  col          / ATLAS_COLS;
        float v0 = (float)  row          / ATLAS_ROWS;
        float u1 = (float) (col + 1)     / ATLAS_COLS;
        float v1 = (float) (row + 1)     / ATLAS_ROWS;
        return new float[]{u0, v0, u1, v1};
    }

    /**
     * Returns the tile ID for a given block face.
     *
     * @param block  the block type
     * @param isTop  {@code true} for the top face
     * @param isSide {@code true} for north/south/east/west faces; {@code false} for the bottom face
     */
    public static int getTileId(BlockType block, boolean isTop, boolean isSide) {
        switch (block) {
            case GRASS:
                if (isTop)  return TILE_GRASS_TOP;
                if (isSide) return TILE_GRASS_SIDE;
                return TILE_DIRT; // bottom
            case DIRT:   return TILE_DIRT;
            case STONE:  return TILE_STONE;
            case WOOD:
                return isTop || !isSide ? TILE_WOOD_TOP : TILE_WOOD_SIDE;
            case LEAVES: return TILE_LEAVES;
            case SAND:   return TILE_SAND;
            case SNOW:   return TILE_SNOW;
            default:     return TILE_DIRT;
        }
    }

    // -------------------------------------------------------------------------
    // Atlas generation
    // -------------------------------------------------------------------------

    private BufferedImage buildAtlas() {
        BufferedImage img = new BufferedImage(ATLAS_W, ATLAS_H, BufferedImage.TYPE_INT_ARGB);

        drawTile(img, TILE_GRASS_TOP,  loadTile(textureName("grass_top"),  () -> grassTop()));
        drawTile(img, TILE_GRASS_SIDE, loadTile(textureName("grass_side"), () -> grassSide()));
        drawTile(img, TILE_DIRT,       loadTile(textureName("dirt"),       () -> dirt()));
        drawTile(img, TILE_STONE,      loadTile(textureName("stone"),      () -> stone()));
        drawTile(img, TILE_WOOD_TOP,   loadTile(textureName("wood_top"),   () -> woodTop()));
        drawTile(img, TILE_WOOD_SIDE,  loadTile(textureName("wood_side"),  () -> woodSide()));
        drawTile(img, TILE_LEAVES,     loadTile(textureName("leaves"),     () -> leaves()));
        drawTile(img, TILE_SAND,       loadTile(textureName("sand"),       () -> sand()));
        drawTile(img, TILE_SNOW,       loadTile(textureName("snow"),       () -> snow()));

        return img;
    }

    /**
     * Returns the PNG filename (without extension) for the given tile key,
     * falling back to the key itself when the key is absent from the map.
     */
    private static String textureName(String key) {
        return TEXTURE_MAP.getOrDefault(key, key);
    }

    /**
     * Reads {@code textures/block_textures.json} from the classpath and returns
     * a map of tile-key → PNG filename (without extension).
     *
     * <p>The file is a simple flat JSON object, e.g.:
     * <pre>
     * {
     *   "grass_top": "grass_block_top",
     *   "dirt":      "dirt"
     * }
     * </pre>
     * If the file is missing or malformed the map is empty, causing each tile
     * to fall back to the procedural generator.
     *
     * <p>Parsing uses a simple regex rather than a full JSON library because
     * the file is a flat string-to-string object with no nesting, and adding
     * an external JSON dependency would be disproportionate for this use case.
     * Keys and values must not contain escaped quotes.
     */
    private static Map<String, String> loadTextureMap() {
        Map<String, String> map = new HashMap<>();
        String path = "/textures/block_textures.json";
        try (InputStream in = TextureAtlas.class.getResourceAsStream(path)) {
            if (in == null) {
                LOGGER.warning("block_textures.json not found on classpath; using procedural fallbacks.");
                return map;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            // Parse flat JSON object: "key" : "value" pairs (no nesting required)
            Pattern pair = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");
            Matcher m = pair.matcher(sb.toString());
            while (m.find()) {
                map.put(m.group(1), m.group(2));
            }
            LOGGER.info("Loaded " + map.size() + " texture mappings from " + path);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to read " + path, e);
        }
        return map;
    }

    /**
     * Loads a 16×16 tile image from {@code textures/<name>.png} on the classpath.
     * Falls back to the supplied procedural generator if the resource is missing or unreadable.
     */
    private static BufferedImage loadTile(String name, java.util.function.Supplier<BufferedImage> fallback) {
        String path = "/textures/" + name + ".png";
        try (InputStream in = TextureAtlas.class.getResourceAsStream(path)) {
            if (in != null) {
                BufferedImage src = ImageIO.read(in);
                if (src != null) {
                    // Ensure the image is in ARGB format and scaled to TILE_SIZE × TILE_SIZE
                    BufferedImage tile = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics g = tile.getGraphics();
                    try {
                        g.drawImage(src, 0, 0, TILE_SIZE, TILE_SIZE, null);
                    } finally {
                        g.dispose();
                    }
                    return tile;
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load texture: " + path, e);
        }
        LOGGER.info("Texture not found, using procedural fallback: " + path);
        return fallback.get();
    }

    /** Copies a tile image into the atlas at the position corresponding to {@code tileId}. */
    private static void drawTile(BufferedImage atlas, int tileId, BufferedImage tile) {
        int ox = (tileId % ATLAS_COLS) * TILE_SIZE;
        int oy = (tileId / ATLAS_COLS) * TILE_SIZE;
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                atlas.setRGB(ox + x, oy + y, tile.getRGB(x, y));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Individual tile generators – each returns a 16×16 BufferedImage
    // -------------------------------------------------------------------------

    private static BufferedImage grassTop() {
        Random rng = new Random(1L);
        BufferedImage img = solid(76, 163, 36);
        for (int i = 0; i < 30; i++) {
            int x = rng.nextInt(TILE_SIZE);
            int y = rng.nextInt(TILE_SIZE);
            setRgb(img, x, y, 55, 130, 20);
        }
        for (int i = 0; i < 10; i++) {
            int x = rng.nextInt(TILE_SIZE);
            int y = rng.nextInt(TILE_SIZE);
            setRgb(img, x, y, 95, 185, 45);
        }
        return img;
    }

    private static BufferedImage grassSide() {
        // Bottom 11 rows = dirt, top 5 rows = green strip
        BufferedImage img = dirt();
        Random rng = new Random(10L);
        for (int py = 0; py < 5; py++) {
            for (int px = 0; px < TILE_SIZE; px++) {
                int v = 163 - rng.nextInt(20);
                setRgb(img, px, py, 76, v, 36);
            }
        }
        return img;
    }

    private static BufferedImage dirt() {
        Random rng = new Random(2L);
        BufferedImage img = solid(134, 96, 67);
        for (int i = 0; i < 40; i++) {
            int x = rng.nextInt(TILE_SIZE);
            int y = rng.nextInt(TILE_SIZE);
            int d = rng.nextInt(25);
            setRgb(img, x, y, 110 - d, 72 - d, 48 - d);
        }
        for (int i = 0; i < 10; i++) {
            int x = rng.nextInt(TILE_SIZE);
            int y = rng.nextInt(TILE_SIZE);
            setRgb(img, x, y, 155, 115, 82);
        }
        return img;
    }

    private static BufferedImage stone() {
        Random rng = new Random(3L);
        BufferedImage img = solid(130, 130, 130);
        for (int i = 0; i < 30; i++) {
            int x = rng.nextInt(TILE_SIZE);
            int y = rng.nextInt(TILE_SIZE);
            int d = rng.nextInt(30);
            setRgb(img, x, y, 100 - d, 100 - d, 100 - d);
        }
        // A few lighter highlights
        for (int i = 0; i < 8; i++) {
            int x = rng.nextInt(TILE_SIZE);
            int y = rng.nextInt(TILE_SIZE);
            setRgb(img, x, y, 160, 160, 160);
        }
        // Horizontal crack lines
        for (int px = 2; px < 7; px++) setRgb(img, px, 5,  90, 90, 90);
        for (int px = 9; px < 14; px++) setRgb(img, px, 11, 90, 90, 90);
        return img;
    }

    private static BufferedImage woodTop() {
        // End-grain cross section: concentric rings on a light base
        BufferedImage img = solid(198, 152, 73);
        int cx = TILE_SIZE / 2;
        int cy = TILE_SIZE / 2;
        for (int py = 0; py < TILE_SIZE; py++) {
            for (int px = 0; px < TILE_SIZE; px++) {
                double dist = Math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy));
                // Draw ring outlines at radii 2, 4, 6
                if (Math.abs(dist - 2.0) < 0.6 || Math.abs(dist - 4.5) < 0.6 || Math.abs(dist - 6.5) < 0.6) {
                    setRgb(img, px, py, 120, 75, 28);
                }
            }
        }
        // Center dot
        setRgb(img, cx,     cy,     140, 90, 35);
        setRgb(img, cx - 1, cy,     140, 90, 35);
        setRgb(img, cx,     cy - 1, 140, 90, 35);
        return img;
    }

    private static BufferedImage woodSide() {
        // Vertical bark stripes
        Random rng = new Random(5L);
        BufferedImage img = solid(160, 100, 35);
        // Darker vertical grain lines
        for (int px = 2; px < TILE_SIZE; px += 4) {
            for (int py = 0; py < TILE_SIZE; py++) {
                int v = rng.nextInt(15);
                setRgb(img, px, py, 120 - v, 70 - v, 22 - v / 2);
            }
        }
        // Lighter stripes between
        for (int px = 0; px < TILE_SIZE; px += 4) {
            for (int py = 0; py < TILE_SIZE; py++) {
                int v = rng.nextInt(10);
                setRgb(img, px, py, 180 + v, 118 + v, 50 + v);
            }
        }
        return img;
    }

    private static BufferedImage leaves() {
        Random rng = new Random(6L);
        // Base dark green
        BufferedImage img = solid(38, 115, 22);
        for (int py = 0; py < TILE_SIZE; py++) {
            for (int px = 0; px < TILE_SIZE; px++) {
                int v = rng.nextInt(30);
                int base = img.getRGB(px, py);
                int g = (base >> 8) & 0xFF;
                setRgb(img, px, py, 30, Math.max(80, g - v), 15);
            }
        }
        // Lighter leaf highlights
        for (int i = 0; i < 20; i++) {
            setRgb(img, rng.nextInt(TILE_SIZE), rng.nextInt(TILE_SIZE), 55, 155, 35);
        }
        // A few dark "gaps" (very dark green, simulating depth)
        for (int i = 0; i < 10; i++) {
            setRgb(img, rng.nextInt(TILE_SIZE), rng.nextInt(TILE_SIZE), 18, 70, 10);
        }
        return img;
    }

    private static BufferedImage sand() {
        Random rng = new Random(7L);
        BufferedImage img = solid(219, 196, 118);
        for (int py = 0; py < TILE_SIZE; py++) {
            for (int px = 0; px < TILE_SIZE; px++) {
                int v = rng.nextInt(18) - 9;
                setRgb(img, px, py, clamp(219 + v), clamp(196 + v), clamp(118 + v));
            }
        }
        // A few slightly darker grains
        for (int i = 0; i < 15; i++) {
            setRgb(img, rng.nextInt(TILE_SIZE), rng.nextInt(TILE_SIZE), 195, 168, 90);
        }
        return img;
    }

    private static BufferedImage snow() {
        Random rng = new Random(8L);
        BufferedImage img = solid(237, 243, 255);
        for (int py = 0; py < TILE_SIZE; py++) {
            for (int px = 0; px < TILE_SIZE; px++) {
                int v = rng.nextInt(14) - 4;
                setRgb(img, px, py, clamp(237 + v), clamp(243 + v), 255);
            }
        }
        return img;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates a solid-colour 16×16 tile. */
    private static BufferedImage solid(int r, int g, int b) {
        BufferedImage img = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        int argb = 0xFF000000 | (r << 16) | (g << 8) | b;
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                img.setRGB(x, y, argb);
            }
        }
        return img;
    }

    private static void setRgb(BufferedImage img, int x, int y, int r, int g, int b) {
        img.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // -------------------------------------------------------------------------
    // GPU upload
    // -------------------------------------------------------------------------

    private static int uploadToGpu(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();

        // Convert ARGB ints -> RGBA bytes
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
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glGenerateMipmap(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, 0);

        return id;
    }
}
