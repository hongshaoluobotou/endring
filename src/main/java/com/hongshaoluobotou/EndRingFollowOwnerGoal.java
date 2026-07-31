package com.hongshaoluobotou;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * Wolf-style follow-owner AI for any {@link Mob}. Reimplementation of vanilla
 * {@code FollowOwnerGoal} (which is hardcoded to {@code TamableAnimal}). The owner is read from
 * {@link EndRingOwnedComponent} on every tick so a respawned player is still found. When the mob is
 * more than 12 blocks away the mob teleports next to the owner rather than pathing across unloaded
 * terrain (matching {@code TamableAnimal.tryToTeleportToOwner}).
 */
public final class EndRingFollowOwnerGoal extends Goal {
	private final Mob mob;
	private final double speedModifier;
	private final PathNavigation navigation;
	private final float stopDistance;
	private final float startDistance;
	private int timeToRecalcPath;
	private float oldWaterCost;
	private LivingEntity cachedOwner;

	public EndRingFollowOwnerGoal(Mob mob, double speedModifier, float startDistance, float stopDistance) {
		this.mob = mob;
		this.speedModifier = speedModifier;
		this.navigation = mob.getNavigation();
		this.startDistance = startDistance;
		this.stopDistance = stopDistance;
		this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		LivingEntity owner = EndRingOwnedComponent.getOwner(this.mob);
		if (owner == null || !owner.isAlive() || owner.isRemoved()) {
			return false;
		}
		if (owner.isSpectator()) {
			return false;
		}
		if (this.mob.isPassenger()) {
			return false;
		}
		if (this.mob.getTarget() != null) {
			// Don't trail the owner while a fight is in progress - the attack goal takes over.
			return false;
		}
		if (this.mob.distanceToSqr(owner) < (double) (this.startDistance * this.startDistance)) {
			return false;
		}
		this.cachedOwner = owner;
		return true;
	}

	@Override
	public boolean canContinueToUse() {
		if (this.cachedOwner == null || !this.cachedOwner.isAlive() || this.cachedOwner.isRemoved()) {
			return false;
		}
		if (this.mob.getTarget() != null) {
			return false;
		}
		if (this.navigation.isDone()) {
			return false;
		}
		return this.mob.distanceToSqr(this.cachedOwner) > (double) (this.stopDistance * this.stopDistance);
	}

	@Override
	public void start() {
		this.timeToRecalcPath = 0;
		this.oldWaterCost = this.mob.getPathfindingMalus(PathType.WATER);
		this.mob.setPathfindingMalus(PathType.WATER, 0.0F);
	}

	@Override
	public void stop() {
		this.cachedOwner = null;
		this.navigation.stop();
		this.mob.setPathfindingMalus(PathType.WATER, this.oldWaterCost);
	}

	@Override
	public void tick() {
		LivingEntity owner = EndRingOwnedComponent.getOwner(this.mob);
		if (owner == null) {
			return;
		}
		this.cachedOwner = owner;
		this.mob.getLookControl().setLookAt(owner, 10.0F, (float) this.mob.getMaxHeadXRot());

		if (this.mob.distanceToSqr(owner) >= 144.0) {
			teleportNear(owner);
			return;
		}
		if (--this.timeToRecalcPath <= 0) {
			this.timeToRecalcPath = this.adjustedTickDelay(10);
			this.navigation.moveTo(owner, this.speedModifier);
		}
	}

	private void teleportNear(LivingEntity owner) {
		BlockPos target = owner.blockPosition();
		for (int attempt = 0; attempt < 10; attempt++) {
			int xd = this.mob.getRandom().nextIntBetweenInclusive(-3, 3);
			int zd = this.mob.getRandom().nextIntBetweenInclusive(-3, 3);
			if (Math.abs(xd) >= 2 || Math.abs(zd) >= 2) {
				int yd = this.mob.getRandom().nextIntBetweenInclusive(-1, 1);
				BlockPos dest = new BlockPos(target.getX() + xd, target.getY() + yd, target.getZ() + zd);
				if (this.mob.level() instanceof net.minecraft.server.level.ServerLevel sl) {
					this.mob.teleportTo(sl, dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, java.util.Set.of(), this.mob.getYRot(), this.mob.getXRot(), false);
					return;
				}
			}
		}
	}
}
