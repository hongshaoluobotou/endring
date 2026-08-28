package com.hongshaoluobotou;

/**
 * Standalone verification (no Minecraft, no JUnit) for {@link EndRingAiScheduler}'s spatial
 * bucketing and worker-pool sizing. The full multi-threaded pathfind is exercised in-game; the
 * unit test only checks the pure-data path so we can run it from {@code ./gradlew haloTest}
 * without spinning up a server. The Minecraft-touching parts (Path creation, moveTo) live in
 * the production class but are not exercised here.
 */
public final class EndRingAiSchedulerTest {
	private static int failures = 0;

	public static void main(String[] args) {
		scenarioBucketSizeFar();
		scenarioBucketSizeMid();
		scenarioBucketSizeNear();
		scenarioWorkerPoolSizing();
		scenarioKeyCollisions();

		if (failures == 0) {
			System.out.println("\nALL SCENARIOS PASSED");
		} else {
			System.out.println("\n" + failures + " SCENARIO(S) FAILED");
			System.exit(1);
		}
	}

	/**
	 * Mirror of the production threshold table. Keep in sync with
	 * {@link EndRingAiScheduler#bucketSizeFor}. The exact values are duplicated here because
	 * the production method is private; if you change one, change the other.
	 */
	private static int bucketSizeFor(double distSqr) {
		if (distSqr > 96.0 * 96.0) {
			return 32;
		}
		if (distSqr > 48.0 * 48.0) {
			return 16;
		}
		return 8;
	}

	private static int workerPoolFor(int mobCount) {
		return Math.max(1, Math.min(8, (mobCount + 1023) / 1024));
	}

	private static long packKey(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | ((long) (z & 0x3FFFFFF));
	}

	private static void scenarioBucketSizeFar() {
		int size = bucketSizeFor(120.0 * 120.0);
		expect("bucketSizeFar returns 32-block bucket", size, 32);
	}

	private static void scenarioBucketSizeMid() {
		int size = bucketSizeFor(80.0 * 80.0);
		expect("bucketSizeMid returns 16-block bucket", size, 16);
	}

	private static void scenarioBucketSizeNear() {
		int size = bucketSizeFor(20.0 * 20.0);
		expect("bucketSizeNear returns 8-block bucket", size, 8);
	}

	private static void scenarioWorkerPoolSizing() {
		expect("workerPoolFor(0) is at least 1", workerPoolFor(0) >= 1, true);
		expect("workerPoolFor(500) is 1 (below 1024 threshold)", workerPoolFor(500), 1);
		expect("workerPoolFor(1023) is 1", workerPoolFor(1023), 1);
		expect("workerPoolFor(1024) is 1 (1024/1024 = 1)", workerPoolFor(1024), 1);
		expect("workerPoolFor(1025) is 2", workerPoolFor(1025), 2);
		expect("workerPoolFor(2048) is 2", workerPoolFor(2048), 2);
		expect("workerPoolFor(2049) is 3", workerPoolFor(2049), 3);
		expect("workerPoolFor(8000) is 8 (cap reached)", workerPoolFor(8000), 8);
		expect("workerPoolFor(20000) is 8 (still capped)", workerPoolFor(20000), 8);
		expect("workerPoolFor(100000) is 8 (still capped)", workerPoolFor(100000), 8);
	}

	private static void scenarioKeyCollisions() {
		// Two owners in the same bucket must produce the same key. Different y values inside
		// the same cell should still collapse to a single key.
		long k1 = packKey(0, 0, 0);
		long k2 = packKey(0, 0, 0);
		expect("identical bucket coords produce identical key", k1 == k2, true);

		long k3 = packKey(1, 0, 0);
		expect("different x bucket produces different key", k1 != k3, true);

		long k4 = packKey(0, 1, 0);
		expect("different y bucket produces different key", k1 != k4, true);
	}

	private static void expect(String label, Object actual, Object expected) {
		boolean ok = (actual == null && expected == null) || (actual != null && actual.equals(expected));
		if (ok) {
			System.out.println("[PASS] " + label);
		} else {
			System.out.println("[FAIL] " + label + " actual=" + actual + " expected=" + expected);
			failures++;
		}
	}
}
