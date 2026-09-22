package com.slashslabs.color;

/** Pure colour helpers (no Minecraft): sRGB to CIELAB, nearest palette entry, texture tinting. */
public final class ColorMath {
    private ColorMath() {}

    /** CIELAB (D65) of a packed 0xRRGGBB colour. */
    public static double[] lab(int rgb) {
        double r = lin(((rgb >> 16) & 0xFF) / 255.0);
        double g = lin(((rgb >> 8) & 0xFF) / 255.0);
        double b = lin((rgb & 0xFF) / 255.0);
        double x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047;
        double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        double z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
        double fx = f(x), fy = f(y), fz = f(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double lin(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double f(double t) {
        return t > 216.0 / 24389.0 ? Math.cbrt(t) : (24389.0 / 27.0 * t + 16) / 116.0;
    }

    /** Euclidean CIE76 distance. */
    public static double deltaE(int a, int b) {
        double[] p = lab(a), q = lab(b);
        double dl = p[0] - q[0], da = p[1] - q[1], db = p[2] - q[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    public static int nearest(int rgb, int[] palette) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            double d = deltaE(rgb, palette[i]);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Multiplies a greyscale ARGB pixel by a tint, as the client's tintindex does. */
    public static int multiply(int argb, int tint) {
        int a = argb >>> 24;
        int r = ((argb >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
        int g = ((argb >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
        int b = (argb & 0xFF) * (tint & 0xFF) / 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Source-over alpha compositing of {@code top} onto {@code bottom} (both ARGB). */
    public static int over(int top, int bottom) {
        int ta = top >>> 24;
        if (ta == 255) return top;
        if (ta == 0) return bottom;
        int ba = bottom >>> 24;
        double a = ta / 255.0, b = ba / 255.0 * (1 - a);
        double oa = a + b;
        int r = (int) Math.round((((top >> 16) & 0xFF) * a + ((bottom >> 16) & 0xFF) * b) / oa);
        int g = (int) Math.round((((top >> 8) & 0xFF) * a + ((bottom >> 8) & 0xFF) * b) / oa);
        int bl = (int) Math.round(((top & 0xFF) * a + (bottom & 0xFF) * b) / oa);
        return ((int) Math.round(oa * 255) << 24) | (r << 16) | (g << 8) | bl;
    }

    /** Mean of packed colours (per channel). */
    public static int average(int[] colors, int n) {
        long r = 0, g = 0, b = 0;
        for (int i = 0; i < n; i++) {
            r += (colors[i] >> 16) & 0xFF;
            g += (colors[i] >> 8) & 0xFF;
            b += colors[i] & 0xFF;
        }
        return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    public static String hex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }
}
