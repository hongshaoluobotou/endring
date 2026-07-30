package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
public abstract class MobTargetMixin {
	// A ring wearer is left neutral: mobs cannot attack (and therefore cannot acquire/keep as a target)
	// the player until the player has actually hurt this specific mob. canAttack is the central gate used
	// by TargetingConditions, asValidTarget and brain validation, so blocking it here covers both
	// goal-based and brain-based AI. Provocation is per-mob, so hitting one zombie does not aggro the
	// whole horde.
	@Inject(method = "canAttack", at = @At("HEAD"), cancellable = true)
	private void endring$stayNeutral(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
		if (!(target instanceof Player player) || EndRingItem.getWorn(player).isEmpty()) {
			return;
		}
		Mob self = (Mob) (Object) this;
		if (self.getLastHurtByMob() != player) {
			cir.setReturnValue(false);
		}
	}
}
