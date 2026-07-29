package com.hongshaoluobotou.client;

import com.hongshaoluobotou.EndRingItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

public final class HaloRenderer {
	private static final int FULL_BRIGHT = 0xF000F0;
	private static final float BASE_HEIGHT = 0.45F;
	private static final float MODEL_SCALE = 1.15F;
	private static final float SPIN_SPEED = 2.2F;

	private static final float LEAN_FACTOR = 0.9F;
	private static final float MAX_TILT = 60.0F;
	private static final float SPRING = 0.18F;
	private static final float DAMPING = 0.82F;

	private static final float SHIFT_FACTOR = 0.006F;
	private static final float MAX_SHIFT = 0.35F;
	private static final float SHIFT_SPRING = 0.18F;
	private static final float SHIFT_DAMPING = 0.82F;

	private static final float PITCH_DROP_FACTOR = 0.0045F;
	private static final float MAX_PITCH_DROP = 0.35F;

	// horizontal-only positional inertia (front/back/left/right); no vertical fling
	private static final float POS_INERTIA = 6.0F;
	private static final float POS_SPRING = 0.16F;
	private static final float POS_DAMPING = 0.80F;
	private static final float MAX_LAG = 0.45F;

	// --- collision-aware placement ---
	// real model extents are measured from the baked model; these are fallbacks until known.
	private static final double DEFAULT_R = 0.5;
	private static final double DEFAULT_T = 0.06;

	private static volatile double modelR = DEFAULT_R;
	private static volatile double modelT = DEFAULT_T;
	private static volatile boolean modelMeasured = false;

	private static final Map<UUID, Halo> HALOS = new HashMap<>();

	private HaloRenderer() {
	}

	public static void tick(ClientLevel level) {
		Minecraft mc = Minecraft.getInstance();
		for (AbstractClientPlayer player : level.players()) {
			ItemStack ring = EndRingItem.getWorn(player);
			if (ring.isEmpty()) {
				HALOS.remove(player.getUUID());
				continue;
			}
			measureModel(mc, player, ring);
			HALOS.computeIfAbsent(player.getUUID(), id -> new Halo()).update(player, level);
		}

		Iterator<UUID> it = HALOS.keySet().iterator();
		while (it.hasNext()) {
			UUID id = it.next();
			if (level.getPlayerByUUID(id) == null) {
				it.remove();
			}
		}
	}

	// measure the real baked-model size once, then derive the halo's world collision extents.
	private static void measureModel(Minecraft mc, AbstractClientPlayer player, ItemStack ring) {
		if (modelMeasured) {
			return;
		}
		ItemStackRenderState rs = new ItemStackRenderState();
		mc.getItemModelResolver().updateForNonLiving(rs, ring, ItemDisplayContext.NONE, player);
		if (rs.isEmpty()) {
			return;
		}
		AABB box = rs.getModelBoundingBox();
		double r = Math.max(box.getXsize(), box.getZsize()) * 0.5 * MODEL_SCALE;
		double t = box.getYsize() * 0.5 * MODEL_SCALE;
		if (r > 1.0E-4) {
			modelR = r;
			modelT = Math.max(t, 0.03);
			modelMeasured = true;
		}
	}

	public static void renderAll(PoseStack poseStack, SubmitNodeCollector collector) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || HALOS.isEmpty()) {
			return;
		}

		Camera camera = mc.gameRenderer.mainCamera();
		double camX = camera.position().x;
		double camY = camera.position().y;
		double camZ = camera.position().z;
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);

		for (AbstractClientPlayer player : level.players()) {
			Halo halo = HALOS.get(player.getUUID());
			if (halo == null || player.isInvisible() || EndRingItem.getWorn(player).isEmpty()) {
				continue;
			}

			ItemStackRenderState renderState = new ItemStackRenderState();
			mc.getItemModelResolver().updateForNonLiving(renderState, EndRingItem.getWorn(player), ItemDisplayContext.NONE, player);
			if (renderState.isEmpty()) {
				continue;
			}

			double px = Mth.lerp(partialTick, player.xOld, player.getX()) - camX;
			double py = Mth.lerp(partialTick, player.yOld, player.getY()) - camY;
			double pz = Mth.lerp(partialTick, player.zOld, player.getZ()) - camZ;

			float tiltX = Mth.lerp(partialTick, halo.prevTiltX, halo.tiltX);
			float tiltZ = Mth.lerp(partialTick, halo.prevTiltZ, halo.tiltZ);
			float shiftX = Mth.lerp(partialTick, halo.prevShiftX, halo.shiftX);
			float shiftZ = Mth.lerp(partialTick, halo.prevShiftZ, halo.shiftZ);
			float spin = Mth.lerp(partialTick, halo.prevSpin, halo.spin);
			float lagX = Mth.lerp(partialTick, halo.prevLagX, halo.lagX);
			float lagZ = Mth.lerp(partialTick, halo.prevLagZ, halo.lagZ);
			float solveTiltX = Mth.lerp(partialTick, halo.prevSolveTiltX, halo.solveTiltX);
			float solveTiltZ = Mth.lerp(partialTick, halo.prevSolveTiltZ, halo.solveTiltZ);
			double ax = Mth.lerp(partialTick, halo.prevAx, halo.ax);
			double ay = Mth.lerp(partialTick, halo.prevAy, halo.ay);
			double az = Mth.lerp(partialTick, halo.prevAz, halo.az);

			// head-look shift/tilt only apply while the halo still sits above the head; fade with height
			float headRef = player.getBbHeight() + BASE_HEIGHT;
			float aboveHead = Mth.clamp(((float) ay - headRef * 0.5F) / (headRef * 0.5F), 0.0F, 1.0F);

			poseStack.pushPose();
			poseStack.translate(px + ax + lagX + shiftX * aboveHead, py + ay, pz + az + lagZ + shiftZ * aboveHead);
			poseStack.mulPose(Axis.XP.rotationDegrees(tiltX * aboveHead + solveTiltX));
			poseStack.mulPose(Axis.ZP.rotationDegrees(tiltZ * aboveHead + solveTiltZ));
			poseStack.mulPose(Axis.YP.rotationDegrees(spin));
			poseStack.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
			poseStack.translate(0.0, -0.5, 0.0);
			renderState.submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
			poseStack.popPose();
		}
	}

	private static final class Halo {
		float tiltX, tiltZ, prevTiltX, prevTiltZ, velX, velZ;
		float shiftX, shiftZ, prevShiftX, prevShiftZ, shiftVelX, shiftVelZ;
		float headYaw, prevHeadYaw;
		float pitchDrop;
		float spin, prevSpin;
		double ax, ay, az, prevAx, prevAy, prevAz;
		float solveTiltX, solveTiltZ, prevSolveTiltX, prevSolveTiltZ;
		float lagX, lagZ, prevLagX, prevLagZ, lagVelX, lagVelZ;
		double lastX, lastZ, lastVelX, lastVelZ;
		boolean initialized;

		void update(AbstractClientPlayer player, ClientLevel level) {
			prevTiltX = tiltX;
			prevTiltZ = tiltZ;
			prevShiftX = shiftX;
			prevShiftZ = shiftZ;
			prevHeadYaw = headYaw;
			prevSpin = spin;
			prevAx = ax;
			prevAy = ay;
			prevAz = az;
			prevLagX = lagX;
			prevLagZ = lagZ;
			prevSolveTiltX = solveTiltX;
			prevSolveTiltZ = solveTiltZ;

			spin += SPIN_SPEED;
			if (spin >= 360.0F) {
				spin -= 360.0F;
				prevSpin -= 360.0F;
			}

			double px = player.getX();
			double py = player.getY();
			double pz = player.getZ();
			double homeY = player.getBbHeight() + BASE_HEIGHT;
			if (!initialized) {
				lastX = px;
				lastZ = pz;
				ax = 0;
				ay = homeY;
				az = 0;
				prevAx = ax;
				prevAy = ay;
				prevAz = az;
				initialized = true;
			}

			double moveX = px - lastX;
			double moveZ = pz - lastZ;
			double accX = moveX - lastVelX;
			double accZ = moveZ - lastVelZ;
			lastX = px;
			lastZ = pz;
			lastVelX = moveX;
			lastVelZ = moveZ;

			float rawYaw = player.getYHeadRot();
			headYaw += Mth.wrapDegrees(rawYaw - headYaw) * 0.25F;
			if (headYaw >= 360.0F) {
				headYaw -= 360.0F;
				prevHeadYaw -= 360.0F;
			} else if (headYaw <= -360.0F) {
				headYaw += 360.0F;
				prevHeadYaw += 360.0F;
			}

			float yaw = headYaw * Mth.DEG_TO_RAD;
			float pitch = player.getXRot();

			float lean = Mth.clamp(pitch * LEAN_FACTOR, -MAX_TILT, MAX_TILT);
			float targetTiltX = Mth.cos(yaw) * lean;
			float targetTiltZ = Mth.sin(yaw) * lean;
			velX += (targetTiltX - tiltX) * SPRING;
			velZ += (targetTiltZ - tiltZ) * SPRING;
			velX *= DAMPING;
			velZ *= DAMPING;
			tiltX = Mth.clamp(tiltX + velX, -MAX_TILT, MAX_TILT);
			tiltZ = Mth.clamp(tiltZ + velZ, -MAX_TILT, MAX_TILT);

			float shift = Mth.clamp(pitch * SHIFT_FACTOR, -MAX_SHIFT, MAX_SHIFT);
			float targetShiftX = -Mth.sin(yaw) * shift;
			float targetShiftZ = Mth.cos(yaw) * shift;
			shiftVelX += (targetShiftX - shiftX) * SHIFT_SPRING;
			shiftVelZ += (targetShiftZ - shiftZ) * SHIFT_SPRING;
			shiftVelX *= SHIFT_DAMPING;
			shiftVelZ *= SHIFT_DAMPING;
			shiftX = Mth.clamp(shiftX + shiftVelX, -MAX_SHIFT, MAX_SHIFT);
			shiftZ = Mth.clamp(shiftZ + shiftVelZ, -MAX_SHIFT, MAX_SHIFT);

			lagVelX += (float) (-accX * POS_INERTIA);
			lagVelZ += (float) (-accZ * POS_INERTIA);
			lagVelX += -lagX * POS_SPRING;
			lagVelZ += -lagZ * POS_SPRING;
			lagVelX *= POS_DAMPING;
			lagVelZ *= POS_DAMPING;
			lagX = Mth.clamp(lagX + lagVelX, -MAX_LAG, MAX_LAG);
			lagZ = Mth.clamp(lagZ + lagVelZ, -MAX_LAG, MAX_LAG);

			float targetDrop = Mth.clamp(Math.abs(pitch) * PITCH_DROP_FACTOR, 0.0F, MAX_PITCH_DROP);
			pitchDrop += (targetDrop - pitchDrop) * SHIFT_SPRING;

			// ideal (home) local offset above the head
			double homeOffY = homeY - pitchDrop;

			// delegate collision-aware placement to the pure solver.
			// only world collision blocks the ring; overlapping the player's own body is fine
			// (the ring encircles the torso) and is handled as a soft penalty inside the solver.
			HaloSolver.FreeTest freeTest = box -> level.noCollision(player,
					new AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
			HaloSolver solver = new HaloSolver(px, py, pz, player.getBbHeight(), player.yBodyRot,
					modelR, modelT, ax, ay, az, solveTiltX, solveTiltZ, moveX, moveZ, freeTest);
			double[] moved = solver.solve(homeOffY);
			ax = moved[0];
			ay = moved[1];
			az = moved[2];
			solveTiltX = (float) moved[3];
			solveTiltZ = (float) moved[4];
		}
	}
}
