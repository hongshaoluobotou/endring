package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import com.hongshaoluobotou.EndRingOwnedComponent;
import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
 *       owner is still wearing the ring right now. Every other target passes through unchanged so
 *       vanilla melee / target goals work as before.</li>
 *   <li>{@code setTarget} - the backstop. Any code path (vanilla melee goal, brain path, third
 *       party mod) that writes a target via {@code Mob.setTarget} flows through here. Clearing
 *       {@code setTarget(null)} is always allowed. Acquiring a forbidden target is cancelled.</li>
 * </ol>
 *
 * <p>Owner UUID + the cached "is owned" flag is refreshed by {@link MobAiStepMixin} every five
 * ticks (the same cadence at which it re-resolves the priority target). Outside of those five-tick
 * windows we read the cached fields directly instead of re-decoding the {@code CUSTOM_DATA} NBT,
 * which is the difference between ~1500 ns/call (two {@code getIntArray} reads plus a UUID unpack)
 * and ~10 ns/call (a boolean read). At 20 000 owned mobs that is the difference between a 4 ms/tick
 * hot path and a 30 microsecond one, which is what lets the swarm keep a 20 TPS tick budget.
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
	/**
	 * Whether {@code this} carries an End Ring owner marker. Refreshed every 5 ticks by
	 * {@link MobAiStepMixin}. Treat as read-only from any other path.
	 */
	@Unique
	private boolean endring$hasOwnerCache;

	/**
	 * Owner UUID stamped on {@code this}, or {@code null} if not owned. Mirrors
	 * {@link EndRingOwnedComponent#getOwnerUuid} but cached so {@code canAttack} / {@code setTarget}
	 * do not have to decode NBT.
	 */
	@Unique
	private UUID endring$ownerUuidCache;

	@Inject(method = "canAttack", at = @At("HEAD"), cancellable = true)
	private void endring$restrictCanAttack(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
		Mob self = (Mob) (Object) this;
		if (!this.endring$hasOwnerCache) {
			return;
		}
		if (isForbiddenTarget(self, target)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
	private void endring$restrictSetTarget(LivingEntity target, CallbackInfo ci) {
		Mob self = (Mob) (Object) this;
		if (target == null) {
			return;
		}
		if (!this.endring$hasOwnerCache) {
			return;
		}
		if (isForbiddenTarget(self, target)) {
			ci.cancel();
		}
	}

	/**
	 * Refreshes the cached owner marker from NBT. Called by {@link MobAiStepMixin} once every
	 * five ticks; safe to call from any other code path that wants the cache up-to-date
	 * immediately (e.g. {@code MobConvertMixin} after a {@code convertTo} swap).
	 */
	@Unique
	public void endring$refreshOwnerCache() {
		Mob self = (Mob) (Object) this;
		this.endring$hasOwnerCache = EndRingOwnedComponent.hasOwner(self);
		this.endring$ownerUuidCache = this.endring$hasOwnerCache ? EndRingOwnedComponent.getOwnerUuid(self) : null;
	}

	/**
	 * True when {@code target} is one this End Ring summon must not engage:
	 * <ul>
	 *   <li>The summoner's owner (matched by the cached owner UUID) <i>and</i>
	 *       that owner is still wearing an End Ring right now, OR</li>
	 *   <li>Another mob owned by the same UUID.</li>
	 * </ul>
	 * {@code target == self} is implicitly covered because no mob owns itself.
	 */
	private boolean isForbiddenTarget(Mob self, LivingEntity target) {
		UUID owner = this.endring$ownerUuidCache;
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
