package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingOwnedComponent;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class VexDeathLootMixin {
	// Vanilla vexes drop nothing, and we don't want natural / pillager-summoned vexes to flood the
	// player with trial-spawner loot. Reward drops are reserved for vexes the End Ring summoned -
	// the ones that carry an EndRingOwnedComponent owner marker (set by EndRingSummons.summonAt
	// right after finalizeSpawn). The owner themselves killing their own vex is also excluded, so
	// the only path that triggers a roll is "some other player killed my vex", which is the intended
	// PvP-bait mechanic. The mixin is attached to LivingEntity because Vex itself does not override
	// die(); Sponge refuses to inject into a method that does not exist on the mixin target, so we
	// hook the parent and gate on (self instanceof Vex).
	@Inject(method = "die", at = @At("TAIL"))
	private void endring$rollOminusConsumables(DamageSource source, CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!(self instanceof Vex vex)) {
			return;
		}
		if (!(vex.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		UUID ownerId = EndRingOwnedComponent.getOwnerUuid((Mob) vex);
		if (ownerId == null) {
			return;
		}
		if (!(vex.getKillCredit() instanceof Player killer)) {
			return;
		}
		if (killer.getUUID().equals(ownerId)) {
			return;
		}
		vex.dropFromLootTable(serverLevel, source, true, BuiltInLootTables.SPAWNER_OMINOUS_TRIAL_CHAMBER_CONSUMABLES);
	}
}
