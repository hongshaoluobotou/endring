package com.hongshaoluobotou.client.mixin;

import com.hongshaoluobotou.EndRingItem;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MultiPlayerGameMode.class)
public class CreativeMiningClientMixin {
	@Shadow
	@org.spongepowered.asm.mixin.Final
	private Minecraft minecraft;

	@ModifyExpressionValue(
		method = {"startDestroyBlock", "continueDestroyBlock"},
		at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Abilities;instabuild:Z")
	)
	private boolean endring$grantCreativeMining(boolean original) {
		return original || (this.minecraft.player != null && EndRingItem.grantsCreativeMining(this.minecraft.player));
	}
}