package mcr.riichi_arise.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import mcr.riichi_arise.RichiConfig;
import mcr.riichi_arise.game.MahjongTileNotation;
import mcr.riichi_arise.network.MahjongTablePayload;

/**
 * 紫水晶之间：公开备选牌区叠加层（三格储位，位置见 {@link RichiConfig#CANDIDATE_POSITION}）。
 * <p>三格储位随对局快照实时更新；参战者始终显示，旁观者需开启"显示对局信息"（见
 * {@link MahjongTableClient#amethystView()}）。</p>
 * <p>轮到自己选牌时，本面板同时充当选牌框：样式与平时完全相同，只在各牌面下方标注 1/2/3
 * 供数字键选择；此时临时移到屏幕水平中间、底边对齐副露/手牌一排的 HUD 基线，选完牌后恢复配置位置。
 * 被选走的格子留空，直到下一名玩家摸牌开始时才补充新牌。</p>
 */
public final class AmethystCandidateOverlay {
	private static final int SLOTS = 3;
	private static final int TILE_W = 24, TILE_H = 32;
	private static final int GAP = 4, PAD = 6, TITLE_H = 12;
	/** 选牌时牌面下方序号行的高度 */
	private static final int NUM_H = 10;
	/** 距屏幕边缘的留白（避开常见 HUD 边缘） */
	private static final int MARGIN = 8;
	/** 选牌阶段面板底边距屏幕底边的高度：与选项面板底边（guiHeight-73）同基线，
	 *  即停靠在副露/手牌那一排之上，既贴齐副露区又不压住手牌与倒计时文字 */
	private static final int PICK_BOTTOM_RESERVE = 73;

	private AmethystCandidateOverlay() {
	}

	public static void render(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		// 打开任何界面（大厅/设置/结算）时不叠加，避免遮挡面板
		if (mc.screen != null)
			return;
		MahjongTablePayload.ViewMessage view = MahjongTableClient.amethystView();
		if (view == null)
			return;
		// "?" = 已被选走、等待下一次摸牌开始时补充的空位（code -1）
		List<Integer> tiles = MahjongTileNotation.parse(view.candidates());
		boolean mine = view.candidatePickSeat() == view.viewerSeat() && view.viewerSeat() >= 0;
		int panelW = PAD * 2 + SLOTS * TILE_W + (SLOTS - 1) * GAP;
		// 序号行恒定预留：选牌开始/结束时面板位置不跳动
		int panelH = PAD * 2 + TITLE_H + TILE_H + NUM_H;
		// 自己选牌时临时居中停靠；选完牌恢复为配置的四角常驻位置
		int[] xy = mine ? pickPosition(g, panelW, panelH) : position(g, panelW, panelH);
		int x = xy[0];
		int y = xy[1];

		int border = mine ? 0xFFFFD75E : 0x66FFFFFF;
		g.fill(x, y, x + panelW, y + panelH, 0xC0101014);
		g.renderOutline(x, y, panelW, panelH, border);

		var font = mc.font;
		g.drawString(font, Component.translatable("gui.riichi_arise.table.candidates").getString(), x + PAD,
				y + PAD - 1, 0xFFFFE9A8, false);
		// 当前选牌者提示改由 boss 栏显示（此处不再绘制，避免与"备选牌"标题重叠）

		int num = 0;
		for (int i = 0; i < SLOTS; i++) {
			int sx = x + PAD + i * (TILE_W + GAP);
			int sy = y + PAD + TITLE_H;
			g.fill(sx, sy, sx + TILE_W, sy + TILE_H, 0x66000000);
			Integer tile = i < tiles.size() ? tiles.get(i) : null;
			if (tile != null && tile >= 0) {
				TileIcons.draw(g, tile, sx, sy, TILE_W);
				// 自己选牌时：可选序号标在牌面下方（与数字键 1..N 对应）
				if (mine) {
					num++;
					String n = String.valueOf(num);
					g.drawString(font, n, sx + (TILE_W - font.width(n)) / 2, sy + TILE_H + 1, 0xFFFFD75E, true);
				}
			} else {
				// 已被选走（空位）或尚未翻出：只画空槽
				g.renderOutline(sx, sy, TILE_W, TILE_H, 0x33FFFFFF);
			}
		}
	}

	/** 选牌阶段的临时停靠位置：屏幕水平居中，底边抬到副露/手牌一排的 HUD 基线（不贴屏幕底边） */
	private static int[] pickPosition(GuiGraphics g, int panelW, int panelH) {
		int x = (g.guiWidth() - panelW) / 2;
		int y = g.guiHeight() - panelH - PICK_BOTTOM_RESERVE;
		return new int[] { x, y };
	}

	/** 按客户端配置计算面板左上角坐标：四个预设角，统一留 MARGIN 边距（仅常驻显示用） */
	private static int[] position(GuiGraphics g, int panelW, int panelH) {
		RichiConfig.CandidatePosition pos = RichiConfig.CANDIDATE_POSITION.get();
		int x = switch (pos) {
			case TOP_LEFT, BOTTOM_LEFT -> MARGIN;
			case TOP_RIGHT, BOTTOM_RIGHT -> g.guiWidth() - panelW - MARGIN;
		};
		int y = switch (pos) {
			case TOP_LEFT, TOP_RIGHT -> MARGIN;
			case BOTTOM_LEFT, BOTTOM_RIGHT -> g.guiHeight() - panelH - MARGIN;
		};
		return new int[] { x, y };
	}
}
