package com.hongshaoluobotou.client.mixin;

import com.hongshaoluobotou.EndRingItem;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Hud.class)
public class HideAirBubblesMixin {
	@org.spongepowered.asm.mixin.injection.Inject(
		method = "extractAirBubbles",
		at = @At("HEAD"),
		cancellable = true
	)
	private void endring$hideAirBubbles(GuiGraphicsExtractor extractor, Player player, int x, int y, int width, CallbackInfo ci) {
		if (!EndRingItem.getWorn(player).isEmpty()) {
			ci.cancel();
		}
	}
}