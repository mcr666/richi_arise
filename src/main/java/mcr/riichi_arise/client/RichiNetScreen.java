package mcr.riichi_arise.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import mcr.riichi_arise.network.MahjongLobbyPayloads;

/**
 * 雀魂连接控制面板：大厅管理界面"查看牌谱"按钮右键打开（或该桌被接管后右键风盘直接打开）。
 * 展示内网端口/连接数/是否已接入/接管来源，提供"开始连接"（开启端口并由外部接管本桌）与
 * "关闭接管"（解除接管，恢复常规交互）。接管状态由服务端 NetStateMessage 推送。
 */
public class RichiNetScreen extends Screen {
	private static final int PANEL_W = 260;
	private static final int PANEL_H = 120;

	private final BlockPos origin;
	private MahjongLobbyPayloads.NetState state;
	/** 首次收到状态时重建按钮 */
	private boolean built;
	/** 已请求过状态（首次打开拉一次服务端状态） */
	private boolean requested;

	public RichiNetScreen(BlockPos origin) {
		super(Component.translatable("gui.riichi_arise.net.title"));
		this.origin = origin;
	}

	public static void open(BlockPos origin) {
		Minecraft.getInstance().setScreen(new RichiNetScreen(origin));
	}

	private record UiBtn(int x, int y, int w, int h, java.util.function.Supplier<String> label, Runnable action) {
	}

	private final java.util.List<UiBtn> btns = new java.util.ArrayList<>();

	private void send(int action) {
		net.neoforged.neoforge.network.PacketDistributor.sendToServer(
				new MahjongLobbyPayloads.ActionMessage(origin.asLong(), action, 0));
	}

	private void refresh() {
		MahjongLobbyPayloads.NetState prev = this.state;
		this.state = MahjongLobbyPayloads.CLIENT_NET.get(origin.asLong());
		boolean changed = prev == null ? this.state != null
				: this.state == null || prev.running() != this.state.running()
						|| prev.takeover() != this.state.takeover()
						|| prev.linked() != this.state.linked();
		if (changed || !this.built) {
			this.built = true;
			rebuildButtons();
		}
	}

	private void rebuildButtons() {
		clearWidgets();
		init(this.minecraft, this.width, this.height);
	}

	@Override
	protected void init() {
		refresh();
		if (!this.requested) {
			this.requested = true;
			send(MahjongLobbyPayloads.ACT_TAKEOVER_STATUS);
		}
		this.btns.clear();
		int left = this.width / 2 - PANEL_W / 2;
		int top = this.height / 2 - PANEL_H / 2;
		int y = top + PANEL_H - 24;
		boolean takeover = this.state != null && this.state.takeover();
		// 开始连接 / 关闭接管（互斥）
		addBtn(left + 6, y, 96, 18,
				() -> Component.translatable("gui.riichi_arise.net.btn_connect").getString(),
				() -> send(MahjongLobbyPayloads.ACT_TAKEOVER_ON), !takeover);
		addBtn(left + 108, y, 96, 18,
				() -> Component.translatable("gui.riichi_arise.net.btn_release").getString(),
				() -> send(MahjongLobbyPayloads.ACT_TAKEOVER_OFF), takeover);
		addBtn(left + PANEL_W - 48, y, 42, 18,
				() -> Component.translatable("gui.riichi_arise.net.btn_close").getString(), this::onClose, true);
	}

	private void addBtn(int x, int y, int w, int h, java.util.function.Supplier<String> label, Runnable action,
			boolean enabled) {
		if (!enabled)
			return;
		this.btns.add(new UiBtn(x, y, w, h, label, action));
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
		int top = this.height / 2 - PANEL_H / 2;
		graphics.drawCenteredString(font, this.title, this.width / 2, top - 14, 0xFFFFFF);
		graphics.fill(left, top, left + PANEL_W, top + PANEL_H, 0x60101018);
		graphics.renderOutline(left, top, PANEL_W, PANEL_H, 0x505000FF);
		if (this.state == null) {
			graphics.drawCenteredString(font, "...", this.width / 2, top + 20, 0xAAAAAA);
			return;
		}
		int lineY = top + 10;
		line(graphics, left + 8, lineY, "gui.riichi_arise.net.port", this.state.port());
		line(graphics, left + 8, lineY + 14, "gui.riichi_arise.net.port_state",
				onOff(this.state.running()));
		line(graphics, left + 8, lineY + 28, "gui.riichi_arise.net.connections",
				String.valueOf(this.state.connections()));
		line(graphics, left + 8, lineY + 42, "gui.riichi_arise.net.linked", onOff(this.state.linked()));
		line(graphics, left + 8, lineY + 56, "gui.riichi_arise.net.takeover", onOff(this.state.takeover()));
		if (this.state.source() != null && !this.state.source().isEmpty())
			line(graphics, left + 8, lineY + 70, "gui.riichi_arise.net.source", this.state.source());
		// 未接管时的操作提示：开始连接后由外部程序（桥接器）接入本桌
		if (!this.state.takeover()) {
			String hint = Component.translatable("gui.riichi_arise.net.hint", this.state.port()).getString();
			graphics.drawString(font, hint, left + 8, top + PANEL_H - 40, 0xAAAAAA, false);
		}
		for (UiBtn b : this.btns) {
			boolean hov = mouseX >= b.x() && mouseX < b.x() + b.w() && mouseY >= b.y() && mouseY < b.y() + b.h();
			graphics.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), hov ? 0x509090C0 : 0x30A0A0A0);
			String label = b.label().get();
			graphics.drawString(font, label, b.x() + (b.w() - font.width(label)) / 2, b.y() + 5, 0xFFFFFF, false);
		}
	}

	private void line(GuiGraphics graphics, int x, int y, String key, String value) {
		graphics.drawString(this.font, Component.translatable(key).getString(), x, y, 0xFFFFA0, false);
		graphics.drawString(this.font, value, x + 120, y, 0xFFFFFF, false);
	}

	private String onOff(boolean on) {
		return Component.translatable(on ? "gui.riichi_arise.net.on" : "gui.riichi_arise.net.off").getString();
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && this.state != null) {
			for (UiBtn b : this.btns) {
				if (mouseX >= b.x() && mouseX < b.x() + b.w() && mouseY >= b.y() && mouseY < b.y() + b.h()) {
					b.action().run();
					return true;
				}
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
