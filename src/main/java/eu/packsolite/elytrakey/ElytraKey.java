package eu.packsolite.elytrakey;

import eu.packsolite.elytrakey.options.ConfigLoader;
import eu.packsolite.elytrakey.ui.ElytraKeyOptions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult.Type;

import org.lwjgl.glfw.GLFW;

import java.util.List;

import static net.minecraft.item.Items.*;

public class ElytraKey implements ModInitializer {

	private static final int OFF_HAND_SLOT_ID = 40;
	private static final int CHEST_PLATE_SLOT_ID = EquipmentSlot.CHEST.getOffsetEntitySlotId(36);
	private static final List<Item> CHESTPLATE_PRIORITY = List.of(NETHERITE_CHESTPLATE, DIAMOND_CHESTPLATE, IRON_CHESTPLATE, CHAINMAIL_CHESTPLATE, GOLDEN_CHESTPLATE, COPPER_CHESTPLATE, LEATHER_HELMET);

	public static boolean AUTO_EQUIP_FALL = true;
	public static boolean AUTO_EQUIP_FIREWORKS = false;
	public static boolean AUTO_UNEQUIP = true;
	public static boolean EASY_TAKEOFF = true;
	public static double AUTO_EQUIP_FALL_VELOCITY;
	public static boolean DOUBLE_JUMP_EQUIP = true;

	private MinecraftClient mc = MinecraftClient.getInstance();

	private static KeyBinding swapElytraKeyBinding;
	private static KeyBinding elytraOptionsKeyBinding;

	private boolean wasAutoEquipped = false;
	private boolean jumpPreviouslyPressed = false;
	private boolean jumpPreviouslyReleased = false;

	@Override
	public void onInitialize() {
		new ConfigLoader().loadConfig();
		KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of("elytrakey"));
		swapElytraKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding("Swap Elytra", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, cat));
		elytraOptionsKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding("ElytraKey Options", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, cat));
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

			boolean fireworksInMainHand = mc.player.getInventory().getSelectedStack().getItem() == Items.FIREWORK_ROCKET;
			boolean fireworksInOffHand = mc.player.getInventory().getStack(OFF_HAND_SLOT_ID).getItem() == Items.FIREWORK_ROCKET;
			boolean isFalling = !mc.player.isOnGround() && mc.player.getVelocity().getY() < AUTO_EQUIP_FALL_VELOCITY;
			boolean hasLanded = mc.player.isOnGround() || mc.player.isTouchingWater();

			if ((AUTO_EQUIP_FIREWORKS && fireworksInMainHand) || (AUTO_EQUIP_FALL && isFalling)) {
				boolean elytraEquipped = isElytraEquipped();
				if (!elytraEquipped) {
					equipElytra();
					wasAutoEquipped = true;
				}
			} else {
				boolean unEquip = AUTO_UNEQUIP && wasAutoEquipped && hasLanded;
				if (unEquip && isElytraEquipped()) {
					wasAutoEquipped = false;
					equipChestplate();
				}
			}

			// Skip double jump and easy takeoff if already flying or in water
			if (mc.player.isGliding() || mc.player.isTouchingWater()) {
				return;
			}

			// Equip elytra if player jumps in mid-air while not in water
			if (DOUBLE_JUMP_EQUIP) {
				updateDoubleJumpEquip();
			}
			// Equip elytra, start gliding and boost with fireworks when right-clicking with a firework
			if (EASY_TAKEOFF && (fireworksInMainHand || fireworksInOffHand)) {
				updateEasyTakeoff(fireworksInMainHand ? Hand.MAIN_HAND : Hand.OFF_HAND);
			}
		});
	}

	// TODO: Replace this with mixin to ClientPlayerEntity.tickMovement() on init sendPacket
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
		if (mc.player.isOnGround()
				&& mc.options.useKey.isPressed()
				&& mc.crosshairTarget instanceof BlockHitResult
				&& mc.crosshairTarget.getType() == Type.MISS) {
			// Elytra already equipped?
			if (!isElytraEquipped()) {
				if (!equipElytra()) {
					return;
				}
				wasAutoEquipped = true;
			}

			// Client side jump (prevent inconsistent launches due to client thinking it's on ground)
			mc.player.jump();
			// Move server player up 0.2 blocks and forcefully set onGround to false (effectively simulating a jump)
			// This allows us to reliably get around waiting 2 ticks for the server to register client jump inputs
			mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
					mc.player.getX(), mc.player.getY() + 0.2, mc.player.getZ(),
					false, mc.player.horizontalCollision));

			// Start gliding with Elytra
			startGliding();

			// Send server packet to use firework (let client reconcile)
			mc.getNetworkHandler().sendPacket(
					new PlayerInteractItemC2SPacket(fireworkHand, 0, mc.player.getYaw(), mc.player.getPitch()));
		}
	}

	public boolean isElytraEquipped() {
		ItemStack chestPlate = mc.player.getInventory().getStack(CHEST_PLATE_SLOT_ID);
		return chestPlate.getItem() == Items.ELYTRA;
	}

	public boolean equipElytra() {
		ItemStack chest = mc.player.getInventory().getStack(CHEST_PLATE_SLOT_ID);

		if (chest.getItem() != Items.ELYTRA) {
			int elytraSlot = searchItem(Items.ELYTRA);

			if (elytraSlot == -1) {
				return false;
			}

			if (elytraSlot < 9) {
				mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, 6, elytraSlot, SlotActionType.SWAP, mc.player);
			} else {
				mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, elytraSlot, 0, SlotActionType.PICKUP, mc.player);
				mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, 6, 0, SlotActionType.PICKUP, mc.player);
				mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, elytraSlot, 0, SlotActionType.PICKUP, mc.player);
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
			mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, 6, chestSlot, SlotActionType.SWAP, mc.player);
		} else {
			mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, chestSlot, 0, SlotActionType.PICKUP, mc.player);
			mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, 6, 0, SlotActionType.PICKUP, mc.player);
			mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, chestSlot, 0, SlotActionType.PICKUP, mc.player);
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
					print("elytrakey.chat.full_inventory");
				} else {
					mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, 6, emptySlot,
						SlotActionType.SWAP, mc.player);
				}
			}
		} else {
			boolean equipped = equipElytra();

			if (!equipped) {
				print("elytrakey.chat.no_elytra");
			}
		}
	}

	private void startGliding() {
		// Send server packet to start gliding (let client reconcile)
		mc.getNetworkHandler()
				.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
	}

	private int findChestplate() {
		// Netherite chestplate
		for (var chestplate : CHESTPLATE_PRIORITY) {
			int slot = searchItem(chestplate);
			if (slot != -1) {
				return slot;
			}
		}
		return -1;
	}

	private int searchItem(Item item) {
		DefaultedList<ItemStack> container = mc.player.getInventory().getMainStacks();
		for (int i = 0; i < container.size(); i++) {
			if (container.get(i).getItem() == item) {
				return i;
			}
		}
		return -1;
	}

	public void print(String key) {
		if (mc.player != null) {
			mc.player.sendMessage(Text.translatable(key), false);
		}
	}
}
