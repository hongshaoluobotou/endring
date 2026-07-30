package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EnchantmentMenu.class)
public class EnchantmentXpMixin {
	// While the ring is worn, let the player enchant even without enough levels. Only the experienceLevel
	// reads in the "enough XP?" gate are inflated - lapis is still consumed, and the actual deduction in
	// onEnchantmentPerformed already clamps to 0, so this just lets XP be spent down to empty.
	@ModifyExpressionValue(
		method = "clickMenuButton",
		at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Player;experienceLevel:I")
	)
	private int endring$ignoreXpGate(int original, Player player) {
		if (!EndRingItem.getWorn(player).isEmpty()) {
			return Integer.MAX_VALUE;
		}
		return original;
	}
}
