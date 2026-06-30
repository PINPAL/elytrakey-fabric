package eu.packsolite.elytrakey.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import eu.packsolite.elytrakey.ElytraKey;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.FireworkRocketItem;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FireworkRocketItem.class)
public abstract class FireworkRocketItemMixin {
	@Inject(method = "use", at = @At("HEAD"))
	private void use(World world, PlayerEntity user, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
		if (user.isGliding()) return;
		ElytraKey.INSTANCE.updateEasyTakeoff();
	}
}
