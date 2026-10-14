package com.quantumarrows.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Predicate;

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
        Predicate<ItemStack> ammo = ArrowInventory.ammoOf(weapon);
        List<ArrowInventory.ArrowType> types = ArrowInventory.collect(minecraft.player, ammo);
        if (types.isEmpty()) {
            minecraft.player.sendOverlayMessage(Component.translatable("quantumarrows.message.no_arrows"));
            return;
        }
        minecraft.gui.setScreen(new ArrowWheelScreen(minecraft.player, types, ammo, config.targetSlot));
    }
}
