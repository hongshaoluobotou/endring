package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingEvents;
import com.hongshaoluobotou.EndRingItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DeathProtection;
import net.minecraft.world.level.gameevent.GameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public class TotemStorageMixin {
	@Inject(method = "checkTotemDeathProtection", at = @At("HEAD"), cancellable = true)
	private void endring$ringTotem(DamageSource killingDamage, CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof ServerPlayer player)) {
			return;
		}

		ItemStack ring = EndRingItem.getWorn(player);
		if (ring.isEmpty() || EndRingItem.getTotems(ring) <= 0) {
			return;
		}

		EndRingItem.setTotems(ring, EndRingItem.getTotems(ring) - 1);
		float maxHealth = player.getMaxHealth();
		float restoreFrac = 0.5F + player.getRandom().nextFloat() * 0.5F;
		player.setHealth(maxHealth * restoreFrac);
		DeathProtection.TOTEM_OF_UNDYING.applyEffects(ring, player);
		EndRingEvents.grantTotemAbsorption(player);
		player.level().broadcastEntityEvent(player, (byte) 35);
		player.gameEvent(GameEvent.ITEM_INTERACT_FINISH);
		cir.setReturnValue(true);
	}
}