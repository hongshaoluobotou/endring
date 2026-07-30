package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public class RingArmourWearMixin {
	// The ring is damageable to drive its totem bar, but ordinary combat/armour wear should never drain
	// its totems. Block the helmet/armour wear path when the head is our ring so doHurtEquipment never
	// touches it. (Player.hurtHelmet is the only caller for the head; Player.hurtArmor iterates
	// feet/legs/chest/head and would include the ring on the head.)
	@Inject(method = "hurtHelmet", at = @At("HEAD"), cancellable = true)
	private void endring$skipRingHelmet(DamageSource source, float damage, CallbackInfo ci) {
		Player self = (Player) (Object) this;
		ItemStack head = self.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
		if (EndRingItem.isRing(head)) {
			ci.cancel();
		}
	}

	@Inject(method = "hurtArmor", at = @At("HEAD"), cancellable = true)
	private void endring$skipRingArmor(DamageSource source, float damage, CallbackInfo ci) {
		Player self = (Player) (Object) this;
		ItemStack head = self.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
		if (EndRingItem.isRing(head)) {
			ci.cancel();
		}
	}
}
