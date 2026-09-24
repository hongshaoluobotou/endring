package com.hongshaoluobotou;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ExperienceOrb;

public final class DragonDeath {
	public static final int DURATION_TICKS = 200;
	private static final int XP_REWARD = 12000;
	private static final int DRAGON_DEATH_EVENT = 1028;

	private static final Map<UUID, Integer> PLAYING = new HashMap<>();

	private DragonDeath() {
	}

	public static boolean isPlaying(ServerPlayer player) {
		return PLAYING.containsKey(player.getUUID());
	}

	public static void start(ServerPlayer player) {
		PLAYING.put(player.getUUID(), 0);
		player.setHealth(1.0F);
		playSound(player, SoundEvents.ENDER_DRAGON_DEATH, 1.0F);
		if (player.level() instanceof ServerLevel level) {
			level.globalLevelEvent(DRAGON_DEATH_EVENT, player.blockPosition(), 0);
		}
	}

	public static void tick(ServerPlayer player) {
		int time = PLAYING.get(player.getUUID());
		freeze(player);
		spawnParticles(player, time);
		broadcast(player, time);

		if (++time >= DURATION_TICKS) {
			finish(player);
		} else {
			PLAYING.put(player.getUUID(), time);
		}
	}

	private static void freeze(ServerPlayer player) {
		player.setHealth(1.0F);
		player.setInvulnerableTime(20);
		player.hurtTime = 0;
		player.setNoGravity(true);
		player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 10, 250, false, false, false));
		player.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 10, 1, false, false, false));
	}

	private static void spawnParticles(ServerPlayer player, int time) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}
		double x = player.getX();
		double y = player.getY() + 1.0;
		double z = player.getZ();

		for (int i = 0; i < 3; i++) {
			double xo = (player.getRandom().nextDouble() - 0.5) * 4.0;
			double yo = player.getRandom().nextDouble() * 3.0;
			double zo = (player.getRandom().nextDouble() - 0.5) * 4.0;
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x + xo, y + yo, z + zo, 1, 0.0, 0.0, 0.0, 0.0);
		}
		level.sendParticles(ParticleTypes.PORTAL, x, y, z, 30, 1.2, 1.5, 1.2, 0.6);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, x, y, z, 20, 0.8, 1.0, 0.8, 0.4);
		level.sendParticles(ParticleTypes.WITCH, x, y, z, 15, 1.0, 1.2, 1.0, 0.05);

		if (time % 10 == 0) {
			playSound(player, SoundEvents.ENDER_DRAGON_HURT, 0.7F);
		}
	}

	private static void broadcast(ServerPlayer player, int time) {
		DeathAnimationPayload payload = new DeathAnimationPayload(player.getId(), time);
		PlayerLookup.tracking(player).forEach(viewer -> ServerPlayNetworking.send(viewer, payload));
		ServerPlayNetworking.send(player, payload);
	}

	private static void finish(ServerPlayer player) {
		PLAYING.remove(player.getUUID());
		player.setNoGravity(false);
		if (player.level() instanceof ServerLevel level) {
			ExperienceOrb.award(level, player.position(), XP_REWARD);
			player.hurtServer(level, player.damageSources().genericKill(), Float.MAX_VALUE);
		}
	}

	private static void playSound(ServerPlayer player, net.minecraft.sounds.SoundEvent sound, float pitch) {
		player.level().playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, 5.0F, pitch);
	}
}
