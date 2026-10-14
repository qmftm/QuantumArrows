package com.quantumarrows.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Finds the arrow types a player carries and moves one of them into the target slot. */
public final class ArrowInventory {
    private static final int HOTBAR_SIZE = 9;
    private static final int MAIN_SIZE = 36;
    private static final int OFFHAND = Inventory.SLOT_OFFHAND;

    // InventoryMenu layout: 9-35 main inventory, 36-44 hotbar, 45 offhand.
    private static final int MENU_HOTBAR_START = 36;
    private static final int MENU_OFFHAND = 45;

    private ArrowInventory() {
    }

    /** One distinct arrow type (item + components, so each tipped arrow potion is separate). */
    public record ArrowType(ItemStack icon, int total, List<Integer> slots) {
    }

    public static boolean isValidTargetSlot(int slot) {
        return (slot >= 0 && slot < MAIN_SIZE) || slot == OFFHAND;
    }

    /** The bow/crossbow the player holds, main hand first, or {@code null}. */
    public static ItemStack heldWeapon(LocalPlayer player) {
        if (player.getMainHandItem().getItem() instanceof ProjectileWeaponItem) {
            return player.getMainHandItem();
        }
        if (player.getOffhandItem().getItem() instanceof ProjectileWeaponItem) {
            return player.getOffhandItem();
        }
        return null;
    }

    public static List<ArrowType> collect(LocalPlayer player, ItemStack weapon) {
        Predicate<ItemStack> ammo = ((ProjectileWeaponItem) weapon.getItem()).getAllSupportedProjectiles();
        Inventory inventory = player.getInventory();
        List<ItemStack> icons = new ArrayList<>();
        List<int[]> totals = new ArrayList<>();
        List<List<Integer>> slots = new ArrayList<>();

        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < MAIN_SIZE; i++) {
            indices.add(i);
        }
        indices.add(OFFHAND);

        for (int index : indices) {
            ItemStack stack = inventory.getItem(index);
            if (stack.isEmpty() || !ammo.test(stack)) {
                continue;
            }
            int found = -1;
            for (int t = 0; t < icons.size(); t++) {
                if (ItemStack.isSameItemSameComponents(icons.get(t), stack)) {
                    found = t;
                    break;
                }
            }
            if (found < 0) {
                icons.add(stack.copyWithCount(1));
                totals.add(new int[1]);
                slots.add(new ArrayList<>());
                found = icons.size() - 1;
            }
            totals.get(found)[0] += stack.getCount();
            slots.get(found).add(index);
        }

        List<ArrowType> types = new ArrayList<>(icons.size());
        for (int t = 0; t < icons.size(); t++) {
            types.add(new ArrowType(icons.get(t), totals.get(t)[0], List.copyOf(slots.get(t))));
        }
        // Fixed order so each arrow type keeps its spot on the wheel after stacks move around.
        types.sort(Comparator.comparingInt((ArrowType type) -> BuiltInRegistries.ITEM.getId(type.icon().getItem()))
                .thenComparing(type -> type.icon().getHoverName().getString()));
        return types;
    }

    /** Moves the largest stack of {@code type} into {@code target} by simulating inventory clicks. */
    public static void moveToSlot(Minecraft minecraft, ArrowType type, int target) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.gameMode == null || player.containerMenu != player.inventoryMenu) {
            return;
        }
        Inventory inventory = player.getInventory();
        if (ItemStack.isSameItemSameComponents(inventory.getItem(target), type.icon())) {
            player.sendOverlayMessage(Component.translatable("quantumarrows.message.already", type.icon().getHoverName()));
            return;
        }

        int source = -1;
        int best = 0;
        for (int index : type.slots()) {
            ItemStack stack = inventory.getItem(index);
            if (index != target && ItemStack.isSameItemSameComponents(stack, type.icon()) && stack.getCount() > best) {
                best = stack.getCount();
                source = index;
            }
        }
        if (source < 0) {
            return;
        }

        int containerId = player.inventoryMenu.containerId;
        int sourceSlot = toMenuSlot(source);
        int targetSlot = toMenuSlot(target);
        // Pick up the arrows, drop them on the target (swapping out whatever was there),
        // then put the swapped-out stack back where the arrows came from.
        minecraft.gameMode.handleContainerInput(containerId, sourceSlot, 0, ContainerInput.PICKUP, player);
        minecraft.gameMode.handleContainerInput(containerId, targetSlot, 0, ContainerInput.PICKUP, player);
        if (!player.inventoryMenu.getCarried().isEmpty()) {
            minecraft.gameMode.handleContainerInput(containerId, sourceSlot, 0, ContainerInput.PICKUP, player);
        }
        player.sendOverlayMessage(Component.translatable("quantumarrows.message.moved", type.icon().getHoverName()));
    }

    private static int toMenuSlot(int inventoryIndex) {
        if (inventoryIndex == OFFHAND) {
            return MENU_OFFHAND;
        }
        if (inventoryIndex < HOTBAR_SIZE) {
            return MENU_HOTBAR_START + inventoryIndex;
        }
        return inventoryIndex;
    }
}
