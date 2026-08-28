package com.hongshaoluobotou.mixin;

import com.hongshaoluobotou.EndRingItem;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Inventory.class)
public class InventoryDeathKeepMixin {
	@Shadow
	@Final
	private NonNullList<ItemStack> items;

	@Shadow
	@Final
	private EntityEquipment equipment;

	@Unique
	private final Map<Integer, ItemStack> endring$stashedFromItems = new HashMap<>();

	@Unique
	private final Map<EquipmentSlot, ItemStack> endring$stashedFromEquipment = new HashMap<>();

	@Unique
	private final Map<Integer, ItemStack> endring$stashedFromClear = new HashMap<>();

	// Player.dropEquipment (called from die() -> dropAllDeathLoot) eventually runs inventory.dropAll.
	// That method first drops every non-empty slot in the 36-slot main inventory, then calls
	// EntityEquipment.dropAll which drops every equipped slot. Both halves can hold a ring, so we
	// snapshot every ring from BOTH places at HEAD, leaving empties behind for the rest of the
	// method to no-op on, and restore them at TAIL. Doing the equipment stash here lets us delete
	// the old EntityEquipment-targeted mixin.
	@Inject(method = "dropAll", at = @At("HEAD"))
	private void endring$stashRingsOnDeath(CallbackInfo ci) {
		this.endring$stashFromItemsAndEquipment();
	}

	@Inject(method = "dropAll", at = @At("TAIL"))
	private void endring$restoreRingsAfterDeath(CallbackInfo ci) {
		this.endring$restoreFromItemsAndEquipment();
	}

	// /clear runs through Inventory.clearOrCountMatchingItems -> ContainerHelper.clearOrCountMatchingItems,
	// which shrinks matching stacks in-place. That bypasses Inventory.setItem (so the head-slot lock
	// in InventoryHeadLockMixin cannot react) and walks all 36 main-inventory slots. Stash every
	// ring out of those slots for the duration of the scan and restore afterwards. InventoryHeadLockMixin
	// still owns the head equipment slot separately (it also hurts the wearer when the head ring
	// gets targeted). The equipment slots other than HEAD are not reachable through Container.getItems()
	// so they do not need protection here.
	@Inject(method = "clearOrCountMatchingItems", at = @At("HEAD"))
	private void endring$stashRingsFromClear(Predicate<ItemStack> predicate, int maxCount, Container container, CallbackInfoReturnable<Integer> cir) {
		Inventory self = (Inventory) (Object) this;
		if (self.player.isCreative() || self.player.level().isClientSide()) {
			return;
		}
		this.endring$stashedFromClear.clear();
		for (int i = 0; i < this.items.size(); i++) {
			ItemStack stack = this.items.get(i);
			if (EndRingItem.isRing(stack) && predicate.test(stack)) {
				this.endring$stashedFromClear.put(i, stack);
				this.items.set(i, ItemStack.EMPTY);
			}
		}
	}

	@Inject(method = "clearOrCountMatchingItems", at = @At("RETURN"))
	private void endring$restoreRingsAfterClear(Predicate<ItemStack> predicate, int maxCount, Container container, CallbackInfoReturnable<Integer> cir) {
		if (this.endring$stashedFromClear.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<Integer, ItemStack>> it = this.endring$stashedFromClear.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, ItemStack> entry = it.next();
			this.items.set(entry.getKey(), entry.getValue());
			it.remove();
		}
	}

	@Unique
	private void endring$stashFromItemsAndEquipment() {
		this.endring$stashedFromItems.clear();
		this.endring$stashedFromEquipment.clear();

		for (int i = 0; i < this.items.size(); i++) {
			ItemStack stack = this.items.get(i);
			if (EndRingItem.isRing(stack)) {
				this.endring$stashedFromItems.put(i, stack);
				this.items.set(i, ItemStack.EMPTY);
			}
		}

		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			ItemStack stack = this.equipment.get(slot);
			if (EndRingItem.isRing(stack)) {
				this.endring$stashedFromEquipment.put(slot, stack);
				this.equipment.set(slot, ItemStack.EMPTY);
			}
		}
	}

	@Unique
	private void endring$restoreFromItemsAndEquipment() {
		Iterator<Map.Entry<Integer, ItemStack>> itemIt = this.endring$stashedFromItems.entrySet().iterator();
		while (itemIt.hasNext()) {
			Map.Entry<Integer, ItemStack> entry = itemIt.next();
			this.items.set(entry.getKey(), entry.getValue());
			itemIt.remove();
		}

		Iterator<Map.Entry<EquipmentSlot, ItemStack>> equipIt = this.endring$stashedFromEquipment.entrySet().iterator();
		while (equipIt.hasNext()) {
			Map.Entry<EquipmentSlot, ItemStack> entry = equipIt.next();
			this.equipment.set(entry.getKey(), entry.getValue());
			equipIt.remove();
		}
	}
}
