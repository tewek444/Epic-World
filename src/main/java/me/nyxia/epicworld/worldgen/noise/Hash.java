package me.nyxia.epicworld.worldgen.noise;

/** Stateless integer hashing for jittered grids. Same inputs always give the same values on every platform. */
public final class Hash {
    private Hash() {}

    public static long mix(long a, long b) {
        long z = a * 0x9E3779B97F4A7C15L + b + 0x632BE59BD9B4E019L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static long cell(long seed, int x, int z, int salt) {
        return mix(mix(mix(seed, salt), x), z);
    }

    public static long cell3(long seed, int x, int y, int z, int salt) {
        return mix(cell(seed, x, z, salt), y);
    }

    /** Uniform double in [0, 1) from a hash and an index into it. */
    public static double unit(long hash, int index) {
        return (mix(hash, index) >>> 11) * 0x1.0p-53;
    }
}
