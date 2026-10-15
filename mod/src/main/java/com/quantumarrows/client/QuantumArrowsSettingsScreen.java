package com.quantumarrows.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Settings opened from Mod Menu. The wheel key here is the same binding that appears under
 * Options > Controls > Key Binds, so changing it in either place changes both.
 */
public final class QuantumArrowsSettingsScreen extends Screen {
    private static final int WIDGET_WIDTH = 220;
    private static final int WIDGET_HEIGHT = 20;
    private static final int SPACING = 6;

    private final Screen parent;
    private Button keyButton;
    private boolean listening;

    public QuantumArrowsSettingsScreen(Screen parent) {
        super(Component.translatable("quantumarrows.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        QuantumArrowsConfig config = QuantumArrowsClient.config();
        int x = (width - WIDGET_WIDTH) / 2;
        int y = height / 4 + 8;

        addRenderableWidget(CycleButton.builder(
                        (QuantumArrowsConfig.Activation value) -> Component.literal(value.label()), config.activation)
                .withValues(QuantumArrowsConfig.Activation.values())
                .create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, Component.translatable("quantumarrows.settings.activation"),
                        (button, value) -> config.activation = value));

        keyButton = addRenderableWidget(Button.builder(keyLabel(), button -> {
            listening = true;
            keyButton.setMessage(keyLabel());
        }).bounds(x, y + WIDGET_HEIGHT + SPACING, WIDGET_WIDTH, WIDGET_HEIGHT).build());

        addRenderableWidget(CycleButton.onOffBuilder(config.offhandBow)
                .create(x, y + (WIDGET_HEIGHT + SPACING) * 2, WIDGET_WIDTH, WIDGET_HEIGHT,
                        Component.translatable("quantumarrows.settings.offhand"),
                        (button, value) -> config.offhandBow = value));

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(x, height - 52, WIDGET_WIDTH, WIDGET_HEIGHT).build());
    }

    private Component keyLabel() {
        Component key = QuantumArrowsClient.WHEEL_KEY.getTranslatedKeyMessage();
        if (listening) {
            key = Component.literal("> ").append(key.copy().withStyle(ChatFormatting.YELLOW)).append(" <");
        }
        return Component.translatable("quantumarrows.settings.key", key);
    }

    private void bind(InputConstants.Key key) {
        QuantumArrowsClient.WHEEL_KEY.setKey(key);
        KeyMapping.resetMapping();
        minecraft.options.save();
        listening = false;
        keyButton.setMessage(keyLabel());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (listening) {
            // Like vanilla: Escape clears the binding.
            bind(event.isEscape() ? InputConstants.UNKNOWN : InputConstants.getKey(event));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (listening) {
            bind(InputConstants.Type.MOUSE.getOrCreate(event.button()));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, height / 4 - 12, 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable("quantumarrows.settings.activation.hint"),
                width / 2, height / 4 + 8 + (WIDGET_HEIGHT + SPACING) * 3 + 4, 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        QuantumArrowsClient.config().save();
        minecraft.options.save();
        minecraft.gui.setScreen(parent);
    }
}
