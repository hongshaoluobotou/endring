package com.hongshaoluobotou.client;

import com.hongshaoluobotou.DeathAnimationPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

public class EndRingClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(DeathAnimationPayload.TYPE, (payload, context) ->
			context.client().execute(() -> ClientDeathAnimations.update(payload.entityId(), payload.animationTime()))
		);

		ClientTickEvents.END_LEVEL_TICK.register(HaloRenderer::tick);

		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
			ClientDeathAnimations.tickCleanup();
			DeathAnimationRenderer.renderAll(ctx.poseStack(), ctx.submitNodeCollector());
			HaloRenderer.renderAll(ctx.poseStack(), ctx.submitNodeCollector());
		});
	}
}