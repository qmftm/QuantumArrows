package com.quantumarrows.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;

import java.util.ArrayList;
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

    public static Predicate<ItemStack> ammoOf(ItemStack weapon) {
        return ((ProjectileWeaponItem) weapon.getItem()).getAllSupportedProjectiles();
    }

    /** Arrow types in inventory order (by the first slot holding each type). */
    public static List<ArrowType> collect(LocalPlayer player, Predicate<ItemStack> ammo) {
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
        return types;
    }

    /**
     * What selecting a type will do: move its largest stack into the target (unless the target
     * already holds it), then move other ammo the weapon would use first (offhand and slots
     * before the target) into empty slots after the target.
     *
     * @param source   slot to swap into the target, or -1 if the target already holds the type
     * @param moves    {from, to} pairs for the cleanup, empty when not needed or not possible
     * @param blocked  true when other ammo comes first but there is not enough room behind the target
     */
    public record Plan(int source, List<int[]> moves, boolean blocked) {
        public boolean isNoop() {
            return source < 0 && moves.isEmpty();
        }
    }

    public static Plan plan(LocalPlayer player, Predicate<ItemStack> ammo, ArrowType type, int target) {
        Inventory inventory = player.getInventory();
        ItemStack[] items = new ItemStack[OFFHAND + 1];
        for (int i = 0; i < items.length; i++) {
            items[i] = i < MAIN_SIZE || i == OFFHAND ? inventory.getItem(i) : ItemStack.EMPTY;
        }

        int source = -1;
        if (!ItemStack.isSameItemSameComponents(items[target], type.icon())) {
            int best = 0;
            for (int index : type.slots()) {
                ItemStack stack = items[index];
                if (index != target && ItemStack.isSameItemSameComponents(stack, type.icon()) && stack.getCount() > best) {
                    best = stack.getCount();
                    source = index;
                }
            }
            if (source >= 0) {
                ItemStack swapped = items[target];
                items[target] = items[source];
                items[source] = swapped;
            }
        }

        // Projectile weapons look at the offhand first, then inventory slots in index order.
        List<Integer> blockers = new ArrayList<>();
        if (target != OFFHAND) {
            List<Integer> before = new ArrayList<>();
            before.add(OFFHAND);
            for (int i = 0; i < target; i++) {
                before.add(i);
            }
            for (int index : before) {
                ItemStack stack = items[index];
                if (!stack.isEmpty() && ammo.test(stack) && !ItemStack.isSameItemSameComponents(stack, type.icon())) {
                    blockers.add(index);
                }
            }
        }
        List<Integer> free = new ArrayList<>();
        if (target != OFFHAND) {
            for (int i = target + 1; i < MAIN_SIZE; i++) {
                if (items[i].isEmpty()) {
                    free.add(i);
                }
            }
        }
        if (blockers.size() > free.size()) {
            return new Plan(source, List.of(), true);
        }
        List<int[]> moves = new ArrayList<>(blockers.size());
        for (int i = 0; i < blockers.size(); i++) {
            moves.add(new int[]{blockers.get(i), free.get(i)});
        }
        return new Plan(source, moves, false);
    }

    /** Applies {@link #plan} with simulated inventory clicks, which the server validates like normal clicks. */
    public static void select(Minecraft minecraft, Predicate<ItemStack> ammo, ArrowType type, int target) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.gameMode == null || player.containerMenu != player.inventoryMenu) {
            return;
        }
        Plan plan = plan(player, ammo, type, target);
        if (plan.isNoop()) {
            player.sendOverlayMessage(Component.translatable("quantumarrows.message.already", type.icon().getHoverName()));
            return;
        }
        if (plan.source() >= 0) {
            // Pick up the arrows, drop them on the target (swapping out whatever was there),
            // then put the swapped-out stack back where the arrows came from.
            click(minecraft, player, plan.source());
            click(minecraft, player, target);
            if (!player.inventoryMenu.getCarried().isEmpty()) {
                click(minecraft, player, plan.source());
            }
        }
        for (int[] move : plan.moves()) {
            click(minecraft, player, move[0]);
            click(minecraft, player, move[1]);
        }
    }

    private static void click(Minecraft minecraft, LocalPlayer player, int inventoryIndex) {
        minecraft.gameMode.handleContainerInput(player.inventoryMenu.containerId, toMenuSlot(inventoryIndex), 0,
                ContainerInput.PICKUP, player);
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
