package com.hongshaoluobotou;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.DamageResistant;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public final class ModItems {
	public static final ResourceKey<Item> END_RING_KEY = ResourceKey.create(Registries.ITEM, EndRing.id("end_ring"));
	public static final Item END_RING = createEndRing();

	private ModItems() {
	}

	public static void register() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT).register(output -> output.accept(END_RING));
	}

	private static Item createEndRing() {
		ItemAttributeModifiers attributes = ItemAttributeModifiers.builder()
			.add(Attributes.ARMOR, modifier("end_ring_armor", 3.0), EquipmentSlotGroup.HEAD)
			.add(Attributes.ARMOR_TOUGHNESS, modifier("end_ring_armor_toughness", 3.0), EquipmentSlotGroup.HEAD)
			.add(Attributes.KNOCKBACK_RESISTANCE, modifier("end_ring_knockback_resistance", 0.1), EquipmentSlotGroup.HEAD)
			.build();

		Item.Properties properties = new Item.Properties()
			.equippable(EquipmentSlot.HEAD)
			.attributes(attributes)
			.setId(END_RING_KEY)
			.durability(EndRingItem.MAX_DAMAGE)
			// .fireResistant() only covers IS_FIRE; the ring also needs to survive explosions, cactus
			// and sweet-berry bushes so a dropped ring cannot be silently destroyed. We build one
			// HolderSet from the tag plus the two block damage types and store it directly.
			.delayedComponent(DataComponents.DAMAGE_RESISTANT, context -> {
				List<Holder<DamageType>> types = new ArrayList<>();
				types.addAll(context.getOrThrow(net.minecraft.tags.DamageTypeTags.IS_FIRE).stream().toList());
				types.addAll(context.getOrThrow(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION).stream().toList());
				types.add(context.getOrThrow(DamageTypes.CACTUS));
				types.add(context.getOrThrow(DamageTypes.SWEET_BERRY_BUSH));
				return new DamageResistant(HolderSet.direct(types));
			})
			.component(ModComponents.END_RING, EndRingComponent.DEFAULT)
			.delayedComponent(DataComponents.ENCHANTMENTS, context -> {
				ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
				enchantments.set(context.getOrThrow(Enchantments.BINDING_CURSE), 1);
				return enchantments.toImmutable();
			});

		return Registry.register(BuiltInRegistries.ITEM, END_RING_KEY, new EndRingItem(properties));
	}

	private static AttributeModifier modifier(String id, double amount) {
		return new AttributeModifier(EndRing.id(id), amount, AttributeModifier.Operation.ADD_VALUE);
	}
}