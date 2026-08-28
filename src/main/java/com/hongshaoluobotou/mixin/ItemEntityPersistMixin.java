package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public class ItemEntityPersistMixin {
	// Vanilla's tick() ends with: if (!level().isClientSide() && age >= 6000) discard();
	// For a ring sitting on the ground we want it to stay there forever. Intercept that one
	// discard() invocation and cancel it when the stack is a ring. This leaves age untouched
	// (no "frozen at 0" weirdness) and naturally leaves isMergable() returning true, so nearby
	// ring drops still merge normally. The /give ghost entity (pickupDelay = 32767) is left alone
	// here too — the age check below still fires for it, since we only short-circuit discard()
	// when the item is a ring.
	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;discard()V"), cancellable = true)
	private void endring$preventRingTimeout(CallbackInfo ci) {
		ItemEntity self = (ItemEntity) (Object) this;
		if (EndRingItem.isRing(self.getItem())) {
			ci.cancel();
		}
	}
}
