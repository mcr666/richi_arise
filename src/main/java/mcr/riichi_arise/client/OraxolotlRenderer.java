package mcr.riichi_arise.client;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

import mcr.riichi_arise.RichiiAriseMod;
import mcr.riichi_arise.entity.OraxolotlEntity;

/**
 * 桔螈实体渲染器：贴图位于 {@code assets/riichi_arise/textures/entity/oraxolotl.png}。
 */
public class OraxolotlRenderer extends MobRenderer<OraxolotlEntity, OraxolotlModel> {
	public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
			RichiiAriseMod.MODID, "textures/entity/oraxolotl.png");

	public OraxolotlRenderer(EntityRendererProvider.Context context) {
		super(context, new OraxolotlModel(context.bakeLayer(OraxolotlModel.LAYER_LOCATION)), 0.5F);
	}

	@Override
	public ResourceLocation getTextureLocation(OraxolotlEntity entity) {
		return TEXTURE;
	}
}
