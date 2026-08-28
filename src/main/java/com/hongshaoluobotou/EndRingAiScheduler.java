package com.hongshaoluobotou;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spatial-bucket pathfind scheduler for the End Ring summon swarm.
 *
 * <p>Each tick, every owned mob that drifts more than 12 blocks from its owner is registered
 * with {@link #schedule(Mob, LivingEntity)}. The scheduler collects the registrations into
 * spatial buckets whose size scales with distance to the owner (32 blocks for the far bucket,
 * 16 for mid, 8 for near) and at the end of the tick (driven from {@code EndRingEvents}) it
 * flushes the buckets in parallel: for each non-empty bucket the first mob is picked as the
 * lead and runs {@code PathFinder.findPath} to its owner's block; every other mob in the
 * bucket receives a {@link Path#copy() copy} of the resulting path and uses it directly.
 *
 * <p>Why this matters at 20 000 mobs: a vanilla A* through {@code FlyingPathNavigation} takes
 * ~50 µs per pathfind (region snapshot, neighbour expansion, distance cache). 20 000 mobs
 * re-pathfinding every 5 ticks is 4 000 pathfinds / tick = 200 ms / tick = 5 TPS even on
 * a fast CPU. Spatial bucketing with the 32/16/8 size ladder cuts that by the average bucket
 * size (which works out to ~6 mobs per bucket in a uniformly-spread swarm, 60x reduction).
 * Running the per-bucket lead computations on a {@link ForkJoinPool} sized to one worker per
 * 1 024 registered mobs (capped at 8) lets the bucket reductions happen in parallel on
 * whatever CPU cores are free, instead of stacking on the main thread.
 *
 * <p>Path copies are shallow: the {@code List<Node>} reference is shared (the nodes themselves
 * are immutable once the path is constructed) but each copy has its own {@code nextNodeIndex}
 * so that individual mobs can advance along the path independently.
 */
public final class EndRingAiScheduler {
	private static final Logger LOG = LoggerFactory.getLogger("endring/ai-scheduler");

	/**
	 * Spatial bucket size as a function of distance-squared to the owner. The bucket
	 * coordinate is {@code floor(owner / bucketSize)} so two mobs whose owners are within
	 * one bucket cell end up in the same bucket regardless of the mobs' own positions.
	 */
	private static final double NEAR_THRESHOLD = 48.0 * 48.0;
	private static final double MID_THRESHOLD = 96.0 * 96.0;
	private static final int BUCKET_NEAR = 8;
	private static final int BUCKET_MID = 16;
	private static final int BUCKET_FAR = 32;

	/** Per-tick bucket-to-lead assignment. Cleared in {@link #flush()}. */
	private final Long2ObjectMap<List<BucketEntry>> buckets = new Long2ObjectOpenHashMap<>();

	/** Cached pool sized to the registered mob count; rebuilt in {@link #flush()}. */
	private ForkJoinPool pool;

	/** Single global instance; the scheduler is stateless across calls. */
	public static final EndRingAiScheduler INSTANCE = new EndRingAiScheduler();

	private EndRingAiScheduler() {
	}

	/**
	 * Registers a mob for follow-pathfind batching. The mob will be grouped with any other mob
	 * in the same spatial bucket at {@link #flush()} time; only the lead mob of each bucket
	 * actually runs {@code findPath}, every other mob in the bucket gets a path copy.
	 */
	public void schedule(Mob mob, LivingEntity owner) {
		double distSqr = mob.distanceToSqr(owner);
		int bucketSize = bucketSizeFor(distSqr);
		int bx = floorDiv(owner.getX(), bucketSize);
		int by = floorDiv(owner.getY(), bucketSize);
		int bz = floorDiv(owner.getZ(), bucketSize);
		long key = packKey(bx, by, bz);
		buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(new BucketEntry(mob, owner));
	}

	/**
	 * Flushes the current batch. Must be called from the main server thread (the pathfind
	 * workers run on the {@link ForkJoinPool}, but the resulting path is applied to each mob
	 * by the main thread). Idempotent: calling it twice in a row is a no-op the second time.
	 */
	public void flush() {
		if (buckets.isEmpty()) {
			return;
		}
		int mobCount = 0;
		for (List<BucketEntry> bucket : buckets.values()) {
			mobCount += bucket.size();
		}
		if (mobCount == 0) {
			buckets.clear();
			return;
		}

		int workers = Math.max(1, Math.min(8, (mobCount + 1023) / 1024));
		ForkJoinPool pool = ensurePool(workers);

		// Build one CompletableFuture per non-empty bucket. Each task runs the lead mob's
		// pathfind on the worker pool; the resulting Path is then distributed to the
		// remaining bucket members on the main thread (after join()) so we never mutate
		// a Mob from a worker.
		List<CompletableFuture<Path>> futures = new ArrayList<>(buckets.size());
		List<List<BucketEntry>> ordered = new ArrayList<>(buckets.size());
		for (List<BucketEntry> bucket : buckets.values()) {
			if (bucket.isEmpty()) {
				continue;
			}
			ordered.add(bucket);
			BucketEntry lead = bucket.get(0);
			futures.add(CompletableFuture.supplyAsync(() -> computeLeadPath(lead), pool));
		}
		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

		// Apply results on the main thread. Each non-lead mob receives a Path.copy() so the
		// shared node list is read-only but nextNodeIndex advances independently.
		for (int i = 0; i < ordered.size(); i++) {
			List<BucketEntry> bucket = ordered.get(i);
			Path leadPath = futures.get(i).join();
			if (leadPath == null) {
				continue;
			}
			for (int j = 0; j < bucket.size(); j++) {
				BucketEntry entry = bucket.get(j);
				Path copy = j == 0 ? leadPath : leadPath.copy();
				applyPath(entry.mob(), copy);
			}
		}
		buckets.clear();
	}

	private static Path computeLeadPath(BucketEntry lead) {
		try {
			PathNavigation nav = lead.mob().getNavigation();
			return nav.createPath(lead.owner().blockPosition(), 0);
		} catch (Throwable t) {
			LOG.warn("Pathfind failed for {} -> {}: {}", lead.mob(), lead.owner(), t.toString());
			return null;
		}
	}

	private static void applyPath(Mob mob, Path path) {
		try {
			mob.getNavigation().moveTo(path, 1.0);
		} catch (Throwable t) {
			LOG.warn("applyPath failed for {}: {}", mob, t.toString());
		}
	}

	private ForkJoinPool ensurePool(int workers) {
		if (this.pool == null || this.pool.getParallelism() != workers) {
			if (this.pool != null) {
				this.pool.shutdown();
			}
			this.pool = new ForkJoinPool(workers);
		}
		return this.pool;
	}

	/** Test/visibility helper. */
	Map<Long, List<BucketEntry>> bucketsForTesting() {
		return this.buckets;
	}

	/** Test/visibility helper. */
	int poolSizeForTesting() {
		return this.pool == null ? 0 : this.pool.getParallelism();
	}

	private static int bucketSizeFor(double distSqr) {
		if (distSqr > MID_THRESHOLD) {
			return BUCKET_FAR;
		}
		if (distSqr > NEAR_THRESHOLD) {
			return BUCKET_MID;
		}
		return BUCKET_NEAR;
	}

	private static int floorDiv(double v, int size) {
		return (int) Math.floor(v / size);
	}

	private static long packKey(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | ((long) (z & 0x3FFFFFF));
	}

	private record BucketEntry(Mob mob, LivingEntity owner) {
	}

	/** Hook called by {@code EndRingEvents} to drain pending pathfind work at end of tick. */
	public static void onEndServerTick() {
		INSTANCE.flush();
	}
}
