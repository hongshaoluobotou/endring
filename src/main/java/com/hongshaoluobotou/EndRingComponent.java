package com.hongshaoluobotou;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record EndRingComponent(int storedTotems, int totemRegenProgress) implements TooltipProvider {
	public static final EndRingComponent DEFAULT = new EndRingComponent(0, 0);
	public static final EndRingComponent FULL = new EndRingComponent(EndRingItem.MAX_TOTEMS, 0);

	public static final Codec<EndRingComponent> CODEC = RecordCodecBuilder.create(instance ->
		instance.group(
			Codec.intRange(0, EndRingItem.MAX_TOTEMS).fieldOf("storedTotems").forGetter(EndRingComponent::storedTotems),
			Codec.INT.fieldOf("totemRegenProgress").forGetter(EndRingComponent::totemRegenProgress)
		).apply(instance, EndRingComponent::new)
	);

	public static final StreamCodec<RegistryFriendlyByteBuf, EndRingComponent> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, EndRingComponent::storedTotems,
		ByteBufCodecs.VAR_INT, EndRingComponent::totemRegenProgress,
		EndRingComponent::new
	);

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