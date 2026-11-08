package mcr.riichi_arise.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import mcr.riichi_arise.entity.OraxolotlEntity;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 桔螈模型：由 Blockbench 导出的原版几何直接烘焙，动画见 {@link OraxolotlAnimations}。
 */
public class OraxolotlModel extends HierarchicalModel<OraxolotlEntity> {
	public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(
			ResourceLocation.fromNamespaceAndPath(mcr.riichi_arise.RichiiAriseMod.MODID, "oraxolotl"), "main");

	private final ModelPart root;
	private final ModelPart head;
	/** 常规状态切换过渡时长（tick） */
	private static final float TRANSITION_TICKS = 4.0F;
	/** 一次性动作（攻击/刨土/跳跃/下落）的过渡时长（tick），更短以免动作发飘 */
	private static final float ONE_SHOT_TRANSITION_TICKS = 2.0F;
	/** 参与过渡插值的所有骨骼（x/y/z + xRot/yRot/zRot） */
	private final List<ModelPart> transitionParts;
	/** 每个渲染主体（实体 / AI 假玩家）独立的过渡状态；弱键避免实体卸载后泄漏 */
	private final Map<Object, Transition> transitions = new WeakHashMap<>();

	public OraxolotlModel(ModelPart root) {
		this.root = root;
		this.head = root.getChild("body").getChild("head");
		this.transitionParts = this.root.getAllParts().toList();
	}

	public static LayerDefinition createBodyLayer() {
		MeshDefinition meshdefinition = new MeshDefinition();
		PartDefinition partdefinition = meshdefinition.getRoot();

		PartDefinition body = partdefinition.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.offset(0.0F, 12.0F, 0.0F));

		PartDefinition torso = body.addOrReplaceChild("torso", CubeListBuilder.create().texOffs(87, 36).addBox(-4.0F, 4.8F, -2.6F, 8.0F, 12.0F, 6.0F, new CubeDeformation(0.0F))
		.texOffs(56, 96).addBox(-4.5F, -0.2F, -3.2F, 9.0F, 7.0F, 7.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, -12.0F, 0.0F));

		PartDefinition hair = torso.addOrReplaceChild("hair", CubeListBuilder.create(), PartPose.offset(0.0F, 5.8F, 3.0F));

		hair.addOrReplaceChild("cube_r9", CubeListBuilder.create().texOffs(56, 43).addBox(-6.0F, 0.0F, 0.0F, 7.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.5F, -4.3935F, 0.2114F, 0.7854F, 0.0F, 0.0F));

		hair.addOrReplaceChild("cube_r11", CubeListBuilder.create().texOffs(56, 41).addBox(-6.0F, 0.0F, 0.0F, 7.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.5F, -5.1935F, 0.2114F, 0.7854F, 0.0F, 0.0F));

		hair.addOrReplaceChild("cube_r10", CubeListBuilder.create().texOffs(56, 41).addBox(-6.0F, 0.0F, 0.0F, 7.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.5F, -6.2071F, -1.9071F, 0.7854F, 0.0F, 0.0F));

		PartDefinition Tail = torso.addOrReplaceChild("Tail", CubeListBuilder.create(), PartPose.offsetAndRotation(0.0F, 7.3F, 1.0F, -1.7453F, 0.0F, 3.1416F));

		PartDefinition TailPrimary = Tail.addOrReplaceChild("TailPrimary", CubeListBuilder.create(), PartPose.offsetAndRotation(0.0F, 0.6392F, -8.8785F, -0.1309F, 0.0F, 0.0F));

		TailPrimary.addOrReplaceChild("Leaf1_r1", CubeListBuilder.create().texOffs(18, 113).mirror().addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)).mirror(false), PartPose.offsetAndRotation(0.0F, 1.8108F, -1.3715F, -2.4034F, -0.7647F, 0.7488F));

		TailPrimary.addOrReplaceChild("Leaf1_r2", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(0.0F, 1.8108F, -1.3715F, -2.4034F, 0.7647F, -0.7488F));

		TailPrimary.addOrReplaceChild("Base_r1_r1", CubeListBuilder.create().texOffs(48, 45).addBox(-2.0F, -12.8636F, -2.6035F, 4.0F, 5.0F, 4.0F, new CubeDeformation(-0.2F)), PartPose.offsetAndRotation(0.0F, 5.6716F, -11.9073F, -1.1781F, 0.0F, 0.0F));

		PartDefinition TailSecondary = TailPrimary.addOrReplaceChild("TailSecondary", CubeListBuilder.create(), PartPose.offset(0.0F, 1.564F, -3.473F));

		TailSecondary.addOrReplaceChild("Leaf1_r3", CubeListBuilder.create().texOffs(18, 113).mirror().addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)).mirror(false), PartPose.offsetAndRotation(-0.25F, 1.2468F, -1.6486F, -3.0569F, -0.9556F, 1.2026F));

		TailSecondary.addOrReplaceChild("Leaf1_r4", CubeListBuilder.create().texOffs(18, 113).mirror().addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)).mirror(false), PartPose.offsetAndRotation(-0.25F, 1.2468F, -5.6486F, -3.0924F, -0.1281F, 1.2655F));

		TailSecondary.addOrReplaceChild("Base_r2_r1", CubeListBuilder.create().texOffs(24, 16).mirror().addBox(-2.5F, -12.7F, -8.0F, 5.0F, 8.0F, 5.0F, new CubeDeformation(-0.2F)).mirror(false)
		.texOffs(24, 16).addBox(-2.5F, -12.7F, -8.0F, 5.0F, 8.0F, 5.0F, new CubeDeformation(-0.2F)), PartPose.offsetAndRotation(0.0F, 6.5486F, -12.2676F, -1.4835F, 0.0F, 0.0F));

		TailSecondary.addOrReplaceChild("Leaf1_r5", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(0.0F, 1.2468F, -5.6486F, -2.7315F, -0.7084F, -0.2736F));

		TailSecondary.addOrReplaceChild("Leaf1_r6", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(0.0F, 0.7468F, -2.6486F, -2.7315F, -0.7084F, -0.2736F));

		TailSecondary.addOrReplaceChild("Leaf1_r7", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(0.25F, 1.2468F, -5.6486F, -3.0924F, 0.1281F, -1.2655F));

		TailSecondary.addOrReplaceChild("Leaf1_r8", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(0.25F, 1.2468F, -1.6486F, -3.0569F, 0.9556F, -1.2026F));

		PartDefinition TailTertiary = TailSecondary.addOrReplaceChild("TailTertiary", CubeListBuilder.create(), PartPose.offset(0.0F, 0.75F, -6.5F));

		TailTertiary.addOrReplaceChild("Leaf1_r9", CubeListBuilder.create().texOffs(18, 113).mirror().addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)).mirror(false), PartPose.offsetAndRotation(-0.75F, 0.4968F, -2.1486F, -2.7869F, -0.1281F, 1.2655F));

		TailTertiary.addOrReplaceChild("Leaf1_r10", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(0.75F, 0.4968F, -2.1486F, -2.7869F, 0.1281F, -1.2655F));

		TailTertiary.addOrReplaceChild("Leaf1_r11", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-5.0F)), PartPose.offsetAndRotation(0.0F, 2.4968F, -2.8986F, -1.7754F, -0.2576F, -0.755F));

		TailTertiary.addOrReplaceChild("Leaf1_r12", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.5F)), PartPose.offsetAndRotation(0.0F, 2.4968F, -5.6486F, -2.7315F, -0.7084F, -0.2736F));

		TailTertiary.addOrReplaceChild("Base_r3_r1", CubeListBuilder.create().texOffs(48, 54).addBox(-2.0F, -12.7F, -14.55F, 4.0F, 4.0F, 4.0F, new CubeDeformation(-0.2F)), PartPose.offsetAndRotation(0.0F, 13.0115F, -12.9433F, -1.5272F, 0.0F, 0.0F));

		PartDefinition TailQuaternary = TailTertiary.addOrReplaceChild("TailQuaternary", CubeListBuilder.create(), PartPose.offset(-0.5F, -0.35F, -4.2F));

		TailQuaternary.addOrReplaceChild("Base_r4", CubeListBuilder.create().texOffs(30, 64).addBox(-1.0F, 0.3F, -1.05F, 3.0F, 3.0F, 3.0F, new CubeDeformation(-0.2F)), PartPose.offsetAndRotation(0.0F, -0.25F, 0.5F, -1.5272F, 0.0F, 0.0F));

		PartDefinition leaf1 = torso.addOrReplaceChild("leaf1", CubeListBuilder.create(), PartPose.offsetAndRotation(0.0F, 11.3584F, 4.0263F, 1.3526F, 0.0F, 3.1416F));

		leaf1.addOrReplaceChild("Base_r1", CubeListBuilder.create().texOffs(0, 81).addBox(-2.0F, 0.0F, -1.0F, 3.0F, 3.0F, 2.0F, new CubeDeformation(-0.1F)), PartPose.offsetAndRotation(0.5F, 0.1666F, -10.4263F, 1.1781F, 0.0F, 0.0F));

		leaf1.addOrReplaceChild("Base_r2", CubeListBuilder.create().texOffs(0, 86).addBox(-3.0F, -2.7F, 0.0F, 5.0F, 3.0F, 2.0F, new CubeDeformation(-0.05F)), PartPose.offsetAndRotation(0.5F, 2.2916F, -5.4263F, 1.5272F, 0.0F, 0.0F));

		leaf1.addOrReplaceChild("Base_r3", CubeListBuilder.create().texOffs(0, 107).addBox(-4.0F, -0.35F, -1.4F, 7.0F, 3.0F, 2.0F, new CubeDeformation(0.1F)), PartPose.offsetAndRotation(0.5F, 0.9416F, -5.6263F, 1.789F, 0.0F, 0.0F));

		leaf1.addOrReplaceChild("Base_r5", CubeListBuilder.create().texOffs(0, 96).addBox(-6.0F, 0.375F, -0.4F, 9.0F, 4.0F, 2.0F, new CubeDeformation(0.1F)), PartPose.offsetAndRotation(1.5F, 1.5916F, -3.3013F, 2.0071F, 0.0F, 0.0F));

		leaf1.addOrReplaceChild("Base_r6", CubeListBuilder.create().texOffs(0, 115).addBox(-6.0F, -0.35F, -0.4F, 9.0F, 4.0F, 2.0F, new CubeDeformation(-0.05F)), PartPose.offsetAndRotation(1.5F, -0.0834F, 0.3737F, 1.7017F, 0.0F, 0.0F));

		leaf1.addOrReplaceChild("Base_r7", CubeListBuilder.create().texOffs(1, 112).addBox(0.0F, -0.35F, 0.6F, 3.0F, 2.0F, 1.0F, new CubeDeformation(-0.05F)), PartPose.offsetAndRotation(-1.5F, 0.4416F, 7.0987F, 1.1345F, 0.0F, 0.0F));

		leaf1.addOrReplaceChild("Base_r8", CubeListBuilder.create().texOffs(0, 86).addBox(-2.0F, -0.35F, -0.4F, 5.0F, 3.0F, 2.0F, new CubeDeformation(-0.4F)), PartPose.offsetAndRotation(-0.5F, -0.5834F, 5.2987F, 1.309F, 0.0F, 0.0F));

		leaf1.addOrReplaceChild("Base_r9", CubeListBuilder.create().texOffs(0, 102).addBox(-4.0F, 0.65F, -0.4F, 7.0F, 3.0F, 2.0F, new CubeDeformation(-0.2F)), PartPose.offsetAndRotation(0.5F, -0.6584F, 2.5987F, 1.4835F, 0.0F, 0.0F));

		PartDefinition leaf2 = torso.addOrReplaceChild("leaf2", CubeListBuilder.create(), PartPose.offset(-8.75F, 12.4F, 3.0F));

		leaf2.addOrReplaceChild("Leaf1_r13", CubeListBuilder.create().texOffs(18, 113).mirror().addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)).mirror(false), PartPose.offsetAndRotation(4.75F, 0.35F, 4.75F, -1.8604F, -0.3609F, 0.5031F));

		leaf2.addOrReplaceChild("Leaf1_r14", CubeListBuilder.create().texOffs(18, 113).mirror().addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)).mirror(false), PartPose.offsetAndRotation(4.75F, -4.65F, 4.75F, -1.8604F, -0.3609F, 0.5031F));

		leaf2.addOrReplaceChild("Leaf6_r1", CubeListBuilder.create().texOffs(18, 113).mirror().addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)).mirror(false), PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, -1.0114F, 1.0354F, -2.5013F));

		leaf2.addOrReplaceChild("Leaf6_r2", CubeListBuilder.create().texOffs(18, 113).mirror().addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)).mirror(false), PartPose.offsetAndRotation(0.0F, -5.0F, 0.0F, -1.0114F, 1.0354F, -2.5013F));

		leaf2.addOrReplaceChild("Leaf6_r3", CubeListBuilder.create().texOffs(18, 113).mirror().addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)).mirror(false), PartPose.offsetAndRotation(1.75F, 6.75F, 1.0F, -0.9598F, 0.5769F, -2.9957F));

		leaf2.addOrReplaceChild("Leaf6_r4", CubeListBuilder.create().texOffs(18, 113).mirror().addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)).mirror(false), PartPose.offsetAndRotation(0.0F, -9.0F, 0.0F, -0.5962F, 0.6918F, -1.9683F));

		leaf2.addOrReplaceChild("Leaf6_r5", CubeListBuilder.create().texOffs(18, 113).mirror().addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)).mirror(false), PartPose.offsetAndRotation(3.0F, -16.0F, -0.75F, -2.4445F, 1.1236F, -3.1317F));

		leaf2.addOrReplaceChild("Leaf6_r6", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(15.75F, 6.75F, 1.0F, -0.9598F, -0.5769F, 2.9957F));

		leaf2.addOrReplaceChild("Leaf6_r7", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(17.5F, 0.0F, 0.0F, -1.0114F, -1.0354F, 2.5013F));

		leaf2.addOrReplaceChild("Leaf6_r8", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(17.5F, 0.75F, 0.0F, -2.593F, -0.3979F, -2.0521F));

		leaf2.addOrReplaceChild("Leaf6_r9", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(17.5F, -5.0F, 0.0F, -1.0114F, -1.0354F, 2.5013F));

		leaf2.addOrReplaceChild("Leaf6_r10", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(17.5F, -9.0F, 0.0F, -0.5962F, -0.6918F, 1.9683F));

		leaf2.addOrReplaceChild("Leaf1_r15", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(8.75F, -17.15F, -1.0F, -0.6728F, 0.582F, -0.437F));

		leaf2.addOrReplaceChild("Leaf6_r11", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(14.5F, -16.0F, -0.75F, -2.4445F, -1.1236F, 3.1317F));

		leaf2.addOrReplaceChild("Leaf6_r12", CubeListBuilder.create().texOffs(18, 113).mirror().addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)).mirror(false), PartPose.offsetAndRotation(0.0F, 0.75F, 0.0F, -2.593F, 0.3979F, 2.0521F));

		PartDefinition leaf3 = torso.addOrReplaceChild("leaf3", CubeListBuilder.create(), PartPose.offset(8.75F, 12.4F, 3.0F));

		leaf3.addOrReplaceChild("Leaf1_r16", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(-8.75F, 5.35F, 5.0F, -1.3372F, 0.3855F, -0.8719F));

		leaf3.addOrReplaceChild("Leaf1_r17", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(-8.75F, -0.65F, 5.0F, -1.3372F, 0.3855F, -0.8719F));

		leaf3.addOrReplaceChild("Leaf1_r18", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(-8.75F, -4.65F, 5.0F, -1.3372F, 0.3855F, -0.8719F));

		leaf3.addOrReplaceChild("Leaf1_r19", CubeListBuilder.create().texOffs(18, 113).mirror().addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)).mirror(false), PartPose.offsetAndRotation(-12.75F, 5.35F, 4.75F, -1.8604F, -0.3609F, 0.5031F));

		leaf3.addOrReplaceChild("Leaf1_r20", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(-4.75F, 5.35F, 4.75F, -1.8604F, 0.3609F, -0.5031F));

		leaf3.addOrReplaceChild("Leaf1_r21", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(-4.75F, 0.35F, 4.75F, -1.8604F, 0.3609F, -0.5031F));

		leaf3.addOrReplaceChild("Leaf1_r22", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(-4.75F, -4.65F, 4.75F, -1.8604F, 0.3609F, -0.5031F));

		PartDefinition head = body.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0).addBox(-4.0F, -8.0F, -3.95F, 8.0F, 8.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, -12.0F, 0.0F));

		head.addOrReplaceChild("Head_r1", CubeListBuilder.create().texOffs(66, 8).addBox(-2.0F, -27.5F, 4.0F, 4.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
		.texOffs(44, 27).addBox(-1.5F, -25.5F, 4.0F, 3.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 24.5F, 0.05F, -3.1416F, 0.0F, 3.1416F));

		head.addOrReplaceChild("Leaf1_r23", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(0.0F, -12.25F, -3.9F, 0.7011F, 0.6063F, 0.4414F));

		head.addOrReplaceChild("Leaf1_r24", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(0.0F, -12.25F, 2.1F, -0.5557F, 0.7239F, -0.3957F));

		head.addOrReplaceChild("Leaf1_r25", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(3.0F, -9.25F, 6.85F, -1.546F, 0.7333F, -0.6507F));

		head.addOrReplaceChild("Leaf1_r26", CubeListBuilder.create().texOffs(18, 113).addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)), PartPose.offsetAndRotation(4.0F, -6.25F, 7.85F, -1.546F, 0.7333F, -0.6507F));

		head.addOrReplaceChild("Leaf1_r27", CubeListBuilder.create().texOffs(18, 113).mirror().addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)).mirror(false), PartPose.offsetAndRotation(-4.0F, -6.25F, 7.85F, -1.546F, -0.7333F, 0.6507F));

		head.addOrReplaceChild("Leaf1_r28", CubeListBuilder.create().texOffs(18, 113).mirror().addBox(-7.0F, -0.5F, -7.0F, 14.0F, 1.0F, 14.0F, new CubeDeformation(-4.0F)).mirror(false), PartPose.offsetAndRotation(-3.0F, -9.25F, 6.85F, -1.546F, -0.7333F, 0.6507F));

		PartDefinition right_ear2 = head.addOrReplaceChild("right_ear2", CubeListBuilder.create(), PartPose.offsetAndRotation(-2.8579F, -9.688F, 0.4749F, -0.1349F, 1.0979F, 0.662F));

		right_ear2.addOrReplaceChild("cube_r4_r1", CubeListBuilder.create().texOffs(18, 65).mirror().addBox(-0.8982F, -42.697F, 1.7769F, 1.0F, 5.0F, 3.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offsetAndRotation(14.704F, 24.8655F, 30.3374F, 1.0472F, 0.4962F, 0.04F));

		right_ear2.addOrReplaceChild("cube_r1_r1", CubeListBuilder.create().texOffs(46, 62).mirror().addBox(0.1018F, -43.247F, 1.0769F, 2.0F, 5.0F, 4.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offsetAndRotation(14.9592F, 24.821F, 31.4005F, 1.0472F, 0.4962F, 0.04F));

		right_ear2.addOrReplaceChild("cube_r8_r1", CubeListBuilder.create().texOffs(46, 71).mirror().addBox(2.1768F, -41.547F, -1.5731F, 2.0F, 1.0F, 3.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offsetAndRotation(13.0943F, 20.2128F, 31.9247F, 1.0472F, 0.4962F, 0.04F));

		right_ear2.addOrReplaceChild("cube_r7_r1", CubeListBuilder.create().texOffs(18, 73).mirror().addBox(3.4018F, -42.872F, -1.8481F, 1.0F, 1.0F, 2.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offsetAndRotation(13.3532F, 19.3004F, 32.8298F, 1.0472F, 0.4962F, 0.04F));

		PartDefinition left_ear = head.addOrReplaceChild("left_ear", CubeListBuilder.create(), PartPose.offsetAndRotation(2.8579F, -9.688F, 0.4749F, -0.1349F, -1.0979F, -0.662F));

		left_ear.addOrReplaceChild("cube_r4_r2", CubeListBuilder.create().texOffs(18, 65).addBox(-0.1018F, -42.697F, 1.7769F, 1.0F, 5.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-14.704F, 24.8655F, 30.3374F, 1.0472F, -0.4962F, -0.04F));

		left_ear.addOrReplaceChild("cube_r1_r2", CubeListBuilder.create().texOffs(46, 62).addBox(-2.1018F, -43.247F, 1.0769F, 2.0F, 5.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-14.9592F, 24.821F, 31.4005F, 1.0472F, -0.4962F, -0.04F));

		left_ear.addOrReplaceChild("cube_r8_r2", CubeListBuilder.create().texOffs(46, 71).addBox(-4.1768F, -41.547F, -1.5731F, 2.0F, 1.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-13.0943F, 20.2128F, 31.9247F, 1.0472F, -0.4962F, -0.04F));

		left_ear.addOrReplaceChild("cube_r7_r2", CubeListBuilder.create().texOffs(18, 73).addBox(-4.4018F, -42.872F, -1.8481F, 1.0F, 1.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-13.3532F, 19.3004F, 32.8298F, 1.0472F, -0.4962F, -0.04F));

		PartDefinition right_arm = body.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(49, 8).mirror().addBox(-3.0F, 7.8757F, -1.7076F, 4.0F, 2.0F, 4.0F, new CubeDeformation(0.1F)).mirror(false)
		.texOffs(49, 8).addBox(-3.01F, 7.8757F, -2.3076F, 4.0F, 2.0F, 4.0F, new CubeDeformation(0.1F))
		.texOffs(24, 29).mirror().addBox(-3.0F, -2.0197F, -1.6284F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offset(-5.0F, -10.0F, 0.0F));

		right_arm.addOrReplaceChild("cube_r1", CubeListBuilder.create().texOffs(4, 66).mirror().addBox(-1.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.15F)).mirror(false)
		.texOffs(4, 66).mirror().addBox(2.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.15F)).mirror(false), PartPose.offsetAndRotation(-2.0F, 9.85F, -2.8F, -0.3054F, 0.0F, 0.0F));

		right_arm.addOrReplaceChild("cube_r2", CubeListBuilder.create().texOffs(4, 66).mirror().addBox(-1.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F)).mirror(false)
		.texOffs(4, 66).mirror().addBox(0.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F)).mirror(false), PartPose.offsetAndRotation(-1.0F, 9.8F, -2.8F, -0.3054F, 0.0F, 0.0F));

		right_arm.addOrReplaceChild("paw4", CubeListBuilder.create().texOffs(72, 41).mirror().addBox(2.0F, 0.17F, -2.28F, 2.0F, 1.0F, 2.0F, new CubeDeformation(0.0F)).mirror(false)
		.texOffs(38, 72).mirror().addBox(2.0F, 0.27F, 0.12F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F)).mirror(false)
		.texOffs(10, 72).mirror().addBox(3.0F, 0.27F, 0.12F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F)).mirror(false)
		.texOffs(38, 70).mirror().addBox(4.0F, 0.27F, -0.48F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F)).mirror(false)
		.texOffs(66, 12).mirror().addBox(1.0F, 0.27F, -0.48F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F)).mirror(false), PartPose.offsetAndRotation(2.0F, 8.9102F, -1.1484F, -3.1416F, 0.0F, 3.1416F));

		PartDefinition left_arm = body.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(49, 8).addBox(-1.0F, 7.8757F, -1.7076F, 4.0F, 2.0F, 4.0F, new CubeDeformation(0.1F))
		.texOffs(49, 8).mirror().addBox(-0.99F, 7.8757F, -2.3076F, 4.0F, 2.0F, 4.0F, new CubeDeformation(0.1F)).mirror(false)
		.texOffs(24, 29).addBox(-1.0F, -2.0197F, -1.6284F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.offset(5.0F, -10.0F, 0.0F));

		left_arm.addOrReplaceChild("cube_r3", CubeListBuilder.create().texOffs(4, 66).addBox(0.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F))
		.texOffs(4, 66).addBox(1.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F)), PartPose.offsetAndRotation(0.0F, 9.8F, -2.8F, -0.3054F, 0.0F, 0.0F));

		left_arm.addOrReplaceChild("cube_r4", CubeListBuilder.create().texOffs(4, 66).addBox(0.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.15F))
		.texOffs(4, 66).addBox(3.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.15F)), PartPose.offsetAndRotation(-1.0F, 9.85F, -2.8F, -0.3054F, 0.0F, 0.0F));

		left_arm.addOrReplaceChild("paw3", CubeListBuilder.create().texOffs(72, 41).addBox(-4.0F, -0.58F, -2.28F, 2.0F, 1.0F, 2.0F, new CubeDeformation(0.0F))
		.texOffs(38, 72).addBox(-3.0F, -0.48F, 0.12F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F))
		.texOffs(10, 72).addBox(-4.0F, -0.48F, 0.12F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F))
		.texOffs(38, 70).addBox(-5.0F, -0.48F, -0.48F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F))
		.texOffs(66, 12).addBox(-2.0F, -0.48F, -0.48F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F)), PartPose.offsetAndRotation(-2.0F, 9.6602F, -1.1484F, -3.1416F, 0.0F, 3.1416F));

		PartDefinition right_leg = body.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-2.0F, 0.0F, 0.0F));

		PartDefinition RightLowerLeg = right_leg.addOrReplaceChild("RightLowerLeg", CubeListBuilder.create(), PartPose.offsetAndRotation(-0.5F, 5.0007F, -3.6576F, -3.1416F, 0.0F, 3.1416F));

		RightLowerLeg.addOrReplaceChild("RightCalf_r1", CubeListBuilder.create().texOffs(0, 48).mirror().addBox(-1.99F, -0.125F, -1.1F, 4.0F, 6.0F, 4.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offsetAndRotation(0.0F, -2.125F, -1.95F, -0.8727F, 0.0F, 0.0F));

		PartDefinition RightFoot = RightLowerLeg.addOrReplaceChild("RightFoot", CubeListBuilder.create(), PartPose.offset(0.0F, 0.8F, -7.175F));

		RightFoot.addOrReplaceChild("RightArch_r1", CubeListBuilder.create().texOffs(16, 56).mirror().addBox(-2.0F, -8.45F, -2.275F, 4.0F, 6.0F, 3.0F, new CubeDeformation(0.005F)).mirror(false), PartPose.offsetAndRotation(0.0F, 7.075F, 4.975F, 0.3491F, 0.0F, 0.0F));

		PartDefinition RightPad = RightFoot.addOrReplaceChild("RightPad", CubeListBuilder.create().texOffs(48, 7).mirror().addBox(-2.0F, 0.0F, -2.5F, 4.0F, 2.0F, 5.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offset(0.0F, 4.325F, 4.425F));

		RightPad.addOrReplaceChild("bone3", CubeListBuilder.create().texOffs(74, 37).mirror().addBox(-13.0F, 9.2F, 0.3F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F)).mirror(false)
		.texOffs(38, 74).mirror().addBox(-10.0F, 9.2F, 0.3F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F)).mirror(false)
		.texOffs(48, 14).mirror().addBox(-11.0F, 9.2F, 0.9F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F)).mirror(false)
		.texOffs(52, 14).mirror().addBox(-12.0F, 9.2F, 0.9F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F)).mirror(false)
		.texOffs(72, 20).mirror().addBox(-12.0F, 9.1F, -1.9F, 2.0F, 1.0F, 2.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offset(11.0F, -8.0F, 0.55F));

		right_leg.addOrReplaceChild("RightThigh_r1", CubeListBuilder.create().texOffs(16, 45).mirror().addBox(-2.01F, 0.0F, -2.0F, 4.0F, 7.0F, 4.0F, new CubeDeformation(0.01F)).mirror(false), PartPose.offsetAndRotation(-0.5F, -1.375F, -0.2F, -0.2182F, 0.0F, 0.0F));

		PartDefinition RightLowerLeg2 = right_leg.addOrReplaceChild("RightLowerLeg2", CubeListBuilder.create(), PartPose.offsetAndRotation(-0.5F, 5.0007F, -3.6576F, -3.1416F, 0.0F, -3.1416F));

		PartDefinition RightFoot2 = RightLowerLeg2.addOrReplaceChild("RightFoot2", CubeListBuilder.create(), PartPose.offset(0.0F, 0.8F, -7.175F));

		PartDefinition RightPad2 = RightFoot2.addOrReplaceChild("RightPad2", CubeListBuilder.create(), PartPose.offset(0.0F, 4.325F, 4.425F));

		RightPad2.addOrReplaceChild("cube_r5", CubeListBuilder.create().texOffs(4, 66).mirror().addBox(-1.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F)).mirror(false)
		.texOffs(4, 66).mirror().addBox(-2.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F)).mirror(false), PartPose.offsetAndRotation(1.0F, 2.1243F, 1.9076F, 0.3054F, 0.0F, 0.0F));

		RightPad2.addOrReplaceChild("cube_r6", CubeListBuilder.create().texOffs(4, 66).mirror().addBox(-1.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.15F)).mirror(false)
		.texOffs(4, 66).mirror().addBox(-4.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.15F)).mirror(false), PartPose.offsetAndRotation(2.0F, 2.1743F, 1.9076F, 0.3054F, 0.0F, 0.0F));

		PartDefinition left_leg = body.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(2.0F, 0.0F, 0.0F));

		PartDefinition LeftLowerLeg = left_leg.addOrReplaceChild("LeftLowerLeg", CubeListBuilder.create(), PartPose.offsetAndRotation(0.5F, 5.0007F, -3.6576F, -3.1416F, 0.0F, 3.1416F));

		LeftLowerLeg.addOrReplaceChild("LeftCalf_r1", CubeListBuilder.create().texOffs(0, 48).addBox(-2.01F, -0.125F, -1.1F, 4.0F, 6.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, -2.125F, -1.95F, -0.8727F, 0.0F, 0.0F));

		PartDefinition LeftFoot = LeftLowerLeg.addOrReplaceChild("LeftFoot", CubeListBuilder.create(), PartPose.offset(0.0F, 0.8F, -7.175F));

		LeftFoot.addOrReplaceChild("LeftArch_r1", CubeListBuilder.create().texOffs(16, 56).addBox(-2.0F, -8.45F, -2.275F, 4.0F, 6.0F, 3.0F, new CubeDeformation(0.005F)), PartPose.offsetAndRotation(0.0F, 7.075F, 4.975F, 0.3491F, 0.0F, 0.0F));

		PartDefinition LeftPad = LeftFoot.addOrReplaceChild("LeftPad", CubeListBuilder.create().texOffs(48, 7).addBox(-2.0F, 0.0F, -2.5F, 4.0F, 2.0F, 5.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 4.325F, 4.425F));

		LeftPad.addOrReplaceChild("cube_r7", CubeListBuilder.create().texOffs(4, 66).addBox(0.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F))
		.texOffs(4, 66).addBox(1.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F)), PartPose.offsetAndRotation(-1.0F, 2.1243F, 1.9076F, 0.3054F, 0.0F, 0.0F));

		LeftPad.addOrReplaceChild("cube_r8", CubeListBuilder.create().texOffs(4, 66).addBox(0.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.15F))
		.texOffs(4, 66).addBox(3.0F, -1.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.15F)), PartPose.offsetAndRotation(-2.0F, 2.1743F, 1.9076F, 0.3054F, 0.0F, 0.0F));

		LeftPad.addOrReplaceChild("bone2", CubeListBuilder.create().texOffs(74, 37).addBox(12.0F, 9.2F, 0.3F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F))
		.texOffs(38, 74).addBox(9.0F, 9.2F, 0.3F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F))
		.texOffs(48, 14).addBox(10.0F, 9.2F, 0.9F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F))
		.texOffs(52, 14).addBox(11.0F, 9.2F, 0.9F, 1.0F, 1.0F, 1.0F, new CubeDeformation(-0.1F))
		.texOffs(72, 20).addBox(10.0F, 9.1F, -1.9F, 2.0F, 1.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offset(-11.0F, -8.0F, 0.55F));

		left_leg.addOrReplaceChild("LeftThigh_r1", CubeListBuilder.create().texOffs(16, 45).addBox(-1.99F, 0.0F, -2.0F, 4.0F, 7.0F, 4.0F, new CubeDeformation(0.01F)), PartPose.offsetAndRotation(0.5F, -1.375F, -0.2F, -0.2182F, 0.0F, 0.0F));

		partdefinition.addOrReplaceChild("leaf4", CubeListBuilder.create(), PartPose.offsetAndRotation(0.0F, 11.3584F, 4.0263F, 1.3526F, 0.0F, -3.1416F));

		partdefinition.addOrReplaceChild("Tail2", CubeListBuilder.create(), PartPose.offsetAndRotation(0.0F, 7.3F, 1.0F, -1.7453F, 0.0F, -3.1416F));

		partdefinition.addOrReplaceChild("TailPrimary2", CubeListBuilder.create(), PartPose.offsetAndRotation(0.0F, 7.9392F, -7.8785F, -0.1309F, 0.0F, 0.0F));

		partdefinition.addOrReplaceChild("TailSecondary2", CubeListBuilder.create(), PartPose.offset(0.0F, 9.5032F, -11.3514F));

		partdefinition.addOrReplaceChild("TailTertiary2", CubeListBuilder.create(), PartPose.offset(0.0F, 10.2532F, -17.8514F));

		return LayerDefinition.create(meshdefinition, 128, 128);
	}

	@Override
	public ModelPart root() {
		return this.root;
	}

	@Override
	public void setupAnim(OraxolotlEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
			float netHeadYaw, float headPitch) {
		// 过渡起点在写入任何骨骼前由"本 tick 唯一状态键"决定；
		// 该键与 OraxolotlEntity.tickAnimations 的互斥调度同源，过渡期间也只会有一条主动画在写骨骼
		beginTransition(entity, entity.getAnimationKey(), ageInTicks);
		this.root.getAllParts().forEach(ModelPart::resetPose);
		// 主动画严格互斥：只应用当前状态对应的那一条，其余 AnimationState 已在 tickAnimations 中停止
		switch (entity.getAnimationKey()) {
			case "sleep" -> animate(entity.sleepAnimationState, OraxolotlAnimations.FERAL_SLEEP, ageInTicks, 1.0F);
			case "sit" -> animate(entity.sitAnimationState, OraxolotlAnimations.FERAL_SIT, ageInTicks, 1.0F);
			case "swim" -> animate(entity.swimAnimationState, OraxolotlAnimations.FERAL_SWIM, ageInTicks, 1.0F);
			case "attack" -> animate(entity.attackAnimationState, OraxolotlAnimations.FERAL_ATTACK, ageInTicks, 1.0F);
			case "dig" -> animate(entity.digAnimationState, OraxolotlAnimations.FERAL_DIG, ageInTicks, 1.0F);
			case "jump" -> animate(entity.jumpAnimationState, OraxolotlAnimations.FERAL_JUMP, ageInTicks, 1.0F);
			case "fall" -> animate(entity.fallAnimationState, OraxolotlAnimations.FERAL_FALL, ageInTicks, 1.0F);
			case "run" -> animate(entity.runAnimationState, OraxolotlAnimations.FERAL_RUN, ageInTicks, 1.0F);
			case "walk" -> animate(entity.walkAnimationState, OraxolotlAnimations.FERAL_WALK, ageInTicks, 1.0F);
			default -> animate(entity.idleAnimationState, OraxolotlAnimations.FERAL_IDLE, ageInTicks, 1.0F);
		}
		// 尾巴作为允许叠加的附属层单独驱动（只写尾骨，不与主动画骨骼冲突）
		animate(entity.tailWalkAnimationState, OraxolotlAnimations.TAIL_WALK, ageInTicks, 1.0F);
		animate(entity.tailIdleAnimationState, OraxolotlAnimations.TAIL_IDLE, ageInTicks, 1.0F);
		// 站立/行走时头部跟随视线；坐、睡、游泳等全身姿势动画里头部已固定，不再叠加
		if (!entity.isSitting() && !entity.isInWater() && !entity.isSleepingPose()) {
			this.head.yRot += netHeadYaw * Mth.DEG_TO_RAD;
			this.head.xRot += headPitch * Mth.DEG_TO_RAD;
		}
		finishTransition(entity, ageInTicks);
	}

	/**
	 * 状态切换过渡（参照 MurmolMod 的做法）：主状态键变化时，把该主体上一帧的最终姿态冻结为过渡起点，
	 * 之后若干 tick 内把新状态姿态从中插值出来，避免硬切。
	 */
	private void beginTransition(Object owner, String key, float ageInTicks) {
		Transition transition = this.transitions.computeIfAbsent(owner, ignored -> new Transition());
		if (key.equals(transition.key))
			return;
		if (transition.lastPose != null) {
			transition.fromPose = transition.lastPose.clone();
			transition.oneShot = isOneShot(key) || isOneShot(transition.key);
			transition.startAge = ageInTicks;
		}
		transition.key = key;
	}

	/** 按过渡进度插值本帧姿态，并记录最终姿态作为下次切换的起点 */
	private void finishTransition(Object owner, float ageInTicks) {
		Transition transition = this.transitions.get(owner);
		if (transition == null)
			return;
		if (transition.fromPose != null) {
			float ticks = transition.oneShot ? ONE_SHOT_TRANSITION_TICKS : TRANSITION_TICKS;
			float alpha = Mth.clamp((ageInTicks - transition.startAge) / ticks, 0.0F, 1.0F);
			if (alpha >= 1.0F) {
				transition.fromPose = null;
			} else {
				blendFrom(transition.fromPose, alpha);
			}
		}
		if (transition.lastPose == null)
			transition.lastPose = new float[this.transitionParts.size() * 6];
		capturePose(transition.lastPose);
	}

	private void capturePose(float[] pose) {
		int i = 0;
		for (ModelPart part : this.transitionParts) {
			pose[i++] = part.x;
			pose[i++] = part.y;
			pose[i++] = part.z;
			pose[i++] = part.xRot;
			pose[i++] = part.yRot;
			pose[i++] = part.zRot;
		}
	}

	private void blendFrom(float[] from, float alpha) {
		int i = 0;
		for (ModelPart part : this.transitionParts) {
			part.x = Mth.lerp(alpha, from[i++], part.x);
			part.y = Mth.lerp(alpha, from[i++], part.y);
			part.z = Mth.lerp(alpha, from[i++], part.z);
			part.xRot = Mth.lerp(alpha, from[i++], part.xRot);
			part.yRot = Mth.lerp(alpha, from[i++], part.yRot);
			part.zRot = Mth.lerp(alpha, from[i++], part.zRot);
		}
	}

	/** 一次性动作使用更短的过渡时长 */
	private static boolean isOneShot(String key) {
		return "attack".equals(key) || "dig".equals(key) || "jump".equals(key) || "fall".equals(key);
	}

	/** 单个渲染主体的过渡状态 */
	private static final class Transition {
		private String key;
		private float[] lastPose;
		private float[] fromPose;
		private float startAge;
		private boolean oneShot;
	}

	@Override
	public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay,
			int color) {
		this.root.render(poseStack, buffer, packedLight, packedOverlay, color);
	}
}
