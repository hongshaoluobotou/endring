package com.hongshaoluobotou;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
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
//			.add(Attributes.ARMOR_TOUGHNESS, modifier("end_ring_toughness", 1_000_000.0), EquipmentSlotGroup.HEAD)
			.add(Attributes.ARMOR_TOUGHNESS, modifier("end_ring_toughness", 3.0), EquipmentSlotGroup.HEAD)
			.build();

		Item.Properties properties = new Item.Properties()
			.stacksTo(1)
			.equippable(EquipmentSlot.HEAD)
			.attributes(attributes)
			.setId(END_RING_KEY)
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