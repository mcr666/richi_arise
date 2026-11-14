package mcr.riichi_arise.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import mcr.riichi_arise.RichiConfig;
import mcr.riichi_arise.network.MahjongLobbyPayloads;
import mcr.riichi_arise.network.VoicePayloads;
import mcr.riichi_arise.voice.VoiceCache;
import mcr.riichi_arise.voice.VoiceCharacters;
import mcr.riichi_arise.voice.VoiceClient;

/**
 * 个人设置 - 角色语音界面（大厅左下角"个人设置"按钮进入）。
 * <p>样式与大厅管理界面一致：灰色半透明列表行（悬停高亮、选中高亮、禁用暗灰）。</p>
 * <ul>
 * <li>仅可修改自己的语音（对他人可见）。</li>
 * <li>角色列表来自已加载的资源包（{@code sounds/voice/<id>/}）：内置角色标注「内置」，
 * 其余标注「资源包」；点击立即选用，不再联网下载。未装对应语音包的玩家听不到你的语音。</li>
 * <li>列表可滚动（滚轮 + ▲/▼）；顶部可关闭语音、返回。</li>
 * </ul>
 */
public class VoiceSettingsScreen extends Screen {
	private static final int PANEL_W = 250;
	private static final int VISIBLE = 14;
	private static final int ROW_H = 18;
	private static final int ROW_GAP = 20;
	/** 角色音量滑块行高与轨道宽 */
	private static final int SLIDER_H = 18;
	private static final int SLIDER_TRACK_W = 100;

	private final BlockPos origin;
	/** 资源包中可用的角色 id（打开界面时扫描一次） */
	private final List<String> ids;
	private String currentVoice;
	private int scroll;
	/** 角色音量滑块是否正在拖动 */
	private boolean draggingVolume;

	public VoiceSettingsScreen(BlockPos origin) {
		super(Component.translatable("gui.riichi_arise.voice.title"));
		this.origin = origin;
		this.ids = VoiceCache.voiceIds();
	}

	public static void open(BlockPos origin) {
		Minecraft.getInstance().setScreen(new VoiceSettingsScreen(origin));
	}

	private int left() {
		return this.width / 2 - PANEL_W / 2;
	}

	private int top() {
		return this.height / 2 - 150;
	}

	private int maxScroll() {
		return Math.max(0, ids.size() - VISIBLE);
	}

	/** 从最近 Sync 取自己当前的语音（空串 = 关闭） */
	private String ownVoice() {
		MahjongLobbyPayloads.LobbySync s = MahjongLobbyPayloads.CLIENT_SYNC.get(origin.asLong());
		if (s == null || s.ownVoice() == null)
			return "";
		return s.ownVoice();
	}

	@Override
	protected void init() {
		this.currentVoice = ownVoice();
	}

	/** 视口内第 row 行（0 起）的角色行 y 坐标 */
	private int rowY(int row) {
		return top() + 58 + 16 + row * ROW_GAP;
	}

	/** 角色音量滑块行 y */
	private int sliderY() {
		return top() + 38;
	}

	/** 音量轨道起点 x（右侧留百分比文字 + 边距） */
	private int sliderTrackX() {
		return left() + PANEL_W - 8 - 36 - SLIDER_TRACK_W;
	}

	/** 按横坐标设置音量（clamp 0~1）并保存配置，实时作用于播放线程 */
	private void setVolume(double mx) {
		double v = (mx - sliderTrackX()) / SLIDER_TRACK_W;
		v = Math.max(0.0, Math.min(1.0, v));
		RichiConfig.VOICE_VOLUME.set(v);
		RichiConfig.SPEC.save();
	}

	/** 角色音量滑块：标签 + 轨道（填充）+ 百分比 + 圆点 */
	private void drawVolumeSlider(GuiGraphics graphics) {
		int left = left();
		int y = sliderY();
		int trackX = sliderTrackX();
		int trackY = y + (SLIDER_H - 6) / 2;
		graphics.fill(left, y, left + PANEL_W, y + SLIDER_H, 0x30A0A0A0);
		graphics.drawString(this.font,
				Component.translatable("gui.riichi_arise.voice.volume").getString(), left + 8, y + 5, 0xFFFFFF, false);
		// 轨道底色 + 已填充部分
		graphics.fill(trackX, trackY, trackX + SLIDER_TRACK_W, trackY + 6, 0x808080);
		double vol = RichiConfig.VOICE_VOLUME.get().doubleValue();
		int fill = (int) Math.round(SLIDER_TRACK_W * vol);
		graphics.fill(trackX, trackY, trackX + fill, trackY + 6, 0xFFCC66);
		// 百分比文字
		graphics.drawString(this.font, Math.round(vol * 100) + "%", trackX + SLIDER_TRACK_W + 6, y + 5, 0xFFCC66, false);
		// 滑块圆点
		int knobX = trackX + fill;
		graphics.fill(knobX - 3, y + 2, knobX + 3, y + SLIDER_H - 2, 0xFFFFFF);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		this.renderBackground(graphics, mouseX, mouseY, partialTick);
		int left = left();
		int top = top();
		var font = this.font;
		// 标题 + 当前语音
		graphics.drawCenteredString(font, this.title, this.width / 2, top - 14, 0xFFFFFF);
		String cur = currentVoice.isEmpty()
				? Component.translatable("gui.riichi_arise.voice.none").getString()
				: VoiceCharacters.displayName(currentVoice);
		graphics.drawCenteredString(font,
				Component.translatable("gui.riichi_arise.voice.current", cur).getString(), this.width / 2, top + 2, 0xAAAAAA);
		// 顶部：关闭语音 / 返回（灰色半透明按钮）
		drawBtn(graphics, left, top + 16, PANEL_W / 2 - 2, 18,
				Component.translatable("gui.riichi_arise.voice.btn_off").getString(), mouseX, mouseY, true);
		drawBtn(graphics, left + PANEL_W / 2 + 2, top + 16, PANEL_W / 2 - 2, 18,
				Component.translatable("gui.riichi_arise.voice.btn_back").getString(), mouseX, mouseY, true);
		// 角色音量滑块
		drawVolumeSlider(graphics);
		// 上翻
		drawBtn(graphics, left, top + 58, PANEL_W, 14, "▲", mouseX, mouseY, scroll > 0);
		// 角色列表行（灰色半透明，悬停高亮；选中高亮文字）
		if (scroll > maxScroll())
			scroll = maxScroll();
		for (int i = 0; i < VISIBLE; i++) {
			int idx = scroll + i;
			if (idx >= ids.size())
				break;
			String id = ids.get(idx);
			int y = rowY(i);
			boolean selected = id.equals(currentVoice);
			boolean hovered = mouseX >= left && mouseX < left + PANEL_W && mouseY >= y
					&& mouseY < y + ROW_H;
			graphics.fill(left, y, left + PANEL_W, y + ROW_H, hovered ? 0x509090C0 : 0x30A0A0A0);
			StringBuilder label = new StringBuilder(selected ? "✔ " : "");
			label.append(VoiceCharacters.displayName(id));
			label.append(Component.translatable(VoiceCharacters.isBuiltin(id)
					? "gui.riichi_arise.voice.tag_offline"
					: "gui.riichi_arise.voice.tag_pack").getString());
			int color = selected ? 0xFFCC66 : 0xFFFFFF;
			graphics.drawString(font, label.toString(), left + 8, y + 5, color, false);
		}
		// 下翻
		int downY = rowY(VISIBLE);
		drawBtn(graphics, left, downY, PANEL_W, 14, "▼", mouseX, mouseY, scroll < maxScroll());
		// 底部提示
		graphics.drawCenteredString(font,
				Component.translatable("gui.riichi_arise.voice.hint").getString(), this.width / 2, downY + 16, 0x808080);
	}

	/** 灰色半透明按钮（与大厅同款）：悬停高亮，禁用暗灰文字不可点 */
	private void drawBtn(GuiGraphics graphics, int x, int y, int w, int h, String label, int mouseX, int mouseY,
			boolean enabled) {
		boolean hov = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		graphics.fill(x, y, x + w, y + h, hov ? 0x509090C0 : 0x30A0A0A0);
		graphics.drawString(this.font, label, x + (w - this.font.width(label)) / 2, y + 4,
				enabled ? 0xFFFFFF : 0x808080, false);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button != 0)
			return super.mouseClicked(mouseX, mouseY, button);
		int left = left();
		int top = top();
		// 关闭语音
		if (inBtn(mouseX, mouseY, left, top + 16, PANEL_W / 2 - 2, 18)) {
			VoiceClient.setOwnVoice(VoicePayloads.NONE);
			onClose();
			return true;
		}
		// 返回
		if (inBtn(mouseX, mouseY, left + PANEL_W / 2 + 2, top + 16, PANEL_W / 2 - 2, 18)) {
			onClose();
			return true;
		}
		// 角色音量滑块：点击轨道定位并进入拖动
		int sx = sliderTrackX();
		if (mouseY >= sliderY() && mouseY < sliderY() + SLIDER_H
				&& mouseX >= sx - 6 && mouseX < sx + SLIDER_TRACK_W + 40) {
			setVolume(mouseX);
			draggingVolume = true;
			return true;
		}
		// 上翻
		if (inBtn(mouseX, mouseY, left, top + 58, PANEL_W, 14)) {
			scroll = Math.max(0, scroll - VISIBLE);
			return true;
		}
		// 下翻
		int downY = rowY(VISIBLE);
		if (inBtn(mouseX, mouseY, left, downY, PANEL_W, 14)) {
			scroll = Math.min(maxScroll(), scroll + VISIBLE);
			return true;
		}
		// 角色行：点击立即选用（语音由已加载资源包提供）
		for (int i = 0; i < VISIBLE; i++) {
			int idx = scroll + i;
			if (idx >= ids.size())
				break;
			int y = rowY(i);
			if (mouseY < y || mouseY >= y + ROW_H || mouseX < left || mouseX >= left + PANEL_W)
				continue;
			String id = ids.get(idx);
			VoiceClient.setOwnVoice(id);
			currentVoice = id;
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		if (dy < 0)
			scroll = Math.min(maxScroll(), scroll + 1);
		else
			scroll = Math.max(0, scroll - 1);
		return true;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (button == 0 && draggingVolume) {
			setVolume(mx);
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		draggingVolume = false;
		return super.mouseReleased(mx, my, button);
	}

	private boolean inBtn(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
