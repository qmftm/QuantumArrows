package com.quantumarrows.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Radial arrow picker. Opened while the pick-block (middle mouse) button is held;
 * releasing it picks the arrow type in the direction the mouse moved from the center.
 *
 * <p>Fewer than {@value #PAGED_MIN} types get one segment each, in a fixed order. With more,
 * types are shown in inventory order, {@value #PAGE_SIZE} per page, and the segment on the
 * right is a "next page" arrow that flips the page when the cursor enters it.
 */
public final class ArrowWheelScreen extends Screen {
    private static final int PAGED_MIN = 8;
    private static final int PAGE_SIZE = 7;
    /** Segment index of the "next page" arrow when paged: straight right on an 8-segment wheel. */
    private static final int NEXT_SEGMENT = 2;

    private static final int DEAD_ZONE = 14;
    private static final int RING_HALF_WIDTH = 15;

    // Shape metrics in GUI units; meshes are rasterized at the native pixel resolution.
    private static final double HIGHLIGHT_INSET = 1.5;
    private static final double HIGHLIGHT_GAP = 1.0;
    private static final double DIVIDER_HALF_WIDTH = 0.35;
    private static final double BORDER_HALF_WIDTH = 0.3;
    private static final double POINTER_LENGTH = 7.0;
    private static final double POINTER_HALF_BASE = 2.4;
    /** Gap between the pointer tip and the inner edge of the highlighted segment. */
    private static final double POINTER_GAP = 1.5;
    private static final double CHEVRON_SIZE = 4.0;
    private static final double CHEVRON_HALF_WIDTH = 0.9;
    private static final int COUNT_MAX_WIDTH = 16;
    private static final int CURRENT_ITEM_BOTTOM_OFFSET = 68;

    private static final int COLOR_DIM = 0x40000000;
    private static final int COLOR_RING = 0xA0101018;
    private static final int COLOR_BORDER = 0x50FFFFFF;
    private static final int COLOR_DIVIDER = 0x50FFFFFF;
    private static final int COLOR_HIGHLIGHT = 0xD060D8D8;
    private static final int COLOR_POINTER = 0xFF7AE6E0;
    private static final int COLOR_CHEVRON = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SUBTEXT = 0xFFB0B0B0;
    private static final int COLOR_WARNING = 0xFFFF7060;

    private final List<ArrowInventory.ArrowType> types;
    private final Predicate<ItemStack> ammo;
    private final int targetSlot;
    private final boolean[] blocked;
    private final boolean paged;
    private final int pageCount;
    private final int segments;
    private final int radius;

    private int page;
    private int hoveredSegment = -1;
    private boolean finished;

    private int meshScale;
    private VectorMesh ringMesh;
    private VectorMesh borderMesh;
    private VectorMesh dividerMesh;
    private VectorMesh chevronMesh;
    private VectorMesh[] highlightMeshes;
    private VectorMesh[] pointerMeshes;

    public ArrowWheelScreen(LocalPlayer player, List<ArrowInventory.ArrowType> types, Predicate<ItemStack> ammo,
                            int targetSlot) {
        super(Component.translatable("quantumarrows.wheel.title"));
        this.paged = types.size() >= PAGED_MIN;
        if (paged) {
            this.types = types;
        } else {
            // Fixed order so each type keeps its spot on the wheel after stacks move around.
            List<ArrowInventory.ArrowType> sorted = new ArrayList<>(types);
            sorted.sort(Comparator.comparingInt((ArrowInventory.ArrowType type) -> BuiltInRegistries.ITEM.getId(type.icon().getItem()))
                    .thenComparing(type -> type.icon().getHoverName().getString()));
            this.types = sorted;
        }
        this.ammo = ammo;
        this.targetSlot = targetSlot;
        this.blocked = new boolean[this.types.size()];
        for (int i = 0; i < this.types.size(); i++) {
            blocked[i] = ArrowInventory.plan(player, ammo, this.types.get(i), targetSlot).blocked();
        }
        this.pageCount = paged ? (types.size() + PAGE_SIZE - 1) / PAGE_SIZE : 1;
        this.segments = paged ? PAGE_SIZE + 1 : types.size();
        this.radius = Math.clamp(segments * 7L, 42, 90);
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
        if (QuantumArrowsClient.WHEEL_KEY.matchesMouse(event)) {
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
        if (QuantumArrowsClient.WHEEL_KEY.matches(event)) {
            finish();
            return true;
        }
        return super.keyReleased(event);
    }

    private boolean isTriggerHeld() {
        InputConstants.Key key = InputConstants.getKey(QuantumArrowsClient.WHEEL_KEY.saveString());
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
        int segment = segmentAt((int) minecraft.mouseHandler.getScaledXPos(window),
                (int) minecraft.mouseHandler.getScaledYPos(window));
        int index = typeIndex(segment);
        onClose();
        if (index >= 0) {
            ArrowInventory.select(minecraft, ammo, types.get(index), targetSlot);
        }
    }

    /** Segment under the cursor, or -1 inside the dead zone. */
    private int segmentAt(int mouseX, int mouseY) {
        double dx = mouseX - width / 2.0;
        double dy = mouseY - height / 2.0;
        if (dx * dx + dy * dy < DEAD_ZONE * DEAD_ZONE) {
            return -1;
        }
        // Clockwise angle from straight up, in [0, 2pi).
        double angle = Math.atan2(dx, -dy);
        if (angle < 0) {
            angle += Math.PI * 2;
        }
        return (int) Math.round(angle / (Math.PI * 2 / segments)) % segments;
    }

    /** Type shown in a segment on the current page, or -1 for none, the dead zone or the page arrow. */
    private int typeIndex(int segment) {
        if (segment < 0) {
            return -1;
        }
        if (!paged) {
            return segment;
        }
        if (segment == NEXT_SEGMENT) {
            return -1;
        }
        int index = page * PAGE_SIZE + (segment < NEXT_SEGMENT ? segment : segment - 1);
        return index < types.size() ? index : -1;
    }

    private void updateHover(int mouseX, int mouseY) {
        int segment = segmentAt(mouseX, mouseY);
        // Flip once per entry into the arrow segment; leaving and re-entering flips again.
        if (paged && segment == NEXT_SEGMENT && hoveredSegment != NEXT_SEGMENT) {
            page = (page + 1) % pageCount;
        }
        hoveredSegment = segment;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, COLOR_DIM);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        updateHover(mouseX, mouseY);
        int cx = width / 2;
        int cy = height / 2;
        double step = Math.PI * 2 / segments;
        int hoveredType = typeIndex(hoveredSegment);
        boolean highlight = hoveredType >= 0 || (paged && hoveredSegment == NEXT_SEGMENT);

        int scale = minecraft.getWindow().getGuiScale();
        if (scale != meshScale) {
            buildMeshes(scale);
        }
        int pcx = cx * scale;
        int pcy = cy * scale;
        graphics.pose().pushMatrix();
        graphics.pose().scale(1.0f / scale);
        ringMesh.draw(graphics, pcx, pcy, COLOR_RING);
        if (highlight) {
            highlightMesh(hoveredSegment).draw(graphics, pcx, pcy, COLOR_HIGHLIGHT);
            pointerMesh(hoveredSegment).draw(graphics, pcx, pcy, COLOR_POINTER);
        }
        if (dividerMesh != null) {
            dividerMesh.draw(graphics, pcx, pcy, COLOR_DIVIDER);
        }
        if (chevronMesh != null) {
            chevronMesh.draw(graphics, pcx, pcy, COLOR_CHEVRON);
        }
        borderMesh.draw(graphics, pcx, pcy, COLOR_BORDER);
        graphics.pose().popMatrix();

        for (int segment = 0; segment < segments; segment++) {
            int index = typeIndex(segment);
            if (index < 0) {
                continue;
            }
            ArrowInventory.ArrowType type = types.get(index);
            double a = segment * step;
            graphics.pose().pushMatrix();
            graphics.pose().translate((float) (cx + Math.sin(a) * radius - 8), (float) (cy - Math.cos(a) * radius - 8));
            graphics.item(type.icon(), 0, 0);
            drawCount(graphics, formatCount(type.total()));
            graphics.pose().popMatrix();
        }

        if (paged) {
            graphics.centeredText(font, (page + 1) + "/" + pageCount, cx, cy + 7, COLOR_SUBTEXT);
        }

        extractCurrentItem(graphics, cx);

        // Text goes above the ring so it never overlaps the hotbar/health bar.
        int textY = Math.max(4, cy - radius - RING_HALF_WIDTH - 6 - font.lineHeight * 2 - 3);
        if (hoveredType >= 0) {
            graphics.centeredText(font, types.get(hoveredType).icon().getHoverName(), cx, textY, COLOR_TEXT);
            if (blocked[hoveredType]) {
                graphics.centeredText(font, Component.translatable("quantumarrows.wheel.blocked"),
                        cx, textY + font.lineHeight + 3, COLOR_WARNING);
            }
        } else {
            graphics.centeredText(font, Component.translatable("quantumarrows.wheel.hint"), cx, textY, COLOR_TEXT);
        }
    }

    /** Item the selection will replace, drawn where vanilla's action-bar message sits (just above the hotbar). */
    private void extractCurrentItem(GuiGraphicsExtractor graphics, int cx) {
        ItemStack current = minecraft.player.getInventory().getItem(targetSlot);
        if (current.isEmpty()) {
            return;
        }
        Component label = current.getCount() > 1
                ? Component.empty().append(current.getHoverName()).append(" x" + current.getCount())
                : current.getHoverName();
        int labelWidth = font.width(label);
        int left = cx - (16 + 4 + labelWidth) / 2;
        int y = height - CURRENT_ITEM_BOTTOM_OFFSET;
        graphics.item(current, left, y - 4);
        graphics.text(font, label, left + 20, y, COLOR_TEXT, true);
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
        double step = Math.PI * 2 / segments;

        ringMesh = VectorMesh.rasterize((x, y) -> VectorMesh.ring(x, y, r, w), r - w, r + w);
        double bw = Math.max(0.6, BORDER_HALF_WIDTH * scale);
        borderMesh = VectorMesh.rasterize(
                (x, y) -> Math.abs(VectorMesh.ring(x, y, r, w - bw)) - bw, r - w, r + w);
        if (segments > 1) {
            double dw = Math.max(0.6, DIVIDER_HALF_WIDTH * scale);
            double r0 = r - w + 2 * scale;
            double r1 = r + w - 2 * scale;
            dividerMesh = VectorMesh.rasterize((x, y) -> {
                double best = Double.MAX_VALUE;
                for (int i = 0; i < segments; i++) {
                    best = Math.min(best, VectorMesh.radialSegment(x, y, (i + 0.5) * step, r0, r1, dw));
                }
                return best;
            }, r0, r1);
        } else {
            dividerMesh = null;
        }
        if (paged) {
            // ">" centered on the right-hand segment.
            double a = NEXT_SEGMENT * step;
            double px = Math.sin(a) * r;
            double py = -Math.cos(a) * r;
            double size = CHEVRON_SIZE * scale;
            double hw = CHEVRON_HALF_WIDTH * scale;
            chevronMesh = VectorMesh.rasterize((x, y) -> Math.min(
                    VectorMesh.segment(x, y, px - size / 2, py - size, px + size / 2, py, hw),
                    VectorMesh.segment(x, y, px + size / 2, py, px - size / 2, py + size, hw)), r - w, r + w);
        } else {
            chevronMesh = null;
        }
        highlightMeshes = new VectorMesh[segments];
        pointerMeshes = new VectorMesh[segments];
    }

    private VectorMesh highlightMesh(int segment) {
        if (highlightMeshes[segment] == null) {
            int scale = meshScale;
            double r = radius * scale;
            double w = (RING_HALF_WIDTH - HIGHLIGHT_INSET) * scale;
            double step = Math.PI * 2 / segments;
            double angle = segment * step;
            double gap = HIGHLIGHT_GAP * scale;
            highlightMeshes[segment] = VectorMesh.rasterize(segments == 1
                    ? (x, y) -> VectorMesh.ring(x, y, r, w)
                    : (x, y) -> Math.max(VectorMesh.ring(x, y, r, w), VectorMesh.wedge(x, y, angle, step / 2) + gap),
                    r - w, r + w);
        }
        return highlightMeshes[segment];
    }

    /** Triangle inside the ring whose tip points at the highlighted segment. */
    private VectorMesh pointerMesh(int segment) {
        if (pointerMeshes[segment] == null) {
            int scale = meshScale;
            double angle = segment * Math.PI * 2 / segments;
            double tip = (radius - RING_HALF_WIDTH - POINTER_GAP) * scale;
            double length = POINTER_LENGTH * scale;
            double halfBase = POINTER_HALF_BASE * scale;
            pointerMeshes[segment] = VectorMesh.rasterize(
                    (x, y) -> VectorMesh.triangle(x, y, angle, tip, length, halfBase), tip - length - 1, tip + 1);
        }
        return pointerMeshes[segment];
    }
}
