package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class KillCommandMixin {
	@Inject(method = "kill", at = @At("HEAD"), cancellable = true)
	private void endring$killAsRandomDamage(ServerLevel level, CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self instanceof ServerPlayer player && !EndRingItem.getWorn(player).isEmpty()) {
			float damage = 6.0F + player.getRandom().nextFloat() * 12.0F;
			player.hurtServer(level, player.damageSources().magic(), damage);
			ci.cancel();
		}
	}
}