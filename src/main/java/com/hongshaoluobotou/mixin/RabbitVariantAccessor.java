package com.hongshaoluobotou.mixin;

import net.minecraft.world.entity.animal.rabbit.Rabbit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Rabbit.class)
public interface RabbitVariantAccessor {
	@Invoker("setVariant")
	void endring$setVariant(Rabbit.Variant variant);
}
