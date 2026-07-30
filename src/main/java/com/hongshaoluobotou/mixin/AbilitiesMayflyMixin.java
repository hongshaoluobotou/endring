package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class AbilitiesMayflyMixin {
	// Vanilla wipes mayfly=false every time it sends an abilities packet - on join, on gamemode
	// change to survival/adventure, on respawn. We re-grant mayfly right before the packet is sent,
	// so the client never sees a frame of "no flight". We deliberately do NOT touch abilities.flying,
	// so the player keeps whatever flight state they had (creative-mode join lands them on the ground,
	// but creative→survival mid-flight leaves them flying).
	@Inject(method = "onUpdateAbilities", at = @At("HEAD"))
	private void endring$grantMayfly(CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (!EndRingItem.getWorn(self).isEmpty()) {
			self.getAbilities().mayfly = true;
		}
	}
}
