package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AnvilMenu.class)
public class AnvilXpMixin {
	// While the ring is worn, let the player take the anvil result even without enough levels. Only the
	// experienceLevel read in mayPickup's "enough XP?" gate is inflated; onTake still calls
	// giveExperienceLevels(-cost), which clamps to 0, so XP is simply spent down to empty.
	@ModifyExpressionValue(
		method = "mayPickup",
		at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Player;experienceLevel:I")
	)
	private int endring$ignoreXpGate(int original, Player player) {
		if (!EndRingItem.getWorn(player).isEmpty()) {
			return Integer.MAX_VALUE;
		}
		return original;
	}
}
