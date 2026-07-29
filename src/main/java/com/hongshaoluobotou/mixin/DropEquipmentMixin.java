package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityEquipment.class)
public class DropEquipmentMixin {
	@Unique
	private ItemStack endring$stashedRing = ItemStack.EMPTY;

	@Inject(method = "dropAll", at = @At("HEAD"))
	private void endring$keepRingOnDeath(LivingEntity dropper, CallbackInfo ci) {
		EntityEquipment self = (EntityEquipment) (Object) this;
		ItemStack head = self.get(EquipmentSlot.HEAD);
		if (EndRingItem.isRing(head)) {
			this.endring$stashedRing = head;
			self.set(EquipmentSlot.HEAD, ItemStack.EMPTY);
		} else {
			this.endring$stashedRing = ItemStack.EMPTY;
		}
	}

	@Inject(method = "dropAll", at = @At("TAIL"))
	private void endring$restoreRing(LivingEntity dropper, CallbackInfo ci) {
		if (!this.endring$stashedRing.isEmpty()) {
			EntityEquipment self = (EntityEquipment) (Object) this;
			self.set(EquipmentSlot.HEAD, this.endring$stashedRing);
			this.endring$stashedRing = ItemStack.EMPTY;
		}
	}
}
