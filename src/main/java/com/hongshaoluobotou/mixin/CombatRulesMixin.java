package com.hongshaoluobotou.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CombatRules.class)
public class CombatRulesMixin {
	@Inject(method = "getDamageAfterAbsorb", at = @At("HEAD"), cancellable = true)
	private static void endring$uncapArmor(
		LivingEntity victim, float damage, DamageSource source, float totalArmor, float armorToughness, CallbackInfoReturnable<Float> cir
	) {
		float toughness = 2.0F + armorToughness / 4.0F;
		float realArmor = Math.max(totalArmor - damage / toughness, totalArmor * 0.2F);
		float armorFraction = Mth.clamp(realArmor / 25.0F, 0.0F, 1.0F);
		ItemStack weaponItem = source.getWeaponItem();
		float modifiedArmorFraction;
		if (weaponItem != null && victim.level() instanceof ServerLevel level) {
			modifiedArmorFraction = Mth.clamp(
				EnchantmentHelper.modifyArmorEffectiveness(level, weaponItem, victim, source, armorFraction), 0.0F, 1.0F
			);
		} else {
			modifiedArmorFraction = armorFraction;
		}

		float damageMultiplier = 1.0F - modifiedArmorFraction;
		cir.setReturnValue(damage * damageMultiplier);
	}
}
