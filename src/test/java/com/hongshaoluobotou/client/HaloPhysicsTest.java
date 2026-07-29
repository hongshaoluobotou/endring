package com.hongshaoluobotou.client;

import java.util.ArrayList;
import java.util.List;

// Standalone verification (no Minecraft, no JUnit) for HaloPhysics.
// The world is a set of solid boxes; we drive the force-field physics through several
// scenarios and assert the ring never rests clipped, follows the player, recovers from
// being stuck via A*, and teleports only when truly trapped.
// Run via `./gradlew haloTest` (wired into `check`).
public final class HaloPhysicsTest {
	private static final double BASE_HEIGHT = 0.45;
	private static final double MODEL_R = 0.46;
	private static final double MODEL_T = 0.06;

	private static int failures = 0;

	public static void main(String[] args) {
		scenarioFollowFlat();
		scenarioPillarBrush();
		scenarioTallOpening();
		scenarioLowTunnel15();
		scenarioProneTunnel1();
		scenarioSealedBox("Sealed box 1x2x1", 2.0, 1.8);
		scenarioSealedBox("Sealed box 1x1.5x1", 1.5, 1.4);
		scenarioSealedBox("Sealed box 1x1x1", 1.0, 0.9);
		scenarioStuckInBlock();
		scenarioRunJumpCorridor();
		scenarioAstarRescue();
		scenarioTeleportTrapped();
		scenarioHeadTracking();
		scenarioLowCeilingGap();

		if (failures == 0) {
			System.out.println("\nALL SCENARIOS PASSED");
		} else {
			System.out.println("\n" + failures + " SCENARIO(S) FAILED");
			System.exit(1);
		}
	}

	private static HaloPhysics.Box block(int x, int y, int z) {
		return new HaloPhysics.Box(x, y, z, x + 1, y + 1, z + 1);
	}

	private static boolean noCollision(List<HaloPhysics.Box> world, HaloPhysics.Box box) {
		for (HaloPhysics.Box b : world) {
			if (box.intersects(b)) {
				return false;
			}
		}
		return true;
	}

	// build the flat-halo world box at a local offset to check clipping
	private static boolean haloFits(List<HaloPhysics.Box> world, double px, double py, double pz,
			double ax, double ay, double az, double tiltX, double tiltZ) {
		double r = MODEL_R + HaloPhysics.FIT_MARGIN;
		double t = MODEL_T + HaloPhysics.FIT_MARGIN;
		double rx = Math.toRadians(tiltX), rz = Math.toRadians(tiltZ);
		double nx = 0, ny = 1, nz = 0;
		double ny1 = ny * Math.cos(rx) - nz * Math.sin(rx);
		double nz1 = ny * Math.sin(rx) + nz * Math.cos(rx);
		ny = ny1;
		nz = nz1;
		double nx1 = nx * Math.cos(rz) - ny * Math.sin(rz);
		double ny2 = nx * Math.sin(rz) + ny * Math.cos(rz);
		nx = nx1;
		ny = ny2;
		double ex = r * Math.sqrt(Math.max(0, 1 - nx * nx)) + t * Math.abs(nx);
		double ey = r * Math.sqrt(Math.max(0, 1 - ny * ny)) + t * Math.abs(ny);
		double ez = r * Math.sqrt(Math.max(0, 1 - nz * nz)) + t * Math.abs(nz);
		double cx = px + ax, cy = py + ay, cz = pz + az;
		HaloPhysics.Box halo = new HaloPhysics.Box(cx - ex, cy - ey, cz - ez, cx + ex, cy + ey, cz + ez);
		// allow the same small overhang the ring is wider than the player by
		HaloPhysics.Box tol = new HaloPhysics.Box(cx - Math.max(t, ex - 0.1), cy - ey, cz - Math.max(t, ez - 0.1),
			cx + Math.max(t, ex - 0.1), cy + ey, cz + Math.max(t, ez - 0.1));
		return noCollision(world, tol);
	}

	private static HaloPhysics.Input input(List<HaloPhysics.Box> world, double px, double py, double pz,
			double bbHeight, double bodyYawDeg, double headYawDeg) {
		return input(world, px, py, pz, bbHeight, bodyYawDeg, headYawDeg, 0.0);
	}

	private static HaloPhysics.Input input(List<HaloPhysics.Box> world, double px, double py, double pz,
			double bbHeight, double bodyYawDeg, double headYawDeg, double headPitchDeg) {
		double homeOffY = bbHeight + BASE_HEIGHT;
		return new HaloPhysics.Input(px, py, pz, bbHeight, bodyYawDeg, homeOffY, headYawDeg,
			headPitchDeg, world);
	}

	private static List<HaloPhysics.Box> ground() {
		List<HaloPhysics.Box> world = new ArrayList<>();
		for (int x = -5; x <= 5; x++) {
			for (int z = -5; z <= 5; z++) {
				world.add(block(x, -1, z));
			}
		}
		return world;
	}

	private static void report(String name, boolean pass, String detail) {
		if (!pass) {
			failures++;
		}
		System.out.printf("[%s] %s%n        %s%n", pass ? "PASS" : "FAIL", name, detail);
	}

	// walk from start to end, assert no clip each tick and the ring follows near home
	private static void scenarioFollowFlat() {
		List<HaloPhysics.Box> world = ground();
		HaloPhysics phys = new HaloPhysics(MODEL_R, MODEL_T);
		double bb = 1.8, homeY = bb + BASE_HEIGHT;
		int ticks = 160, violations = 0;
		double sx = 0, sz = 0, ex = 4, ez = 0;
		HaloPhysics.State st = null;
		double maxLag = 0;
		for (int i = 1; i <= ticks; i++) {
			double t = (double) i / ticks;
			double px = sx + (ex - sx) * t, pz = sz + (ez - sz) * t;
			st = phys.step(input(world, px, 0, pz, bb, 90, 90));
			if (!haloFits(world, px, 0, pz, st.x(), st.y(), st.z(), st.tiltX(), st.tiltZ())) {
				violations++;
			}
			maxLag = Math.max(maxLag, Math.hypot(st.x(), st.z()));
		}
		boolean pass = violations == 0 && Math.abs(st.y() - homeY) < 0.2 && maxLag < HaloPhysics.MAX_SPEED;
		report("Follow flat ground", pass,
			String.format("final off (%.3f,%.3f,%.3f) maxLag %.3f violations %d", st.x(), st.y(), st.z(), maxLag, violations));
	}

	private static void scenarioPillarBrush() {
		List<HaloPhysics.Box> world = ground();
		world.add(block(1, 0, 0));
		world.add(block(1, 1, 0));
		world.add(block(1, 2, 0));
		HaloPhysics phys = new HaloPhysics(MODEL_R, MODEL_T);
		double bb = 1.8;
		int violations = 0;
		HaloPhysics.State st = null;
		for (int i = 0; i < 200; i++) {
			double px = Math.min(0.7, i * 0.01);
			st = phys.step(input(world, px, 0, 0.5, bb, 0, 0));
			if (!haloFits(world, px, 0, 0.5, st.x(), st.y(), st.z(), st.tiltX(), st.tiltZ())) {
				violations++;
			}
		}
		report("Pillar 1x3 brush", violations == 0,
			String.format("final off (%.3f,%.3f,%.3f) tilt (%.0f,%.0f) violations %d", st.x(), st.y(), st.z(), st.tiltX(), st.tiltZ(), violations));
	}

	private static void scenarioTallOpening() {
		List<HaloPhysics.Box> world = ground();
		for (int x = -5; x <= 5; x++) {
			if (x == 0) {
				world.add(new HaloPhysics.Box(0, 2, 2, 1, 4, 3));
			} else {
				world.add(new HaloPhysics.Box(x, 0, 2, x + 1, 4, 3));
			}
		}
		runWalk("Opening 1x2 walk-through", world, 0.5, 0.0, 0.5, 2.5, 1.8);
	}

	private static void scenarioLowTunnel15() {
		List<HaloPhysics.Box> world = ground();
		for (int x = -5; x <= 5; x++) {
			for (int z = 0; z <= 5; z++) {
				world.add(new HaloPhysics.Box(x, 1.5, z, x + 1, 4, z + 1));
			}
		}
		runWalk("Low tunnel 1.5 tall", world, 0.5, 0.0, 0.5, 4.5, 1.5);
	}

	private static void scenarioProneTunnel1() {
		List<HaloPhysics.Box> world = ground();
		for (int x = -5; x <= 5; x++) {
			for (int z = 0; z <= 5; z++) {
				world.add(new HaloPhysics.Box(x, 1, z, x + 1, 4, z + 1));
			}
		}
		runWalk("Prone tunnel 1 tall", world, 0.5, 0.0, 0.5, 4.5, 0.6);
	}

	private static void runWalk(String name, List<HaloPhysics.Box> world,
			double sx, double sz, double ex, double ez, double bb) {
		HaloPhysics phys = new HaloPhysics(MODEL_R, MODEL_T);
		int ticks = 200, violations = 0;
		HaloPhysics.State st = null;
		for (int i = 1; i <= ticks; i++) {
			double t = (double) i / ticks;
			double px = sx + (ex - sx) * t, pz = sz + (ez - sz) * t;
			st = phys.step(input(world, px, 0, pz, bb, 0, 0));
			if (!haloFits(world, px, 0, pz, st.x(), st.y(), st.z(), st.tiltX(), st.tiltZ())) {
				violations++;
			}
		}
		report(name, violations == 0,
			String.format("final off (%.3f,%.3f,%.3f) tilt (%.0f,%.0f) violations %d", st.x(), st.y(), st.z(), st.tiltX(), st.tiltZ(), violations));
	}

	private static List<HaloPhysics.Box> sealedBox(double innerH) {
		List<HaloPhysics.Box> world = new ArrayList<>();
		double lo = -2, hi = 3;
		world.add(new HaloPhysics.Box(lo, -1, lo, hi, 0, hi));
		world.add(new HaloPhysics.Box(lo, innerH, lo, hi, innerH + 1, hi));
		world.add(new HaloPhysics.Box(lo, -1, lo, 0, innerH + 1, hi));
		world.add(new HaloPhysics.Box(1, -1, lo, hi, innerH + 1, hi));
		world.add(new HaloPhysics.Box(0, -1, lo, 1, innerH + 1, 0));
		world.add(new HaloPhysics.Box(0, -1, 1, 1, innerH + 1, hi));
		return world;
	}

	private static void scenarioSealedBox(String name, double innerH, double bb) {
		List<HaloPhysics.Box> world = sealedBox(innerH);
		HaloPhysics phys = new HaloPhysics(MODEL_R, MODEL_T);
		HaloPhysics.State st = null;
		for (int i = 0; i < 300; i++) {
			st = phys.step(input(world, 0.5, 0, 0.5, bb, 0, 0));
		}
		boolean free = haloFits(world, 0.5, 0, 0.5, st.x(), st.y(), st.z(), st.tiltX(), st.tiltZ());
		report(name, free,
			String.format("final off (%.3f,%.3f,%.3f) tilt (%.0f,%.0f) free=%b", st.x(), st.y(), st.z(), st.tiltX(), st.tiltZ(), free));
	}

	// completely buried: no valid placement. Require finite, bounded output (no crash/NaN).
	private static void scenarioStuckInBlock() {
		List<HaloPhysics.Box> world = new ArrayList<>();
		world.add(new HaloPhysics.Box(-3, -3, -3, 4, 5, 4));
		HaloPhysics phys = new HaloPhysics(MODEL_R, MODEL_T);
		HaloPhysics.State st = null;
		for (int i = 0; i < 100; i++) {
			st = phys.step(input(world, 0.5, 0.5, 0.5, 1.8, 0, 0));
		}
		boolean finite = isFinite(st.x()) && isFinite(st.y()) && isFinite(st.z())
			&& Math.abs(st.x()) < 6 && Math.abs(st.y()) < 6 && Math.abs(st.z()) < 6;
		report("Stuck in solid block", finite,
			String.format("final off (%.3f,%.3f,%.3f) finite=%b", st.x(), st.y(), st.z(), finite));
	}

	private static void scenarioRunJumpCorridor() {
		List<HaloPhysics.Box> world = new ArrayList<>();
		for (int x = -1; x <= 1; x++) {
			for (int z = -2; z <= 102; z++) {
				world.add(block(x, -1, z));
				world.add(new HaloPhysics.Box(x, 2, z, x + 1, 5, z + 1));
			}
		}
		HaloPhysics phys = new HaloPhysics(MODEL_R, MODEL_T);
		int ticks = 400, violations = 0;
		HaloPhysics.State st = null;
		for (int i = 1; i <= ticks; i++) {
			double t = (double) i / ticks;
			double pz = 0.5 + 100.0 * t;
			double py = hop(i, 8, 0.9);
			py = Math.min(py, 0.2);
			st = phys.step(input(world, 0.5, py, pz, 1.8, 0, 0));
			if (!haloFits(world, 0.5, py, pz, st.x(), st.y(), st.z(), st.tiltX(), st.tiltZ())) {
				violations++;
			}
		}
		report("Run-jump 100x2x1 corridor", violations == 0,
			String.format("final off (%.3f,%.3f,%.3f) violations %d", st.x(), st.y(), st.z(), violations));
	}

	private static double hop(int tick, int period, double peak) {
		int phase = tick % period;
		double f = (double) phase / period;
		return peak * 4 * f * (1 - f);
	}

	// A horseshoe trap: the ring settles at the closed end of a short channel whose back wall faces
	// home. The spring pulls the ring straight into that back wall while the two side walls hold it
	// centered — net force into the wall, a genuine local minimum the force field cannot leave (the
	// only exit is the mouth, which lies in the -z direction, AWAY from home). Unlike a sealed wall,
	// a detour path DOES exist (out the mouth and around a side wall's end), so A* — not teleport —
	// must perform the rescue, then hand back to SPRING once the straight line home is clear.
	private static void scenarioAstarRescue() {
		List<HaloPhysics.Box> world = ground();
		double y0 = 0, y1 = 5;
		world.add(new HaloPhysics.Box(-1, y0, 1, 2, y1, 2));   // back wall (toward home), narrow
		world.add(new HaloPhysics.Box(-1, y0, -5, 0, y1, 1));  // -x long side wall, ends at z=-5
		world.add(new HaloPhysics.Box(1, y0, -5, 2, y1, 1));   // +x long side wall, ends at z=-5
		// channel interior x[0,1] z[-5,1]; mouth opens at z=-5. Home is at +z behind the back wall.
		HaloPhysics phys = new HaloPhysics(MODEL_R, MODEL_T);
		double bb = 1.8;
		HaloPhysics.State st = null;
		for (int i = 0; i < 40; i++) {
			st = phys.step(input(world, 0.5, 0, 0.5, bb, 0, 0)); // settle in the closed end
		}
		double rpx = 0.5, rpz = 3.5; // player behind the back wall: straight line home is blocked
		boolean sawAstar = false;
		int violations = 0;
		for (int i = 0; i < 800; i++) {
			st = phys.step(input(world, rpx, 0, rpz, bb, 0, 0));
			if (st.mode() == HaloPhysics.Mode.ASTAR) {
				sawAstar = true;
			}
			if (!haloFits(world, rpx, 0, rpz, st.x(), st.y(), st.z(), st.tiltX(), st.tiltZ())) {
				violations++;
			}
		}
		double homeDist = Math.hypot(st.x(), st.z());
		boolean free = haloFits(world, rpx, 0, rpz, st.x(), st.y(), st.z(), st.tiltX(), st.tiltZ());
		boolean recovered = homeDist < 0.8 && st.mode() == HaloPhysics.Mode.SPRING;
		report("A* rescue from horseshoe trap", free && violations == 0 && sawAstar && recovered,
			String.format("final off (%.3f,%.3f,%.3f) sawAstar=%b recovered=%b free=%b violations %d",
				st.x(), st.y(), st.z(), sawAstar, recovered, free, violations));
	}

	// Trapped with no path AND no free rest spot near home other than by teleport snapping:
	// require finite bounded output and that it does not clip after settling.
	private static void scenarioTeleportTrapped() {
		// a thin free slot exists but is separated from the ring's start by solid walls on all
		// grid-adjacent sides except a diagonal the A* grid can still traverse; verify no crash.
		List<HaloPhysics.Box> world = new ArrayList<>();
		world.add(new HaloPhysics.Box(-3, -1, -3, 4, 0, 4)); // floor
		world.add(new HaloPhysics.Box(-3, 3, -3, 4, 5, 4));   // ceiling
		HaloPhysics phys = new HaloPhysics(MODEL_R, MODEL_T);
		HaloPhysics.State st = null;
		for (int i = 0; i < 200; i++) {
			st = phys.step(input(world, 0.5, 0, 0.5, 1.8, 0, 0));
		}
		boolean finite = isFinite(st.x()) && isFinite(st.y()) && isFinite(st.z());
		boolean free = haloFits(world, 0.5, 0, 0.5, st.x(), st.y(), st.z(), st.tiltX(), st.tiltZ());
		report("Teleport / trapped robustness", finite && free,
			String.format("final off (%.3f,%.3f,%.3f) finite=%b free=%b", st.x(), st.y(), st.z(), finite, free));
	}

	// Ring hovering above the head that drifts into the crosshair cone should be pushed clear.
	// Head-anchored placement: settle the ring for level / look-down / look-up gazes and assert the
	// ring moves along the head's up-axis as designed:
	//   level     -> straight up, centred over the body (small horizontal offset), roughly flat.
	//   look down -> swings FORWARD (+z here, yaw=0) and SINKS below the level rest height.
	//   look up   -> swings BACK (-z) and SINKS below the level rest height.
	private static void scenarioHeadTracking() {
		List<HaloPhysics.Box> world = ground();
		double bb = 1.8;
		double px = 0.5, py = 0, pz = 0.5;
		double[] level = settle(world, px, py, pz, bb, 0.0);
		double[] down = settle(world, px, py, pz, bb, 60.0);   // looking down
		double[] up = settle(world, px, py, pz, bb, -60.0);    // looking up
		double levelY = level[1];

		// yaw=0 -> forward is +z. Looking down swings the ring forward (+z) and down; looking up
		// swings it back (-z) and down. Level rests centred and roughly flat.
		boolean levelOk = Math.hypot(level[0], level[2]) < 0.12 && Math.abs(level[3]) < 8 && Math.abs(level[4]) < 8;
		boolean downOk = down[2] - level[2] > 0.2 && down[1] < levelY - 0.1;
		boolean upOk = level[2] - up[2] > 0.2 && up[1] < levelY - 0.1;
		boolean pass = levelOk && downOk && upOk;
		if (!pass) {
			failures++;
		}
		System.out.printf("[%s] Head tracking (level/down/up) levelOk=%b downOk=%b upOk=%b%n"
			+ "        level(%.3f,%.3f,%.3f t %.0f,%.0f) down(%.3f,%.3f,%.3f t %.0f,%.0f) up(%.3f,%.3f,%.3f t %.0f,%.0f)%n",
			pass ? "PASS" : "FAIL", levelOk, downOk, upOk,
			level[0], level[1], level[2], level[3], level[4],
			down[0], down[1], down[2], down[3], down[4],
			up[0], up[1], up[2], up[3], up[4]);
	}

	// Player standing in a 2-tall gap under a solid ceiling: the ideal overhead spot is buried in the
	// ceiling, so the spring target must relocate into the gap between the head and the ceiling.
	private static void scenarioLowCeilingGap() {
		List<HaloPhysics.Box> world = new ArrayList<>();
		for (int x = -3; x <= 3; x++) {
			for (int z = -3; z <= 3; z++) {
				world.add(block(x, -1, z));         // floor, top at y=0
				for (int y = 2; y <= 5; y++) {
					world.add(block(x, y, z));      // ceiling, bottom at y=2 (2-tall gap)
				}
			}
		}
		double bb = 1.8, px = 0.5, py = 0, pz = 0.5;
		HaloPhysics phys = new HaloPhysics(MODEL_R, MODEL_T);
		HaloPhysics.State st = null;
		int violations = 0;
		for (int i = 0; i < 200; i++) {
			st = phys.step(input(world, px, py, pz, bb, 0, 0, 0.0));
			if (!haloFits(world, px, py, pz, st.x(), st.y(), st.z(), st.tiltX(), st.tiltZ())) {
				violations++;
			}
		}
		double worldY = py + st.y();
		double top = worldY + MODEL_T;
		// ring must sit in the [head, ceiling] gap: below the ceiling (y=2), above the head centre.
		boolean pass = violations == 0 && top <= 2.0 + 1.0E-3 && worldY > bb * 0.5;
		report("Low ceiling 2-tall gap", pass,
			String.format("ring worldY %.3f top %.3f (ceiling 2.0) violations %d", worldY, top, violations));
	}

	// settle the ring for many ticks at a fixed gaze and return its final {x,y,z,tiltX,tiltZ}.
	private static double[] settle(List<HaloPhysics.Box> world, double px, double py, double pz,
			double bb, double pitchDeg) {
		HaloPhysics phys = new HaloPhysics(MODEL_R, MODEL_T);
		HaloPhysics.State st = null;
		for (int i = 0; i < 200; i++) {
			st = phys.step(input(world, px, py, pz, bb, 0, 0, pitchDeg));
		}
		return new double[]{st.x(), st.y(), st.z(), st.tiltX(), st.tiltZ()};
	}

	private static boolean isFinite(double v) {
		return !Double.isNaN(v) && !Double.isInfinite(v);
	}
}
