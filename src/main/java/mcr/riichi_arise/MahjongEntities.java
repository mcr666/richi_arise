package mcr.riichi_arise;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import mcr.riichi_arise.entity.OraxolotlEntity;

/**
 * 生物实体类型注册（当前仅桔螈）。
 */
public final class MahjongEntities {
	private MahjongEntities() {
	}

	public static final DeferredRegister<EntityType<?>> REGISTRY = DeferredRegister
			.create(Registries.ENTITY_TYPE, RichiiAriseMod.MODID);

	/** 桔螈：被动生物，可右键坐下 */
	public static final DeferredHolder<EntityType<?>, EntityType<OraxolotlEntity>> ORAXOLOTL = REGISTRY.register(
			"oraxolotl",
			() -> EntityType.Builder.of(OraxolotlEntity::new, MobCategory.CREATURE)
					.sized(1.0F, 1.1F)
					.eyeHeight(0.9F)
					.clientTrackingRange(10)
					.build("oraxolotl"));

	/** 实体属性注册（挂在 mod 事件总线上） */
	@SubscribeEvent
	public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
		event.put(ORAXOLOTL.get(), OraxolotlEntity.createAttributes().build());
	}
}
