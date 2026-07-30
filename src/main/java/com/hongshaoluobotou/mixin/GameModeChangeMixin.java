package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hongshaoluobotou.FlightLock;

@Mixin(ServerPlayerGameMode.class)
public abstract class GameModeChangeMixin {
	@Accessor
	abstract ServerPlayer getPlayer();

	@Inject(method = "setGameModeForPlayer", at = @At("HEAD"))
	private void endring$lockFlight(GameType newMode, GameType oldMode, CallbackInfo ci) {
		FlightLock.setLocked(!EndRingItem.getWorn(this.getPlayer()).isEmpty());
	}

	@Inject(method = "setGameModeForPlayer", at = @At("TAIL"))
	private void endring$unlockFlight(GameType newMode, GameType oldMode, CallbackInfo ci) {
		FlightLock.setLocked(false);
	}

	@Redirect(
		method = "changeGameModeForPlayer",
		at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Abilities;flying:Z", opcode = 181, ordinal = 0)
	)
	private void endring$skipGroundFlyingReset(Abilities abilities, boolean value) {
		if (!value && FlightLock.isLocked()) {
			return;
		}
		abilities.flying = value;
	}
}
