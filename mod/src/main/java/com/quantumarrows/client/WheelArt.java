package com.quantumarrows.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The wheel's ring, dividers and highlights, rasterized once on a background thread into textures so a
 * frame costs a few blits instead of thousands of tiny fills.
 *
 * <p>The render thread only uploads finished images. Until an image is ready its layer is simply not drawn,
 * so opening the wheel never waits for the rasterizing.
 */
final class WheelArt {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumarrows");

    static final int RING_HALF_WIDTH = 15;

    // Shape metrics in GUI units; meshes are rasterized at the native pixel resolution.
    private static final double HIGHLIGHT_INSET = 1.5;
    private static final double HIGHLIGHT_GAP = 1.0;
    private static final double DIVIDER_HALF_WIDTH = 0.35;
    private static final double BORDER_HALF_WIDTH = 0.3;
    private static final double CHEVRON_SIZE = 4.0;
    private static final double CHEVRON_HALF_WIDTH = 0.9;

    private static final int COLOR_RING = 0xA0101018;
    private static final int COLOR_BORDER = 0x50FFFFFF;
    private static final int COLOR_DIVIDER = 0x50FFFFFF;
    private static final int COLOR_HIGHLIGHT = 0x7060D8D8;
    private static final int COLOR_CHEVRON = 0xFFFFFFFF;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "QuantumArrows-WheelArt");
        thread.setDaemon(true);
        return thread;
    });

    private static Variant current;
    private static int nextId;

    private WheelArt() {
    }

    /**
     * @param chevronSegment segment that shows the "next page" arrow, or -1
     */
    record Key(int scale, int segments, int radius, int chevronSegment) {
    }

    /** ARGB pixels of an image whose top-left is ({@code originX}, {@code originY}) from the wheel center. */
    private record Baked(int originX, int originY, int width, int height, int[] argb) {
    }

    private record Tex(Identifier id, Baked baked) {
        void draw(GuiGraphicsExtractor graphics, int centerX, int centerY) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, id, centerX + baked.originX, centerY + baked.originY,
                    0f, 0f, baked.width, baked.height, baked.width, baked.height);
        }
    }

    /** The art for one wheel layout. Replaced (and its textures freed) when the layout changes. */
    static final class Variant {
        private final Key key;
        private volatile Baked ring;
        private volatile Baked top;
        private final AtomicReferenceArray<Baked> highlights;
        private Tex ringTex;
        private Tex topTex;
        private final Tex[] highlightTex;

        private Variant(Key key) {
            this.key = key;
            this.highlights = new AtomicReferenceArray<>(key.segments);
            this.highlightTex = new Tex[key.segments];
            WORKER.execute(() -> run(() -> {
                ring = bakeRing(key);
                top = bakeTop(key);
            }));
            for (int segment = 0; segment < key.segments; segment++) {
                int s = segment;
                WORKER.execute(() -> run(() -> highlights.set(s, bakeHighlight(key, s))));
            }
        }

        private static void run(Runnable task) {
            try {
                task.run();
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to build the arrow wheel graphics", e);
            }
        }

        /** Draws the layers under the hovered segment's highlight (or all of them when nothing is hovered). */
        void draw(GuiGraphicsExtractor graphics, int centerX, int centerY, int hoveredSegment) {
            Minecraft minecraft = Minecraft.getInstance();
            if (ringTex == null && ring != null) {
                ringTex = upload(minecraft, ring);
            }
            if (ringTex != null) {
                ringTex.draw(graphics, centerX, centerY);
            }
            if (hoveredSegment >= 0 && hoveredSegment < key.segments) {
                Baked baked = highlights.get(hoveredSegment);
                if (highlightTex[hoveredSegment] == null && baked != null) {
                    highlightTex[hoveredSegment] = upload(minecraft, baked);
                }
                if (highlightTex[hoveredSegment] != null) {
                    highlightTex[hoveredSegment].draw(graphics, centerX, centerY);
                }
            }
            if (topTex == null && top != null) {
                topTex = upload(minecraft, top);
            }
            if (topTex != null) {
                topTex.draw(graphics, centerX, centerY);
            }
        }

        private void release(Minecraft minecraft) {
            releaseTex(minecraft, ringTex);
            releaseTex(minecraft, topTex);
            for (Tex tex : highlightTex) {
                releaseTex(minecraft, tex);
            }
        }
    }

    /** The art for {@code key}, started in the background if it is not the current layout. */
    static Variant acquire(Key key) {
        if (current == null || !current.key.equals(key)) {
            if (current != null) {
                current.release(Minecraft.getInstance());
            }
            current = new Variant(key);
        }
        return current;
    }

    private static Tex upload(Minecraft minecraft, Baked baked) {
        NativeImage image = new NativeImage(baked.width, baked.height, true);
        for (int y = 0; y < baked.height; y++) {
            for (int x = 0; x < baked.width; x++) {
                image.setPixel(x, y, baked.argb[y * baked.width + x]);
            }
        }
        Identifier id = Identifier.fromNamespaceAndPath("quantumarrows", "wheel/" + nextId++);
        minecraft.getTextureManager().register(id, new DynamicTexture(() -> id.toString(), image));
        return new Tex(id, baked);
    }

    private static void releaseTex(Minecraft minecraft, Tex tex) {
        if (tex != null) {
            minecraft.getTextureManager().release(tex.id);
        }
    }

    // --- Rasterizing (runs on the worker thread) ---------------------------------------------

    private static Baked blank(Key key) {
        int extent = (int) Math.ceil((key.radius + RING_HALF_WIDTH) * (double) key.scale) + 2;
        return new Baked(-extent, -extent, extent * 2, extent * 2, new int[extent * 2 * extent * 2]);
    }

    private static Baked bakeRing(Key key) {
        double r = key.radius * (double) key.scale;
        double w = RING_HALF_WIDTH * (double) key.scale;
        Baked baked = blank(key);
        VectorMesh.rasterize((x, y) -> VectorMesh.ring(x, y, r, w), r - w, r + w)
                .blendInto(baked.argb, baked.width, baked.originX, baked.originY, COLOR_RING);
        return baked;
    }

    /** Dividers, the page arrow and the outline, which sit above the highlight. */
    private static Baked bakeTop(Key key) {
        int scale = key.scale;
        double r = key.radius * (double) scale;
        double w = RING_HALF_WIDTH * (double) scale;
        double step = Math.PI * 2 / key.segments;
        Baked baked = blank(key);

        if (key.segments > 1) {
            double dw = Math.max(0.6, DIVIDER_HALF_WIDTH * scale);
            double r0 = r - w + 2 * scale;
            double r1 = r + w - 2 * scale;
            VectorMesh.rasterize((x, y) -> {
                double best = Double.MAX_VALUE;
                for (int i = 0; i < key.segments; i++) {
                    best = Math.min(best, VectorMesh.radialSegment(x, y, (i + 0.5) * step, r0, r1, dw));
                }
                return best;
            }, r0, r1).blendInto(baked.argb, baked.width, baked.originX, baked.originY, COLOR_DIVIDER);
        }
        if (key.chevronSegment >= 0) {
            // ">" centered on the "next page" segment.
            double a = key.chevronSegment * step;
            double px = Math.sin(a) * r;
            double py = -Math.cos(a) * r;
            double size = CHEVRON_SIZE * scale;
            double hw = CHEVRON_HALF_WIDTH * scale;
            VectorMesh.rasterize((x, y) -> Math.min(
                            VectorMesh.segment(x, y, px - size / 2, py - size, px + size / 2, py, hw),
                            VectorMesh.segment(x, y, px + size / 2, py, px - size / 2, py + size, hw)), r - w, r + w)
                    .blendInto(baked.argb, baked.width, baked.originX, baked.originY, COLOR_CHEVRON);
        }
        double bw = Math.max(0.6, BORDER_HALF_WIDTH * scale);
        VectorMesh.rasterize((x, y) -> Math.abs(VectorMesh.ring(x, y, r, w - bw)) - bw, r - w, r + w)
                .blendInto(baked.argb, baked.width, baked.originX, baked.originY, COLOR_BORDER);
        return baked;
    }

    /** The highlighted segment, cropped to its own bounds. */
    private static Baked bakeHighlight(Key key, int segment) {
        int scale = key.scale;
        double r = key.radius * (double) scale;
        double w = (RING_HALF_WIDTH - HIGHLIGHT_INSET) * scale;
        double step = Math.PI * 2 / key.segments;
        double angle = segment * step;
        double gap = HIGHLIGHT_GAP * scale;
        VectorMesh mesh = VectorMesh.rasterize(key.segments == 1
                ? (x, y) -> VectorMesh.ring(x, y, r, w)
                : (x, y) -> Math.max(VectorMesh.ring(x, y, r, w), VectorMesh.wedge(x, y, angle, step / 2) + gap),
                r - w, r + w);
        int[] bounds = mesh.bounds();
        int width = Math.max(1, bounds[2] - bounds[0]);
        int height = Math.max(1, bounds[3] - bounds[1]);
        int[] pixels = new int[width * height];
        mesh.blendInto(pixels, width, bounds[0], bounds[1], COLOR_HIGHLIGHT);
        return new Baked(bounds[0], bounds[1], width, height, pixels);
    }
}
