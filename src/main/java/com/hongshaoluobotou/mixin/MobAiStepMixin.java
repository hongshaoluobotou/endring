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
 * uses its own navigation, its own attack goal and its own AI quirks. The mixin forces two
 * things every five ticks:
 *
 * <ol>
 *   <li>The mob's {@code target} is set to the highest-priority End Ring target (damage source
 *       → player attack target → owner's last-hurt-by → nearest same-type of those). Calls into
 *       {@code Mob.setTarget} which is itself mixin-guarded, so a forbidden target (the ring
 *       wearer or a sibling summon) is rejected.</li>
 *   <li>If the mob is more than 12 blocks from its owner, the mob is registered with the
 *       {@link com.hongshaoluobotou.EndRingAiScheduler} so its pathfind can be batched together
 *       with neighbouring summons. Within 12 blocks the vanilla goal selector (FollowOwnerGoal +
 *       melee / charge goals) is already moving the mob without an explicit A* call, so we skip
 *       scheduling entirely and let the existing AI handle the close-range drift.</li>
 * </ol>
 *
 * <p>Refreshes the {@link MobTargetingMixin#endring$refreshOwnerCache() owner cache} once every
 * five ticks, ahead of the targeting decision so the cache is in sync with the new target. The
 * cache is what lets the per-tick {@code canAttack} / {@code setTarget} hot path run in O(1)
 * without re-decoding NBT.
 *
 * <p>Because the check keys off the NBT owner marker, behaviour is restored automatically after
 * server restart, chunk reload, and {@code Mob.convertTo} (the marker survives all three via
 * CUSTOM_DATA). No "reinstall" pass is needed.
 */
@Mixin(Mob.class)
public abstract class MobAiStepMixin {
	/** Per-mob throttle so we don't recompute / re-set the target every single tick. */
	@Unique
	private int endring$aiTickCounter;

	@Inject(method = "aiStep", at = @At("HEAD"))
	private void endring$driveEndRingAi(CallbackInfo ci) {
		Mob mob = (Mob) (Object) this;
		if (!EndRingOwnedComponent.hasOwner(mob)) {
			return;
		}
		if (++this.endring$aiTickCounter < 5) {
			return;
		}
		this.endring$aiTickCounter = 0;

		// Refresh the targeting mixin's cached owner marker so the canAttack / setTarget hot path
		// (which is hit dozens of times per tick by vanilla goals) can read the boolean instead of
		// decoding NBT. We're on the same @Mixin target (Mob) as MobTargetingMixin, so the @Unique
		// field is shared by reference identity and a direct cast is safe.
		((MobTargetingMixin) (Object) mob).endring$refreshOwnerCache();

		LivingEntity owner = EndRingOwnedComponent.getOwner(mob);
		if (owner == null || !owner.isAlive()) {
			return;
		}

		LivingEntity priority = EndRingSummons.resolveTargetFor(mob, owner);
		// Tactical retreat: if the mob is below 30% HP and currently engaging a target, drop
		// the engagement and let it wander back toward the owner. Without this the swarm tends
		// to throw wounded summons at the same target until they die, which both wastes damage
		// and drains the player's resources. The threshold (30%) is high enough to actually
		// save the mob before it gets oneshot, but low enough that a still-healthy mob is not
		// pulled out of combat prematurely.
		if (priority != null
			&& mob.getHealth() < mob.getMaxHealth() * 0.3F
			&& mob.getHealth() > 0.0F) {
			mob.setTarget(null);
		} else {
			mob.setTarget(priority);
		}

		// Below the 12-block threshold the vanilla FollowOwnerGoal / melee goals are already
		// pathfinding (or moving via VexMoveControl); the extra navigator.moveTo call here would
		// be pure overhead. Past 12 blocks we hand the mob to the AI scheduler, which groups
		// nearby summons into spatial buckets so a single lead mob's pathfind result is shared by
		// every group member.
		if (mob.distanceToSqr(owner) >= 12.0 * 12.0) {
			EndRingSummons.scheduleFollow(mob, owner);
		}
	}
}
