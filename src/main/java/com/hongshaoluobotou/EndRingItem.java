package com.hongshaoluobotou;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
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

	// Set while the mod itself moves the ring out of and back into the head slot (e.g. to shield it from
	// a /clear pass). During that window HeadSlotLockMixin must not treat the temporary emptying as a
	// removal attempt, or it would cancel our own stash and re-hurt the player.
	private static boolean lockSuppressed = false;

	public static boolean isLockSuppressed() {
		return lockSuppressed;
	}

	public static void setLockSuppressed(boolean value) {
		lockSuppressed = value;
	}

	// A ring worn on the head refuses to be removed by commands (/clear, /item, /data, ...) or any
	// programmatic head-slot write; instead the wearer is hurt, like taking a /kill hit. Returns true
	// when the attempted change should be blocked (i.e. a ring is being pulled off the head).
	//
	// Creative players are exempt: they can freely take the ring off with the mouse. Locking them would
	// desync the creative GUI (client removes it, server re-adds it) which spams the equip sound and
	// lets a held drop duplicate the ring endlessly; and creative invulnerability would swallow the
	// damage anyway. Client-side calls are ignored too: this only ever acts on the authoritative server,
	// so a server-driven equipment sync is never fought client-side (which would leave a ghost ring).
	public static boolean punishHeadRemoval(LivingEntity entity, ItemStack incoming) {
		if (lockSuppressed) {
			return false;
		}
		if (!(entity instanceof Player player) || isRing(incoming) || player.isCreative()) {
			return false;
		}
		if (player.level().isClientSide()) {
			return false;
		}
		if (getWorn(player).isEmpty()) {
			return false;
		}
		hurtForRemoval(player);
		return true;
	}

	// Damage dealt when something tries to strip the ring off the head - uses the genericKill source
	// (bypasses invulnerability like the void) so the damage always lands, with a random 6-18 amount
	// matching the /kill conversion in KillCommandMixin.
	public static void hurtForRemoval(Player player) {
		if (player.level() instanceof ServerLevel level) {
			float damage = 6.0F + player.getRandom().nextFloat() * 12.0F;
			player.hurtServer(level, player.damageSources().genericKill(), damage);
		}
	}

	public static int getTotems(ItemStack ring) {
		return data(ring).storedTotems();
	}

	public static void setTotems(ItemStack ring, int count) {
		EndRingComponent current = data(ring);
		ring.set(ModComponents.END_RING, new EndRingComponent(Math.clamp(count, 0, MAX_TOTEMS), current.totemRegenProgress()));
	}

	private static EndRingComponent data(ItemStack ring) {
		return ring.getOrDefault(ModComponents.END_RING, EndRingComponent.DEFAULT);
	}
}