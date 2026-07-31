package com.hongshaoluobotou;

import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.CustomData;

/**
 * Marks a mob as owned by an End Ring wearer. The owner UUID is stored in the entity's per-instance
 * {@link DataComponents#CUSTOM_DATA} NBT under the key {@code EndRingOwner} (split into
 * {@code EndRingOwnerMost} and {@code EndRingOwnerLeast} as two longs, the same shape vanilla uses
 * for {@code TamableAnimal}'s owner). This means the marker survives server restarts, chunk reloads
 * and {@code Mob.convertTo} conversions automatically (the custom-data tag is part of the entity's
 * own saved data, not a synched state field).
 *
 * <p>Goals read the owner via {@link #getOwner(Mob)} each tick so they re-resolve to the current
 * {@link ServerPlayer} instance after respawn. The list of which summons belong to which player is
 * still kept in {@link EndRingSummons#SUMMONED} for cap counting and cleanup.
 */
public final class EndRingOwnedComponent {
	private static final String OWNER_KEY = "EndRingOwner";
	private static final String OWNER_MOST = "EndRingOwnerMost";
	private static final String OWNER_LEAST = "EndRingOwnerLeast";

	private EndRingOwnedComponent() {
	}

	/** {@code true} if the mob carries an End Ring owner marker. */
	public static boolean hasOwner(Mob mob) {
		return readOwnerUuid(mob) != null;
	}

	/**
	 * Resolves the owner as a living entity. Returns {@code null} if the mob has no owner marker, the
	 * owner is offline, or the owner isn't loaded.
	 */
	public static LivingEntity getOwner(Mob mob) {
		UUID id = readOwnerUuid(mob);
		if (id == null) {
			return null;
		}
		if (!(mob.level() instanceof ServerLevel level)) {
			return null;
		}
		Player player = level.getServer().getPlayerList().getPlayer(id);
		return player;
	}

	/** Writes the owner marker. {@code null} clears the marker. */
	public static void setOwner(Mob mob, LivingEntity owner) {
		writeOwner(mob, owner == null ? null : owner.getUUID());
	}

	/** Removes the owner marker, leaving the rest of the custom data intact. */
	public static void clearOwner(Mob mob) {
		writeOwner(mob, null);
	}

	private static UUID readOwnerUuid(Mob mob) {
		CustomData data = mob.get(DataComponents.CUSTOM_DATA);
		if (data == null || data.isEmpty()) {
			return null;
		}
		CompoundTag tag = data.copyTag();
		if (!tag.contains(OWNER_MOST) || !tag.contains(OWNER_LEAST)) {
			return null;
		}
		return new UUID(tag.getLong(OWNER_MOST).orElse(0L), tag.getLong(OWNER_LEAST).orElse(0L));
	}

	private static void writeOwner(Mob mob, UUID owner) {
		CustomData current = mob.get(DataComponents.CUSTOM_DATA);
		CompoundTag tag = (current == null || current.isEmpty()) ? new CompoundTag() : current.copyTag();
		if (owner == null) {
			tag.remove(OWNER_KEY);
			tag.remove(OWNER_MOST);
			tag.remove(OWNER_LEAST);
		} else {
			tag.putString(OWNER_KEY, owner.toString());
			tag.putLong(OWNER_MOST, owner.getMostSignificantBits());
			tag.putLong(OWNER_LEAST, owner.getLeastSignificantBits());
		}
		mob.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}
}
