package com.hongshaoluobotou.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

// Off-thread physics computation for halos. Each halo owns a persistent HaloPhysics instance
// (only ever touched by the worker) plus a lock-free published Frame the render thread reads.
//
// Threading contract:
//   - tick() (client thread) builds an immutable HaloPhysics.Input snapshot and submits it.
//   - workers run HaloPhysics.step off-thread and publish {prev,cur} states atomically.
//   - renderAll() (render thread) reads the latest Frame and interpolates; never blocks.
// One in-flight job per halo (latest-wins): a newer snapshot supersedes a pending one.
public final class HaloScheduler {
	// a published pair of successive states for render-time interpolation
	public record Frame(HaloPhysics.State prev, HaloPhysics.State cur) {
	}

	private static final class Entry {
		final HaloPhysics physics;
		final AtomicReference<Frame> frame = new AtomicReference<>();
		final AtomicReference<HaloPhysics.Input> pending = new AtomicReference<>();
		final AtomicBoolean running = new AtomicBoolean(false);
		volatile HaloPhysics.State last;

		Entry(double modelR, double modelT) {
			this.physics = new HaloPhysics(modelR, modelT);
		}
	}

	private static final ExecutorService POOL = Executors.newFixedThreadPool(
		Math.max(1, Runtime.getRuntime().availableProcessors() - 1), new ThreadFactory() {
			private final AtomicInteger n = new AtomicInteger();
			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "endring-halo-" + n.getAndIncrement());
				t.setDaemon(true);
				return t;
			}
		});

	private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();

	private HaloScheduler() {
	}

	// submit the latest snapshot for a halo; supersedes any queued-but-not-started snapshot.
	public static void submit(UUID id, double modelR, double modelT, HaloPhysics.Input input) {
		Entry e = ENTRIES.computeIfAbsent(id, k -> new Entry(modelR, modelT));
		e.pending.set(input);
		tryDispatch(e);
	}

	private static void tryDispatch(Entry e) {
		if (!e.running.compareAndSet(false, true)) {
			return; // a worker is already draining this entry's pending snapshots
		}
		POOL.execute(() -> {
			try {
				HaloPhysics.Input in;
				while ((in = e.pending.getAndSet(null)) != null) {
					HaloPhysics.State cur = e.physics.step(in);
					HaloPhysics.State prev = e.last != null ? e.last : cur;
					e.last = cur;
					e.frame.set(new Frame(prev, cur));
				}
			} finally {
				e.running.set(false);
				// a snapshot may have arrived between the null-drain and clearing running.
				if (e.pending.get() != null) {
					tryDispatch(e);
				}
			}
		});
	}

	// latest computed frame for a halo, or null if none has been produced yet.
	public static Frame frame(UUID id) {
		Entry e = ENTRIES.get(id);
		return e == null ? null : e.frame.get();
	}

	public static void remove(UUID id) {
		ENTRIES.remove(id);
	}

	public static void clear() {
		ENTRIES.clear();
	}
}
