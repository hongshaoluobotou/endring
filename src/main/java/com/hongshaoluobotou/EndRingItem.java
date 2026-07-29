package com.hongshaoluobotou;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class EndRingItem extends Item {
	public static final int MAX_TOTEMS = 9;
	public static final int TOTEM_REGEN_TICKS = 600;

	static final int LORE_LINES = 11;

	public EndRingItem(Properties properties) {
		super(properties);
	}

	public static boolean isRing(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() instanceof EndRingItem;
	}

	public static ItemStack getWorn(LivingEntity entity) {
		ItemStack head = entity.getItemBySlot(EquipmentSlot.HEAD);
		return isRing(head) ? head : ItemStack.EMPTY;
	}

	public static boolean grantsCreativeMining(Player player) {
		return !getWorn(player).isEmpty() && !player.getMainHandItem().has(DataComponents.TOOL);
	}

	public static int getTotems(ItemStack ring) {
		return data(ring).storedTotems();
	}

	public static void setTotems(ItemStack ring, int count) {
		EndRingComponent current = data(ring);
		ring.set(ModComponents.END_RING, new EndRingComponent(Math.clamp(count, 0, MAX_TOTEMS), current.totemRegenProgress()));
	}

	public static void tickRegen(ItemStack ring) {
		EndRingComponent current = data(ring);
		if (current.storedTotems() >= MAX_TOTEMS) {
			if (current.totemRegenProgress() != 0) {
				ring.set(ModComponents.END_RING, new EndRingComponent(current.storedTotems(), 0));
			}
			return;
		}
		int progress = current.totemRegenProgress() + 1;
		if (progress >= TOTEM_REGEN_TICKS) {
			ring.set(ModComponents.END_RING, new EndRingComponent(Math.clamp(current.storedTotems() + 1, 0, MAX_TOTEMS), 0));
		} else {
			ring.set(ModComponents.END_RING, new EndRingComponent(current.storedTotems(), progress));
		}
	}

	private static EndRingComponent data(ItemStack ring) {
		return ring.getOrDefault(ModComponents.END_RING, EndRingComponent.DEFAULT);
	}
}