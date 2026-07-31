package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import com.hongshaoluobotou.EndRingOwnedComponent;
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
	//
	// End Ring summons have an owner marker in NBT (EndRingOwnedComponent). They are allies of the
	// ring wearer and so the ring-wearer-passive restriction does not apply to their targeting.
	@Inject(method = "canAttack", at = @At("HEAD"), cancellable = true)
	private void endring$stayNeutral(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
		if (!(target instanceof Player player) || EndRingItem.getWorn(player).isEmpty()) {
			return;
		}
		Mob self = (Mob) (Object) this;
		if (EndRingOwnedComponent.hasOwner(self)) {
			return;
		}
		if (self.getLastHurtByMob() != player) {
			cir.setReturnValue(false);
		}
	}
}
