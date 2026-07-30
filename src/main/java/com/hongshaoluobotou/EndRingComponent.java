package com.hongshaoluobotou;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;

public record EndRingComponent() implements TooltipProvider {
	public static final EndRingComponent DEFAULT = new EndRingComponent();

	public static final StreamCodec<RegistryFriendlyByteBuf, EndRingComponent> STREAM_CODEC = StreamCodec.unit(DEFAULT);

	@Override
	public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltip, TooltipFlag flag, DataComponentGetter getter) {
		for (int i = 1; i <= EndRingItem.LORE_LINES; i++) {
			tooltip.accept(
				Component.translatable("item.endring.end_ring.desc" + i)
					.withStyle(ChatFormatting.GRAY)
			);
		}
	}
}
