package mcr.riichi_arise.client;

import net.minecraft.resources.ResourceLocation;

/**
 * 麻雀牌面贴图直绘（客户端）：把牌面代码映射到 assets/riichi_arise/textures/item/mahjong/<名>.png，
 * GUI 上直接 blit 原始贴图（不经 item 模型渲染管线）。
 * 贴图实际尺寸 48×64（3:4 竖长），绘制时按比例缩放（宽 w → 高 w*4/3），避免压扁。
 * code：0=红5万 1-9=万 10=红5饼 11-19=饼 20=红5索 21-29=索 30-36=字。
 */
public final class TileIcons {
	private TileIcons() {
	}

	private static final String[] Z = { "1z", "2z", "3z", "4z", "5z", "6z", "7z" };
	/** 普通牌面贴图原始尺寸 */
	private static final int TEX_W = 48, TEX_H = 64;
	/** 万能牌动态精灵图：竖排 4 帧，每帧 24×32（整图 24×128） */
	private static final ResourceLocation WILD_TEX = ResourceLocation.fromNamespaceAndPath(
			mcr.riichi_arise.MahjongItems.NAMESPACE, "textures/item/mahjong/wanengpai.png");
	private static final int WILD_FRAMES = 4, WILD_FRAME_W = 24, WILD_FRAME_H = 32, WILD_TEX_H = 128;
	/** 每帧持续毫秒（4 帧一轮 ≈ 0.8s 缓慢循环） */
	private static final long WILD_FRAME_MS = 200L;

	/** 牌面代码 → 贴图路径（不含 assets/ 前缀） */
	public static ResourceLocation icon(int code) {
		int b = mcr.riichi_arise.game.TileFlag.base(code); // 透明牌标志不参与贴图选择
		if (b == mcr.riichi_arise.game.TileFlag.WILD_BASE) // 万能牌（青金石之间）：专用动态精灵图
			return WILD_TEX;
		code = b;
		String name;
		if (code < 10)
			name = code + "m";
		else if (code < 20)
			name = (code == 10 ? 0 : code - 10) + "p";
		else if (code < 30)
			name = (code == 20 ? 0 : code - 20) + "s";
		else
			name = Z[Math.min(6, Math.max(0, code - 30))];
		return ResourceLocation.fromNamespaceAndPath(mcr.riichi_arise.MahjongItems.NAMESPACE,
				"textures/item/mahjong/" + name + ".png");
	}

	/** 等比绘制：宽 w，高 w*4/3（整张贴图缩放到该尺寸） */
	public static void draw(net.minecraft.client.gui.GuiGraphics g, int code, int x, int y, int w) {
		int h = w * TEX_H / TEX_W;
		if (mcr.riichi_arise.game.TileFlag.base(code) == mcr.riichi_arise.game.TileFlag.WILD_BASE) {
			// 万能牌：4 帧动态精灵图，按时间选帧后取该帧子矩形（24×32）等比放大绘制
			int frame = (int) ((net.minecraft.Util.getMillis() / WILD_FRAME_MS) % WILD_FRAMES);
			g.blit(WILD_TEX, x, y, w, h, 0.0F, frame * WILD_FRAME_H, WILD_FRAME_W, WILD_FRAME_H, WILD_FRAME_W,
					WILD_TEX_H);
			return;
		}
		// 全图采样：uWidth/vHeight = 整张贴图像素，缩放绘制到 w×h
		g.blit(icon(code), x, y, w, h, 0.0F, 0.0F, TEX_W, TEX_H, TEX_W, TEX_H);
	}

	/** 便利重载：默认宽 12 → 高 16 */
	public static void draw(net.minecraft.client.gui.GuiGraphics g, int code, int x, int y) {
		draw(g, code, x, y, 12);
	}
}
