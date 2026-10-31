package mcr.riichi_arise.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashSet;
import java.util.Set;

import mcr.riichi_arise.MahjongParticleTypes;
import mcr.riichi_arise.MahjongItems;
import mcr.riichi_arise.MahjongTileItem;
import mcr.riichi_arise.block.FengPanBlock;

/**
 * 麻雀内容客户端注册：为麻雀牌物品注册 "code" 物品属性，
 * 配合模型 overrides（assets/riichi_arise/models/item/mahjong_tile.json）实现按 NBT 牌面切换模型。
 * 注意：无组件时必须返回 -1（不匹配任何 override，显示背面），不能钳到 0（0 是红五万）。
 * 另：手持风盘时用 astral_burst 粒子绕桌心围圈标记桌位（类原版屏障的标记显示）。
 */
@EventBusSubscriber(Dist.CLIENT)
public class MahjongClient {
	/** 客户端 tick 计数（粒子圈节流用） */
	private static int tickCounter;
	/** 粒子圈扫描半径（格，水平） */
	private static final int SCAN_RADIUS = 10;
	/** 每张桌子的粒子圈点数 */
	private static final int CIRCLE_POINTS = 12;
	/** 粒子圈半径（格） */
	private static final double CIRCLE_RADIUS = 0.5;
	/** 报番语音资源包下载/说明页（进游戏时缺包提示里给出的可点击链接） */
	private static final String FAN_VOICE_PACK_URL = "https://github.com/your-name/richi_arise/releases";
	/** 本次进服是否已检查（只在进游戏时提示一次，断开重进重新检查） */
	private static boolean fanPackChecked;

	@SubscribeEvent
	public static void onClientSetup(FMLClientSetupEvent event) {
		event.enqueueWork(() -> {
			ItemProperties.register(
					MahjongItems.MAHJONG_TILE.get(),
					ResourceLocation.withDefaultNamespace("code"),
					(stack, level, entity, seed) -> MahjongTileItem.getCode(stack));
			// 玻璃之间：透明牌标记（模型 overrides 据此选择半透明牌体）
			ItemProperties.register(
					MahjongItems.MAHJONG_TILE.get(),
					ResourceLocation.withDefaultNamespace("glass"),
					(stack, level, entity, seed) -> Boolean.TRUE.equals(
							stack.get(mcr.riichi_arise.MahjongComponents.TILE_GLASS.get())) ? 1f : 0f);
			// 玻璃之间：玻璃牌背标记（隐藏手牌/牌山/飞牌渲染为玻璃质感牌背）
			ItemProperties.register(
					MahjongItems.MAHJONG_TILE.get(),
					ResourceLocation.withDefaultNamespace("glass_back"),
					(stack, level, entity, seed) -> Boolean.TRUE.equals(
							stack.get(mcr.riichi_arise.MahjongComponents.TILE_GLASS_BACK.get())) ? 1f : 0f);
			// 内置 mp3 播放器预热：后台初始化音频子系统并加载 jlayer，消除首次角色语音的播放延迟
			mcr.riichi_arise.voice.VoicePlayer.warmup();
		});
	}

	/** 玻璃之间的玻璃牌背：按物品堆的牌面切换独立模型（见 MahjongTableClient.GlassBackBakedModel） */
	@SubscribeEvent
	public static void onModifyBakedModel(net.neoforged.neoforge.client.event.ModelEvent.ModifyBakingResult event) {
		MahjongTableClient.wrapTileModel(event);
	}

	@SubscribeEvent
	public static void onRegisterGuiLayers(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
		// Tab 玩家列表打开时，屏幕左侧的对局信息叠加层（四家点数排名 / 局目与牌山量 / 场供）
		event.registerAboveAll(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
				mcr.riichi_arise.RichiiAriseMod.MODID, "table_overlay"),
				(guiGraphics, partialTick) -> MahjongTableOverlay.render(guiGraphics));
	}

	/**
	 * 进游戏时提示：未安装报番语音资源包则发一条可点击的聊天提示（含 GitHub 链接）。
	 * 报番语音完全由资源包提供，缺包时结算只按固定节奏显示役种、没有念白。
	 */
	private static void noticeMissingFanVoicePack(LocalPlayer player) {
		if (mcr.riichi_arise.voice.VoiceCache.hasFanVoicePack())
			return;
		net.minecraft.network.chat.Component link = net.minecraft.network.chat.Component
				.literal(FAN_VOICE_PACK_URL)
				.withStyle(s -> s.withColor(net.minecraft.ChatFormatting.AQUA)
						.withUnderlined(true)
						.withClickEvent(new net.minecraft.network.chat.ClickEvent(
								net.minecraft.network.chat.ClickEvent.Action.OPEN_URL, FAN_VOICE_PACK_URL))
						.withHoverEvent(new net.minecraft.network.chat.HoverEvent(
								net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
								net.minecraft.network.chat.Component.literal("点击打开下载页面"))));
		player.displayClientMessage(
				net.minecraft.network.chat.Component.translatable("message.riichi_arise.fan_voice_missing")
						.append(link),
				false);
	}

	/** 平放麻将牌方块实体渲染器（按牌面代码渲染躺平的麻将牌） */
	@SubscribeEvent
	public static void onRegisterRenderers(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
		event.registerBlockEntityRenderer(mcr.riichi_arise.MahjongBlockEntities.MAHJONG_TILE.get(),
				MahjongTileBlockRenderer::new);
	}

	/** 手持风盘（大/小型）时，扫描附近风盘并在每个桌面中心外围生成 astral_burst 粒子圈 */
	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		tickCounter++;
		MahjongTableClient.tick(); // 牌桌发牌/倒牌动画调度（逐 tick 驱动）
		MahjongSettlementScreen.tickDeferredOpen(); // 和牌语音播完后再弹结算界面
		MahjongPointDeltaScreen.tickWaiting(); // 点数变化界面"等待其他玩家"省略号动画与确认重发
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) {
			fanPackChecked = false; // 离开世界/未进游戏：下次进入时重新检查
			return;
		}
		if (!fanPackChecked) {
			fanPackChecked = true;
			// 资源包异步加载：进入世界后下一 tick 再判断，避免刚进服时资源管理器还没就绪
			minecraft.execute(() -> noticeMissingFanVoicePack(player));
		}
		WinEffectClient.tick(); // 推进和牌特效时间线
		// 每 4 tick 刷新一轮粒子圈（粒子本身有寿命，持续补充保持可见）
		if (tickCounter % 4 != 0)
			return;
		boolean holding = player.getMainHandItem().is(MahjongItems.FENG_PAN.get().asItem())
				|| player.getOffhandItem().is(MahjongItems.FENG_PAN.get().asItem())
				|| player.getMainHandItem().is(MahjongItems.FENG_PAN_SMALL.get().asItem())
				|| player.getOffhandItem().is(MahjongItems.FENG_PAN_SMALL.get().asItem());
		if (!holding)
			return;
		BlockPos base = player.blockPosition();
		Set<BlockPos> seenOrigins = new HashSet<>();
		for (BlockPos pos : BlockPos.betweenClosed(
				base.offset(-SCAN_RADIUS, -2, -SCAN_RADIUS), base.offset(SCAN_RADIUS, 2, SCAN_RADIUS))) {
			if (!(level.getBlockState(pos).getBlock() instanceof FengPanBlock))
				continue;
			// 同一张桌子由 4 个方块拼成，按原点去重，只画一圈
			BlockPos origin = FengPanBlock.getOrigin(level.getBlockState(pos), pos);
			if (!seenOrigins.add(origin))
				continue;
			Vec3 center = FengPanBlock.getTableCenter(level.getBlockState(pos), pos);
			for (int i = 0; i < CIRCLE_POINTS; i++) {
				double angle = Math.PI * 2 * i / CIRCLE_POINTS;
				level.addParticle(MahjongParticleTypes.ASTRAL_BURST.get(),
						center.x + Math.cos(angle) * CIRCLE_RADIUS,
						center.y,
						center.z + Math.sin(angle) * CIRCLE_RADIUS,
						0, 0, 0);
			}
		}
	}
}
