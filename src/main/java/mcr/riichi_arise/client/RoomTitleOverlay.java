package mcr.riichi_arise.client;

import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

import mcr.riichi_arise.RichiiAriseMod;

/**
 * 对局开始标题：每次对局开始时由服务端推给各参与者，内容为当前对局类型。
 * <ul>
 * <li>普通房间：用原版 Toast 呈现（右下角，原版淡入淡出/堆叠/不拦截输入），颜色以带样式文本实现。</li>
 * <li>混沌之间：改为屏幕正中演出「xxx混沌之间xxx」——两侧 xxx 为原版闪烁乱码字体，
 * 整串文字在紫/黑/蓝之间循环变色（与役满番种逐字彩色同一做法），停留 3 秒后渐隐消失。</li>
 * </ul>
 */
@EventBusSubscriber(modid = RichiiAriseMod.MODID, value = Dist.CLIENT)
public final class RoomTitleOverlay {
	/** 混沌标题停留时长（毫秒） */
	private static final long CHAOS_HOLD_MS = 3000;
	/** 停留结束后的渐隐时长（毫秒） */
	private static final long CHAOS_FADE_MS = 800;
	/** 混沌标题字号倍率（屏幕中央更醒目） */
	private static final float CHAOS_SCALE = 2.0f;
	/** 颜色循环周期（毫秒） */
	private static final long CHAOS_CYCLE_MS = 2400;
	/** 循环色板：紫 → 黑 → 蓝 */
	private static final int[] CHAOS_RGB = { 0xC46BFF, 0x000000, 0x3A6BFF };
	/** 两侧乱码占位字数（xxx 各 3 个） */
	private static final String CHAOS_GLITCH = "xxx";

	/** 混沌标题开始显示的时间（毫秒，-1 = 未显示） */
	private static long chaosStart = -1;
	/** 混沌标题中央文字（房间名） */
	private static Component chaosName = Component.empty();

	private RoomTitleOverlay() {
	}

	/** 显示标题：顶行 header，正文 body（按颜色着色） */
	public static void show(Component header, Component body, int color) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getToasts() == null)
			return;
		mc.getToasts().addToast(new SystemToast(
				SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
				header,
				body.copy().withStyle(s -> s.withColor(color & 0xFFFFFF))));
	}

	/** 显示混沌之间标题（屏幕中央演出） */
	public static void showChaos(Component name) {
		chaosName = name;
		chaosStart = Util.getMillis();
	}

	@SubscribeEvent
	public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
		event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(RichiiAriseMod.MODID, "richi_room_title"),
				(LayeredDraw.Layer) RoomTitleOverlay::renderChaos);
	}

	private static void renderChaos(GuiGraphics g, DeltaTracker partialTick) {
		if (chaosStart < 0)
			return;
		long elapsed = Util.getMillis() - chaosStart;
		long total = CHAOS_HOLD_MS + CHAOS_FADE_MS;
		if (elapsed >= total) {
			chaosStart = -1;
			return;
		}
		// 停留 3 秒，之后线性渐隐
		float alpha = elapsed <= CHAOS_HOLD_MS ? 1.0f
				: 1.0f - (elapsed - CHAOS_HOLD_MS) / (float) CHAOS_FADE_MS;
		int alphaBits = Math.max(0, Math.min(255, Math.round(alpha * 255f))) << 24;

		Minecraft mc = Minecraft.getInstance();
		var font = mc.font;
		String name = chaosName.getString();
		String full = CHAOS_GLITCH + name + CHAOS_GLITCH;
		int textW = font.width(full);
		int textH = font.lineHeight;
		float x0 = (g.guiWidth() - textW * CHAOS_SCALE) / 2f;
		float y0 = (g.guiHeight() - textH * CHAOS_SCALE) / 2f;
		// 色相在紫/黑/蓝间循环；逐字略作相位偏移，效果与结算界面役满番种彩色字一致
		float phase = (elapsed % CHAOS_CYCLE_MS) / (float) CHAOS_CYCLE_MS * CHAOS_RGB.length;
		var pose = g.pose();
		pose.pushPose();
		pose.translate(x0, y0, 0);
		pose.scale(CHAOS_SCALE, CHAOS_SCALE, 1.0f);
		int cx = 0;
		for (int i = 0; i < full.length(); i++) {
			String ch = String.valueOf(full.charAt(i));
			boolean glitch = i < CHAOS_GLITCH.length() || i >= CHAOS_GLITCH.length() + name.length();
			Component part = Component.literal(ch);
			if (glitch)
				part = part.copy().withStyle(ChatFormatting.OBFUSCATED);
			g.drawString(font, part, cx, 0, alphaBits | cycleColor(phase - i * 0.08f), true);
			cx += font.width(ch);
		}
		pose.popPose();
	}

	/** 按相位在紫/黑/蓝色板间插值（phase 每 +1 前进一个色段） */
	private static int cycleColor(float phase) {
		int n = CHAOS_RGB.length;
		float p = phase % n;
		if (p < 0)
			p += n;
		int i = (int) p;
		float f = p - i;
		int a = CHAOS_RGB[i];
		int b = CHAOS_RGB[(i + 1) % n];
		int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * f);
		int gr = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * f);
		int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * f);
		return (r << 16) | (gr << 8) | bl;
	}
}
