package mcr.riichi_arise.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

import mcr.riichi_arise.RichiiAriseMod;

/**
 * 对局开始标题（屏幕中央大字）：每次对局开始时由服务端推给各参与者，
 * 内容为当前对局类型（铁之间 = 灰色 / 玻璃之间 = 浅蓝色）。
 * 淡入 → 停留 → 淡出，约 2s；不遮挡鼠标、不拦截输入。
 */
@EventBusSubscriber(modid = RichiiAriseMod.MODID, value = Dist.CLIENT)
public final class RoomTitleOverlay {
	/** 总时长与淡入淡出时长（tick） */
	private static final int LIFE = 50;
	private static final int FADE_IN = 8;
	private static final int FADE_OUT = 12;

	private RoomTitleOverlay() {
	}

	private static Component text;
	private static int argb = 0xFFFFFFFF;
	private static int ticks;

	/** 显示标题（文本 + ARGB 颜色），并重置计时 */
	public static void show(Component title, int color) {
		text = title;
		argb = color;
		ticks = 0;
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		if (text != null && ++ticks > LIFE)
			text = null;
	}

	@SubscribeEvent
	public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
		event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(RichiiAriseMod.MODID, "room_title"),
				(GuiGraphics gfx, DeltaTracker partialTick) -> render(gfx));
	}

	private static void render(GuiGraphics gfx) {
		Component title = text;
		if (title == null || Minecraft.getInstance().options.hideGui)
			return;
		// 淡入淡出：alpha 乘到颜色上（0x00RRGGBB 与 0x00FFFFFF 取色）
		float a = ticks < FADE_IN ? ticks / (float) FADE_IN
				: ticks > LIFE - FADE_OUT ? (LIFE - ticks) / (float) FADE_OUT : 1f;
		a = Math.max(0f, Math.min(1f, a));
		int alpha = (int) (a * ((argb >>> 24) & 0xFF));
		int color = (alpha << 24) | (argb & 0xFFFFFF);
		var font = Minecraft.getInstance().font;
		int w = gfx.guiWidth();
		int h = gfx.guiHeight();
		// 居中偏上（约屏幕 1/3 处），下方一条同色细线作分隔
		int y = h / 3;
		gfx.drawCenteredString(font, title, w / 2, y, color);
		int lineW = font.width(title) / 2 + 8;
		int lineColor = (alpha / 3 << 24) | (argb & 0xFFFFFF);
		gfx.fill(w / 2 - lineW, y + 14, w / 2 + lineW, y + 15, lineColor);
	}
}
