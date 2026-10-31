package mcr.riichi_arise.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import mcr.riichi_arise.game.RichiTableState;
import mcr.riichi_arise.network.MahjongTablePayload;

/**
 * Tab 玩家列表打开时的对局信息叠加层：屏幕左侧渲染当前参战桌的
 * 局目/牌山量/场供，以及四家点数与排名。仅按 Tab 且处于参战（viewerSeat>=0）时显示。
 */
public final class MahjongTableOverlay {
	private MahjongTableOverlay() {
	}

	public static void render(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		// 仅按 Tab 打开玩家列表时显示
		if (!mc.options.keyPlayerList.isDown())
			return;
		MahjongTablePayload.ViewMessage view = MahjongTableClient.participatingView();
		if (view == null || view.phase() == RichiTableState.PHASE_WAITING)
			return;
		int n = Math.max(1, view.playerCount());
		int pad = 8;
		int lineH = 11;
		int headerH = pad * 2 + lineH * 3 + 8; // 局目 + 牌山 + 场供
		int h = headerH + n * (lineH + 3) + pad;
		int w = 196;
		int left = 8;
		int top = 10;
		g.fill(left, top, left + w, top + h, 0xC0101010);
		int border = 0xFFB8860B;
		g.fill(left, top, left + w, top + 1, border);
		g.fill(left, top + h - 1, left + w, top + h, border);
		g.fill(left, top, left + 1, top + h, border);
		g.fill(left + w - 1, top, left + w, top + h, border);

		int y = top + pad;
		String round = (view.roundWind() == 0 ? "東" : "南") + view.round() + "局"
				+ (view.honba() > 0 ? " " + view.honba() + "本场" : "");
		g.drawString(mc.font, round, left + pad, y, 0xFFE8A0);
		y += lineH;
		g.drawString(mc.font, "牌山 " + view.wallCount(), left + pad, y, 0xCCCCCC);
		y += lineH + 4;
		int totalSticks = view.carriedSticks();
		for (int s = 0; s < n; s++)
			totalSticks += view.riichiSticks()[s];
		g.drawString(mc.font, "场供 " + totalSticks, left + pad, y, 0xCCCCCC);
		y += lineH + 6;

		// 四家点数与排名（点数高→低，同分保持座位序）
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++)
			order[i] = i;
		java.util.Arrays.sort(order, (a, b) -> Integer.compare(view.points()[b], view.points()[a]));
		for (int r = 0; r < n; r++) {
			int s = order[r];
			String name = view.names()[s] == null || view.names()[s].isBlank() ? "?" : view.names()[s];
			String label = (r + 1) + ". " + name;
			g.drawString(mc.font, label, left + pad, y, 0xFFFFFF);
			String pt = String.valueOf(view.points()[s]);
			g.drawString(mc.font, pt, left + w - pad - mc.font.width(pt), y, 0xFFFFAA);
			y += lineH + 3;
		}
	}
}
