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
	private static final int VISIBLE = 13;

	private final BlockPos origin;

	/** 行类型 */
	private static final int HEADER = 0, CLIENT_TOGGLE = 1, PERSONAL_VOICE = 2, WORLD_TOGGLE = 3, NET_TOGGLE = 4,
			CLIENT_CORNER = 5;

	private record Row(int kind, int cat, String labelKey, String value, boolean enabled, int extra) {
	}

	/** 三个类别是否展开 */
	private final boolean[] expanded = { true, true, true };
	private int scroll;

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
		return this.height / 2 - 150;
	}

	private String onOff(boolean b) {
		return Component.translatable(b ? "gui.riichi_arise.settings.on" : "gui.riichi_arise.settings.off").getString();
	}

	private String ownVoice() {
		MahjongLobbyPayloads.LobbySync s = MahjongLobbyPayloads.CLIENT_SYNC.get(origin.asLong());
		if (s == null || s.ownVoice() == null || s.ownVoice().isEmpty())
			return Component.translatable("gui.riichi_arise.voice.none").getString();
		return mcr.riichi_arise.voice.VoiceCharacters.displayName(s.ownVoice());
	}

	/** 宝牌指示叠加层位置（四角预设）当前值的显示名 */
	private String cornerName() {
		String key = switch (RichiConfig.HUD_DORA_CORNER.get()) {
			case TOP_RIGHT -> "top_right";
			case BOTTOM_LEFT -> "bottom_left";
			case BOTTOM_RIGHT -> "bottom_right";
			default -> "top_left";
		};
		return Component.translatable("gui.riichi_arise.settings.corner_" + key).getString();
	}

	private List<Row> rows() {
		List<Row> r = new ArrayList<>();
		// 客户端（本地配置）
		r.add(new Row(HEADER, 0, "gui.riichi_arise.settings.cat_client", expanded[0] ? "▾" : "▸", true, 0));
		if (expanded[0]) {
			r.add(clientRow("gui.riichi_arise.settings.client_spectator_open", RichiConfig.SPECTATOR_OPEN_HANDS));
			r.add(clientRow("gui.riichi_arise.settings.client_avatar_stand", RichiConfig.MAHJONG_AVATAR_STANDING));
			r.add(clientRow("gui.riichi_arise.settings.client_spectator_show", RichiConfig.SPECTATOR_SHOW_TABLE));
			r.add(new Row(CLIENT_CORNER, 0, "gui.riichi_arise.settings.client_hud_corner", cornerName(), true, 0));
		}
		// 个人设定
		r.add(new Row(HEADER, 1, "gui.riichi_arise.settings.cat_personal", expanded[1] ? "▾" : "▸", true, 0));
		if (expanded[1])
			r.add(new Row(PERSONAL_VOICE, 1, "gui.riichi_arise.settings.personal_voice", ownVoice(), true, 0));
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

	private int maxScroll() {
		return Math.max(0, rows().size() - VISIBLE);
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
		int bc = 0xFFB8860B; // 金色
		for (int i = 0; i < VISIBLE; i++) {
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
		int downY = rowY(VISIBLE);
		drawArrow(graphics, left, downY, PANEL_W, 14, "▼", mouseX, mouseY, scroll < maxScroll());
		// 底部提示：世界规则需管理员
		graphics.drawCenteredString(font,
				Component.translatable("gui.riichi_arise.settings.hint").getString(), this.width / 2, downY + 16, 0x808080);
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
		if (button != 0)
			return super.mouseClicked(mouseX, mouseY, button);
		int left = left();
		// 上翻
		if (inBtn(mouseX, mouseY, left, rowY(0) - ROW_GAP, PANEL_W, 14)) {
			scroll = Math.max(0, scroll - 1);
			return true;
		}
		// 下翻
		int downY = rowY(VISIBLE);
		if (inBtn(mouseX, mouseY, left, downY, PANEL_W, 14)) {
			scroll = Math.min(maxScroll(), scroll + 1);
			return true;
		}
		// 行命中
		List<Row> rows = rows();
		for (int i = 0; i < VISIBLE; i++) {
			int idx = scroll + i;
			if (idx >= rows.size())
				break;
			int y = rowY(i);
			if (mouseY < y || mouseY >= y + ROW_H || mouseX < left || mouseX >= left + PANEL_W)
				continue;
			Row row = rows.get(idx);
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
				net.neoforged.neoforge.common.ModConfigSpec.BooleanValue v;
				if (row.labelKey().equals("gui.riichi_arise.settings.client_spectator_open"))
					v = RichiConfig.SPECTATOR_OPEN_HANDS;
				else if (row.labelKey().equals("gui.riichi_arise.settings.client_avatar_stand"))
					v = RichiConfig.MAHJONG_AVATAR_STANDING;
				else
					v = RichiConfig.SPECTATOR_SHOW_TABLE;
				v.set(!v.get());
				RichiConfig.SPEC.save();
				// 实时生效：按最近一次快照立即重建牌桌（观战摊手 / AI 形象姿态等无需等下一包）
				MahjongTableClient.refreshAll();
			}
			case PERSONAL_VOICE -> VoiceSettingsScreen.open(this.origin);
			case CLIENT_CORNER -> {
				// 四角循环切换（HUD 逐帧读配置，切换即生效）
				RichiConfig.HudCorner[] all = RichiConfig.HudCorner.values();
				RichiConfig.HUD_DORA_CORNER.set(all[(RichiConfig.HUD_DORA_CORNER.get().ordinal() + 1) % all.length]);
				RichiConfig.SPEC.save();
			}
			case WORLD_TOGGLE -> {
				if (this.sync == null || !this.sync.canManage())
					return;
				boolean cur = this.sync.autoOpenNetPort();
				send(MahjongSettingsPayloads.ACT_SET_GAMERULE, row.extra(), cur ? 0 : 1);
				// 乐观更新，服务端 Sync 到达后校正
				this.sync = new MahjongSettingsPayloads.SettingsSync(!cur, this.sync.netRunning(),
						this.sync.netPort(), this.sync.netBind(), this.sync.netConn(), this.sync.canManage());
			}
			case NET_TOGGLE -> {
				if (this.sync == null || !this.sync.canManage())
					return;
				send(this.sync.netRunning() ? MahjongSettingsPayloads.ACT_NET_STOP : MahjongSettingsPayloads.ACT_NET_START,
						0, 0);
				// 乐观更新端口状态
				this.sync = new MahjongSettingsPayloads.SettingsSync(this.sync.autoOpenNetPort(),
						!this.sync.netRunning(), this.sync.netPort(), this.sync.netBind(),
						this.sync.netConn(), this.sync.canManage());
			}
			default -> {
			}
		}
	}

	private boolean inBtn(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		if (dy < 0)
			scroll = Math.min(maxScroll(), scroll + 1);
		else
			scroll = Math.max(0, scroll - 1);
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
