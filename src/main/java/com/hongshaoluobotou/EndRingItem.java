package com.hongshaoluobotou;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

public class EndRingItem extends Item {
	public static final int MAX_TOTEMS = 8;
	public static final int TOTEM_REGEN_TICKS = 600;

	static final int LORE_LINES = 11;

	public EndRingItem(Properties properties) {
		super(properties);
	}

	// The durability bar is a pure display of the stored totems (8 = full), NOT vanilla item damage: the
	// ring has no DAMAGE component, so ordinary combat / armour wear can never drain it. Totems only ever
	// move via the fatal-hit consume and the timed regen. Bar full when totems == MAX_TOTEMS.
	@Override
	public boolean isBarVisible(ItemStack stack) {
		return isRing(stack) && getTotems(stack) < MAX_TOTEMS;
	}

	@Override
	public int getBarWidth(ItemStack stack) {
		return net.minecraft.util.Mth.clamp(Math.round(getTotems(stack) * 13.0F / MAX_TOTEMS), 0, 13);
	}

	@Override
	public int getBarColor(ItemStack stack) {
		float fraction = (float) getTotems(stack) / MAX_TOTEMS;
		return net.minecraft.util.Mth.hsvToRgb(fraction / 3.0F, 1.0F, 1.0F);
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
		int clamped = Math.clamp(count, 0, MAX_TOTEMS);
		ring.set(ModComponents.END_RING, new EndRingComponent(clamped, current.totemRegenProgress()));
		syncModel(ring, clamped);
	}

	// Mirror the totem count into custom_model_data float #0 so the client item model can range-dispatch
	// on it: 0 -> damaged, 1-3 (<50%) -> chipped, 4-8 -> normal. The END_RING component itself is not a
	// client model property, so this bridge is what actually swaps the visible model.
	private static void syncModel(ItemStack ring, int totems) {
		net.minecraft.world.item.component.CustomModelData current =
			ring.getOrDefault(DataComponents.CUSTOM_MODEL_DATA, net.minecraft.world.item.component.CustomModelData.EMPTY);
		ring.set(
			DataComponents.CUSTOM_MODEL_DATA,
			new net.minecraft.world.item.component.CustomModelData(
				java.util.List.of((float) totems),
				current.flags(),
				current.strings(),
				current.colors()
			)
		);
	}

	// Consume one totem for a fatal hit, honouring the Unbreaking enchantment: Unbreaking is given a
	// chance to spare the totem entirely (via EnchantmentHelper.processDurabilityChange, the same path
	// vanilla uses for tool durability). Returns true when a totem was actually spent.
	public static boolean consumeTotem(ServerLevel level, ItemStack ring) {
		if (getTotems(ring) <= 0) {
			return false;
		}
		int cost = EnchantmentHelper.processDurabilityChange(level, ring, 1);
		if (cost <= 0) {
			return true;
		}
		setTotems(ring, getTotems(ring) - cost);
		return true;
	}

	private static EndRingComponent data(ItemStack ring) {
		return ring.getOrDefault(ModComponents.END_RING, EndRingComponent.DEFAULT);
	}
}