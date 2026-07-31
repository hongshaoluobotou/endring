package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import com.hongshaoluobotou.EndRingOwnedComponent;
import java.util.UUID;
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
 *       owner marker) is not allowed to consider its own owner as a valid attack target while that
 *       owner is still wearing the ring. Every other target passes through unchanged so vanilla
 *       melee / target goals work as before.</li>
 *   <li>{@code setTarget} - the backstop. Any code path (vanilla melee goal, brain path, third
 *       party mod) that writes a target via {@code Mob.setTarget} flows through here. Clearing
 *       {@code setTarget(null)} is always allowed. Acquiring a forbidden target is cancelled.</li>
 * </ol>
 *
 * <p>Both rules short-circuit on non-owned mobs so the common case is a single NBT read; the
 * owner-UUID lookup on the {@code canAttack} branch is the second-fast path. The hot path is
 * "non-owned mob, vanilla target", which never touches the body of either method.
 *
 * <p><b>Why owner-UUID match, not "any ring wearer":</b> the protected target is the summoner's
 * <i>current</i> owner, identified by UUID match against the NBT marker on the summon. The
 * additional "is the target still wearing the ring right now" check means:
 * <ul>
 *   <li>If the original owner dropped the ring, their mobs no longer recognise them - the mob
 *       will attack the (now-disgraced) former owner like any other mob.</li>
 *   <li>If two players each wear a ring and fight, each player's summons are owned by their own
 *       UUID. A summon owned by player A will <i>not</i> protect B (UUID mismatch) and a summon
 *       owned by B will <i>not</i> protect A - cross-faction combat flows through normally.</li>
 * </ul>
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
		Mob self = (Mob) (Object) this;
		if (!EndRingOwnedComponent.hasOwner(self)) {
			return;
		}
		if (isForbiddenTarget(self, target)) {
			ci.cancel();
		}
	}

	/**
	 * True when {@code target} is one this End Ring summon must not engage:
	 * <ul>
	 *   <li>The summoner's owner (matched by the owner UUID stamped on {@code self}) <i>and</i>
	 *       that owner is still wearing an End Ring right now, OR</li>
	 *   <li>Another mob owned by the same UUID.</li>
	 * </ul>
	 * {@code target == self} is implicitly covered because no mob owns itself.
	 */
	private static boolean isForbiddenTarget(Mob self, LivingEntity target) {
		UUID owner = EndRingOwnedComponent.getOwnerUuid(self);
		if (owner == null) {
			return false;
		}
		if (target instanceof Player player
				&& player.getUUID().equals(owner)
				&& !EndRingItem.getWorn(player).isEmpty()) {
			return true;
		}
		return target instanceof Mob targetMob && EndRingOwnedComponent.isAllyOf(self, targetMob);
	}
}
