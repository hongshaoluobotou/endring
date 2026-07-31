package com.hongshaoluobotou;

import java.util.EnumSet;
import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

/**
 * Target-selection goal for End Ring summons. Runs at the highest priority in the {@code targetSelector}
 * and forces the mob's target to the highest-priority living entity on a four-step ladder:
 *
 * <ol>
 *   <li>Damage source at summon time, then re-recorded each time the owner is hit
 *       ({@link EndRingSummons#recordLastHurtBy}).</li>
 *   <li>The owner's most recent attack target (recorded by
 *       {@link com.hongshaoluobotou.mixin.PlayerAttackRecordMixin}).</li>
 *   <li>The mob that last hurt the owner ({@code owner.getLastHurtByMob()}).</li>
 *   <li>Any living non-player-owned entity of the same {@code EntityType} as 1/2/3.</li>
 * </ol>
 *
 * <p>Implemented as an AI goal (rather than a tick-listener in
 * {@code EndRingSummons}) so it composes with vanilla target goals: this goal's
 * {@link Goal.Flag#TARGET} flag also blocks lower-priority vanilla
 * {@code NearestAttackableTargetGoal}s from re-picking after we've already chosen a target.
 */
public final class EndRingAttackOwnerTargetGoal extends TargetGoal {
	private final TargetingConditions conditions;
	private int recheckCooldown;

	public EndRingAttackOwnerTargetGoal(Mob mob) {
		super(mob, false);
		this.conditions = TargetingConditions.forCombat().range(mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE));
		this.setFlags(EnumSet.of(Goal.Flag.TARGET));
	}

	@Override
	public boolean canUse() {
		LivingEntity owner = EndRingOwnedComponent.getOwner(this.mob);
		if (owner == null) {
			return false;
		}
		// Always re-evaluate; the four priority sources change every tick and we need to react
		// to them without any cooldown. The performance cost is one map lookup + a few isAlive
		// checks, which is cheap.
		LivingEntity target = EndRingSummons.resolveTargetFor(this.mob, owner);
		if (target == null) {
			return false;
		}
		if (this.mob.getTarget() == target) {
			return false;
		}
		return true;
	}

	@Override
	public boolean canContinueToUse() {
		LivingEntity target = this.mob.getTarget();
		return target != null && target.isAlive() && !target.isRemoved();
	}

	@Override
	public void start() {
		LivingEntity owner = EndRingOwnedComponent.getOwner(this.mob);
		if (owner == null) {
			return;
		}
		LivingEntity target = EndRingSummons.resolveTargetFor(this.mob, owner);
		if (target != null) {
			this.mob.setTarget(target);
		}
		super.start();
	}

	@Override
	public void tick() {
		// Re-resolve every tick so the priority ladder reacts to new damage sources / new attack
		// targets. EndRingSummons.resolveTargetFor already filters out the owner and any other
		// owned summons.
		LivingEntity owner = EndRingOwnedComponent.getOwner(this.mob);
		if (owner == null) {
			return;
		}
		LivingEntity target = EndRingSummons.resolveTargetFor(this.mob, owner);
		if (target != null) {
			this.mob.setTarget(target);
		} else {
			LivingEntity current = this.mob.getTarget();
			if (current != null && (!current.isAlive() || current.isRemoved())) {
				this.mob.setTarget(null);
			}
		}
	}
}
