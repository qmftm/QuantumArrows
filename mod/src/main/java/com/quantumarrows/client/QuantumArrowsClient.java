package com.quantumarrows.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class QuantumArrowsClient implements ClientModInitializer {
    private static QuantumArrowsConfig config;

    @Override
    public void onInitializeClient() {
        config = QuantumArrowsConfig.load();
        // Runs at the start of Minecraft.tick(), before handleKeybinds(), so draining the
        // pick-block clicks here keeps vanilla middle-click from firing while a bow is held.
        ClientTickEvents.START_CLIENT_TICK.register(QuantumArrowsClient::onStartTick);
    }

    private static void onStartTick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.gui.screen() != null) {
            return;
        }
        ItemStack weapon = ArrowInventory.heldWeapon(minecraft.player);
        if (weapon == null) {
            return;
        }
        boolean clicked = false;
        while (minecraft.options.keyPickItem.consumeClick()) {
            clicked = true;
        }
        if (!clicked) {
            return;
        }
        List<ArrowInventory.ArrowType> types = ArrowInventory.collect(minecraft.player, weapon);
        if (types.isEmpty()) {
            minecraft.player.sendOverlayMessage(Component.translatable("quantumarrows.message.no_arrows"));
            return;
        }
        minecraft.gui.setScreen(new ArrowWheelScreen(types, config.targetSlot));
    }
}
