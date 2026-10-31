package mcr.riichi_arise.voice;

import java.util.List;

/**
 * 报番同步状态（客户端）：把役种语音的播放进度暴露给结算界面，
 * 实现"本条语音开始 → 对应役种同时显现 + 打点音效"的节奏。
 * <p>
 * 结算界面弹出后延迟 1s（{@link #start()}）才真正开始报番：此前 act（荣/自摸）语音
 * 即使已播完也不推进显现；{@link #start()} 把役种/宝牌语音按序入队，语音一开始播放就把
 * 对应条目显现出来（文字与语音同时出现）。最后一条有音频的语音播完才算报番结束，
 * 其后没有音频的条目此时一并显现。宝牌计数等附加条目排在役种之后，
 * 结算列表末尾的宝牌条目同样随它们的播报逐条显现（{@link #startedExtras()}）。
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
	/** 附加播报数（宝牌计数等，对应结算列表末尾的宝牌条目） */
	private static volatile int extras;
	/** 本次报番条目总数（役种 + 附加） */
	private static volatile int total;
	/** 已开始播报的条目数（第 i 条语音开始时置为 i+1；0 = 还没开始） */
	private static volatile int started;
	/** 全部播报完成（最后一条语音播完；剩余无音频条目此时全部显现） */
	private static volatile boolean done;
	/** 是否存在报番任务（角色有报番音频并已入队） */
	private static volatile boolean active;
	/** 是否已开始播报（结算界面弹出 1s 后置位） */
	private static volatile boolean begun;
	/** 本次报番所用角色 id（start 时入队语音用） */
	private static volatile String voiceId = "";
	/** 待播的点数等级语音所用的角色 id（结算点数显现时由结算界面触发播放） */
	private static volatile String levelVoiceId = "";
	/** 最后一条实际有音频的条目下标（-1 = 全部缺失）：它播完即报番结束 */
	private static volatile int lastVoiced = -1;

	/**
	 * 准备一次报番：zhNames 为按播报顺序的役种中文名；files 为待播语音文件名（役种在前、宝牌在后）；
	 * extraCount 为宝牌计数等附加播报数。此时只记状态，不播放——等结算界面弹出 1s 后由 {@link #start()} 开始。
	 */
	static void begin(List<String> zhNames, List<String> files, int extraCount, String charId) {
		spoken = List.copyOf(zhNames);
		pendingFiles = List.copyOf(files);
		extras = Math.max(0, extraCount);
		total = spoken.size() + extras;
		started = 0;
		done = false;
		active = true;
		begun = false;
		lastVoiced = -1;
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
		total = 0;
		started = 0;
		done = false;
		active = false;
		begun = false;
		lastVoiced = -1;
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
	 * 开始报番（结算界面弹出 1s 后调用，幂等）：把待播语音按序入队。
	 * 每条语音一开始播放就显现对应条目（文字与语音同时出现），最后一条播完即报番结束。
	 * 未准备报番任务时 no-op。
	 */
	public static void start() {
		synchronized (FanAnnouncer.class) {
			if (!active || begun)
				return;
			begun = true;
			done = total == 0;
			// 从干净队列开始：清掉上一轮没播完的报番语音（典型是末尾宝牌/段位语音），
			// 否则它们会排在本次番名之前先响，听起来像"宝牌比番名还早"
			VoicePlayer.clearFan();
			String id = voiceId;
			List<String> files = pendingFiles;
			pendingFiles = List.of();
			lastVoiced = -1;
			List<String> missing = new java.util.ArrayList<>();
			for (int i = 0; i < files.size(); i++) {
				int idx = i;
				String file = files.get(i);
				boolean ok = VoicePlayer.enqueueOrSkip(id, file,
						() -> onItemStart(idx), () -> onItemEnd(idx));
				if (ok)
					lastVoiced = idx;
				else
					missing.add(file);
			}
			// 报番语音由资源包提供（resourcepacks/riichi_arise_fan_voice）：日志里区分「整轮缺失」和「个别缺文件」
			if (lastVoiced < 0) {
				started = total;
				done = true;
				// 整轮音频缺失（映射表有键但资源包缺 mp3）：把 active 置 false，让结算界面回退到
				// "固定节奏逐条显现"（每条间隔 SETTLE_ANNOUNCE_STEP = 10 tick）。若保持 active，
				// 界面会误判"报番已完成"而一次性弹出全部役种（isActive && done → 全显）。
				active = false;
				if (!missing.isEmpty())
					com.mojang.logging.LogUtils.getLogger().info(
							"[voice] 未找到报番语音（{}）：{}——需安装报番语音资源包，本次按固定节奏显现",
							id, missing);
			} else if (!missing.isEmpty()) {
				com.mojang.logging.LogUtils.getLogger().warn("[voice] 报番音频缺失（{}）：{}", id, missing);
			}
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

	/** 第 idx 条语音开始播放：对应条目（及其前没有音频的条目）随语音一起显现 */
	private static void onItemStart(int idx) {
		synchronized (FanAnnouncer.class) {
			if (!active)
				return;
			int n = Math.min(total, idx + 1);
			if (n > started)
				started = n;
		}
		revealSound();
	}

	/** 第 idx 条语音播完：最后一条有音频的条目播完即报番结束，其后无音频的条目一并显现 */
	private static void onItemEnd(int idx) {
		synchronized (FanAnnouncer.class) {
			if (!active || done || idx < lastVoiced)
				return;
			started = total;
			done = true;
		}
	}

	/** 役种显现音效（模仿天凤报番的打点音；切回主线程播放） */
	private static void revealSound() {
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
