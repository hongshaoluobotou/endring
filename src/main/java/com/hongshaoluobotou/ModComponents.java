package com.hongshaoluobotou;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;

public final class ModComponents {
	public static final DataComponentType<EndRingComponent> END_RING = Registry.register(
		BuiltInRegistries.DATA_COMPONENT_TYPE,
		EndRing.id("end_ring"),
		DataComponentType.<EndRingComponent>builder()
			.persistent(EndRingComponent.CODEC)
			.networkSynchronized(EndRingComponent.STREAM_CODEC)
			.build()
	);

	private ModComponents() {
	}

	public static void register() {
	}
}