package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class HeadSlotLockMixin {
	// Covers /item, /replaceitem, /data and any code that swaps equipment via setItemSlot. Death-drops
	// go through EntityEquipment.dropAll directly (handled by DropEquipmentMixin), so they are not
	// affected here.
	@Inject(method = "setItemSlot", at = @At("HEAD"), cancellable = true)
	private void endring$lockHeadRing(EquipmentSlot slot, ItemStack stack, CallbackInfo ci) {
		if (slot != EquipmentSlot.HEAD) {
			return;
		}
		LivingEntity self = (LivingEntity) (Object) this;
		if (EndRingItem.punishHeadRemoval(self, stack)) {
			ci.cancel();
		}
	}
}
