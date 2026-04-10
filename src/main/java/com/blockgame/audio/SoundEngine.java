package com.blockgame.audio;

import com.blockgame.mob.MobType;
import com.blockgame.world.BlockType;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * OpenAL-based sound engine.
 *
 * <p>Supports both 2-D (positional-independent) sounds for UI and player
 * feedback, and 3-D positional sounds for world events (block breaks, mob
 * noises, etc.).
 *
 * <p>OGG/Vorbis files are decoded on first use via {@code stb_vorbis} and
 * cached as OpenAL buffers for subsequent plays.  Up to
 * {@value #SOURCE_POOL_SIZE} sounds may play simultaneously; if all sources
 * are busy the oldest source is reused.
 *
 * <p>Initialisation may fail gracefully (e.g. on a headless CI server with no
 * audio device): all play methods silently no-op when the engine is not
 * initialised.
 */
public class SoundEngine {

    private static final int SOURCE_POOL_SIZE = 24;

    /** Horizontal distance between successive footstep sounds (player & mobs). */
    public static final float STEP_DISTANCE = 1.5f;

    /** Seconds between swim sounds when moving in water. */
    public static final float SWIM_INTERVAL = 0.45f;

    /** Minimum seconds between mob ambient "say" sounds. */
    public static final float MOB_AMBIENT_INTERVAL_MIN = 10f;
    /** Maximum seconds between mob ambient "say" sounds. */
    public static final float MOB_AMBIENT_INTERVAL_MAX = 20f;

    // -------------------------------------------------------------------------
    // Sound path arrays
    // -------------------------------------------------------------------------

    // Block dig (break) sounds
    private static final String[] DIG_STONE   = paths("/Sounds/dig/stone",    4);
    private static final String[] DIG_WOOD    = paths("/Sounds/dig/wood",     4);
    private static final String[] DIG_GRASS   = paths("/Sounds/dig/grass",    4);
    private static final String[] DIG_GRAVEL  = paths("/Sounds/dig/gravel",   4);
    private static final String[] DIG_SAND    = paths("/Sounds/dig/sand",     4);
    private static final String[] DIG_SNOW    = paths("/Sounds/dig/snow",     4);
    private static final String[] DIG_CLOTH   = paths("/Sounds/dig/cloth",    4);
    private static final String[] DIG_WGRASS  = paths("/Sounds/dig/wet_grass", 4);

    // Block step (footstep) sounds
    private static final String[] STEP_STONE  = paths("/Sounds/step/stone",    6);
    private static final String[] STEP_WOOD   = paths("/Sounds/step/wood",     6);
    private static final String[] STEP_GRASS  = paths("/Sounds/step/grass",    6);
    private static final String[] STEP_GRAVEL = paths("/Sounds/step/gravel",   4);
    private static final String[] STEP_SAND   = paths("/Sounds/step/sand",     5);
    private static final String[] STEP_SNOW   = paths("/Sounds/step/snow",     4);
    private static final String[] STEP_CLOTH  = paths("/Sounds/step/cloth",    4);
    private static final String[] STEP_WGRASS = paths("/Sounds/step/wet_grass", 6);

    // Liquid sounds
    private static final String[] SWIM        = paths("/Sounds/liquid/swim",   18);
    private static final String[] SPLASH      = {
        "/Sounds/liquid/splash.ogg", "/Sounds/liquid/splash2.ogg"
    };

    // Player sounds
    private static final String[] ATTACK_STRONG = paths("/Sounds/player/attack/strong", 6);
    private static final String[] ATTACK_WEAK   = paths("/Sounds/player/attack/weak",   4);
    private static final String[] HURT_DROWN    = paths("/Sounds/player/hurt/drown",    4);

    // Mob sounds
    private static final String[] ZOMBIE_SAY    = paths("/Sounds/Mob/zombie/say",    3);
    private static final String[] ZOMBIE_HURT   = paths("/Sounds/Mob/zombie/hurt",   2);
    private static final String   ZOMBIE_DEATH  = "/Sounds/Mob/zombie/death.ogg";
    private static final String[] ZOMBIE_STEP   = paths("/Sounds/Mob/zombie/step",   5);

    private static final String[] SKELETON_SAY   = paths("/Sounds/Mob/skeleton/say",  3);
    private static final String[] SKELETON_HURT  = paths("/Sounds/Mob/skeleton/hurt", 4);
    private static final String   SKELETON_DEATH = "/Sounds/Mob/skeleton/death.ogg";
    private static final String[] SKELETON_STEP  = paths("/Sounds/Mob/skeleton/step", 4);

    private static final String[] CREEPER_SAY   = paths("/Sounds/Mob/creeper/say", 4);
    private static final String   CREEPER_DEATH = "/Sounds/Mob/creeper/death.ogg";

    private static final String[] SPIDER_SAY   = paths("/Sounds/Mob/spider/say",  4);
    private static final String   SPIDER_DEATH = "/Sounds/Mob/spider/death.ogg";
    private static final String[] SPIDER_STEP  = paths("/Sounds/Mob/spider/step", 4);

    private static final String[] PIG_SAY   = paths("/Sounds/Mob/pig/say",  3);
    private static final String   PIG_DEATH = "/Sounds/Mob/pig/death.ogg";
    private static final String[] PIG_STEP  = paths("/Sounds/Mob/pig/step", 5);

    private static final String[] SHEEP_SAY  = paths("/Sounds/Mob/sheep/say",  3);
    private static final String[] SHEEP_STEP = paths("/Sounds/Mob/sheep/step", 5);

    // -------------------------------------------------------------------------
    // OpenAL state
    // -------------------------------------------------------------------------

    private long device;
    private long context;
    private boolean initialized = false;

    private final Map<String, Integer> bufferCache = new HashMap<>();
    private final int[] sources = new int[SOURCE_POOL_SIZE];

    private final Random random = new Random();

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Initialises the OpenAL device, context, and source pool.
     *
     * <p>If no audio device is available (headless environments, missing
     * audio drivers) this method prints a warning and returns without
     * throwing; all subsequent play calls are silently ignored.
     */
    public void init() {
        device = ALC10.alcOpenDevice((ByteBuffer) null);
        if (device == 0) {
            System.err.println("[SoundEngine] No OpenAL device available – audio disabled.");
            return;
        }

        ALCCapabilities alcCaps = ALC.createCapabilities(device);
        context = ALC10.alcCreateContext(device, (IntBuffer) null);
        if (context == 0) {
            System.err.println("[SoundEngine] Failed to create OpenAL context – audio disabled.");
            ALC10.alcCloseDevice(device);
            return;
        }

        ALC10.alcMakeContextCurrent(context);
        AL.createCapabilities(alcCaps);

        AL10.alGenSources(sources);
        AL10.alListenerf(AL10.AL_GAIN, 1.0f);
        AL10.alDistanceModel(AL11.AL_LINEAR_DISTANCE_CLAMPED);

        initialized = true;
    }

    /**
     * Frees all OpenAL resources (sources, cached buffers, context, device).
     * Safe to call even if {@link #init()} was not called or failed.
     */
    public void cleanup() {
        if (!initialized) return;

        AL10.alDeleteSources(sources);
        for (int buf : bufferCache.values()) {
            if (buf >= 0) AL10.alDeleteBuffers(buf);
        }
        bufferCache.clear();

        ALC10.alcMakeContextCurrent(0);
        ALC10.alcDestroyContext(context);
        ALC10.alcCloseDevice(device);
        initialized = false;
    }

    // -------------------------------------------------------------------------
    // Listener
    // -------------------------------------------------------------------------

    /**
     * Updates the OpenAL listener position and orientation.
     *
     * @param x  listener world X
     * @param y  listener world Y
     * @param z  listener world Z
     * @param fx look-direction X component
     * @param fy look-direction Y component
     * @param fz look-direction Z component
     */
    public void setListenerPosition(float x, float y, float z,
                                    float fx, float fy, float fz) {
        if (!initialized) return;
        AL10.alListener3f(AL10.AL_POSITION, x, y, z);
        AL10.alListenerfv(AL10.AL_ORIENTATION, new float[]{fx, fy, fz, 0f, 1f, 0f});
    }

    // -------------------------------------------------------------------------
    // Block sounds
    // -------------------------------------------------------------------------

    /** Plays a random block-break (dig) sound for the given block type. */
    public void playBlockBreak(BlockType type, float x, float y, float z) {
        playRandom3D(digSounds(type), x, y, z);
    }

    /**
     * Plays a random footstep sound for the given block-under-foot.
     *
     * @param underFoot block type directly beneath the player/mob feet
     */
    public void playBlockStep(BlockType underFoot, float x, float y, float z) {
        playRandom3D(stepSounds(underFoot), x, y, z);
    }

    // -------------------------------------------------------------------------
    // Liquid sounds
    // -------------------------------------------------------------------------

    /** Plays a random swimming stroke sound. */
    public void playSwim(float x, float y, float z) {
        playRandom3D(SWIM, x, y, z);
    }

    /** Plays a random water-entry splash sound. */
    public void playSplash(float x, float y, float z) {
        playRandom3D(SPLASH, x, y, z);
    }

    // -------------------------------------------------------------------------
    // Player sounds
    // -------------------------------------------------------------------------

    /** Plays a strong hit sound (player connected with a melee swing). */
    public void playPlayerAttackHit() {
        playRandom2D(ATTACK_STRONG);
    }

    /** Plays a weak swing sound (player swung but missed). */
    public void playPlayerAttackMiss() {
        playRandom2D(ATTACK_WEAK);
    }

    /** Plays a drowning-damage sound. */
    public void playPlayerDrownDamage() {
        playRandom2D(HURT_DROWN);
    }

    // -------------------------------------------------------------------------
    // Mob sounds
    // -------------------------------------------------------------------------

    /** Plays a random ambient "say" sound for the given mob type. */
    public void playMobAmbient(MobType type, float x, float y, float z) {
        playRandom3D(mobSaySounds(type), x, y, z);
    }

    /** Plays a random step sound for the given mob type (if any). */
    public void playMobStep(MobType type, float x, float y, float z) {
        String[] steps = mobStepSounds(type);
        if (steps != null) playRandom3D(steps, x, y, z);
    }

    /** Plays a random hurt sound for the given mob type (if any). */
    public void playMobHurt(MobType type, float x, float y, float z) {
        String[] hurts = mobHurtSounds(type);
        if (hurts != null) playRandom3D(hurts, x, y, z);
    }

    /** Plays the death sound for the given mob type (if any). */
    public void playMobDeath(MobType type, float x, float y, float z) {
        String death = mobDeathSound(type);
        if (death != null) play3D(death, x, y, z);
    }

    // -------------------------------------------------------------------------
    // Internal play helpers
    // -------------------------------------------------------------------------

    private void playRandom2D(String[] paths) {
        if (paths == null || paths.length == 0) return;
        play2D(paths[random.nextInt(paths.length)]);
    }

    private void playRandom3D(String[] paths, float x, float y, float z) {
        if (paths == null || paths.length == 0) return;
        play3D(paths[random.nextInt(paths.length)], x, y, z);
    }

    /** Plays a 2-D (positional-independent) sound. */
    private void play2D(String path) {
        if (!initialized) return;
        int buf = getOrLoadBuffer(path);
        if (buf < 0) return;
        int src = getFreeSource();
        AL10.alSourcei(src, AL10.AL_BUFFER, buf);
        AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSource3f(src, AL10.AL_POSITION, 0f, 0f, 0f);
        AL10.alSourcef(src, AL10.AL_ROLLOFF_FACTOR, 0f);
        AL10.alSourcef(src, AL10.AL_GAIN, 1.0f);
        AL10.alSourcePlay(src);
    }

    /** Plays a 3-D positional sound at the given world coordinates. */
    private void play3D(String path, float x, float y, float z) {
        if (!initialized) return;
        int buf = getOrLoadBuffer(path);
        if (buf < 0) return;
        int src = getFreeSource();
        AL10.alSourcei(src, AL10.AL_BUFFER, buf);
        AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
        AL10.alSource3f(src, AL10.AL_POSITION, x, y, z);
        AL10.alSourcef(src, AL10.AL_REFERENCE_DISTANCE, 8f);
        AL10.alSourcef(src, AL10.AL_MAX_DISTANCE, 32f);
        AL10.alSourcef(src, AL10.AL_ROLLOFF_FACTOR, 1f);
        AL10.alSourcef(src, AL10.AL_GAIN, 1.0f);
        AL10.alSourcePlay(src);
    }

    private int getFreeSource() {
        for (int s : sources) {
            if (AL10.alGetSourcei(s, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) return s;
        }
        return sources[0]; // all busy – reuse the first (oldest) source
    }

    // -------------------------------------------------------------------------
    // Buffer loading (lazy, cached)
    // -------------------------------------------------------------------------

    private int getOrLoadBuffer(String path) {
        Integer cached = bufferCache.get(path);
        if (cached != null) return cached;

        int result = loadBuffer(path);
        bufferCache.put(path, result);
        return result;
    }

    private int loadBuffer(String path) {
        try (InputStream is = SoundEngine.class.getResourceAsStream(path)) {
            if (is == null) {
                System.err.println("[SoundEngine] Resource not found: " + path);
                return -1;
            }

            byte[] raw = is.readAllBytes();
            ByteBuffer rawBuf = MemoryUtil.memAlloc(raw.length);
            rawBuf.put(raw).flip();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer channels    = stack.mallocInt(1);
                IntBuffer sampleRate  = stack.mallocInt(1);
                ShortBuffer pcm = STBVorbis.stb_vorbis_decode_memory(rawBuf, channels, sampleRate);
                MemoryUtil.memFree(rawBuf);

                if (pcm == null) {
                    System.err.println("[SoundEngine] OGG decode failed: " + path);
                    return -1;
                }

                int format = (channels.get(0) == 1) ? AL10.AL_FORMAT_MONO16
                                                     : AL10.AL_FORMAT_STEREO16;
                int alBuf = AL10.alGenBuffers();
                AL10.alBufferData(alBuf, format, pcm, sampleRate.get(0));
                MemoryUtil.memFree(pcm);
                return alBuf;
            }
        } catch (IOException e) {
            System.err.println("[SoundEngine] IO error loading " + path + ": " + e.getMessage());
            return -1;
        }
    }

    // -------------------------------------------------------------------------
    // Sound-group mappings
    // -------------------------------------------------------------------------

    private static String[] digSounds(BlockType type) {
        return switch (type) {
            case GRASS, DIRT                               -> DIG_GRASS;
            case WOOD, PLANKS, BOOKSHELF                   -> DIG_WOOD;
            case GRAVEL                                    -> DIG_GRAVEL;
            case SAND                                      -> DIG_SAND;
            case SNOW                                      -> DIG_SNOW;
            case SPONGE, TNT                               -> DIG_CLOTH;
            case LEAVES                                    -> DIG_WGRASS;
            default                                        -> DIG_STONE;
        };
    }

    private static String[] stepSounds(BlockType type) {
        return switch (type) {
            case GRASS, DIRT                               -> STEP_GRASS;
            case WOOD, PLANKS, BOOKSHELF                   -> STEP_WOOD;
            case GRAVEL                                    -> STEP_GRAVEL;
            case SAND                                      -> STEP_SAND;
            case SNOW                                      -> STEP_SNOW;
            case SPONGE, TNT                               -> STEP_CLOTH;
            case LEAVES                                    -> STEP_WGRASS;
            default                                        -> STEP_STONE;
        };
    }

    private static String[] mobSaySounds(MobType type) {
        return switch (type) {
            case ZOMBIE   -> ZOMBIE_SAY;
            case SKELETON -> SKELETON_SAY;
            case CREEPER  -> CREEPER_SAY;
            case SPIDER   -> SPIDER_SAY;
            case PIG      -> PIG_SAY;
            case SHEEP    -> SHEEP_SAY;
        };
    }

    private static String[] mobStepSounds(MobType type) {
        return switch (type) {
            case ZOMBIE   -> ZOMBIE_STEP;
            case SKELETON -> SKELETON_STEP;
            case SPIDER   -> SPIDER_STEP;
            case PIG      -> PIG_STEP;
            case SHEEP    -> SHEEP_STEP;
            default       -> null; // CREEPER has no step sounds
        };
    }

    private static String[] mobHurtSounds(MobType type) {
        return switch (type) {
            case ZOMBIE   -> ZOMBIE_HURT;
            case SKELETON -> SKELETON_HURT;
            default       -> null; // CREEPER, SPIDER, PIG, SHEEP have no hurt sounds
        };
    }

    private static String mobDeathSound(MobType type) {
        return switch (type) {
            case ZOMBIE   -> ZOMBIE_DEATH;
            case SKELETON -> SKELETON_DEATH;
            case CREEPER  -> CREEPER_DEATH;
            case SPIDER   -> SPIDER_DEATH;
            case PIG      -> PIG_DEATH;
            default       -> null; // SHEEP has no death sound
        };
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Builds an array of resource paths of the form
     * {@code base + n + ".ogg"} for {@code n = 1 … count}.
     */
    private static String[] paths(String base, int count) {
        String[] result = new String[count];
        for (int i = 0; i < count; i++) {
            result[i] = base + (i + 1) + ".ogg";
        }
        return result;
    }
}
