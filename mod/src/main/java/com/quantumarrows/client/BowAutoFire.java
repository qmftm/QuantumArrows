package com.quantumarrows.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;

/**
 * With a bow in the main hand, the left mouse button controls the draw stage instead of attacking:
 * a short click moves to the next stage, and holding it shoots again and again at the chosen stage.
 *
 * <p>Each stage releases the arrow after a fixed draw time, from the shortest possible shot to a full draw.
 */
public final class BowAutoFire {
    /**
     * Draw time of each stage in ticks. Stage 1 is the shortest draw that still shoots (an arrow needs at
     * least 10% power, which a bow reaches after 3 ticks); the last stage is the full 20-tick draw.
     */
    private static final int[] STAGE_TICKS = {3, 10, 15, BowItem.MAX_DRAW_DURATION};
    public static final int STAGES = STAGE_TICKS.length;

    /**
     * Ticks the button must stay down before it counts as "held" instead of a click. This is the only wait
     * before the first shot; the pause between later shots is {@link #RESTART_DELAY}.
     */
    private static final int HOLD_TICKS = 3;
    private static final int RESTART_DELAY = 3;

    private static int leftHeldTicks;
    private static boolean autoMode;
    /** A draw started by this class that has not been released yet. */
    private static boolean drawing;
    /** Ticks to wait after a shot before drawing again. */
    private static int cooldown;

    private BowAutoFire() {
    }

    /** The stage's draw time as a percentage of a full draw. */
    public static int percent(int stage) {
        return stageTicks(stage) * 100 / BowItem.MAX_DRAW_DURATION;
    }

    private static int stageTicks(int stage) {
        return STAGE_TICKS[stage - 1];
    }

    private static void reset(Minecraft minecraft) {
        if (drawing) {
            minecraft.options.keyUse.setDown(false);
        }
        leftHeldTicks = 0;
        autoMode = false;
        drawing = false;
        cooldown = 0;
    }

    /** Runs at the start of every client tick, before vanilla handles the key bindings. */
    public static void tick(Minecraft minecraft, QuantumArrowsConfig config) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.gui.screen() != null || !config.autoFireBow
                || !(player.getMainHandItem().getItem() instanceof BowItem)) {
            reset(minecraft);
            return;
        }

        boolean leftDown = minecraft.mouseHandler.isLeftPressed();
        // Presses are queued by the key mapping, so a click shorter than one tick is still seen.
        boolean pressed = false;
        while (minecraft.options.keyAttack.consumeClick()) {
            pressed = true;
        }
        if (leftDown || leftHeldTicks > 0) {
            // The bow's attack would otherwise hit or mine while the button is down.
            minecraft.options.keyAttack.setDown(false);
        }

        if (leftDown) {
            if (leftHeldTicks == 0) {
                // A new press: the first shot never waits for the pause that follows a previous shot.
                cooldown = 0;
            }
            leftHeldTicks++;
            if (leftHeldTicks > HOLD_TICKS) {
                autoMode = true;
            }
        } else if (leftHeldTicks > 0) {
            if (!autoMode) {
                nextStage(player, config);
            }
            autoMode = false;
            leftHeldTicks = 0;
        } else if (pressed) {
            // Pressed and released between two ticks.
            nextStage(player, config);
        }

        if (drawing) {
            if (!player.isUsingItem()) {
                drawing = false;
                minecraft.options.keyUse.setDown(false);
            } else if (player.getTicksUsingItem() >= stageTicks(config.fireStage)) {
                // Vanilla releases a used item as soon as the use key is up, which fires the arrow.
                minecraft.options.keyUse.setDown(false);
                drawing = false;
                cooldown = RESTART_DELAY;
            } else {
                // Keep vanilla from releasing the draw early.
                minecraft.options.keyUse.setDown(true);
            }
        } else if (cooldown > 0) {
            cooldown--;
        } else if (autoMode && !player.isUsingItem() && canShoot(player)) {
            minecraft.options.keyUse.setDown(true);
            minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            drawing = player.isUsingItem();
            if (!drawing) {
                minecraft.options.keyUse.setDown(false);
            }
        }
    }

    private static boolean canShoot(LocalPlayer player) {
        ItemStack bow = player.getMainHandItem();
        return player.hasInfiniteMaterials() || !player.getProjectile(bow).isEmpty();
    }

    private static void nextStage(LocalPlayer player, QuantumArrowsConfig config) {
        config.fireStage = config.fireStage % STAGES + 1;
        config.save();
        player.sendOverlayMessage(Component.translatable("quantumarrows.message.stage",
                config.fireStage, STAGES, percent(config.fireStage)));
    }
}
