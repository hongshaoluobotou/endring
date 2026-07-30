package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import java.util.function.Predicate;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Inventory.class)
public abstract class InventoryHeadLockMixin {
	@Unique
	private ItemStack endring$clearStash = ItemStack.EMPTY;

	// Direct programmatic writes to the head slot (container GUI moves, mod code) go through setItem.
	// If a ring is on the head and something tries to replace it with a non-ring, hurt instead.
	@Inject(method = "setItem", at = @At("HEAD"), cancellable = true)
	private void endring$lockHeadRing(int index, ItemStack stack, CallbackInfo ci) {
		EquipmentSlot slot = Inventory.EQUIPMENT_SLOT_MAPPING.get(index);
		if (slot != EquipmentSlot.HEAD) {
			return;
		}
		Inventory self = (Inventory) (Object) this;
		if (EndRingItem.punishHeadRemoval(self.player, stack)) {
			ci.cancel();
		}
	}

	// /clear reaches the ring through ContainerHelper.clearOrCountMatchingItems, which shrinks the
	// equipped stack in-place BEFORE Inventory.setItem is ever called - so the setItem hook above sees
	// an already-empty slot and cannot react. Stash the ring out of the head slot before the clear pass
	// runs (so it is neither counted nor shrunk), then restore it and hurt the wearer afterwards, the
	// same stash/restore pattern DropEquipmentMixin uses for death drops.
	@Inject(method = "clearOrCountMatchingItems", at = @At("HEAD"))
	private void endring$stashRingFromClear(Predicate<ItemStack> predicate, int maxCount, Container container, CallbackInfoReturnable<Integer> cir) {
		this.endring$clearStash = ItemStack.EMPTY;
		Inventory self = (Inventory) (Object) this;
		if (self.player.isCreative() || self.player.level().isClientSide()) {
			return;
		}
		ItemStack head = self.player.getItemBySlot(EquipmentSlot.HEAD);
		if (EndRingItem.isRing(head) && predicate.test(head)) {
			this.endring$clearStash = head;
			EndRingItem.setLockSuppressed(true);
			try {
				self.player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
			} finally {
				EndRingItem.setLockSuppressed(false);
			}
		}
	}

	@Inject(method = "clearOrCountMatchingItems", at = @At("RETURN"))
	private void endring$restoreRingFromClear(Predicate<ItemStack> predicate, int maxCount, Container container, CallbackInfoReturnable<Integer> cir) {
		if (this.endring$clearStash.isEmpty()) {
			return;
		}
		Inventory self = (Inventory) (Object) this;
		EndRingItem.setLockSuppressed(true);
		try {
			self.player.setItemSlot(EquipmentSlot.HEAD, this.endring$clearStash);
		} finally {
			EndRingItem.setLockSuppressed(false);
		}
		this.endring$clearStash = ItemStack.EMPTY;
		EndRingItem.hurtForRemoval(self.player);
	}
}
