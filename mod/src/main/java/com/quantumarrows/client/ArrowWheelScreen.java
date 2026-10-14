package com.quantumarrows.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Radial arrow picker. Opened while the pick-block (middle mouse) button is held;
 * releasing it picks the arrow type in the direction the mouse moved from the center.
 */
public final class ArrowWheelScreen extends Screen {
    private static final int DEAD_ZONE = 14;
    private static final int RING_HALF_WIDTH = 15;
    // Shape metrics in GUI units; meshes are rasterized at the native pixel resolution.
    private static final double HIGHLIGHT_INSET = 1.5;
    private static final double HIGHLIGHT_GAP = 1.0;
    private static final double DIVIDER_HALF_WIDTH = 0.35;
    private static final double BORDER_HALF_WIDTH = 0.3;
    private static final double KNOB_RADIUS = 2.5;
    private static final int COUNT_MAX_WIDTH = 16;

    private static final int COLOR_DIM = 0x40000000;
    private static final int COLOR_RING = 0xA0101018;
    private static final int COLOR_BORDER = 0x50FFFFFF;
    private static final int COLOR_DIVIDER = 0x50FFFFFF;
    private static final int COLOR_HIGHLIGHT = 0xC0E0B040;
    private static final int COLOR_KNOB = 0xFFE0B040;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SUBTEXT = 0xFFB0B0B0;

    private final List<ArrowInventory.ArrowType> types;
    private final int targetSlot;
    private final int radius;
    private int hovered = -1;
    private boolean finished;

    private int meshScale;
    private VectorMesh ringMesh;
    private VectorMesh borderMesh;
    private VectorMesh dividerMesh;
    private VectorMesh[] highlightMeshes;
    private VectorMesh[] knobMeshes;

    public ArrowWheelScreen(List<ArrowInventory.ArrowType> types, int targetSlot) {
        super(Component.translatable("quantumarrows.wheel.title"));
        this.types = types;
        this.targetSlot = targetSlot;
        this.radius = Math.clamp(types.size() * 7L, 42, 90);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        // The release can happen before the screen receives mouse events (e.g. a quick tap),
        // so also poll the physical button state.
        if (!isTriggerHeld()) {
            finish();
        }
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (minecraft.options.keyPickItem.matchesMouse(event)) {
            finish();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            finish();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (minecraft.options.keyPickItem.matches(event)) {
            finish();
            return true;
        }
        return super.keyReleased(event);
    }

    private boolean isTriggerHeld() {
        InputConstants.Key key = InputConstants.getKey(minecraft.options.keyPickItem.saveString());
        if (key.getType() == InputConstants.Type.KEYBOARD) {
            return InputConstants.isKeyDown(key.getValue());
        }
        return switch (key.getValue()) {
            case InputConstants.MOUSE_BUTTON_LEFT -> minecraft.mouseHandler.isLeftPressed();
            case InputConstants.MOUSE_BUTTON_MIDDLE -> minecraft.mouseHandler.isMiddlePressed();
            case InputConstants.MOUSE_BUTTON_RIGHT -> minecraft.mouseHandler.isRightPressed();
            // No polling API for extra mouse buttons; rely on mouseReleased.
            default -> true;
        };
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        Window window = minecraft.getWindow();
        updateHovered((int) minecraft.mouseHandler.getScaledXPos(window), (int) minecraft.mouseHandler.getScaledYPos(window));
        onClose();
        if (hovered >= 0) {
            ArrowInventory.moveToSlot(minecraft, types.get(hovered), targetSlot);
        }
    }

    private void updateHovered(int mouseX, int mouseY) {
        double dx = mouseX - width / 2.0;
        double dy = mouseY - height / 2.0;
        if (dx * dx + dy * dy < DEAD_ZONE * DEAD_ZONE) {
            hovered = -1;
            return;
        }
        // Clockwise angle from straight up, in [0, 2pi).
        double angle = Math.atan2(dx, -dy);
        if (angle < 0) {
            angle += Math.PI * 2;
        }
        double step = Math.PI * 2 / types.size();
        hovered = (int) Math.round(angle / step) % types.size();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, COLOR_DIM);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        updateHovered(mouseX, mouseY);
        int cx = width / 2;
        int cy = height / 2;
        int count = types.size();
        double step = Math.PI * 2 / count;

        int scale = minecraft.getWindow().getGuiScale();
        if (scale != meshScale) {
            buildMeshes(scale);
        }
        int pcx = cx * scale;
        int pcy = cy * scale;
        graphics.pose().pushMatrix();
        graphics.pose().scale(1.0f / scale);
        ringMesh.draw(graphics, pcx, pcy, COLOR_RING);
        if (hovered >= 0) {
            highlightMesh(hovered).draw(graphics, pcx, pcy, COLOR_HIGHLIGHT);
            knobMesh(hovered).draw(graphics, pcx, pcy, COLOR_KNOB);
        }
        if (dividerMesh != null) {
            dividerMesh.draw(graphics, pcx, pcy, COLOR_DIVIDER);
        }
        borderMesh.draw(graphics, pcx, pcy, COLOR_BORDER);
        graphics.pose().popMatrix();

        for (int i = 0; i < count; i++) {
            ArrowInventory.ArrowType type = types.get(i);
            double a = i * step;
            graphics.pose().pushMatrix();
            graphics.pose().translate((float) (cx + Math.sin(a) * radius - 8), (float) (cy - Math.cos(a) * radius - 8));
            graphics.item(type.icon(), 0, 0);
            drawCount(graphics, formatCount(type.total()));
            graphics.pose().popMatrix();
        }

        // Text goes above the ring so it never overlaps the hotbar/health bar.
        int textY = Math.max(4, cy - radius - RING_HALF_WIDTH - 6 - font.lineHeight * 2 - 3);
        if (hovered >= 0) {
            ArrowInventory.ArrowType type = types.get(hovered);
            graphics.centeredText(font, type.icon().getHoverName(), cx, textY, COLOR_TEXT);
        } else {
            graphics.centeredText(font, Component.translatable("quantumarrows.wheel.hint"), cx, textY, COLOR_TEXT);
        }
        graphics.centeredText(font, Component.translatable("quantumarrows.wheel.target", targetSlot),
                cx, textY + font.lineHeight + 3, COLOR_SUBTEXT);
    }

    /** Bottom-right count like vanilla's, shrunk when wider than the slot (e.g. "1.2K"). */
    private void drawCount(GuiGraphicsExtractor graphics, String text) {
        int textWidth = font.width(text);
        float textScale = textWidth > COUNT_MAX_WIDTH ? (float) COUNT_MAX_WIDTH / textWidth : 1.0f;
        graphics.pose().pushMatrix();
        graphics.pose().translate(17 - textWidth * textScale, 9 + 8 * (1 - textScale));
        graphics.pose().scale(textScale);
        graphics.text(font, text, 0, 0, COLOR_TEXT, true);
        graphics.pose().popMatrix();
    }

    /** Counts of 1000 or more are shortened to one decimal, e.g. 1234 -> "1.2K" (rounded down). */
    public static String formatCount(int count) {
        if (count < 1000) {
            return String.valueOf(count);
        }
        if (count < 10000) {
            int tenths = count / 100;
            return tenths / 10 + "." + tenths % 10 + "K";
        }
        return count / 1000 + "K";
    }

    private void buildMeshes(int scale) {
        meshScale = scale;
        double r = radius * scale;
        double w = RING_HALF_WIDTH * scale;
        int count = types.size();
        double step = Math.PI * 2 / count;

        ringMesh = VectorMesh.rasterize((x, y) -> VectorMesh.ring(x, y, r, w), r - w, r + w);
        double bw = Math.max(0.6, BORDER_HALF_WIDTH * scale);
        borderMesh = VectorMesh.rasterize(
                (x, y) -> Math.abs(VectorMesh.ring(x, y, r, w - bw)) - bw, r - w, r + w);
        if (count > 1) {
            double dw = Math.max(0.6, DIVIDER_HALF_WIDTH * scale);
            double r0 = r - w + 2 * scale;
            double r1 = r + w - 2 * scale;
            dividerMesh = VectorMesh.rasterize((x, y) -> {
                double best = Double.MAX_VALUE;
                for (int i = 0; i < count; i++) {
                    best = Math.min(best, VectorMesh.radialSegment(x, y, (i + 0.5) * step, r0, r1, dw));
                }
                return best;
            }, r0, r1);
        } else {
            dividerMesh = null;
        }
        highlightMeshes = new VectorMesh[count];
        knobMeshes = new VectorMesh[count];
    }

    private VectorMesh highlightMesh(int index) {
        if (highlightMeshes[index] == null) {
            int scale = meshScale;
            double r = radius * scale;
            double w = (RING_HALF_WIDTH - HIGHLIGHT_INSET) * scale;
            int count = types.size();
            double step = Math.PI * 2 / count;
            double angle = index * step;
            double gap = HIGHLIGHT_GAP * scale;
            highlightMeshes[index] = VectorMesh.rasterize(count == 1
                    ? (x, y) -> VectorMesh.ring(x, y, r, w)
                    : (x, y) -> Math.max(VectorMesh.ring(x, y, r, w), VectorMesh.wedge(x, y, angle, step / 2) + gap),
                    r - w, r + w);
        }
        return highlightMeshes[index];
    }

    private VectorMesh knobMesh(int index) {
        if (knobMeshes[index] == null) {
            int scale = meshScale;
            double angle = index * Math.PI * 2 / types.size();
            double distance = (radius - RING_HALF_WIDTH - KNOB_RADIUS - 2) * scale;
            double kx = Math.sin(angle) * distance;
            double ky = -Math.cos(angle) * distance;
            double kr = KNOB_RADIUS * scale;
            knobMeshes[index] = VectorMesh.rasterize(
                    (x, y) -> VectorMesh.disc(x, y, kx, ky, kr), distance - kr, distance + kr);
        }
        return knobMeshes[index];
    }
}
