package mcr.riichi_arise.voice;

import java.util.List;

/**
 * 报番同步状态（客户端）：把役种语音的播放进度暴露给结算界面，
 * 实现"念完上一条 → 下一个役种显现 + 打点音效"的节奏。
 * <p>
 * 进度由播放完成回调驱动（VoicePlayer 的 onDone）：act（荣/自摸）语音播完 → 第一个役种显现；
 * 每条役种语音播完 → 下一个役种显现。角色无报番音频时保持 inactive，
 * 结算界面回退为固定节奏逐条显示。
 * </p>
 */
public final class FanAnnouncer {
	private FanAnnouncer() {
	}

	/** 本次报番的役种中文名（按播报顺序，仅含实际有音频的役） */
	private static volatile List<String> spoken = List.of();
	/** 附加播报数（宝牌计数等，不参与界面逐条显现映射，只计入播报进度） */
	private static volatile int extras;
	/** 已开始播报的条目数（0 = 还没轮到第一个役；act 播完 → 1，每条语音播完 +1） */
	private static volatile int started;
	/** 全部播报完成（剩余宝牌等条目此时全部显现） */
	private static volatile boolean done;
	/** 是否存在报番任务（角色有报番音频并已入队） */
	private static volatile boolean active;
	/** 待播的点数等级语音所用的角色 id（结算点数显现时由结算界面触发播放） */
	private static volatile String levelVoiceId = "";

	/** 开始一次报番：zhNames 为按播报顺序的役种中文名；extraCount 为宝牌计数等附加播报数 */
	static void begin(List<String> zhNames, int extraCount, String voiceId) {
		spoken = List.copyOf(zhNames);
		extras = Math.max(0, extraCount);
		started = 0;
		done = false;
		active = true;
		levelVoiceId = voiceId == null ? "" : voiceId;
	}

	/** 无可播报的役种音频：保持/置为 inactive，结算界面回退固定节奏 */
	static void invalidate() {
		spoken = List.of();
		extras = 0;
		started = 0;
		done = false;
		active = false;
		levelVoiceId = "";
	}

	/** 结算点数显现时播报点数等级（满贯/跳满/倍满/三倍满/累计役满/N倍役满）；levelKey 为空则跳过 */
	public static void playLevel(String levelKey) {
		String id = levelVoiceId;
		if (levelKey == null || levelKey.isEmpty() || id.isEmpty())
			return;
		levelVoiceId = ""; // 一局只播一次，避免残留到无报番的下一局
		VoicePlayer.enqueue(id, levelKey);
	}

	/** act（荣/自摸）语音播完：第一个役种显现（无报番任务时 no-op） */
	public static void actDone() {
		advance();
	}

	/** 一条语音播完：下一个役种显现 + 打点音效；全部（含附加播报）完成 → done */
	public static void advance() {
		if (!active || done)
			return;
		started++;
		if (started >= spoken.size() + extras)
			done = true;
		tick();
	}

	/** 役种显现音效（模仿天凤报番的打点音；切回主线程播放） */
	private static void tick() {
		try {
			net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
			mc.execute(() -> mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
					.forUI(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1.5f)));
		} catch (Throwable ignored) {
		}
	}

	// —— 结算界面读取 ——

	/** 是否存在报番任务（决定结算界面用语音同步节奏还是固定节奏） */
	public static boolean isActive() {
		return active;
	}

	/** 全部役种语音是否播完（剩余宝牌条目此时全部显现） */
	public static boolean isDone() {
		return done;
	}

	/** 已开始播报的役种中文名（前 started 个，按播报顺序） */
	public static List<String> startedZh() {
		List<String> s = spoken;
		int n = Math.min(started, s.size());
		return n >= s.size() ? s : s.subList(0, n);
	}
}
