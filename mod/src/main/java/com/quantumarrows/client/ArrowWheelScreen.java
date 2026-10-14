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
    private static final int HIGHLIGHT_RADIUS = 13;

    private static final int COLOR_DIM = 0x40000000;
    private static final int COLOR_RING = 0xA0101018;
    private static final int COLOR_DIVIDER = 0x60FFFFFF;
    private static final int COLOR_HIGHLIGHT = 0xC0E0B040;
    private static final int COLOR_POINTER = 0xFFE0B040;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SUBTEXT = 0xFFB0B0B0;

    private final List<ArrowInventory.ArrowType> types;
    private final int targetSlot;
    private final int radius;
    private int hovered = -1;
    private boolean finished;

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

        fillRing(graphics, cx, cy, radius - RING_HALF_WIDTH, radius + RING_HALF_WIDTH, COLOR_RING);
        if (count > 1) {
            for (int i = 0; i < count; i++) {
                double a = (i + 0.5) * step;
                for (int r = radius - RING_HALF_WIDTH + 2; r <= radius + RING_HALF_WIDTH - 2; r++) {
                    int px = cx + (int) Math.round(Math.sin(a) * r);
                    int py = cy - (int) Math.round(Math.cos(a) * r);
                    graphics.fill(px, py, px + 1, py + 1, COLOR_DIVIDER);
                }
            }
        }

        if (hovered >= 0) {
            double a = hovered * step;
            int hx = cx + (int) Math.round(Math.sin(a) * radius);
            int hy = cy - (int) Math.round(Math.cos(a) * radius);
            fillDisc(graphics, hx, hy, HIGHLIGHT_RADIUS, COLOR_HIGHLIGHT);
            for (int r = DEAD_ZONE / 2; r < radius - RING_HALF_WIDTH; r += 3) {
                int px = cx + (int) Math.round(Math.sin(a) * r);
                int py = cy - (int) Math.round(Math.cos(a) * r);
                graphics.fill(px - 1, py - 1, px + 1, py + 1, COLOR_POINTER);
            }
        }

        for (int i = 0; i < count; i++) {
            ArrowInventory.ArrowType type = types.get(i);
            double a = i * step;
            int ix = cx + (int) Math.round(Math.sin(a) * radius) - 8;
            int iy = cy - (int) Math.round(Math.cos(a) * radius) - 8;
            graphics.item(type.icon(), ix, iy);
            graphics.itemDecorations(font, type.icon(), ix, iy, String.valueOf(type.total()));
        }

        // Text goes above the ring so it never overlaps the hotbar/health bar.
        int textY = Math.max(4, cy - radius - RING_HALF_WIDTH - 6 - font.lineHeight * 2 - 3);
        if (hovered >= 0) {
            ArrowInventory.ArrowType type = types.get(hovered);
            graphics.centeredText(font, type.icon().getHoverName(), cx, textY, COLOR_TEXT);
        } else {
            graphics.centeredText(font, Component.translatable("quantumarrows.wheel.hint"), cx, textY, COLOR_TEXT);
        }
        graphics.centeredText(font,
                Component.translatable("quantumarrows.wheel.target", ArrowInventory.describeSlot(targetSlot)),
                cx, textY + font.lineHeight + 3, COLOR_SUBTEXT);
    }

    private static void fillDisc(GuiGraphicsExtractor graphics, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.sqrt(r * r - dy * dy);
            graphics.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    private static void fillRing(GuiGraphicsExtractor graphics, int cx, int cy, int inner, int outer, int color) {
        for (int dy = -outer; dy <= outer; dy++) {
            int xo = (int) Math.sqrt(outer * outer - dy * dy);
            int y = cy + dy;
            if (Math.abs(dy) >= inner) {
                graphics.fill(cx - xo, y, cx + xo + 1, y + 1, color);
            } else {
                int xi = (int) Math.sqrt(inner * inner - dy * dy);
                graphics.fill(cx - xo, y, cx - xi, y + 1, color);
                graphics.fill(cx + xi + 1, y, cx + xo + 1, y + 1, color);
            }
        }
    }
}
