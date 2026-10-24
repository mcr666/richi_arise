package mcr.richi.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import mcr.richi.network.MahjongLobbyPayloads;

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

	/** AI 形象模式显示键（顺序与 MahjongLobby.AVATAR_MODES 一致：随机 > 人类 > 各形态） */
	private static final String[] AVATAR_KEYS = { "gui.riichi_arise.lobby.av_random", "gui.riichi_arise.lobby.av_human",
			"gui.riichi_arise.lobby.av_spring_flower", "gui.riichi_arise.lobby.av_chen_huang", "gui.riichi_arise.lobby.av_moss_beast",
			"gui.riichi_arise.lobby.av_silkmoth", "gui.riichi_arise.lobby.av_komainu", "gui.riichi_arise.lobby.av_wen_yao",
			"gui.riichi_arise.lobby.av_ferocious", "gui.riichi_arise.lobby.av_dark_fury", "gui.riichi_arise.lobby.av_villager" };

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

	private void refresh() {
		boolean had = this.sync != null;
		int oldType = this.sync != null ? this.sync.gameType() : -1;
		int oldThink = this.sync != null ? this.sync.thinkIdx() : -1;
		int oldSpeed = this.sync != null ? this.sync.aiSpeedIdx() : -1;
		boolean oldOpen = this.sync != null && this.sync.openHand();
		boolean oldInQueue = this.sync != null && !this.sync.queueNames().isEmpty()
				&& this.minecraft != null && this.minecraft.player != null
				&& this.sync.queueNames().contains(this.minecraft.player.getGameProfile().getName());
		this.sync = MahjongLobbyPayloads.CLIENT_SYNC.get(origin.asLong());
		boolean newInQueue = this.sync != null && !this.sync.queueNames().isEmpty()
				&& this.minecraft != null && this.minecraft.player != null
				&& this.sync.queueNames().contains(this.minecraft.player.getGameProfile().getName());
		if (!had && this.sync != null || (this.sync != null && this.sync.gameType() != oldType)
				|| (this.sync != null && this.sync.thinkIdx() != oldThink)
				|| (this.sync != null && this.sync.aiSpeedIdx() != oldSpeed)
				|| (this.sync != null && newInQueue != oldInQueue)
				|| (this.sync != null && this.sync.openHand() != oldOpen))
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

	private record UiBtn(int x, int y, int w, int h, java.util.function.Supplier<String> label, Runnable action) {
	}

	private final java.util.List<UiBtn> btns = new java.util.ArrayList<>();

	private void addBtn(int x, int y, int w, int h, java.util.function.Supplier<String> label, Runnable action) {
		this.btns.add(new UiBtn(x, y, w, h, label, action));
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
		int colLX = left + 4, colRX = left + PANEL_W - 4 - colW;
		int queueTop = top + 4 * ROW_H + 6;
		// 两列内容起点：队列头行下方
		int y = queueTop + 12 + QUEUE_ROWS * 12 + 2;
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
			return;
		}
		// 左列：加入/退出队列、开始对局、查看牌谱
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
		addBtn(colLX, y + 40, colW, 18,
				() -> Component.translatable("gui.riichi_arise.lobby.btn_paipu").getString(),
				PaipuListScreen::open);
		// 个人设置：固定停靠面板左下角（不随左列按钮流，独立于队列/座位布局）
		addBtn(colLX, top + 220, colW, 18,
				() -> Component.translatable("gui.riichi_arise.lobby.btn_voice").getString(),
				() -> VoiceSettingsScreen.open(this.origin));
		// 右列：游戏设置列表（管理员·等待/终局未清理阶段可改；对局中不可调整，点击整行循环切换）
		boolean adminSettings = this.sync.canManage() && this.sync.phase() != PHASE_PLAYING;
		if (adminSettings) {
			String[] typeKeys = { "gui.riichi_arise.lobby.type_single", "gui.riichi_arise.lobby.type_east", "gui.riichi_arise.lobby.type_full" };
			int type = Math.floorMod(this.sync.gameType(), 3);
			addBtn(colRX, queueTop + 12, colW, 18,
					() -> Component.translatable("gui.riichi_arise.lobby.btn_type",
							Component.translatable(typeKeys[Math.floorMod(this.sync.gameType(), 3)]).getString())
							.getString(),
					() -> send(MahjongLobbyPayloads.ACT_SET_TYPE,
							(Math.floorMod(this.sync.gameType(), 3) + 1) % 3));
			// 思考时间档位循环：5+20 → 10+30 → 60+0 → 3+5
			String[] thinkPresets = { "5+20", "10+30", "60+0", "3+5" };
			addBtn(colRX, queueTop + 32, colW, 18,
					() -> Component.translatable("gui.riichi_arise.lobby.btn_think",
							thinkPresets[Math.floorMod(this.sync.thinkIdx(), 4)]).getString(),
					() -> send(MahjongLobbyPayloads.ACT_SET_THINK,
							(Math.floorMod(this.sync.thinkIdx(), 4) + 1) % 4));
			String[] speedKeys = { "gui.riichi_arise.lobby.speed_fast", "gui.riichi_arise.lobby.speed_mid", "gui.riichi_arise.lobby.speed_slow" };
			addBtn(colRX, queueTop + 52, colW, 18,
					() -> Component.translatable("gui.riichi_arise.lobby.btn_speed",
							Component.translatable(speedKeys[Math.floorMod(this.sync.aiSpeedIdx(), 3)]).getString())
							.getString(),
					() -> send(MahjongLobbyPayloads.ACT_SET_SPEED,
							(Math.floorMod(this.sync.aiSpeedIdx(), 3) + 1) % 3));
			addBtn(colRX, queueTop + 72, colW, 18,
					() -> Component.translatable("gui.riichi_arise.lobby.btn_open",
							Component.translatable(this.sync.openHand() ? "gui.riichi_arise.lobby.open_on"
									: "gui.riichi_arise.lobby.open_off").getString()).getString(),
					() -> send(MahjongLobbyPayloads.ACT_SET_OPEN, this.sync.openHand() ? 0 : 1));
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
		refresh();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		var font = this.font;
		int left = this.width / 2 - PANEL_W / 2;
		int top = this.height / 2 - 100;
		int colW = (PANEL_W - 12) / 2;
		int colLX = left + 4, colRX = left + PANEL_W - 4 - colW;
		int queueTop = top + 4 * ROW_H + 6;

		graphics.drawCenteredString(font, this.title, this.width / 2, top - 14, 0xFFFFFF);
		if (this.sync == null) {
			graphics.drawCenteredString(font, "...", this.width / 2, top + 20, 0xAAAAAA);
			return;
		}

		// 座位区（等待阶段显示 AI 预约；对局中显示名字 + [AI]）
		// 管理悬浮提示（等待阶段）：记录悬停座位，循环结束后在鼠标旁绘制悬浮框
		String hoverHint = null;
		for (int seat = 0; seat < 4; seat++) {
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
				// AI 座位：名字旁显示该座位当前独立形象模式（空 = 跟随全局），行右侧显示对应流派
				extra = " [AI:" + seatAvatarLabel(this.sync.seatAvatarModes()[seat]) + "]";
			}
			graphics.drawString(font, name + extra, left + 64, y + 6, 0xFFFFFF, false);
			if (this.sync.seatAI()[seat]) {
				// 流派显示在面板外的右侧，避免与名字重叠
				String flow = flowLabel(this.sync.seatAvatarModes()[seat]);
				graphics.drawString(font, flow, left + PANEL_W + 8, y + 6, 0x80C8C8, false);
			}
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
						this.sync.queueNames().size(), 4).getString(),
				colLX, queueTop, 0xAAAAAA, false);
		for (int i = 0; i < QUEUE_ROWS; i++) {
			String name = i < this.sync.queueNames().size() ? this.sync.queueNames().get(i) : null;
			if (name != null)
				graphics.drawString(font, (i + 1) + ". " + name, colLX + 8,
						queueTop + 12 + i * 12, 0xFFFFFF, false);
		}
		// 游戏设置列表头（右半边，仅管理员）
		if (this.sync.canManage() && this.sync.phase() != PHASE_PLAYING)
			graphics.drawString(font,
					Component.translatable("gui.riichi_arise.lobby.settings").getString(),
					colRX, queueTop, 0xAAAAAA, false);

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
				// 终局：设置列表下方（终局未清理也可调整对局设置）
				graphics.drawCenteredString(font, phase, colRX + colW / 2,
						queueTop + 116, 0xFFAA55);
		}

		// 座位 AI 类型快速选择列表（最后绘制，盖住队列/按钮；右键座位弹出，显示顺序见 pickerOrder）
		if (this.pickerSeat >= 0 && this.sync.seatAI()[this.pickerSeat] && this.sync.phase() != PHASE_PLAYING) {
			int px = left + 64, py = pickerTop();
			int[] order = pickerOrder();
			graphics.fill(px - 4, py, px + PICK_W + 4, py + pickerH(), 0xFF101018); // 背景不透明
			graphics.renderOutline(px - 4, py, PICK_W + 8, pickerH(), 0x505000FF); // 描边
			String cur = this.sync.seatAvatarModes()[this.pickerSeat];
			for (int row = 0; row < order.length; row++) {
				int mode = order[row]; // -1 = 移除 AI；>= STYLE_OFFSET = 风格；否则 AVATAR_MODES 下标
				int ey = py + 3 + row * PICK_ROW;
				int eyBottom = ey + PICK_ROW;
				if (mouseY >= ey && mouseY < eyBottom)
					graphics.fill(px - 2, ey, px + PICK_W + 2, eyBottom, 0x405088FF);
				boolean isRemove = mode < 0;
				boolean isStyle = mode >= STYLE_OFFSET;
				String label;
				int color;
				if (isRemove) {
					label = Component.translatable("gui.riichi_arise.lobby.picker_remove").getString();
					color = 0xFF8080;
				} else if (isStyle) {
					int s = mode - STYLE_OFFSET;
					label = Component.translatable(STYLE_KEYS[s]).getString();
					color = styleMatches(s, cur) ? 0xFFCC66 : 0xFFFFFF;
				} else {
					label = Component.translatable(AVATAR_KEYS[mode]).getString();
					color = mcr.richi.game.MahjongLobby.AVATAR_MODES[mode].equals(cur) ? 0xFFCC66 : 0xFFFFFF;
				}
				graphics.drawString(font, label, px + 2, ey + 2, color, false);
			}
		}
	}

	/** 人类座位数（投票总数；AI 不参与投票） */
	private int humanSeats() {
		int n = 0;
		for (int i = 0; i < 4; i++)
			if (this.sync.seatNames()[i] != null && !this.sync.seatNames()[i].isEmpty() && !this.sync.seatAI()[i])
				n++;
		return Math.max(1, n);
	}

	/** 座位独立 AI 形象模式显示名：null/空 = 随机，未知值也回退随机 */
	private String seatAvatarLabel(String mode) {
		if (mode != null && !mode.isEmpty()) {
			for (int i = 0; i < mcr.richi.game.MahjongLobby.AVATAR_MODES.length; i++)
				if (mcr.richi.game.MahjongLobby.AVATAR_MODES[i].equals(mode))
					return Component.translatable(AVATAR_KEYS[i]).getString();
		}
		return Component.translatable(AVATAR_KEYS[0]).getString();
	}

	/** 座位 AI 形象对应的流派名（预约阶段展示；随机/人类/村民 = 随机牌风，映射同 RiichiBot.flowOfAvatar） */
	private String flowLabel(String mode) {
		String key = switch (mode == null ? "" : mode) {
			case "chen_huang" -> "flow_general";
			case "spring_flower" -> "flow_boar";
			case "moss_beast" -> "flow_quiet";
			case "wen_yao" -> "flow_musou";
			case "komainu" -> "flow_kouten";
			case "ferocious" -> "flow_kouten";
			case "dark_fury" -> "flow_kouten";
			case "silkmoth" -> "flow_flash";
			default -> "flow_random";
		};
		return Component.translatable("gui.riichi_arise.lobby." + key).getString();
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (this.sync != null) {
			// 灰色半透明按钮（列表样式）命中
			if (this.pickerSeat < 0 && button == 0) {
				for (UiBtn b : this.btns) {
					if (mouseX >= b.x() && mouseX < b.x() + b.w() && mouseY >= b.y() && mouseY < b.y() + b.h()) {
						b.action().run();
						return true;
					}
				}
			}
		}
		// 仅等待/终局阶段允许操作座位行（终局未点清理也可调整下一局 AI）：左击空座 = 预约 AI；
		// 右击已预约 AI 座位 = 打开类型快速选择列表（列表含全部风格/形象 + 移除 AI）；左击已预约座位 = 循环切换
		if (this.sync != null && this.sync.canManage() && this.sync.phase() != PHASE_PLAYING) {
			int left = this.width / 2 - PANEL_W / 2;
			int top = this.height / 2 - 100;
			if (this.pickerSeat >= 0) {
				handlePickerClick(mouseX, mouseY);
				return true;
			}
			if (mouseX >= left && mouseX < left + PANEL_W && mouseY >= top && mouseY < top + 4 * ROW_H) {
				int seat = (int) ((mouseY - top) / ROW_H);
				if (button == 1 && this.sync.seatAI()[seat]) {
					this.pickerSeat = seat; // 右键：打开快速选择列表
				} else if (button == 0 && this.sync.seatAI()[seat]) {
					// 左击循环：沿选择列表（人类/村民/随机/风格…）循环切换；未知（随机/空）= 从随机开始
					String cur = this.sync.seatAvatarModes()[seat];
					int[] order = cycleOrder();
					int start = 0;
					boolean matched = false;
					for (int i = 0; i < order.length; i++)
						if (order[i] >= STYLE_OFFSET ? styleMatches(order[i] - STYLE_OFFSET, cur)
								: mcr.richi.game.MahjongLobby.AVATAR_MODES[order[i]].equals(cur)) {
							start = i;
							matched = true;
							break;
						}
					if (!matched) // 随机/未知：定位「随机」条目为起点
						for (int i = 0; i < order.length; i++)
							if (order[i] >= 0 && order[i] < STYLE_OFFSET
									&& "random".equals(mcr.richi.game.MahjongLobby.AVATAR_MODES[order[i]])) {
								start = i;
								break;
							}
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
	private static final int PICK_ROW = 13, PICK_W = 128;
	/** 风格条目编码偏移：>= STYLE_OFFSET 的条目为风格（值 - STYLE_OFFSET = AVATAR_STYLES 下标） */
	private static final int STYLE_OFFSET = 1000;
	/** 风格显示键（与 MahjongLobby.AVATAR_STYLES 顺序一致：一般流/野猪流/门清流/御无双/魂天流/闪电流） */
	private static final String[] STYLE_KEYS = { "gui.riichi_arise.lobby.flow_general", "gui.riichi_arise.lobby.flow_boar",
			"gui.riichi_arise.lobby.flow_quiet", "gui.riichi_arise.lobby.flow_musou", "gui.riichi_arise.lobby.flow_kouten",
			"gui.riichi_arise.lobby.flow_flash" };

	/** 弹出列表显示顺序：移除AI > 人类 > 村民 > 随机 > 各风格（选择风格后随机从符合风格的形象中选一个） */
	private static int[] pickerOrder() {
		String[] modes = mcr.richi.game.MahjongLobby.AVATAR_MODES;
		int human = -1, villager = -1, random = -1;
		for (int i = 0; i < modes.length; i++) {
			switch (modes[i]) {
				case "human" -> human = i;
				case "villager" -> villager = i;
				case "random" -> random = i;
			}
		}
		int[] order = new int[3 + mcr.richi.game.MahjongLobby.AVATAR_STYLES.length + 1];
		int n = 0;
		order[n++] = -1; // 移除 AI
		if (human >= 0)
			order[n++] = human;
		if (villager >= 0)
			order[n++] = villager;
		if (random >= 0)
			order[n++] = random;
		for (int s = 0; s < mcr.richi.game.MahjongLobby.AVATAR_STYLES.length; s++)
			order[n++] = STYLE_OFFSET + s;
		return order;
	}

	/** 左击循环条目（去掉开头的移除项）：人类 > 村民 > 随机 > 各风格 */
	private static int[] cycleOrder() {
		int[] order = pickerOrder();
		int[] c = new int[order.length - 1];
		System.arraycopy(order, 1, c, 0, c.length);
		return c;
	}

	/** 当前形象是否属于风格 s（风格 = 该流派的可随机形象组） */
	private static boolean styleMatches(int s, String cur) {
		if (s < 0 || s >= mcr.richi.game.MahjongLobby.AVATAR_STYLES.length || cur == null)
			return false;
		for (String f : mcr.richi.game.MahjongLobby.AVATAR_STYLES[s])
			if (f.equals(cur))
				return true;
		return false;
	}

	/** 按条目编码发送：-1 = 移除 AI；风格 = ACT_SET_AVATAR_STYLE；其余 = ACT_SET_AVATAR_SEAT */
	private void sendEntry(int seat, int entry) {
		if (entry < 0)
			send(MahjongLobbyPayloads.ACT_REMOVE_AI, seat);
		else if (entry >= STYLE_OFFSET)
			send(MahjongLobbyPayloads.ACT_SET_AVATAR_STYLE, (seat << 16) | (entry - STYLE_OFFSET));
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
