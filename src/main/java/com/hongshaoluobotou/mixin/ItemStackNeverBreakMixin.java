package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStack.class)
public class ItemStackNeverBreakMixin {
	@Inject(method = "applyDamage", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isBroken()Z"), cancellable = true)
	private void endring$preventRingBreak(int newDamage, net.minecraft.server.level.ServerPlayer player, java.util.function.Consumer<Item> onBreak, CallbackInfo ci) {
		ItemStack self = (ItemStack) (Object) this;
		if (EndRingItem.isRing(self)) {
			ci.cancel();
		}
	}
}
