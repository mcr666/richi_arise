package mcr.riichi_arise.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;

import mcr.riichi_arise.RichiiAriseMod;
import mcr.riichi_arise.game.RichiTableState;
import mcr.riichi_arise.network.MahjongSettingsPayloads;
import mcr.riichi_arise.network.MahjongTablePayload;

/**
 * Tab 玩家列表打开时的对局信息叠加层：屏幕左侧渲染当前参战桌的
 * 局目/牌山量/场供，以及四家点数与排名。仅按 Tab 且处于参战（viewerSeat>=0）时显示。
 * <p>底部两个对局偏好复选框：数字键 1 = 自动和牌，2 = 关闭鸣牌（均默认关闭；
 * 关闭鸣牌每局目开始由服务端复位并回发同步）。</p>
 */
@EventBusSubscriber(modid = RichiiAriseMod.MODID, value = Dist.CLIENT)
public final class MahjongTableOverlay {
	private MahjongTableOverlay() {
	}

	/** 对局偏好本地显示值（服务端 GamePrefSync 下发/回显） */
	private static boolean autoWin;
	private static boolean noClaims;

	/** 服务端下发对局偏好当前值 */
	public static void onPrefSync(boolean autoWinVal, boolean noClaimsVal) {
		autoWin = autoWinVal;
		noClaims = noClaimsVal;
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
		int prefH = 12 + (lineH + 3) * 2; // 两个对局偏好复选框
		int h = headerH + n * (lineH + 3) + prefH + pad;
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

		// 对局偏好复选框（数字键 1/2 切换）
		y += 4;
		g.fill(left + pad, y, left + w - pad, y + 1, 0x40FFFFFF);
		y += 5;
		drawPref(g, mc, left + pad, y, "1", "gui.riichi_arise.table.auto_win", autoWin);
		y += lineH + 3;
		drawPref(g, mc, left + pad, y, "2", "gui.riichi_arise.table.no_claims", noClaims);
	}

	private static void drawPref(GuiGraphics g, Minecraft mc, int x, int y, String key, String langKey,
			boolean on) {
		String label = "[" + (on ? "x" : " ") + "] " + key + "·"
				+ Component.translatable(langKey).getString();
		g.drawString(mc.font, label, x, y, on ? 0xFF7CFC00 : 0xFFAAAAAA, false);
	}

	/** 数字键 1/2：Tab 叠加层打开时切换对局偏好 */
	@SubscribeEvent
	public static void onKey(InputEvent.Key event) {
		if (event.getAction() != org.lwjgl.glfw.GLFW.GLFW_PRESS)
			return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen != null || !mc.options.keyPlayerList.isDown())
			return;
		MahjongTablePayload.ViewMessage view = MahjongTableClient.participatingView();
		if (view == null || view.phase() == RichiTableState.PHASE_WAITING)
			return;
		int key = event.getKey();
		if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_1 || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_1) {
			autoWin = !autoWin;
			MahjongSettingsPayloads.sendGamePref(MahjongSettingsPayloads.PREF_AUTO_WIN, autoWin);
		} else if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_2 || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_2) {
			noClaims = !noClaims;
			MahjongSettingsPayloads.sendGamePref(MahjongSettingsPayloads.PREF_NO_CLAIMS, noClaims);
		}
	}

	/** Tab 叠加层打开时吞掉 1/2 的快捷栏点击计数，防止同时切换快捷栏 */
	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Pre event) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen != null || !mc.options.keyPlayerList.isDown())
			return;
		if (MahjongTableClient.participatingView() == null)
			return;
		for (int i = 0; i < 2 && i < mc.options.keyHotbarSlots.length; i++) {
			while (mc.options.keyHotbarSlots[i].consumeClick()) {
				// drain：阻止本帧快捷栏切换
			}
		}
	}
}
