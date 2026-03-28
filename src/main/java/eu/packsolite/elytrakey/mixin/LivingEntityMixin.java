package eu.packsolite.elytrakey.mixin;

import eu.packsolite.elytrakey.ElytraKey;
import net.minecraft.entity.LivingEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class) public abstract class LivingEntityMixin {
	@Inject(
		method = "canGlide", allow = 1, cancellable = true, at = @At(
			value = "FIELD",
			target = "Lnet/minecraft/entity/EquipmentSlot;VALUES:Ljava/util/List;",
			opcode = Opcodes.GETSTATIC
		)
	)
	private void checkCanGlideInventory(CallbackInfoReturnable<Boolean> cir) {
		// Cancel further canGlide checks if an elytra was equipped
		if (ElytraKey.INSTANCE.doubleJumpEquip()) cir.setReturnValue(true);
	}
}
