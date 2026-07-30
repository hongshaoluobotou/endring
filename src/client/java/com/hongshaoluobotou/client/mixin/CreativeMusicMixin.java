package com.hongshaoluobotou.client.mixin;

import com.hongshaoluobotou.EndRingItem;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
public class CreativeMusicMixin {
	@ModifyExpressionValue(
		method = "getSituationalMusic",
		at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Abilities;instabuild:Z")
	)
	private boolean endring$playCreativeMusic(boolean original) {
		Minecraft mc = (Minecraft) (Object) this;
		return original || (mc.player != null && !EndRingItem.getWorn(mc.player).isEmpty());
	}
}