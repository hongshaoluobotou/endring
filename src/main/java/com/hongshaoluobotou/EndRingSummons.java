package com.hongshaoluobotou;

import com.hongshaoluobotou.mixin.RabbitVariantAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * End Ring summon manager. As the ring's stored totems run low the ring bleeds the wearer's hp to
 * spawn vexes (frac &lt; 0.1 / 0.2), killer bunnies (frac &lt; 0.3 / 0.4) and zombies (frac &lt; 0.5 /
 * 0.6 / 0.7 / 0.8 / 0.9).
 *
 * <p>Each summoned mob is tagged with an owner UUID via {@link EndRingOwnedComponent} (stored in
 * the entity's per-instance CUSTOM_DATA NBT, so the marker survives chunk reloads and
 * {@code Mob.convertTo} conversions). Goals read the owner from NBT on every tick, so a
 * respawned player is still found.
 *
 * <p>Each summoned mob also gets:
 *
 * <ul>
 *   <li>{@link EndRingFollowOwnerGoal} at goal-priority 1 (trail the owner between fights; teleports
 *       when more than 12 blocks away, matching vanilla {@code TamableAnimal}).</li>
 *   <li>{@link MeleeAttackGoal} at goal-priority 4 (only the EVIL rabbit and the zombie need this;
 *       vexes already have a charge-attack goal).</li>
 *   <li>{@link EndRingAttackOwnerTargetGoal} at target-priority 0 (highest). Forces the mob's
 *       {@code target} to the priority-1-4 entity each tick, overriding any vanilla
 *       {@code NearestAttackableTargetGoal} the mob type may register.</li>
 * </ul>
 */
public final class EndRingSummons {
	// Per-tier damage accumulator. The ring "spends" hp at these rates; residue carries into the next
	// tick so a single big hit can trigger multiple tiers in one go.
	private static final Map<UUID, Float> PENDING_VEX_DAMAGE_LIGHT = new HashMap<>();
	private static final Map<UUID, Float> PENDING_VEX_DAMAGE = new HashMap<>();
	private static final Map<UUID, Float> PENDING_BUNNY_DAMAGE_LIGHT = new HashMap<>();
	private static final Map<UUID, Float> PENDING_BUNNY_DAMAGE = new HashMap<>();
	private static final Map<UUID, Float> PENDING_ZOMBIE_DAMAGE_FOUR = new HashMap<>();
	private static final Map<UUID, Float> PENDING_ZOMBIE_DAMAGE = new HashMap<>();

	// Auto-summon timer for the < 0.1 vex tier.
	private static final Map<UUID, Integer> VEX_AUTO_TIMER = new HashMap<>();
	private static final int VEX_AUTO_INTERVAL_TICKS = 200;

	// Per-player summon list. Used to count living summons, cap spawns, and clean up on ring
	// removal. Ownership is also tracked per-mob in NBT (EndRingOwnedComponent) so this list
	// only needs to handle live-runtime counting; persistence is automatic.
	private static final Map<UUID, List<LivingEntity>> SUMMONED = new HashMap<>();
	private static final java.util.function.Predicate<LivingEntity> ALIVE = e -> e != null && e.isAlive() && !e.isRemoved();

	// Priority 1 (most-recent damage source entity) and priority 2 (entity the player just attacked).
	private static final Map<UUID, LivingEntity> LAST_HURT_ENTITY = new HashMap<>();
	private static final Map<UUID, LivingEntity> PLAYER_ATTACK_TARGET = new HashMap<>();

	private EndRingSummons() {
	}

	/**
	 * Server-tick entry point. Drains damage accumulators, runs the 200-tick auto-spawn for
	 * {@code frac < 0.1} vexes, then runs the summon-list reaper so dead/converted entries are
	 * pruned. Target re-selection is handled by {@link EndRingAttackOwnerTargetGoal} in each mob's
	 * own AI tick.
	 */
	public static void onPlayerTick(ServerPlayer player) {
		ItemStack ring = EndRingItem.getWorn(player);
		if (ring.isEmpty()) {
			clearAll(player);
			return;
		}
		float frac = totemFraction(ring);
		UUID id = player.getUUID();

		if (frac < 0.1F) {
			int timer = VEX_AUTO_TIMER.getOrDefault(id, 0) + 1;
			if (timer >= VEX_AUTO_INTERVAL_TICKS) {
				spawnVexes(player, 3, 20);
				VEX_AUTO_TIMER.put(id, 0);
			} else {
				VEX_AUTO_TIMER.put(id, timer);
			}
		} else {
			VEX_AUTO_TIMER.remove(id);
		}

		consumePools(player, frac);
		prune(player);
	}

	/**
	 * Records that the player just took damage. {@code damageTaken} is the post-mitigation value the
	 * Fabric {@code AFTER_DAMAGE} event reports; this is the hp the player actually felt.
	 */
	public static void onDamaged(ServerPlayer player, float damageTaken) {
		if (damageTaken <= 0.0F) {
			return;
		}
		UUID id = player.getUUID();
		PENDING_VEX_DAMAGE_LIGHT.merge(id, damageTaken, Float::sum);
		PENDING_VEX_DAMAGE.merge(id, damageTaken, Float::sum);
		PENDING_BUNNY_DAMAGE_LIGHT.merge(id, damageTaken, Float::sum);
		PENDING_BUNNY_DAMAGE.merge(id, damageTaken, Float::sum);
		PENDING_ZOMBIE_DAMAGE_FOUR.merge(id, damageTaken, Float::sum);
		PENDING_ZOMBIE_DAMAGE.merge(id, damageTaken, Float::sum);
	}

	/** Ring-removed cleanup: despawn every mob the ring spawned. */
	public static void onRingRemoved(ServerPlayer player) {
		clearAll(player);
	}

	/** Called by the {@code AFTER_DAMAGE} hook with the entity that hurt the player. */
	public static void recordLastHurtBy(ServerPlayer player, LivingEntity attacker) {
		if (attacker == null) {
			return;
		}
		LAST_HURT_ENTITY.put(player.getUUID(), attacker);
	}

	/** Called by the {@code PlayerAttackRecordMixin} when the player attacks. */
	public static void recordPlayerAttack(ServerPlayer player, LivingEntity target) {
		if (target == null) {
			return;
		}
		PLAYER_ATTACK_TARGET.put(player.getUUID(), target);
	}

	/**
	 * Called by {@link EndRingAttackOwnerTargetGoal} from each summoned mob's own AI tick. Resolves
	 * the four-step priority ladder for the given mob's owner.
	 */
	public static LivingEntity resolveTargetFor(Mob summoned, LivingEntity owner) {
		UUID id = owner.getUUID();
		// (1) Damage source.
		LivingEntity priority = LAST_HURT_ENTITY.get(id);
		if (isValidTarget(priority, owner, summoned)) {
			return priority;
		}
		// (2) Owner attack target.
		LivingEntity attacked = PLAYER_ATTACK_TARGET.get(id);
		if (isValidTarget(attacked, owner, summoned)) {
			return attacked;
		}
		// (3) Mob that last hurt the owner.
		LivingEntity lastHurt = owner.getLastHurtByMob();
		if (isValidTarget(lastHurt, owner, summoned)) {
			return lastHurt;
		}
		// (4) Same-type non-player-owned entity of any of 1/2/3.
		LivingEntity sameType = findSameTypeOfPriority(summoned, owner, priority, attacked, lastHurt);
		if (isValidTarget(sameType, owner, summoned)) {
			return sameType;
		}
		return null;
	}

	private static LivingEntity findSameTypeOfPriority(Mob summoned, LivingEntity owner, LivingEntity... sources) {
		ServerLevel level = (ServerLevel) summoned.level();
		List<LivingEntity> owned = SUMMONED.getOrDefault(owner.getUUID(), List.of());
		EntityType<?> summonedType = summoned.getType();
		for (LivingEntity source : sources) {
			if (source == null) {
				continue;
			}
			EntityType<?> type = source.getType();
			List<LivingEntity> candidates = level.getEntitiesOfClass(
				LivingEntity.class,
				owner.getBoundingBox().inflate(64.0),
				e -> e != owner
					&& e.isAlive()
					&& !e.isRemoved()
					&& e.getType() == type
					&& e != source
					&& !owned.contains(e)
					&& e.getType() != summonedType
			);
			if (!candidates.isEmpty()) {
				LivingEntity best = null;
				double bestDist = Double.MAX_VALUE;
				for (LivingEntity c : candidates) {
					double d = c.distanceToSqr(owner);
					if (d < bestDist) {
						bestDist = d;
						best = c;
					}
				}
				return best;
			}
		}
		return null;
	}

	private static boolean isValidTarget(LivingEntity target, LivingEntity owner, Mob summoned) {
		if (target == null || !target.isAlive() || target.isRemoved()) {
			return false;
		}
		if (target == owner) {
			return false;
		}
		// Never target another owned summon.
		List<LivingEntity> owned = SUMMONED.getOrDefault(owner.getUUID(), List.of());
		if (owned.contains(target)) {
			return false;
		}
		return true;
	}

	// -----------------------------------------------------------------------
	// Per-tick consumption of damage pools
	// -----------------------------------------------------------------------

	private static void consumePools(ServerPlayer player, float frac) {
		UUID id = player.getUUID();

		if (frac < 0.1F) {
			spendPool(player, PENDING_VEX_DAMAGE_LIGHT, id, 1.0F, EndRingSummons::spawnVexes, 20);
		} else {
			PENDING_VEX_DAMAGE_LIGHT.remove(id);
		}

		if (frac < 0.2F) {
			spendPool(player, PENDING_VEX_DAMAGE, id, 1.5F, EndRingSummons::spawnVexes, 10);
		} else {
			PENDING_VEX_DAMAGE.remove(id);
		}

		if (frac < 0.3F) {
			spendPool(player, PENDING_BUNNY_DAMAGE_LIGHT, id, 1.5F, EndRingSummons::spawnKillerBunnies, 9);
		} else {
			PENDING_BUNNY_DAMAGE_LIGHT.remove(id);
		}

		if (frac < 0.4F) {
			spendPool(player, PENDING_BUNNY_DAMAGE, id, 2.0F, EndRingSummons::spawnKillerBunnies, 5);
		} else {
			PENDING_BUNNY_DAMAGE.remove(id);
		}

		if (frac < 0.5F) {
			float pool = PENDING_ZOMBIE_DAMAGE_FOUR.getOrDefault(id, 0.0F);
			if (pool >= 4.0F) {
				int spawns = (int) (pool / 4.0F);
				if (spawnZombies(player, spawns * 2, 5)) {
					pool -= spawns * 4.0F;
					PENDING_ZOMBIE_DAMAGE_FOUR.put(id, pool);
				}
			}
		} else {
			PENDING_ZOMBIE_DAMAGE_FOUR.remove(id);
		}

		// 0.6 / 0.7 / 0.8 / 0.9 zombie tiers all draw from the same PENDING_ZOMBIE_DAMAGE pool,
		// in order of cost. The cheapest active tier drains the pool first, then the next tier,
		// and so on. The pool is only cleared once the player crosses the strictest threshold
		// (0.9) - otherwise the frac < 0.6 else-branch would wipe the pool the moment the
		// player crossed 0.6 and the higher tiers would never fire.
		if (frac < 0.6F) {
			spendPool(player, PENDING_ZOMBIE_DAMAGE, id, 4.0F, EndRingSummons::spawnZombies, 3);
		}
		if (frac < 0.7F) {
			spendPool(player, PENDING_ZOMBIE_DAMAGE, id, 8.0F, EndRingSummons::spawnZombies, 3);
		}
		if (frac < 0.8F) {
			spendPool(player, PENDING_ZOMBIE_DAMAGE, id, 12.0F, EndRingSummons::spawnZombies, 3);
		}
		if (frac < 0.9F) {
			spendPool(player, PENDING_ZOMBIE_DAMAGE, id, 16.0F, EndRingSummons::spawnZombies, 3);
		}
		if (frac >= 0.9F) {
			PENDING_ZOMBIE_DAMAGE.remove(id);
		}
	}

	@FunctionalInterface
	private interface Spawner {
		boolean spawn(ServerPlayer player, int count, int cap);
	}

	private static void spendPool(ServerPlayer player, Map<UUID, Float> pool, UUID id, float costPerSpawn, Spawner spawner, int cap) {
		float current = pool.getOrDefault(id, 0.0F);
		if (current < costPerSpawn) {
			return;
		}
		int spawns = (int) (current / costPerSpawn);
		if (spawner.spawn(player, spawns, cap)) {
			pool.put(id, current - spawns * costPerSpawn);
		}
	}

	private static void clearAll(ServerPlayer player) {
		UUID id = player.getUUID();
		List<LivingEntity> list = SUMMONED.remove(id);
		if (list != null) {
			for (LivingEntity e : list) {
				if (e != null && e.isAlive() && !e.isRemoved()) {
					e.discard();
				}
			}
		}
		PENDING_VEX_DAMAGE_LIGHT.remove(id);
		PENDING_VEX_DAMAGE.remove(id);
		PENDING_BUNNY_DAMAGE_LIGHT.remove(id);
		PENDING_BUNNY_DAMAGE.remove(id);
		PENDING_ZOMBIE_DAMAGE_FOUR.remove(id);
		PENDING_ZOMBIE_DAMAGE.remove(id);
		VEX_AUTO_TIMER.remove(id);
		LAST_HURT_ENTITY.remove(id);
		PLAYER_ATTACK_TARGET.remove(id);
	}

	private static void prune(ServerPlayer player) {
		List<LivingEntity> list = SUMMONED.get(player.getUUID());
		if (list == null) {
			return;
		}
		list.removeIf(e -> !ALIVE.test(e) || !EndRingOwnedComponent.hasOwner((Mob) e) || EndRingOwnedComponent.getOwner((Mob) e) != player);
	}

	private static float totemFraction(ItemStack ring) {
		int max = EndRingItem.maxTotems(ring);
		return max <= 0 ? 0.0F : (float) EndRingItem.getTotems(ring) / max;
	}

	// -----------------------------------------------------------------------
	// Spawn helpers
	// -----------------------------------------------------------------------

	private static boolean spawnVexes(ServerPlayer player, int count, int cap) {
		return spawn(player, EntityTypes.VEX, count, cap, EndRingSummons::configureVex);
	}

	private static boolean spawnKillerBunnies(ServerPlayer player, int count, int cap) {
		return spawn(player, EntityTypes.RABBIT, count, cap, (level, mob) -> {
			// setVariant is private in vanilla; the accessor mixin exposes it. The EVIL variant
			// installs the MeleeAttackGoal, HurtByTargetGoal and Player/Wolf targeting in its
			// setVariant method, so the rabbit actually fights back.
			((RabbitVariantAccessor) mob).endring$setVariant(Rabbit.Variant.EVIL);
		});
	}

	private static boolean spawnZombies(ServerPlayer player, int count, int cap) {
		return spawn(player, EntityTypes.ZOMBIE, count, cap, (level, mob) -> {
		});
	}

	@FunctionalInterface
	private interface MobConfigurator {
		void configure(ServerLevel level, Mob mob);
	}

	private static <T extends Mob> boolean spawn(ServerPlayer player, EntityType<T> type, int count, int cap, MobConfigurator config) {
		if (count <= 0) {
			return true;
		}
		ServerLevel level = player.level();
		List<LivingEntity> list = SUMMONED.computeIfAbsent(player.getUUID(), k -> new ArrayList<>());
		list.removeIf(e -> !ALIVE.test(e));
		int free = cap - list.size();
		if (free <= 0) {
			return false;
		}
		int toSpawn = Math.min(count, free);
		BlockPos pos = player.blockPosition();
		BlockPos.MutableBlockPos spawnAt = new BlockPos.MutableBlockPos();
		for (int i = 0; i < toSpawn; i++) {
			spawnAt.set(pos.getX() + level.getRandom().nextInt(3) - 1, pos.getY() + 1, pos.getZ() + level.getRandom().nextInt(3) - 1);
			T mob = type.spawn(level, spawnAt, EntitySpawnReason.MOB_SUMMONED);
			if (mob == null) {
				continue;
			}
			config.configure(level, mob);
			installOwnedAi(mob, player);
			list.add(mob);
		}
		return true;
	}

	private static void configureVex(ServerLevel level, Mob mob) {
		if (mob instanceof Vex vex) {
			// setLimitedLife matches vanilla spell-summoned vexes: ~30s before the vex starves itself.
			vex.setLimitedLife(20 * 30);
		}
	}

	/**
	 * Replaces the mob's hostile-with-owner-player behaviour with End Ring AI:
	 *
	 * <ol>
	 *   <li>Tag the mob with the player as owner (NBT-backed).</li>
	 *   <li>Strip the vanilla target-selector entries that would acquire the owner (NearestAttackableTargetGoal on Player/Wolf for killer bunnies, target-on-angry for zombies, etc.) and HurtByTargetGoal so the mob doesn't auto-retaliate against the player.</li>
	 *   <li>Add the wolf-style follow goal at goal-priority 1.</li>
	 *   <li>Add a melee attack goal at priority 4 (zombie / killer bunny; vex already has its own).</li>
	 *   <li>Install our target-priority goal at target-priority 0 (highest).</li>
	 * </ol>
	 */
	public static void installOwnedAi(Mob mob, ServerPlayer owner) {
		EndRingOwnedComponent.setOwner(mob, owner);

		// Strip vanilla target-selector entries. The mob type's targeting was designed for the
		// hostile case and would otherwise acquire the owner as a target.
		// removeAllGoals(true) on a Predicate<Goal> is exposed publicly by GoalSelector.
		((com.hongshaoluobotou.mixin.MobTargetSelectorAccessor) mob).endring$targetSelector().removeAllGoals(g -> true);

		mob.getGoalSelector().addGoal(1, new EndRingFollowOwnerGoal(mob, 1.0, 10.0F, 2.0F));
		if (mob instanceof PathfinderMob pm && !(mob instanceof Vex)) {
			mob.getGoalSelector().addGoal(4, new MeleeAttackGoal(pm, 1.0, true));
		}

		// Target selector: our priority-ladder goal at the highest priority.
		((com.hongshaoluobotou.mixin.MobTargetSelectorAccessor) mob).endring$targetSelector().addGoal(0, new EndRingAttackOwnerTargetGoal(mob));
	}

	// -----------------------------------------------------------------------
	// Conversion (zombie -> drowned, villager -> zombie villager, etc.)
	// -----------------------------------------------------------------------

	/**
	 * Called by the {@code MobConvertMixin} after {@code Mob.convertTo} swaps the entity. The
	 * CUSTOM_DATA NBT (which carries the owner marker) is automatically copied by the entity's own
	 * save/load cycle, but in 26.2 {@code convertTo} does not copy entity data. We re-tag the fresh
	 * entity with the old entity's owner and re-install the AI goals.
	 */
	public static void onConverted(Mob old, Mob fresh) {
		if (old == null || fresh == null || old == fresh) {
			return;
		}
		UUID ownerId = null;
		for (Map.Entry<UUID, List<LivingEntity>> entry : SUMMONED.entrySet()) {
			if (entry.getValue().remove(old)) {
				entry.getValue().add(fresh);
				ownerId = entry.getKey();
				break;
			}
		}
		if (ownerId == null) {
			return;
		}
		if (!(fresh.level() instanceof ServerLevel level)) {
			return;
		}
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
		if (owner == null) {
			return;
		}
		// NBT persistence: write the owner UUID directly into the new entity's CUSTOM_DATA so
		// the goal's getOwner() lookup succeeds even if EndRingOwnedComponent.setOwner's
		// entity-data path is interrupted by the conversion. We re-use the same key shape.
		copyOwnerNbt(old, fresh);
		installOwnedAi(fresh, owner);
	}

	private static void copyOwnerNbt(Mob old, Mob fresh) {
		net.minecraft.world.item.component.CustomData oldData = old.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
		if (oldData == null || oldData.isEmpty()) {
			return;
		}
		net.minecraft.nbt.CompoundTag freshTag = fresh.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
		net.minecraft.nbt.CompoundTag oldTag = oldData.copyTag();
		for (String key : new String[]{"EndRingOwner", "EndRingOwnerMost", "EndRingOwnerLeast"}) {
			if (oldTag.contains(key)) {
				if (oldTag.get(key) instanceof net.minecraft.nbt.LongTag lt) {
					freshTag.putLong(key, lt.longValue());
				} else if (oldTag.get(key) instanceof net.minecraft.nbt.StringTag st) {
					freshTag.putString(key, st.value());
				}
			}
		}
		fresh.setComponent(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(freshTag));
	}
}
