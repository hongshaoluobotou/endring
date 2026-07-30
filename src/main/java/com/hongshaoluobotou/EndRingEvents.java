package com.hongshaoluobotou;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class EndRingEvents {
	private static final Map<UUID, Float> LAST_HEALTH = new HashMap<>();
	private static final Map<UUID, FlightState> FLIGHT = new HashMap<>();
	private static final Set<UUID> LAST_STAND = new HashSet<>();
	// Totem regen progress is tracked here instead of on the item so the ring's component is not
	// rewritten every tick. Constant per-tick component churn made vanilla treat the helmet as
	// re-equipped each tick (replaying the equip sound / re-syncing the slot). The item is only mutated
	// when a totem is actually gained.
	private static final Map<UUID, Integer> TOTEM_REGEN = new HashMap<>();

	private static final net.minecraft.resources.Identifier DYNAMIC_ARMOR_ID = EndRing.id("end_ring_dynamic_armor");
	private static final net.minecraft.resources.Identifier DYNAMIC_ARMOR_TOUGHNESS_ID = EndRing.id("end_ring_dynamic_armor_toughness");
	private static final net.minecraft.resources.Identifier LAST_STAND_HEALTH_ID = EndRing.id("end_ring_last_stand_health");
	private static final net.minecraft.resources.Identifier BLOCK_REACH_ID = EndRing.id("end_ring_block_reach");
	private static final net.minecraft.resources.Identifier ENTITY_REACH_ID = EndRing.id("end_ring_entity_reach");

	// match creative-mode reach: +0.5 block range, +2.0 entity range (vanilla creative modifier values)
	private static final double CREATIVE_BLOCK_REACH_BONUS = 0.5;
	private static final double CREATIVE_ENTITY_REACH_BONUS = 2.0;

	private static final int HURT_RESISTANCE_DURATION = 600;
	private static final double LAST_STAND_MAX_HEALTH_BONUS = 20.0;

	private static final float BASE_FLY_SPEED = 0.05F;
	private static final float MAX_FLY_SPEED = 0.6F;
	private static final float FLY_SPEED_STEP = 0.004F;
	private static final double VERTICAL_THRESHOLD = 0.08;

	private EndRingEvents() {
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(DeathAnimationPayload.TYPE, DeathAnimationPayload.CODEC);

		ServerTickEvents.END_SERVER_TICK.register(server -> server.getPlayerList().getPlayers().forEach(EndRingEvents::tickPlayer));

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			ItemStack ring = EndRingItem.getWorn(oldPlayer);
			if (!ring.isEmpty() && EndRingItem.getWorn(newPlayer).isEmpty()) {
				newPlayer.setItemSlot(EquipmentSlot.HEAD, ring.copy());
			}
			LAST_HEALTH.remove(newPlayer.getUUID());
		});

		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
			if (entity instanceof ServerPlayer player && !EndRingItem.getWorn(player).isEmpty() && !DragonDeath.isPlaying(player)) {
				onDamaged(player);
			}
		});

		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer player
				&& !DragonDeath.isPlaying(player)
				&& !EndRingItem.getWorn(player).isEmpty()
				&& EndRingItem.getTotems(EndRingItem.getWorn(player)) <= 0
				&& !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
				DragonDeath.start(player);
				return false;
			}
			return true;
		});
	}

	private static void tickPlayer(ServerPlayer player) {
		if (DragonDeath.isPlaying(player)) {
			DragonDeath.tick(player);
			return;
		}

		ItemStack ring = EndRingItem.getWorn(player);
		if (ring.isEmpty()) {
			LAST_HEALTH.remove(player.getUUID());
			TOTEM_REGEN.remove(player.getUUID());
			removeDynamicArmor(player);
			removeLastStand(player);
			removeReach(player);
			resetFlightSpeed(player);
			revokeFlight(player);
			return;
		}

		playHurtSound(player);
		applyWornEffects(player);
		tickFlightBoost(player);
		tickTotemRegen(player, ring);
	}

	private static void tickTotemRegen(ServerPlayer player, ItemStack ring) {
		UUID id = player.getUUID();
		if (EndRingItem.getTotems(ring) >= EndRingItem.MAX_TOTEMS) {
			TOTEM_REGEN.remove(id);
			return;
		}
		int progress = TOTEM_REGEN.getOrDefault(id, 0) + 1;
		if (progress >= EndRingItem.TOTEM_REGEN_TICKS) {
			EndRingItem.setTotems(ring, EndRingItem.getTotems(ring) + 1);
			TOTEM_REGEN.remove(id);
		} else {
			TOTEM_REGEN.put(id, progress);
		}
	}

	private static void playHurtSound(ServerPlayer player) {
		float health = player.getHealth();
		Float previous = LAST_HEALTH.put(player.getUUID(), health);
		if (previous != null && previous - health > 1.0F && player.isAlive()) {
			player.level().playSound(null, player.blockPosition(), SoundEvents.ENDER_DRAGON_HURT, SoundSource.PLAYERS, 5.0F, 1.0F);
		}
	}

	private static void applyWornEffects(ServerPlayer player) {
		if (!player.getAbilities().mayfly) {
			player.getAbilities().mayfly = true;
			player.onUpdateAbilities();
		}

		if (player.level().isDarkOutside()) {
			refreshEffect(player, MobEffects.NIGHT_VISION, 0);
		} else if (player.hasEffect(MobEffects.NIGHT_VISION)) {
			player.removeEffect(MobEffects.NIGHT_VISION);
		}

		if (player.hasEffect(MobEffects.CONDUIT_POWER)) {
			player.removeEffect(MobEffects.CONDUIT_POWER);
		}

		float frac = totemFraction(player);
		updateDynamicArmor(player, frac);

		applyReach(player);

		int strengthAmp = strengthAmplifier(frac);
		if (strengthAmp >= 0) {
			applyTiered(player, MobEffects.STRENGTH, strengthAmp);
		}

		if (frac < 0.10F) {
			applyTiered(player, MobEffects.REGENERATION, 1);
		}

		updateLastStandHealth(player, frac);
	}

	private static void onDamaged(ServerPlayer player) {
		float frac = totemFraction(player);

		int resistanceAmp = resistanceAmplifier(frac);
		if (resistanceAmp >= 0) {
			refreshFixed(player, MobEffects.RESISTANCE, resistanceAmp, HURT_RESISTANCE_DURATION);
		}

		if (frac < 0.20F && frac >= 0.10F) {
			applyTiered(player, MobEffects.REGENERATION, 0);
		}
	}

	// The lower the fraction of stored totems, the stronger the granted attributes/effects (a totem count
	// of 0 yields fraction 0, MAX_TOTEMS yields 1).
	private static float totemFraction(ServerPlayer player) {
		return (float) EndRingItem.getTotems(EndRingItem.getWorn(player)) / EndRingItem.MAX_TOTEMS;
	}

	private static int resistanceAmplifier(float frac) {
		if (frac < 0.20F) {
			return 3;
		}
		if (frac < 0.40F) {
			return 2;
		}
		if (frac < 0.60F) {
			return 1;
		}
		if (frac < 0.80F) {
			return 0;
		}
		return -1;
	}

	private static int strengthAmplifier(float frac) {
		if (frac < 0.10F) {
			return 63;
		}
		if (frac < 0.20F) {
			return 48;
		}
		if (frac < 0.30F) {
			return 35;
		}
		if (frac < 0.40F) {
			return 24;
		}
		if (frac < 0.50F) {
			return 15;
		}
		if (frac < 0.60F) {
			return 8;
		}
		if (frac < 0.70F) {
			return 3;
		}
		if (frac < 0.80F) {
			return 0;
		}
		return -1;
	}

	private static double dynamicArmorBonus(float frac) {
		if (frac < 0.10F) {
			return 25;
		}
		if (frac < 0.20F) {
			return 24;
		}
		if (frac < 0.30F) {
			return 20;
		}
		if (frac < 0.40F) {
			return 15;
		}
		if (frac < 0.50F) {
			return 10.0;
		}
		if (frac < 0.60F) {
			return 5.0;
		}
		if (frac < 0.70F) {
			return 3.0;
		}
		if (frac < 0.80F) {
			return 1.0;
		}
		return 0.0;
	}

	private static double dynamicArmorToughnessBonus(float frac) {
		if (frac < 0.10F) {
			return 63;
		}
		if (frac < 0.20F) {
			return 48;
		}
		if (frac < 0.30F) {
			return 35;
		}
		if (frac < 0.40F) {
			return 24;
		}
		if (frac < 0.50F) {
			return 15;
		}
		if (frac < 0.60F) {
			return 8;
		}
		if (frac < 0.70F) {
			return 3;
		}
		if (frac < 0.80F) {
			return 0;
		}
		return -1;
	}

	private static void updateDynamicArmor(ServerPlayer player, float frac) {
		updateModifier(player, Attributes.ARMOR, DYNAMIC_ARMOR_ID, dynamicArmorBonus(frac));
		updateModifier(player, Attributes.ARMOR_TOUGHNESS, DYNAMIC_ARMOR_TOUGHNESS_ID, dynamicArmorToughnessBonus(frac));
	}

	private static void updateModifier(ServerPlayer player, Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
			net.minecraft.resources.Identifier id, double bonus) {
		AttributeInstance instance = player.getAttribute(attribute);
		if (instance == null) {
			return;
		}
		AttributeModifier existing = instance.getModifier(id);
		if (bonus <= 0.0) {
			if (existing != null) {
				instance.removeModifier(id);
			}
			return;
		}
		if (existing == null || existing.amount() != bonus) {
			instance.removeModifier(id);
			instance.addOrUpdateTransientModifier(new AttributeModifier(id, bonus, AttributeModifier.Operation.ADD_VALUE));
		}
	}

	private static void removeDynamicArmor(ServerPlayer player) {
		AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
		if (armor != null && armor.getModifier(DYNAMIC_ARMOR_ID) != null) {
			armor.removeModifier(DYNAMIC_ARMOR_ID);
		}
		AttributeInstance toughness = player.getAttribute(Attributes.ARMOR_TOUGHNESS);
		if (toughness != null && toughness.getModifier(DYNAMIC_ARMOR_TOUGHNESS_ID) != null) {
			toughness.removeModifier(DYNAMIC_ARMOR_TOUGHNESS_ID);
		}
	}

	private static void applyReach(ServerPlayer player) {
		applyReach(player, Attributes.BLOCK_INTERACTION_RANGE, BLOCK_REACH_ID, CREATIVE_BLOCK_REACH_BONUS);
		applyReach(player, Attributes.ENTITY_INTERACTION_RANGE, ENTITY_REACH_ID, CREATIVE_ENTITY_REACH_BONUS);
	}

	private static void applyReach(ServerPlayer player, Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
			net.minecraft.resources.Identifier id, double bonus) {
		AttributeInstance instance = player.getAttribute(attribute);
		if (instance == null) {
			return;
		}
		AttributeModifier existing = instance.getModifier(id);
		if (existing == null || existing.amount() != bonus) {
			instance.removeModifier(id);
			instance.addOrUpdateTransientModifier(new AttributeModifier(id, bonus, AttributeModifier.Operation.ADD_VALUE));
		}
	}

	private static void removeReach(ServerPlayer player) {
		removeReach(player, Attributes.BLOCK_INTERACTION_RANGE, BLOCK_REACH_ID);
		removeReach(player, Attributes.ENTITY_INTERACTION_RANGE, ENTITY_REACH_ID);
	}

	private static void removeReach(ServerPlayer player, Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
			net.minecraft.resources.Identifier id) {
		AttributeInstance instance = player.getAttribute(attribute);
		if (instance != null && instance.getModifier(id) != null) {
			instance.removeModifier(id);
		}
	}

	// Bonus max health scales up smoothly as stored totems run low, peaking at LAST_STAND_MAX_HEALTH_BONUS
	// when no totems remain. Replaces the old fixed +20 granted only at zero totems.
	private static void updateLastStandHealth(ServerPlayer player, float frac) {
		double bonus = (1.0 - frac) * LAST_STAND_MAX_HEALTH_BONUS;
		AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
		if (maxHealth == null) {
			return;
		}
		AttributeModifier existing = maxHealth.getModifier(LAST_STAND_HEALTH_ID);
		if (bonus <= 0.0) {
			if (existing != null) {
				maxHealth.removeModifier(LAST_STAND_HEALTH_ID);
			}
			return;
		}
		if (existing == null || existing.amount() != bonus) {
			maxHealth.removeModifier(LAST_STAND_HEALTH_ID);
			maxHealth.addOrUpdateTransientModifier(new AttributeModifier(LAST_STAND_HEALTH_ID, bonus, AttributeModifier.Operation.ADD_VALUE));
		}
	}

	// One-shot absorption granted when a totem is consumed. Lasts a single totem's worth of time and is not
	// refreshed every tick; the fewer totems left after the trigger, the stronger the shield.
	public static void grantTotemAbsorption(ServerPlayer player) {
		int amplifier = absorptionAmplifier(totemFraction(player));
		if (amplifier < 0) {
			return;
		}
		player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, EndRingItem.TOTEM_REGEN_TICKS, amplifier, false, true, true));
	}

	private static int absorptionAmplifier(float frac) {
		if (frac < 0.10F) {
			return 9;
		}
		if (frac < 0.30F) {
			return 5;
		}
		if (frac < 0.50F) {
			return 2;
		}
		if (frac < 0.70F) {
			return 0;
		}
		return -1;
	}

	private static void removeLastStand(ServerPlayer player) {
		LAST_STAND.remove(player.getUUID());
		AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
		if (maxHealth != null && maxHealth.getModifier(LAST_STAND_HEALTH_ID) != null) {
			maxHealth.removeModifier(LAST_STAND_HEALTH_ID);
		}
		player.removeEffect(MobEffects.ABSORPTION);
	}

	private static void applyTiered(ServerPlayer player, Holder<MobEffect> effect, int amplifier) {
		MobEffectInstance current = player.getEffect(effect);
		if (current == null || current.getAmplifier() < amplifier) {
			player.addEffect(new MobEffectInstance(effect, randomDuration(player), amplifier, false, true, true));
		}
	}

	private static void refreshFixed(ServerPlayer player, Holder<MobEffect> effect, int amplifier, int duration) {
		MobEffectInstance current = player.getEffect(effect);
		if (current == null || current.getAmplifier() < amplifier || current.getDuration() < duration) {
			player.addEffect(new MobEffectInstance(effect, duration, amplifier, false, true, true));
		}
	}

	private static int randomDuration(LivingEntity entity) {
		RandomSource random = entity.getRandom();
		return 100 + random.nextInt(500);
	}

	private static void refreshEffect(ServerPlayer player, Holder<MobEffect> effect, int amplifier) {
		MobEffectInstance current = player.getEffect(effect);
		if (current == null || current.getAmplifier() != amplifier || current.getDuration() < 400) {
			player.addEffect(new MobEffectInstance(effect, MobEffectInstance.INFINITE_DURATION, amplifier, false, false, false));
		}
	}

	private static void tickFlightBoost(ServerPlayer player) {
		FlightState state = FLIGHT.computeIfAbsent(player.getUUID(), id -> new FlightState());

		boolean flying = player.getAbilities().flying;

		double y = player.getY();
		double dy = state.hasPos ? y - state.lastY : 0.0;
		state.lastY = y;
		state.hasPos = true;

		boolean sprinting = flying && player.isSprinting();
		boolean verticalFlight = flying && Math.abs(dy) >= VERTICAL_THRESHOLD;
		boolean boosting = sprinting || verticalFlight;

		if (boosting && !state.wasBoosting) {
			state.savedSpeed = player.getAbilities().getFlyingSpeed();
		}
		state.wasBoosting = boosting;

		if (!boosting) {
			restoreFlightSpeed(player);
			return;
		}

		float target = Math.min(MAX_FLY_SPEED, player.getAbilities().getFlyingSpeed() + FLY_SPEED_STEP);
		applyFlightSpeed(player, state, target);
	}

	private static void applyFlightSpeed(ServerPlayer player, FlightState state, float speed) {
		if (state.speed == speed && player.getAbilities().getFlyingSpeed() == speed) {
			return;
		}
		state.speed = speed;
		player.getAbilities().setFlyingSpeed(speed);
		player.onUpdateAbilities();
	}

	private static void restoreFlightSpeed(ServerPlayer player) {
		FlightState state = FLIGHT.get(player.getUUID());
		if (state == null) {
			return;
		}
		if (player.getAbilities().getFlyingSpeed() != state.savedSpeed) {
			state.speed = state.savedSpeed;
			player.getAbilities().setFlyingSpeed(state.savedSpeed);
			player.onUpdateAbilities();
		}
	}

	private static void resetFlightSpeed(ServerPlayer player) {
		FlightState state = FLIGHT.get(player.getUUID());
		if (state == null) {
			return;
		}
		state.wasBoosting = false;
		state.hasPos = false;
		if (player.getAbilities().getFlyingSpeed() != state.savedSpeed) {
			state.speed = state.savedSpeed;
			player.getAbilities().setFlyingSpeed(state.savedSpeed);
			player.onUpdateAbilities();
		}
	}

	// The ring grants flight (mayfly) while worn; take it back once it is gone. Skip creative/spectator,
	// whose game mode legitimately grants flight so we must not strip it.
	private static void revokeFlight(ServerPlayer player) {
		if (player.isCreative() || player.isSpectator()) {
			return;
		}
		if (player.getAbilities().mayfly || player.getAbilities().flying) {
			player.getAbilities().mayfly = false;
			player.getAbilities().flying = false;
			player.onUpdateAbilities();
		}
	}

	private static final class FlightState {
		float speed = BASE_FLY_SPEED;
		float savedSpeed = BASE_FLY_SPEED;
		boolean wasBoosting;
		double lastY;
		boolean hasPos;
	}
}
