package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public class ItemEntityPersistMixin {
	@Shadow
	private int age;

	// Vanilla discards a ground item after 6000 ticks (5 min) and refuses to merge after that
	// threshold too. A ring should never time out while sitting on the ground — pin the age to 0
	// every tick so neither the discard nor the mergeable check fires. The age field is private on
	// ItemEntity, so we @Shadow it here rather than reflecting.
	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
	private void endring$persistRing(CallbackInfo ci) {
		ItemEntity self = (ItemEntity) (Object) this;
		if (EndRingItem.isRing(self.getItem())) {
			this.age = 0;
		}
	}
}
