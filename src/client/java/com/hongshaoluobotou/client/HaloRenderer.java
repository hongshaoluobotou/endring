package com.hongshaoluobotou.client;

import com.hongshaoluobotou.EndRingItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
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
import net.minecraft.world.phys.shapes.VoxelShape;

public final class HaloRenderer {
	private static final int FULL_BRIGHT = 0xF000F0;
	private static final float BASE_HEIGHT = 0.45F;
	private static final float MODEL_SCALE = 1.15F;
	private static final float SPIN_SPEED = 2.2F;

	// how far around the player to snapshot solid collision boxes for the physics worker
	private static final double SNAPSHOT_PAD = 6.0;

	// real model extents are measured from the baked model; these are fallbacks until known.
	private static final double DEFAULT_R = 0.5;
	private static final double DEFAULT_T = 0.06;

	private static volatile double modelR = DEFAULT_R;
	private static volatile double modelT = DEFAULT_T;
	private static volatile boolean modelMeasured = false;

	// decorative spin is the only render-thread animation state we still keep per halo.
	private static final java.util.Map<UUID, float[]> SPIN = new java.util.HashMap<>();

	private HaloRenderer() {
	}

	public static void tick(ClientLevel level) {
		Minecraft mc = Minecraft.getInstance();
		Set<UUID> live = new HashSet<>();
		for (AbstractClientPlayer player : level.players()) {
			ItemStack ring = EndRingItem.getWorn(player);
			if (ring.isEmpty()) {
				HaloScheduler.remove(player.getUUID());
				SPIN.remove(player.getUUID());
				continue;
			}
			measureModel(mc, player, ring);
			live.add(player.getUUID());
			submitSnapshot(mc, level, player);
		}
		// drop halos whose players left or unequipped
		Iterator<UUID> it = SPIN.keySet().iterator();
		while (it.hasNext()) {
			UUID id = it.next();
			if (!live.contains(id)) {
				it.remove();
				HaloScheduler.remove(id);
			}
		}
	}

	// build the immutable physics input snapshot on the client thread (world reads must be here)
	// and hand it to the async scheduler.
	private static void submitSnapshot(Minecraft mc, ClientLevel level, AbstractClientPlayer player) {
		double px = player.getX();
		double py = player.getY();
		double pz = player.getZ();
		double bb = player.getBbHeight();
		double homeOffY = bb + BASE_HEIGHT;

		AABB region = new AABB(px - SNAPSHOT_PAD, py - SNAPSHOT_PAD, pz - SNAPSHOT_PAD,
			px + SNAPSHOT_PAD, py + bb + SNAPSHOT_PAD, pz + SNAPSHOT_PAD);
		List<HaloPhysics.Box> world = new ArrayList<>();
		for (VoxelShape shape : level.getBlockCollisions(player, region)) {
			for (AABB a : shape.toAabbs()) {
				world.add(new HaloPhysics.Box(a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ));
			}
		}

		HaloPhysics.Input input = new HaloPhysics.Input(px, py, pz, bb, player.yBodyRot,
			homeOffY, player.getYHeadRot(), player.getXRot(), world);
		HaloScheduler.submit(player.getUUID(), modelR, modelT, input);
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
		if (level == null) {
			return;
		}

		Camera camera = mc.gameRenderer.mainCamera();
		double camX = camera.position().x;
		double camY = camera.position().y;
		double camZ = camera.position().z;
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);

		for (AbstractClientPlayer player : level.players()) {
			UUID id = player.getUUID();
			HaloScheduler.Frame frame = HaloScheduler.frame(id);
			if (frame == null || player.isInvisible() || EndRingItem.getWorn(player).isEmpty()) {
				continue;
			}

			ItemStackRenderState renderState = new ItemStackRenderState();
			mc.getItemModelResolver().updateForNonLiving(renderState, EndRingItem.getWorn(player), ItemDisplayContext.NONE, player);
			if (renderState.isEmpty()) {
				continue;
			}

			HaloPhysics.State prev = frame.prev();
			HaloPhysics.State cur = frame.cur();
			double ax = Mth.lerp(partialTick, prev.x(), cur.x());
			double ay = Mth.lerp(partialTick, prev.y(), cur.y());
			double az = Mth.lerp(partialTick, prev.z(), cur.z());
			float tiltX = (float) Mth.lerp(partialTick, prev.tiltX(), cur.tiltX());
			float tiltZ = (float) Mth.lerp(partialTick, prev.tiltZ(), cur.tiltZ());

			float[] spinState = SPIN.computeIfAbsent(id, k -> new float[]{0F, 0F});
			float spin = Mth.lerp(partialTick, spinState[1], spinState[0]);

			double px = Mth.lerp(partialTick, player.xOld, player.getX()) - camX;
			double py = Mth.lerp(partialTick, player.yOld, player.getY()) - camY;
			double pz = Mth.lerp(partialTick, player.zOld, player.getZ()) - camZ;

			poseStack.pushPose();
			poseStack.translate(px + ax, py + ay, pz + az);
			poseStack.mulPose(Axis.XP.rotationDegrees(tiltX));
			poseStack.mulPose(Axis.ZP.rotationDegrees(tiltZ));
			poseStack.mulPose(Axis.YP.rotationDegrees(spin));
			poseStack.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
			poseStack.translate(0.0, -0.5, 0.0);
			renderState.submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
			poseStack.popPose();
		}

		advanceSpin();
	}

	// decorative spin advances once per frame; kept lock-free and render-thread-local.
	private static void advanceSpin() {
		for (float[] s : SPIN.values()) {
			s[1] = s[0];
			s[0] += SPIN_SPEED;
			if (s[0] >= 360.0F) {
				s[0] -= 360.0F;
				s[1] -= 360.0F;
			}
		}
	}
}
