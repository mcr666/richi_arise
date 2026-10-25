package mcr.riichi_arise.voice;

import java.util.List;

/**
 * 报番同步状态（客户端）：把役种语音的播放进度暴露给结算界面，
 * 实现"念完上一条 → 下一个役种显现 + 打点音效"的节奏。
 * <p>
 * 结算界面弹出后延迟 1s（{@link #start()}）才真正开始报番：此前 act（荣/自摸）语音
 * 即使已播完也不推进显现；{@link #start()} 把役种/宝牌语音按序入队并立刻显现第一条。
 * 之后每条语音播完 → 下一个役种显现，且相邻两条之间至少间隔
 * {@link #MIN_ADVANCE_TICKS} tick（音频缺失或不完整时也逐条切换，不会一次性全显示）。
 * 宝牌计数等附加条目排在役种之后，结算列表末尾的宝牌条目同样随它们的播报逐条显现
 * （{@link #startedExtras()}），不会拖到全部播完才一起出现。
 * 角色无报番音频时保持 inactive，结算界面回退为固定节奏逐条显示。
 * </p>
 */
public final class FanAnnouncer {
	private FanAnnouncer() {
	}

	/** 本次报番的役种中文名（按播报顺序，仅含实际有音频的役） */
	private static volatile List<String> spoken = List.of();
	/** 待入队播放的语音文件名（役种在前、宝牌计数在后），{@link #start()} 时才真正播放 */
	private static volatile List<String> pendingFiles = List.of();
	/** 附加播报数（宝牌计数等，不参与界面逐条显现映射，只计入播报进度） */
	private static volatile int extras;
	/** 已开始播报的条目数（0 = 还没开始；start 时置 1，每条语音播完 +1） */
	private static volatile int started;
	/** 全部播报完成（剩余宝牌等条目此时全部显现） */
	private static volatile boolean done;
	/** 是否存在报番任务（角色有报番音频并已入队） */
	private static volatile boolean active;
	/** 是否已开始播报（结算界面弹出 1s 后置位；此前 act 播完不推进显现） */
	private static volatile boolean begun;
	/** 本次报番所用角色 id（start 时入队语音用） */
	private static volatile String voiceId = "";
	/** 待播的点数等级语音所用的角色 id（结算点数显现时由结算界面触发播放） */
	private static volatile String levelVoiceId = "";

	/** 相邻两个番种显现之间的最小间隔（tick）：音频缺失/过短时也逐条切换，不会一次性全显示 */
	private static final int MIN_ADVANCE_TICKS = 12;
	/** 客户端 tick 计数（结算界面每 tick 驱动 {@link #tick()} 递增） */
	private static volatile int age;
	/** 上次推进显现时的 age（保证与下一次间隔 >= MIN_ADVANCE_TICKS） */
	private static volatile int lastAdvanceAge;
	/** 待推进的显现条数（语音播完/音频缺失都记一次，按最小间隔逐条消费） */
	private static volatile int pendingAdvance;

	/**
	 * 准备一次报番：zhNames 为按播报顺序的役种中文名；files 为待播语音文件名（役种在前、宝牌在后）；
	 * extraCount 为宝牌计数等附加播报数。此时只记状态，不播放——等结算界面弹出 1s 后由 {@link #start()} 开始。
	 */
	static void begin(List<String> zhNames, List<String> files, int extraCount, String charId) {
		spoken = List.copyOf(zhNames);
		pendingFiles = List.copyOf(files);
		extras = Math.max(0, extraCount);
		started = 0;
		done = false;
		active = true;
		begun = false;
		age = 0;
		lastAdvanceAge = 0;
		pendingAdvance = 0;
		voiceId = charId == null ? "" : charId;
		levelVoiceId = voiceId;
		// 新一次报番：先清掉上一轮没播完的报番语音（末尾宝牌/段位语音最容易被留下），
		// 否则结算界面刚弹出时就会听见上一局的"宝牌"，像是宝牌抢在番名前面
		VoicePlayer.clearFan();
	}

	/** 无可播报的役种音频：保持/置为 inactive，结算界面回退固定节奏（同时清掉段位语音角色，避免沿用上一局） */
	static void invalidate() {
		spoken = List.of();
		pendingFiles = List.of();
		extras = 0;
		started = 0;
		done = false;
		active = false;
		begun = false;
		age = 0;
		lastAdvanceAge = 0;
		pendingAdvance = 0;
		voiceId = "";
		levelVoiceId = "";
		// 丢掉上一轮没播完的报番语音（末尾宝牌/段位语音），否则它们会排到下一局番名之前先响
		VoicePlayer.clearFan();
	}

	/** 结算界面关闭（手动确认 / 服务端推进）：清状态并中止残留报番语音，避免遗留到下一局 */
	public static void cancel() {
		invalidate();
	}

	/**
	 * 开始报番（结算界面弹出 1s 后调用，幂等）：把待播语音按序入队，并立刻显现第一条
	 * （等价于原先 act 语音播完推进的那一步）。未准备报番任务时 no-op。
	 */
	public static void start() {
		synchronized (FanAnnouncer.class) {
			if (!active || begun)
				return;
			begun = true;
			int total = spoken.size() + extras;
			started = total > 0 ? 1 : 0;
			if (total == 0)
				done = true;
			lastAdvanceAge = age; // 第一条显现后至少间隔 MIN_ADVANCE_TICKS 才切换下一条
			// 从干净队列开始：清掉上一轮没播完的报番语音（典型是末尾宝牌/段位语音），
			// 否则它们会排在本次番名之前先响，听起来像"宝牌比番名还早"
			VoicePlayer.clearFan();
			String id = voiceId;
			for (String file : pendingFiles) {
				// 缺音频（如内置语音包没有 tsumo.mp3）：记一次待推进，按最小间隔逐条显现，避免一次性全显示
				if (!VoicePlayer.enqueueOrSkip(id, file, FanAnnouncer::advance)) {
					com.mojang.logging.LogUtils.getLogger()
							.warn("[voice] 报番音频缺失: {} fan/{}（宝牌等条目会静默跳过）", id, file);
					advance();
				}
			}
			pendingFiles = List.of();
		}
	}

	/** 结算点数显现时播报点数等级（满贯/跳满/倍满/三倍满/累计役满/N倍役满）；levelKey 为空则跳过 */
	public static void playLevel(String levelKey) {
		String id = levelVoiceId;
		if (levelKey == null || levelKey.isEmpty() || id.isEmpty())
			return;
		levelVoiceId = ""; // 一局只播一次，避免残留到无报番的下一局
		VoicePlayer.enqueue(id, levelKey);
	}

	/** 一条语音播完（或被跳过）：记一次待推进，由客户端 {@link #tick()} 按最小间隔逐条显现 */
	public static void advance() {
		synchronized (FanAnnouncer.class) {
			if (!active || done || !begun)
				return;
			// 每条语音（含末尾宝牌）各记一次，最多记满总数，保证最后一条宝牌也走完显现节奏
			if (started + pendingAdvance <= spoken.size() + extras)
				pendingAdvance++;
		}
	}

	/**
	 * 客户端 tick（结算界面每 tick 调用）：推进 age，并在距上次显现满 {@link #MIN_ADVANCE_TICKS} 时
	 * 消费一条待推进——每次只切换一个番种，从而保证相邻番种至少间隔 12 tick。
	 */
	public static void tick() {
		age++;
		synchronized (FanAnnouncer.class) {
			if (!active || !begun || done || pendingAdvance <= 0)
				return;
			if (age - lastAdvanceAge < MIN_ADVANCE_TICKS)
				return;
			pendingAdvance--;
			lastAdvanceAge = age;
			started++;
			// 最后一条（末尾宝牌计数）也播完后才算结束：此前结算界面不进入番符/点数/段位阶段
			if (started > spoken.size() + extras)
				done = true;
		}
		tickSound();
	}

	/** 役种显现音效（模仿天凤报番的打点音；切回主线程播放） */
	private static void tickSound() {
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

	/** 已开始播报的附加条数（宝牌计数等）：役种播完之后才逐个推进，结算界面据此让宝牌条目随语音显现 */
	public static int startedExtras() {
		synchronized (FanAnnouncer.class) {
			return begun ? Math.max(0, started - spoken.size()) : 0;
		}
	}

	/** 本次报番的附加播报总数（宝牌计数等，对应结算列表末尾的宝牌条目数） */
	public static int extrasTotal() {
		return extras;
	}
}
