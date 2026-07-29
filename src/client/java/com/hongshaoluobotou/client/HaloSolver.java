package com.hongshaoluobotou.client;

import java.util.ArrayList;
import java.util.List;

// Pure, Minecraft-independent collision-aware placement for the halo ring.
// All geometry lives here; the only outside dependency is a FreeTest that reports
// whether a given axis-aligned box is free of world/body collision.
public final class HaloSolver {
	public static final double FIT_MARGIN = 0.02;
	public static final double PATH_STEP = 0.12;
	public static final int PATH_MAX_STEPS = 10;
	// the ring has a hole, so it can slip past an obstruction band no thicker than this
	public static final double TUNNEL_MAX = 0.6;
	public static final double TUNNEL_PROBE = 0.06;
	public static final double W_HOME_DIST = 1.0;
	public static final double W_TRAVEL = 0.6;
	public static final double W_LOW = 0.15;
	// body overlap is only a soft preference: a ring may encircle the torso, but we'd rather it didn't.
	public static final double W_BODY = 0.5;
	// tilting/standing the ring up is allowed but visually dispreferred; used only when it frees space.
	public static final double W_TILT = 0.4;
	// the ring (dia ~1.0) is wider than the player (~0.8), so it naturally overhangs ~0.1 on each
	// side. Allow that much horizontal penetration into blocks before treating it as a real
	// collision — this stops the ring twitching when the player merely stands next to a wall.
	public static final double WALL_TOLERANCE = 0.1;
	// forward-instability penalty: candidates that would collide if the player keeps moving forward
	// (i.e. the player is walking INTO the obstacle) are dispreferred, so entering a doorway makes the
	// ring sink through rather than sidestep-then-drop.
	public static final double W_FORWARD = 3.0;
	public static final double FORWARD_LOOKAHEAD = 0.45;
	public static final double PLAYER_HALF_W = 0.3;
	public static final double BODY_CORE = 0.14;
	private static final double DEG_TO_RAD = Math.PI / 180.0;

	// candidate ring orientations as {tiltXDeg, tiltZDeg}: flat (preferred), 45° leans, and fully
	// upright about each horizontal axis (thin edge lets it slip into narrow vertical gaps).
	private static final double[][] ORIENTATIONS = {
		{0, 0},
		{45, 0},
		{0, 45},
		{90, 0},
		{0, 90},
	};

	// axis-aligned box in world space
	public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		public boolean intersects(Box o) {
			return this.minX < o.maxX && this.maxX > o.minX
				&& this.minY < o.maxY && this.maxY > o.minY
				&& this.minZ < o.maxZ && this.maxZ > o.minZ;
		}

		public Box deflate(double d) {
			return new Box(minX + d, minY + d, minZ + d, maxX - d, maxY - d, maxZ - d);
		}

		public Box move(double dx, double dy, double dz) {
			return new Box(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
		}
	}

	@FunctionalInterface
	public interface FreeTest {
		boolean isFree(Box box);
	}

	private final double px, py, pz;
	private final double bbHeight;
	private final double bodyYawDeg;
	private final double modelR;
	private final double modelT;
	private final FreeTest freeTest;
	private final Box bodyBox;
	private double ax, ay, az;
	private final double atx, atz;
	private final double moveX, moveZ;

	public HaloSolver(double px, double py, double pz, double bbHeight, double bodyYawDeg,
			double modelR, double modelT, double ax, double ay, double az, FreeTest freeTest) {
		this(px, py, pz, bbHeight, bodyYawDeg, modelR, modelT, ax, ay, az, 0.0, 0.0, 0.0, 0.0, freeTest);
	}

	public HaloSolver(double px, double py, double pz, double bbHeight, double bodyYawDeg,
			double modelR, double modelT, double ax, double ay, double az,
			double atx, double atz, FreeTest freeTest) {
		this(px, py, pz, bbHeight, bodyYawDeg, modelR, modelT, ax, ay, az, atx, atz, 0.0, 0.0, freeTest);
	}

	public HaloSolver(double px, double py, double pz, double bbHeight, double bodyYawDeg,
			double modelR, double modelT, double ax, double ay, double az,
			double atx, double atz, double moveX, double moveZ, FreeTest freeTest) {
		this.px = px;
		this.py = py;
		this.pz = pz;
		this.bbHeight = bbHeight;
		this.bodyYawDeg = bodyYawDeg;
		this.modelR = modelR;
		this.modelT = modelT;
		this.ax = ax;
		this.ay = ay;
		this.az = az;
		this.atx = atx;
		this.atz = atz;
		this.moveX = moveX;
		this.moveZ = moveZ;
		this.freeTest = freeTest;
		double hw = PLAYER_HALF_W - BODY_CORE;
		this.bodyBox = new Box(px - hw, py + BODY_CORE, pz - hw, px + hw, py + bbHeight - BODY_CORE, pz + hw);
	}

	// halo world collision box at a local offset from the player position (flat orientation, exact size)
	public Box haloBox(double ox, double oy, double oz) {
		return haloBox(ox, oy, oz, 0, 0, false);
	}

	public Box haloBox(double ox, double oy, double oz, double tiltXDeg, double tiltZDeg) {
		return haloBox(ox, oy, oz, tiltXDeg, tiltZDeg, false);
	}

	// halo world collision box for a disc tilted by tiltXDeg (about X) then tiltZDeg (about Z).
	// A disc of radius r, half-thickness t, with unit normal n has AABB half-extents
	// e_i = r*sqrt(1 - n_i^2) + t*|n_i| along each axis (independent of spin about the normal).
	// When tolerant, the horizontal extents are shrunk by WALL_TOLERANCE so a small overhang into a
	// block is not counted as a collision (used only to decide WHETHER to react, not where to go).
	public Box haloBox(double ox, double oy, double oz, double tiltXDeg, double tiltZDeg, boolean tolerant) {
		double r = modelR + FIT_MARGIN;
		double t = modelT + FIT_MARGIN;
		double[] n = discNormal(tiltXDeg, tiltZDeg);
		double ex = r * Math.sqrt(Math.max(0.0, 1 - n[0] * n[0])) + t * Math.abs(n[0]);
		double ey = r * Math.sqrt(Math.max(0.0, 1 - n[1] * n[1])) + t * Math.abs(n[1]);
		double ez = r * Math.sqrt(Math.max(0.0, 1 - n[2] * n[2])) + t * Math.abs(n[2]);
		if (tolerant) {
			// allow a little horizontal overhang into blocks (the ring is wider than the player) but
			// keep the vertical extent exact so the thin disc still senses low ceilings.
			ex = Math.max(t, ex - WALL_TOLERANCE);
			ez = Math.max(t, ez - WALL_TOLERANCE);
		}
		double cx = px + ox;
		double cy = py + oy;
		double cz = pz + oz;
		return new Box(cx - ex, cy - ey, cz - ez, cx + ex, cy + ey, cz + ez);
	}

	// unit normal of a flat (+Y) disc after rotating tiltXDeg about X then tiltZDeg about Z.
	private static double[] discNormal(double tiltXDeg, double tiltZDeg) {
		double rx = tiltXDeg * DEG_TO_RAD;
		double rz = tiltZDeg * DEG_TO_RAD;
		double nx = 0, ny = 1, nz = 0;
		// rotate about X: (y,z)
		double ny1 = ny * Math.cos(rx) - nz * Math.sin(rx);
		double nz1 = ny * Math.sin(rx) + nz * Math.cos(rx);
		ny = ny1;
		nz = nz1;
		// rotate about Z: (x,y)
		double nx1 = nx * Math.cos(rz) - ny * Math.sin(rz);
		double ny2 = nx * Math.sin(rz) + ny * Math.cos(rz);
		nx = nx1;
		ny = ny2;
		return new double[]{nx, ny, nz};
	}

	// true if the halo at this offset clips only world geometry (block/entity collision).
	// Overlapping the player's own body is NOT a collision — the ring encircles the torso.
	public boolean fits(double ox, double oy, double oz) {
		return freeTest.isFree(haloBox(ox, oy, oz));
	}

	public boolean fits(double ox, double oy, double oz, double tiltXDeg, double tiltZDeg) {
		return freeTest.isFree(haloBox(ox, oy, oz, tiltXDeg, tiltZDeg, false));
	}

	// tolerant fit: a small horizontal overhang into a block is allowed. Used to decide whether the
	// ring must react at all — not where it should go (targets use the exact fit so the ring moves
	// fully clear rather than resting with a visible overhang).
	public boolean fitsTolerant(double ox, double oy, double oz, double tiltXDeg, double tiltZDeg) {
		return freeTest.isFree(haloBox(ox, oy, oz, tiltXDeg, tiltZDeg, true));
	}

	// true if the halo at this offset overlaps the player's body core (a soft, dispreferred state).
	public boolean overlapsBody(double ox, double oy, double oz) {
		return haloBox(ox, oy, oz).intersects(bodyBox);
	}

	public boolean overlapsBody(double ox, double oy, double oz, double tiltXDeg, double tiltZDeg) {
		return haloBox(ox, oy, oz, tiltXDeg, tiltZDeg).intersects(bodyBox);
	}

	// The tilt that makes the disc's normal (its perpendicular center axis) point through the head
	// center — used to aim the ring at the head for a natural look. The head center sits BELOW the
	// ring's resting height, so a ring nudged to the side only needs a gentle tilt to aim at it.
	// Only applied when the ring is above the head center; otherwise stay flat.
	private double[] aimTilt(double ox, double oy, double oz, double headCenterY) {
		double dy = oy - headCenterY;
		if (dy <= 1.0E-3) {
			return new double[]{0.0, 0.0};
		}
		// normal points from the head center up toward the ring: (ox, dy, oz)
		double len = Math.sqrt(ox * ox + dy * dy + oz * oz);
		if (len < 1.0E-4) {
			return new double[]{0.0, 0.0};
		}
		double nx = ox / len, ny = dy / len, nz = oz / len;
		// invert discNormal(): normal = (-cos(rx)sin(rz), cos(rx)cos(rz), sin(rx))
		double tiltX = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, nz))));
		double tiltZ = Math.toDegrees(Math.atan2(-nx, ny));
		return new double[]{tiltX, tiltZ};
	}

	// full solve for one tick: find a target and walk toward it; returns {x,y,z,tiltX,tiltZ}
	public double[] solve(double homeOffY) {
		double[] target = solveTarget(homeOffY);
		double[] moved = walkPath(target);
		// robustness: if smooth motion left the ring stuck inside solid geometry (e.g. sealed in
		// on all sides so the only free slot is unreachable by short steps) but the target itself
		// is valid, snap to it rather than stay clipped.
		if (!fits(moved[0], moved[1], moved[2], moved[3], moved[4])
				&& fits(target[0], target[1], target[2], target[3], target[4])) {
			return target;
		}
		return moved;
	}

	// generate candidate offsets/orientations and score them; pick the best reachable free spot.
	public double[] solveTarget(double homeOffY) {
		double[] home = {0.0, homeOffY, 0.0, 0.0, 0.0};
		// react only when the overhang exceeds the tolerance; a tiny overhang is left as-is.
		if (fitsTolerant(home[0], home[1], home[2], home[3], home[4])) {
			return home;
		}

		double h = bbHeight;
		double bodyYaw = bodyYawDeg * DEG_TO_RAD;
		double fx = -Math.sin(bodyYaw);
		double fz = Math.cos(bodyYaw);
		double sx = -fz;
		double sz = fx;

		// base positions (orientation is expanded per-position below)
		List<double[]> positions = new ArrayList<>();
		// small sideways nudges near head height come FIRST so a gentle horizontal shift (optionally
		// with a slight aim-tilt) is preferred over dropping the ring down toward the back.
		double[] nearDirsX = {fx, -fx, sx, -sx, fx + sx, fx - sx, -fx + sx, -fx - sx};
		double[] nearDirsZ = {fz, -fz, sz, -sz, fz + sz, fz - sz, -fz + sz, -fz - sz};
		double[] nudgeRad = {0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5};
		for (double oy = homeOffY; oy >= homeOffY - 0.3; oy -= 0.15) {
			for (double rad : nudgeRad) {
				for (int d = 0; d < nearDirsX.length; d++) {
					double len = Math.sqrt(nearDirsX[d] * nearDirsX[d] + nearDirsZ[d] * nearDirsZ[d]);
					if (len < 1.0E-4) {
						continue;
					}
					positions.add(new double[]{nearDirsX[d] / len * rad, oy, nearDirsZ[d] / len * rad});
				}
			}
		}
		// then the vertical column (drop straight down) and wider ring positions as fallbacks
		for (double oy = homeOffY; oy >= -0.6; oy -= 0.1) {
			positions.add(new double[]{0.0, oy, 0.0});
		}
		double[] radii = {0.55, 0.8};
		for (double oy = h * 0.75; oy >= 0.1; oy -= h * 0.25) {
			for (double rad : radii) {
				for (int d = 0; d < nearDirsX.length; d++) {
					double len = Math.sqrt(nearDirsX[d] * nearDirsX[d] + nearDirsZ[d] * nearDirsZ[d]);
					if (len < 1.0E-4) {
						continue;
					}
					positions.add(new double[]{nearDirsX[d] / len * rad, oy, nearDirsZ[d] / len * rad});
				}
			}
		}
		positions.add(new double[]{0.0, -0.55, 0.0});

		double[] best = null;
		double bestScore = -Double.MAX_VALUE;
		for (double[] p : positions) {
			// per-position orientations: the natural aim-at-head tilt (preferred), flat, plus the
			// discrete leans/uprights that only win when space truly demands them.
			double[] aim = aimTilt(p[0], p[1], p[2], bbHeight * 0.9);
			List<double[]> oris = new ArrayList<>();
			oris.add(aim);
			for (double[] o : ORIENTATIONS) {
				oris.add(o);
			}
			for (double[] o : oris) {
				double[] c = {p[0], p[1], p[2], o[0], o[1]};
				if (!fits(c[0], c[1], c[2], c[3], c[4])) {
					continue;
				}
				double score = scoreCandidate(c, home, aim);
				if (score > bestScore) {
					bestScore = score;
					best = c;
				}
			}
		}
		return best != null ? best : new double[]{ax, Math.max(ay, -0.55), az, atx, atz};
	}

	// higher score = better: near the home spot, near the current anchor (short travel), not too
	// low, preferably not overlapping the body. Tilt itself is cheap when it matches the natural
	// aim-at-head angle; only tilt that DEVIATES from that aim (e.g. forced uprights) is penalized.
	// Forward-unstable candidates (would collide if the player keeps walking forward) are penalized
	// so that entering a doorway sinks the ring instead of sidestepping then dropping.
	private double scoreCandidate(double[] c, double[] home, double[] aim) {
		double dh = dist(c[0], c[1], c[2], home[0], home[1], home[2]);
		double travel = dist(c[0], c[1], c[2], ax, ay, az);
		double lowPenalty = Math.max(0.0, home[1] - c[1]);
		double bodyPenalty = overlapsBody(c[0], c[1], c[2], c[3], c[4]) ? 1.0 : 0.0;
		double tiltDeviation = (Math.abs(c[3] - aim[0]) + Math.abs(c[4] - aim[1])) / 90.0;
		double forwardPenalty = forwardUnstable(c) ? 1.0 : 0.0;
		return -(W_HOME_DIST * dh + W_TRAVEL * travel + W_LOW * lowPenalty
			+ W_BODY * bodyPenalty + W_TILT * tiltDeviation + W_FORWARD * forwardPenalty);
	}

	// A candidate is forward-unstable if, when the player advances along the current move direction,
	// the ring at the SAME local offset/orientation would clip a block. A sidestep in front of a
	// doorway is unstable (the player walks into it); sinking through the doorway is stable.
	private boolean forwardUnstable(double[] c) {
		double speed = Math.sqrt(moveX * moveX + moveZ * moveZ);
		if (speed < 1.0E-3) {
			return false; // standing still: no forward direction, so no instability
		}
		double ux = moveX / speed * FORWARD_LOOKAHEAD;
		double uz = moveZ / speed * FORWARD_LOOKAHEAD;
		Box box = haloBox(c[0], c[1], c[2], c[3], c[4]).move(ux, 0, uz);
		return !freeTest.isFree(box);
	}

	// move from current anchor toward target in bounded micro-steps, sliding per-axis around obstacles.
	// orientation is interpolated toward the target orientation alongside position.
	public double[] walkPath(double[] target) {
		double cx = ax;
		double cy = ay;
		double cz = az;
		double ctx = atx;
		double ctz = atz;
		double tx = target[0];
		double ty = target[1];
		double tz = target[2];
		double ttx = target[3];
		double ttz = target[4];
		for (int step = 0; step < PATH_MAX_STEPS; step++) {
			double dx = tx - cx;
			double dy = ty - cy;
			double dz = tz - cz;
			double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (d < 1.0E-3) {
				ctx = ttx;
				ctz = ttz;
				break;
			}
			double s = Math.min(PATH_STEP, d);
			double frac = s / d;
			double ntx = ctx + (ttx - ctx) * frac;
			double ntz = ctz + (ttz - ctz) * frac;
			double nx = cx + dx / d * s;
			double ny = cy + dy / d * s;
			double nz = cz + dz / d * s;
			if (fits(nx, ny, nz, ntx, ntz)) {
				cx = nx;
				cy = ny;
				cz = nz;
				ctx = ntx;
				ctz = ntz;
				continue;
			}
			boolean movedAny = false;
			if (fits(nx, cy, cz, ntx, ntz)) {
				cx = nx;
				ctx = ntx;
				ctz = ntz;
				movedAny = true;
			}
			if (fits(cx, ny, cz, ntx, ntz)) {
				cy = ny;
				ctx = ntx;
				ctz = ntz;
				movedAny = true;
			}
			if (fits(cx, cy, nz, ntx, ntz)) {
				cz = nz;
				ctx = ntx;
				ctz = ntz;
				movedAny = true;
			}
			if (!movedAny) {
				// fully blocked on all axes: the ring has a hole, so a thin obstruction band
				// (e.g. a doorway lintel) may pass through it. Probe further along the line and,
				// if a free point exists just beyond the band, tunnel to it.
				double[] tunnel = tunnelThrough(cx, cy, cz, ntx, ntz, dx / d, dy / d, dz / d, d);
				if (tunnel != null) {
					cx = tunnel[0];
					cy = tunnel[1];
					cz = tunnel[2];
					ctx = ntx;
					ctz = ntz;
					continue;
				}
				break;
			}
		}
		return new double[]{cx, cy, cz, ctx, ctz};
	}

	// look ahead along a unit direction for the nearest free point beyond a thin obstruction.
	// returns that point, or null if the obstruction is thicker than TUNNEL_MAX (not a gap the ring can clear).
	private double[] tunnelThrough(double cx, double cy, double cz, double tiltXDeg, double tiltZDeg,
			double ux, double uy, double uz, double maxDist) {
		double limit = Math.min(maxDist, TUNNEL_MAX);
		for (double probe = PATH_STEP; probe <= limit + 1.0E-9; probe += TUNNEL_PROBE) {
			double nx = cx + ux * probe;
			double ny = cy + uy * probe;
			double nz = cz + uz * probe;
			if (fits(nx, ny, nz, tiltXDeg, tiltZDeg)) {
				return new double[]{nx, ny, nz};
			}
		}
		return null;
	}

	private static double dist(double ax, double ay, double az, double bx, double by, double bz) {
		double dx = ax - bx;
		double dy = ay - by;
		double dz = az - bz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
