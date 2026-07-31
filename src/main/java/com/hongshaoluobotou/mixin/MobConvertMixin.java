package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingOwnedComponent;
import com.hongshaoluobotou.EndRingSummons;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
public abstract class MobConvertMixin {
	// When vanilla converts a mob (zombie -> drowned, villager -> zombie villager, skeleton ->
	// stray, etc.) it allocates a brand-new entity and discards the old one. Any state we attached
	// to the old mob - including the EndRingOwner NBT marker and the priority-ladder goal - is
	// lost. The new mob, having no owner marker, would not trail the player anymore. We re-attach
	// ownership here so the converted mob still serves the ring wearer.
	//
	// 26.2 has two overloads: a 4-arg one (the canonical one with the AfterConversion callback) and
	// a 3-arg one that delegates to the 4-arg one. The 3-arg overload runs first in the call chain,
	// so we only need to target the 4-arg method to catch every conversion.

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
		// Only intervene for End Ring summons. Non-owned conversions (vanilla zombie -> drowned
		// by a vanilla event, etc.) are left untouched.
		if (!EndRingOwnedComponent.hasOwner(self)) {
			return;
		}
		EndRingSummons.onConverted(self, fresh);
	}
}
