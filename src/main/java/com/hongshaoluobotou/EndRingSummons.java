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
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * End Ring summon manager. As the ring's stored totems run low the ring bleeds the wearer's hp to
 * spawn vexes (frac &lt; 0.1 / 0.2 / 0.3), killer bunnies (frac &lt; 0.3 / 0.4), and zombies
 * (frac &lt; 0.5 / 0.6 / 0.7 / 0.8 / 0.9). At any frac, every pool that passes its threshold can
 * fire in parallel, so a low-frac player can have a mixed swarm of vexes + bunnies + zombies
 * simultaneously, capped per-mob-type.
 *
 * <p>Per-mob-type caps scale with frac: the lower the ring's totems, the more of each mob type
 * we let pile up, so the swarm actually gets dense right when the player needs it most. Cost
 * per spawn also drops with frac (cheaper pools at lower frac), so less hp is needed to spawn
 * each unit.
 *
 * <p>Each spawned mob is tagged with the owner's UUID via {@link EndRingOwnedComponent}. The
 * marker is written in {@link #spawn} after the entity is constructed; from that point on the
 * mixins in {@code mixin/} take over:
 *
 * <ul>
 *   <li>{@code MobTargetingMixin} blocks the mob from targeting the ring wearer or another owned
 *       summon, both via {@code canAttack} (used by {@code TargetingConditions}) and via
 *       {@code setTarget} (the backstop that catches any code path that bypasses
 *       {@code canAttack}).</li>
 *   <li>{@code MobAiStepMixin} runs every five ticks and (re)asserts the priority target the mob
 *       should chase, and walks the mob back toward the owner if it has strayed more than 12
 *       blocks.</li>
 *   <li>{@code MobConvertMixin} carries the owner marker across {@code Mob.convertTo} so a zombie
 *       that becomes a drowned (or any other conversion) keeps serving the ring wearer.</li>
 * </ul>
 *
 * <p>This class no longer manipulates goal selectors - all AI injection is via mixins.
 */
public final class EndRingSummons {
	// Per-tier damage accumulator. The ring "spends" hp at these rates; residue carries into the
	// next tick so a single big hit can trigger multiple tiers in one go.
	private static final Map<UUID, Float> PENDING_VEX_DAMAGE_ULTRA_LIGHT = new HashMap<>();
	private static final Map<UUID, Float> PENDING_VEX_DAMAGE_LIGHT = new HashMap<>();
	private static final Map<UUID, Float> PENDING_VEX_DAMAGE = new HashMap<>();
	private static final Map<UUID, Float> PENDING_BUNNY_DAMAGE_LIGHT = new HashMap<>();
	private static final Map<UUID, Float> PENDING_BUNNY_DAMAGE = new HashMap<>();
	private static final Map<UUID, Float> PENDING_ZOMBIE_DAMAGE_FOUR = new HashMap<>();
	private static final Map<UUID, Float> PENDING_ZOMBIE_DAMAGE = new HashMap<>();

	// Auto-summon timer for the < 0.1 vex tier.
	private static final Map<UUID, Integer> VEX_AUTO_TIMER = new HashMap<>();
	private static final int VEX_AUTO_INTERVAL_TICKS = 200;

	// Per-player summon list. Used only for cap counting and cleanup on ring removal. The owner
	// marker is stored per-mob in NBT (EndRingOwnedComponent) so this list is not the source of
	// truth for "who owns what" - the mixins read NBT directly. The list is kept so we can
	// cheaply check "is this mob in the swarm for player X" and so we can despawn the swarm
	// when the player takes the ring off without a global scan.
	private static final Map<UUID, List<LivingEntity>> SUMMONED = new HashMap<>();

	/**
	 * A mob is "counted" (i.e. held against the cap and eligible for cleanup) only when it is in
	 * a currently-loaded chunk of any server level. The entity is otherwise alive - it has just
	 * been deserialized out of memory when its chunk unloaded - but our SUMMONED list still holds
	 * the reference and the NBT marker is preserved. We use {@code level.getEntity(id)} to test
	 * membership in the live entity set: chunk-unloaded entities return null.
	 */
	private static final java.util.function.Predicate<LivingEntity> LOADED = e -> {
		if (e == null || e.isRemoved() || !e.isAlive()) {
			return false;
		}
		net.minecraft.world.level.Level level = e.level();
		if (level == null) {
			return false;
		}
		return level.getEntity(e.getId()) == e;
	};

	// Priority 1 (most-recent damage source entity) and priority 2 (entity the player just
	// attacked). Read by resolveTargetFor.
	private static final Map<UUID, LivingEntity> LAST_HURT_ENTITY = new HashMap<>();
	private static final Map<UUID, LivingEntity> PLAYER_ATTACK_TARGET = new HashMap<>();

	private EndRingSummons() {
	}

	/**
	 * Server-tick entry point. Drains damage accumulators, runs the 200-tick auto-spawn for
	 * {@code frac < 0.1} vexes, and prunes the SUMMONED list of dead / chunk-unloaded entries.
	 * Targeting and follow behaviour is driven by the mixins in {@code mixin/} - we don't touch
	 * goal selectors here.
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
				spawnVexes(player, 3, capForFrac(VEX_CAPS, VEX_CAPS_VALUES, frac));
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
	 * Records that the player just took damage. {@code damageTaken} is the post-mitigation value
	 * the Fabric {@code AFTER_DAMAGE} event reports; this is the hp the player actually felt.
	 */
	public static void onDamaged(ServerPlayer player, float damageTaken) {
		if (damageTaken <= 0.0F) {
			return;
		}
		UUID id = player.getUUID();
		PENDING_VEX_DAMAGE_ULTRA_LIGHT.merge(id, damageTaken, Float::sum);
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
	 * Called by the {@code MobAiStepMixin} from each owned mob's AI tick. Resolves the four-step
	 * priority ladder for the given mob's owner:
	 *
	 * <ol>
	 *   <li>The entity that most recently hurt the player ({@code LAST_HURT_ENTITY}).</li>
	 *   <li>The entity the player last attacked ({@code PLAYER_ATTACK_TARGET}).</li>
	 *   <li>The entity the player was last hurt by (vanilla {@code getLastHurtByMob}).</li>
	 *   <li>The nearest entity of the same type as any of 1/2/3 within 64 blocks of the owner
	 *       (skips the original 1/2/3 entity itself and any End Ring summon).</li>
	 * </ol>
	 *
	 * <p>Returns {@code null} if no valid target exists - the calling mixin writes that as
	 * {@code setTarget(null)} which clears the existing target.
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

		// Each pool is a separate channel with its own frac gate, cost-per-spawn, and per-mob-type
		// cap. Multiple pools can fire in the same tick - e.g. at frac=0.25 the light vex, the
		// heavy vex, and the light bunny pools all drain in parallel, so the player can end up
		// with vexes and killer bunnies side-by-side. Per-mob-type caps scale with frac: the
		// lower the ring's totems, the more of each mob type we let pile up, so the swarm
		// actually gets dense right when the player needs it most.

		int vexCap = capForFrac(VEX_CAPS, VEX_CAPS_VALUES, frac);
		int bunnyCap = capForFrac(BUNNY_CAPS, BUNNY_CAPS_VALUES, frac);
		int zombieCap = capForFrac(ZOMBIE_CAPS, ZOMBIE_CAPS_VALUES, frac);

		if (frac < 0.1F) {
			spendPool(player, PENDING_VEX_DAMAGE_ULTRA_LIGHT, id, 0.3F, spawns -> spawnVexes(player, spawns, vexCap));
		} else {
			PENDING_VEX_DAMAGE_ULTRA_LIGHT.remove(id);
		}

		if (frac < 0.2F) {
			spendPool(player, PENDING_VEX_DAMAGE_LIGHT, id, 0.7F, spawns -> spawnVexes(player, spawns, vexCap));
		} else {
			PENDING_VEX_DAMAGE_LIGHT.remove(id);
		}

		if (frac < 0.3F) {
			spendPool(player, PENDING_VEX_DAMAGE, id, 1.5F, spawns -> spawnVexes(player, spawns, vexCap));
		} else {
			PENDING_VEX_DAMAGE.remove(id);
		}

		if (frac < 0.3F) {
			spendPool(player, PENDING_BUNNY_DAMAGE_LIGHT, id, 1.5F, spawns -> spawnKillerBunnies(player, spawns, bunnyCap));
		} else {
			PENDING_BUNNY_DAMAGE_LIGHT.remove(id);
		}

		if (frac < 0.4F) {
			spendPool(player, PENDING_BUNNY_DAMAGE, id, 2.0F, spawns -> spawnKillerBunnies(player, spawns, bunnyCap));
		} else {
			PENDING_BUNNY_DAMAGE.remove(id);
		}

		if (frac < 0.5F) {
			spendPool(player, PENDING_ZOMBIE_DAMAGE_FOUR, id, 4.0F, spawns -> spawnZombies(player, spawns * 2, zombieCap));
		} else {
			PENDING_ZOMBIE_DAMAGE_FOUR.remove(id);
		}

		// Zombie hp tiers 0.6 / 0.7 / 0.8 / 0.9 all draw from the same PENDING_ZOMBIE_DAMAGE
		// pool, cheapest first. The pool is only cleared once the player crosses 0.9 - otherwise
		// the frac < 0.6 else-branch would wipe the pool the moment the player crossed 0.6 and
		// the higher tiers would never fire.
		if (frac < 0.6F) {
			spendPool(player, PENDING_ZOMBIE_DAMAGE, id, 4.0F, spawns -> spawnZombies(player, spawns, zombieCap));
		}
		if (frac < 0.7F) {
			spendPool(player, PENDING_ZOMBIE_DAMAGE, id, 8.0F, spawns -> spawnZombies(player, spawns, zombieCap));
		}
		if (frac < 0.8F) {
			spendPool(player, PENDING_ZOMBIE_DAMAGE, id, 12.0F, spawns -> spawnZombies(player, spawns, zombieCap));
		}
		if (frac < 0.9F) {
			spendPool(player, PENDING_ZOMBIE_DAMAGE, id, 16.0F, spawns -> spawnZombies(player, spawns, zombieCap));
		}
		if (frac >= 0.9F) {
			PENDING_ZOMBIE_DAMAGE.remove(id);
		}
	}

	/**
	 * Per-mob-type cap ladder. The first threshold &le; frac gives the cap. The tables are
	 * ordered most-permissive (lowest frac) first. The cap is the *total* for that mob type
	 * across every tier that fires at that frac - so a player at frac=0.05 can hold up to 60
	 * vexes combined across all three vex tiers, while a player at frac=0.45 only gets 18.
	 */
	private static final float[] VEX_CAPS = {0.1F, 0.2F, 0.3F, 0.5F, 1.0F};
	private static final int[] VEX_CAPS_VALUES = {60, 48, 36, 18, 0};
	private static final float[] BUNNY_CAPS = {0.3F, 0.4F, 0.5F, 1.0F};
	private static final int[] BUNNY_CAPS_VALUES = {45, 27, 9, 0};
	private static final float[] ZOMBIE_CAPS = {0.5F, 0.6F, 0.8F, 1.0F};
	private static final int[] ZOMBIE_CAPS_VALUES = {24, 12, 6, 0};

	private static int capForFrac(float[] thresholds, int[] values, float frac) {
		for (int i = 0; i < thresholds.length; i++) {
			if (frac < thresholds[i]) {
				return values[i];
			}
		}
		return 0;
	}

	private static void spendPool(ServerPlayer player, Map<UUID, Float> pool, UUID id, float costPerSpawn, java.util.function.IntFunction<Boolean> spawnFn) {
		float current = pool.getOrDefault(id, 0.0F);
		if (current < costPerSpawn) {
			return;
		}
		int spawns = (int) (current / costPerSpawn);
		if (spawnFn.apply(spawns)) {
			pool.put(id, current - spawns * costPerSpawn);
		}
	}

	private static void clearAll(ServerPlayer player) {
		UUID id = player.getUUID();
		List<LivingEntity> list = SUMMONED.remove(id);
		if (list != null) {
			for (LivingEntity e : list) {
				// Only call discard() on entities that are still loaded in some level - chunk
				// unload has already detached them and the entity object is no longer
				// attached to any world.
				if (LOADED.test(e)) {
					if (e instanceof Mob m) {
						EndRingOwnedComponent.clearOwner(m);
					}
					e.discard();
				}
			}
		}
		PENDING_VEX_DAMAGE_ULTRA_LIGHT.remove(id);
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
		list.removeIf(e -> !LOADED.test(e));
	}

	/**
	 * Called by {@code MobConvertMixin} after {@code Mob.convertTo} swaps the entity. Swaps the
	 * SUMMONED list entry from old to fresh and clears the owner marker on the old (now detached)
	 * instance so a stale reference can't keep ticking AI for a mob that's been replaced.
	 */
	public static void onConverted(Mob old, Mob fresh) {
		if (old == null || fresh == null || old == fresh) {
			return;
		}
		for (List<LivingEntity> entry : SUMMONED.values()) {
			if (entry.remove(old)) {
				entry.add(fresh);
				break;
			}
		}
		EndRingOwnedComponent.clearOwner(old);
	}

	private static float totemFraction(ItemStack ring) {
		int max = EndRingItem.maxTotems(ring);
		return max <= 0 ? 0.0F : (float) EndRingItem.getTotems(ring) / max;
	}

	// -----------------------------------------------------------------------
	// Spawn helpers
	// -----------------------------------------------------------------------

	private static boolean spawnVexes(ServerPlayer player, int count, int cap) {
		return spawn(player, EntityTypes.VEX, Vex.class, count, cap, EndRingSummons::configureVex, VEX_POLICY);
	}

	private static boolean spawnKillerBunnies(ServerPlayer player, int count, int cap) {
		return spawn(player, EntityTypes.RABBIT, Rabbit.class, count, cap, (level, mob) -> {
			// setVariant is private in vanilla; the accessor mixin exposes it. The EVIL variant
			// installs MeleeAttackGoal + HurtByTargetGoal + NearestAttackableTargetGoal<Player/Wolf>
			// in its setVariant method, so the rabbit actually fights back. The targeting mixin
			// then blocks the auto-acquired Player target.
			((RabbitVariantAccessor) mob).endring$setVariant(Rabbit.Variant.EVIL);
		}, RABBIT_POLICY);
	}

	private static boolean spawnZombies(ServerPlayer player, int count, int cap) {
		return spawn(player, EntityTypes.ZOMBIE, Zombie.class, count, cap, (level, mob) -> {
		}, ZOMBIE_POLICY);
	}

	// Vexes fly, so the "is there a solid floor under their feet" check is irrelevant - they hover
	// independently of the block below. We only need feet+head to not be jammed by a solid.
	private static final SpawnPolicy VEX_POLICY = new SpawnPolicy(false, false);
	// Rabbits are 0.5 blocks tall and have a small hitbox; they fit anywhere a 1-block-tall pocket
	// has air at foot level and a solid below. The head clearance check is unnecessary.
	private static final SpawnPolicy RABBIT_POLICY = new SpawnPolicy(true, true);
	// Zombies are 1.95 blocks tall and need the full 2-block clearance plus a solid floor.
	private static final SpawnPolicy ZOMBIE_POLICY = new SpawnPolicy(true, false);

	@FunctionalInterface
	private interface MobConfigurator {
		void configure(ServerLevel level, Mob mob);
	}

	private record SpawnPolicy(boolean needsSolidFloor, boolean oneBlockTall) {
	}

	private static <T extends Mob> boolean spawn(ServerPlayer player, EntityType<T> type, Class<T> typeClass, int count, int typeCap, MobConfigurator config, SpawnPolicy policy) {
		if (count <= 0) {
			return true;
		}
		ServerLevel level = player.level();
		List<LivingEntity> list = SUMMONED.computeIfAbsent(player.getUUID(), k -> new ArrayList<>());
		list.removeIf(e -> !LOADED.test(e));
		int ofType = 0;
		for (LivingEntity e : list) {
			if (typeClass.isInstance(e)) {
				ofType++;
			}
		}
		int free = typeCap - ofType;
		if (free <= 0) {
			return false;
		}
		int toSpawn = Math.min(count, free);
		BlockPos pos = player.blockPosition();
		for (int i = 0; i < toSpawn; i++) {
			BlockPos spawnAt = findSafeSpawn(level, pos, policy);
			T mob = type.spawn(level, spawnAt.immutable(), EntitySpawnReason.MOB_SUMMONED);
			if (mob == null) {
				continue;
			}
			config.configure(level, mob);
			// Tag the mob with the owner UUID. From the next aiStep onwards the targeting and
			// follow mixins in mixin/ take over - no goal selector rewriting is done.
			EndRingOwnedComponent.setOwner(mob, player);
			list.add(mob);
		}
		return true;
	}

	/**
	 * Try to place the mob in a sane pocket of space.
	 * <ul>
	 *   <li>Vex (no safety): just drop it one block above the player; flying mobs can sort
	 *       themselves out of walls on their own.</li>
	 *   <li>Otherwise scan the 3x3 around the player (player cell first) for a spot whose
	 *       feet block is air or a source fluid, whose head block is air or a source fluid
	 *       (or skip the head check for one-block-tall mobs like rabbits), and which has a
	 *       solid floor underneath (when the policy requires a solid floor).</li>
	 *   <li>Two distinct degenerate cases get dedicated fallbacks rather than the
	 *       "spawn at player pos" crutch:
	 *     <ul>
	 *       <li>If every scanned cell fails because the <em>floor is air/water</em>
	 *           (i.e. the player is standing over open air / water with nothing to
	 *           stand on nearby) the caller is floating in featureless space; pick a
	 *           random cell in the 3x3 and place the mob there regardless of floor.</li>
	 *       <li>If every scanned cell fails because the <em>feet cell is already
	 *           occupied</em> by a solid block, the player is boxed in; fall back to
	 *           the player's own position (feet+1) so the summon still appears on
	 *           the player rather than vanishing.</li>
	 *     </ul>
	 *   </li>
	 * </ul>
	 */
	private static BlockPos findSafeSpawn(ServerLevel level, BlockPos playerPos, SpawnPolicy policy) {
		BlockPos headHeight = new BlockPos(playerPos.getX(), playerPos.getY(), playerPos.getZ());
		if (!policy.needsSolidFloor()) {
			// Vex - no safety check whatsoever; park it one block above the player.
			return headHeight;
		}
		int[][] deltas = new int[25][];
		int idx = 0;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				deltas[idx++] = new int[]{dx, dz};
			}
		}
		boolean anyFeetBlocked = false;
		boolean anyFloorOpen = false;
		for (int[] d : deltas) {
			int x = playerPos.getX() + d[0];
			int z = playerPos.getZ() + d[1];
			int y = playerPos.getY() + 1;
			SpotResult r = classifySpot(level, x, y, z, policy);
			if (r == SpotResult.OK) {
				return new BlockPos(x, y, z);
			}
			if (r == SpotResult.FEET_BLOCKED) {
				anyFeetBlocked = true;
			} else if (r == SpotResult.FLOOR_OPEN) {
				anyFloorOpen = true;
			}
		}
		// If every candidate failed because there was nowhere to stand (floor open everywhere),
		// random-pick a cell in the ring and drop the mob there. The Y is also jittered inside a
		// small band around the player's head so the swarm spreads out vertically instead of
		// stacking on the same slab. The cell's feet block must still be passable - otherwise the
		// mob would be wedged inside a wall - so we re-roll until we find a passable pick. If no
		// passable random pick can be found, fall back to the player's own coordinates so the
		// summon still appears on the player rather than vanishing.
		if (!anyFeetBlocked && anyFloorOpen) {
			BlockPos fallback = headHeight;
			for (int attempt = 0; attempt < 8; attempt++) {
				int[] pick = deltas[level.getRandom().nextInt(deltas.length)];
				int x = playerPos.getX() + pick[0];
				int z = playerPos.getZ() + pick[1];
				int yOffset = level.getRandom().nextInt(3) - 1; // -1, 0, +1
				int y = playerPos.getY() + 1 + yOffset;
				if (isPassable(level, new BlockPos(x, y, z))) {
					return new BlockPos(x, y, z);
				}
			}
			return fallback;
		}
		// Otherwise (some feet were blocked, possibly mixed) spawn on the player so the
		// summon still materialises rather than silently disappearing.
		return headHeight;
	}

	private enum SpotResult {
		OK,
		FEET_BLOCKED,
		FLOOR_OPEN
	}

	private static SpotResult classifySpot(ServerLevel level, int x, int y, int z, SpawnPolicy policy) {
		BlockPos feet = new BlockPos(x, y, z);
		if (!isPassable(level, feet)) {
			return SpotResult.FEET_BLOCKED;
		}
		if (!policy.oneBlockTall()) {
			BlockPos head = new BlockPos(x, y + 1, z);
			if (!isPassable(level, head)) {
				return SpotResult.FEET_BLOCKED;
			}
		}
		if (policy.needsSolidFloor()) {
			BlockPos floor = new BlockPos(x, y - 1, z);
			BlockState floorState = level.getBlockState(floor);
			if (floorState.isAir() || !floorState.blocksMotion()) {
				return SpotResult.FLOOR_OPEN;
			}
		}
		return SpotResult.OK;
	}

	private static boolean isPassable(ServerLevel level, BlockPos pos) {
		if (level.getFluidState(pos).isSource()) {
			return true;
		}
		return level.getBlockState(pos).isAir();
	}

	private static void configureVex(ServerLevel level, Mob mob) {
		if (mob instanceof Vex vex) {
			// setLimitedLife matches vanilla spell-summoned vexes: ~30s before the vex starves
			// itself.
			vex.setLimitedLife(20 * 30);
		}
	}

	// -----------------------------------------------------------------------
	// Internal-only constants exposed for testing
	// -----------------------------------------------------------------------

	static int summonCount(ServerPlayer player) {
		List<LivingEntity> list = SUMMONED.get(player.getUUID());
		return list == null ? 0 : (int) list.stream().filter(LOADED).count();
	}

	static boolean hasLiveSummonOf(ServerPlayer player, Class<? extends Mob> type) {
		List<LivingEntity> list = SUMMONED.get(player.getUUID());
		if (list == null) {
			return false;
		}
		return list.stream().anyMatch(e -> LOADED.test(e) && type.isInstance(e));
	}
}
