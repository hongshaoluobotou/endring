package com.hongshaoluobotou.client.mixin;

import com.hongshaoluobotou.EndRingItem;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public class HideHeadItemMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void endring$hideRingOnHead(LivingEntity entity, LivingEntityRenderState state, float partialTicks, CallbackInfo ci) {
		if (EndRingItem.isRing(entity.getItemBySlot(EquipmentSlot.HEAD))) {
			state.headItem.clear();
		}
	}
}