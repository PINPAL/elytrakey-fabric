package de.liquiddev.elytrakey;

import de.liquiddev.elytrakey.options.ConfigLoader;
import de.liquiddev.elytrakey.ui.ElytraKeyOptions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult.Type;
import org.lwjgl.glfw.GLFW;

import static net.minecraft.text.Text.literal;

public class ElytraKey implements ModInitializer {

    public static boolean AUTO_EQUIP_FALL = true;
    public static boolean AUTO_EQUIP_FIREWORKS = false;
    public static boolean AUTO_UNEQUIP = true;
    public static boolean EASY_TAKEOFF = true;
    public static boolean DOUBLE_JUMP_EQUIP = true;

    private static ElytraKey instance;
    private MinecraftClient mc = MinecraftClient.getInstance();

    private static KeyBinding swapElytraKeyBinding;
    private static KeyBinding elytraOptionsKeyBinding;

    public static ElytraKey getInstance() {
        return instance;
    }

    private boolean wasAutoEquipped = false;

    private boolean jumpPreviouslyPressed = false;
    private boolean jumpPreviouslyReleased = false;

    @Override
    public void onInitialize() {
        instance = this;
        new ConfigLoader().loadConfig();
        swapElytraKeyBinding = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("Swap Elytra", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.misc"));
        elytraOptionsKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding("ElytraKey Options",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.misc"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            while (swapElytraKeyBinding.wasPressed()) {
                swapElytra();
            }

            while (elytraOptionsKeyBinding.wasPressed()) {
                mc.setScreen(new ElytraKeyOptions());
            }

            if (mc.player == null || mc.player.isCreative() || mc.player.isSpectator()) {
                return;
            }

            boolean fireworksInMainHand = mc.player.getInventory().getMainHandStack()
                    .getItem() == Items.FIREWORK_ROCKET;
            boolean fireworksInOffHand = mc.player.getInventory().getStack(40).getItem() == Items.FIREWORK_ROCKET;
            boolean isFalling = !mc.player.isOnGround() && mc.player.getVelocity().getY() < -0.65;

            if ((AUTO_EQUIP_FIREWORKS && fireworksInMainHand) || (AUTO_EQUIP_FALL && isFalling)) {
                boolean elytraEquipped = isElytraEquipped();
                if (!elytraEquipped) {
                    equipElytra();
                    wasAutoEquipped = true;
                }
            } else {
                boolean unEquip = AUTO_UNEQUIP && wasAutoEquipped && mc.player.isOnGround();
                if (unEquip && isElytraEquipped()) {
                    wasAutoEquipped = false;
                    equipChestplate();
                }
            }

            // Equip elytra if player jumps in mid-air
            if (DOUBLE_JUMP_EQUIP) {
                updateDoubleJumpEquip();
            }

            // Equip elytra, start gliding and boost with fireworks when right-clicking with
            // a firework
            if (EASY_TAKEOFF && (fireworksInMainHand || fireworksInOffHand)) {
                updateEasyTakeoff(fireworksInMainHand ? Hand.MAIN_HAND : Hand.OFF_HAND);
            }
        });

        System.out.println("ElytraKey mod initialized!");

    }

    private void updateDoubleJumpEquip() {
        // Reset flags when the player is on the ground
        if (mc.player.isOnGround()) {
            jumpPreviouslyPressed = false;
            jumpPreviouslyReleased = false;
            return;
        }
        // If we haven't detected jump key release yet, keep checking
        if (!jumpPreviouslyReleased) {
            // Detect key down
            if (mc.options.jumpKey.isPressed()) {
                jumpPreviouslyPressed = true;
            }
            // Detect key release
            if (!mc.options.jumpKey.isPressed() && jumpPreviouslyPressed) {
                jumpPreviouslyReleased = true;
            }
        } else if (mc.options.jumpKey.isPressed()) {
            if (!isElytraEquipped()) {
                // Equip Elytra
                wasAutoEquipped = equipElytra();
                // Start gliding with Elytra
                startGliding();
            }
        }
    }

    private void updateEasyTakeoff(Hand fireworkHand) {
        if (!mc.player.isGliding() && mc.options.useKey.isPressed()) {
            // Clicked with fireworks in air?
            if ((mc.crosshairTarget instanceof BlockHitResult)
                    && ((BlockHitResult) mc.crosshairTarget).getType() == Type.MISS) {

                // Elytra already equipped?
                if (!isElytraEquipped()) {
                    // Equip Elytra
                    if (!equipElytra()) {
                        return;
                    }
                    wasAutoEquipped = true;
                }

                // Jump if on ground
                if (mc.player.isOnGround()) {
                    mc.player.jump();
                }

                // Start gliding with Elytra
                startGliding();

                // Boost with firework
                mc.interactionManager.interactItem(mc.player, fireworkHand);
                mc.player.swingHand(Hand.MAIN_HAND);
            }
        }
    }

    public boolean isElytraEquipped() {
        ItemStack chestPlate = mc.player.getInventory().getArmorStack(2);
        return chestPlate.getItem() == Items.ELYTRA;
    }

    public boolean equipElytra() {
        ItemStack chest = mc.player.getInventory().getArmorStack(2);

        if (chest.getItem() != Items.ELYTRA) {
            int elytraSlot = searchItem(Items.ELYTRA);

            if (elytraSlot == -1) {
                return false;
            }

            if (elytraSlot < 9) {
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, 6, elytraSlot,
                        SlotActionType.SWAP,
                        mc.player);
            } else {
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, elytraSlot, 0,
                        SlotActionType.PICKUP,
                        mc.player);

                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, 6, 0, SlotActionType.PICKUP,
                        mc.player);

                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, elytraSlot, 0,
                        SlotActionType.PICKUP,
                        mc.player);
            }
        }
        return true;
    }

    public boolean equipChestplate() {
        int chestSlot = findChestplate();

        if (chestSlot == -1) {
            return false;
        }

        if (chestSlot < 9) {
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, 6, chestSlot, SlotActionType.SWAP,
                    mc.player);
        } else {
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, chestSlot, 0, SlotActionType.PICKUP,
                    mc.player);

            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, 6, 0, SlotActionType.PICKUP,
                    mc.player);

            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, chestSlot, 0, SlotActionType.PICKUP,
                    mc.player);
        }

        return true;
    }

    private void swapElytra() {
        if (isElytraEquipped()) {
            boolean equipped = equipChestplate();

            // No chestplate found?
            if (!equipped) {
                int emptySlot = mc.player.getInventory().getEmptySlot();

                if (emptySlot < 0) {
                    print("Inventory full!");
                } else {
                    mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, 6, emptySlot,
                            SlotActionType.SWAP, mc.player);
                }
            }
        } else {
            boolean equipped = equipElytra();

            if (!equipped) {
                print("No Elytra found in main inventory.");
            }
        }
    }

    private void startGliding() {
        // Update Server Side
        mc.getNetworkHandler().sendPacket(
                new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
    }

    private int findChestplate() {
        // Netherite chestplate
        int chestSlot = searchItem(Items.NETHERITE_CHESTPLATE);
        if (chestSlot != -1) {
            return chestSlot;
        }

        // Diamond chestplate
        chestSlot = searchItem(Items.DIAMOND_CHESTPLATE);
        if (chestSlot != -1) {
            return chestSlot;
        }

        // Iron chestplate
        chestSlot = searchItem(Items.IRON_CHESTPLATE);
        if (chestSlot != -1) {
            return chestSlot;
        }

        return -1;
    }

    private int searchItem(Item item) {
        DefaultedList<ItemStack> container = mc.player.getInventory().main;
        for (int i = 0; i < container.size(); i++) {
            if (container.get(i).getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    public void print(String message) {
        mc.player.sendMessage(literal(message), false);
    }
}
