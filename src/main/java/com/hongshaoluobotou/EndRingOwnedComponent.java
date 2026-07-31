package com.hongshaoluobotou;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.component.CustomData;

/**
 * Marks a mob as owned by an End Ring wearer. The owner UUID is stored in the entity's per-instance
 * {@link DataComponents#CUSTOM_DATA} NBT under the key {@code EndRingOwner}, encoded as an
 * {@code int[4]} the same way vanilla's {@code EntityReference} writes its owner (see
 * {@code EntityReference.store} in 26.2). The shape matches what {@code TamableAnimal} writes for
 * its {@code Owner} key, so the value is interchangeable with vanilla should we ever extend that
 * class instead.
 *
 * <p>Because the marker is part of the entity's own saved data, it survives server restarts, chunk
 * reloads and {@code Mob.convertTo} conversions automatically. AI behaviour is driven by mixins
 * that check {@link #hasOwner(Mob)} every tick, so the moment the marker is back in place the mob
 * resumes End Ring behaviour without any explicit "reinstall" step.
 */
public final class EndRingOwnedComponent {
	private static final String OWNER_KEY = "EndRingOwner";

	private EndRingOwnedComponent() {
	}

	/** True if the mob carries an End Ring owner marker. */
	public static boolean hasOwner(Mob mob) {
		return readOwnerUuid(mob) != null;
	}

	/**
	 * Resolves the owner UUID stamped on the mob, or {@code null} if the mob has no marker. Cheap
	 * (one NBT read, no level / server lookup), safe to call from hot mixin paths. Used by the
	 * targeting mixin to check "is this target the summoner's owner" without going through
	 * {@link #getOwner} (which would force a player lookup and resolve a specific player instance,
	 * including the wearer's mob if it is the only one online).
	 */
	public static UUID getOwnerUuid(Mob mob) {
		return readOwnerUuid(mob);
	}

	/**
	 * Resolves the owner as a living entity. Returns {@code null} if the mob has no owner marker,
	 * the owner is offline, or the owner isn't loaded.
	 */
	public static LivingEntity getOwner(Mob mob) {
		UUID id = readOwnerUuid(mob);
		if (id == null) {
			return null;
		}
		if (!(mob.level() instanceof ServerLevel level)) {
			return null;
		}
		return level.getServer().getPlayerList().getPlayer(id);
	}

	/** Writes the owner marker. {@code null} clears the marker. */
	public static void setOwner(Mob mob, LivingEntity owner) {
		if (owner == null) {
			clearOwner(mob);
			return;
		}
		writeOwner(mob, owner.getUUID());
	}

	/** Removes the owner marker, leaving the rest of the custom data intact. */
	public static void clearOwner(Mob mob) {
		CompoundTag tag = currentTag(mob);
		if (tag.contains(OWNER_KEY)) {
			tag.remove(OWNER_KEY);
			mob.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		}
	}

	/**
	 * True if both mobs carry an End Ring owner marker and the two owners have the same UUID. Used
	 * by the targeting mixins to block an owned summon from acquiring another owned summon (even
	 * one of a different type, e.g. a zombie targeting a vex) as its target.
	 */
	public static boolean isAllyOf(Mob self, Mob other) {
		if (self == null || other == null) {
			return false;
		}
		UUID a = readOwnerUuid(self);
		UUID b = readOwnerUuid(other);
		return a != null && a.equals(b);
	}

	private static UUID readOwnerUuid(Mob mob) {
		CustomData data = mob.get(DataComponents.CUSTOM_DATA);
		if (data == null || data.isEmpty()) {
			return null;
		}
		int[] arr = data.copyTag().getIntArray(OWNER_KEY).orElse(null);
		if (arr == null || arr.length != 4) {
			return null;
		}
		return UUIDUtil.uuidFromIntArray(arr);
	}

	private static void writeOwner(Mob mob, UUID owner) {
		CompoundTag tag = currentTag(mob);
		tag.putIntArray(OWNER_KEY, UUIDUtil.uuidToIntArray(owner));
		mob.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	private static CompoundTag currentTag(Mob mob) {
		CustomData data = mob.get(DataComponents.CUSTOM_DATA);
		return (data == null || data.isEmpty()) ? new CompoundTag() : data.copyTag();
	}
}
