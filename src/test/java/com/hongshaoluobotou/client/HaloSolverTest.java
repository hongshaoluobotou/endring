package com.hongshaoluobotou.client;

import java.util.ArrayList;
import java.util.List;

// Standalone verification (no Minecraft, no JUnit) for HaloSolver collision avoidance.
// The world is a set of solid boxes; we reuse the exact production FreeTest logic
// (block collision + player body-core exclusion), simulate the player walking through
// several obstacle scenarios, and assert the halo never clips a block and settles
// collision-free. Run via `./gradlew haloTest` (wired into `check`).
public final class HaloSolverTest {
	private static final double BASE_HEIGHT = 0.45;
	// A realistically-measured ring: Xsize ~0.8 -> modelR = 0.8*0.5*1.15 ~= 0.46 (diameter ~0.96 fits a 1-wide gap).
	private static final double MODEL_R = 0.46;
	private static final double MODEL_T = 0.06;

	private static int failures = 0;

	public static void main(String[] args) {
		scenarioPillar2();
		scenarioPillar3();
		scenarioTallOpening();
		scenarioSneakOpening();
		scenarioLowTunnel15();
		scenarioProneTunnel1();
		scenarioSealedBox("Sealed box 1x2x1", 2.0, 1.8);
		scenarioSealedBox("Sealed box 1x1.8x1", 1.8, 1.5);
		scenarioSealedBox("Sealed box 1x1.5x1", 1.5, 1.4);
		scenarioSealedBox("Sealed box 1x1x1", 1.0, 0.9);
		scenarioSealedBox("Sealed box 1x0.3x1", 0.3, 0.3);
		scenarioStuckInBlock();
		scenarioRunJumpCorridor();
		scenarioRunJumpFlat();
		scenarioCrawlJumpTunnel1();
		scenarioVerticalSlot();
		scenarioWallToleranceStanding();
		scenarioEnterOpeningSinks();

		if (failures == 0) {
			System.out.println("\nALL SCENARIOS PASSED");
		} else {
			System.out.println("\n" + failures + " SCENARIO(S) FAILED");
			System.exit(1);
		}
	}

	private static HaloSolver.Box block(int x, int y, int z) {
		return new HaloSolver.Box(x, y, z, x + 1, y + 1, z + 1);
	}

	private static boolean noCollision(List<HaloSolver.Box> world, HaloSolver.Box box) {
		for (HaloSolver.Box b : world) {
			if (box.intersects(b)) {
				return false;
			}
		}
		return true;
	}

	// world collision only; body overlap is handled internally by the solver as a soft penalty.
	private static HaloSolver.FreeTest freeTest(List<HaloSolver.Box> world) {
		return box -> noCollision(world, box);
	}

	// Simulate the player walking from start to end; the solver re-anchors the halo each tick.
	// Asserts the halo is collision-free at EVERY tick and reports the final resting offset.
	private static void simulate(String name, List<HaloSolver.Box> world,
			double sx, double sz, double ex, double ez, double py, double bbHeight, double bodyYawDeg) {
		int ticks = 160;
		double homeY = bbHeight + BASE_HEIGHT;
		double ax = 0, ay = homeY, az = 0, atx = 0, atz = 0;
		int violations = 0;
		double minY = homeY;
		double prevPx = sx, prevPz = sz;
		for (int i = 1; i <= ticks; i++) {
			double t = (double) i / ticks;
			double px = sx + (ex - sx) * t;
			double pz = sz + (ez - sz) * t;
			double mvX = px - prevPx;
			double mvZ = pz - prevPz;
			prevPx = px;
			prevPz = pz;
			HaloSolver.FreeTest ft = freeTest(world);
			HaloSolver solver = new HaloSolver(px, py, pz, bbHeight, bodyYawDeg,
				MODEL_R, MODEL_T, ax, ay, az, atx, atz, mvX, mvZ, ft);
			double[] moved = solver.solve(homeY);
			ax = moved[0];
			ay = moved[1];
			az = moved[2];
			atx = moved[3];
			atz = moved[4];
			minY = Math.min(minY, ay);
			if (!solver.fitsTolerant(ax, ay, az, atx, atz)) {
				violations++;
			}
		}

		boolean finalFree = freeTestFits(world, ex, py, ez, bbHeight, bodyYawDeg, ax, ay, az, atx, atz);
		boolean pass = finalFree && violations == 0;
		if (!pass) {
			failures++;
		}
		System.out.printf("[%s] %s%n        final offset (%.3f, %.3f, %.3f) tilt (%.0f, %.0f), lowest halo Y offset %.3f, per-tick clip violations %d%n",
			pass ? "PASS" : "FAIL", name, ax, ay, az, atx, atz, minY, violations);
	}

	private static boolean freeTestFits(List<HaloSolver.Box> world, double px, double py, double pz,
			double bbHeight, double bodyYawDeg, double ax, double ay, double az, double atx, double atz) {
		HaloSolver.FreeTest ft = freeTest(world);
		HaloSolver s = new HaloSolver(px, py, pz, bbHeight, bodyYawDeg, MODEL_R, MODEL_T, ax, ay, az, atx, atz, ft);
		return s.fits(ax, ay, az, atx, atz);
	}

	// Walk from start to end (feeding real per-tick motion), then STOP at the end and let the ring
	// settle with zero movement — models a player brushing up to an obstacle and stopping.
	private static void simulateApproachStop(String name, List<HaloSolver.Box> world,
			double sx, double sz, double ex, double ez, double py, double bbHeight, double bodyYawDeg) {
		int walkTicks = 120, stopTicks = 80;
		double homeY = bbHeight + BASE_HEIGHT;
		double ax = 0, ay = homeY, az = 0, atx = 0, atz = 0;
		int violations = 0;
		double minY = homeY;
		double prevPx = sx, prevPz = sz;
		HaloSolver.FreeTest ft = freeTest(world);
		for (int i = 1; i <= walkTicks + stopTicks; i++) {
			double px, pz, mvX, mvZ;
			if (i <= walkTicks) {
				double t = (double) i / walkTicks;
				px = sx + (ex - sx) * t;
				pz = sz + (ez - sz) * t;
			} else {
				px = ex;
				pz = ez;
			}
			mvX = px - prevPx;
			mvZ = pz - prevPz;
			prevPx = px;
			prevPz = pz;
			HaloSolver solver = new HaloSolver(px, py, pz, bbHeight, bodyYawDeg,
				MODEL_R, MODEL_T, ax, ay, az, atx, atz, mvX, mvZ, ft);
			double[] moved = solver.solve(homeY);
			ax = moved[0];
			ay = moved[1];
			az = moved[2];
			atx = moved[3];
			atz = moved[4];
			minY = Math.min(minY, ay);
			if (!solver.fitsTolerant(ax, ay, az, atx, atz)) {
				violations++;
			}
		}
		boolean finalFree = freeTestFits(world, ex, py, ez, bbHeight, bodyYawDeg, ax, ay, az, atx, atz);
		boolean pass = finalFree && violations == 0;
		if (!pass) {
			failures++;
		}
		System.out.printf("[%s] %s%n        final offset (%.3f, %.3f, %.3f) tilt (%.0f, %.0f), lowest halo Y offset %.3f, per-tick clip violations %d%n",
			pass ? "PASS" : "FAIL", name, ax, ay, az, atx, atz, minY, violations);
	}

	private static List<HaloSolver.Box> ground() {
		List<HaloSolver.Box> world = new ArrayList<>();
		for (int x = -4; x <= 4; x++) {
			for (int z = -4; z <= 4; z++) {
				world.add(block(x, -1, z));
			}
		}
		return world;
	}

	// Scenario 1: flat ground, player brushes a 1-wide 2-tall pillar.
	// The pillar top (y=2) sits below the overhead halo (~y 2.25), so the halo is undisturbed.
	private static void scenarioPillar2() {
		List<HaloSolver.Box> world = ground();
		world.add(block(1, 0, 0));
		world.add(block(1, 1, 0));
		simulate("Pillar 1x2 brush", world, 0.0, 0.5, 0.7, 0.5, 0.0, 1.8, 0.0);
	}

	// Scenario 2: a 1-wide 3-tall pillar. Its top (y=3) is ABOVE the overhead halo, so at close
	// range the halo overlaps it. The player walks up to the pillar and STOPS beside it (you cannot
	// walk through a solid pillar), so the ring should settle into a gentle sidestep + aim-tilt
	// rather than an aggressive upright dodge.
	private static void scenarioPillar3() {
		List<HaloSolver.Box> world = ground();
		world.add(block(1, 0, 0));
		world.add(block(1, 1, 0));
		world.add(block(1, 2, 0));
		simulateApproachStop("Pillar 1x3 dodge", world, 0.0, 0.5, 0.7, 0.5, 0.0, 1.8, 0.0);
	}

	// Scenario 3: player walks through a 1-wide 2-tall opening in a tall wall.
	private static void scenarioTallOpening() {
		List<HaloSolver.Box> world = ground();
		for (int x = -4; x <= 4; x++) {
			if (x == 0) {
				world.add(new HaloSolver.Box(0, 2, 2, 1, 4, 3)); // lintel above the 2-tall hole
			} else {
				world.add(new HaloSolver.Box(x, 0, 2, x + 1, 4, 3));
			}
		}
		simulate("Opening 1x2 walk-through", world, 0.5, 0.0, 0.5, 2.5, 0.0, 1.8, 0.0);
	}

	// Scenario 4: sneaking player (bbHeight 1.5) enters a 1-wide, 1.8-tall opening.
	private static void scenarioSneakOpening() {
		List<HaloSolver.Box> world = ground();
		for (int x = -4; x <= 4; x++) {
			if (x == 0) {
				world.add(new HaloSolver.Box(0, 1.8, 2, 1, 4, 3)); // lintel at 1.8 -> opening 1.8 tall
			} else {
				world.add(new HaloSolver.Box(x, 0, 2, x + 1, 4, 3));
			}
		}
		simulate("Sneak opening 1x1.8", world, 0.5, 0.0, 0.5, 2.5, 0.0, 1.5, 0.0);
	}

	// Scenario 5: sneaking player travels down a 1.5-block-tall low corridor (ceiling at y=1.5).
	// The overhead halo cannot fit and must ride low the whole way.
	private static void scenarioLowTunnel15() {
		List<HaloSolver.Box> world = ground();
		for (int x = -4; x <= 4; x++) {
			for (int z = 0; z <= 4; z++) {
				world.add(new HaloSolver.Box(x, 1.5, z, x + 1, 4, z + 1)); // low ceiling
			}
		}
		simulate("Low tunnel 1.5 tall", world, 0.5, 0.0, 0.5, 3.5, 0.0, 1.5, 0.0);
	}

	// Scenario 6: prone/crawling player (bbHeight 0.6) advances through a 1-block-tall tunnel
	// (ceiling at y=1). The halo must ride just above the player's back, under the roof.
	private static void scenarioProneTunnel1() {
		List<HaloSolver.Box> world = ground();
		for (int x = -4; x <= 4; x++) {
			for (int z = 0; z <= 4; z++) {
				world.add(new HaloSolver.Box(x, 1, z, x + 1, 4, z + 1)); // 1-tall ceiling
			}
		}
		simulate("Prone tunnel 1 tall", world, 0.5, 0.0, 0.5, 3.5, 0.0, 0.6, 0.0);
	}

	// Build a sealed 1-wide box interior spanning x[0,1] z[0,1], floor at y=0, ceiling at y=innerH,
	// with all six faces solid (floor, ceiling, 4 walls). The player stands at the center.
	private static List<HaloSolver.Box> sealedBox(double innerH) {
		List<HaloSolver.Box> world = new ArrayList<>();
		double lo = -2, hi = 3;
		// floor and ceiling slabs covering the whole footprint
		world.add(new HaloSolver.Box(lo, -1, lo, hi, 0, hi));
		world.add(new HaloSolver.Box(lo, innerH, lo, hi, innerH + 1, hi));
		// four walls around the single interior cell x[0,1] z[0,1]
		world.add(new HaloSolver.Box(lo, -1, lo, 0, innerH + 1, hi));     // -x wall
		world.add(new HaloSolver.Box(1, -1, lo, hi, innerH + 1, hi));     // +x wall
		world.add(new HaloSolver.Box(0, -1, lo, 1, innerH + 1, 0));       // -z wall
		world.add(new HaloSolver.Box(0, -1, 1, 1, innerH + 1, hi));       // +z wall
		return world;
	}

	// Scenarios 7-11: player fully boxed in (all six faces) inside a cell of the given inner height.
	// A horizontal ring can always find a free vertical slot as long as the cell is taller than the
	// ring's thickness; the solver must settle collision-free.
	private static void scenarioSealedBox(String name, double innerH, double bbHeight) {
		List<HaloSolver.Box> world = sealedBox(innerH);
		double ringThickness = 2 * (MODEL_T + HaloSolver.FIT_MARGIN);
		boolean expectFree = innerH > ringThickness;
		simulateStatic(name, world, 0.5, 0.5, 0.0, bbHeight, expectFree);
	}

	// Scenario 12: the ring's owner is buried — the interior cell is completely solid (no air at all).
	// There is no valid placement; the solver must not crash, loop, or return NaN/infinite values.
	private static void scenarioStuckInBlock() {
		List<HaloSolver.Box> world = new ArrayList<>();
		world.add(new HaloSolver.Box(-3, -3, -3, 4, 5, 4)); // solid everywhere around the player
		simulateStatic("Stuck in solid block", world, 0.5, 0.5, 0.0, 1.8, false);
	}

	// Run the solver for many ticks at a fixed player position (no movement) and assert convergence.
	// expectFree=true  -> the ring must end collision-free (a slot exists).
	// expectFree=false -> no slot exists; we only require finite, bounded output (no crash/NaN).
	private static void simulateStatic(String name, List<HaloSolver.Box> world,
			double px, double pz, double py, double bbHeight, boolean expectFree) {
		double homeY = bbHeight + BASE_HEIGHT;
		double ax = 0, ay = homeY, az = 0, atx = 0, atz = 0;
		HaloSolver.FreeTest ft = freeTest(world);
		for (int i = 0; i < 200; i++) {
			HaloSolver solver = new HaloSolver(px, py, pz, bbHeight, bodyYawDeg(), MODEL_R, MODEL_T, ax, ay, az, atx, atz, ft);
			double[] moved = solver.solve(homeY);
			ax = moved[0];
			ay = moved[1];
			az = moved[2];
			atx = moved[3];
			atz = moved[4];
		}
		HaloSolver check = new HaloSolver(px, py, pz, bbHeight, bodyYawDeg(), MODEL_R, MODEL_T, ax, ay, az, atx, atz, ft);
		boolean finite = isFinite(ax) && isFinite(ay) && isFinite(az)
			&& Math.abs(ax) < 5 && Math.abs(ay) < 5 && Math.abs(az) < 5;
		boolean pass = finite && (expectFree == check.fits(ax, ay, az, atx, atz));
		if (!pass) {
			failures++;
		}
		System.out.printf("[%s] %s%n        final offset (%.3f, %.3f, %.3f) tilt (%.0f, %.0f), expectFree=%b, actualFree=%b, finite=%b%n",
			pass ? "PASS" : "FAIL", name, ax, ay, az, atx, atz, expectFree, check.fits(ax, ay, az, atx, atz), finite);
	}

	private static double bodyYawDeg() {
		return 0.0;
	}

	private static boolean isFinite(double v) {
		return !Double.isNaN(v) && !Double.isInfinite(v);
	}

	// Scenario 13: running-and-jumping down a long 100x2x1 corridor (ceiling at y=2).
	// The overhead halo (~y 2.25) does not fit under the 2-tall roof, so it must ride low; each
	// jump lifts the player and must NOT let the halo pop up into the ceiling.
	private static void scenarioRunJumpCorridor() {
		List<HaloSolver.Box> world = new ArrayList<>();
		for (int x = -1; x <= 1; x++) {
			for (int z = -2; z <= 102; z++) {
				world.add(block(x, -1, z));            // floor
				world.add(new HaloSolver.Box(x, 2, z, x + 1, 5, z + 1)); // ceiling at y=2
			}
		}
		simulateJump("Run-jump 100x2x1 corridor", world, 0.5, 0.0, 0.5, 100.0, 1.8, 0.0, 8, 0.9);
	}

	// Scenario 14: running and jumping across open flat ground. The halo must follow but the
	// horizontal-only inertia means jumps should not fling it upward; it settles back to home.
	private static void scenarioRunJumpFlat() {
		List<HaloSolver.Box> world = new ArrayList<>();
		for (int x = -2; x <= 2; x++) {
			for (int z = -2; z <= 102; z++) {
				world.add(block(x, -1, z));
			}
		}
		simulateJump("Run-jump flat ground", world, 0.5, 0.0, 0.5, 60.0, 1.8, 0.0, 6, 1.0);
	}

	// Scenario 15: crawling (bbHeight 0.6) forward through a 1-tall tunnel while repeatedly trying
	// to jump. The 1-block ceiling means "jumps" barely lift the player; the halo must stay tucked
	// under the roof and never clip it.
	private static void scenarioCrawlJumpTunnel1() {
		List<HaloSolver.Box> world = new ArrayList<>();
		for (int x = -1; x <= 1; x++) {
			for (int z = -2; z <= 42; z++) {
				world.add(block(x, -1, z));
				world.add(new HaloSolver.Box(x, 1, z, x + 1, 5, z + 1)); // 1-tall ceiling
			}
		}
		// crawling: jumps are tiny (0.15) and clamped by the low roof
		simulateJump("Crawl-jump 1-tall tunnel", world, 0.5, 0.0, 0.5, 40.0, 0.6, 0.0, 12, 0.15);
	}

	// Simulate running along +z while jumping periodically. Player py follows a parabolic hop arc,
	// clamped so the head never passes through the ceiling (mimicking real MC vertical collision).
	// jumpEveryTicks: ticks between hops; jumpPeak: apex height of a hop in blocks.
	private static void simulateJump(String name, List<HaloSolver.Box> world,
			double sx, double sz, double ex, double ez, double bbHeight, double bodyYawDeg,
			int jumpEveryTicks, double jumpPeak) {
		int ticks = 400;
		double homeY = bbHeight + BASE_HEIGHT;
		double ax = 0, ay = homeY, az = 0, atx = 0, atz = 0;
		int violations = 0;
		double maxY = -Double.MAX_VALUE, minY = Double.MAX_VALUE;
		HaloSolver.FreeTest ft = freeTest(world);
		for (int i = 1; i <= ticks; i++) {
			double t = (double) i / ticks;
			double px = sx + (ex - sx) * t;
			double pz = sz + (ez - sz) * t;
			double py = groundY(world, px, pz) + hopHeight(i, jumpEveryTicks, jumpPeak);
			py = clampUnderCeiling(world, px, pz, py, bbHeight);
			HaloSolver solver = new HaloSolver(px, py, pz, bbHeight, bodyYawDeg, MODEL_R, MODEL_T, ax, ay, az, atx, atz, ft);
			double[] moved = solver.solve(homeY);
			ax = moved[0];
			ay = moved[1];
			az = moved[2];
			atx = moved[3];
			atz = moved[4];
			maxY = Math.max(maxY, py + ay);
			minY = Math.min(minY, py + ay);
			if (!solver.fitsTolerant(ax, ay, az, atx, atz)) {
				violations++;
			}
		}
		boolean pass = violations == 0;
		if (!pass) {
			failures++;
		}
		System.out.printf("[%s] %s%n        world halo Y span [%.3f, %.3f], per-tick clip violations %d%n",
			pass ? "PASS" : "FAIL", name, minY, maxY, violations);
	}

	// parabolic hop: 0 at rest, peaking mid-cycle; returns extra height above the floor.
	private static double hopHeight(int tick, int period, double peak) {
		int phase = tick % period;
		double f = (double) phase / period; // 0..1
		return peak * 4 * f * (1 - f);       // parabola, 0 at ends, peak at f=0.5
	}

	// top surface Y at (px,pz): highest solid box top strictly below a probe, else 0.
	private static double groundY(List<HaloSolver.Box> world, double px, double pz) {
		double best = 0.0;
		for (HaloSolver.Box b : world) {
			if (px > b.minX() && px < b.maxX() && pz > b.minZ() && pz < b.maxZ() && b.maxY() <= 0.5) {
				best = Math.max(best, b.maxY());
			}
		}
		return best;
	}

	// prevent the player's head from entering a ceiling: lower py until the body box is collision-free.
	private static double clampUnderCeiling(List<HaloSolver.Box> world, double px, double pz, double py, double bbHeight) {
		double hw = 0.3;
		for (int guard = 0; guard < 50; guard++) {
			HaloSolver.Box body = new HaloSolver.Box(px - hw, py, pz - hw, px + hw, py + bbHeight, pz + hw);
			if (noCollision(world, body)) {
				return py;
			}
			py -= 0.05;
			if (py <= groundY(world, px, pz)) {
				return groundY(world, px, pz);
			}
		}
		return py;
	}

	// Scenario 16: a narrow vertical slot only ~0.25 wide in X but tall in Y and deep in Z.
	// A flat ring (needs ~0.96 in X) cannot fit anywhere; only an upright ring (thin in X)
	// can occupy the slot. The solver must therefore choose a tilted/vertical orientation.
	private static void scenarioVerticalSlot() {
		List<HaloSolver.Box> world = new ArrayList<>();
		// solid everywhere except a thin vertical slot x in [0.375, 0.625], z in [-1, 2], y in [-1, 4]
		double slotMinX = 0.375, slotMaxX = 0.625;
		world.add(new HaloSolver.Box(-3, -1, -3, slotMinX, 4, 4));   // -x solid
		world.add(new HaloSolver.Box(slotMaxX, -1, -3, 4, 4, 4));    // +x solid
		world.add(new HaloSolver.Box(slotMinX, -1, -3, slotMaxX, 4, -1)); // -z cap
		world.add(new HaloSolver.Box(slotMinX, -1, 2, slotMaxX, 4, 4));   // +z cap
		double px = 0.5, pz = 0.5, py = 0.0, bbHeight = 1.8;
		double homeY = bbHeight + BASE_HEIGHT;
		double ax = 0, ay = homeY, az = 0, atx = 0, atz = 0;
		HaloSolver.FreeTest ft = freeTest(world);
		for (int i = 0; i < 200; i++) {
			HaloSolver solver = new HaloSolver(px, py, pz, bbHeight, 0.0, MODEL_R, MODEL_T, ax, ay, az, atx, atz, ft);
			double[] moved = solver.solve(homeY);
			ax = moved[0];
			ay = moved[1];
			az = moved[2];
			atx = moved[3];
			atz = moved[4];
		}
		HaloSolver check = new HaloSolver(px, py, pz, bbHeight, 0.0, MODEL_R, MODEL_T, ax, ay, az, atx, atz, ft);
		boolean free = check.fits(ax, ay, az, atx, atz);
		boolean tilted = (Math.abs(atx) + Math.abs(atz)) > 1.0;
		boolean pass = free && tilted;
		if (!pass) {
			failures++;
		}
		System.out.printf("[%s] Vertical slot 0.25 wide%n        final offset (%.3f, %.3f, %.3f) tilt (%.0f, %.0f), free=%b, tilted=%b%n",
			pass ? "PASS" : "FAIL", ax, ay, az, atx, atz, free, tilted);
	}

	// Scenario 17: player walks up to a tall wall and STOPS with the body flush against it. The ring
	// (wider than the body) overhangs the wall by ~0.1, which is within WALL_TOLERANCE, so the ring
	// should barely react — stay near home height (no sinking) with only a tiny sidestep/tilt.
	private static void scenarioWallToleranceStanding() {
		List<HaloSolver.Box> world = ground();
		for (int x = -4; x <= 4; x++) {
			for (int y = 0; y <= 4; y++) {
				world.add(new HaloSolver.Box(x, y, 2, x + 1, y + 1, 3)); // solid wall at z in [2,3]
			}
		}
		double homeY = 1.8 + BASE_HEIGHT;
		double ax = 0, ay = homeY, az = 0, atx = 0, atz = 0;
		// player body half-width ~0.3 (solver's PLAYER_HALF_W); stand so the body is flush: pz=2-0.3=1.7
		double px = 0.5, pz = 1.7, py = 0.0, bbHeight = 1.8;
		HaloSolver.FreeTest ft = freeTest(world);
		for (int i = 0; i < 200; i++) {
			HaloSolver solver = new HaloSolver(px, py, pz, bbHeight, 0.0,
				MODEL_R, MODEL_T, ax, ay, az, atx, atz, 0.0, 0.0, ft); // standing still: no move
			double[] moved = solver.solve(homeY);
			ax = moved[0];
			ay = moved[1];
			az = moved[2];
			atx = moved[3];
			atz = moved[4];
		}
		HaloSolver check = new HaloSolver(px, py, pz, bbHeight, 0.0, MODEL_R, MODEL_T, ax, ay, az, atx, atz, ft);
		boolean free = check.fits(ax, ay, az, atx, atz);   // fully clear of the wall (exact box)
		boolean noSink = ay > homeY - 0.15;                 // did NOT drop meaningfully
		boolean smallMove = Math.hypot(ax, az) < 0.35;      // a modest reposition, not a big detour
		boolean pass = free && noSink && smallMove;
		if (!pass) {
			failures++;
		}
		System.out.printf("[%s] Wall tolerance standing%n        final offset (%.3f, %.3f, %.3f) tilt (%.0f, %.0f), free=%b, noSink=%b, smallMove=%b%n",
			pass ? "PASS" : "FAIL", ax, ay, az, atx, atz, free, noSink, smallMove);
	}

	// Scenario 18: player walks forward INTO a 1x2 doorway. Because the player keeps advancing, any
	// sideways dodge is forward-unstable, so the ring should sink through the opening rather than
	// sidestep first. We assert the horizontal offset stays small throughout AND the ring ends low.
	private static void scenarioEnterOpeningSinks() {
		List<HaloSolver.Box> world = ground();
		for (int x = -4; x <= 4; x++) {
			if (x == 0) {
				world.add(new HaloSolver.Box(0, 2, 2, 1, 4, 3)); // lintel above the 2-tall hole
			} else {
				world.add(new HaloSolver.Box(x, 0, 2, x + 1, 4, 3));
			}
		}
		int ticks = 160;
		double homeY = 1.8 + BASE_HEIGHT;
		double ax = 0, ay = homeY, az = 0, atx = 0, atz = 0;
		double bbHeight = 1.8, py = 0.0;
		double sx = 0.5, sz = 0.0, ex = 0.5, ez = 2.5;
		double prevPx = sx, prevPz = sz;
		double maxHoriz = 0.0;
		int violations = 0;
		HaloSolver.FreeTest ft = freeTest(world);
		for (int i = 1; i <= ticks; i++) {
			double t = (double) i / ticks;
			double px = sx + (ex - sx) * t;
			double pz = sz + (ez - sz) * t;
			double mvX = px - prevPx, mvZ = pz - prevPz;
			prevPx = px;
			prevPz = pz;
			HaloSolver solver = new HaloSolver(px, py, pz, bbHeight, 0.0,
				MODEL_R, MODEL_T, ax, ay, az, atx, atz, mvX, mvZ, ft);
			double[] moved = solver.solve(homeY);
			ax = moved[0];
			ay = moved[1];
			az = moved[2];
			atx = moved[3];
			atz = moved[4];
			maxHoriz = Math.max(maxHoriz, Math.hypot(ax, az));
			// transient overhang within tolerance is acceptable during the sink; count only real clips
			if (!solver.fitsTolerant(ax, ay, az, atx, atz)) {
				violations++;
			}
		}
		boolean sank = ay < homeY - 0.2;      // resolved by sinking
		boolean noBigSidestep = maxHoriz < 0.3; // never made a large sideways detour
		boolean pass = violations == 0 && sank && noBigSidestep;
		if (!pass) {
			failures++;
		}
		System.out.printf("[%s] Enter opening sinks%n        final offset (%.3f, %.3f, %.3f), maxHoriz %.3f, sank=%b, noBigSidestep=%b, violations %d%n",
			pass ? "PASS" : "FAIL", ax, ay, az, maxHoriz, sank, noBigSidestep, violations);
	}
}
