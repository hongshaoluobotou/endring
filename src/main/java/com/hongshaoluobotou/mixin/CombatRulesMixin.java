package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
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
		// Only the End Ring bypasses CombatRules' 20-armor cap. Without the ring the vanilla formula
		// (clamp realArmor to [totalArmor*0.2, 20]) runs unchanged.
		if (!(victim instanceof net.minecraft.world.entity.player.Player player) || EndRingItem.getWorn(player).isEmpty()) {
			return;
		}

		float toughness = 2.0F + armorToughness / 4.0F;
		// Damage scaling: 0.2 floor preserved, but no 20-point ceiling. With End Ring, 25 armor == 1.0
		// (100% reduction) and 30 armor == 1.2; armor-piercing weapon enchantments then eat into that
		// raw fraction (so 30 armor + -0.3 piercing -> 0.9 == 90% reduction).
		float realArmor = Math.max(totalArmor - damage / toughness, totalArmor * 0.2F);
		float armorFraction = realArmor / 25.0F;
		ItemStack weaponItem = source.getWeaponItem();
		float modifiedArmorFraction;
		if (weaponItem != null && victim.level() instanceof ServerLevel level) {
			modifiedArmorFraction = EnchantmentHelper.modifyArmorEffectiveness(level, weaponItem, victim, source, armorFraction);
		} else {
			modifiedArmorFraction = armorFraction;
		}
		// Final clamp to [0, 1] so 30-armor unmodified stays at 100% (1.2 -> 1.0) but piercing can drop
		// it below.
		float clampedFraction = Mth.clamp(modifiedArmorFraction, 0.0F, 1.0F);
		cir.setReturnValue(damage * (1.0F - clampedFraction));
	}
}
