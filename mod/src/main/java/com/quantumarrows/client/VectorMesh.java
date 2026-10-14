package com.quantumarrows.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

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
                        if (n + 4 > out.length) {
                            out = Arrays.copyOf(out, out.length * 2);
                        }
                        out[n++] = runStart;
                        out[n++] = x;
                        out[n++] = y;
                        out[n++] = runAlpha;
                    }
                    runStart = x;
                    runAlpha = alpha;
                }
            }
            if (runAlpha > 0) {
                if (n + 4 > out.length) {
                    out = Arrays.copyOf(out, out.length * 2);
                }
                out[n++] = runStart;
                out[n++] = xMax + 1;
                out[n++] = y;
                out[n++] = runAlpha;
            }
        }
        return new VectorMesh(out, n);
    }

    /** Draws the mesh centered at ({@code cx}, {@code cy}) in physical pixels; the pose must already be unscaled. */
    void draw(GuiGraphicsExtractor graphics, int cx, int cy, int argb) {
        int baseAlpha = argb >>> 24;
        int rgb = argb & 0xFFFFFF;
        for (int i = 0; i < size; i += 4) {
            int alpha = runs[i + 3] * baseAlpha / 255;
            int y = cy + runs[i + 2];
            graphics.fill(cx + runs[i], y, cx + runs[i + 1], y + 1, alpha << 24 | rgb);
        }
    }

    // --- Distance functions -------------------------------------------------------------

    /** Ring of radius {@code radius} and half thickness {@code halfWidth}. */
    static double ring(double x, double y, double radius, double halfWidth) {
        return Math.abs(Math.sqrt(x * x + y * y) - radius) - halfWidth;
    }

    static double disc(double x, double y, double cx, double cy, double radius) {
        double dx = x - cx;
        double dy = y - cy;
        return Math.sqrt(dx * dx + dy * dy) - radius;
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
