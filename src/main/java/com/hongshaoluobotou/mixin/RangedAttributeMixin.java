package com.hongshaoluobotou.mixin;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RangedAttribute.class)
public abstract class RangedAttributeMixin {
	@Shadow
	public abstract double getMinValue();

	@Inject(method = "sanitizeValue", at = @At("HEAD"), cancellable = true)
	private void endring$raiseArmorCap(double value, CallbackInfoReturnable<Double> cir) {
		String descriptionId = ((Attribute) (Object) this).getDescriptionId();
		if ("attribute.name.armor".equals(descriptionId)) {
			double sanitized = Double.isNaN(value) ? this.getMinValue() : Mth.clamp(value, this.getMinValue(), 25.0);
			cir.setReturnValue(sanitized);
		} else if ("attribute.name.armor_toughness".equals(descriptionId)) {
			double sanitized = Double.isNaN(value) ? this.getMinValue() : Mth.clamp(value, this.getMinValue(), Double.MAX_VALUE);
			cir.setReturnValue(sanitized);
		}
	}
}
