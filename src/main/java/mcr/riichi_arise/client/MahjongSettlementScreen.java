package mcr.riichi_arise.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import mcr.riichi_arise.RichiConfig;
import mcr.riichi_arise.game.RichiTableState;
import mcr.riichi_arise.network.MahjongSettlementPayload;

/**
 * 结算界面（S→C 推送后自动展示）：
 * <ul>
 * <li>和牌结算：和牌者 + 荣和/自摸 + 役种翻符，手牌与副露直接 blit 牌面贴图（TileIcons），
 * 和牌张抬高并高亮。</li>
 * <li>终局结算：四家最终点数排名（高→低），冠军标注。</li>
 * </ul>
 * 两种展示方式（客户端设置「结算界面停靠左侧」决定）：
 * <ul>
 * <li><b>GUI</b>：屏幕中央的 {@link Screen}，带确认按钮，打开期间接管输入。</li>
 * <li><b>叠加层</b>：屏幕左侧的 HUD 面板（{@link #renderOverlay}），不打开任何 Screen，
 * 不拦截鼠标/键盘，玩家可继续操作游戏；播报结束后按倒计时自动确认。</li>
 * </ul>
 * 状态字段为静态：同一时刻只展示一份结算，GUI 与叠加层共用同一套计时与播报逻辑。
 */
public class MahjongSettlementScreen extends Screen {
	/** 牌面绘制步长 = 贴图宽（缩放后 14px 宽 × 18px 高），牌与牌紧贴 */
	private static final int TILE_STEP = 14;

	// ---- 共享展示状态（GUI 与叠加层共用）----
	private static MahjongSettlementPayload.Settlement data;
	private static int age;
	/** 已播报条数：每新出现一条役种播放一次木板放置音效 */
	private static int announced;
	/** 番数段位音效是否已播（番数在役种播报完后才显示，此刻按段位播一次） */
	private static boolean tierPlayed;
	/** 是否已发出 settle_confirm（倒计时归零自动确认 / 手动点"确定"，只发一次） */
	private static boolean confirmSent;
	/** 报番语音模式下的播报结束时刻（最后一次 FanAnnouncer.done 时记录） */
	private static Integer voiceEndAge;
	/** 点数等级语音是否已触发（点数显现的同一刻播一次） */
	private static boolean levelPlayed;
	/** 是否以 HUD 叠加层形式展示（true = 不打开 Screen，仅左侧面板） */
	private static boolean overlayActive;

	/** "确定"按钮（仅 GUI 模式）：报番+番符/点数/段位全部显现前隐藏，防止播报进行中误点跳过 */
	private Button confirmButton;

	private MahjongSettlementScreen() {
		super(Component.translatable("gui.riichi_arise.settlement.title"));
	}

	/** 待打开的结算界面（等和牌语音播完再弹） */
	private static boolean pendingOpen;

	/** 复位本次展示状态（GUI 与叠加层共用） */
	private static void resetState() {
		data = MahjongSettlementPayload.last;
		age = 0;
		announced = 0;
		tierPlayed = false;
		confirmSent = false;
		voiceEndAge = null;
		levelPlayed = false;
	}

	public static void open() {
		pendingOpen = false;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null)
			return;
		resetState();
		// 客户端设置：结算界面停靠左侧 → 一律走 HUD 叠加层，不接管输入、不影响玩家操作
		if (RichiConfig.SETTLEMENT_OVERLAY.get()) {
			overlayActive = true;
			return;
		}
		overlayActive = false;
		mc.setScreen(new MahjongSettlementScreen());
	}

	/** 收到结算包：和牌语音（荣/自摸，多家荣和会并行播）播完后才展示界面 */
	public static void openWhenVoiceDone() {
		if (mcr.riichi_arise.voice.VoicePlayer.isBusy()) {
			pendingOpen = true;
			return;
		}
		open();
	}

	/** 客户端 tick：和牌语音播完后补开结算界面 */
	public static void tickDeferredOpen() {
		if (pendingOpen && !mcr.riichi_arise.voice.VoicePlayer.isBusy())
			open();
	}

	/** 阶段切换（进入点数变化界面）：取消还在等语音的结算界面，避免它稍后又弹回来 */
	public static void discardPendingOpen() {
		pendingOpen = false;
		mcr.riichi_arise.voice.FanAnnouncer.cancel(); // 报番到此为止，残留语音不要带到下一局
		if (overlayActive)
			closeOverlay();
	}

	/** 服务端推进下一局：关闭已打开的结算界面（GUI 与叠加层两种形态都清掉） */
	public static void closeIfOpen() {
		pendingOpen = false;
		mcr.riichi_arise.voice.FanAnnouncer.cancel(); // 清掉没播完的报番/段位语音，避免下一局先响
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen instanceof MahjongSettlementScreen)
			mc.setScreen(null);
		overlayActive = false;
		data = null;
	}

	/** 叠加层形态关闭（不清界面栈，只清展示状态并回传确认） */
	private static void closeOverlay() {
		sendConfirmIfNeeded();
		mcr.riichi_arise.voice.FanAnnouncer.cancel();
		overlayActive = false;
		data = null;
		age = 0;
		voiceEndAge = null;
	}

	/** 结束本次展示（GUI = 关闭界面；叠加层 = 清掉面板）——由计时兜底调用 */
	private static void exitDisplay() {
		if (overlayActive) {
			closeOverlay();
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen instanceof MahjongSettlementScreen s)
			s.onClose();
	}

	@Override
	protected void init() {
		resetState();
		overlayActive = false;
		// 单一"确定"按钮：回传确认（服务端全员确认后推进）并关闭界面；关闭界面等同确认。
		// 初始隐藏，等全部播报完（番符/点数/段位显现的 tierAt 时刻）后才由 tick 显示，
		// 避免役种报番 / 段位播报进行中误点跳过。
		this.confirmButton = addRenderableWidget(Button.builder(
				Component.translatable("gui.riichi_arise.settlement.confirm"), b -> onClose())
				.bounds(this.width / 2 - 40, Math.min(this.height - 26, this.height / 2 + 78), 80, 20).build());
		this.confirmButton.visible = false;
	}

	@Override
	public void onClose() {
		// 关闭界面（含 ESC / 确定按钮）= 确认，否则服务端要等满兜底时限才推进；
		// 同时停掉没播完的报番语音，避免残留在下一局番名之前先响
		sendConfirmIfNeeded();
		mcr.riichi_arise.voice.FanAnnouncer.cancel();
		super.onClose();
		// 不在这里自行打开点数变化界面：多人荣和要先看完各家结算，
		// 服务端在队列全部展示完后统一推送点数结算（MahjongPointDeltaPayload.MODE_SHOW）
	}

	/** 回传 settle_confirm（同一局只发一次） */
	private static void sendConfirmIfNeeded() {
		if (confirmSent || data == null || data.mode() != MahjongSettlementPayload.MODE_HAND_WIN)
			return;
		confirmSent = true;
		net.neoforged.neoforge.network.PacketDistributor.sendToServer(
				new mcr.riichi_arise.network.MahjongGamePayloads.ActionMessage(
						data.originPacked(), "settle_confirm", ""));
	}

	/** 叠加层每 tick（MahjongClient 转发）：驱动与 GUI 完全相同的计时/播报/自动确认 */
	public static void tickOverlay() {
		if (!overlayActive)
			return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			overlayActive = false;
			data = null;
			return;
		}
		tickState();
	}

	@Override
	public void tick() {
		tickState();
		// 全部播报完（番符/点数/段位显现的 tierAt 时刻）后才显示"确定"按钮；
		// 终局结算无报番，直接显示（不必等固定节奏）
		if (this.confirmButton == null || data == null)
			return;
		int tierAt = tierAtNow();
		boolean timingReady = tierAt < Integer.MAX_VALUE - 1000;
		boolean finalMode = data.mode() == MahjongSettlementPayload.MODE_FINAL;
		if (finalMode || (timingReady && age >= tierAt))
			this.confirmButton.visible = true;
	}

	/** 展示计时主体（GUI 与叠加层共用）：报番推进、分段显现、自动确认与兜底收尾 */
	private static void tickState() {
		// 报番延迟 1s 开始：界面弹出满延迟才入队语音（幂等）；之后文字随各条语音开始同时显现
		if (age >= RichiTableState.SETTLE_ANNOUNCE_DELAY)
			mcr.riichi_arise.voice.FanAnnouncer.start();
		age++;
		// 报番结束时刻（渲染与自动确认共用）：语音播完或超时后记录
		if (mcr.riichi_arise.voice.FanAnnouncer.isActive() && voiceEndAge == null
				&& (mcr.riichi_arise.voice.FanAnnouncer.isDone() || age > announceFallbackEnd()))
			voiceEndAge = age;
		int tierAt = tierAtNow();
		boolean timingReady = tierAt < Integer.MAX_VALUE - 1000;
		// 倒计时归零 → 自动回确认（与"确定"按钮同一条包），保证界面上的倒计时就是真实推进时刻；
		// 服务端在全员确认（或兜底时限）后推进到点数变化界面
		if (timingReady && age >= tierAt + RichiTableState.SETTLE_CONFIRM_TICKS)
			sendConfirmIfNeeded();
		// 兜底关闭：确认后服务端迟迟未推进（掉线等异常）才自行关闭
		if (timingReady
				&& age >= tierAt + RichiTableState.SETTLE_CONFIRM_TICKS + RichiTableState.SETTLE_FALLBACK_TICKS)
			exitDisplay();
		// 无报番语音时的固定节奏音效：每新出现一条役种播放一次木板放置声（可见条数由 age 推导，与渲染一致）；
		// 有报番语音时由 FanAnnouncer 在每条语音开始时播打点音，这里不再另响
		if (!mcr.riichi_arise.voice.FanAnnouncer.isActive()
				&& data != null && !data.yakuList().isBlank()) {
			int n = data.yakuList().split(";").length;
			int visible = 0;
			for (int i = 0; i < n; i++)
				if (age >= RichiTableState.SETTLE_ANNOUNCE_DELAY + 15 + RichiTableState.SETTLE_ANNOUNCE_STEP * i)
					visible++;
			if (visible > announced) {
				announced = visible;
				Minecraft.getInstance().getSoundManager().play(
						net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
								net.minecraft.sounds.SoundEvents.WOOD_PLACE, 1.0F));
			}
		}
		// 段位显现时刻（番符 +1s、点数 +0.5s 后）：按打点段位播一次音效——役满=不死图腾、三倍满=信标、
		// 倍满=铁砧放置、跳满=喷溅药水迸发、满贯（含流局满贯）=粘液块放置、满贯以下无
		if (!tierPlayed && timingReady && data != null
				&& data.mode() == MahjongSettlementPayload.MODE_HAND_WIN
				&& age >= tierAt) {
			tierPlayed = true;
			var sound = tierSound();
			if (sound != null)
				Minecraft.getInstance().getSoundManager().play(
						net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(sound, 1.0F));
		}
		// 段位语音（满贯/跳满/倍满/三倍满/累计役满/N倍役满）：音效播完 0.5s 后再念，避免与音效重叠
		if (!levelPlayed && timingReady && data != null
				&& data.mode() == MahjongSettlementPayload.MODE_HAND_WIN
				&& age >= tierAt + 10) {
			levelPlayed = true;
			mcr.riichi_arise.voice.FanAnnouncer.playLevel(tierVoiceKey());
		}
	}

	/** 单条报番语音的保守时长（刻，2s）：役名与宝牌语音长短差异大，按上限留足，避免提前判"报番结束" */
	private static final int SETTLE_VOICE_TICKS = RichiTableState.SETTLE_VOICE_TICKS;
	/** 报番语音收尾余量（刻）：最后一条播完到判定结束的缓冲 */
	private static final int SETTLE_VOICE_TAIL = 20;

	/** 报番固定节奏的结束时刻（无报番语音 / 语音超时兜底） */
	private static int announceFallbackEnd() {
		// 有语音报番时按实际入队条数估算：宝牌/里宝牌语音排在最后，若沿用"列表行数 × 0.5s"的固定节奏估算，
		// 结束时刻会早于真实语音长度，界面自动确认关闭时把还没播的宝牌/里宝牌语音一并清掉（表现为宝牌漏报）
		if (mcr.riichi_arise.voice.FanAnnouncer.isActive()) {
			int items = Math.max(1, mcr.riichi_arise.voice.FanAnnouncer.total());
			return RichiTableState.SETTLE_ANNOUNCE_DELAY + 15
					+ SETTLE_VOICE_TICKS * items + SETTLE_VOICE_TAIL;
		}
		int n = data == null || data.yakuList().isBlank() ? 0
				: data.yakuList().split(";").length;
		return RichiTableState.SETTLE_ANNOUNCE_DELAY + 15
				+ RichiTableState.SETTLE_ANNOUNCE_STEP * (n - 1) + 20;
	}

	/** 报番结束时刻：语音播完（或超时）后 +1s；未结束时返回哨兵值 */
	private static int announceEndNow() {
		if (!mcr.riichi_arise.voice.FanAnnouncer.isActive())
			return announceEndTick();
		return voiceEndAge != null ? voiceEndAge + 20 : Integer.MAX_VALUE - 1;
	}

	/** 段位显现时刻（= 确认倒计时起点）；报番尚未结束时返回哨兵值 */
	private static int tierAtNow() {
		int end = announceEndNow();
		return end >= Integer.MAX_VALUE - 1000 ? Integer.MAX_VALUE : end + RichiTableState.SETTLE_TIER_OFFSET;
	}

	/** 打点段位等级：0=满贯 1=跳满 2=倍满 3=三倍满 4=役满；-1=满贯以下。
	 *  按翻/符判定而非实际收入点数——三麻自摸有自摸损，收入点数会比同等打点低一档。 */
	private static int tierRank() {
		if (data == null)
			return -1;
		if (data.fu() == 0)
			return 4; // 真役满（符=0；han 为倍数）
		if ("nagashi".equals(data.winType()))
			return 0; // 流局满贯
		int han = data.han();
		if (han >= 13)
			return 4; // 累计役满
		if (han >= 11)
			return 3;
		if (han >= 8)
			return 2;
		if (han >= 6)
			return 1;
		if (han >= 5)
			return 0;
		// 3~4 翻按基本点判定切上满贯（4 翻 40 符、3 翻 70 符 = 2000 基本点）
		int base = data.fu() << (2 + Math.max(0, han));
		return base >= 2000 ? 0 : -1;
	}

	/** 打点段位音效（按 tierRank 映射） */
	private static net.minecraft.sounds.SoundEvent tierSound() {
		return switch (tierRank()) {
			case 4 -> net.minecraft.sounds.SoundEvents.TOTEM_USE; // 役满
			case 3 -> net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE; // 三倍满
			case 2 -> net.minecraft.sounds.SoundEvents.ANVIL_PLACE; // 倍满
			case 1 -> net.minecraft.sounds.SoundEvents.SPLASH_POTION_BREAK; // 跳满
			case 0 -> net.minecraft.sounds.SoundEvents.SLIME_BLOCK_PLACE; // 满贯（含流局满贯）
			default -> null;
		};
	}

	/** 打点段位名（役种播报完显示于番符行；满贯以下返回 null 保持番符点数格式） */
	private static String tierLabel() {
		switch (tierRank()) {
			case 4:
				if (data.fu() == 0) {
					// 真役满（han 编码倍数）：1 倍"役满"，n 倍"n倍役满"
					int count = Math.max(1, data.han());
					return count > 1
							? Component.translatable("gui.riichi_arise.settlement.yakuman_n", count).getString()
							: Component.translatable("gui.riichi_arise.settlement.yakuman").getString();
				}
				// 累计役满（13 翻以上、符非 0）：段位名单独显示，不与真役满混称"役满"
				return Component.translatable("gui.riichi_arise.settlement.leijiyiman").getString();
			case 3:
				return Component.translatable("gui.riichi_arise.settlement.sanbaiman").getString();
			case 2:
				return Component.translatable("gui.riichi_arise.settlement.baiman").getString();
			case 1:
				return Component.translatable("gui.riichi_arise.settlement.haneman").getString();
			case 0:
				return Component.translatable("gui.riichi_arise.settlement.mangan").getString();
			default:
				return null;
		}
	}

	/** 点数等级播报键（与实际打点段位一致；未达满贯返回 null） */
	private static String tierVoiceKey() {
		if (data == null || "nagashi".equals(data.winType()))
			return null; // 流局满贯无役种报番，不播等级语音
		if (tierRank() == 4 && data.fu() == 0) {
			// 真役满（han 编码倍数）：N 倍役满语音（6 倍以上用 6 倍音频）
			int count = Math.max(1, data.han());
			return "yiman" + Math.min(6, count);
		}
		return switch (tierRank()) {
			case 4 -> "leijiyiman"; // 累计役满（13 翻以上、符非 0）
			case 3 -> "sanbeiman";
			case 2 -> "beiman";
			case 1 -> "tiaoman";
			case 0 -> "mangan";
			default -> null;
		};
	}

	/** 役种逐条播报结束时刻（弹出后延迟 1s 开始，固定节奏 15tick 起、每条间隔 SETTLE_ANNOUNCE_STEP、尾条停留 1s） */
	private static int announceEndTick() {
		int n = data == null || data.yakuList().isBlank() ? 0
				: data.yakuList().split(";").length;
		return n == 0 ? RichiTableState.SETTLE_ANNOUNCE_DELAY + 20
				: RichiTableState.SETTLE_ANNOUNCE_DELAY + 15
						+ RichiTableState.SETTLE_ANNOUNCE_STEP * (n - 1) + 20;
	}

	private static List<Integer> parseCsv(String csv) {
		List<Integer> codes = new ArrayList<>();
		for (String p : csv.split(","))
			if (!p.isBlank())
				try {
					codes.add(Integer.parseInt(p.trim()));
				} catch (NumberFormatException ignored) {
				}
		return codes;
	}

	private static List<List<Integer>> meldGroups() {
		List<List<Integer>> groups = new ArrayList<>();
		if (data == null)
			return groups;
		for (String grp : data.meldsGroups().split(";")) {
			if (grp.isBlank())
				continue;
			groups.add(parseCsv(grp));
		}
		return groups;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick); // 确认按钮
		renderPanel(g, this.width, this.height, this.font, false);
	}

	/** HUD 叠加层渲染（MahjongClient 的 RegisterGuiLayersEvent 转发）：左侧面板，不接管输入 */
	public static void renderOverlay(GuiGraphics g) {
		if (!overlayActive)
			return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			overlayActive = false;
			data = null;
			return;
		}
		renderPanel(g, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), mc.font, true);
	}

	/**
	 * 面板绘制主体（GUI 与叠加层共用）。
	 *
	 * @param width   可用屏幕宽（GUI = Screen 宽、叠加层 = 窗口 GUI 宽）
	 * @param height  可用屏幕高
	 * @param overlay true = 贴屏幕左缘停靠；false = 屏幕居中（按钮位置由 Screen 自己维护）
	 */
	private static void renderPanel(GuiGraphics g, int width, int height, Font font, boolean overlay) {
		if (data == null) {
			g.drawCenteredString(font, "...", width / 2, height / 2, 0xAAAAAA);
			return;
		}
		boolean win = data.mode() == MahjongSettlementPayload.MODE_HAND_WIN;
		List<Integer> doraTiles = win ? parseCsv(data.doraCsv()) : List.of();
		List<Integer> uraTiles = win ? parseCsv(data.uraCsv()) : List.of();
		List<String> yakus = win && !data.yakuList().isBlank()
				? List.of(data.yakuList().split(";"))
				: List.of();
		// 报番同步：有报番任务时役种随语音进度显现（每条语音一开始，对应条目同时出现+打点音）；
		// 语音播完或超时后回退固定节奏
		boolean fanActive = mcr.riichi_arise.voice.FanAnnouncer.isActive();
		boolean fanEnded = fanActive && voiceEndAge != null;
		int announceEnd = announceEndNow();
		// 报番结束后分三段显现：+5tick 番数符数 → +5tick 点数 → +5tick 段位名（满贯/跳满等）
		boolean pending = announceEnd >= Integer.MAX_VALUE - 1000; // 报番尚未结束（哨兵值），避免加法溢出
		int hanFuAt = pending ? Integer.MAX_VALUE : announceEnd + 5; // 番数符数
		int pointAt = pending ? Integer.MAX_VALUE : announceEnd + 10;
		int tierAt = pending ? Integer.MAX_VALUE : announceEnd + RichiTableState.SETTLE_TIER_OFFSET;
		// 役种网格：固定每列 4 个（排满一列再排下一列，≤4 条目单列）
		int cols = yakus.size() <= 4 ? 1 : (yakus.size() + 3) / 4;
		int yakuRows = yakus.isEmpty() ? 0 : Math.min(4, yakus.size());
		int COL_W = 78;
		int pad = 12;
		// 动态高度：标题+宝牌指示牌（表/里同一排）+手牌（与副露同一行）+役种网格+番符点数；终局 = 标题+4 行排名
		List<List<Integer>> melds = win ? meldGroups() : List.of();
		int meldTileCount = 0;
		for (List<Integer> m : melds)
			meldTileCount += m.size();
		final int MELD_GAP = 6; // 手牌与副露组、组与组之间的间隔
		int rowUnits = parseCsv(data.handCsv()).size() + meldTileCount;
		int naturalRow = rowUnits * TILE_STEP + melds.size() * MELD_GAP; // 手牌行自然宽度（等比缩放前）
		int indRows = win && (!doraTiles.isEmpty() || !uraTiles.isEmpty()) ? 1 : 0;
		int indW = (doraTiles.size() + uraTiles.size()) * TILE_STEP + 110;
		int seats = Math.max(1, Math.min(4, data.playerCount())); // 参战人数（三麻 3 / 四麻 4）
		int h = pad + 12 + (win ? 12 + (indRows * 32) + 22
				+ (yakuRows == 0 ? 0 : yakuRows * 13 + 4) + 14 : seats * 18 + 8) + pad;
		int w = Math.min(width - 8, win
				? Math.max(220, Math.max(naturalRow,
						yakus.size() > 1 ? cols * COL_W : 0) + pad * 2)
				: 180);
		if (win)
			w = Math.min(width - 8, Math.max(w, indW));
		int left = overlay ? 8 : width / 2 - w / 2; // 叠加层：贴屏幕左缘停靠
		int top = overlay ? Math.max(8, height / 2 - h / 2) : height / 2 - h / 2 - 8;
		g.fill(left, top, left + w, top + h, 0xE0000000);
		int border = 0xFFB8860B;
		g.fill(left, top, left + w, top + 1, border);
		g.fill(left, top + h - 1, left + w, top + h, border);
		g.fill(left, top, left + 1, top + h, border);
		g.fill(left + w - 1, top, left + w, top + h, border);

		int y = top + 6;
		// 标题 = 荣和/自摸/流局满贯 + 和牌者
		String title = Component.translatable(
				win ? "gui.riichi_arise.settlement." + data.winType() : "gui.riichi_arise.settlement.final").getString();
		String head = win ? title + "　" + data.winnerName() : title;
		g.drawCenteredString(font, head, left + w / 2, y, 0xFFFF55); // 面板内居中（居中布局 = 屏幕居中）
		// 倒计时在番符/点数/段位全部显现后（报番结束 +2s）才开始显示；归零时 tick 自动回确认
		if (age >= tierAt) {
			int remain = Math.max(0, (tierAt + RichiTableState.SETTLE_CONFIRM_TICKS - age + 19) / 20);
			String cd = Component.translatable("gui.riichi_arise.settlement.countdown", remain).getString();
			g.drawString(font, cd, left + w - pad - font.width(cd), top + 6, 0xFFAA55, false);
		}
		y += 14;

		if (win) {
			// 宝牌指示牌行（表宝牌，含里宝：立直和牌时展示）
			// 表宝牌指示牌与里宝牌同一排；各自小字标签画在牌面上方一排（0.75 倍字号）
			if (!doraTiles.isEmpty() || !uraTiles.isEmpty()) {
				int ty = y + 9;
				int tx = left + pad;
				if (!doraTiles.isEmpty()) {
					drawSmall(g, font, Component.translatable("gui.riichi_arise.settlement.dora_ind").getString(),
							tx, y, 0x7FDDFF);
					for (int code : doraTiles)
						TileIcons.draw(g, code, tx += TILE_STEP, ty, TILE_STEP);
					tx += TILE_STEP + 8;
				}
				if (!uraTiles.isEmpty()) {
					drawSmall(g, font, Component.translatable("gui.riichi_arise.settlement.ura").getString(),
							tx, y, 0xAA88FF);
					for (int code : uraTiles)
						TileIcons.draw(g, code, tx += TILE_STEP, ty, TILE_STEP);
				}
				y += 32;
			}
			// 手牌 + 副露同一行：放不下时整行等比缩小（牌宽 step 缩小，高按 4:3 跟随）
			List<Integer> hand = parseCsv(data.handCsv());
			int step = Math.min(TILE_STEP, (w - pad * 2 - melds.size() * MELD_GAP) / Math.max(1, rowUnits));
			if (step < 6)
				step = 6;
			int tx = left + pad;
			for (int i = 0; i < hand.size(); i++) {
				int code = hand.get(i);
				boolean isWinTile = code == data.winTile() && i == hand.size() - 1;
				int ty = y - (isWinTile ? 3 : 0);
				if (isWinTile)
					g.fill(tx - 1, ty - 1, tx + step + 1, ty + step * 4 / 3 + 1, 0xFFB8860B);
				TileIcons.draw(g, code, tx, ty, step);
				tx += step;
			}
			// 副露组接在手牌右侧（同行，组间隔 MELD_GAP；组内逐张推进）
			for (List<Integer> group : melds) {
				tx += MELD_GAP;
				for (int code : group) {
					TileIcons.draw(g, code, tx, y, step);
					tx += step;
				}
			}
			y += 22;
			// 役种逐条播报：报番模式下随语音进度显现（念完一条显现下一条）；
			// 无报番时按固定节奏逐条显现（弹出 1s 后开始，每条间隔 ANNOUNCE_STEP = 10 tick）
			if (!yakus.isEmpty()) {
				y += 2;
				int shown = Math.min(yakus.size(), cols * 4);
				int limit = -1; // 已显现的最大条目下标
				if (fanActive) {
					if (fanEnded) {
						limit = yakus.size() - 1;
					} else {
						// 已开始播报的役种按中文名对应到列表下标（其之前的未播报条目一并显现）
						for (String zh : mcr.riichi_arise.voice.FanAnnouncer.startedZh()) {
							int idx = yakus.indexOf(zh);
							if (idx >= 0)
								limit = Math.max(limit, idx);
						}
						// 宝牌/红宝牌/里宝牌条目排在列表末尾，随对应宝牌语音逐条显现；
						// 否则要等全部播报结束才一起出现，看起来像"宝牌都念完了字才出来"
						// 影牌（宝牌双刃）没有对应语音且排在最后：不参与这段映射，否则映射基准会整体后移一位，
						// 导致第一条宝牌语音刚响就把宝牌/红宝牌/里宝牌/影牌一次性全弹出来（音画不同步）；
						// 影牌等最后一条宝牌语音播完（报番结束）才显现，与它的语音节奏对齐
						int voicedTail = yakus.size();
						if (voicedTail > 0 && yakus.get(voicedTail - 1).startsWith("影牌"))
							voicedTail--;
						int extraTotal = Math.min(mcr.riichi_arise.voice.FanAnnouncer.extrasTotal(), voicedTail);
						int extraStarted = mcr.riichi_arise.voice.FanAnnouncer.startedExtras();
						if (extraTotal > 0 && extraStarted > 0)
							limit = Math.max(limit,
									Math.min(voicedTail - 1, voicedTail - extraTotal + extraStarted - 1));
					}
				} else {
					for (int i = 0; i < shown; i++)
						if (age >= RichiTableState.SETTLE_ANNOUNCE_DELAY + 15
								+ RichiTableState.SETTLE_ANNOUNCE_STEP * i)
							limit = i;
				}
				// 列优先且每列固定 4 个：col = i/4, row = i%4（播报顺序与排布一致）
				// 役满：役种逐字彩色渐变（顺序色相），便于与普通役区分；累计役满（符≠0）不变色
				boolean yakumanHand = tierRank() == 4 && data.fu() == 0;
				for (int i = 0; i < shown; i++) {
					if (i > limit)
						break;
					int col = i / 4;
					int row = i % 4;
					if (yakumanHand)
						drawRainbow(g, font, yakus.get(i), left + pad + col * COL_W, y + row * 13, i * 4);
					else {
						// 宝牌双刃：影牌条目红色显示（负番）
						int color = yakus.get(i).startsWith("影牌") ? 0xFF5555 : 0x7FFF7F;
						g.drawString(font, yakus.get(i), left + pad + col * COL_W, y + row * 13, color, true);
					}
				}
				if (shown < yakus.size() && limit >= shown - 1)
					g.drawString(font, "+" + (yakus.size() - shown), left + pad, y + yakuRows * 13, 0xAAAAAA, true);
				y += yakuRows * 13 + 4;
			}
			// 底部两段式显现：报番结束 → +5tick 番数符数 → +5tick 点数；段位名单独画在屏幕右下角（大字橙色）
			boolean nagashi = "nagashi".equals(data.winType());
			String tier = nagashi ? null : tierLabel();
			// 两段按最终宽度一次性排好位置，逐段到时才绘制：后出现的段落不会把已显现的文字挤开。
			// 整行贴在窗口下半部分（窗口底边往上留 24px）。
			String hanFuText = (!nagashi && data.fu() != 0)
					? Component.translatable("gui.riichi_arise.settlement.han_fu",
							data.han(), data.fu()).getString()
					: null;
			String ptsText = Component.translatable("gui.riichi_arise.settlement.pts_only", data.gain()).getString();
			List<String> parts = new ArrayList<>(2);
			List<Integer> showAt = new ArrayList<>(2);
			if (hanFuText != null) {
				parts.add(hanFuText);
				showAt.add(hanFuAt);
			}
			parts.add(ptsText);
			showAt.add(pointAt);
			int sepW = font.width("　");
			int totalW = sepW * (parts.size() - 1);
			for (String s : parts)
				totalW += font.width(s);
			int px = left + w / 2 - totalW / 2; // 面板内居中（居中布局 = 屏幕居中）
			int infoY = top + h - 24;
			for (int i = 0; i < parts.size(); i++) {
				if (age >= showAt.get(i))
					g.drawString(font, parts.get(i), px, infoY, 0xFFFF55, true);
				px += font.width(parts.get(i)) + sepW;
			}
			// 打点段位（满贯/跳满/倍满/三倍满/役满）：两倍字号、橙色，紧贴结算界面右下角
			if (tier != null && age >= tierAt) {
				float scale = 2.0f;
				int tw = (int) (font.width(tier) * scale);
				int th = (int) (font.lineHeight * scale);
				var pose = g.pose();
				pose.pushPose();
				pose.translate(left + w - tw - 6, top + h - th - 6, 0);
					pose.scale(scale, scale, 1.0f);
				g.drawString(font, tier, 0, 0, 0xFFFF8C00, true);
				pose.popPose();
			}
		} else {
			// 终局排名：按点数高→低（只列本局参战人数，三麻不显示第 4 人）
			Integer[] order = new Integer[seats];
			for (int i = 0; i < seats; i++)
				order[i] = i;
			java.util.Arrays.sort(order, (a, b) -> Integer.compare(data.points()[b], data.points()[a]));
			for (int rank = 0; rank < seats; rank++) {
				int seat = order[rank];
				String line = (rank + 1) + ". " + data.names()[seat]
						+ "　" + data.points()[seat]
						+ Component.translatable("gui.riichi_arise.settlement.pt").getString();
				int color = rank == 0 ? 0xFFFF55 : 0xFFFFFF;
				g.drawString(font, line, left + pad, y, color, true);
				y += 18;
			}
		}
	}

	/** 逐字彩虹渐变（彩虹羊式）：整体色相随时间循环，字与字之间只做小幅偏移 */
	private static void drawRainbow(GuiGraphics g, Font font, String text, int x, int y, int startIndex) {
		float base = (age * 0.006f) % 1.0f; // 随时间缓慢循环（约 8 秒一轮）
		int cx = x;
		for (int k = 0; k < text.length(); k++) {
			String ch = String.valueOf(text.charAt(k));
			int color = 0xFF000000
					| net.minecraft.util.Mth.hsvToRgb((base + (startIndex + k) * 0.02f) % 1.0f, 0.7f, 1.0f);
			g.drawString(font, ch, cx, y, color, true);
			cx += font.width(ch);
		}
	}

	/** 小字标签（0.75 倍字号）：宝牌行上方的提示文字 */
	private static void drawSmall(GuiGraphics g, Font font, String text, int x, int y, int color) {
		var pose = g.pose();
		pose.pushPose();
		pose.translate(x, y, 0);
		pose.scale(0.75f, 0.75f, 1.0f);
		g.drawString(font, text, 0, 0, color, true);
		pose.popPose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
