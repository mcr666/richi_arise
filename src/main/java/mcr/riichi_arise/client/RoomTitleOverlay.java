package mcr.riichi_arise.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * 对局开始标题：每次对局开始时由服务端推给各参与者，
 * 内容为当前对局类型（铁之间 = 灰色 / 玻璃之间 = 浅蓝色）。
 * 用原版 Toast 呈现（右下角，原版淡入淡出/堆叠/不拦截输入），颜色以带样式文本实现。
 */
public final class RoomTitleOverlay {
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
}
