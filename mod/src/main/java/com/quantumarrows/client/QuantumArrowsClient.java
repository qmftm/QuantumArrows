package com.quantumarrows.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;

import java.util.List;
import java.util.function.Predicate;

public final class QuantumArrowsClient implements ClientModInitializer {
    /** Shown in Options > Controls > Key Binds and in the mod's own settings screen. */
    public static final KeyMapping WHEEL_KEY = new KeyMapping("key.quantumarrows.wheel", InputConstants.Type.MOUSE,
            InputConstants.MOUSE_BUTTON_MIDDLE,
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("quantumarrows", "main")));

    private static QuantumArrowsConfig config;

    public static QuantumArrowsConfig config() {
        return config;
    }

    @Override
    public void onInitializeClient() {
        config = QuantumArrowsConfig.load();
        KeyMappingHelper.registerKeyMapping(WHEEL_KEY);
        // Runs at the start of Minecraft.tick(), before handleKeybinds(), so draining the
        // pick-block clicks here keeps vanilla middle-click from firing when the wheel opens.
        ClientTickEvents.START_CLIENT_TICK.register(QuantumArrowsClient::onStartTick);
    }

    private static void onStartTick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.gui.screen() != null) {
            return;
        }
        ItemStack weapon = ArrowInventory.heldWeapon(minecraft.player);
        if (weapon == null && config.activation != QuantumArrowsConfig.Activation.EVERYTHING) {
            // Not active: leave the clicks alone so the key keeps its other bindings' behavior.
            return;
        }
        boolean clicked = false;
        while (WHEEL_KEY.consumeClick()) {
            clicked = true;
        }
        if (!clicked) {
            return;
        }
        // The wheel key may share its button with another binding (middle click is also pick block).
        while (minecraft.options.keyPickItem.consumeClick()) {
            // drained
        }
        Predicate<ItemStack> ammo = weapon != null ? ArrowInventory.ammoOf(weapon) : ProjectileWeaponItem.ARROW_ONLY;
        List<ArrowInventory.ArrowType> types = ArrowInventory.collect(minecraft.player, ammo);
        if (types.isEmpty()) {
            minecraft.player.sendOverlayMessage(Component.translatable("quantumarrows.message.no_arrows"));
            return;
        }
        minecraft.gui.setScreen(new ArrowWheelScreen(minecraft.player, types, ammo, config.targetSlot));
    }
}
