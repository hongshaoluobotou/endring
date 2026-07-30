package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.FlightLock;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GameType.class)
public abstract class GameTypeUpdateAbilitiesMixin {
	@Redirect(
		method = "updatePlayerAbilities",
		at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Abilities;flying:Z", opcode = 181, ordinal = 1)
	)
	private void endring$skipFlyingReset(Abilities abilities, boolean value) {
		if (!value && FlightLock.isLocked()) {
			return;
		}
		abilities.flying = value;
	}
}
