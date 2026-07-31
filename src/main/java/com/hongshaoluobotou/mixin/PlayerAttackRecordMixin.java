package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingSummons;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerAttackRecordMixin {
	// Records the entity the player just attacked, so the End Ring's summoned mobs can prefer it as a
	// target (priority 2 of the four-step ladder). Player.attack runs only after a successful swing,
	// so the recorded target reflects the player's *intended* target, not just any nearby entity. We
	// only record for ServerPlayer since summons are a server-side concept.
	@Inject(method = "attack", at = @At("HEAD"))
	private void endring$recordAttackTarget(Entity target, CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (!(self instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (target instanceof LivingEntity living) {
			EndRingSummons.recordPlayerAttack(serverPlayer, living);
		}
	}
}
