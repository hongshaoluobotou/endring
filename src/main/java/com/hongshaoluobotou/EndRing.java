package com.hongshaoluobotou;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.item.v1.ItemComponentTooltipProviderRegistry;
import net.minecraft.resources.Identifier;

public class EndRing implements ModInitializer {
	public static final String MOD_ID = "endring";

	@Override
	public void onInitialize() {
		ModComponents.register();
		ModItems.register();
		EndRingEvents.register();
		ItemComponentTooltipProviderRegistry.addLast(ModComponents.END_RING);
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}