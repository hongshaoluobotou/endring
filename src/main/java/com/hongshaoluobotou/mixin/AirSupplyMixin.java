package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public class AirSupplyMixin {
	@org.spongepowered.asm.mixin.injection.Inject(
		method = "decreaseAirSupply",
		at = @At("HEAD"),
		cancellable = true
	)
	private void endring$preventAirLoss(int currentAir, CallbackInfoReturnable<Integer> cir) {
		if ((Object) this instanceof Player player && !EndRingItem.getWorn(player).isEmpty()) {
			cir.setReturnValue(currentAir);
		}
	}
}