package mcr.riichi_arise.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import mcr.riichi_arise.network.MahjongPointDeltaPayload;

/**
 * 点数变化界面（和牌结算界面关闭后展示）：四家点数 局前 → 局后，带结算动画：
 * 出现即显示原点数 + 右侧红/绿增减数 → 1s 后点数 2s 内快速浮动到最终点数 →
 * 四家名字按排名上下滑动排序 → 最后 3s 自动确认（回 settle_confirm 推进服务端）；
 * 全员确认/手动确认立即推进（服务端关闭包兜底）。
 */
public class MahjongPointDeltaScreen extends Screen {
	// 结算动画时间轴（tick，1/20s）：
	//   0s    界面出现：四家显示原点数，右侧红/绿点数增减数
	//   1s    点数变动家的点数开始快速浮动，2s 内（即 3s 时）变到最终点数
	//   3s    点数落定 → 四家名字按排名上下滑动排序成一~四位
	//   排序完成 → 开始 3s 自动确认倒计时，超时回传推进
	private static final int POINTS_DELAY_TICKS = 20;       // 1s：点数开始浮动前
	private static final int POINTS_DURATION_TICKS = 40;    // 2s：浮动到最终点数
	private static final int REORDER_START = POINTS_DELAY_TICKS + POINTS_DURATION_TICKS; // 3s：点数落定，开始排序
	private static final int REORDER_DURATION_TICKS = 20;   // 1s：名字按排名滑动
	private static final int COUNTDOWN_START = REORDER_START + REORDER_DURATION_TICKS;   // 排序完成，开始倒计时
	private static final int COUNTDOWN_TICKS = 60;          // 3s：自动确认倒计时
	private static final int AUTO_CONFIRM_TICKS = COUNTDOWN_START + COUNTDOWN_TICKS;

	private MahjongPointDeltaPayload.PointDeltaMessage data;
	private int age;

	private MahjongPointDeltaScreen(MahjongPointDeltaPayload.PointDeltaMessage data) {
		super(Component.translatable("gui.riichi_arise.settlement.title"));
		this.data = data;
	}

	public static void open(MahjongPointDeltaPayload.PointDeltaMessage data) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null)
			mc.setScreen(new MahjongPointDeltaScreen(data));
	}

	/** 服务端推进下一局：关闭已打开的点数变化界面 */
	public static void closeIfOpen() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen instanceof MahjongPointDeltaScreen)
			mc.setScreen(null);
	}

	@Override
	public void tick() {
		if (++age >= AUTO_CONFIRM_TICKS)
			confirm();
	}

	/** 确认：回传服务端推进（阶段 2 全员确认即续局）并关闭界面 */
	private void confirm() {
		if (this.data != null)
			net.neoforged.neoforge.network.PacketDistributor.sendToServer(
					new mcr.riichi_arise.network.MahjongGamePayloads.ActionMessage(
							this.data.originPacked(), "settle_confirm", ""));
		onClose();
	}

	/** 排名行：按最终点数高→低排 1~4 位（同分保持座位序） */
	private int[] rankOrder() {
		Integer[] order = { 0, 1, 2, 3 };
		java.util.Arrays.sort(order, (a, b) -> Integer.compare(this.data.after().get(b), this.data.after().get(a)));
		return new int[] { order[0], order[1], order[2], order[3] };
	}

	/** 点数动画：1s 延迟后 2s 内从原点数快速浮动到最终点数（缓出：先快后落定） */
	private int animatedPoints(int before, int after) {
		if (age < POINTS_DELAY_TICKS)
			return before;
		double p = (age - POINTS_DELAY_TICKS) / (double) POINTS_DURATION_TICKS;
		p = Math.max(0.0, Math.min(1.0, p));
		double e = 1 - Math.pow(1 - p, 3); // ease-out：快速起跳、末尾落定
		return (int) Math.round(before + (after - before) * e);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
				|| keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE
				|| keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_E) {
			confirm();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		confirm();
		return true;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		if (this.data == null)
			return;
		int pad = 14;
		int rowH = 20;
		int h = pad * 2 + 14 + 12 + 4 * rowH + 14;
		int w = 210;
		int left = this.width / 2 - w / 2;
		int top = this.height / 2 - h / 2 - 8;
		g.fill(left, top, left + w, top + h, 0xE0000000);
		int border = 0xFFB8860B;
		g.fill(left, top, left + w, top + 1, border);
		g.fill(left, top + h - 1, left + w, top + h, border);
		g.fill(left, top, left + 1, top + h, border);
		g.fill(left + w - 1, top, left + w, top + h, border);

		int y = top + 6;
		g.drawCenteredString(this.font,
				Component.translatable("gui.riichi_arise.points.title").getString(), this.width / 2, y, 0xFFFF55);
		y += 16;
		String pt = Component.translatable("gui.riichi_arise.settlement.pt").getString();
		int[] order = rankOrder();
		for (int seat = 0; seat < 4; seat++) {
			int before = this.data.before().get(seat);
			int after = this.data.after().get(seat);
			int delta = after - before;
			// 当前显示点数：原点数 → 1s 后 2s 内快速浮动到最终点数（未变动的家恒为原点数）
			int shown = animatedPoints(before, after);
			// 行位置：先按座位序，点数落定后按排名上下滑动到 1~4 位
			int targetRow = 0;
			for (int i = 0; i < 4; i++)
				if (order[i] == seat) {
					targetRow = i;
					break;
				}
			double rowF = seat;
			if (age >= REORDER_START) {
				double p = Math.min(1.0, (age - REORDER_START) / (double) REORDER_DURATION_TICKS);
				double e = p * p * (3 - 2 * p); // smoothstep
				rowF = seat + (targetRow - seat) * e;
			}
			int rowY = (int) Math.round(y + rowF * rowH);
			g.drawString(this.font, this.data.names().get(seat), left + pad, rowY, 0xFFFFFF, true);
			g.drawString(this.font, String.valueOf(shown), left + pad + 70, rowY, 0xCCCCCC, false);
			String deltaStr = (delta >= 0 ? "+" : "") + delta;
			int color = delta > 0 ? 0x7FFF7F : delta < 0 ? 0xFF7F7F : 0xFFFFFF;
			String line = deltaStr + pt;
			g.drawString(this.font, line, left + w - pad - this.font.width(line), rowY, color, true);
		}
		// 3s 自动确认倒计时：名字排序完成后才开始
		if (age >= COUNTDOWN_START) {
			int remain = Math.max(0, (AUTO_CONFIRM_TICKS - age + 19) / 20);
			String cd = Component.translatable("gui.riichi_arise.points.confirm_in", remain).getString();
			g.drawCenteredString(this.font, cd, this.width / 2, y + 4 * rowH + 2, 0xFFAA55);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
