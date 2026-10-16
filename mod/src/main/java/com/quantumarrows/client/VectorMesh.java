package com.quantumarrows.client;

import java.util.Arrays;

/**
 * An anti-aliased shape rasterized from a signed distance function at the window's native
 * pixel resolution, so it stays smooth at any GUI scale. Stored as horizontal runs of
 * equal coverage relative to the shape's center.
 */
final class VectorMesh {
    /** Signed distance in physical pixels from the center: negative inside, positive outside. */
    interface Sdf {
        double at(double x, double y);
    }

    private static final int STRIDE = 4;

    // Packed runs: x0, x1 (exclusive), y, coverage (0-255).
    private final int[] runs;
    private final int size;

    private VectorMesh(int[] runs, int size) {
        this.runs = runs;
        this.size = size;
    }

    /**
     * Samples {@code sdf} at every pixel center whose distance from the origin lies in
     * [{@code innerRadius} - 1, {@code outerRadius} + 1]; pixels outside that band are skipped.
     */
    static VectorMesh rasterize(Sdf sdf, double innerRadius, double outerRadius) {
        int[] out = new int[256];
        int n = 0;
        int extent = (int) Math.ceil(outerRadius) + 1;
        double skip = innerRadius - 1;
        for (int y = -extent; y < extent; y++) {
            double py = y + 0.5;
            double outerSq = (outerRadius + 1) * (outerRadius + 1) - py * py;
            if (outerSq <= 0) {
                continue;
            }
            int xMax = (int) Math.ceil(Math.sqrt(outerSq));
            double innerSq = skip > 0 ? skip * skip - py * py : -1;
            int holeHalf = innerSq > 0 ? (int) Math.floor(Math.sqrt(innerSq)) : -1;
            int runStart = 0;
            int runAlpha = 0;
            for (int x = -xMax; x <= xMax; x++) {
                int alpha;
                if (holeHalf > 0 && x >= -holeHalf && x < holeHalf - 1) {
                    alpha = 0;
                } else {
                    double coverage = 0.5 - sdf.at(x + 0.5, py);
                    alpha = coverage <= 0 ? 0 : coverage >= 1 ? 255 : (int) (coverage * 255);
                }
                if (alpha != runAlpha || x == -xMax) {
                    if (runAlpha > 0 && x > runStart) {
                        out = append(out, n, runStart, x, y, runAlpha);
                        n += STRIDE;
                    }
                    runStart = x;
                    runAlpha = alpha;
                }
            }
            if (runAlpha > 0) {
                out = append(out, n, runStart, xMax + 1, y, runAlpha);
                n += STRIDE;
            }
        }
        return new VectorMesh(out, n);
    }

    private static int[] append(int[] out, int n, int x0, int x1, int y, int alpha) {
        if (n + STRIDE > out.length) {
            out = Arrays.copyOf(out, out.length * 2);
        }
        out[n] = x0;
        out[n + 1] = x1;
        out[n + 2] = y;
        out[n + 3] = alpha;
        return out;
    }

    /** Pixel bounds {@code {minX, minY, maxX, maxY}} (max exclusive) relative to the center; all zero if empty. */
    int[] bounds() {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int i = 0; i < size; i += STRIDE) {
            minX = Math.min(minX, runs[i]);
            maxX = Math.max(maxX, runs[i + 1]);
            minY = Math.min(minY, runs[i + 2]);
            maxY = Math.max(maxY, runs[i + 2] + 1);
        }
        return size == 0 ? new int[4] : new int[]{minX, minY, maxX, maxY};
    }

    /**
     * Paints the mesh in {@code argb} over {@code pixels} (ARGB, {@code width} wide) whose top-left pixel is
     * at ({@code originX}, {@code originY}) relative to the mesh center.
     */
    void blendInto(int[] pixels, int width, int originX, int originY, int argb) {
        int colorAlpha = argb >>> 24;
        int rgb = argb & 0xFFFFFF;
        for (int i = 0; i < size; i += STRIDE) {
            int alpha = runs[i + 3] * colorAlpha / 255;
            if (alpha == 0) {
                continue;
            }
            int src = alpha << 24 | rgb;
            int row = (runs[i + 2] - originY) * width - originX;
            for (int x = runs[i]; x < runs[i + 1]; x++) {
                pixels[row + x] = over(pixels[row + x], src);
            }
        }
    }

    /** Source-over blending of non-premultiplied ARGB colors. */
    private static int over(int dst, int src) {
        int sa = src >>> 24;
        if (sa == 255 || dst >>> 24 == 0) {
            return src;
        }
        int da = dst >>> 24;
        int outA = sa + da * (255 - sa) / 255;
        int result = outA << 24;
        for (int shift = 0; shift <= 16; shift += 8) {
            int sc = src >>> shift & 0xFF;
            int dc = dst >>> shift & 0xFF;
            result |= (sc * sa + dc * da * (255 - sa) / 255) / outA << shift;
        }
        return result;
    }

    // --- Distance functions -------------------------------------------------------------

    /** Ring of radius {@code radius} and half thickness {@code halfWidth}. */
    static double ring(double x, double y, double radius, double halfWidth) {
        return Math.abs(Math.sqrt(x * x + y * y) - radius) - halfWidth;
    }

    /**
     * Wedge with its apex at the origin, centered on {@code angle} (clockwise from straight up)
     * and spanning {@code halfAngle} to each side. Exact near the edges, which is all coverage needs.
     */
    static double wedge(double x, double y, double angle, double halfAngle) {
        double r = Math.sqrt(x * x + y * y);
        double delta = Math.atan2(x, -y) - angle;
        delta = Math.abs(Math.IEEEremainder(delta, Math.PI * 2));
        double over = Math.clamp(delta - halfAngle, -Math.PI / 2, Math.PI / 2);
        return r * Math.sin(over);
    }

    /** Line segment from ({@code ax}, {@code ay}) to ({@code bx}, {@code by}), {@code halfWidth} thick. */
    static double segment(double x, double y, double ax, double ay, double bx, double by, double halfWidth) {
        double vx = bx - ax;
        double vy = by - ay;
        double t = Math.clamp(((x - ax) * vx + (y - ay) * vy) / (vx * vx + vy * vy), 0, 1);
        double ex = x - ax - vx * t;
        double ey = y - ay - vy * t;
        return Math.sqrt(ex * ex + ey * ey) - halfWidth;
    }

    /** Line segment along {@code angle} from radius {@code r0} to {@code r1}, {@code halfWidth} thick. */
    static double radialSegment(double x, double y, double angle, double r0, double r1, double halfWidth) {
        double dx = Math.sin(angle);
        double dy = -Math.cos(angle);
        double t = Math.clamp(x * dx + y * dy, r0, r1);
        double ex = x - dx * t;
        double ey = y - dy * t;
        return Math.sqrt(ex * ex + ey * ey) - halfWidth;
    }
}
