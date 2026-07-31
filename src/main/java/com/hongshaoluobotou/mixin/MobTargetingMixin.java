package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import com.hongshaoluobotou.EndRingOwnedComponent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Targeting guards for {@link Mob}. Two complementary rules:
 *
 * <ol>
 *   <li>{@code canAttack} - the central gate used by {@code TargetingConditions},
 *       {@code asValidTarget} and brain validation. An End Ring summon (one whose NBT carries an
 *       owner marker) is not allowed to consider the ring wearer as a valid attack target, nor
 *       another owned summon. Every other target passes through unchanged so vanilla melee / target
 *       goals work as before.</li>
 *   <li>{@code setTarget} - the backstop. Any code path (vanilla melee goal, brain path, third
 *       party mod) that writes a target via {@code Mob.setTarget} flows through here. Clearing
 *       {@code setTarget(null)} is always allowed. Acquiring a forbidden target is cancelled.</li>
 * </ol>
 *
 * <p>Both rules short-circuit on non-owned mobs so the common case is a single NBT read; the
 * ring-wearer check on the {@code canAttack} branch is the second-fast path. The hot path is
 * "non-owned mob, vanilla target", which never touches the body of either method.
 */
@Mixin(Mob.class)
public abstract class MobTargetingMixin {
	@Inject(method = "canAttack", at = @At("HEAD"), cancellable = true)
	private void endring$restrictCanAttack(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
		Mob self = (Mob) (Object) this;
		if (!EndRingOwnedComponent.hasOwner(self)) {
			return;
		}
		if (isForbiddenTarget(self, target)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
	private void endring$restrictSetTarget(LivingEntity target, CallbackInfo ci) {
		if (target == null) {
			return;
		}
		Mob self = (Mob) (Object) this;
		if (!EndRingOwnedComponent.hasOwner(self)) {
			return;
		}
		if (isForbiddenTarget(self, target)) {
			ci.cancel();
		}
	}

	/**
	 * True when {@code target} is one the End Ring summon must not engage: either the ring
	 * wearer (a {@code Player} whose head slot holds a non-empty ring stack) or another mob that
	 * shares the same owner UUID. {@code target == self} is implicitly covered because no mob
	 * owns itself.
	 */
	private static boolean isForbiddenTarget(Mob self, LivingEntity target) {
		if (target instanceof Player player && !EndRingItem.getWorn(player).isEmpty()) {
			return true;
		}
		return target instanceof Mob targetMob && EndRingOwnedComponent.isAllyOf(self, targetMob);
	}
}
