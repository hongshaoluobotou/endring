package com.hongshaoluobotou;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record DeathAnimationPayload(int entityId, int animationTime) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<DeathAnimationPayload> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(EndRing.MOD_ID, "death_animation"));

	public static final StreamCodec<RegistryFriendlyByteBuf, DeathAnimationPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, DeathAnimationPayload::entityId,
		ByteBufCodecs.VAR_INT, DeathAnimationPayload::animationTime,
		DeathAnimationPayload::new
	);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
