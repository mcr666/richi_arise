package mcr.riichi_arise.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import mcr.riichi_arise.network.MahjongLobbyPayloads;

/**
 * 麻雀大厅管理界面（潜行右击风盘打开）：可视化查看座位/等待队列，底部按钮加入或退出队列、
 * 开始对局（管理员或队首）。管理员（2 级权限）点击座位行：等待阶段 = 安排/取消该座位的 AI 预约，
 * 对局中 = 踢出该座位玩家（AI 接管继续打）。数据来自服务端广播的 Sync（操作后立即重播）。
 */
public class MahjongLobbyScreen extends Screen {
	private static final int PANEL_W = 230;
	private static final int ROW_H = 20;
	private static final int QUEUE_ROWS = 5;

	private static final int PHASE_WAITING = 0, PHASE_PLAYING = 1, PHASE_FINISHED = 2;

	/** AI 座位角色显示键（顺序与 MahjongLobby.AVATAR_MODES 一致：默认 > 八木唯 > 琳琅 > 福姬 > 瓦西子 > 赤木 > 一姬 > 桔螈） */
	private static final String[] AVATAR_KEYS = { "gui.riichi_arise.lobby.ch_default", "gui.riichi_arise.lobby.ch_bamuwei",
			"gui.riichi_arise.lobby.ch_linlang", "gui.riichi_arise.lobby.ch_fuji", "gui.riichi_arise.lobby.ch_washizu",
			"gui.riichi_arise.lobby.ch_akagi", "gui.riichi_arise.lobby.ch_yiji",
			"gui.riichi_arise.lobby.ch_oraxolotl" };

	private final BlockPos origin;
	/** 最近一次服务端同步（未收到前显示加载中） */
	private MahjongLobbyPayloads.LobbySync sync;
	/** sync 首次到达时重建底部按钮（按钮语义取决于是否已在队列） */
	private boolean builtButtons;

	public MahjongLobbyScreen(BlockPos origin) {
		super(Component.translatable("gui.riichi_arise.lobby.title"));
		this.origin = origin;
	}

	public static void open(BlockPos origin) {
		Minecraft.getInstance().setScreen(new MahjongLobbyScreen(origin));
	}

	/** S→C OpenMessage 入口（仅客户端执行；把 Screen 相关逻辑收在客户端类，避免专用服务端类校验加载 Screen）：
	 *  该桌已被雀魂连接接管时改开连接控制面板，否则在无已打开界面时打开大厅管理界面。 */
	public static void openFromNetwork(BlockPos origin) {
		MahjongLobbyPayloads.NetState state = MahjongLobbyPayloads.CLIENT_NET.get(origin.asLong());
		if (state != null && state.takeover())
			RichiNetScreen.open(origin);
		else if (Minecraft.getInstance().screen == null)
			open(origin);
	}

	private void refresh() {
		boolean had = this.sync != null;
		int oldType = this.sync != null ? this.sync.gameType() : -1;
		int oldPlayers = this.sync != null ? this.sync.playerCount() : -1;
		int oldThink = this.sync != null ? this.sync.thinkIdx() : -1;
		int oldSpeed = this.sync != null ? this.sync.aiSpeedIdx() : -1;
		boolean oldOpen = this.sync != null && this.sync.openHand();
		int oldRoom = this.sync != null ? this.sync.roomType() : -1;
		boolean oldInQueue = this.sync != null && !this.sync.queueNames().isEmpty()
				&& this.minecraft != null && this.minecraft.player != null
				&& this.sync.queueNames().contains(this.minecraft.player.getGameProfile().getName());
		this.sync = MahjongLobbyPayloads.CLIENT_SYNC.get(origin.asLong());
		boolean newInQueue = this.sync != null && !this.sync.queueNames().isEmpty()
				&& this.minecraft != null && this.minecraft.player != null
				&& this.sync.queueNames().contains(this.minecraft.player.getGameProfile().getName());
		if (!had && this.sync != null || (this.sync != null && this.sync.gameType() != oldType)
				|| (this.sync != null && this.sync.playerCount() != oldPlayers)
				|| (this.sync != null && this.sync.thinkIdx() != oldThink)
				|| (this.sync != null && this.sync.aiSpeedIdx() != oldSpeed)
				|| (this.sync != null && newInQueue != oldInQueue)
				|| (this.sync != null && this.sync.openHand() != oldOpen)
				|| (this.sync != null && this.sync.roomType() != oldRoom))
			rebuildButtons();
	}

	private void rebuildButtons() {
		clearWidgets();
		init(this.minecraft, this.width, this.height);
	}

	private boolean inQueue() {
		var player = this.minecraft != null ? this.minecraft.player : null;
		if (player == null || this.sync == null)
			return false;
		String name = player.getGameProfile().getName();
		return this.sync.queueNames().stream().anyMatch(name::equals);
	}

	private void send(int action, int extra) {
		net.neoforged.neoforge.network.PacketDistributor.sendToServer(
				new MahjongLobbyPayloads.ActionMessage(origin.asLong(), action, extra));
	}

	// ==================================================================
	// 灰色半透明按钮（与座位/列表行同款样式，替代原版 Button）
	// ==================================================================

	/** rightAction：右键点击行为（null = 与左键相同，仅打开雀魂连接控制面板的按钮使用） */
	private record UiBtn(int x, int y, int w, int h, java.util.function.Supplier<String> label, Runnable action,
			Runnable rightAction) {
	}

	private final java.util.List<UiBtn> btns = new java.util.ArrayList<>();

	/** 右键弹出的单项候选（label 已本地化，selected = 当前值） */
	private record SettingOption(String label, boolean selected, Runnable action) {
	}

	/** 游戏设置列表行（右侧停靠，样式仿座位列表：左黄标＋右白值，左键循环切换 / 右键弹出候选列表） */
	private record SettingRow(int x, int y, int w, int h, java.util.function.Supplier<String> label,
			java.util.function.Supplier<String> value, Runnable action, java.util.List<SettingOption> options) {
	}

	private final java.util.List<SettingRow> settingRows = new java.util.ArrayList<>();

	private void addSettingRow(int y, java.util.function.Supplier<String> label,
			java.util.function.Supplier<String> value, Runnable action) {
		addSettingRow(y, label, value, action, null);
	}

	private void addSettingRow(int y, java.util.function.Supplier<String> label,
			java.util.function.Supplier<String> value, Runnable action, java.util.List<SettingOption> options) {
		int w = settingsW();
		this.settingRows.add(new SettingRow(settingsX(), y, w, ROW_H - 2, label, value, action, options));
	}

	/** 右键候选项构造器：label(i) 生成显示名；action(i) 生成选中后的操作 */
	private interface OptionLabel {
		String get(int index);
	}

	private interface OptionAction {
		Runnable get(int index);
	}

	private java.util.List<SettingOption> optionList(OptionLabel labels, int selected, OptionAction actions,
			int count) {
		java.util.List<SettingOption> list = new java.util.ArrayList<>(count);
		for (int i = 0; i < count; i++)
			list.add(new SettingOption(labels.get(i), i == selected, actions.get(i)));
		return list;
	}

	/** 右侧游戏设置列表宽度（随屏幕宽度收缩，最少 150） */
	private int settingsW() {
		return Math.min(180, Math.max(150, this.width / 4));
	}

	/** 右侧游戏设置列表停靠位置：屏幕右半边、与面板座位区顶对齐 */
	private int settingsX() {
		return this.width - settingsW() - 12;
	}

	private void addBtn(int x, int y, int w, int h, java.util.function.Supplier<String> label, Runnable action) {
		this.btns.add(new UiBtn(x, y, w, h, label, action, null));
	}

	/** 带右键行为的按钮：右键触发 rightAction（"查看牌谱"右键 = 打开雀魂连接控制面板） */
	private void addBtn(int x, int y, int w, int h, java.util.function.Supplier<String> label, Runnable action,
			Runnable rightAction) {
		this.btns.add(new UiBtn(x, y, w, h, label, action, rightAction));
	}

	@Override
	protected void init() {
		refresh();
		this.btns.clear();
		if (this.sync == null)
			return;
		int left = this.width / 2 - PANEL_W / 2;
		int top = this.height / 2 - 100;
		int colW = (PANEL_W - 12) / 2; // 左右两列等宽（列间隙 12）
		int colLX = left + 4;
		int queueTop = top + 4 * ROW_H + 6;
		// 两列内容起点：队列头行下方
		int y = queueTop + 12 + QUEUE_ROWS * 12 + 2;
		// 屏幕左下角停靠：设置 / 牌谱查看器（面板外，不随左列按钮流）
		int dockX = 12, dockW = 100, dockH = 18, dockBottom = this.height - dockH - 12;
		this.settingRows.clear();
		if (this.sync.phase() == PHASE_PLAYING) {
			// 对局中：投票结束对局（旧操作全部无效）
			addBtn(colLX, y, 130, 18,
					() -> Component.translatable("gui.riichi_arise.lobby.btn_vote_end").getString(),
					() -> {
						send(MahjongLobbyPayloads.ACT_VOTE_END, 0);
						onClose(); // 投票后关闭界面，避免挡住桌面
					});
			addBtn(colLX + 134, y, colW - 26, 18,
					() -> Component.translatable("gui.riichi_arise.lobby.btn_close").getString(), this::onClose);
			// 设置：对局中同样可打开（内容里的客户端配置切换后立即重建牌桌生效）
			addBtn(dockX, dockBottom, dockW, dockH,
					() -> Component.translatable("gui.riichi_arise.lobby.btn_settings").getString(),
					() -> RichiSettingsScreen.open(this.origin));
			return;
		}
		// 左列：加入/退出队列、开始对局（牌谱/设置改停靠屏幕左下角）
		addBtn(colLX, y, colW, 18,
				() -> Component.translatable(inQueue() ? "gui.riichi_arise.lobby.btn_leave" : "gui.riichi_arise.lobby.btn_join")
						.getString(),
				() -> send(MahjongLobbyPayloads.ACT_JOIN_QUEUE, 0));
		addBtn(colLX, y + 20, colW, 18,
				() -> Component.translatable("gui.riichi_arise.lobby.btn_start").getString(),
				() -> {
					send(MahjongLobbyPayloads.ACT_START, 0);
					onClose(); // 开局后关闭界面，直接看发牌
				});
		boolean finished = this.sync.canManage() && this.sync.phase() == PHASE_FINISHED;
		// 屏幕左下角停靠：查看牌谱（左键 = 牌谱列表；右键 = 雀魂连接控制面板）、设置
		addBtn(dockX, dockBottom - 22, dockW, dockH,
				() -> Component.translatable("gui.riichi_arise.lobby.btn_paipu").getString(),
				PaipuListScreen::open, () -> RichiNetScreen.open(this.origin));
		addBtn(dockX, dockBottom, dockW, dockH,
				() -> Component.translatable("gui.riichi_arise.lobby.btn_settings").getString(),
				() -> RichiSettingsScreen.open(this.origin));
		// 右侧游戏设置列表（管理员·等待/终局未清理阶段可改；对局中不可调整，整行点击循环切换）
		boolean adminSettings = this.sync.canManage() && this.sync.phase() != PHASE_PLAYING;
		if (adminSettings) {
			String[] typeKeys = { "gui.riichi_arise.lobby.type_single", "gui.riichi_arise.lobby.type_east", "gui.riichi_arise.lobby.type_full" };
			String[] thinkPresets = { "5+20", "10+30", "60+0", "3+5" };
			String[] roomKeys = { "gui.riichi_arise.lobby.room_tie", "gui.riichi_arise.lobby.room_diamond",
					"gui.riichi_arise.lobby.room_amethyst", "gui.riichi_arise.lobby.room_glass",
					"gui.riichi_arise.lobby.room_lazurite", "gui.riichi_arise.lobby.room_chaos" };
			// 房间按钮显示顺序 → 实际 roomType 编码（编码保持不变，避免存档/协议含义变化）
			int[] roomOrder = { mcr.riichi_arise.game.RichiTableState.ROOM_TIE,
					mcr.riichi_arise.game.RichiTableState.ROOM_DIAMOND,
					mcr.riichi_arise.game.RichiTableState.ROOM_AMETHYST,
					mcr.riichi_arise.game.RichiTableState.ROOM_GLASS,
					mcr.riichi_arise.game.RichiTableState.ROOM_LAZURITE,
					mcr.riichi_arise.game.RichiTableState.ROOM_CHAOS };
			String[] speedKeys = { "gui.riichi_arise.lobby.speed_fast", "gui.riichi_arise.lobby.speed_mid", "gui.riichi_arise.lobby.speed_slow" };
			int row = 0;
			// 当前房间在显示顺序中的下标（找不到时退回第一项）
			int roomIdx = -1;
			for (int i = 0; i < roomOrder.length; i++)
				if (roomOrder[i] == Math.floorMod(this.sync.roomType(), 6)) {
					roomIdx = i;
					break;
				}
			final int curRoom = roomIdx < 0 ? 0 : roomIdx;
			int curType = Math.floorMod(this.sync.gameType(), 3);
			int curThink = Math.floorMod(this.sync.thinkIdx(), 4);
			int curSpeed = Math.floorMod(this.sync.aiSpeedIdx(), 3);
			// 房间类型：铁之间 → 钻石之间 → 紫水晶之间 → 玻璃之间 → 青金石之间 → 混沌之间（左键循环；右键弹出全部候选，开局时生效）
			addSettingRow(top + row++ * ROW_H,
					() -> Component.translatable("gui.riichi_arise.lobby.label_room").getString(),
					() -> Component.translatable(roomKeys[curRoom]).getString(),
					() -> send(MahjongLobbyPayloads.ACT_SET_ROOM, roomOrder[(curRoom + 1) % 6]),
					optionList((i) -> Component.translatable(roomKeys[i]).getString(), curRoom,
							(i) -> () -> send(MahjongLobbyPayloads.ACT_SET_ROOM, roomOrder[i]), 6));
			// 对局人数：四人 / 三人（队列人数超过目标人数时服务端拒绝）
			addSettingRow(top + row++ * ROW_H,
					() -> Component.translatable("gui.riichi_arise.lobby.label_players").getString(),
					() -> Component.translatable(this.sync.playerCount() == 3
							? "gui.riichi_arise.lobby.players_3" : "gui.riichi_arise.lobby.players_4").getString(),
					() -> send(MahjongLobbyPayloads.ACT_SET_PLAYERS, this.sync.playerCount() == 3 ? 4 : 3),
					optionList((i) -> Component.translatable(i == 0 ? "gui.riichi_arise.lobby.players_4"
							: "gui.riichi_arise.lobby.players_3").getString(),
							this.sync.playerCount() == 3 ? 1 : 0,
							(i) -> () -> send(MahjongLobbyPayloads.ACT_SET_PLAYERS, i == 0 ? 4 : 3), 2));
			addSettingRow(top + row++ * ROW_H,
					() -> Component.translatable("gui.riichi_arise.lobby.label_type").getString(),
					() -> Component.translatable(typeKeys[Math.floorMod(this.sync.gameType(), 3)]).getString(),
					() -> send(MahjongLobbyPayloads.ACT_SET_TYPE,
							(Math.floorMod(this.sync.gameType(), 3) + 1) % 3),
					optionList((i) -> Component.translatable(typeKeys[i]).getString(), curType,
							(i) -> () -> send(MahjongLobbyPayloads.ACT_SET_TYPE, i), 3));
			// 思考时间档位循环：5+20 → 10+30 → 60+0 → 3+5
			addSettingRow(top + row++ * ROW_H,
					() -> Component.translatable("gui.riichi_arise.lobby.label_think").getString(),
					() -> thinkPresets[Math.floorMod(this.sync.thinkIdx(), 4)],
					() -> send(MahjongLobbyPayloads.ACT_SET_THINK,
							(Math.floorMod(this.sync.thinkIdx(), 4) + 1) % 4),
					optionList((i) -> thinkPresets[i], curThink,
							(i) -> () -> send(MahjongLobbyPayloads.ACT_SET_THINK, i), 4));
			addSettingRow(top + row++ * ROW_H,
					() -> Component.translatable("gui.riichi_arise.lobby.label_speed").getString(),
					() -> Component.translatable(speedKeys[Math.floorMod(this.sync.aiSpeedIdx(), 3)]).getString(),
					() -> send(MahjongLobbyPayloads.ACT_SET_SPEED,
							(Math.floorMod(this.sync.aiSpeedIdx(), 3) + 1) % 3),
					optionList((i) -> Component.translatable(speedKeys[i]).getString(), curSpeed,
							(i) -> () -> send(MahjongLobbyPayloads.ACT_SET_SPEED, i), 3));
			addSettingRow(top + row++ * ROW_H,
					() -> Component.translatable("gui.riichi_arise.lobby.label_open").getString(),
					() -> Component.translatable(this.sync.openHand() ? "gui.riichi_arise.lobby.open_on"
							: "gui.riichi_arise.lobby.open_off").getString(),
					() -> send(MahjongLobbyPayloads.ACT_SET_OPEN, this.sync.openHand() ? 0 : 1),
					optionList((i) -> Component.translatable(i == 1 ? "gui.riichi_arise.lobby.open_on"
							: "gui.riichi_arise.lobby.open_off").getString(), this.sync.openHand() ? 1 : 0,
							(i) -> () -> send(MahjongLobbyPayloads.ACT_SET_OPEN, i), 2));
			// 击飞（默认开）：有人点数 < 0 立即终局
			addSettingRow(top + row++ * ROW_H,
					() -> Component.translatable("gui.riichi_arise.lobby.label_tobi").getString(),
					() -> Component.translatable(this.sync.tobi() ? "gui.riichi_arise.lobby.rule_enabled"
							: "gui.riichi_arise.lobby.rule_disabled").getString(),
					() -> send(MahjongLobbyPayloads.ACT_SET_TOBI, this.sync.tobi() ? 0 : 1),
					optionList((i) -> Component.translatable(i == 1 ? "gui.riichi_arise.lobby.rule_enabled"
							: "gui.riichi_arise.lobby.rule_disabled").getString(), this.sync.tobi() ? 1 : 0,
							(i) -> () -> send(MahjongLobbyPayloads.ACT_SET_TOBI, i), 2));
			// 食替（默认关）：吃/碰后当巡不能打出所鸣的同一张牌
			addSettingRow(top + row++ * ROW_H,
					() -> Component.translatable("gui.riichi_arise.lobby.label_kuikae").getString(),
					() -> Component.translatable(this.sync.kuikae() ? "gui.riichi_arise.lobby.rule_enabled"
							: "gui.riichi_arise.lobby.rule_disabled").getString(),
					() -> send(MahjongLobbyPayloads.ACT_SET_KUIKAE, this.sync.kuikae() ? 0 : 1),
					optionList((i) -> Component.translatable(i == 1 ? "gui.riichi_arise.lobby.rule_enabled"
							: "gui.riichi_arise.lobby.rule_disabled").getString(), this.sync.kuikae() ? 1 : 0,
							(i) -> () -> send(MahjongLobbyPayloads.ACT_SET_KUIKAE, i), 2));
		}
		// 清理桌子：停靠屏幕右下角（面板外，不与对局设置挤占）；终局未清理时显示，清桌后回到等待阶段自动隐藏
		if (finished) {
			int cw = 100, ch = 18;
			addBtn(this.width - cw - 12, this.height - ch - 12, cw, ch,
					() -> Component.translatable("gui.riichi_arise.lobby.btn_clear").getString(),
					() -> send(MahjongLobbyPayloads.ACT_CLEAR, 0));
		}
	}

	@Override
	public void tick() {
		// 该桌已被雀魂连接接管：管理界面自动切换为连接控制面板（接管期间只保留该面板入口）
		MahjongLobbyPayloads.NetState net = MahjongLobbyPayloads.CLIENT_NET.get(this.origin.asLong());
		if (net != null && net.takeover()) {
			RichiNetScreen.open(this.origin);
			return;
		}
		refresh();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		var font = this.font;
		int left = this.width / 2 - PANEL_W / 2;
		int top = this.height / 2 - 100;
		int colW = (PANEL_W - 12) / 2;
		int colLX = left + 4;
		int queueTop = top + 4 * ROW_H + 6;

		graphics.drawCenteredString(font, this.title, this.width / 2, top - 14, 0xFFFFFF);
		if (this.sync == null) {
			graphics.drawCenteredString(font, "...", this.width / 2, top + 20, 0xAAAAAA);
			return;
		}

		// 座位区（等待阶段显示 AI 预约；对局中显示名字 + [AI]）；三麻只显示前 3 席（座位 4 不存在）
		String hoverHint = null;
		for (int seat = 0; seat < seatRows(); seat++) {
			int y = top + seat * ROW_H;
			boolean hovered = this.sync.canManage() && mouseX >= left && mouseX < left + PANEL_W
					&& mouseY >= y && mouseY < y + ROW_H - 2;
			graphics.fill(left, y, left + PANEL_W, y + ROW_H - 2,
					hovered ? 0x509090C0 : 0x30A0A0A0);
			String seatLabel = Component.translatable("gui.riichi_arise.lobby.seat", seat + 1).getString();
			graphics.drawString(font, seatLabel, left + 6, y + 6, 0xFFFFA0, false);
			String name = this.sync.seatNames()[seat];
			String extra = "";
			if (name == null || name.isEmpty()) {
				name = this.sync.seatAI()[seat]
						? Component.translatable("gui.riichi_arise.lobby.ai_reserved").getString()
						: Component.translatable("gui.riichi_arise.lobby.empty").getString();
			}
			if (this.sync.seatAI()[seat]) {
				// AI 座位：名字旁标注该座位当前所选角色
				extra = " [AI:" + seatAvatarLabel(this.sync.seatAvatarModes()[seat]) + "]";
			}
			graphics.drawString(font, name + extra, left + 64, y + 6, 0xFFFFFF, false);
			// 悬停提示（仅等待/终局阶段：空座预约 AI / 已预约座位循环形象）
			if (hovered && this.sync.phase() != PHASE_PLAYING)
				hoverHint = Component.translatable(
						this.sync.seatAI()[seat] ? "gui.riichi_arise.lobby.hint_avatar_cycle" : "gui.riichi_arise.lobby.hint_reserve")
						.getString();
		}
		if (hoverHint != null) {
			// 悬浮信息框（跟随鼠标，样式仿原版 tooltip）
			int tw = font.width(hoverHint) + 10;
			int tx = Math.min(mouseX + 14, this.width - tw - 2);
			int ty = Math.max(mouseY - 16, 4);
			graphics.fill(tx, ty, tx + tw, ty + 18, 0xF0100018);
			graphics.renderOutline(tx, ty, tw, 18, 0x505000FF);
			graphics.drawString(font, hoverHint, tx + 5, ty + 5, 0xFFFFFF, false);
		}

		// 队列区（左半边）
		graphics.drawString(font,
				Component.translatable("gui.riichi_arise.lobby.queue",
					this.sync.queueNames().size(), this.sync.playerCount()).getString(),
				colLX, queueTop, 0xAAAAAA, false);
		for (int i = 0; i < QUEUE_ROWS; i++) {
			String name = i < this.sync.queueNames().size() ? this.sync.queueNames().get(i) : null;
			if (name != null)
				graphics.drawString(font, (i + 1) + ". " + name, colLX + 8,
						queueTop + 12 + i * 12, 0xFFFFFF, false);
		}
		// 游戏设置列表（右侧停靠，样式仿座位列表：左黄标＋右白值）
		if (!this.settingRows.isEmpty()) {
			graphics.drawString(font,
					Component.translatable("gui.riichi_arise.lobby.settings").getString(),
					settingsX() + 6, top - 12, 0xAAAAAA, false);
			for (SettingRow r : this.settingRows) {
				boolean hov = mouseX >= r.x() && mouseX < r.x() + r.w() && mouseY >= r.y() && mouseY < r.y() + r.h();
				graphics.fill(r.x(), r.y(), r.x() + r.w(), r.y() + r.h(), hov ? 0x509090C0 : 0x30A0A0A0);
				graphics.drawString(font, r.label().get(), r.x() + 6, r.y() + 6, 0xFFFFA0, false);
				graphics.drawString(font, r.value().get(), r.x() + 80, r.y() + 6, 0xFFFFFF, false);
			}
		}

		// 灰色半透明按钮（与列表行同款样式：悬停高亮）
		for (UiBtn b : this.btns) {
			boolean hov = mouseX >= b.x() && mouseX < b.x() + b.w() && mouseY >= b.y() && mouseY < b.y() + b.h();
			graphics.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), hov ? 0x509090C0 : 0x30A0A0A0);
			String label = b.label().get();
			graphics.drawString(font, label, b.x() + (b.w() - font.width(label)) / 2, b.y() + 5, 0xFFFFFF, false);
		}

		// 阶段提示：对局中 = 投票进度（面板居中，投票按钮下方）；终局 = 右列（设置/清理桌子下方；非管理员在右列顶部）
		String phase = switch (this.sync.phase()) {
			case PHASE_PLAYING -> Component.translatable("gui.riichi_arise.lobby.vote",
					this.sync.voteCount(), humanSeats()).getString();
			case PHASE_FINISHED -> Component.translatable("gui.riichi_arise.lobby.finished").getString();
			default -> "";
		};
		if (!phase.isEmpty()) {
			if (this.sync.phase() == PHASE_PLAYING)
				graphics.drawCenteredString(font, phase, this.width / 2, queueTop + 96, 0xFFAA55);
			else
				// 终局：管理员显示在右侧设置列表下方；非管理员显示在右半区顶部
				graphics.drawCenteredString(font, phase, settingsX() + settingsW() / 2,
						this.settingRows.isEmpty() ? top + 4 : top + 8 * ROW_H + 6, 0xFFAA55);
		}

		// 座位 AI 类型快速选择列表（最后绘制，盖住队列/按钮；右键座位弹出，显示顺序见 pickerOrder）
		if (this.pickerSeat >= 0 && this.pickerSeat < seatRows()
				&& this.sync.seatAI()[this.pickerSeat] && this.sync.phase() != PHASE_PLAYING) {
			int px = left + 64, py = pickerTop();
			int[] order = pickerOrder();
			graphics.fill(px - 4, py, px + PICK_W + 4, py + pickerH(), 0xFF101018); // 背景不透明
			graphics.renderOutline(px - 4, py, PICK_W + 8, pickerH(), 0x505000FF); // 描边
			String cur = this.sync.seatAvatarModes()[this.pickerSeat];
			for (int row = 0; row < order.length; row++) {
				int mode = order[row]; // -1 = 移除 AI；否则 = AVATAR_MODES（角色）下标
				int ey = py + 3 + row * PICK_ROW;
				int eyBottom = ey + PICK_ROW;
				if (mouseY >= ey && mouseY < eyBottom)
					graphics.fill(px - 2, ey, px + PICK_W + 2, eyBottom, 0x405088FF);
				String label;
				int color;
				if (mode < 0) {
					label = Component.translatable("gui.riichi_arise.lobby.picker_remove").getString();
					color = 0xFF8080;
				} else {
					// 防御：角色键与 AVATAR_MODES 数量不一致（如残留旧编译产物）时不越界
					String key = mode < AVATAR_KEYS.length ? AVATAR_KEYS[mode] : AVATAR_KEYS[0];
					label = Component.translatable(key).getString();
					color = mode < mcr.riichi_arise.game.MahjongLobby.AVATAR_MODES.length
							&& mcr.riichi_arise.game.MahjongLobby.AVATAR_MODES[mode].equals(cur) ? 0xFFCC66 : 0xFFFFFF;
				}
				graphics.drawString(font, label, px + 2, ey + 2, color, false);
			}
		}
		// 右键游戏设置弹出的候选列表（与座位 AI 列表同款外观；最后绘制，盖住右侧列表）
		SettingRow picked = settingPickerTarget();
		if (picked != null) {
			int px = picked.x(), py = settingPickerTop(picked);
			int ph = picked.options().size() * SET_PICK_ROW + 6;
			graphics.fill(px - 4, py, px + picked.w() + 4, py + ph, 0xFF101018);
			graphics.renderOutline(px - 4, py, picked.w() + 8, ph, 0x505000FF);
			for (int i = 0; i < picked.options().size(); i++) {
				SettingOption opt = picked.options().get(i);
				int ey = py + 3 + i * SET_PICK_ROW;
				int eyBottom = ey + SET_PICK_ROW;
				if (mouseX >= px - 2 && mouseX < px + picked.w() + 2 && mouseY >= ey && mouseY < eyBottom)
					graphics.fill(px - 2, ey, px + picked.w() + 2, eyBottom, 0x405088FF);
				String label = (opt.selected() ? "✔ " : "  ") + opt.label();
				graphics.drawString(font, label, px + 2, ey + 2, opt.selected() ? 0xFFCC66 : 0xFFFFFF, false);
			}
		}
	}

	/** 当前右键候选列表对应的设置行（无候选 / 行已重建时返回 null） */
	private SettingRow settingPickerTarget() {
		if (this.settingPickerRow < 0 || this.settingPickerRow >= this.settingRows.size())
			return null;
		SettingRow r = this.settingRows.get(this.settingPickerRow);
		return r.options() == null || r.options().isEmpty() ? null : r;
	}

	/** 候选列表顶部：优先贴着该行下方，底部溢出时上移，保证整张列表不超出屏幕 */
	private int settingPickerTop(SettingRow r) {
		int h = r.options().size() * SET_PICK_ROW + 6;
		return Math.max(4, Math.min(r.y() + r.h(), this.height - h - 8));
	}

	/** 座位行数：四麻 4 行，三麻只显示前 3 席（座位 4 在该模式下不存在） */
	private int seatRows() {
		return this.sync == null ? 4 : Math.max(1, Math.min(4, this.sync.playerCount()));
	}

	/** 人类座位数（投票总数；AI 不参与投票） */
	private int humanSeats() {
		int n = 0;
		for (int i = 0; i < 4; i++)
			if (this.sync.seatNames()[i] != null && !this.sync.seatNames()[i].isEmpty() && !this.sync.seatAI()[i])
				n++;
		return Math.max(1, n);
	}

	/** 座位 AI 角色显示名：空/未知 = 默认 */
	private String seatAvatarLabel(String mode) {
		if (mode != null && !mode.isEmpty()) {
			for (int i = 0; i < mcr.riichi_arise.game.MahjongLobby.AVATAR_MODES.length; i++)
				if (mcr.riichi_arise.game.MahjongLobby.AVATAR_MODES[i].equals(mode))
					return Component.translatable(i < AVATAR_KEYS.length ? AVATAR_KEYS[i] : AVATAR_KEYS[0]).getString();
		}
		return Component.translatable(AVATAR_KEYS[0]).getString();
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (this.sync != null) {
			// 右键候选列表优先：点击条目 = 选中并关闭；点击列表外 = 直接关闭（不穿透到下层）
			if (this.settingPickerRow >= 0) {
				SettingRow r = settingPickerTarget();
				int px = r == null ? 0 : r.x(), py = r == null ? 0 : settingPickerTop(r);
				int rowIndex = r == null ? -1 : (int) ((mouseY - py - 3) / SET_PICK_ROW);
				if (r != null && mouseX >= px - 4 && mouseX < px + r.w() + 4
						&& rowIndex >= 0 && rowIndex < r.options().size()) {
					r.options().get(rowIndex).action().run();
				}
				this.settingPickerRow = -1;
				return true;
			}
			// 右侧游戏设置列表命中：左键循环切换；右键弹出全部候选列表
			if (this.pickerSeat < 0 && (button == 0 || button == 1)) {
				for (int i = 0; i < this.settingRows.size(); i++) {
					SettingRow r = this.settingRows.get(i);
					if (mouseX >= r.x() && mouseX < r.x() + r.w() && mouseY >= r.y() && mouseY < r.y() + r.h()) {
						if (button == 0)
							r.action().run();
						else if (r.options() != null && !r.options().isEmpty())
							this.settingPickerRow = i; // 右键：弹出候选列表
						return true;
					}
				}
			}
			// 灰色半透明按钮（列表样式）命中：左键 = 主行为；右键 = rightAction（无则忽略）
			if (this.pickerSeat < 0 && (button == 0 || button == 1)) {
				for (UiBtn b : this.btns) {
					if (mouseX >= b.x() && mouseX < b.x() + b.w() && mouseY >= b.y() && mouseY < b.y() + b.h()) {
						if (button == 0)
							b.action().run();
						else if (b.rightAction() != null)
							b.rightAction().run();
						return true;
					}
				}
			}
		}
		// 仅等待/终局阶段允许操作座位行（终局未点清理也可调整下一局 AI）：左击空座 = 预约 AI；
		// 右击已预约 AI 座位 = 打开角色选择列表（列表含角色 + 移除 AI）；左击已预约座位 = 循环切换角色
		if (this.sync != null && this.sync.canManage() && this.sync.phase() != PHASE_PLAYING) {
			int left = this.width / 2 - PANEL_W / 2;
			int top = this.height / 2 - 100;
			if (this.pickerSeat >= 0) {
				handlePickerClick(mouseX, mouseY);
				return true;
			}
			if (mouseX >= left && mouseX < left + PANEL_W && mouseY >= top && mouseY < top + seatRows() * ROW_H) {
				int seat = (int) ((mouseY - top) / ROW_H);
				if (button == 1 && this.sync.seatAI()[seat]) {
					this.pickerSeat = seat; // 右键：打开角色选择列表
					this.settingPickerRow = -1; // 与设置候选列表互斥
				} else if (button == 0 && this.sync.seatAI()[seat]) {
					// 左击循环：沿角色列表循环切换；未知（空）= 从默认开始
					String cur = this.sync.seatAvatarModes()[seat];
					int[] order = cycleOrder();
					int start = -1;
					for (int i = 0; i < order.length; i++)
						if (mcr.riichi_arise.game.MahjongLobby.AVATAR_MODES[order[i]].equals(cur)) {
							start = i;
							break;
						}
					if (start < 0)
						start = order.length - 1; // 未知：下一项落到「默认」（下标 0）
					sendEntry(seat, order[(start + 1) % order.length]);
				} else if (button == 0
						&& (this.sync.seatNames()[seat] == null || this.sync.seatNames()[seat].isEmpty())) {
					// 仅空座可预约（队列玩家占用的座位显示名字，不可替换为 AI）
					send(MahjongLobbyPayloads.ACT_ADD_AI, seat);
				}
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	// ==================================================================
	// 座位 AI 类型快速选择列表（右键座位弹出）
	// ==================================================================

	/** 弹出列表的座位（-1 = 关闭）；条目按 pickerOrder 显示 */
	private int pickerSeat = -1;
	private static final int PICK_ROW = 13, PICK_W = 140;

	/** 右键弹出的游戏设置候选列表：行下标（-1 = 关闭） */
	private int settingPickerRow = -1;
	private static final int SET_PICK_ROW = 13;

	/** 弹出列表显示顺序：移除AI > 默认 > 八木唯 > 琳琅 > 福姬 > 瓦西子 > 赤木（下标 = AVATAR_MODES 下标） */
	private static int[] pickerOrder() {
		int n = mcr.riichi_arise.game.MahjongLobby.AVATAR_MODES.length;
		int[] order = new int[n + 1];
		order[0] = -1; // 移除 AI
		for (int i = 0; i < n; i++)
			order[i + 1] = i;
		return order;
	}

	/** 左击循环条目（去掉开头的移除项）：默认 > 八木唯 > 琳琅 > 福姬 > 瓦西子 > 赤木 */
	private static int[] cycleOrder() {
		int[] order = pickerOrder();
		int[] c = new int[order.length - 1];
		System.arraycopy(order, 1, c, 0, c.length);
		return c;
	}

	/** 按条目编码发送：-1 = 移除 AI；其余 = 角色下标（ACT_SET_AVATAR_SEAT） */
	private void sendEntry(int seat, int entry) {
		if (entry < 0)
			send(MahjongLobbyPayloads.ACT_REMOVE_AI, seat);
		else
			send(MahjongLobbyPayloads.ACT_SET_AVATAR_SEAT, (seat << 16) | entry);
	}

	/** 列表几何（顶部对齐座位行下方，底部溢出时上移） */
	private int pickerTop() {
		int seatY = this.height / 2 - 100 + this.pickerSeat * ROW_H + ROW_H;
		return Math.min(seatY, this.height - pickerH() - 8);
	}

	private int pickerH() {
		return pickerOrder().length * PICK_ROW + 6;
	}

	private void handlePickerClick(double mouseX, double mouseY) {
		int seat = this.pickerSeat;
		this.pickerSeat = -1;
		int px = this.width / 2 - PANEL_W / 2 + 64, py = pickerTopFor(seat);
		int[] order = pickerOrder();
		if (mouseX >= px && mouseX < px + PICK_W && mouseY >= py) {
			int row = (int) ((mouseY - py - 3) / PICK_ROW);
			if (row >= 0 && row < order.length)
				sendEntry(seat, order[row]); // -1 移除 AI / 风格 → 随机形象 / 其余直接设形态
		}
	}

	private int pickerTopFor(int seat) {
		int saved = this.pickerSeat;
		this.pickerSeat = seat;
		int v = pickerTop();
		this.pickerSeat = saved;
		return v;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
