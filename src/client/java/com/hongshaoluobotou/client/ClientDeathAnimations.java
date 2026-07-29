package com.hongshaoluobotou.client;

import java.util.HashMap;
import java.util.Map;

public final class ClientDeathAnimations {
	private static final Map<Integer, AnimationState> ANIMATIONS = new HashMap<>();

	public static void update(int entityId, int animationTime) {
		ANIMATIONS.put(entityId, new AnimationState(animationTime, System.currentTimeMillis()));
	}

	public static Map<Integer, AnimationState> all() {
		return ANIMATIONS;
	}

	public static void tickCleanup() {
		long now = System.currentTimeMillis();
		ANIMATIONS.entrySet().removeIf(e -> now - e.getValue().lastUpdateMs() > 1000L);
	}

	public record AnimationState(int animationTime, long lastUpdateMs) {
	}

	private ClientDeathAnimations() {
	}
}
