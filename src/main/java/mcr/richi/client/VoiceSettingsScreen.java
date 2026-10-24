package mcr.richi.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import mcr.richi.network.MahjongLobbyPayloads;
import mcr.richi.network.VoicePayloads;
import mcr.richi.voice.VoiceCache;
import mcr.richi.voice.VoiceCharacters;
import mcr.richi.voice.VoiceClient;

/**
 * 个人设置 - 角色语音界面（大厅左下角"个人设置"按钮进入）。
 * <p>样式与大厅管理界面一致：灰色半透明列表行（悬停高亮、选中高亮、禁用暗灰）。</p>
 * <ul>
 * <li>仅可修改自己的语音（对他人可见）。</li>
 * <li>离线内置 6 名角色标注「内置」，点击直接选用；在线角色标注「在线」，<b>点击后先下载，
 * 下载完成（6 条语音就绪）才真正选用</b>，下载中显示「下载中…」且不可重复点击。</li>
 * <li>列表可滚动（滚轮 + ▲/▼）；顶部可关闭语音、返回。</li>
 * </ul>
 */
public class VoiceSettingsScreen extends Screen {
	private static final int PANEL_W = 250;
	private static final int VISIBLE = 14;
	private static final int ROW_H = 18;
	private static final int ROW_GAP = 20;

	private final BlockPos origin;
	private final List<VoiceCharacters.Character> chars;
	/** 正在下载的角色 id（下载中显示"下载中…"且不可再点） */
	private final Set<String> downloading = new HashSet<>();
	private String currentVoice;
	private int scroll;

	public VoiceSettingsScreen(BlockPos origin) {
		super(Component.translatable("gui.riichi_arise.voice.title"));
		this.origin = origin;
		this.chars = VoiceCharacters.all();
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
		return Math.max(0, chars.size() - VISIBLE);
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
		this.downloading.clear();
	}

	/** 视口内第 row 行（0 起）的角色行 y 坐标 */
	private int rowY(int row) {
		return top() + 38 + 16 + row * ROW_GAP;
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
		// 上翻
		drawBtn(graphics, left, top + 38, PANEL_W, 14, "▲", mouseX, mouseY, scroll > 0);
		// 角色列表行（灰色半透明，悬停高亮；选中高亮文字；下载中禁用暗灰）
		if (scroll > maxScroll())
			scroll = maxScroll();
		for (int i = 0; i < VISIBLE; i++) {
			int idx = scroll + i;
			if (idx >= chars.size())
				break;
			VoiceCharacters.Character c = chars.get(idx);
			int y = rowY(i);
			boolean dl = downloading.contains(c.id());
			boolean ready = VoiceCache.isReady(c.id());
			boolean selected = c.id().equals(currentVoice);
			boolean enabled = !dl; // 下载中不可点
			boolean hovered = enabled && mouseX >= left && mouseX < left + PANEL_W && mouseY >= y
					&& mouseY < y + ROW_H;
			graphics.fill(left, y, left + PANEL_W, y + ROW_H, hovered ? 0x509090C0 : 0x30A0A0A0);
			StringBuilder label = new StringBuilder(selected ? "✔ " : "");
			label.append(c.displayName());
			if (dl)
				label.append(Component.translatable("gui.riichi_arise.voice.tag_downloading").getString());
			else if (c.offline())
				label.append(Component.translatable("gui.riichi_arise.voice.tag_offline").getString());
			else if (ready)
				label.append(Component.translatable("gui.riichi_arise.voice.tag_ready").getString());
			else
				label.append(Component.translatable("gui.riichi_arise.voice.tag_online").getString());
			int color = dl ? 0x808080 : selected ? 0xFFCC66 : 0xFFFFFF;
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
		// 上翻
		if (inBtn(mouseX, mouseY, left, top + 38, PANEL_W, 14)) {
			scroll = Math.max(0, scroll - VISIBLE);
			return true;
		}
		// 下翻
		int downY = rowY(VISIBLE);
		if (inBtn(mouseX, mouseY, left, downY, PANEL_W, 14)) {
			scroll = Math.min(maxScroll(), scroll + VISIBLE);
			return true;
		}
		// 角色行：点击 → 下载完成后选用（离线直接选用）
		for (int i = 0; i < VISIBLE; i++) {
			int idx = scroll + i;
			if (idx >= chars.size())
				break;
			int y = rowY(i);
			if (mouseY < y || mouseY >= y + ROW_H || mouseX < left || mouseX >= left + PANEL_W)
				continue;
			VoiceCharacters.Character c = chars.get(idx);
			if (downloading.contains(c.id()))
				return true; // 下载中忽略
			downloading.add(c.id());
			VoiceClient.selectVoice(c.id(), () -> Minecraft.getInstance().execute(() -> {
				downloading.remove(c.id());
				if (VoiceCache.isReady(c.id()))
					currentVoice = c.id(); // 下载完成才真正选用
			}));
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

	private boolean inBtn(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
