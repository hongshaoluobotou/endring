package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerPlayerGameMode.class)
public class CreativeMiningMixin {
	@Shadow
	@org.spongepowered.asm.mixin.Final
	protected ServerPlayer player;

	@ModifyExpressionValue(
		method = "handleBlockBreakAction",
		at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Abilities;instabuild:Z")
	)
	private boolean endring$grantCreativeMining(boolean original) {
		return original || EndRingItem.grantsCreativeMining(this.player);
	}
}