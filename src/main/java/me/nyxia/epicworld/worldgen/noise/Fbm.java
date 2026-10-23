package me.nyxia.epicworld.worldgen.noise;

/** Fractal sums of independent simplex layers. Each octave has its own permutation. */
public final class Fbm {
    private final Simplex2[] octaves;
    private final double gain;
    private final double norm;

    public Fbm(long seed, int octaves, double gain) {
        this.octaves = new Simplex2[octaves];
        this.gain = gain;
        double amplitude = 1;
        double total = 0;
        for (int i = 0; i < octaves; i++) {
            this.octaves[i] = new Simplex2(Hash.mix(seed, i * 0x9E3779B97F4A7C15L + 17));
            total += amplitude;
            amplitude *= gain;
        }
        this.norm = 1 / total;
    }

    /** Sum with octave periods {@code period, period/2, ...}; normalized to roughly [-1, 1]. */
    public double sample(double x, double z, double period) {
        double f = 1 / period;
        double amplitude = 1;
        double sum = 0;
        for (Simplex2 octave : octaves) {
            sum += amplitude * octave.sample(x * f, z * f);
            f *= 2.03;
            amplitude *= gain;
        }
        return sum * norm;
    }

    /** Ridged multifractal in [0, 1]: sharp crests where the base noise crosses zero. */
    public double ridged(double x, double z, double period) {
        double f = 1 / period;
        double amplitude = 1;
        double weight = 1;
        double sum = 0;
        double total = 0;
        for (Simplex2 octave : octaves) {
            double n = 1 - Math.abs(octave.sample(x * f, z * f));
            n *= n;
            n *= weight;
            weight = Math.min(1, n * 1.6);
            sum += n * amplitude;
            total += amplitude;
            f *= 2.03;
            amplitude *= gain;
        }
        return sum / total;
    }

    /**
     * Fbm whose finer octaves fade where the coarser ones are steep (after Inigo Quilez): smooth, broad valleys and
     * detailed crests, as erosion leaves them. Roughly in [-1, 1].
     */
    public double eroded(double x, double z, double period) {
        double f = 1 / period;
        double amplitude = 1;
        double sum = 0;
        double dx = 0;
        double dz = 0;
        double[] g = new double[2];
        for (Simplex2 octave : octaves) {
            double n = octave.sampleWithGradient(x * f, z * f, g);
            // World-space slope of an octave is its noise gradient times frequency; with gain 0.5 and frequency x2 per
            // octave, amplitude * frequency stays constant, so every octave counts the same: add the raw gradient.
            dx += g[0];
            dz += g[1];
            sum += amplitude * n / (1 + 0.6 * (dx * dx + dz * dz));
            f *= 2.03;
            amplitude *= gain;
        }
        return sum * norm;
    }

    /**
     * Ridged multifractal with rounded crests: {@code 1 - sqrt(v^2 + softness)} instead of {@code 1 - |v|}, so a crest
     * is a narrow rounded ridge, not a knife edge one block wide. In [0, 1].
     */
    public double ridgedSoft(double x, double z, double period, double softness) {
        double f = 1 / period;
        double amplitude = 1;
        double weight = 1;
        double sum = 0;
        double total = 0;
        double top = 1 - Math.sqrt(softness);
        for (Simplex2 octave : octaves) {
            double v = octave.sample(x * f, z * f);
            double n = (1 - Math.sqrt(v * v + softness)) / top;
            n *= n;
            n *= weight;
            weight = Math.min(1, n * 1.6);
            sum += n * amplitude;
            total += amplitude;
            f *= 2.03;
            amplitude *= gain;
        }
        return sum / total;
    }

    public double first(double x, double z, double period) {
        return octaves[0].sample(x / period, z / period);
    }
}
