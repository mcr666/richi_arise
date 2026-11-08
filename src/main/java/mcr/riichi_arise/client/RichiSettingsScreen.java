package mcr.riichi_arise.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import mcr.riichi_arise.RichiConfig;
import mcr.riichi_arise.network.MahjongLobbyPayloads;
import mcr.riichi_arise.network.MahjongSettingsPayloads;

/**
 * 麻雀"内容设置"界面（风盘界面左下角"设置"按钮进入）。
 * <p>带类别名 + 分隔线的可收起/展开、可滚动列表，三类：客户端 / 个人设定 / 世界规则。</p>
 * <ul>
 * <li><b>客户端</b>：观战摊手 / AI 形象姿态 / 观战显示牌局内容——本地配置（RichiConfig），点击切换并保存。</li>
 * <li><b>个人设定</b>：角色语音——点击进入语音选择界面（VoiceSettingsScreen）。</li>
 * <li><b>世界规则</b>：网络接口自动开启 gamerule，以及内网端口手动开关（管理员可改，经服务端包）。</li>
 * </ul>
 * 样式与大厅管理界面一致：灰色半透明行（悬停高亮、禁用暗灰）+ 金色分隔线。
 */
public class RichiSettingsScreen extends Screen {
	private static final int PANEL_W = 250;
	private static final int ROW_H = 18;
	private static final int ROW_GAP = 19;
	/** 内容区右侧竖向滑块条宽与间距 */
	private static final int SCROLLBAR_W = 6;
	private static final int SCROLLBAR_GAP = 6;
	/** 底部提示预留高度（距屏幕底边） */
	private static final int BOTTOM_RESERVE = 38;

	private final BlockPos origin;

	/** 行类型 */
	private static final int HEADER = 0, CLIENT_TOGGLE = 1, PERSONAL_VOICE = 2, WORLD_TOGGLE = 3, NET_TOGGLE = 4,
			VOICE_VOLUME = 5, WIN_EFFECT = 6, CLIENT_CANDIDATE_POS = 7, RIICHI_EFFECT = 8;

	/** 右键候选列表行高（与风盘座位选角列表一致） */
	private static final int POPUP_ROW = 13;

	private record Row(int kind, int cat, String labelKey, String value, boolean enabled, int extra) {
	}

	/** 右键弹出列表中的一项：label 已本地化，selected = 当前值 */
	private record SettingOption(String label, boolean selected, Runnable action) {
	}

	/** 三个类别是否展开 */
	private final boolean[] expanded = { true, true, true };
	private int scroll;
	/** 内容区右侧滑块条是否正在拖动 */
	private boolean draggingScrollbar;
	/** 右键候选列表：打开时对应的行（null = 未打开）、纵向锚点与内部滚动 */
	private Row popupRow;
	private int popupAnchorY;
	private int popupScroll;

	/** 世界规则/端口状态（服务端 Sync） */
	private MahjongSettingsPayloads.SettingsSync sync;

	/** 当前打开的界面实例（供网络包回调更新） */
	private static RichiSettingsScreen ACTIVE;

	public RichiSettingsScreen(BlockPos origin) {
		super(Component.translatable("gui.riichi_arise.settings.title"));
		this.origin = origin;
	}

	public static void open(BlockPos origin) {
		Minecraft.getInstance().setScreen(new RichiSettingsScreen(origin));
	}

	/** 网络包回调：更新世界规则/端口状态 */
	public static void onSync(MahjongSettingsPayloads.SettingsSync s) {
		if (ACTIVE != null)
			ACTIVE.sync = s;
	}

	@Override
	protected void init() {
		ACTIVE = this;
		this.popupRow = null;
		this.popupScroll = 0;
		if (this.sync == null)
			send(MahjongSettingsPayloads.ACT_REQUEST_SYNC, 0, 0);
	}

	@Override
	public void onClose() {
		if (ACTIVE == this)
			ACTIVE = null;
		super.onClose();
	}

	private void send(int action, int extra, int flag) {
		net.neoforged.neoforge.network.PacketDistributor.sendToServer(
				new MahjongSettingsPayloads.SettingsAction(action, extra, flag));
	}

	// ==================================================================
	// 行列表（按当前展开状态 + 最新 Sync 构建）
	// ==================================================================

	private int left() {
		return this.width / 2 - PANEL_W / 2;
	}

	private int top() {
		// 小窗口下也保证标题不越出屏幕顶边
		return Math.max(24, this.height / 2 - 150);
	}

	private String onOff(boolean b) {
		return Component.translatable(b ? "gui.riichi_arise.settings.on" : "gui.riichi_arise.settings.off").getString();
	}

	/** 自己当前语音 id（空串 = 关闭） */
	private String ownVoiceId() {
		MahjongLobbyPayloads.LobbySync s = MahjongLobbyPayloads.CLIENT_SYNC.get(origin.asLong());
		return s == null || s.ownVoice() == null ? "" : s.ownVoice();
	}

	private String ownVoice() {
		String id = ownVoiceId();
		if (id.isEmpty())
			return Component.translatable("gui.riichi_arise.voice.none").getString();
		return mcr.riichi_arise.voice.VoiceCharacters.displayName(id);
	}

	private List<Row> rows() {
		List<Row> r = new ArrayList<>();
		// 客户端（本地配置）
		r.add(new Row(HEADER, 0, "gui.riichi_arise.settings.cat_client", expanded[0] ? "▾" : "▸", true, 0));
		if (expanded[0]) {
			r.add(clientRow("gui.riichi_arise.settings.client_spectator_open", RichiConfig.SPECTATOR_OPEN_HANDS));
			r.add(clientRow("gui.riichi_arise.settings.client_avatar_stand", RichiConfig.MAHJONG_AVATAR_STANDING));
			r.add(clientRow("gui.riichi_arise.settings.client_spectator_show", RichiConfig.SPECTATOR_SHOW_TABLE));
			r.add(clientRow("gui.riichi_arise.settings.client_action_anim", RichiConfig.TILE_MOVE_ANIMATIONS));
			r.add(clientRow("gui.riichi_arise.settings.client_swap_buttons", RichiConfig.SWAP_HAND_BUTTONS));
			r.add(clientRow("gui.riichi_arise.settings.client_flat_glass", RichiConfig.FLAT_GLASS_HAND_TILES));
			r.add(new Row(CLIENT_CANDIDATE_POS, 0, "gui.riichi_arise.settings.client_candidate_pos",
					candidatePosText(), true, 0));
			r.add(new Row(VOICE_VOLUME, 0, "gui.riichi_arise.voice.volume", voiceVolumeText(), true, 0));
		}
		// 个人设定
		r.add(new Row(HEADER, 1, "gui.riichi_arise.settings.cat_personal", expanded[1] ? "▾" : "▸", true, 0));
		if (expanded[1]) {
			r.add(new Row(PERSONAL_VOICE, 1, "gui.riichi_arise.settings.personal_voice", ownVoice(), true, 0));
			r.add(new Row(WIN_EFFECT, 1, "gui.riichi_arise.settings.personal_win_effect", winEffectText(), true, 0));
			r.add(new Row(RIICHI_EFFECT, 1, "gui.riichi_arise.settings.personal_riichi_effect",
					riichiEffectText(), true, 0));
		}
		// 世界规则（gamerule + 端口，需管理员；数据来自服务端 Sync）
		r.add(new Row(HEADER, 2, "gui.riichi_arise.settings.cat_world", expanded[2] ? "▾" : "▸", true, 0));
		if (expanded[2]) {
			boolean admin = this.sync != null && this.sync.canManage();
			boolean loaded = this.sync != null;
			boolean auto = loaded && this.sync.autoOpenNetPort();
			boolean running = loaded && this.sync.netRunning();
			r.add(new Row(WORLD_TOGGLE, 2, "gui.riichi_arise.settings.world_auto_net",
					loaded ? onOff(auto) : "...", admin, MahjongSettingsPayloads.RULE_AUTO_NET));
			String netVal = !loaded ? "..." : running
					? Component.translatable("gui.riichi_arise.settings.net_running", this.sync.netPort(),
							this.sync.netConn()).getString()
					: Component.translatable("gui.riichi_arise.settings.net_stopped").getString();
			r.add(new Row(NET_TOGGLE, 2, "gui.riichi_arise.settings.world_net", netVal, admin, 0));
		}
		return r;
	}

	private Row clientRow(String key, net.neoforged.neoforge.common.ModConfigSpec.BooleanValue v) {
		return new Row(CLIENT_TOGGLE, 0, key, onOff(v.get()), true, 0);
	}

	/** 角色语音音量显示（百分比） */
	private String voiceVolumeText() {
		return Math.round(RichiConfig.VOICE_VOLUME.get() * 100) + "%";
	}

	/** 备选牌渲染位置显示名（左上/右上/左下/右下） */
	private String candidatePosText() {
		String[] keys = { "gui.riichi_arise.settings.candidate_pos.top_left",
				"gui.riichi_arise.settings.candidate_pos.top_right",
				"gui.riichi_arise.settings.candidate_pos.bottom_left",
				"gui.riichi_arise.settings.candidate_pos.bottom_right" };
		return Component.translatable(keys[RichiConfig.CANDIDATE_POSITION.get().ordinal()]).getString();
	}

	/** 和牌特效当前风格值（与立直特效共享 6 种样式 + 关闭 + 随机） */
	private int winEffectIndex() {
		MahjongLobbyPayloads.LobbySync s = MahjongLobbyPayloads.CLIENT_SYNC.get(origin.asLong());
		int v = s == null ? 2 : s.ownWinEffect(); // 缺省 = 光环
		return Math.max(0, Math.min(TableTileEffect.STYLE_COUNT - 1, v));
	}

	/** 立直特效当前风格值（与和牌特效共用同一套 6 种样式 + 关闭 + 随机） */
	private int riichiEffectIndex() {
		MahjongLobbyPayloads.LobbySync s = MahjongLobbyPayloads.CLIENT_SYNC.get(origin.asLong());
		int v = s == null ? 2 : s.ownRiichiEffect(); // 缺省 = 光环
		return Math.max(0, Math.min(TableTileEffect.STYLE_COUNT - 1, v));
	}

	private String winEffectText() {
		return Component.translatable(TableTileEffect.STYLE_KEYS[winEffectIndex()]).getString();
	}

	/** 立直特效风格显示名（0=关闭、1=随机、2=光环、3=星环、4=光柱、5=星陨、6=金阁、7=极光）：取服务端同步的自己当前值 */
	private String riichiEffectText() {
		return Component.translatable(TableTileEffect.STYLE_KEYS[riichiEffectIndex()]).getString();
	}

	/** 视口内可显示的行数：按可用高度推算（与牌谱列表的 rowsVisible 同义），不再写死 */
	private int rowsVisible() {
		return Math.max(1, (downArrowY() - rowY(0)) / ROW_GAP);
	}

	/** 下翻按钮 y（同时是内容区视口的下边界）：距屏幕底边预留底部提示 */
	private int downArrowY() {
		return Math.max(rowY(0) + ROW_GAP, this.height - BOTTOM_RESERVE);
	}

	private int maxScroll() {
		return Math.max(0, rows().size() - rowsVisible());
	}

	// ------------------------------------------------------------------
	// 右键候选列表（与风盘座位选角列表同款外观：点击条目即选中并关闭；长列表滚轮滚动）
	// ------------------------------------------------------------------

	/** 候选列表宽度：与内容面板等宽，窄屏时收缩到屏幕内 */
	private int popupW() {
		return Math.min(PANEL_W, Math.max(80, this.width - 12));
	}

	/** 候选列表横坐标：优先贴面板右侧，放不下时退回面板左缘并夹紧在屏幕内 */
	private int popupX() {
		int w = popupW();
		int preferred = left() + PANEL_W + SCROLLBAR_GAP + SCROLLBAR_W + 6;
		if (preferred + w > this.width - 6)
			preferred = Math.max(6, Math.min(left(), this.width - w - 6));
		return preferred;
	}

	/** 当前候选列表一次能显示的行数（按屏幕高度限制，避免超出屏幕） */
	private int popupFitRows() {
		return Math.max(1, (this.height - 12) / POPUP_ROW);
	}

	private int popupVisible() {
		return Math.min(popupCount(this.popupRow), popupFitRows());
	}

	private int popupH() {
		return popupVisible() * POPUP_ROW + 6;
	}

	/** 候选列表顶部：优先贴该行，底部溢出时上移，保证整张列表不超出屏幕 */
	private int popupY() {
		return Math.max(6, Math.min(this.popupAnchorY, this.height - popupH() - 6));
	}

	/** 该行右键候选数量（0 = 不弹列表） */
	private int popupCount(Row r) {
		if (r == null || !r.enabled())
			return 0;
		return switch (r.kind()) {
			case CLIENT_TOGGLE, WORLD_TOGGLE, NET_TOGGLE -> 2;
			case CLIENT_CANDIDATE_POS -> RichiConfig.CandidatePosition.values().length;
			case VOICE_VOLUME -> 11;
			case WIN_EFFECT, RIICHI_EFFECT -> TableTileEffect.STYLE_COUNT;
			case PERSONAL_VOICE -> 1 + mcr.riichi_arise.voice.VoiceCharacters.all().size();
			default -> 0;
		};
	}

	/** 构造该行的完整候选（selected 标记当前值；每帧重建以跟随最新同步） */
	private List<SettingOption> popupOptions(Row r) {
		if (popupCount(r) <= 0)
			return null;
		List<SettingOption> list = new ArrayList<>();
		switch (r.kind()) {
			case CLIENT_TOGGLE -> {
				var v = clientToggleValue(r.labelKey());
				if (v == null)
					return null;
				boolean cur = v.get();
				String key = r.labelKey();
				list.add(new SettingOption(onOff(true), cur, () -> applyClientToggle(key, true)));
				list.add(new SettingOption(onOff(false), !cur, () -> applyClientToggle(key, false)));
			}
			case CLIENT_CANDIDATE_POS -> {
				RichiConfig.CandidatePosition[] all = RichiConfig.CandidatePosition.values();
				String[] keys = { "gui.riichi_arise.settings.candidate_pos.top_left",
						"gui.riichi_arise.settings.candidate_pos.top_right",
						"gui.riichi_arise.settings.candidate_pos.bottom_left",
						"gui.riichi_arise.settings.candidate_pos.bottom_right" };
				int cur = RichiConfig.CANDIDATE_POSITION.get().ordinal();
				for (int i = 0; i < all.length; i++) {
					final int idx = i;
					list.add(new SettingOption(Component.translatable(keys[i]).getString(), i == cur, () -> {
						RichiConfig.CANDIDATE_POSITION.set(all[idx]);
						RichiConfig.SPEC.save();
					}));
				}
			}
			case VOICE_VOLUME -> {
				int curStep = (int) Math.round(RichiConfig.VOICE_VOLUME.get() * 10);
				for (int i = 0; i <= 10; i++) {
					final double vol = i / 10.0;
					list.add(new SettingOption(Math.round(vol * 100) + "%", i == curStep, () -> {
						RichiConfig.VOICE_VOLUME.set(vol);
						RichiConfig.SPEC.save();
					}));
				}
			}
			case WIN_EFFECT -> {
				int cur = winEffectIndex();
				for (int style : TableTileEffect.SETTING_STYLES) {
					list.add(new SettingOption(Component.translatable(TableTileEffect.STYLE_KEYS[style]).getString(),
							style == cur, () -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
									new mcr.riichi_arise.network.VoicePayloads.WinEffectSetMessage(style))));
				}
			}
			case RIICHI_EFFECT -> {
				int cur = riichiEffectIndex();
				for (int style : TableTileEffect.SETTING_STYLES) {
					list.add(new SettingOption(Component.translatable(TableTileEffect.STYLE_KEYS[style]).getString(),
							style == cur, () -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
									new mcr.riichi_arise.network.RiichiEffectPayload.SetMessage(style))));
				}
			}
			case PERSONAL_VOICE -> {
				String cur = ownVoiceId();
				list.add(new SettingOption(Component.translatable("gui.riichi_arise.voice.none").getString(),
						cur.isEmpty(),
						() -> mcr.riichi_arise.voice.VoiceClient.setOwnVoice(mcr.riichi_arise.network.VoicePayloads.NONE)));
				for (var c : mcr.riichi_arise.voice.VoiceCharacters.all()) {
					StringBuilder label = new StringBuilder(c.displayName());
					if (c.offline())
						label.append(Component.translatable("gui.riichi_arise.voice.tag_offline").getString());
					else if (mcr.riichi_arise.voice.VoiceCache.isReady(c.id()))
						label.append(Component.translatable("gui.riichi_arise.voice.tag_ready").getString());
					else
						label.append(Component.translatable("gui.riichi_arise.voice.tag_online").getString());
					list.add(new SettingOption(label.toString(), c.id().equals(cur),
							() -> mcr.riichi_arise.voice.VoiceClient.selectVoice(c.id(), null)));
				}
			}
			case WORLD_TOGGLE -> {
				boolean cur = this.sync != null && this.sync.autoOpenNetPort();
				int rule = r.extra();
				list.add(new SettingOption(onOff(true), cur, () -> setWorldAuto(rule, true)));
				list.add(new SettingOption(onOff(false), !cur, () -> setWorldAuto(rule, false)));
			}
			case NET_TOGGLE -> {
				boolean cur = this.sync != null && this.sync.netRunning();
				list.add(new SettingOption(onOff(true), cur, () -> setNetRunning(true)));
				list.add(new SettingOption(onOff(false), !cur, () -> setNetRunning(false)));
			}
			default -> {
			}
		}
		return list;
	}

	/** 客户端开关行对应的配置项（未知返回 null） */
	private net.neoforged.neoforge.common.ModConfigSpec.BooleanValue clientToggleValue(String labelKey) {
		return switch (labelKey) {
			case "gui.riichi_arise.settings.client_spectator_open" -> RichiConfig.SPECTATOR_OPEN_HANDS;
			case "gui.riichi_arise.settings.client_avatar_stand" -> RichiConfig.MAHJONG_AVATAR_STANDING;
			case "gui.riichi_arise.settings.client_action_anim" -> RichiConfig.TILE_MOVE_ANIMATIONS;
			case "gui.riichi_arise.settings.client_swap_buttons" -> RichiConfig.SWAP_HAND_BUTTONS;
			case "gui.riichi_arise.settings.client_flat_glass" -> RichiConfig.FLAT_GLASS_HAND_TILES;
			case "gui.riichi_arise.settings.client_spectator_show" -> RichiConfig.SPECTATOR_SHOW_TABLE;
			default -> null;
		};
	}

	/** 设置某个客户端开关并保存（左右键对调需上报服务端；其余立即重建牌桌生效） */
	private void applyClientToggle(String labelKey, boolean value) {
		var v = clientToggleValue(labelKey);
		if (v == null)
			return;
		v.set(value);
		RichiConfig.SPEC.save();
		if (labelKey.equals("gui.riichi_arise.settings.client_swap_buttons"))
			MahjongSettingsPayloads.sendHandSwap(value);
		MahjongTableClient.refreshAll();
	}

	/** 世界规则开关（服务端 gamerule，带乐观更新） */
	private void setWorldAuto(int rule, boolean value) {
		if (this.sync == null || !this.sync.canManage())
			return;
		send(MahjongSettingsPayloads.ACT_SET_GAMERULE, rule, value ? 1 : 0);
		this.sync = new MahjongSettingsPayloads.SettingsSync(value, this.sync.netRunning(),
				this.sync.netPort(), this.sync.netBind(), this.sync.netConn(), this.sync.canManage());
	}

	/** 网络接口开关（服务端规则，带乐观更新） */
	private void setNetRunning(boolean running) {
		if (this.sync == null || !this.sync.canManage())
			return;
		if (this.sync.netRunning() == running)
			return;
		send(running ? MahjongSettingsPayloads.ACT_NET_START : MahjongSettingsPayloads.ACT_NET_STOP, 0, 0);
		this.sync = new MahjongSettingsPayloads.SettingsSync(this.sync.autoOpenNetPort(), running,
				this.sync.netPort(), this.sync.netBind(), this.sync.netConn(), this.sync.canManage());
	}

	/** 绘制候选列表（最后绘制，盖住内容列表） */
	private void drawPopup(GuiGraphics graphics, int mouseX, int mouseY) {
		if (this.popupRow == null)
			return;
		List<SettingOption> opts = popupOptions(this.popupRow);
		if (opts == null || opts.isEmpty()) {
			this.popupRow = null;
			return;
		}
		int total = opts.size();
		int visible = Math.min(total, popupFitRows());
		this.popupScroll = Math.max(0, Math.min(this.popupScroll, total - visible));
		int px = popupX();
		int py = popupY();
		int w = popupW();
		int h = visible * POPUP_ROW + 6;
		graphics.fill(px - 4, py, px + w + 4, py + h, 0xFF101018); // 背景不透明
		graphics.renderOutline(px - 4, py, w + 8, h, 0x505000FF); // 描边
		for (int i = 0; i < visible; i++) {
			SettingOption opt = opts.get(this.popupScroll + i);
			int ey = py + 3 + i * POPUP_ROW;
			if (mouseX >= px - 2 && mouseX < px + w + 2 && mouseY >= ey && mouseY < ey + POPUP_ROW)
				graphics.fill(px - 2, ey, px + w + 2, ey + POPUP_ROW, 0x405088FF);
			String label = (opt.selected() ? "✔ " : "  ") + opt.label();
			graphics.drawString(this.font, label, px + 2, ey + 2, opt.selected() ? 0xFFCC66 : 0xFFFFFF, false);
		}
		// 长列表滚动条（右侧细条，随滚动位置移动）
		if (total > visible) {
			int trackX = px + w - 3;
			int trackTop = py + 3;
			int trackH = h - 6;
			graphics.fill(trackX, trackTop, trackX + 3, trackTop + trackH, 0x30A0A0A0);
			int th = Math.max(10, trackH * visible / total);
			int ty = trackTop + (int) Math.round((trackH - th) * (this.popupScroll / (double) (total - visible)));
			graphics.fill(trackX, ty, trackX + 3, ty + th, 0xFFCC66);
		}
	}

	// ------------------------------------------------------------------
	// 内容区右侧滑块条（位置与拖拽，视觉沿用语音设置界面的金色填充 + 白色滑块）
	// ------------------------------------------------------------------

	private int scrollbarX() {
		return left() + PANEL_W + SCROLLBAR_GAP;
	}

	private int scrollbarTop() {
		return rowY(0);
	}

	private int scrollbarH() {
		return Math.max(1, downArrowY() - scrollbarTop());
	}

	private int thumbH() {
		int total = rows().size();
		if (total <= 0)
			return scrollbarH();
		return Math.max(12, Math.min(scrollbarH(), scrollbarH() * rowsVisible() / total));
	}

	private int thumbY() {
		int max = maxScroll();
		int travel = scrollbarH() - thumbH();
		if (max <= 0 || travel <= 0)
			return scrollbarTop();
		return scrollbarTop() + (int) Math.round(travel * (scroll / (double) max));
	}

	private boolean inScrollbar(double mx, double my) {
		return maxScroll() > 0 && mx >= scrollbarX() && mx < scrollbarX() + SCROLLBAR_W
				&& my >= scrollbarTop() && my < downArrowY();
	}

	/** 按鼠标纵向位置换算滚动行（点击轨道直接定位，拖动时连续跟手） */
	private void scrollToMouse(double my) {
		int max = maxScroll();
		int travel = scrollbarH() - thumbH();
		if (max <= 0)
			return;
		if (travel <= 0) {
			scroll = 0;
			return;
		}
		double frac = (my - scrollbarTop() - thumbH() / 2.0) / travel;
		scroll = (int) Math.round(Math.max(0.0, Math.min(1.0, frac)) * max);
	}

	// ==================================================================
	// 渲染
	// ==================================================================

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		this.renderBackground(graphics, mouseX, mouseY, partialTick);
		int left = left();
		int top = top();
		var font = this.font;
		graphics.drawCenteredString(font, this.title, this.width / 2, top - 14, 0xFFFFFF);

		List<Row> rows = rows();
		if (scroll > maxScroll())
			scroll = maxScroll();
		int visible = rowsVisible();
		int bc = 0xFFB8860B; // 金色
		for (int i = 0; i < visible; i++) {
			int idx = scroll + i;
			if (idx >= rows.size())
				break;
			Row row = rows.get(idx);
			int y = rowY(i);
			if (row.kind() == HEADER) {
				// 类别头：金色分隔线 + 类别名 + 展开状态
				graphics.fill(left, y, left + PANEL_W, y + ROW_H, 0x55A0A0A0);
				graphics.fill(left, y, left + PANEL_W, y + 1, bc);
				graphics.fill(left, y + ROW_H - 1, left + PANEL_W, y + ROW_H, bc);
				graphics.drawCenteredString(font, Component.translatable(row.labelKey()).getString(),
						left + PANEL_W / 2, y + 5, 0xFFFFA0);
				graphics.drawString(font, row.value(), left + PANEL_W - 14, y + 5, 0xFFFFFF, false);
			} else {
				boolean hov = row.enabled() && mouseX >= left && mouseX < left + PANEL_W && mouseY >= y
						&& mouseY < y + ROW_H;
				graphics.fill(left, y, left + PANEL_W, y + ROW_H, hov ? 0x509090C0 : 0x30A0A0A0);
				String label = Component.translatable(row.labelKey()).getString();
				graphics.drawString(font, label, left + 8, y + 5, row.enabled() ? 0xFFFFFF : 0x808080, false);
				String val = row.value();
				int color = row.kind() == NET_TOGGLE ? (row.enabled() ? 0x88DDFF : 0x808080)
						: (row.enabled() ? 0xFFCC66 : 0x808080);
				graphics.drawString(font, val, left + PANEL_W - 8 - font.width(val), y + 5, color, false);
			}
		}
		// 上翻 / 下翻
		drawArrow(graphics, left, rowY(0) - ROW_GAP, PANEL_W, 14, "▲", mouseX, mouseY, scroll > 0);
		int downY = downArrowY();
		drawArrow(graphics, left, downY, PANEL_W, 14, "▼", mouseX, mouseY, scroll < maxScroll());
		// 内容区右侧滑块条：内容不足一屏（maxScroll=0）时自然隐藏
		drawScrollbar(graphics, mouseX, mouseY);
		// 底部提示：世界规则需管理员
		graphics.drawCenteredString(font,
				Component.translatable("gui.riichi_arise.settings.hint").getString(), this.width / 2, downY + 16, 0x808080);
		// 右键候选列表：最后绘制，盖住内容列表
		drawPopup(graphics, mouseX, mouseY);
	}

	/** 内容区右侧竖向滑块条（轨道 + 金色滑块，可点击定位与拖拽） */
	private void drawScrollbar(GuiGraphics graphics, int mouseX, int mouseY) {
		if (maxScroll() <= 0)
			return;
		int x = scrollbarX();
		int top = scrollbarTop();
		int h = scrollbarH();
		int th = thumbH();
		int ty = thumbY();
		boolean hov = mouseX >= x && mouseX < x + SCROLLBAR_W && mouseY >= top && mouseY < top + h;
		graphics.fill(x, top, x + SCROLLBAR_W, top + h, hov || draggingScrollbar ? 0x509090C0 : 0x30A0A0A0);
		graphics.fill(x, ty, x + SCROLLBAR_W, ty + th, 0xFFCC66);
	}

	private int rowY(int i) {
		return top() + 42 + i * ROW_GAP;
	}

	private void drawArrow(GuiGraphics graphics, int x, int y, int w, int h, String label, int mx, int my,
			boolean enabled) {
		boolean hov = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
		graphics.fill(x, y, x + w, y + h, hov ? 0x509090C0 : 0x30A0A0A0);
		graphics.drawString(this.font, label, x + (w - this.font.width(label)) / 2, y + 3,
				enabled ? 0xFFFFFF : 0x808080, false);
	}

	// ==================================================================
	// 交互
	// ==================================================================

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// 右键候选列表优先：点击条目 = 选中并关闭；点击列表外 = 直接关闭（不穿透到下层）
		if (this.popupRow != null) {
			List<SettingOption> opts = popupOptions(this.popupRow);
			int px = popupX();
			int py = popupY();
			int visIdx = (int) ((mouseY - py - 3) / POPUP_ROW);
			if (opts != null) {
				int visible = Math.min(opts.size(), popupFitRows());
				if (mouseX >= px - 4 && mouseX < px + popupW() + 4 && visIdx >= 0 && visIdx < visible) {
					int idx = this.popupScroll + visIdx;
					if (idx < opts.size())
						opts.get(idx).action().run();
				}
			}
			this.popupRow = null;
			this.popupScroll = 0;
			return true;
		}
		if (button != 0 && button != 1)
			return super.mouseClicked(mouseX, mouseY, button);
		int left = left();
		// 滑块条：点击轨道定位并进入拖动（仅左键）
		if (button == 0 && inScrollbar(mouseX, mouseY)) {
			scrollToMouse(mouseY);
			draggingScrollbar = true;
			return true;
		}
		// 上翻
		if (button == 0 && inBtn(mouseX, mouseY, left, rowY(0) - ROW_GAP, PANEL_W, 14)) {
			scroll = Math.max(0, scroll - 1);
			return true;
		}
		// 下翻
		int downY = downArrowY();
		if (button == 0 && inBtn(mouseX, mouseY, left, downY, PANEL_W, 14)) {
			scroll = Math.min(maxScroll(), scroll + 1);
			return true;
		}
		// 行命中：左键循环切换；右键弹出全部候选列表（仅对可切换/可选的项）
		List<Row> rows = rows();
		int visible = rowsVisible();
		for (int i = 0; i < visible; i++) {
			int idx = scroll + i;
			if (idx >= rows.size())
				break;
			int y = rowY(i);
			if (mouseY < y || mouseY >= y + ROW_H || mouseX < left || mouseX >= left + PANEL_W)
				continue;
			Row row = rows.get(idx);
			if (button == 1) {
				if (popupCount(row) > 0) {
					this.popupRow = row;
					this.popupAnchorY = y;
					this.popupScroll = 0;
				}
				return true;
			}
			handleRow(row);
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	private void handleRow(Row row) {
		switch (row.kind()) {
			case HEADER -> {
				expanded[row.cat()] = !expanded[row.cat()];
				if (scroll > maxScroll())
					scroll = maxScroll();
			}
			case CLIENT_TOGGLE -> {
				var v = clientToggleValue(row.labelKey());
				if (v != null)
					applyClientToggle(row.labelKey(), !v.get());
			}
			case PERSONAL_VOICE -> VoiceSettingsScreen.open(this.origin);
			case CLIENT_CANDIDATE_POS -> {
				// 备选牌渲染位置：单击循环切换（左上 → 右上 → 左下 → 右下 → 左上），本地即时生效
				RichiConfig.CandidatePosition[] all = RichiConfig.CandidatePosition.values();
				RichiConfig.CANDIDATE_POSITION
						.set(all[(RichiConfig.CANDIDATE_POSITION.get().ordinal() + 1) % all.length]);
				RichiConfig.SPEC.save();
			}
			case WIN_EFFECT -> {
				// 和牌特效：单击循环切换（顺序与取值一致：关闭 → 随机 → 光环 → 星环 → 光柱 → 星陨 → 金阁 → 极光）
				// 提交给服务端持久化并广播，使他人也按自己的设置看到特效
				MahjongLobbyPayloads.LobbySync s = MahjongLobbyPayloads.CLIENT_SYNC.get(origin.asLong());
				int cur = s == null ? 2 : s.ownWinEffect();
				net.neoforged.neoforge.network.PacketDistributor.sendToServer(
						new mcr.riichi_arise.network.VoicePayloads.WinEffectSetMessage(
								TableTileEffect.nextSettingStyle(cur)));
			}
			case RIICHI_EFFECT -> {
				// 立直特效：单击循环切换（顺序与取值一致：关闭 → 随机 → 光环 → 星环 → 光柱 → 星陨 → 金阁 → 极光）
				// 提交给服务端持久化并广播，立直时全桌都按声明者的设置看到特效
				MahjongLobbyPayloads.LobbySync s = MahjongLobbyPayloads.CLIENT_SYNC.get(origin.asLong());
				int cur = s == null ? 2 : s.ownRiichiEffect();
				net.neoforged.neoforge.network.PacketDistributor.sendToServer(
						new mcr.riichi_arise.network.RiichiEffectPayload.SetMessage(
								TableTileEffect.nextSettingStyle(cur)));
			}
			case VOICE_VOLUME -> {
				// 角色语音音量：每次点击 +10%，100% 后回到 0%（VoicePlayer 逐次读取，立即生效）
				double v = RichiConfig.VOICE_VOLUME.get();
				double next = Math.round((v + 0.1) * 10) / 10.0;
				if (next > 1.0 + 1.0E-6)
					next = 0.0;
				RichiConfig.VOICE_VOLUME.set(next);
				RichiConfig.SPEC.save();
			}
			case WORLD_TOGGLE -> {
				boolean cur = this.sync != null && this.sync.autoOpenNetPort();
				setWorldAuto(row.extra(), !cur);
			}
			case NET_TOGGLE -> setNetRunning(this.sync == null || !this.sync.netRunning());
			default -> {
			}
		}
	}

	private boolean inBtn(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		// 右键候选列表打开时优先滚动列表内部（长列表：角色语音等）
		if (this.popupRow != null) {
			List<SettingOption> opts = popupOptions(this.popupRow);
			int total = opts == null ? 0 : opts.size();
			int max = Math.max(0, total - Math.min(total, popupFitRows()));
			this.popupScroll = Math.max(0, Math.min(this.popupScroll - (int) dy, max));
			return true;
		}
		// 与牌谱查看器一致：按滚轮格数步进并夹紧；内容不足一屏时不响应
		if (maxScroll() <= 0)
			return false;
		scroll = Math.max(0, Math.min(scroll - (int) dy, maxScroll()));
		return true;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (button == 0 && draggingScrollbar) {
			scrollToMouse(my);
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		draggingScrollbar = false;
		return super.mouseReleased(mx, my, button);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
