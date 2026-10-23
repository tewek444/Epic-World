package me.nyxia.epicworld.worldgen.noise;

import java.util.SplittableRandom;

/** Seeded 2D simplex noise. Immutable and thread-safe; output is roughly in [-1, 1]. */
public final class Simplex2 {
    private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
    private static final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;
    private static final int GRADIENTS = 24;
    private static final double[] GX = new double[GRADIENTS];
    private static final double[] GY = new double[GRADIENTS];

    static {
        for (int i = 0; i < GRADIENTS; i++) {
            double angle = (i + 0.5) * Math.PI * 2 / GRADIENTS;
            GX[i] = Math.cos(angle);
            GY[i] = Math.sin(angle);
        }
    }

    private final short[] perm = new short[512];
    /** {@code perm[i] % GRADIENTS}, so corners need no division. */
    private final byte[] grad = new byte[512];
    private final double offsetX;
    private final double offsetY;

    public Simplex2(long seed) {
        var random = new SplittableRandom(seed);
        short[] p = new short[256];
        for (short i = 0; i < 256; i++) p[i] = i;
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            short t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) {
            perm[i] = p[i & 255];
            grad[i] = (byte) (perm[i] % GRADIENTS);
        }
        offsetX = random.nextDouble() * 4096;
        offsetY = random.nextDouble() * 4096;
    }

    public double sample(double x, double y) {
        x += offsetX;
        y += offsetY;
        double s = (x + y) * F2;
        int i = floor(x + s);
        int j = floor(y + s);
        double t = (i + j) * G2;
        double x0 = x - (i - t);
        double y0 = y - (j - t);
        int i1 = x0 > y0 ? 1 : 0;
        int j1 = 1 - i1;
        double x1 = x0 - i1 + G2;
        double y1 = y0 - j1 + G2;
        double x2 = x0 - 1 + 2 * G2;
        double y2 = y0 - 1 + 2 * G2;
        int ii = i & 255;
        int jj = j & 255;
        double n = corner(grad[ii + perm[jj]], x0, y0)
                + corner(grad[ii + i1 + perm[jj + j1]], x1, y1)
                + corner(grad[ii + 1 + perm[jj + 1]], x2, y2);
        return 70.0 * n;
    }

    /**
     * Noise value with its gradient: {@code out[0]} = d/dx, {@code out[1]} = d/dy (in noise units). Same value as
     * {@link #sample}.
     */
    public double sampleWithGradient(double x, double y, double[] out) {
        x += offsetX;
        y += offsetY;
        double s = (x + y) * F2;
        int i = floor(x + s);
        int j = floor(y + s);
        double t = (i + j) * G2;
        double x0 = x - (i - t);
        double y0 = y - (j - t);
        int i1 = x0 > y0 ? 1 : 0;
        int j1 = 1 - i1;
        double x1 = x0 - i1 + G2;
        double y1 = y0 - j1 + G2;
        double x2 = x0 - 1 + 2 * G2;
        double y2 = y0 - 1 + 2 * G2;
        int ii = i & 255;
        int jj = j & 255;
        out[0] = 0;
        out[1] = 0;
        double n = cornerWithGradient(grad[ii + perm[jj]], x0, y0, out)
                + cornerWithGradient(grad[ii + i1 + perm[jj + j1]], x1, y1, out)
                + cornerWithGradient(grad[ii + 1 + perm[jj + 1]], x2, y2, out);
        out[0] *= 70.0;
        out[1] *= 70.0;
        return 70.0 * n;
    }

    private static double cornerWithGradient(int g, double x, double y, double[] out) {
        double t = 0.5 - x * x - y * y;
        if (t <= 0) return 0;
        double gx = GX[g];
        double gy = GY[g];
        double dot = gx * x + gy * y;
        double t2 = t * t;
        double t4 = t2 * t2;
        // d/dx of t^4 * dot = -8 t^3 x dot + t^4 gx.
        double k = -8 * t2 * t * dot;
        out[0] += k * x + t4 * gx;
        out[1] += k * y + t4 * gy;
        return t4 * dot;
    }

    private static double corner(int g, double x, double y) {
        double t = 0.5 - x * x - y * y;
        if (t <= 0) return 0;
        t *= t;
        return t * t * (GX[g] * x + GY[g] * y);
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
