package com.hongshaoluobotou.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class DeathAnimationRenderer {
	private static final float HALF_SQRT_3 = (float) (Math.sqrt(3.0) / 2.0);
	private static final int ANIMATION_TICKS = 200;

	public static void renderAll(PoseStack poseStack, SubmitNodeCollector collector) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || ClientDeathAnimations.all().isEmpty()) {
			return;
		}

		Camera camera = mc.gameRenderer.mainCamera();
		Vec3 camPos = camera.position();
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);

		for (var entry : ClientDeathAnimations.all().entrySet()) {
			Entity entity = level.getEntity(entry.getKey());
			if (entity == null) {
				continue;
			}

			float deathTime = Math.min(entry.getValue().animationTime() + partialTick, ANIMATION_TICKS) / (float) ANIMATION_TICKS;
			double x = Mth.lerp(partialTick, entity.xOld, entity.getX()) - camPos.x;
			double y = Mth.lerp(partialTick, entity.yOld, entity.getY()) - camPos.y;
			double z = Mth.lerp(partialTick, entity.zOld, entity.getZ()) - camPos.z;

			poseStack.pushPose();
			poseStack.translate(x, y + 1.0, z);
			submitRays(poseStack, deathTime, collector, RenderTypes.dragonRays());
			poseStack.popPose();
		}
	}

	private static void submitRays(PoseStack poseStack, float deathTime, SubmitNodeCollector collector, RenderType renderType) {
		collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
			float overDrive = Math.min(deathTime > 0.8F ? (deathTime - 0.8F) / 0.2F : 0.0F, 1.0F);
			int innerColor = ARGB.colorFromFloat(1.0F - overDrive, 1.0F, 1.0F, 1.0F);
			RandomSource random = RandomSource.createThreadLocalInstance(432L);
			Vector3f origin = new Vector3f();
			Vector3f outerLeft = new Vector3f();
			Vector3f outerRight = new Vector3f();
			Vector3f outerBottom = new Vector3f();
			Quaternionf rayRotation = new Quaternionf();
			int rayCount = Mth.floor((deathTime + deathTime * deathTime) / 2.0F * 60.0F);

			for (int i = 0; i < rayCount; i++) {
				rayRotation.rotationXYZ(
						random.nextFloat() * (float) (Math.PI * 2),
						random.nextFloat() * (float) (Math.PI * 2),
						random.nextFloat() * (float) (Math.PI * 2)
					)
					.rotateXYZ(
						random.nextFloat() * (float) (Math.PI * 2),
						random.nextFloat() * (float) (Math.PI * 2),
						random.nextFloat() * (float) (Math.PI * 2) + deathTime * (float) (Math.PI / 2)
					);
				pose.rotate(rayRotation);
				float length = random.nextFloat() * 20.0F + 5.0F + overDrive * 10.0F;
				float width = random.nextFloat() * 2.0F + 1.0F + overDrive * 2.0F;
				outerLeft.set(-HALF_SQRT_3 * width, length, -0.5F * width);
				outerRight.set(HALF_SQRT_3 * width, length, -0.5F * width);
				outerBottom.set(0.0F, length, width);
				buffer.addVertex(pose, origin).setColor(innerColor);
				buffer.addVertex(pose, outerLeft).setColor(16711935);
				buffer.addVertex(pose, outerRight).setColor(16711935);
				buffer.addVertex(pose, origin).setColor(innerColor);
				buffer.addVertex(pose, outerRight).setColor(16711935);
				buffer.addVertex(pose, outerBottom).setColor(16711935);
				buffer.addVertex(pose, origin).setColor(innerColor);
				buffer.addVertex(pose, outerBottom).setColor(16711935);
				buffer.addVertex(pose, outerLeft).setColor(16711935);
			}
		});
	}

	private DeathAnimationRenderer() {
	}
}
