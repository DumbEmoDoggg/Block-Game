package com.blockgame.world;

/**
 * Classic Perlin noise (Ken Perlin's improved 2002 permutation-based version).
 *
 * <p>The implementation is entirely self-contained (no external libraries) and
 * supports an arbitrary long seed.  Use {@link #noise(double, double)} for 2-D
 * terrain sampling, or compose multiple octaves yourself with
 * {@link #octaveNoise(double, double, int, double, double)}.
 */
public final class PerlinNoise {

    private final int[] p = new int[512];

    /** Reference permutation table (Ken Perlin, 2002). */
    private static final int[] PERM = {
        151,160,137, 91, 90, 15,131, 13,201, 95, 96, 53,194,233,  7,225,
        140, 36,103, 30, 69,142,  8, 99, 37,240, 21, 10, 23,190,  6,148,
        247,120,234, 75,  0, 26,197, 62, 94,252,219,203,117, 35, 11, 32,
         57,177, 33, 88,237,149, 56, 87,174, 20,125,136,171,168, 68,175,
         74,165, 71,134,139, 48, 27,166, 77,146,158,231, 83,111,229,122,
         60,211,133,230,220,105, 92, 41, 55, 46,245, 40,244,102,143, 54,
         65, 25, 63,161,  1,216, 80, 73,209, 76,132,187,208, 89, 18,169,
        200,196,135,130,116,188,159, 86,164,100,109,198,173,186,  3, 64,
         52,217,226,250,124,123,  5,202, 38,147,118,126,255, 82, 85,212,
        207,206, 59,227, 47, 16, 58, 17,182,189, 28, 42,223,183,170,213,
        119,248,152,  2, 44,154,163, 70,221,153,101,155,167, 43,172,  9,
        129, 22, 39,253, 19, 98,108,110, 79,113,224,232,178,185,112,104,
        218,246, 97,228,251, 34,242,193,238,210,144, 12,191,179,162,241,
         81, 51,145,235,249, 14,239,107, 49,192,214, 31,181,199,106,157,
        184, 84,204,176,115,121, 50, 45,127,  4,150,254,138,236,205, 93,
        222,114, 67, 29, 24, 72,243,141,128,195, 78, 66,215, 61,156,180
    };

    /**
     * Constructs a Perlin noise generator seeded by {@code seed}.
     * The same seed always produces identical output.
     */
    public PerlinNoise(long seed) {
        // Copy and shuffle the reference permutation with a simple LCG
        int[] tmp = PERM.clone();
        java.util.Random rng = new java.util.Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int t = tmp[i]; tmp[i] = tmp[j]; tmp[j] = t;
        }
        for (int i = 0; i < 512; i++) {
            p[i] = tmp[i & 255];
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns a single-octave noise value in [-1, 1] for the given (x, z) position.
     */
    public double noise(double x, double z) {
        // Unit square containing the point
        int X = (int) Math.floor(x) & 255;
        int Z = (int) Math.floor(z) & 255;

        // Relative position inside the unit square
        double xf = x - Math.floor(x);
        double zf = z - Math.floor(z);

        // Fade curves
        double u = fade(xf);
        double w = fade(zf);

        // Hash coordinates of the 4 square corners (2-D: y = 0)
        int a  = p[X]     + Z;
        int b  = p[X + 1] + Z;
        int aa = p[a];
        int ab = p[a + 1];
        int ba = p[b];
        int bb = p[b + 1];

        // Interpolate
        return lerp(w,
                lerp(u, grad(p[aa], xf,       zf),
                        grad(p[ba], xf - 1.0,  zf)),
                lerp(u, grad(p[ab], xf,       zf - 1.0),
                        grad(p[bb], xf - 1.0,  zf - 1.0)));
    }

    /**
     * Returns a single-octave 3-D noise value in [-1, 1] for the given (x, y, z) position.
     */
    public double noise3(double x, double y, double z) {
        int X = (int) Math.floor(x) & 255;
        int Y = (int) Math.floor(y) & 255;
        int Z = (int) Math.floor(z) & 255;

        double xf = x - Math.floor(x);
        double yf = y - Math.floor(y);
        double zf = z - Math.floor(z);

        double u = fade(xf);
        double v = fade(yf);
        double w = fade(zf);

        int A  = p[X]   + Y;
        int AA = p[A]   + Z;
        int AB = p[A+1] + Z;
        int B  = p[X+1] + Y;
        int BA = p[B]   + Z;
        int BB = p[B+1] + Z;

        return lerp(w,
            lerp(v,
                lerp(u, grad3(p[AA],   xf,   yf,   zf),
                        grad3(p[BA],   xf-1, yf,   zf)),
                lerp(u, grad3(p[AB],   xf,   yf-1, zf),
                        grad3(p[BB],   xf-1, yf-1, zf))),
            lerp(v,
                lerp(u, grad3(p[AA+1], xf,   yf,   zf-1),
                        grad3(p[BA+1], xf-1, yf,   zf-1)),
                lerp(u, grad3(p[AB+1], xf,   yf-1, zf-1),
                        grad3(p[BB+1], xf-1, yf-1, zf-1))));
    }

    /**
     * Combines {@code octaves} layers of 3-D noise (fBm).
     * Returns a value roughly in [-1, 1].
     */
    public double octaveNoise3(double x, double y, double z, int octaves,
                               double persistence, double lacunarity) {
        double value     = 0.0;
        double amplitude = 1.0;
        double frequency = 1.0;
        double maxValue  = 0.0;

        for (int i = 0; i < octaves; i++) {
            value    += noise3(x * frequency, y * frequency, z * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= persistence;
            frequency *= lacunarity;
        }
        return value / maxValue;
    }

    /**
     * Combines {@code octaves} layers of noise (fBm) with the given
     * {@code persistence} (amplitude decay per octave) and {@code lacunarity}
     * (frequency multiplier per octave).  Returns a value roughly in [-1, 1].
     */
    public double octaveNoise(double x, double z, int octaves,
                              double persistence, double lacunarity) {
        double value     = 0.0;
        double amplitude = 1.0;
        double frequency = 1.0;
        double maxValue  = 0.0; // for normalization

        for (int i = 0; i < octaves; i++) {
            value     += noise(x * frequency, z * frequency) * amplitude;
            maxValue  += amplitude;
            amplitude *= persistence;
            frequency *= lacunarity;
        }
        return value / maxValue;
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /** Ken Perlin's fade / smoothstep function: 6t⁵ − 15t⁴ + 10t³ */
    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    /**
     * 2-D gradient function.  Uses the low 4 bits of {@code hash} to select one
     * of eight unit-square edge vectors.
     */
    private static double grad(int hash, double x, double z) {
        int h = hash & 7;
        double u = (h < 4) ? x : z;
        double v = (h < 4) ? z : x;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    /**
     * 3-D gradient function.  Uses the low 4 bits of {@code hash} to select one
     * of Ken Perlin's reference 16 gradient cases (which map to 12 unique cube
     * edge vectors, with h=12/h=14 and h=13/h=15 being intentional duplicates
     * for a power-of-two-friendly lookup).
     */
    private static double grad3(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
}
