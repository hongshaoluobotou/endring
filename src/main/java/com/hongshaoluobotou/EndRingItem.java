package com.hongshaoluobotou;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class EndRingItem extends Item {
	public static final int MAX_TOTEMS = 8;
	public static final int TOTEM_REGEN_TICKS = 600;

	static final int LORE_LINES = 5;

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

	// Totems are mirrored into vanilla DAMAGE: damage = MAX_TOTEMS - totems. MAX_DAMAGE is MAX_TOTEMS + 1
	// so the bar at the bottom (totems=0, damage=MAX_TOTEMS) still shows one notch of width — we never
	// reach MAX_DAMAGE itself, which keeps the ring out of vanilla's break-and-shrink path while still
	// driving the durability bar, Unbreaking, Mending, and ItemStack::isDamaged uniformly.
	public static final int MAX_DAMAGE = MAX_TOTEMS + 1;
	public static int getTotems(ItemStack ring) {
		return Math.max(0, MAX_TOTEMS - ring.getDamageValue());
	}

	public static void setTotems(ItemStack ring, int count) {
		int damage = Math.max(0, MAX_TOTEMS - Math.clamp(count, 0, MAX_TOTEMS));
		ring.setDamageValue(damage);
	}

	// Consume one totem for a fatal hit, honouring the Unbreaking enchantment: Unbreaking is given a
	// chance to spare the totem entirely (the same way vanilla tools gain durability). Returns true when
	// a totem was actually spent. The ring never breaks: damage is clamped below MAX_DAMAGE.
	public static boolean consumeTotem(ServerPlayer player, ItemStack ring) {
		if (getTotems(ring) <= 0) {
			return false;
		}
		// Run the Unbreaking pass the same way vanilla tools do, then write the new damage directly. A
		// direct setDamageValue cannot shrink the stack (ItemStack only shrinks inside applyDamage), so
		// the ring is safe at the upper end even though we momentarily reach MAX_DAMAGE on paper.
		int cost = net.minecraft.world.item.enchantment.EnchantmentHelper.processDurabilityChange(player.level(), ring, 1);
		if (cost <= 0) {
			return true;
		}
		ring.setDamageValue(Math.min(ring.getDamageValue() + cost, MAX_DAMAGE - 1));
		return true;
	}

	public static EndRingComponent data(ItemStack ring) {
		return ring.getOrDefault(ModComponents.END_RING, EndRingComponent.DEFAULT);
	}
}
