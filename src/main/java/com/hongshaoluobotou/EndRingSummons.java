package com.hongshaoluobotou;

import com.hongshaoluobotou.mixin.RabbitVariantAccessor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

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
	private static final Map<UUID, Float> PENDING_SILVERFISH_DAMAGE_ULTRA_LIGHT = new HashMap<>();
	private static final Map<UUID, Float> PENDING_SILVERFISH_DAMAGE_LIGHT = new HashMap<>();
	private static final Map<UUID, Float> PENDING_SILVERFISH_DAMAGE = new HashMap<>();
	private static final Map<UUID, Float> PENDING_SKELETON_DAMAGE_ULTRA_LIGHT = new HashMap<>();
	private static final Map<UUID, Float> PENDING_SKELETON_DAMAGE_LIGHT = new HashMap<>();
	private static final Map<UUID, Float> PENDING_SKELETON_DAMAGE = new HashMap<>();
	private static final Map<UUID, Float> PENDING_WITCH_DAMAGE_ULTRA_LIGHT = new HashMap<>();
	private static final Map<UUID, Float> PENDING_WITCH_DAMAGE_LIGHT = new HashMap<>();
	private static final Map<UUID, Float> PENDING_WITCH_DAMAGE = new HashMap<>();
	private static final Map<UUID, Float> PENDING_PILLAGER_DAMAGE_ULTRA_LIGHT = new HashMap<>();
	private static final Map<UUID, Float> PENDING_PILLAGER_DAMAGE_LIGHT = new HashMap<>();
	private static final Map<UUID, Float> PENDING_PILLAGER_DAMAGE = new HashMap<>();
	private static final Map<UUID, Float> PENDING_RAVAGER_DAMAGE_ULTRA_LIGHT = new HashMap<>();
	private static final Map<UUID, Float> PENDING_RAVAGER_DAMAGE_LIGHT = new HashMap<>();
	private static final Map<UUID, Float> PENDING_RAVAGER_DAMAGE = new HashMap<>();
	private static final Map<UUID, Float> PENDING_WITHER_SKELETON_DAMAGE_ULTRA_LIGHT = new HashMap<>();
	private static final Map<UUID, Float> PENDING_WITHER_SKELETON_DAMAGE_LIGHT = new HashMap<>();
	private static final Map<UUID, Float> PENDING_WITHER_SKELETON_DAMAGE = new HashMap<>();

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

	// Queued spawns: damage pools (and the auto-spawn timer) record spawns here instead of
	// generating entities directly. drainPendingSpawns() flushes up to PER_TICK_BUDGET jobs per
	// tick, which keeps a single big-hit frame from cramming a dozen vexes + bunnies + zombies
	// into the player's pocket and crushing them with cramming damage. The cap check still runs
	// at drain time using the player's *current* cap, so a cap-shrink that happens while jobs
	// are queued simply trims the overflow off the tail.
	private static final Map<UUID, Deque<SpawnJob>> PENDING_SPAWNS = new HashMap<>();
	private static final int PER_TICK_BUDGET = 3;

	private enum SpawnType {
		VEX(EntityTypes.VEX, Vex.class),
		BUNNY(EntityTypes.RABBIT, Rabbit.class),
		ZOMBIE(EntityTypes.ZOMBIE, Zombie.class),
		SILVERFISH(EntityTypes.SILVERFISH, Silverfish.class),
		SKELETON(EntityTypes.SKELETON, Skeleton.class),
		WITCH(EntityTypes.WITCH, Witch.class),
		PILLAGER(EntityTypes.PILLAGER, Pillager.class),
		RAVAGER(EntityTypes.RAVAGER, Ravager.class),
		WITHER_SKELETON(EntityTypes.WITHER_SKELETON, WitherSkeleton.class);

		final EntityType<? extends Mob> entityType;
		final Class<? extends Mob> typeClass;

		SpawnType(EntityType<? extends Mob> entityType, Class<? extends Mob> typeClass) {
			this.entityType = entityType;
			this.typeClass = typeClass;
		}
	}

	private static final class SpawnJob {
		final SpawnType type;
		int remaining;

		SpawnJob(SpawnType type, int count) {
			this.type = type;
			this.remaining = count;
		}
	}

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
				enqueueSpawn(player, SpawnType.VEX, 3);
				VEX_AUTO_TIMER.put(id, 0);
			} else {
				VEX_AUTO_TIMER.put(id, timer);
			}
		} else {
			VEX_AUTO_TIMER.remove(id);
		}

		consumePools(player, frac);
		drainPendingSpawns(player, frac);
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
		PENDING_SILVERFISH_DAMAGE_ULTRA_LIGHT.merge(id, damageTaken, Float::sum);
		PENDING_SILVERFISH_DAMAGE_LIGHT.merge(id, damageTaken, Float::sum);
		PENDING_SILVERFISH_DAMAGE.merge(id, damageTaken, Float::sum);
		PENDING_SKELETON_DAMAGE_ULTRA_LIGHT.merge(id, damageTaken, Float::sum);
		PENDING_SKELETON_DAMAGE_LIGHT.merge(id, damageTaken, Float::sum);
		PENDING_SKELETON_DAMAGE.merge(id, damageTaken, Float::sum);
		PENDING_WITCH_DAMAGE_ULTRA_LIGHT.merge(id, damageTaken, Float::sum);
		PENDING_WITCH_DAMAGE_LIGHT.merge(id, damageTaken, Float::sum);
		PENDING_WITCH_DAMAGE.merge(id, damageTaken, Float::sum);
		PENDING_PILLAGER_DAMAGE_ULTRA_LIGHT.merge(id, damageTaken, Float::sum);
		PENDING_PILLAGER_DAMAGE_LIGHT.merge(id, damageTaken, Float::sum);
		PENDING_PILLAGER_DAMAGE.merge(id, damageTaken, Float::sum);
		PENDING_RAVAGER_DAMAGE_ULTRA_LIGHT.merge(id, damageTaken, Float::sum);
		PENDING_RAVAGER_DAMAGE_LIGHT.merge(id, damageTaken, Float::sum);
		PENDING_RAVAGER_DAMAGE.merge(id, damageTaken, Float::sum);
		PENDING_WITHER_SKELETON_DAMAGE_ULTRA_LIGHT.merge(id, damageTaken, Float::sum);
		PENDING_WITHER_SKELETON_DAMAGE_LIGHT.merge(id, damageTaken, Float::sum);
		PENDING_WITHER_SKELETON_DAMAGE.merge(id, damageTaken, Float::sum);
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

		// Each pool is a separate channel with its own frac gate and cost-per-spawn. Multiple
		// pools can fire in the same tick - e.g. at frac=0.25 the light vex, the heavy vex, and
		// the light bunny pools all drain in parallel, so the player can end up with vexes and
		// killer bunnies side-by-side. We don't apply the per-mob-type cap here; the cap is
		// re-evaluated at drain time using the player's current frac, so a cap-shrink that
		// happens while jobs are queued simply trims the overflow off the tail.

		if (frac < 0.1F) {
			spendPool(player, PENDING_VEX_DAMAGE_ULTRA_LIGHT, id, 0.3F, spawns -> enqueueSpawn(player, SpawnType.VEX, spawns));
		} else {
			PENDING_VEX_DAMAGE_ULTRA_LIGHT.remove(id);
		}

		if (frac < 0.2F) {
			spendPool(player, PENDING_VEX_DAMAGE_LIGHT, id, 0.7F, spawns -> enqueueSpawn(player, SpawnType.VEX, spawns));
		} else {
			PENDING_VEX_DAMAGE_LIGHT.remove(id);
		}

		if (frac < 0.3F) {
			spendPool(player, PENDING_VEX_DAMAGE, id, 1.5F, spawns -> enqueueSpawn(player, SpawnType.VEX, spawns));
		} else {
			PENDING_VEX_DAMAGE.remove(id);
		}

		if (frac < 0.3F) {
			spendPool(player, PENDING_BUNNY_DAMAGE_LIGHT, id, 1.5F, spawns -> enqueueSpawn(player, SpawnType.BUNNY, spawns));
		} else {
			PENDING_BUNNY_DAMAGE_LIGHT.remove(id);
		}

		if (frac < 0.4F) {
			spendPool(player, PENDING_BUNNY_DAMAGE, id, 2.0F, spawns -> enqueueSpawn(player, SpawnType.BUNNY, spawns));
		} else {
			PENDING_BUNNY_DAMAGE.remove(id);
		}

		if (frac < 0.5F) {
			// 4 hp buys two zombies; the pool's spend count is in "4 hp units", and enqueue
			// expects head count, so we multiply.
			spendPool(player, PENDING_ZOMBIE_DAMAGE_FOUR, id, 4.0F, spawns -> enqueueSpawn(player, SpawnType.ZOMBIE, spawns * 2));
		} else {
			PENDING_ZOMBIE_DAMAGE_FOUR.remove(id);
		}

		// Zombie hp tiers 0.6 / 0.7 / 0.8 / 0.9 all draw from the same PENDING_ZOMBIE_DAMAGE
		// pool, cheapest first. The pool is only cleared once the player crosses 0.9 - otherwise
		// the frac < 0.6 else-branch would wipe the pool the moment the player crossed 0.6 and
		// the higher tiers would never fire.
		if (frac < 0.6F) {
			spendPool(player, PENDING_ZOMBIE_DAMAGE, id, 4.0F, spawns -> enqueueSpawn(player, SpawnType.ZOMBIE, spawns));
		}
		if (frac < 0.7F) {
			spendPool(player, PENDING_ZOMBIE_DAMAGE, id, 8.0F, spawns -> enqueueSpawn(player, SpawnType.ZOMBIE, spawns));
		}
		if (frac < 0.8F) {
			spendPool(player, PENDING_ZOMBIE_DAMAGE, id, 12.0F, spawns -> enqueueSpawn(player, SpawnType.ZOMBIE, spawns));
		}
		if (frac < 0.9F) {
			spendPool(player, PENDING_ZOMBIE_DAMAGE, id, 16.0F, spawns -> enqueueSpawn(player, SpawnType.ZOMBIE, spawns));
		}
		if (frac >= 0.9F) {
			PENDING_ZOMBIE_DAMAGE.remove(id);
		}

		if (frac < 0.1F) {
			spendPool(player, PENDING_SILVERFISH_DAMAGE_ULTRA_LIGHT, id, 0.5F, spawns -> enqueueSpawn(player, SpawnType.SILVERFISH, spawns));
		} else {
			PENDING_SILVERFISH_DAMAGE_ULTRA_LIGHT.remove(id);
		}

		if (frac < 0.2F) {
			spendPool(player, PENDING_SILVERFISH_DAMAGE_LIGHT, id, 1.0F, spawns -> enqueueSpawn(player, SpawnType.SILVERFISH, spawns));
		} else {
			PENDING_SILVERFISH_DAMAGE_LIGHT.remove(id);
		}

		if (frac < 0.3F) {
			spendPool(player, PENDING_SILVERFISH_DAMAGE, id, 2.0F, spawns -> enqueueSpawn(player, SpawnType.SILVERFISH, spawns));
		} else {
			PENDING_SILVERFISH_DAMAGE.remove(id);
		}

		if (frac < 0.1F) {
			spendPool(player, PENDING_SKELETON_DAMAGE_ULTRA_LIGHT, id, 0.5F, spawns -> enqueueSpawn(player, SpawnType.SKELETON, spawns));
		} else {
			PENDING_SKELETON_DAMAGE_ULTRA_LIGHT.remove(id);
		}

		if (frac < 0.2F) {
			spendPool(player, PENDING_SKELETON_DAMAGE_LIGHT, id, 1.0F, spawns -> enqueueSpawn(player, SpawnType.SKELETON, spawns));
		} else {
			PENDING_SKELETON_DAMAGE_LIGHT.remove(id);
		}

		if (frac < 0.3F) {
			spendPool(player, PENDING_SKELETON_DAMAGE, id, 2.0F, spawns -> enqueueSpawn(player, SpawnType.SKELETON, spawns));
		} else {
			PENDING_SKELETON_DAMAGE.remove(id);
		}

		if (frac < 0.1F) {
			spendPool(player, PENDING_WITCH_DAMAGE_ULTRA_LIGHT, id, 0.5F, spawns -> enqueueSpawn(player, SpawnType.WITCH, spawns));
		} else {
			PENDING_WITCH_DAMAGE_ULTRA_LIGHT.remove(id);
		}

		if (frac < 0.2F) {
			spendPool(player, PENDING_WITCH_DAMAGE_LIGHT, id, 1.0F, spawns -> enqueueSpawn(player, SpawnType.WITCH, spawns));
		} else {
			PENDING_WITCH_DAMAGE_LIGHT.remove(id);
		}

		if (frac < 0.3F) {
			spendPool(player, PENDING_WITCH_DAMAGE, id, 2.0F, spawns -> enqueueSpawn(player, SpawnType.WITCH, spawns));
		} else {
			PENDING_WITCH_DAMAGE.remove(id);
		}

		if (frac < 0.1F) {
			spendPool(player, PENDING_PILLAGER_DAMAGE_ULTRA_LIGHT, id, 0.5F, spawns -> enqueueSpawn(player, SpawnType.PILLAGER, spawns));
		} else {
			PENDING_PILLAGER_DAMAGE_ULTRA_LIGHT.remove(id);
		}

		if (frac < 0.2F) {
			spendPool(player, PENDING_PILLAGER_DAMAGE_LIGHT, id, 1.0F, spawns -> enqueueSpawn(player, SpawnType.PILLAGER, spawns));
		} else {
			PENDING_PILLAGER_DAMAGE_LIGHT.remove(id);
		}

		if (frac < 0.3F) {
			spendPool(player, PENDING_PILLAGER_DAMAGE, id, 2.0F, spawns -> enqueueSpawn(player, SpawnType.PILLAGER, spawns));
		} else {
			PENDING_PILLAGER_DAMAGE.remove(id);
		}

		if (frac < 0.1F) {
			spendPool(player, PENDING_RAVAGER_DAMAGE_ULTRA_LIGHT, id, 0.5F, spawns -> enqueueSpawn(player, SpawnType.RAVAGER, spawns));
		} else {
			PENDING_RAVAGER_DAMAGE_ULTRA_LIGHT.remove(id);
		}

		if (frac < 0.2F) {
			spendPool(player, PENDING_RAVAGER_DAMAGE_LIGHT, id, 1.0F, spawns -> enqueueSpawn(player, SpawnType.RAVAGER, spawns));
		} else {
			PENDING_RAVAGER_DAMAGE_LIGHT.remove(id);
		}

		if (frac < 0.3F) {
			spendPool(player, PENDING_RAVAGER_DAMAGE, id, 2.0F, spawns -> enqueueSpawn(player, SpawnType.RAVAGER, spawns));
		} else {
			PENDING_RAVAGER_DAMAGE.remove(id);
		}

		if (frac < 0.1F) {
			spendPool(player, PENDING_WITHER_SKELETON_DAMAGE_ULTRA_LIGHT, id, 0.5F, spawns -> enqueueSpawn(player, SpawnType.WITHER_SKELETON, spawns));
		} else {
			PENDING_WITHER_SKELETON_DAMAGE_ULTRA_LIGHT.remove(id);
		}

		if (frac < 0.2F) {
			spendPool(player, PENDING_WITHER_SKELETON_DAMAGE_LIGHT, id, 1.0F, spawns -> enqueueSpawn(player, SpawnType.WITHER_SKELETON, spawns));
		} else {
			PENDING_WITHER_SKELETON_DAMAGE_LIGHT.remove(id);
		}

		if (frac < 0.3F) {
			spendPool(player, PENDING_WITHER_SKELETON_DAMAGE, id, 2.0F, spawns -> enqueueSpawn(player, SpawnType.WITHER_SKELETON, spawns));
		} else {
			PENDING_WITHER_SKELETON_DAMAGE.remove(id);
		}
	}

	/**
	 * Per-mob-type cap ladder. The first threshold &le; frac gives the cap. The tables are
	 * ordered most-permissive (lowest frac) first. The cap is the *total* for that mob type
	 * across every tier that fires at that frac - so a player at frac=0.05 can hold up to 60
	 * vexes combined across all three vex tiers, while a player at frac=0.45 only gets 18.
	 */
	private static final float[] VEX_CAPS = {0.1F, 0.2F, 0.3F, 0.5F, 1.0F};
	private static final int[] VEX_CAPS_VALUES = {384, 64, 4, 1, 0};
	private static final float[] BUNNY_CAPS = {0.3F, 0.4F, 0.5F, 1.0F};
	private static final int[] BUNNY_CAPS_VALUES = {0, 1, 3, 1};
	private static final float[] ZOMBIE_CAPS = {0.5F, 0.6F, 0.8F, 1.0F};
	private static final int[] ZOMBIE_CAPS_VALUES = {0, 1, 10, 0};
	private static final float[] SILVERFISH_CAPS = {0.1F, 0.2F, 0.3F, 0.5F, 1.0F};
	private static final int[] SILVERFISH_CAPS_VALUES = {0, 1, 16, 9, 0};
	private static final float[] SKELETON_CAPS = {0.1F, 0.2F, 0.3F, 0.5F, 1.0F};
	private static final int[] SKELETON_CAPS_VALUES = {0, 1, 3, 20, 0};
	private static final float[] WITCH_CAPS = {0.1F, 0.2F, 0.3F, 0.5F, 1.0F};
	private static final int[] WITCH_CAPS_VALUES = {0, 1, 10, 3, 0};
	private static final float[] PILLAGER_CAPS = {0.1F, 0.2F, 0.3F, 0.5F, 1.0F};
	private static final int[] PILLAGER_CAPS_VALUES = {0, 1, 30, 1, 0};
	private static final float[] RAVAGER_CAPS = {0.1F, 0.2F, 0.3F, 0.5F, 1.0F};
	private static final int[] RAVAGER_CAPS_VALUES = {0, 1, 3, 1, 0};
	private static final float[] WITHER_SKELETON_CAPS = {0.1F, 0.2F, 0.3F, 0.5F, 1.0F};
	private static final int[] WITHER_SKELETON_CAPS_VALUES = {0, 20, 5, 1, 0};

	private static int capForFrac(float[] thresholds, int[] values, float frac) {
		for (int i = 0; i < thresholds.length; i++) {
			if (frac < thresholds[i]) {
				return values[i];
			}
		}
		return 0;
	}

	private static void spendPool(ServerPlayer player, Map<UUID, Float> pool, UUID id, float costPerSpawn, java.util.function.IntConsumer spawnFn) {
		float current = pool.getOrDefault(id, 0.0F);
		if (current < costPerSpawn) {
			return;
		}
		int spawns = (int) (current / costPerSpawn);
		spawnFn.accept(spawns);
		pool.put(id, current - spawns * costPerSpawn);
	}

	public static void clearAll(ServerPlayer player) {
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
		PENDING_SILVERFISH_DAMAGE_ULTRA_LIGHT.remove(id);
		PENDING_SILVERFISH_DAMAGE_LIGHT.remove(id);
		PENDING_SILVERFISH_DAMAGE.remove(id);
		PENDING_SKELETON_DAMAGE_ULTRA_LIGHT.remove(id);
		PENDING_SKELETON_DAMAGE_LIGHT.remove(id);
		PENDING_SKELETON_DAMAGE.remove(id);
		PENDING_WITCH_DAMAGE_ULTRA_LIGHT.remove(id);
		PENDING_WITCH_DAMAGE_LIGHT.remove(id);
		PENDING_WITCH_DAMAGE.remove(id);
		PENDING_PILLAGER_DAMAGE_ULTRA_LIGHT.remove(id);
		PENDING_PILLAGER_DAMAGE_LIGHT.remove(id);
		PENDING_PILLAGER_DAMAGE.remove(id);
		PENDING_RAVAGER_DAMAGE_ULTRA_LIGHT.remove(id);
		PENDING_RAVAGER_DAMAGE_LIGHT.remove(id);
		PENDING_RAVAGER_DAMAGE.remove(id);
		PENDING_WITHER_SKELETON_DAMAGE_ULTRA_LIGHT.remove(id);
		PENDING_WITHER_SKELETON_DAMAGE_LIGHT.remove(id);
		PENDING_WITHER_SKELETON_DAMAGE.remove(id);
		VEX_AUTO_TIMER.remove(id);
		LAST_HURT_ENTITY.remove(id);
		PLAYER_ATTACK_TARGET.remove(id);
		PENDING_SPAWNS.remove(id);
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

	/**
	 * Push a spawn request onto the player's queue instead of generating the entity right away.
	 * Multiple jobs for the same type are coalesced so a hit that triggers several pools at once
	 * still leaves the queue compact. The cap is re-evaluated at drain time - see
	 * {@link #drainPendingSpawns}.
	 */
	private static void enqueueSpawn(ServerPlayer player, SpawnType type, int count) {
		if (count <= 0) {
			return;
		}
		Deque<SpawnJob> queue = PENDING_SPAWNS.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
		// Coalesce with the tail job of the same type so a single big hit that fires multiple
		// pools in one tick doesn't grow the queue linearly with pool count.
		SpawnJob tail = queue.peekLast();
		if (tail != null && tail.type == type) {
			tail.remaining += count;
		} else {
			queue.addLast(new SpawnJob(type, count));
		}
	}

	/**
	 * Flush up to {@link #PER_TICK_BUDGET} entities from the player's queue. Each pop decrements
	 * the job by 1; the job is removed when it hits 0. The cap check uses the player's current
	 * frac, so a cap-shrink while jobs are queued silently trims the overflow. Spawning itself
	 * is delegated to {@link #summonAround}, which samples up to {@code MAX_ATTEMPTS} positions
	 * and falls back to a forced spawn at the player's head on a total miss.
	 */
	private static void drainPendingSpawns(ServerPlayer player, float frac) {
		Deque<SpawnJob> queue = PENDING_SPAWNS.get(player.getUUID());
		if (queue == null || queue.isEmpty()) {
			return;
		}
		ServerLevel level = player.level();
		BlockPos center = player.blockPosition();
		int budget = PER_TICK_BUDGET;
		while (budget > 0 && !queue.isEmpty()) {
			SpawnJob job = queue.peekFirst();
			if (job == null) {
				break;
			}
			int cap = currentCapFor(job.type, frac);
			if (cap <= 0) {
				queue.removeFirst();
				continue;
			}
			// Cap accounting uses the same SUMMONED list we already maintain. We re-count from
			// scratch each iteration because the spawn itself appends to the list and would
			// otherwise let one budget slice through the cap.
			List<LivingEntity> list = SUMMONED.computeIfAbsent(player.getUUID(), k -> new ArrayList<>());
			list.removeIf(e -> !LOADED.test(e));
			int ofType = 0;
			for (LivingEntity e : list) {
				if (job.type.typeClass.isInstance(e)) {
					ofType++;
				}
			}
			if (ofType >= cap) {
				queue.removeFirst();
				continue;
			}
			int spawned = summonAround(level, center, job.type, 1, player, 5, 3, 5);
			if (spawned > 0) {
				job.remaining -= spawned;
				if (job.remaining <= 0) {
					queue.removeFirst();
				}
				budget -= spawned;
			} else {
				// spawned == 0: even the forced fallback at center.above() failed (player inside
				// a 1x1 box, or world fully blocked). Drop the job - it cannot succeed next tick
				// without the player moving.
				queue.removeFirst();
			}
		}
	}

	private static int currentCapFor(SpawnType type, float frac) {
		return switch (type) {
			case VEX -> capForFrac(VEX_CAPS, VEX_CAPS_VALUES, frac);
			case BUNNY -> capForFrac(BUNNY_CAPS, BUNNY_CAPS_VALUES, frac);
			case ZOMBIE -> capForFrac(ZOMBIE_CAPS, ZOMBIE_CAPS_VALUES, frac);
			case SILVERFISH -> capForFrac(SILVERFISH_CAPS, SILVERFISH_CAPS_VALUES, frac);
			case SKELETON -> capForFrac(SKELETON_CAPS, SKELETON_CAPS_VALUES, frac);
			case WITCH -> capForFrac(WITCH_CAPS, WITCH_CAPS_VALUES, frac);
			case PILLAGER -> capForFrac(PILLAGER_CAPS, PILLAGER_CAPS_VALUES, frac);
			case RAVAGER -> capForFrac(RAVAGER_CAPS, RAVAGER_CAPS_VALUES, frac);
			case WITHER_SKELETON -> capForFrac(WITHER_SKELETON_CAPS, WITHER_SKELETON_CAPS_VALUES, frac);
		};
	}

	/**
	 * Per-type overrides applied AFTER vanilla finalizeSpawn so we don't have to reimplement
	 * the equipment / variant / attribute randomisation paths ourselves. Vex lifetime still has
	 * to be set manually because vanilla Vex.finalizeSpawn doesn't install it; the killer-bunny
	 * variant has to override the biome-aware one that Rabbit.finalizeSpawn just picked.
	 */
	private static void postSpawnConfigure(Mob mob, SpawnType type) {
		switch (type) {
			case VEX -> {
				if (mob instanceof Vex vex) {
					// setLimitedLife matches vanilla spell-summoned vexes: ~30s before the vex
					// starves itself.
					vex.setLimitedLife(20 * 30);
					// Vex.createAttributes sets ATTACK_DAMAGE=5.0; randomise each spawned vex in
					// [5, 40] so the swarm is unpredictable (some glass cannons, some chonks).
					AttributeInstance atk = vex.getAttribute(Attributes.ATTACK_DAMAGE);
					if (atk != null) {
						float r = vex.getRandom().nextFloat();
						atk.setBaseValue(5.0 + r * 35.0);
					}
				}
			}
			case BUNNY -> {
				// setVariant is private in vanilla; the accessor mixin exposes it. The EVIL
				// variant installs MeleeAttackGoal + HurtByTargetGoal +
				// NearestAttackableTargetGoal<Player/Wolf> in its setVariant method, so the
				// rabbit actually fights back. The targeting mixin then blocks the auto-acquired
				// Player target. We override here because Rabbit.finalizeSpawn just picked a
				// biome-aware variant.
				((RabbitVariantAccessor) mob).endring$setVariant(Rabbit.Variant.EVIL);
			}
			case ZOMBIE -> {
				// no extra config: vanilla Zombie.finalizeSpawn already randomised occupation,
				// equipment, enchantments, knockback resistance, follow range, and reinforcement
				// chance based on the local difficulty. We get all of that for free.
			}
			case SILVERFISH -> {
				// vanilla Monster finalise path is a no-op for silverfish (no equipment).
			}
			case SKELETON -> {
				// AbstractSkeleton.finalizeSpawn equips a Bow and runs reassessWeaponGoal() for
				// us; nothing to override.
			}
			case WITCH -> {
				// Witch has no held item by design; potions are constructed at fire time in
				// performRangedAttack.
			}
			case PILLAGER -> {
				// Pillager.finalizeSpawn equips a Crossbow and runs enchantment rolls for us.
			}
			case RAVAGER -> {
				// Ravager is melee-only; no held items, vanilla default applies.
			}
			case WITHER_SKELETON -> {
				// WitherSkeleton.finalizeSpawn equips a Stone Sword (overriding the
				// AbstractSkeleton bow) and sets ATTACK_DAMAGE=4.0.
			}
		}
	}

	/**
	 * Per-entity position-sampling budget used by {@link #summonAround}. Matches the 12 attempts
	 * vanilla's BaseSpawner / NaturalSpawner run before giving up on a single mob.
	 */
	private static final int MAX_ATTEMPTS = 12;

	/**
	 * 在指定坐标固定刷出 1 只怪物。无采样, 无重试: 给定坐标不合法就返回 false。
	 * <p>
	 * 碰撞检查按怪物类型分:
	 * <ul>
	 *   <li>vex: 不做碰撞箱检查, 直接尝试刷出 (vex 体积小, 偶发卡方块边缘可接受)。</li>
	 *   <li>zombie / rabbit: 检查 per-entity AABB, 避免出生在实心方块内部。</li>
	 * </ul>
	 * 成功刷出后写入 owner 标记 + 追加到 {@link #SUMMONED}。
	 *
	 * @param level  服务端世界
	 * @param pos    怪物 {@link EntityType#spawn} 接收的坐标 (vanilla 会用 getYOffset 找脚下)
	 * @param type   怪物类型
	 * @param owner  拥有者 (写入 EndRingOwnedComponent + SUMMONED)
	 * @return 成功刷出 true; 给定坐标被判定为不合法时 false
	 */
	private static boolean summonAt(
		ServerLevel level,
		BlockPos pos,
		SpawnType type,
		LivingEntity owner
	) {
		EntityType<? extends Mob> entityType = type.entityType;
		double x = pos.getX() + 0.5;
		double y = pos.getY();
		double z = pos.getZ() + 0.5;
		if (type != SpawnType.VEX && !level.noCollision(entityType.getSpawnAABB(x, y, z))) {
			return false;
		}
		Mob mob = entityType.spawn(
			level,
			null,
			null,
			pos.immutable(),
			EntitySpawnReason.MOB_SUMMONED,
			true,
			false
		);
		if (mob == null) {
			return false;
		}
		postSpawnConfigure(mob, type);
		EndRingOwnedComponent.setOwner(mob, owner);
		SUMMONED.computeIfAbsent(owner.getUUID(), k -> new ArrayList<>()).add(mob);
		// 生成时给随机方向和速度
		RandomSource random = level.getRandom();
		mob.setDeltaMovement(
			(random.nextFloat() - 0.5f) * 1.8f,
			(random.nextFloat() - 0.5f) * 0.6f,
			(random.nextFloat() - 0.5f) * 1.8f
		);
		return true;
	}

	/**
	 * 在中心坐标周围批量生成怪物。每只怪独立最多 12 次坐标采样, 全失败本轮就放弃。
	 * 抽样范围: X / Z 浮点 ±size/2, Y 整数 [center.y+1-size/2, center.y+1+size/2] (整数除以 2,
	 * 自动向下取整, 即 size=5 时 Y 档为 -2, -1, 0, +1, +2)。
	 * <p>
	 * 地面规则: vex 一直浮空, 不做脚下校验; zombie / rabbit 前 11 次采样要求脚下方块通过
	 * {@code isValidSpawn} (即标准可站立方块), 最后一次放宽允许浮空 —— 玩家卡墙角时仍能出怪。
	 * <p>
	 * 全失败兜底: 整批 {@code max} 只都没找到合法位置时, 在 {@code center.above()}
	 * 强制刷 1 只 (交给 vanilla {@code getYOffset} 找脚下)。这样玩家卡墙角等极端场景下
	 * 仍能保证这一 tick 至少出 1 只, 不用把 job 留到下 tick 重试。
	 *
	 * @param level  服务端世界
	 * @param center 中心坐标 (玩家脚部 BlockPos)
	 * @param type   怪物类型
	 * @param max    本次最多生成数量
	 * @param owner  拥有者 (写入 EndRingOwnedComponent + SUMMONED)
	 * @param xSize  X 轴盒子边长 (5 → 5 格宽, 抽样 ±2)
	 * @param ySize  Y 轴盒子边长 (3 → 3 个 Y 档, 抽样 -1, 0, +1)
	 * @param zSize  Z 轴盒子边长 (5 → 5 格深, 抽样 ±2)
	 * @return 实际生成数量
	 */
	private static int summonAround(
		ServerLevel level,
		BlockPos center,
		SpawnType type,
		int max,
		LivingEntity owner,
		int xSize,
		int ySize,
		int zSize
	) {
		if (max <= 0 || xSize <= 0 || ySize <= 0 || zSize <= 0) {
			return 0;
		}
		EntityType<? extends Mob> entityType = type.entityType;
		boolean skipCollision = (type == SpawnType.VEX);
		RandomSource random = level.getRandom();
		double xHalf = xSize / 2.0;
		double zHalf = zSize / 2.0;
		int yHalf = ySize / 2;
		int spawned = 0;
		for (int i = 0; i < max; i++) {
			boolean placed = false;
			for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
				boolean isFinalAttempt = (attempt == MAX_ATTEMPTS - 1);
				double x = center.getX() + (random.nextDouble() - random.nextDouble()) * xHalf;
				double y = center.getY() + 1 + random.nextInt(ySize) - yHalf;
				double z = center.getZ() + (random.nextDouble() - random.nextDouble()) * zHalf;
				BlockPos pos = BlockPos.containing(x, y, z);
				if (!skipCollision && !level.noCollision(entityType.getSpawnAABB(x, y, z))) {
					continue;
				}
				// zombie / rabbit: 前 11 次要求站在可站立方块上 (脚下方块通过 isValidSpawn),
				// 最后一次允许浮空 (玩家卡墙角时仍能出怪)。vex 一直浮空, 不受此约束。
				if (!skipCollision && !isFinalAttempt) {
					BlockPos below = pos.below();
					if (!level.getBlockState(below).isValidSpawn(level, below, entityType)) {
						continue;
					}
				}
				if (summonAt(level, pos.immutable(), type, owner)) {
					spawned++;
					placed = true;
					break;
				}
			}
			if (!placed) {
				break;
			}
		}
		if (spawned == 0) {
			// 全失败兜底: 玩家脚部正上方强制刷 1 只, 不再等下 tick。
			if (summonAt(level, center.above(), type, owner)) {
				spawned = 1;
			}
		}
		return spawned;
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
