package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;

@Mixin(LivingEntity.class)
public abstract class DamageResistanceMixin {
	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void endring$immuneDamage(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof Player player) || EndRingItem.getWorn(player).isEmpty()) {
			return;
		}
		if (endring$isImmune(source)) {
			cir.setReturnValue(false);
		}
	}

	@ModifyVariable(method = "hurtServer", at = @At("HEAD"), ordinal = 0, argsOnly = true)
	private float endring$reduceDamage(float damage, ServerLevel level, DamageSource source) {
		if (!((Object) this instanceof Player player) || EndRingItem.getWorn(player).isEmpty()) {
			return damage;
		}
		if (source.is(DamageTypes.STARVE)) {
			if (player.getHealth() > 4.0F) {
				return 3.0F + player.getRandom().nextInt(2);
			}
			return 3.0F;
		}
		if (endring$isReduced(source)) {
			return damage * 0.2F;
		}
		return damage;
	}

	private static boolean endring$isImmune(DamageSource source) {
		return source.is(DamageTypeTags.IS_FIRE)
			|| source.is(DamageTypeTags.IS_FALL)
			|| source.is(DamageTypeTags.IS_EXPLOSION)
			|| source.is(DamageTypes.CACTUS)
			|| source.is(DamageTypes.IN_WALL);
	}

	private static boolean endring$isReduced(DamageSource source) {
		return source.is(DamageTypes.SWEET_BERRY_BUSH)
			|| source.is(DamageTypeTags.IS_FREEZING)
			|| source.is(DamageTypeTags.IS_PROJECTILE)
			|| endring$isMelee(source)
			|| endring$isMagic(source);
	}

	private static boolean endring$isMelee(DamageSource source) {
		return source.is(DamageTypeTags.IS_PLAYER_ATTACK)
			|| source.is(DamageTypeTags.IS_MACE_SMASH)
			|| source.is(DamageTypes.MOB_ATTACK)
			|| source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)
			|| source.is(DamageTypes.STING)
			|| source.is(DamageTypes.THORNS)
			|| source.is(DamageTypes.SPEAR);
	}

	private static boolean endring$isMagic(DamageSource source) {
		return source.is(DamageTypes.MAGIC)
			|| source.is(DamageTypes.INDIRECT_MAGIC)
			|| source.is(DamageTypes.DRAGON_BREATH);
	}
}
