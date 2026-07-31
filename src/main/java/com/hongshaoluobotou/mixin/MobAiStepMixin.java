package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingOwnedComponent;
import com.hongshaoluobotou.EndRingSummons;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives End Ring summon AI directly from the entity's AI tick. Each mob subtype (zombie, killer
 * rabbit, vex) keeps its vanilla goal selector - we don't try to rewrite it - so the mob still
 * uses its own navigation, its own attack goal and its own AI quirks. The mixin simply forces
 * two things every five ticks:
 *
 * <ol>
 *   <li>The mob's {@code target} is set to the highest-priority End Ring target (damage source
 *       → player attack target → owner's last-hurt-by → nearest same-type of those). Calls into
 *       {@code Mob.setTarget} which is itself mixin-guarded, so a forbidden target (the ring
 *       wearer or a sibling summon) is rejected.</li>
 *   <li>If the mob is more than 12 blocks from its owner, the navigation is asked to walk back
 *       (matches the vanilla {@code FollowOwnerGoal} start distance).</li>
 * </ol>
 *
 * <p>Because the check keys off the NBT owner marker, behaviour is restored automatically after
 * server restart, chunk reload, and {@code Mob.convertTo} (the marker survives all three via
 * CUSTOM_DATA). No "reinstall" pass is needed.
 */
@Mixin(LivingEntity.class)
public abstract class MobAiStepMixin {
	/** Per-mob throttle so we don't recompute / re-set the target every single tick. */
	@Unique
	private int endring$aiTickCounter;

	@Inject(method = "aiStep", at = @At("HEAD"))
	private void endring$driveEndRingAi(CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!(self instanceof Mob mob)) {
			return;
		}
		if (!EndRingOwnedComponent.hasOwner(mob)) {
			return;
		}
		if (++endring$aiTickCounter < 5) {
			return;
		}
		endring$aiTickCounter = 0;

		LivingEntity owner = EndRingOwnedComponent.getOwner(mob);
		if (owner == null || !owner.isAlive()) {
			return;
		}

		LivingEntity priority = EndRingSummons.resolveTargetFor(mob, owner);
		mob.setTarget(priority);

		if (mob.distanceToSqr(owner) >= 144.0) {
			mob.getNavigation().moveTo(owner, 1.0);
		}
	}
}
