package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;

@Mixin(LivingEntity.class)
public abstract class DamageResistanceMixin {
	// per-player "dodge" build-up: each non-immune, non-slime hit that fails to dodge raises the odds
	// of the next hit being fully immune; a successful dodge resets it back to the low base chance.
	private static final Map<UUID, Integer> ENDRING_DODGE_STREAK = new HashMap<>();
	private static final double ENDRING_DODGE_BASE = 0.05;    // base chance at full health, fresh streak
	private static final double ENDRING_DODGE_PER_HIT = 0.06; // added per consecutive non-dodged hit
	private static final double ENDRING_DODGE_MAX = 0.9;       // cap

	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void endring$immuneDamage(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof Player player) || EndRingItem.getWorn(player).isEmpty()) {
			return;
		}
		if (endring$isImmune(source)) {
			cir.setReturnValue(false);
			return;
		}
		// The ring removes the player's own hurt-cooldown clamp so normal i-frames apply again. Slime-
		// family attacks (slime, magma cube, sulfur cube) are the exception: clear the cooldown so they
		// bypass i-frames and can deal damage every tick. Slimes never trigger the random dodge.
		if (source.getEntity() instanceof AbstractCubeMob) {
			player.invulnerableTime = 0;
			return;
		}
		// Random immunity for everything else: chance grows as health drops and as more hits pile up
		// without a dodge. On a successful dodge, negate the damage and reset the streak to the low base.
		if (endring$rollDodge(player)) {
			cir.setReturnValue(false);
		}
	}

	private static boolean endring$rollDodge(Player player) {
		UUID id = player.getUUID();
		int streak = ENDRING_DODGE_STREAK.getOrDefault(id, 0);
		float missingFrac = 1.0F - player.getHealth() / player.getMaxHealth();
		double chance = Math.min(ENDRING_DODGE_MAX,
			(ENDRING_DODGE_BASE + ENDRING_DODGE_PER_HIT * streak) * (1.0 + 2.0 * missingFrac));
		if (player.getRandom().nextDouble() < chance) {
			ENDRING_DODGE_STREAK.put(id, 0);
			return true;
		}
		ENDRING_DODGE_STREAK.put(id, streak + 1);
		return false;
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
