package eu.packsolite.elytrakey;

import eu.packsolite.elytrakey.options.ConfigLoader;
import eu.packsolite.elytrakey.ui.ElytraKeyOptions;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

public class ElytraKey implements ClientModInitializer {

	public static ElytraKey INSTANCE;

	public static boolean AUTO_EQUIP_FALL = true;
	public static boolean AUTO_EQUIP_FIREWORKS = false;
	public static boolean AUTO_UNEQUIP = true;
	public static boolean EASY_TAKEOFF = true;
	public static double AUTO_EQUIP_FALL_VELOCITY;
	public static boolean DOUBLE_JUMP_EQUIP = true;

	private ClientPlayerEntity player;
	private ClientPlayNetworkHandler network;
	private ClientPlayerInteractionManager interactionManager;

	private static KeyBinding swapElytraKeyBinding;
	private static KeyBinding elytraOptionsKeyBinding;

	/**
	 * True if elytra was equipped automatically and therefore should be swapped to chestplate upon landing
	 * @since 1.2.4 - renamed from wasAutoEquipped
	 */
	private boolean pending_unequip = false;

	@Override
	public void onInitializeClient() {
		INSTANCE = this;

		new ConfigLoader().loadConfig();
		KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of("elytrakey"));
		swapElytraKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding("Swap Elytra", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, cat));
		elytraOptionsKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding("ElytraKey Options", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, cat));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			this.player = client.player;
			this.network = client.getNetworkHandler();
			this.interactionManager = client.interactionManager;
			if (player == null || network == null || interactionManager == null) {
				return;
			}

			while (swapElytraKeyBinding.wasPressed()) {
				swapElytra();
			}

			while (elytraOptionsKeyBinding.wasPressed()) {
				client.setScreen(new ElytraKeyOptions());
			}

			// Ignore players who have creative flight
			if (player.getAbilities().flying) {
				return;
			}

			boolean fireworksInMainHand = player.getMainHandStack().getItem() == Items.FIREWORK_ROCKET;
			boolean isFalling = !player.isOnGround() && player.getVelocity().getY() < AUTO_EQUIP_FALL_VELOCITY;
			boolean hasLanded = player.isOnGround() || player.isTouchingWater();

			if ((AUTO_EQUIP_FIREWORKS && fireworksInMainHand) || (AUTO_EQUIP_FALL && isFalling)) {
				boolean elytraEquipped = isElytraEquipped();
				if (!elytraEquipped) {
					equipElytra();
					pending_unequip = true;
				}
			} else {
				boolean unEquip = AUTO_UNEQUIP && pending_unequip && hasLanded;
				if (unEquip && isElytraEquipped()) {
					pending_unequip = false;
					equipChestplate();
				}
			}
		});
	}

	/**
	 * Equip elytra if double jump equip is enabled
	 * @return true if {@link #pending_unequip} was set due to elytra being equipped
	 */
	public boolean doubleJumpEquip() {
		if (DOUBLE_JUMP_EQUIP) {
			pending_unequip = equipElytra();
			return pending_unequip;
		}
		return false;
	}

	public void updateEasyTakeoff() {
			if (!EASY_TAKEOFF) return;

			// Exit early if we couldn't find an Elytra to equip
			pending_unequip = equipElytra();
			if (!pending_unequip) return;

			// Client side jump (prevent inconsistent launches due to client thinking it's on ground)
			// TODO: maybe wrap this in isOnGround() check??
			player.jump();
			// Move server player up 0.2 blocks and forcefully set onGround to false (effectively simulating a jump)
			// This allows us to reliably get around waiting 2 ticks for the server to register client jump inputs
			network.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
					player.getX(), player.getY() + 0.2, player.getZ(),
					false, player.horizontalCollision));

			// Start gliding with Elytra
			startGliding();
	}

	/**
	 * Checks if the player is currently wearing an "elytra like" chestplate
	 * @return true if wearing an elytra, false otherwise
	 */
	private boolean isElytraEquipped() {
		return LivingEntity.canGlideWith(player.getEquippedStack(EquipmentSlot.CHEST),EquipmentSlot.CHEST);
	}

	/**
	 * Equips the elytra if it is not already equipped
	 * @return true if the elytra has been auto equipped (or was already equipped), false if no elytra was found
	 */
	private boolean equipElytra() {
		if (!isElytraEquipped()) {
			int elytraSlot = findChestEquipment(true);

			if (elytraSlot == -1) {
				return false;
			}

			if (elytraSlot < 9) {
				interactionManager.clickSlot(player.playerScreenHandler.syncId, 6, elytraSlot, SlotActionType.SWAP, player);
			} else {
				interactionManager.clickSlot(player.playerScreenHandler.syncId, elytraSlot, 0, SlotActionType.PICKUP, player);
				interactionManager.clickSlot(player.playerScreenHandler.syncId, 6, 0, SlotActionType.PICKUP, player);
				interactionManager.clickSlot(player.playerScreenHandler.syncId, elytraSlot, 0, SlotActionType.PICKUP, player);
			}
		}
		return true;
	}

	public boolean equipChestplate() {
		int chestSlot = findChestEquipment(false);

		if (chestSlot == -1) {
			return false;
		}

		if (chestSlot < 9) {
			interactionManager.clickSlot(player.playerScreenHandler.syncId, 6, chestSlot, SlotActionType.SWAP, player);
		} else {
			interactionManager.clickSlot(player.playerScreenHandler.syncId, chestSlot, 0, SlotActionType.PICKUP, player);
			interactionManager.clickSlot(player.playerScreenHandler.syncId, 6, 0, SlotActionType.PICKUP, player);
			interactionManager.clickSlot(player.playerScreenHandler.syncId, chestSlot, 0, SlotActionType.PICKUP, player);
		}
		return true;
	}

	private void swapElytra() {
		if (isElytraEquipped()) {
			boolean equipped = equipChestplate();

			// No chestplate found?
			if (!equipped) {
				int emptySlot = player.getInventory().getEmptySlot();

				if (emptySlot < 0) {
					print("elytrakey.chat.full_inventory");
				} else {
					interactionManager.clickSlot(player.playerScreenHandler.syncId, 6, emptySlot,
							SlotActionType.SWAP, player);
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
		network.sendPacket(new ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
	}

	/**
	 * Searches the player's inventory for the best chestplate or elytra
	 *
	 * @param findElytra If true, search for an elytra and return the first one found
	 * @return The slot index of the best chestplate or elytra, or -1 if none found
	 */
	private int findChestEquipment(boolean findElytra) {
		DefaultedList<ItemStack> container = player.getInventory().getMainStacks();
		HashMap<Integer, ItemStack> potentialChestplates = new HashMap<>();

		for (int i = 0; i < container.size(); i++) {
			ItemStack currentItem = container.get(i);
			/// Check if [net.minecraft.component.type.EquippableComponent] allows the player to equip the chestplate
			if (player.canEquip(currentItem, EquipmentSlot.CHEST)) {
				// If the item is an elytra, and we want an elytra, exit early and return the slot
				if (currentItem.contains(DataComponentTypes.GLIDER)) {
					// TODO: implement predicate for matching (eg: elytra with unbreaking | chestplate algorithm) instead of findElytra jank
					if (findElytra) return i;
				// Otherwise, mark it as a potential chestplate
				} else {
					potentialChestplates.put(i, currentItem);
				}
			}
		}

		if (findElytra) return -1;

		return calculateBestChestplate(potentialChestplates);
	}

	private int calculateBestChestplate(HashMap<Integer, ItemStack> chestplates) {
		if (chestplates.size() == 1) return chestplates.entrySet().iterator().next().getKey();
		return chestplates.entrySet().stream()
           .max(
               Comparator.comparingDouble(entry -> {
                   // Get the attribute modifiers of the chestplate
                   ItemStack stack = entry.getValue();
                   AttributeModifiersComponent attributes = stack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
                   if (attributes == null) return 0.0;
                   // Calculate the armor & toughness value of the chestplate
                   double armor = attributes.applyOperations(EntityAttributes.ARMOR, EntityAttributes.ARMOR.value().getDefaultValue(), EquipmentSlot.CHEST);
				   double toughness = attributes.applyOperations(EntityAttributes.ARMOR_TOUGHNESS, EntityAttributes.ARMOR_TOUGHNESS.value().getDefaultValue(), EquipmentSlot.CHEST);
				   return armor + toughness;
               })
           ).map(Map.Entry::getKey)
           .orElse(-1);
		// % damageReduction = ((min(20, max((armor/5), armor - ((4 * damage) / (min(20, toughness) + 8)))/25)*100
		// damageReduction = (min(20, protectionEnchantLevel))/25
	}

	public void print(String key) {
		player.sendMessage(Text.translatable(key), false);
		System.out.println(key);
	}
}
