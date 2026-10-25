package mcr.riichi_arise.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import mcr.riichi_arise.game.RichiTableState;
import mcr.riichi_arise.network.MahjongSettlementPayload;

/**
 * 结算界面（S→C 推送后自动打开）：
 * <ul>
 * <li>和牌结算：和牌者 + 荣和/自摸 + 役种翻符，手牌与副露直接 blit 牌面贴图（TileIcons），
 * 和牌张抬高并高亮。</li>
 * <li>终局结算：四家最终点数排名（高→低），冠军标注。</li>
 * </ul>
 * 役种播报结束后进入 5s 确认窗口，全员确定或超时由服务端推进并推送关闭包；
 * 之后展示点数变化界面（MahjongPointDeltaScreen）。
 */
public class MahjongSettlementScreen extends Screen {
	/** 牌面绘制步长 = 贴图宽（缩放后 14px 宽 × 18px 高），牌与牌紧贴 */
	private static final int TILE_STEP = 14;

	private MahjongSettlementPayload.Settlement data;
	private int age;
	/** 已播报条数：每新出现一条役种播放一次木板放置音效 */
	private int announced;
	/** 番数段位音效是否已播（番数在役种播报完后才显示，此刻按段位播一次） */
	private boolean tierPlayed;
	/** 是否已发出 settle_confirm（倒计时归零自动确认 / 手动点"确定"，只发一次） */
	private boolean confirmSent;

	private MahjongSettlementScreen() {
		super(Component.translatable("gui.riichi_arise.settlement.title"));
	}

	/** 待打开的结算界面（等和牌语音播完再弹） */
	private static boolean pendingOpen;

	public static void open() {
		pendingOpen = false;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null)
			mc.setScreen(new MahjongSettlementScreen());
	}

	/** 收到结算包：和牌语音（荣/自摸，多家荣和会并行播）播完后才打开界面 */
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
	}

	/** 服务端推进下一局：关闭已打开的结算界面 */
	public static void closeIfOpen() {
		pendingOpen = false;
		mcr.riichi_arise.voice.FanAnnouncer.cancel(); // 清掉没播完的报番/段位语音，避免下一局先响
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen instanceof MahjongSettlementScreen)
			mc.setScreen(null);
	}

	@Override
	protected void init() {
		this.data = MahjongSettlementPayload.last;
		// 单一"确定"按钮：回传确认（服务端全员确认后推进）并关闭界面；关闭界面等同确认
		addRenderableWidget(Button.builder(Component.translatable("gui.riichi_arise.settlement.confirm"), b -> onClose())
				.bounds(this.width / 2 - 40, Math.min(this.height - 26, this.height / 2 + 78), 80, 20).build());
	}

	@Override
	public void onClose() {
		// 关闭界面（含 ESC / 确定按钮）= 确认，否则服务端要等满兜底时限才推进；
		// 同时停掉没播完的报番语音，避免残留在下一局番名之前先响
		sendConfirmIfNeeded();
		mcr.riichi_arise.voice.FanAnnouncer.cancel();
		super.onClose();
	}

	/** 回传 settle_confirm（同一局只发一次） */
	private void sendConfirmIfNeeded() {
		if (confirmSent || this.data == null || this.data.mode() != MahjongSettlementPayload.MODE_HAND_WIN)
			return;
		confirmSent = true;
		net.neoforged.neoforge.network.PacketDistributor.sendToServer(
				new mcr.riichi_arise.network.MahjongGamePayloads.ActionMessage(
						this.data.originPacked(), "settle_confirm", ""));
	}

	@Override
	public void tick() {
		// 报番推进门控：相邻番种至少间隔 12 tick（音频缺失的角色也逐条显现，不会一次性全显示）
		mcr.riichi_arise.voice.FanAnnouncer.tick();
		// 报番延迟 1s 开始：界面弹出满延迟才入队语音并显现第一条（幂等）
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
			onClose();
		// 报番音效：每新出现一条役种播放一次木板放置声（可见条数由 age 推导，与渲染一致）
		if (this.data != null && !this.data.yakuList().isBlank()) {
			int n = this.data.yakuList().split(";").length;
			int visible = 0;
			for (int i = 0; i < n; i++)
				if (age >= RichiTableState.SETTLE_ANNOUNCE_DELAY + 15 + RichiTableState.SETTLE_ANNOUNCE_STEP * i)
					visible++;
			if (visible > this.announced) {
				this.announced = visible;
				Minecraft.getInstance().getSoundManager().play(
						net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
								net.minecraft.sounds.SoundEvents.WOOD_PLACE, 1.0F));
			}
		}
		// 段位显现时刻（番符 +1s、点数 +0.5s 后）：按打点段位播一次音效——役满=不死图腾、三倍满=信标、
		// 倍满=铁砧放置、跳满=喷溅药水迸发、满贯（含流局满贯）=粘液块放置、满贯以下无
		if (!this.tierPlayed && timingReady && this.data != null
				&& this.data.mode() == MahjongSettlementPayload.MODE_HAND_WIN
				&& age >= tierAt) {
			this.tierPlayed = true;
			var sound = tierSound();
			if (sound != null)
				Minecraft.getInstance().getSoundManager().play(
						net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(sound, 1.0F));
		}
		// 段位语音（满贯/跳满/倍满/三倍满/累计役满/N倍役满）：音效播完 1s 后再念，避免与音效重叠
		if (!this.levelPlayed && timingReady && this.data != null
				&& this.data.mode() == MahjongSettlementPayload.MODE_HAND_WIN
				&& age >= tierAt + 20) {
			this.levelPlayed = true;
			mcr.riichi_arise.voice.FanAnnouncer.playLevel(tierVoiceKey());
		}
	}

	/** 报番固定节奏的结束时刻（无报番语音 / 语音超时兜底） */
	private int announceFallbackEnd() {
		int n = this.data == null || this.data.yakuList().isBlank() ? 0
				: this.data.yakuList().split(";").length;
		return RichiTableState.SETTLE_ANNOUNCE_DELAY + 15
				+ RichiTableState.SETTLE_ANNOUNCE_STEP * (n - 1) + 20;
	}

	/** 报番结束时刻：语音播完（或超时）后 +1s；未结束时返回哨兵值 */
	private int announceEndNow() {
		if (!mcr.riichi_arise.voice.FanAnnouncer.isActive())
			return announceEndTick();
		return voiceEndAge != null ? voiceEndAge + 20 : Integer.MAX_VALUE - 1;
	}

	/** 段位显现时刻（= 确认倒计时起点）；报番尚未结束时返回哨兵值 */
	private int tierAtNow() {
		int end = announceEndNow();
		return end >= Integer.MAX_VALUE - 1000 ? Integer.MAX_VALUE : end + RichiTableState.SETTLE_TIER_OFFSET;
	}

	/** 打点段位（雀魂按点数段位判定，gain=番符点数本体不含本场）：役满符=0（含累计役满 13n翻/0符） */
	private net.minecraft.sounds.SoundEvent tierSound() {
		if (this.data == null)
			return null;
		if (this.data.fu() == 0)
			return net.minecraft.sounds.SoundEvents.TOTEM_USE; // 役满
		if ("nagashi".equals(this.data.winType()))
			return net.minecraft.sounds.SoundEvents.SLIME_BLOCK_PLACE; // 流局满贯 = 满贯段位
		int gain = this.data.gain();
		// 庄家打点 = 子家 ×1.5：满贯 12000/跳满 18000/倍满 24000/三倍满 36000（子家 8000/12000/16000/24000）
		boolean parent = this.data.parent();
		int sanbaiman = parent ? 36000 : 24000;
		int baiman = parent ? 24000 : 16000;
		int haneman = parent ? 18000 : 12000;
		int mangan = parent ? 12000 : 8000;
		if (gain >= sanbaiman)
			return net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE; // 三倍满
		if (gain >= baiman)
			return net.minecraft.sounds.SoundEvents.ANVIL_PLACE; // 倍满
		if (gain >= haneman)
			return net.minecraft.sounds.SoundEvents.SPLASH_POTION_BREAK; // 跳满
		if (gain >= mangan)
			return net.minecraft.sounds.SoundEvents.SLIME_BLOCK_PLACE; // 满贯
		return null;
	}

	/** 打点段位名（役种播报完显示于番符行；满贯以下返回 null 保持番符点数格式） */
	private String tierLabel() {
		if (this.data == null)
			return null;
		if (this.data.fu() == 0) {
			// 役满（han 编码倍数）：1 倍"役满"，n 倍"n倍役满"
			int count = Math.max(1, this.data.han());
			return count > 1
					? Component.translatable("gui.riichi_arise.settlement.yakuman_n", count).getString()
					: Component.translatable("gui.riichi_arise.settlement.yakuman").getString();
		}
		int gain = this.data.gain();
		// 庄家打点 = 子家 ×1.5：满贯 12000/跳满 18000/倍满 24000/三倍满 36000（子家 8000/12000/16000/24000）
		boolean parent = this.data.parent();
		int sanbaiman = parent ? 36000 : 24000;
		int baiman = parent ? 24000 : 16000;
		int haneman = parent ? 18000 : 12000;
		int mangan = parent ? 12000 : 8000;
		if (gain >= sanbaiman)
			return Component.translatable("gui.riichi_arise.settlement.sanbaiman").getString();
		if (gain >= baiman)
			return Component.translatable("gui.riichi_arise.settlement.baiman").getString();
		if (gain >= haneman)
			return Component.translatable("gui.riichi_arise.settlement.haneman").getString();
		if (gain >= mangan)
			return Component.translatable("gui.riichi_arise.settlement.mangan").getString();
		return null;
	}

	/** 点数等级播报键（与实际打点段位一致；未达满贯返回 null） */
	private String tierVoiceKey() {
		if (this.data == null || "nagashi".equals(this.data.winType()))
			return null; // 流局满贯无役种报番，不播等级语音
		if (this.data.fu() == 0) {
			// 役满（han 编码倍数）：13 番以上为累计役满，否则 N 倍役满（6 倍以上用 6 倍音频）
			int count = Math.max(1, this.data.han());
			return count >= 13 ? "leijiyiman" : "yiman" + Math.min(6, count);
		}
		int gain = this.data.gain();
		boolean parent = this.data.parent();
		if (gain >= (parent ? 36000 : 24000))
			return "sanbeiman";
		if (gain >= (parent ? 24000 : 16000))
			return "beiman";
		if (gain >= (parent ? 18000 : 12000))
			return "tiaoman";
		if (gain >= (parent ? 12000 : 8000))
			return "mangan";
		return null;
	}

	/** 报番语音模式下的播报结束时刻（最后一次 FanAnnouncer.done 时记录） */
	private Integer voiceEndAge;

	/** 点数等级语音是否已触发（点数显现的同一刻播一次） */
	private boolean levelPlayed;

	/** 役种逐条播报结束时刻（弹出后延迟 1s 开始，固定节奏 15tick 起、每条间隔 SETTLE_ANNOUNCE_STEP、尾条停留 1s） */
	private int announceEndTick() {
		int n = this.data == null || this.data.yakuList().isBlank() ? 0
				: this.data.yakuList().split(";").length;
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

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		if (this.data == null) {
			g.drawCenteredString(this.font, "...", this.width / 2, this.height / 2, 0xAAAAAA);
			return;
		}
		boolean win = this.data.mode() == MahjongSettlementPayload.MODE_HAND_WIN;
		List<Integer> doraTiles = win ? parseCsv(this.data.doraCsv()) : List.of();
		List<Integer> uraTiles = win ? parseCsv(this.data.uraCsv()) : List.of();
		List<String> yakus = win && !this.data.yakuList().isBlank()
				? List.of(this.data.yakuList().split(";"))
				: List.of();
		// 报番同步：有报番任务时役种随语音进度显现（念完一条显现下一条+打点音）；
		// 语音播完或超时后回退固定节奏
		boolean fanActive = mcr.riichi_arise.voice.FanAnnouncer.isActive();
		boolean fanEnded = fanActive && voiceEndAge != null;
		int announceEnd = announceEndNow();
		// 报番结束后分三段显现：+1s 番数符数 → +0.5s 点数 → +0.5s 段位名（满贯/跳满等）
		boolean pending = announceEnd >= Integer.MAX_VALUE - 1000; // 报番尚未结束（哨兵值），避免加法溢出
		int hanFuAt = pending ? Integer.MAX_VALUE : announceEnd + 20;
		int pointAt = pending ? Integer.MAX_VALUE : announceEnd + 30;
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
		int rowUnits = parseCsv(this.data.handCsv()).size() + meldTileCount;
		int naturalRow = rowUnits * TILE_STEP + melds.size() * MELD_GAP; // 手牌行自然宽度（等比缩放前）
		int indRows = win && (!doraTiles.isEmpty() || !uraTiles.isEmpty()) ? 1 : 0;
		int indW = (doraTiles.size() + uraTiles.size()) * TILE_STEP + 110;
		int h = pad + 12 + (win ? 12 + (indRows * 32) + 22
				+ (yakuRows == 0 ? 0 : yakuRows * 13 + 4) + 14 : 4 * 18 + 8) + pad;
		int w = Math.min(this.width - 8, win
				? Math.max(220, Math.max(naturalRow,
						yakus.size() > 1 ? cols * COL_W : 0) + pad * 2)
				: 180);
		if (win)
			w = Math.min(this.width - 8, Math.max(w, indW));
		int left = this.width / 2 - w / 2;
		int top = this.height / 2 - h / 2 - 8;
		g.fill(left, top, left + w, top + h, 0xE0000000);
		int border = 0xFFB8860B;
		g.fill(left, top, left + w, top + 1, border);
		g.fill(left, top + h - 1, left + w, top + h, border);
		g.fill(left, top, left + 1, top + h, border);
		g.fill(left + w - 1, top, left + w, top + h, border);

		int y = top + 6;
		// 标题 = 荣和/自摸/流局满贯 + 和牌者
		String title = Component.translatable(
				win ? "gui.riichi_arise.settlement." + this.data.winType() : "gui.riichi_arise.settlement.final").getString();
		String head = win ? title + "　" + this.data.winnerName() : title;
		g.drawCenteredString(this.font, head, this.width / 2, y, 0xFFFF55);
		// 倒计时在番符/点数/段位全部显现后（报番结束 +2s）才开始显示；归零时 tick 自动回确认
		if (age >= tierAt) {
			int remain = Math.max(0, (tierAt + RichiTableState.SETTLE_CONFIRM_TICKS - age + 19) / 20);
			String cd = Component.translatable("gui.riichi_arise.settlement.countdown", remain).getString();
			g.drawString(this.font, cd, left + w - pad - this.font.width(cd), top + 6, 0xFFAA55, false);
		}
		y += 14;

		if (win) {
			// 宝牌指示牌行（表宝牌，含里宝：立直和牌时展示）
			// 表宝牌指示牌与里宝牌同一排；各自小字标签画在牌面上方一排（0.75 倍字号）
			if (!doraTiles.isEmpty() || !uraTiles.isEmpty()) {
				int ty = y + 9;
				int tx = left + pad;
				if (!doraTiles.isEmpty()) {
					drawSmall(g, Component.translatable("gui.riichi_arise.settlement.dora_ind").getString(),
							tx, y, 0x7FDDFF);
					for (int code : doraTiles)
						TileIcons.draw(g, code, tx += TILE_STEP, ty, TILE_STEP);
					tx += TILE_STEP + 8;
				}
				if (!uraTiles.isEmpty()) {
					drawSmall(g, Component.translatable("gui.riichi_arise.settlement.ura").getString(),
							tx, y, 0xAA88FF);
					for (int code : uraTiles)
						TileIcons.draw(g, code, tx += TILE_STEP, ty, TILE_STEP);
				}
				y += 32;
			}
			// 手牌 + 副露同一行：放不下时整行等比缩小（牌宽 step 缩小，高按 4:3 跟随）
			List<Integer> hand = parseCsv(this.data.handCsv());
			int step = Math.min(TILE_STEP, (w - pad * 2 - melds.size() * MELD_GAP) / Math.max(1, rowUnits));
			if (step < 6)
				step = 6;
			int tx = left + pad;
			for (int i = 0; i < hand.size(); i++) {
				int code = hand.get(i);
				boolean isWinTile = code == this.data.winTile() && i == hand.size() - 1;
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
						int extraTotal = Math.min(mcr.riichi_arise.voice.FanAnnouncer.extrasTotal(), yakus.size());
						int extraStarted = mcr.riichi_arise.voice.FanAnnouncer.startedExtras();
						if (extraTotal > 0 && extraStarted > 0)
							limit = Math.max(limit,
									Math.min(yakus.size() - 1, yakus.size() - extraTotal + extraStarted - 1));
					}
				} else {
					for (int i = 0; i < shown; i++)
						if (age >= RichiTableState.SETTLE_ANNOUNCE_DELAY + 15
								+ RichiTableState.SETTLE_ANNOUNCE_STEP * i)
							limit = i;
				}
				// 列优先且每列固定 4 个：col = i/4, row = i%4（播报顺序与排布一致）
				for (int i = 0; i < shown; i++) {
					if (i > limit)
						break;
					int col = i / 4;
					int row = i % 4;
					g.drawString(this.font, yakus.get(i), left + pad + col * COL_W, y + row * 13, 0x7FFF7F, true);
				}
				if (shown < yakus.size() && limit >= shown - 1)
					g.drawString(this.font, "+" + (yakus.size() - shown), left + pad, y + yakuRows * 13, 0xAAAAAA, true);
				y += yakuRows * 13 + 4;
			}
			// 底部三段式显现：报番结束 → +1s 番数符数 → +0.5s 点数 → +0.5s 段位名（满贯/跳满等）
			// （段位语音由 tick 在音效后 1s 播放）
			boolean nagashi = "nagashi".equals(this.data.winType());
			String tier = nagashi ? null : tierLabel();
			// 三段按最终宽度一次性排好位置，逐段到时才绘制：后出现的段落不会把已显现的文字挤开。
			// 整行贴在窗口下半部分（窗口底边往上留 24px）。
			String hanFuText = (!nagashi && this.data.fu() != 0)
					? Component.translatable("gui.riichi_arise.settlement.han_fu",
							this.data.han(), this.data.fu()).getString()
					: null;
			String ptsText = Component.translatable("gui.riichi_arise.settlement.pts_only", this.data.gain()).getString();
			List<String> parts = new ArrayList<>(3);
			List<Integer> showAt = new ArrayList<>(3);
			if (hanFuText != null) {
				parts.add(hanFuText);
				showAt.add(hanFuAt);
			}
			parts.add(ptsText);
			showAt.add(pointAt);
			if (tier != null) {
				parts.add(tier);
				showAt.add(tierAt);
			}
			int sepW = this.font.width("　");
			int totalW = sepW * (parts.size() - 1);
			for (String s : parts)
				totalW += this.font.width(s);
			int px = this.width / 2 - totalW / 2;
			int infoY = top + h - 24;
			for (int i = 0; i < parts.size(); i++) {
				if (age >= showAt.get(i))
					g.drawString(this.font, parts.get(i), px, infoY, 0xFFFF55, true);
				px += this.font.width(parts.get(i)) + sepW;
			}
		} else {
			// 终局排名：按点数高→低
			Integer[] order = { 0, 1, 2, 3 };
			java.util.Arrays.sort(order, (a, b) -> Integer.compare(this.data.points()[b], this.data.points()[a]));
			for (int rank = 0; rank < 4; rank++) {
				int seat = order[rank];
				String line = (rank + 1) + ". " + this.data.names()[seat]
						+ "　" + this.data.points()[seat]
						+ Component.translatable("gui.riichi_arise.settlement.pt").getString();
				int color = rank == 0 ? 0xFFFF55 : 0xFFFFFF;
				g.drawString(this.font, line, left + pad, y, color, true);
				y += 18;
			}
		}
	}

	/** 小字标签（0.75 倍字号）：宝牌行上方的提示文字 */
	private void drawSmall(GuiGraphics g, String text, int x, int y, int color) {
		var pose = g.pose();
		pose.pushPose();
		pose.translate(x, y, 0);
		pose.scale(0.75f, 0.75f, 1.0f);
		g.drawString(this.font, text, 0, 0, color, true);
		pose.popPose();
	}

	private List<List<Integer>> meldGroups() {
		List<List<Integer>> groups = new ArrayList<>();
		for (String grp : this.data.meldsGroups().split(";")) {
			if (grp.isBlank())
				continue;
			groups.add(parseCsv(grp));
		}
		return groups;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
