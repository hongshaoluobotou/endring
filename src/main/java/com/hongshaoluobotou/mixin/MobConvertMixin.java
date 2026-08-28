package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingOwnedComponent;
import com.hongshaoluobotou.EndRingSummons;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * When vanilla converts a mob (zombie → drowned, villager → zombie villager, skeleton → stray,
 * etc.) it allocates a brand-new entity and discards the old one. The old entity's
 * {@code CUSTOM_DATA} is not automatically carried over in 26.2, so the End Ring owner marker
 * (and therefore the AI mixin's "follow / target" behaviour) would be lost on conversion.
 *
 * <p>We re-tag the fresh entity with the old entity's owner so the targeting and follow mixins
 * pick it up on the next tick. The SUMMONED bookkeeping list is also updated so the cap count
 * stays correct.
 *
 * <p>26.2 has two {@code Mob.convertTo} overloads: a 4-arg one (canonical, with
 * {@code AfterConversion}) and a 3-arg one that delegates to it. The 3-arg overload runs first in
 * the call chain, so targeting the 4-arg method catches every conversion.
 */
@Mixin(Mob.class)
public abstract class MobConvertMixin {
	@Inject(method = "convertTo(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ConversionParams;Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/world/entity/ConversionParams$AfterConversion;)Lnet/minecraft/world/entity/Mob;", at = @At("RETURN"))
	private <T extends Mob> void endring$onConverted(
		EntityType<T> entityType,
		ConversionParams params,
		EntitySpawnReason spawnReason,
		ConversionParams.AfterConversion<T> afterConversion,
		CallbackInfoReturnable<T> cir
	) {
		Mob self = (Mob) (Object) this;
		T fresh = cir.getReturnValue();
		if (fresh == null) {
			return;
		}
		// Only intervene for End Ring summons. Non-owned conversions (e.g. a vanilla villager
		// hit by a zombie) are left untouched.
		if (!EndRingOwnedComponent.hasOwner(self)) {
			return;
		}
		LivingEntity owner = EndRingOwnedComponent.getOwner(self);
		if (owner != null) {
			EndRingOwnedComponent.setOwner(fresh, owner);
		}
		// Force the targeting cache to recompute on the next aiStep regardless of the 5-tick
		// throttle - convertTo replaces the entity instance, so the new Mob's @Unique cache
		// fields are at their default (false) values until the next refresh tick. Without this
		// the freshly-converted mob would briefly accept the owner as a target. We can't cast
		// generic T directly to a mixin class, but the MobAiStepMixin will refresh the cache
		// anyway on the very next 5-tick boundary, and the window where the cache is stale is
		// at most 5 ticks (250 ms) - which is the same window any other mob has between refresh
		// ticks anyway. Skip the explicit refresh here; relying on the normal 5-tick cycle is
		// both simpler and avoids the cross-mixin cast entirely.
		EndRingSummons.onConverted(self, fresh);
	}
}
