package mcr.riichi_arise.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import mcr.riichi_arise.network.MahjongPointDeltaPayload;

/**
 * 点数变化界面（和牌结算界面关闭后展示）：四家点数 局前 → 局后，带结算动画：
 * 出现即显示原点数 + 右侧红/绿增减数 → 1s 后点数 1s 内快速浮动到最终点数 →
 * 四家名字按排名上下滑动排序 → 最后 3s 自动确认（回 settle_confirm 推进服务端）。
 * 点击/回车确认后进入"等待其他玩家"状态（省略号点数循环变动）：界面可继续关闭，
 * 关闭后在动作栏持续提示，直到全员确认、服务端推进下一局。
 */
public class MahjongPointDeltaScreen extends Screen {
	// 结算动画时间轴（tick，1/20s）：
	//   0s    界面出现：四家显示原点数，右侧红/绿点数增减数
	//   1s    点数变动家的点数开始快速浮动，1s 内（即 2s 时）变到最终点数
	//   2s    点数落定 → 四家名字按排名上下滑动排序成一~四位
	//   3s    排序完成 → 开始 3s 自动确认倒计时，超时回传推进
	private static final int POINTS_DELAY_TICKS = 20;       // 1s：点数开始浮动前
	private static final int POINTS_DURATION_TICKS = 20;    // 1s（速度已调快一倍）：浮动到最终点数
	private static final int REORDER_START = POINTS_DELAY_TICKS + POINTS_DURATION_TICKS; // 2s：点数落定，开始排序
	private static final int REORDER_DURATION_TICKS = 20;   // 1s：名字按排名滑动
	private static final int COUNTDOWN_START = REORDER_START + REORDER_DURATION_TICKS;   // 3s：排序完成，开始倒计时
	private static final int COUNTDOWN_TICKS = 60;          // 3s：自动确认倒计时
	private static final int AUTO_CONFIRM_TICKS = COUNTDOWN_START + COUNTDOWN_TICKS;     // 6s：动画+倒计时合计

	private MahjongPointDeltaPayload.PointDeltaMessage data;
	private int age;
	/** 已确认、正在等其他玩家（确认包已发出） */
	private boolean waiting;
	/** 全局"等待其他玩家"状态：界面关闭后仍继续在动作栏提示，直到服务端推进（closeIfOpen） */
	private static boolean waitingForOthers;
	/** 等待计时（tick）：驱动省略号点数 1→6 循环与确认包周期重发 */
	private static int waitTicks;
	/** 本界面是否已发过 settle_confirm（避免重复发包） */
	private boolean confirmSent;

	private MahjongPointDeltaScreen(MahjongPointDeltaPayload.PointDeltaMessage data) {
		super(Component.translatable("gui.riichi_arise.settlement.title"));
		this.data = data;
	}

	public static void open(MahjongPointDeltaPayload.PointDeltaMessage data) {
		mcr.riichi_arise.client.MahjongSettlementScreen.discardPendingOpen();
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null)
			return;
		// 已在等待其他玩家（界面可关）或本界面已打开：不重复打开/重置动画
		if (waitingForOthers || mc.screen instanceof MahjongPointDeltaScreen)
			return;
		mc.setScreen(new MahjongPointDeltaScreen(data));
	}

	/** 关闭和牌结算界面后本地立即打开（数据来自服务端预置包，不必等其他人确认） */
	public static void openPrepared(MahjongPointDeltaPayload.PointDeltaMessage data) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || waitingForOthers || mc.screen instanceof MahjongPointDeltaScreen)
			return;
		mc.setScreen(new MahjongPointDeltaScreen(data));
	}

	/** 服务端推进下一局：关闭已打开的点数变化界面，并清掉等待提示 */
	public static void closeIfOpen() {
		waitingForOthers = false;
		waitTicks = 0;
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen instanceof MahjongPointDeltaScreen)
			mc.setScreen(null);
	}

	/** 客户端每 tick（MahjongClient 转发）：等待其他玩家时驱动省略号动画、周期重发确认并在动作栏提示 */
	public static void tickWaiting() {
		if (!waitingForOthers)
			return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			// 退出世界/断线：清掉等待状态，避免残留动作栏提示
			waitingForOthers = false;
			waitTicks = 0;
			return;
		}
		waitTicks++;
		// 每 20 tick 重发一次确认：服务端可能还处于结算阶段（确认会被并入下一阶段），重发保证最终生效
		if (waitTicks % 20 == 1)
			sendConfirm(MahjongPointDeltaPayload.prepared != null
					? MahjongPointDeltaPayload.prepared.originPacked() : 0);
		// 界面已关闭时在动作栏继续提示，直到全员确认、服务端推进
		if (!(mc.screen instanceof MahjongPointDeltaScreen))
			mc.gui.setOverlayMessage(Component.literal(waitingText()), false);
	}

	/** "正在等待其他玩家" + 1~6 个点循环变动 */
	private static String waitingText() {
		int dots = waitTicks / 5 % 6 + 1;
		return Component.translatable("gui.riichi_arise.points.waiting").getString() + ".".repeat(dots);
	}

	private static void sendConfirm(long originPacked) {
		net.neoforged.neoforge.network.PacketDistributor.sendToServer(
				new mcr.riichi_arise.network.MahjongGamePayloads.ActionMessage(originPacked, "settle_confirm", ""));
	}

	@Override
	public void tick() {
		if (waiting)
			return;
		if (++age >= AUTO_CONFIRM_TICKS)
			confirm();
	}

	/** 确认：回传服务端（阶段 2 全员确认即续局），进入等待其他玩家状态（界面保留，可自行关闭）。
	 *  观战者确认不参与服务端推进（纯 AI 对局旁观时尤其不能卡在等待），直接关闭界面。 */
	private void confirm() {
		if (this.data == null)
			return;
		if (!isParticipant()) {
			onClose();
			return;
		}
		if (!confirmSent) {
			confirmSent = true;
			sendConfirm(this.data.originPacked());
		}
		waiting = true;
		waitingForOthers = true;
		waitTicks = 0;
	}

	/** 本地玩家是否为该桌参战者（观战者的确认不会推进服务端结算） */
	private boolean isParticipant() {
		mcr.riichi_arise.network.MahjongTablePayload.ViewMessage view = MahjongTableClient.participatingView();
		return view != null && view.viewerSeat() >= 0 && view.originPacked() == this.data.originPacked();
	}

	@Override
	public void onClose() {
		// 界面关闭不取消等待：动作栏继续提示，直到服务端推进
		super.onClose();
	}

	/** 参战人数（三麻 3 / 四麻 4）：第 4 席在三麻不存在，不显示 */
	private int seats() {
		return Math.max(1, Math.min(4, this.data == null ? 4 : this.data.playerCount()));
	}

	/** 排名行：按最终点数高→低排 1~n 位（同分保持座位序） */
	private int[] rankOrder() {
		int n = seats();
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++)
			order[i] = i;
		java.util.Arrays.sort(order, (a, b) -> Integer.compare(this.data.after().get(b), this.data.after().get(a)));
		int[] out = new int[n];
		for (int i = 0; i < n; i++)
			out[i] = order[i];
		return out;
	}

	/** 点数动画：1s 延迟后 1s 内从原点数快速浮动到最终点数（缓出：先快后落定） */
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
		int n = seats();
		int h = pad * 2 + 14 + 12 + n * rowH + 14;
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
		for (int seat = 0; seat < n; seat++) {
			int before = this.data.before().get(seat);
			int after = this.data.after().get(seat);
			int delta = after - before;
			// 当前显示点数：原点数 → 1s 后 1s 内快速浮动到最终点数（未变动的家恒为原点数）
			int shown = animatedPoints(before, after);
			// 行位置：先按座位序，点数落定后按排名上下滑动到 1~n 位
			int targetRow = 0;
			for (int i = 0; i < n; i++)
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
		// 3s 自动确认倒计时：名字排序完成后才开始（正在等待其他玩家时改为显示等待提示）
		if (waitingForOthers) {
			g.drawCenteredString(this.font, waitingText(), this.width / 2, y + n * rowH + 2, 0xFFAA55);
		} else if (age >= COUNTDOWN_START) {
			int remain = Math.max(0, (AUTO_CONFIRM_TICKS - age + 19) / 20);
			String cd = Component.translatable("gui.riichi_arise.points.confirm_in", remain).getString();
			g.drawCenteredString(this.font, cd, this.width / 2, y + n * rowH + 2, 0xFFAA55);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
