package com.hongshaoluobotou.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

// Pure, Minecraft-independent force-field physics for the halo ring.
//
// The ring is a thin disc that hovers above the player's head and follows head/body
// motion with spring inertia, is pushed out of blocks by repulsion + a hard positional
// resolve (so it never rests clipped), and steps out of the way only when it actually
// covers the camera face cone (the crosshair).
//
// Recovery policy (chosen by design):
//   SPRING  — normal state: a critically-damped spring pulls the ring toward its home
//             anchor; wall repulsion + MTV resolve keep it collision-free.
//   ASTAR   — if the ring gets stuck far from home for a while (spring can't recover and a
//             block sits between the ring and home), run A* on a coarse grid and walk the
//             path. As soon as the straight line ring->home is clear again, drop back to SPRING.
//   TELEPORT— if A* can't find any path either, snap directly to home (or nearest free spot).
//
// All world geometry is a snapshot List<Box> (block-thread reads happen elsewhere); this
// class touches no Minecraft API so it is unit-testable and safe to run off-thread.
public final class HaloPhysics {
	public static final double FIT_MARGIN = 0.02;

	// --- spring / integration ---
	public static final double SPRING_K = 1.1;       // pull strength toward home
	public static final double DAMPING = 0.78;       // velocity retention per tick
	public static final int SUBSTEPS = 4;            // integration sub-steps per tick
	public static final double MAX_SPEED = 3.5;      // clamp per-tick displacement (blocks)

	// --- wall repulsion / hard resolve ---
	public static final int RESOLVE_ITERS = 4;
	// contact constraint solver: when the ring touches a block face, the spring force and velocity
	// components pointing INTO that face are projected out, so only the tangential part remains and the
	// ring slides along the surface instead of bouncing. CONTACT_SKIN is the thin band in which a face
	// is treated as "in contact" (so the into-wall push is cancelled just before it penetrates). A
	// gentle Baumgarte bias (BIAS_K, capped by BIAS_MAX) eases any residual penetration back out
	// without injecting the energy that used to cause the bounce.
	public static final double CONTACT_SKIN = 0.04;
	public static final double BIAS_K = 0.2;
	public static final double BIAS_MAX = 0.08;

	// --- body preference (the ring may encircle the torso, but prefers not to) ---
	public static final double PLAYER_HALF_W = 0.3;
	public static final double BODY_CORE = 0.14;

	// --- orientation ---
	public static final double TILT_K = 0.35;
	public static final double TILT_DAMPING = 0.7;

	// --- head-anchored placement ---
	// the ring orbits a pivot near the head at a fixed radius, along the head's up-axis rotated by
	// pitch: level -> straight up (flat, centred); look down -> swings forward and sinks (tilts
	// forward); look up -> swings back and sinks (tilts back). The pivot is the head centre.
	public static final double PIVOT_HEIGHT_FRAC = 0.9; // pivot Y = py + bbHeight * this
	public static final double MAX_PITCH_DEG = 75.0;    // clamp so extreme pitch doesn't fling the ring

	// when the ideal head-normal spot is inside a block, slide the target along the normal ray to the
	// nearest gap. Keep at least this clearance from the head, and probe a little past the ideal radius.
	public static final double HOME_HEAD_CLEARANCE = 0.05;
	public static final double HOME_RAY_OVERSHOOT = 1.0;

	// --- stuck detection / A* rescue ---
	public static final double STUCK_DIST = 0.6;      // "far" from home
	public static final double STUCK_PROGRESS = 0.02; // min home-distance gained per tick to count as progress
	public static final int STUCK_TICKS = 6;          // sustained no-progress ticks before A* kicks in
	public static final double GRID = 0.25;          // fit-probe resolution
	public static final double PATH_GRID = 0.5;      // coarser A* cell size (fewer nodes, faster)
	public static final int ASTAR_MAX_NODES = 20000;
	public static final double ASTAR_HEUR_WEIGHT = 1.4; // >1: greedier, finds a route far faster
	public static final double ASTAR_SPEED = 0.35;   // per-tick travel along the path
	public static final double ASTAR_RANGE = 6.0;    // grid half-extent around player

	private static final double DEG_TO_RAD = Math.PI / 180.0;

	private static final double[][] ORIENTATIONS = {
		{0, 0}, {45, 0}, {0, 45}, {90, 0}, {0, 90},
	};

	public enum Mode { SPRING, ASTAR }

	// axis-aligned box in world space
	public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		public boolean intersects(Box o) {
			return this.minX < o.maxX && this.maxX > o.minX
				&& this.minY < o.maxY && this.maxY > o.minY
				&& this.minZ < o.maxZ && this.maxZ > o.minZ;
		}

		public Box move(double dx, double dy, double dz) {
			return new Box(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
		}
	}

	// per-tick immutable input snapshot
	public record Input(
			double px, double py, double pz,
			double bbHeight, double bodyYawDeg,
			double homeOffY, double headYawDeg, double headPitchDeg,
			List<Box> world) {
	}

	// immutable result state (local offset from player + tilt)
	public record State(double x, double y, double z, double tiltX, double tiltZ, Mode mode) {
	}

	private final double modelR;
	private final double modelT;

	// persistent world-space state
	private double wx, wy, wz;         // halo center, world space
	private double vx, vy, vz;         // world velocity
	private double tiltX, tiltZ;
	private double tiltVelX, tiltVelZ;
	private Mode mode = Mode.SPRING;
	private int stuckTicks;
	private double prevHomeDist = Double.MAX_VALUE;
	private boolean initialized;

	// active A* path (world-space waypoints) and cursor
	private List<double[]> path;
	private int pathIndex;
	private int astarStallTicks;

	public HaloPhysics(double modelR, double modelT) {
		this.modelR = modelR;
		this.modelT = modelT;
	}

	public Mode mode() {
		return mode;
	}

	// ---- geometry ----

	private double[] discNormal(double tiltXDeg, double tiltZDeg) {
		double rx = tiltXDeg * DEG_TO_RAD;
		double rz = tiltZDeg * DEG_TO_RAD;
		double nx = 0, ny = 1, nz = 0;
		double ny1 = ny * Math.cos(rx) - nz * Math.sin(rx);
		double nz1 = ny * Math.sin(rx) + nz * Math.cos(rx);
		ny = ny1;
		nz = nz1;
		double nx1 = nx * Math.cos(rz) - ny * Math.sin(rz);
		double ny2 = nx * Math.sin(rz) + ny * Math.cos(rz);
		nx = nx1;
		ny = ny2;
		return new double[]{nx, ny, nz};
	}

	// disc AABB half-extents for a given orientation (independent of spin about the normal)
	private double[] extents(double tiltXDeg, double tiltZDeg) {
		double r = modelR + FIT_MARGIN;
		double t = modelT + FIT_MARGIN;
		double[] n = discNormal(tiltXDeg, tiltZDeg);
		double ex = r * Math.sqrt(Math.max(0.0, 1 - n[0] * n[0])) + t * Math.abs(n[0]);
		double ey = r * Math.sqrt(Math.max(0.0, 1 - n[1] * n[1])) + t * Math.abs(n[1]);
		double ez = r * Math.sqrt(Math.max(0.0, 1 - n[2] * n[2])) + t * Math.abs(n[2]);
		return new double[]{ex, ey, ez};
	}

	// halo world-space AABB centered at (cx,cy,cz)
	private Box boxAt(double cx, double cy, double cz, double tiltXDeg, double tiltZDeg) {
		double[] e = extents(tiltXDeg, tiltZDeg);
		return new Box(cx - e[0], cy - e[1], cz - e[2], cx + e[0], cy + e[1], cz + e[2]);
	}

	private boolean isFree(List<Box> world, Box box) {
		for (Box b : world) {
			if (box.intersects(b)) {
				return false;
			}
		}
		return true;
	}

	private boolean fitsFlat(List<Box> world, double cx, double cy, double cz) {
		return isFree(world, boxAt(cx, cy, cz, 0, 0));
	}

	// ---- main tick ----

	public State step(Input in) {
		// Head-anchored orbit: the ring hovers on the head's up-axis, rotated by head pitch, at a
		// fixed radius from a pivot near the head centre. Level -> straight up (flat, centred over the
		// body). Looking down -> the up-axis swings FORWARD, so the ring moves forward, sinks, and (via
		// the aim-tilt) leans forward. Looking up -> it swings BACK, sinks, and leans back.
		double yaw = in.headYawDeg() * DEG_TO_RAD;
		double pitch = Math.max(-MAX_PITCH_DEG, Math.min(MAX_PITCH_DEG, in.headPitchDeg())) * DEG_TO_RAD;
		double fx = -Math.sin(yaw);
		double fz = Math.cos(yaw);
		double pivotY = in.py() + in.bbHeight() * PIVOT_HEIGHT_FRAC;
		double radius = (in.py() + in.homeOffY()) - pivotY; // level rest height == old overhead height
		double dirUp = Math.cos(pitch);
		double dirFwd = Math.sin(pitch); // looking down (pitch>0) pushes along +forward
		// head up-axis (unit): straight up when level, swings forward/back with pitch. This is the
		// "head normal" perpendicular to the line of sight; the ring ideally sits on this ray.
		double upX = fx * dirFwd, upY = dirUp, upZ = fz * dirFwd;
		double homeX = in.px() + upX * radius;
		double homeY = pivotY + upY * radius;
		double homeZ = in.pz() + upZ * radius;

		// if the ideal spot is buried in a block (e.g. player crouched in a 1x2 hole, ring lands in
		// the ceiling), slide the target into the nearest fitting gap, preferring the head normal.
		double[] home = resolveHome(in.world(), in.px(), pivotY, in.pz(), upX, upY, upZ,
			radius, in.bbHeight(), homeX, homeY, homeZ);
		homeX = home[0];
		homeY = home[1];
		homeZ = home[2];

		if (!initialized) {
			wx = homeX;
			wy = homeY;
			wz = homeZ;
			initialized = true;
		}

		double headCenterY = pivotY;

		switch (mode) {
			case ASTAR -> stepAstar(in, homeX, homeY, homeZ);
			default -> stepSpring(in, homeX, homeY, homeZ, headCenterY);
		}

		// orientation: aim the disc through the head; if flat can't fit here, adopt a fitting lean.
		double[] aim = resolveOrientation(in.world(), headCenterY, in.px(), in.py(), in.pz());
		tiltVelX += (aim[0] - tiltX) * TILT_K;
		tiltVelZ += (aim[1] - tiltZ) * TILT_K;
		tiltVelX *= TILT_DAMPING;
		tiltVelZ *= TILT_DAMPING;
		tiltX += tiltVelX;
		tiltZ += tiltVelZ;

		// hard safety: never rest clipped
		hardResolve(in.world());

		return new State(wx - in.px(), wy - in.py(), wz - in.pz(), tiltX, tiltZ, mode);
	}

	private void stepSpring(Input in, double homeX, double homeY, double homeZ, double headCenterY) {
		double dt = 1.0 / SUBSTEPS;
		boolean contact = false;
		for (int s = 0; s < SUBSTEPS; s++) {
			// spring acceleration toward home
			double ax = (homeX - wx) * SPRING_K;
			double ay = (homeY - wy) * SPRING_K;
			double az = (homeZ - wz) * SPRING_K;

			vx = (vx + ax * dt);
			vy = (vy + ay * dt);
			vz = (vz + az * dt);

			// contact solver: for every block face the ring is touching, cancel the velocity
			// component pointing INTO the face (and add a tiny bias to ease out any penetration).
			// This leaves only the tangential velocity, so the ring slides along the surface with the
			// remaining spring pull instead of bouncing off it.
			if (solveContacts(in.world(), dt)) {
				contact = true;
			}

			vx *= DAMPING;
			vy *= DAMPING;
			vz *= DAMPING;
			clampSpeed();
			// swept, collide-and-slide move so a fast spring can't tunnel through thin walls.
			if (moveSwept(in.world(), vx * dt, vy * dt, vz * dt)) {
				contact = true;
			}
		}

		// stuck bookkeeping: far from home and making no progress toward it, with a wall between.
		double dHome = dist(wx, wy, wz, homeX, homeY, homeZ);
		boolean blocked = segmentBlocked(in.world(), wx, wy, wz, homeX, homeY, homeZ);
		// track the BEST (smallest) home distance seen this stuck-window; only a real net gain toward
		// home resets the counter. Small oscillation in a channel keeps counting as stuck.
		boolean noProgress = dHome > prevHomeDist - STUCK_PROGRESS;
		if (dHome > STUCK_DIST && noProgress && blocked) {
			stuckTicks++;
		} else {
			stuckTicks = 0;
		}
		if (dHome < prevHomeDist) {
			prevHomeDist = dHome; // remember the closest approach; jitter away from it won't reset
		}
		if (stuckTicks >= STUCK_TICKS) {
			beginRescue(in, homeX, homeY, homeZ);
		}
	}

	private void beginRescue(Input in, double homeX, double homeY, double homeZ) {
		stuckTicks = 0;
		prevHomeDist = Double.MAX_VALUE;
		astarStallTicks = 0;
		path = astar(in.world(), wx, wy, wz, homeX, homeY, homeZ);
		if (path == null) {
			teleport(in.world(), homeX, homeY, homeZ);
			mode = Mode.SPRING;
			return;
		}
		pathIndex = 0;
		mode = Mode.ASTAR;
	}

	private void stepAstar(Input in, double homeX, double homeY, double homeZ) {
		// if the straight line home is clear again, hand back to the spring.
		if (!segmentBlocked(in.world(), wx, wy, wz, homeX, homeY, homeZ)) {
			mode = Mode.SPRING;
			path = null;
			return;
		}
		if (path == null || pathIndex >= path.size()) {
			// reached path end but still blocked: replan, else teleport.
			path = astar(in.world(), wx, wy, wz, homeX, homeY, homeZ);
			if (path == null) {
				teleport(in.world(), homeX, homeY, homeZ);
				mode = Mode.SPRING;
				return;
			}
			pathIndex = 0;
		}

		// A* planned on the FLAT-disc grid, so travel the path flat too — otherwise a tilted ring's
		// wider extents could clip a wall the flat plan deemed clear. Ease the tilt out to zero.
		tiltX *= 0.5;
		tiltZ *= 0.5;
		tiltVelX = tiltVelZ = 0;

		double startX = wx, startY = wy, startZ = wz;
		double budget = ASTAR_SPEED;
		int guard = 0;
		while (budget > 1.0E-6 && pathIndex < path.size() && guard++ < 64) {
			double[] wp = path.get(pathIndex);
			double dx = wp[0] - wx;
			double dy = wp[1] - wy;
			double dz = wp[2] - wz;
			double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (d < 0.03) {
				pathIndex++;
				continue;
			}
			double s = Math.min(budget, d);
			// Move along the FULL 3D segment (not per-axis) so diagonal doorway waypoints aren't
			// dead-locked by an axis that is individually blocked at a corner. Collision-checked so a
			// stale/corner-cutting segment still can't tunnel a wall.
			if (!moveAlong(in.world(), dx / d * s, dy / d * s, dz / d * s)) {
				break; // blocked along the segment; handled by the stall/replan logic below
			}
			budget -= s;
		}

		// If the whole tick made almost no headway, the plan is stale (player moved, or a corner):
		// replan from here; if no route exists, teleport as the last resort.
		double advanced = dist(wx, wy, wz, startX, startY, startZ);
		if (advanced < ASTAR_SPEED * 0.25) {
			astarStallTicks++;
			if (astarStallTicks >= 3) {
				astarStallTicks = 0;
				path = astar(in.world(), wx, wy, wz, homeX, homeY, homeZ);
				if (path == null) {
					teleport(in.world(), homeX, homeY, homeZ);
					mode = Mode.SPRING;
					return;
				}
				pathIndex = 0;
			}
		} else {
			astarStallTicks = 0;
		}
		// keep velocity coherent so the handoff back to spring isn't jarring
		vx = vy = vz = 0;
	}

	private void teleport(List<Box> world, double homeX, double homeY, double homeZ) {
		if (fitsFlat(world, homeX, homeY, homeZ)) {
			wx = homeX;
			wy = homeY;
			wz = homeZ;
		} else {
			double[] near = nearestFree(world, homeX, homeY, homeZ);
			if (near != null) {
				wx = near[0];
				wy = near[1];
				wz = near[2];
			}
		}
		vx = vy = vz = 0;
		path = null;
	}

	// ---- forces ----

	// Contact constraint solver. For every block whose face the ring is touching (within a thin skin
	// band, or already penetrating), find the contact face normal (the axis of least overlap, as for
	// an MTV) and remove the velocity component pointing INTO the face. A small Baumgarte bias nudges
	// any residual penetration back out along the normal. The net effect: the spring keeps pulling the
	// ring, but any part of that pull aimed into a wall is cancelled, so the ring slides flush along
	// the surface instead of bouncing between spring-in and repulsion-out. Returns true if in contact.
	private boolean solveContacts(List<Box> world, double dt) {
		double[] e = extents(tiltX, tiltZ);
		Box grown = new Box(wx - e[0] - CONTACT_SKIN, wy - e[1] - CONTACT_SKIN, wz - e[2] - CONTACT_SKIN,
			wx + e[0] + CONTACT_SKIN, wy + e[1] + CONTACT_SKIN, wz + e[2] + CONTACT_SKIN);
		boolean any = false;
		for (Box b : world) {
			if (!grown.intersects(b)) {
				continue;
			}
			double bhx = (b.maxX() - b.minX()) * 0.5;
			double bhy = (b.maxY() - b.minY()) * 0.5;
			double bhz = (b.maxZ() - b.minZ()) * 0.5;
			double dx = wx - (b.minX() + b.maxX()) * 0.5;
			double dy = wy - (b.minY() + b.maxY()) * 0.5;
			double dz = wz - (b.minZ() + b.maxZ()) * 0.5;
			// overlap along each axis (positive = penetrating, negative = gap up to the skin band)
			double ox = (e[0] + bhx) - Math.abs(dx);
			double oy = (e[1] + bhy) - Math.abs(dy);
			double oz = (e[2] + bhz) - Math.abs(dz);
			if (ox <= -CONTACT_SKIN || oy <= -CONTACT_SKIN || oz <= -CONTACT_SKIN) {
				continue; // separated on some axis by more than the skin -> not a contact
			}
			// contact normal = axis of LEAST overlap (the face we're resting against), signed outward
			double nx = 0, ny = 0, nz = 0, pen;
			if (ox <= oy && ox <= oz) {
				nx = Math.copySign(1.0, dx);
				pen = ox;
			} else if (oy <= ox && oy <= oz) {
				ny = Math.copySign(1.0, dy);
				pen = oy;
			} else {
				nz = Math.copySign(1.0, dz);
				pen = oz;
			}
			// velocity component INTO the wall (negative = approaching); cancel only that part.
			double vn = vx * nx + vy * ny + vz * nz;
			if (vn < 0) {
				vx -= vn * nx;
				vy -= vn * ny;
				vz -= vn * nz;
				any = true;
			}
			// Baumgarte bias: if actually penetrating, add a gentle outward velocity to ease out.
			if (pen > 0) {
				double bias = Math.min(BIAS_MAX, pen * BIAS_K) / dt;
				vx += bias * nx;
				vy += bias * ny;
				vz += bias * nz;
				any = true;
			}
		}
		return any;
	}


	private double[] resolveOrientation(List<Box> world, double headCenterY, double px, double py, double pz) {
		double ox = wx - px;
		double oy = wy - py;
		double oz = wz - pz;
		double[] aim = aimTilt(ox, oy, oz, headCenterY - py);
		if (isFree(world, boxAt(wx, wy, wz, aim[0], aim[1]))) {
			return aim;
		}
		double[] best = aim;
		for (double[] o : ORIENTATIONS) {
			if (isFree(world, boxAt(wx, wy, wz, o[0], o[1]))) {
				return o;
			}
		}
		return best;
	}

	private double[] aimTilt(double ox, double oy, double oz, double headCenterY) {
		double dy = oy - headCenterY;
		if (dy <= 1.0E-3) {
			return new double[]{0.0, 0.0};
		}
		double len = Math.sqrt(ox * ox + dy * dy + oz * oz);
		if (len < 1.0E-4) {
			return new double[]{0.0, 0.0};
		}
		double nx = ox / len, ny = dy / len, nz = oz / len;
		double tx = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, nz))));
		double tz = Math.toDegrees(Math.atan2(-nx, ny));
		return new double[]{tx, tz};
	}

	// iterative minimal-translation push-out so the ring never rests inside a block
	private void hardResolve(List<Box> world) {
		for (int iter = 0; iter < RESOLVE_ITERS; iter++) {
			double[] e = extents(tiltX, tiltZ);
			Box halo = new Box(wx - e[0], wy - e[1], wz - e[2], wx + e[0], wy + e[1], wz + e[2]);
			Box worst = null;
			double worstPen = 0;
			for (Box b : world) {
				if (!halo.intersects(b)) {
					continue;
				}
				double pen = minPenetration(halo, b);
				if (pen > worstPen) {
					worstPen = pen;
					worst = b;
				}
			}
			if (worst == null) {
				return;
			}
			pushOut(halo, worst);
		}
	}

	private double minPenetration(Box a, Box b) {
		double ox = Math.min(a.maxX(), b.maxX()) - Math.max(a.minX(), b.minX());
		double oy = Math.min(a.maxY(), b.maxY()) - Math.max(a.minY(), b.minY());
		double oz = Math.min(a.maxZ(), b.maxZ()) - Math.max(a.minZ(), b.minZ());
		return Math.min(ox, Math.min(oy, oz));
	}

	private void pushOut(Box halo, Box b) {
		double ox = Math.min(halo.maxX(), b.maxX()) - Math.max(halo.minX(), b.minX());
		double oy = Math.min(halo.maxY(), b.maxY()) - Math.max(halo.minY(), b.minY());
		double oz = Math.min(halo.maxZ(), b.maxZ()) - Math.max(halo.minZ(), b.minZ());
		double cx = (halo.minX() + halo.maxX()) * 0.5;
		double cy = (halo.minY() + halo.maxY()) * 0.5;
		double cz = (halo.minZ() + halo.maxZ()) * 0.5;
		double bx = (b.minX() + b.maxX()) * 0.5;
		double by = (b.minY() + b.maxY()) * 0.5;
		double bz = (b.minZ() + b.maxZ()) * 0.5;
		if (ox <= oy && ox <= oz) {
			wx += Math.copySign(ox + 1.0E-4, cx - bx);
			vx = 0;
		} else if (oy <= ox && oy <= oz) {
			wy += Math.copySign(oy + 1.0E-4, cy - by);
			vy = 0;
		} else {
			wz += Math.copySign(oz + 1.0E-4, cz - bz);
			vz = 0;
		}
	}

	// ---- helpers ----

	private void clampSpeed() {
		double sp = Math.sqrt(vx * vx + vy * vy + vz * vz);
		if (sp > MAX_SPEED) {
			double k = MAX_SPEED / sp;
			vx *= k;
			vy *= k;
			vz *= k;
		}
	}

	// move by (dx,dy,dz) in small sub-slices, stopping/sliding per-axis on contact so a fast spring
	// never tunnels through a thin wall. The per-axis kill of velocity lets the ring slide along
	// surfaces instead of sticking. Returns true if it was blocked on any axis (i.e. touched a wall).
	private boolean moveSwept(List<Box> world, double dx, double dy, double dz) {
		double[] e = extents(tiltX, tiltZ);
		double maxExtent = Math.min(e[0], Math.min(e[1], e[2]));
		double step = Math.max(0.02, maxExtent * 0.5); // never advance more than ~half the thinnest extent
		double total = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (total < 1.0E-9) {
			return false;
		}
		boolean blocked = false;
		int slices = Math.max(1, (int) Math.ceil(total / step));
		double sx = dx / slices, sy = dy / slices, sz = dz / slices;
		for (int i = 0; i < slices; i++) {
			if (freeAt(world, wx + sx, wy, wz)) {
				wx += sx;
			} else {
				vx = 0;
				blocked = true;
			}
			if (freeAt(world, wx, wy + sy, wz)) {
				wy += sy;
			} else {
				vy = 0;
				blocked = true;
			}
			if (freeAt(world, wx, wy, wz + sz)) {
				wz += sz;
			} else {
				vz = 0;
				blocked = true;
			}
		}
		return blocked;
	}

	private boolean freeAt(List<Box> world, double cx, double cy, double cz) {
		return isFree(world, boxAt(cx, cy, cz, tiltX, tiltZ));
	}

	// advance along the full 3D vector in small sub-slices, checking the COMBINED position each step
	// (not per-axis). Returns true if it advanced the full distance, false if it hit a wall. Used for
	// A* path-following, where waypoints are deliberately diagonal through doorways. If the ring
	// STARTS overlapping (it was resting against a wall), a step that reduces penetration is allowed
	// so it can peel off the surface instead of dead-locking.
	private boolean moveAlong(List<Box> world, double dx, double dy, double dz) {
		double[] e = extents(tiltX, tiltZ);
		double step = Math.max(0.02, Math.min(e[0], Math.min(e[1], e[2])) * 0.5);
		double total = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (total < 1.0E-9) {
			return true;
		}
		int slices = Math.max(1, (int) Math.ceil(total / step));
		double sx = dx / slices, sy = dy / slices, sz = dz / slices;
		for (int i = 0; i < slices; i++) {
			double curPen = penetration(world, wx, wy, wz);
			double nextPen = penetration(world, wx + sx, wy + sy, wz + sz);
			// accept the step if the new spot is free, OR if we're already clipping and the step
			// doesn't make it worse (lets a wall-resting ring slide free along the path).
			if (nextPen <= 1.0E-6 || (curPen > 1.0E-6 && nextPen <= curPen + 1.0E-6)) {
				wx += sx;
				wy += sy;
				wz += sz;
			} else {
				return false;
			}
		}
		return true;
	}

	// deepest penetration of the (tilted) halo into any world box at the given center; 0 if free.
	private double penetration(List<Box> world, double cx, double cy, double cz) {
		Box halo = boxAt(cx, cy, cz, tiltX, tiltZ);
		double worst = 0;
		for (Box b : world) {
			if (!halo.intersects(b)) {
				continue;
			}
			worst = Math.max(worst, minPenetration(halo, b));
		}
		return worst;
	}

	// sample the segment; true if the flat halo would clip a block anywhere along it
	private boolean segmentBlocked(List<Box> world, double x0, double y0, double z0,
			double x1, double y1, double z1) {
		double d = dist(x0, y0, z0, x1, y1, z1);
		int steps = Math.max(1, (int) Math.ceil(d / (GRID * 0.5)));
		for (int i = 0; i <= steps; i++) {
			double t = (double) i / steps;
			double x = x0 + (x1 - x0) * t;
			double y = y0 + (y1 - y0) * t;
			double z = z0 + (z1 - z0) * t;
			if (!fitsFlat(world, x, y, z)) {
				return true;
			}
		}
		return false;
	}

	// resolve the spring target so it lands in an actual gap rather than inside a block. Preference
	// order: (1) the ideal head-normal point; (2) the nearest FITTING point still on the head-normal
	// ray (slide in toward the head / out away from it, staying perpendicular to sight, choosing the
	// spot closest to the top of the head); (3) if the whole normal ray is blocked, abandon the
	// normal and take the nearest fitting spot anywhere around the head.
	private double[] resolveHome(List<Box> world, double px, double pivotY, double pz,
			double upX, double upY, double upZ, double radius, double bbHeight,
			double idealX, double idealY, double idealZ) {
		// (1) ideal spot already fits.
		if (fitsFlat(world, idealX, idealY, idealZ)) {
			return new double[]{idealX, idealY, idealZ};
		}
		// (2) walk the head-normal ray. Sample radii from a small clearance out past the ideal,
		// keep the fitting candidate nearest the top of the head.
		double topX = px, topY = pivotY + bbHeight * (1.0 - PIVOT_HEIGHT_FRAC), topZ = pz;
		double[] best = null;
		double bestScore = Double.MAX_VALUE;
		double rMin = Math.max(GRID, modelR + HOME_HEAD_CLEARANCE);
		double rMax = radius + HOME_RAY_OVERSHOOT;
		for (double r = rMin; r <= rMax + 1.0E-6; r += GRID) {
			double x = px + upX * r, y = pivotY + upY * r, z = pz + upZ * r;
			if (!fitsFlat(world, x, y, z)) {
				continue;
			}
			double score = dist(x, y, z, topX, topY, topZ);
			if (score < bestScore) {
				bestScore = score;
				best = new double[]{x, y, z};
			}
		}
		if (best != null) {
			return best;
		}
		// (3) normal ray fully blocked: abandon the normal, take the nearest fitting spot.
		double[] free = nearestFree(world, idealX, idealY, idealZ);
		return free != null ? free : new double[]{idealX, idealY, idealZ};
	}

	private double[] nearestFree(List<Box> world, double cx, double cy, double cz) {
		double[] best = null;
		double bestD = Double.MAX_VALUE;
		for (double r = GRID; r <= ASTAR_RANGE; r += GRID) {
			for (double dx = -r; dx <= r; dx += GRID) {
				for (double dy = -r; dy <= r; dy += GRID) {
					for (double dz = -r; dz <= r; dz += GRID) {
						double x = cx + dx, y = cy + dy, z = cz + dz;
						if (!fitsFlat(world, x, y, z)) {
							continue;
						}
						double dd = dx * dx + dy * dy + dz * dz;
						if (dd < bestD) {
							bestD = dd;
							best = new double[]{x, y, z};
						}
					}
				}
			}
			if (best != null) {
				return best;
			}
		}
		return best;
	}

	private static double dist(double ax, double ay, double az, double bx, double by, double bz) {
		double dx = ax - bx, dy = ay - by, dz = az - bz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	// ---- A* on a coarse grid centered on the player ----

	private List<double[]> astar(List<Box> world, double sx, double sy, double sz,
			double gx, double gy, double gz) {
		double originX = Math.round(sx / PATH_GRID) * PATH_GRID;
		double originY = Math.round(sy / PATH_GRID) * PATH_GRID;
		double originZ = Math.round(sz / PATH_GRID) * PATH_GRID;
		long startKey = key(0, 0, 0);
		int gxi = (int) Math.round((gx - originX) / PATH_GRID);
		int gyi = (int) Math.round((gy - originY) / PATH_GRID);
		int gzi = (int) Math.round((gz - originZ) / PATH_GRID);
		int range = (int) Math.round(ASTAR_RANGE / PATH_GRID);
		gxi = clampI(gxi, -range, range);
		gyi = clampI(gyi, -range, range);
		gzi = clampI(gzi, -range, range);
		long goalKey = key(gxi, gyi, gzi);

		Map<Long, Long> cameFrom = new HashMap<>();
		Map<Long, Double> gScore = new HashMap<>();
		PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[1], b[1]));
		gScore.put(startKey, 0.0);
		open.add(new double[]{startKey, heur(0, 0, 0, gxi, gyi, gzi)});
		int expanded = 0;

		while (!open.isEmpty() && expanded < ASTAR_MAX_NODES) {
			double[] cur = open.poll();
			long ck = (long) cur[0];
			expanded++;
			if (ck == goalKey) {
				return reconstruct(cameFrom, ck, originX, originY, originZ, gx, gy, gz);
			}
			int cxi = kx(ck), cyi = ky(ck), czi = kz(ck);
			double cg = gScore.getOrDefault(ck, Double.MAX_VALUE);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dy == 0 && dz == 0) {
							continue;
						}
						int nxi = cxi + dx, nyi = cyi + dy, nzi = czi + dz;
						if (Math.abs(nxi) > range || Math.abs(nyi) > range || Math.abs(nzi) > range) {
							continue;
						}
						double nx = originX + nxi * PATH_GRID;
						double ny = originY + nyi * PATH_GRID;
						double nz = originZ + nzi * PATH_GRID;
						if (!fitsFlat(world, nx, ny, nz)) {
							continue;
						}
						// prevent corner-cutting: for a diagonal move, the orthogonal cells it
						// squeezes past must also be free, otherwise the straight segment between the
						// two grid points clips a wall corner (which moveAlong then can't follow).
						if (cutsCorner(world, cxi, cyi, czi, dx, dy, dz, originX, originY, originZ)) {
							continue;
						}
						double step = Math.sqrt(dx * dx + dy * dy + dz * dz) * PATH_GRID;
						long nk = key(nxi, nyi, nzi);
						double tentative = cg + step;
						if (tentative < gScore.getOrDefault(nk, Double.MAX_VALUE)) {
							cameFrom.put(nk, ck);
							gScore.put(nk, tentative);
							open.add(new double[]{nk, tentative + ASTAR_HEUR_WEIGHT * heur(nxi, nyi, nzi, gxi, gyi, gzi)});
						}
					}
				}
			}
		}
		return null;
	}

	private List<double[]> reconstruct(Map<Long, Long> cameFrom, long cur,
			double ox, double oy, double oz, double gx, double gy, double gz) {
		ArrayDeque<double[]> deque = new ArrayDeque<>();
		deque.addFirst(new double[]{gx, gy, gz});
		while (cameFrom.containsKey(cur)) {
			cur = cameFrom.get(cur);
			deque.addFirst(new double[]{ox + kx(cur) * PATH_GRID, oy + ky(cur) * PATH_GRID, oz + kz(cur) * PATH_GRID});
		}
		return new ArrayList<>(deque);
	}

	private double heur(int x, int y, int z, int gx, int gy, int gz) {
		double dx = (x - gx) * PATH_GRID, dy = (y - gy) * PATH_GRID, dz = (z - gz) * PATH_GRID;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	// True if moving from cell (cxi,cyi,czi) by the diagonal (dx,dy,dz) would cut a wall corner: the
	// straight segment between the two grid points must stay clear, so every cell that shares an edge
	// or face with BOTH endpoints (i.e. the orthogonal decompositions of the diagonal) must be free.
	private boolean cutsCorner(List<Box> world, int cxi, int cyi, int czi,
			int dx, int dy, int dz, double ox, double oy, double oz) {
		int nonZero = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
		if (nonZero <= 1) {
			return false; // straight move, no corner to cut
		}
		// each single-axis neighbour that the diagonal passes must be free
		if (dx != 0 && !cellFree(world, cxi + dx, cyi, czi, ox, oy, oz)) {
			return true;
		}
		if (dy != 0 && !cellFree(world, cxi, cyi + dy, czi, ox, oy, oz)) {
			return true;
		}
		if (dz != 0 && !cellFree(world, cxi, cyi, czi + dz, ox, oy, oz)) {
			return true;
		}
		// for a full 3D diagonal, also require the three face-diagonal midpoints to be clear
		if (nonZero == 3) {
			if (!cellFree(world, cxi + dx, cyi + dy, czi, ox, oy, oz)
				|| !cellFree(world, cxi + dx, cyi, czi + dz, ox, oy, oz)
				|| !cellFree(world, cxi, cyi + dy, czi + dz, ox, oy, oz)) {
				return true;
			}
		}
		return false;
	}

	private boolean cellFree(List<Box> world, int xi, int yi, int zi, double ox, double oy, double oz) {
		return fitsFlat(world, ox + xi * PATH_GRID, oy + yi * PATH_GRID, oz + zi * PATH_GRID);
	}

	private static int clampI(int v, int lo, int hi) {
		return v < lo ? lo : Math.min(v, hi);
	}

	// pack a grid coordinate (each in [-1024,1023]) into a long key
	private static long key(int x, int y, int z) {
		return ((long) (x + 1024) << 42) | ((long) (y + 1024) << 21) | (long) (z + 1024);
	}

	private static int kx(long k) {
		return (int) ((k >> 42) & 0x1FFFFF) - 1024;
	}

	private static int ky(long k) {
		return (int) ((k >> 21) & 0x1FFFFF) - 1024;
	}

	private static int kz(long k) {
		return (int) (k & 0x1FFFFF) - 1024;
	}
}
