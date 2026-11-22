package mcr.riichi_arise.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import mcr.riichi_arise.network.MahjongPointDeltaPayload;

/**
 * 万象修罗（青金石）中局点数结算叠加层：不再弹出 GUI 界面，改为停靠在屏幕左侧的 HUD 面板，
 * 对局画面不被打断。动画与 MahjongPointDeltaScreen 的精简时间轴一致：
 * 出现即显示原点数 + 红/绿增减数 → 0.2s 后 0.4s 内点数浮动到最终值 → 名字按排名滑动 →
 * 总超时 1s（20 tick）自动确认（回 settle_confirm 推进服务端）。
 * 确认后叠加层显示"等待其他玩家"，直到服务端推进下发 MODE_CLOSE 清除。
 */
public final class MahjongPointDeltaOverlay {
	// 精简动画时间轴（tick）：与 MahjongPointDeltaScreen 的 FAST_* 常量一致
	private static final int POINTS_DELAY_TICKS = 4;     // 0.2s：点数开始浮动前
	private static final int POINTS_DURATION_TICKS = 8;  // 0.4s：浮动到最终点数
	private static final int REORDER_DURATION_TICKS = 8; // 0.4s：名字按排名滑动
	/** 总超时：1s 自动确认（从叠加层出现起计） */
	private static final int AUTO_CONFIRM_TICKS = 20;

	/** 当前展示的数据（null = 叠加层隐藏） */
	private static MahjongPointDeltaPayload.PointDeltaMessage data;
	private static int age;
	/** 已发过确认包（避免重复） */
	private static boolean confirmSent;

	private MahjongPointDeltaOverlay() {
	}

	/** fast 点数结算推送：打开左侧叠加层（不打开 GUI 界面） */
	public static void open(MahjongPointDeltaPayload.PointDeltaMessage msg) {
		data = msg;
		age = 0;
		confirmSent = false;
	}

	/** 服务端推进下一局（MODE_CLOSE）：清除叠加层 */
	public static void close() {
		data = null;
		age = 0;
		confirmSent = false;
	}

	/** 客户端每 tick（MahjongClient 转发）：推进动画计时，超时自动确认；确认后每 20 tick 重发确认包 */
	public static void tick() {
		if (data == null)
			return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			close();
			return;
		}
		age++;
		if (age >= AUTO_CONFIRM_TICKS && !confirmSent)
			confirm();
		// 确认包周期重发：服务端可能还处于结算阶段，重发保证最终生效
		if (confirmSent && age % 20 == 1)
			sendConfirm(data.originPacked());
	}

	private static void confirm() {
		if (data == null || confirmSent)
			return;
		confirmSent = true;
		age = Math.min(age, AUTO_CONFIRM_TICKS); // 进入等待阶段：冻结倒计时
		sendConfirm(data.originPacked());
	}

	private static void sendConfirm(long originPacked) {
		net.neoforged.neoforge.network.PacketDistributor.sendToServer(
				new mcr.riichi_arise.network.MahjongGamePayloads.ActionMessage(originPacked, "settle_confirm", ""));
	}

	/** 参战人数（三麻 3 / 四麻 4） */
	private static int seats() {
		return Math.max(1, Math.min(4, data == null ? 4 : data.playerCount()));
	}

	/** 排名行：按给定点数高→低排 1~n 位；同分保持座位序（稳定排序） */
	private static int[] rankOrder(java.util.List<Integer> points) {
		int n = seats();
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++)
			order[i] = i;
		java.util.Arrays.sort(order, (a, b) -> Integer.compare(points.get(b), points.get(a)));
		int[] out = new int[n];
		for (int i = 0; i < n; i++)
			out[i] = order[i];
		return out;
	}

	private static int indexOf(int[] order, int seat) {
		for (int i = 0; i < order.length; i++)
			if (order[i] == seat)
				return i;
		return 0;
	}

	/** 点数动画：0.2s 延迟后 0.4s 内从原点数快速浮动到最终点数（ease-out） */
	private static int animatedPoints(int before, int after) {
		if (age < POINTS_DELAY_TICKS)
			return before;
		double p = Math.max(0.0, Math.min(1.0, (age - POINTS_DELAY_TICKS) / (double) POINTS_DURATION_TICKS));
		double e = 1 - Math.pow(1 - p, 3);
		return (int) Math.round(before + (after - before) * e);
	}

	/** HUD 层渲染（MahjongClient 的 RegisterGuiLayersEvent 转发）：面板停靠屏幕左侧、垂直居中 */
	public static void render(GuiGraphics g) {
		if (data == null)
			return;
		Minecraft mc = Minecraft.getInstance();
		var font = mc.font;
		int pad = 8;
		int rowH = 16;
		int n = seats();
		int w = 150;
		int h = pad * 2 + 12 + n * rowH + 12;
		int left = 8;
		int top = (mc.getWindow().getGuiScaledHeight() - h) / 2;
		g.fill(left, top, left + w, top + h, 0xD0000000);
		int border = 0xFFB8860B;
		g.fill(left, top, left + w, top + 1, border);
		g.fill(left, top + h - 1, left + w, top + h, border);
		g.fill(left, top, left + 1, top + h, border);
		g.fill(left + w - 1, top, left + w, top + h, border);

		int y = top + 5;
		g.drawCenteredString(font, Component.translatable("gui.riichi_arise.points.title").getString(),
				left + w / 2, y, 0xFFFF55);
		y += 13;
		String pt = Component.translatable("gui.riichi_arise.settlement.pt").getString();
		int[] beforeOrder = rankOrder(data.before());
		int[] afterOrder = rankOrder(data.after());
		int reorderStart = POINTS_DELAY_TICKS + POINTS_DURATION_TICKS;
		for (int seat = 0; seat < n; seat++) {
			int before = data.before().get(seat);
			int after = data.after().get(seat);
			int delta = after - before;
			int shown = animatedPoints(before, after);
			int initialRow = indexOf(beforeOrder, seat);
			int targetRow = indexOf(afterOrder, seat);
			double rowF = initialRow;
			if (age >= reorderStart) {
				double p = Math.min(1.0, (age - reorderStart) / (double) REORDER_DURATION_TICKS);
				double e = p * p * (3 - 2 * p); // smoothstep
				rowF = initialRow + (targetRow - initialRow) * e;
			}
			int rowY = (int) Math.round(y + rowF * rowH);
			g.drawString(font, data.names().get(seat), left + pad, rowY, 0xFFFFFF, true);
			g.drawString(font, String.valueOf(shown), left + pad + 60, rowY, 0xCCCCCC, false);
			String deltaStr = (delta >= 0 ? "+" : "") + delta;
			int color = delta > 0 ? 0x7FFF7F : delta < 0 ? 0xFF7F7F : 0xFFFFFF;
			String line = deltaStr + pt;
			g.drawString(font, line, left + w - pad - font.width(line), rowY, color, true);
		}
		// 底部提示：确认后等待其他玩家；否则显示 3s 超时剩余秒数
		int hintY = y + n * rowH + 2;
		if (confirmSent) {
			int dots = age / 5 % 6 + 1;
			g.drawCenteredString(font,
					Component.translatable("gui.riichi_arise.points.waiting").getString() + ".".repeat(dots),
					left + w / 2, hintY, 0xFFAA55);
		} else {
			int remain = Math.max(0, (AUTO_CONFIRM_TICKS - age + 19) / 20);
			g.drawCenteredString(font,
					Component.translatable("gui.riichi_arise.points.confirm_in", remain).getString(),
					left + w / 2, hintY, 0xFFAA55);
		}
	}
}
