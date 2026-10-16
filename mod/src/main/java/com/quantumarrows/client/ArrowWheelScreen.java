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
    private static final int RING_HALF_WIDTH = WheelArt.RING_HALF_WIDTH;

    private static final int COUNT_MAX_WIDTH = 16;
    private static final int CURRENT_ITEM_BOTTOM_OFFSET = 68;

    private static final int COLOR_DIM = 0x40000000;
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
        WheelArt.Variant art = WheelArt.acquire(new WheelArt.Key(scale, segments, radius, paged ? NEXT_SEGMENT : -1));
        graphics.pose().pushMatrix();
        graphics.pose().scale(1.0f / scale);
        art.draw(graphics, cx * scale, cy * scale, highlight ? hoveredSegment : -1);
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
}
