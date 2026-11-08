package mcr.riichi_arise.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 报番语音（资源包驱动）：和牌结算时在 act 语音（荣/自摸）之后按役种顺序追加播报。
 * <p>
 * 音频不随 mod 打包，由资源包提供：{@code assets/<命名空间>/sounds/voice/<id>/fan/<key>.mp3}
 * （随仓库附带的报番语音资源包在 {@code resourcepacks/riichi_arise_fan_voice}）。
 * 资源包未安装、或该角色没有报番音频时静默跳过，结算界面回退为固定节奏逐条显示。
 * 映射键与 {@code Mahjong4jBridge.WinResult.yakuNames()} 的英文役名一致（同 YakuNames 表），
 * 另含宝牌计数键（DORA/URA/RED/KITA + 数量，统一按张数报 dora 系列；拔北与宝牌同音）。
 * </p>
 */
public final class YakuVoices {
	private YakuVoices() {
	}

	/** 单次和牌最多播报的役种数（役种过多时截断，避免报番拖太长） */
	private static final int MAX_FAN = 8;

	/** 英文役名 → fan 音频文件名（自风/场风直接报牌名：东南西北） */
	private static final Map<String, String> FAN = Map.ofEntries(
			// 一般役
			Map.entry("TANYAO", "tanyao"),
			Map.entry("PINFU", "pinfu"),
			Map.entry("IPEIKO", "ipeiko"),
			Map.entry("RYANPEIKO", "ryanpeikou"),
			Map.entry("SANSHOKUDOHJUN", "sanshokudoujun"),
			Map.entry("SANSHOKUDOHKO", "sanshokudoukou"),
			Map.entry("IKKITSUKAN", "ittsuu"),
			Map.entry("TOITOIHO", "toitoi"),
			Map.entry("SANANKO", "sanankou"),
			Map.entry("SANKANTSU", "sankantsu"),
			Map.entry("SUKANTSU", "sukantsu"),
			Map.entry("SHOSANGEN", "shousangen"),
			Map.entry("CHITOITSU", "chiitoi"),
			Map.entry("CHANTA", "chanta"),
			Map.entry("JUNCHAN", "junchan"),
			Map.entry("HONROHTOH", "honroutou"),
			Map.entry("CHINROTO", "chinrutou"),
			Map.entry("HONITSU", "honitsu"),
			Map.entry("CHINITSU", "chinitsu"),
			Map.entry("TSUISO", "tsuuiisou"),
			Map.entry("RYUISO", "ryuuiisou"),
			Map.entry("REACH", "riichi"),
			Map.entry("DOUBLE_REACH", "daburii"),
			// 门清自摸（需要 fan/tsumo.mp3；内置语音包未附带该条，缺文件时静默跳过）
			Map.entry("TSUMO", "tsumo"),
			// 机会役
			Map.entry("CHANKAN", "chankan"),
			Map.entry("RINSHANKAIHOH", "rinshan"),
			Map.entry("HAITEI", "haitei"),
			Map.entry("HOUTEI", "houtei"),
			// 三元牌（白/发/中）
			Map.entry("HAKU", "haku"),
			Map.entry("HATSU", "hatsu"),
			Map.entry("CHUN", "chun"),
			// 机会役
			Map.entry("IPPATSU", "ippatsu"),
			// 自风/场风：服务端按具体方位带后缀（_TON/_NAN/_SHAA/_PEI），报牌名（东南西北）
			Map.entry("JIKAZE_TON", "ton"),
			Map.entry("JIKAZE_NAN", "nan"),
			Map.entry("JIKAZE_SHAA", "shaa"),
			Map.entry("JIKAZE_PEI", "pei"),
			Map.entry("BAKAZE_TON", "ton"),
			Map.entry("BAKAZE_NAN", "nan"),
			Map.entry("BAKAZE_SHAA", "shaa"),
			Map.entry("BAKAZE_PEI", "pei"),
			// 役满
			Map.entry("TENHO", "tenhou"),
			Map.entry("CHIHO", "chiho"),
			Map.entry("KOKUSHIMUSO", "kokushi"),
			Map.entry("KOKUSHIMUSO_13", "kokushi13"),
			Map.entry("SUANKO", "suankou"),
			Map.entry("SUANKO_TANKI", "suankoutanki"),
			Map.entry("DAISANGEN", "daisangen"),
			Map.entry("SHOSUSHI", "shousuushi"),
			Map.entry("DAISUSHI", "daisuushi"),
			Map.entry("CHURENPOHTO", "chuuren"),
			Map.entry("CHURENPOHTO_PURE", "chuurenpure"));

	/**
	 * 把某角色某次和牌的报番键列表追加进报番队列（客户端调用）。
	 * <ul>
	 * <li>役种英文名（自风/场风带方位后缀）：有对应音频的按序播报，并进入界面显现映射；</li>
	 * <li>宝牌计数键 {@code DORA/URA/RED/KITA + 数量}（表/里/红/拔北通用按数量报）：作为附加播报排在役种之后，
	 * 结算列表末尾的宝牌条目随对应语音逐条显现（{@link FanAnnouncer#startedExtras()}）；
	 * 服务端顺序为表 → 红 → 拔北（三麻）→ 里</li>
	 * </ul>
	 * 该角色没有报番音频（如只有 act 语音的桔螈）时借用有报番音频的默认角色，保证能出声（音色不同）。
	 * 一条役种音频都没有时通知界面回退固定节奏。
	 * @param source 音源世界坐标（牌桌中心），报番语音按此位置做距离衰减；null = 不做距离衰减
	 */
	public static void enqueue(String voiceId, List<String> keys, net.minecraft.world.phys.Vec3 source) {
		if (voiceId == null || voiceId.isEmpty() || keys == null) {
			// 该家没有语音（默认角色/未选语音）：清掉上一局残留的报番与段位语音状态，
			// 否则结算界面会误判报番已完成（一次性显示）或沿用其他角色的段位语音
			FanAnnouncer.invalidate();
			return;
		}
		List<String> fanKeys = new ArrayList<>();
		List<String> zh = new ArrayList<>();
		List<String> extraKeys = new ArrayList<>();
		for (String key : keys) {
			if (key == null || key.isEmpty())
				continue;
			if (key.startsWith("DORA") || key.startsWith("URA") || key.startsWith("RED")
					|| key.startsWith("KITA")) {
				String k = doraKey(key);
				if (k != null)
					extraKeys.add(k);
				continue;
			}
			if (fanKeys.size() >= MAX_FAN)
				continue;
			String file = FAN.get(key);
			if (file == null)
				continue;
			fanKeys.add(file);
			// 界面映射用不带方位后缀的基础役名（自风牌/场风牌，与结算列表条目一致）
			zh.add(mcr.riichi_arise.game.riichi.YakuNames.zh(basicName(key)));
		}
		if (fanKeys.isEmpty() && extraKeys.isEmpty()) {
			// 本轮没有任何报番音频：清掉上一轮遗留状态后回退固定节奏。
			// 若不清（尤其上一轮已播完、done=true），结算界面会误判"报番已完成"而一次性显示全部役种。
			FanAnnouncer.invalidate();
			return;
		}
		// 语音文件按播出顺序排好（役种在前、宝牌计数在后），交给 FanAnnouncer 在界面弹出 1s 后开始。
		// 只有宝牌没有役种音频（如役名无对应音频）时也要照常报宝牌，不能整轮丢弃。
		List<String> files = new ArrayList<>(fanKeys);
		files.addAll(extraKeys);
		// 该角色整轮没有报番音频（oraxolotl 等只有 act 语音）：整轮借用默认角色，避免同一轮内音色混用；
		// 若只是个别条目缺文件（角色本身有报番音频），保持原角色，缺的那条静默，不做单条借用
		String id = resolveFanVoice(voiceId, files);
		com.mojang.logging.LogUtils.getLogger().info("[voice] 报番计划 {} 键={} 役种={} 宝牌={} 文件={}",
				id, keys, zh, extraKeys, files);
		FanAnnouncer.begin(zh, files, extraKeys.size(), id, source);
	}

	/** 报番音频探测哨兵：资源包里的内置角色都带 {@code fan/dora.mp3}，用它判断该角色是否整轮缺报番音频 */
	private static final String FAN_SENTINEL = "dora";

	/**
	 * 报番语音角色兜底：该角色没有报番音频时，借用资源包内有报番音频的默认角色（第一个可用的离线角色），
	 * 保证和牌仍能报出役种（音色不同），并写日志提示缺资源，便于后续补专属 {@code fan/*.mp3}。
	 */
	private static String resolveFanVoice(String voiceId, List<String> files) {
		if (files.isEmpty() || hasFanVoice(voiceId))
			return voiceId;
		String alt = fanVoiceOtherThan(voiceId);
		if (!alt.isEmpty()) {
			com.mojang.logging.LogUtils.getLogger().info(
					"[voice] 角色 {} 缺报番语音资源，借用 {} 的报番语音（音色不同，后续可补专属 fan 音频）",
					voiceId, alt);
			return alt;
		}
		// 资源包整体未安装：保持原角色，由 FanAnnouncer 回退固定节奏（不刷兜底日志）
		return voiceId;
	}

	/** 除 exclude 外第一个有报番音频的离线角色（没有则返回空串）：仅用于整轮换音色的兜底 */
	private static String fanVoiceOtherThan(String exclude) {
		for (String id : VoiceCharacters.OFFLINE_IDS) {
			if (id.equals(exclude))
				continue;
			if (hasFanVoice(id))
				return id;
		}
		return "";
	}

	/** 该角色是否有报番音频（探测资源包内 fan/dora.mp3） */
	private static boolean hasFanVoice(String id) {
		try (java.io.InputStream in = VoiceCache.openFan(id, FAN_SENTINEL)) {
			return in != null;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * 只去掉自风/场风的方位后缀（JIKAZE_TON → JIKAZE、BAKAZE_PEI → BAKAZE），
	 * 其余带下划线的役名（SUANKO_TANKI / KOKUSHIMUSO_13 / CHURENPOHTO_PURE / DOUBLE_REACH）原样保留——
	 * 截断会让界面显现映射找不到对应条目，那条役种的文字只能等到报番结束才出现。
	 */
	private static String basicName(String key) {
		if (key.startsWith("JIKAZE_") || key.startsWith("BAKAZE_"))
			return key.substring(0, key.indexOf('_'));
		return key;
	}

	/** 宝牌计数键 → 音频文件名：表/里/红/拔北统一用 dora 系列（拔北与宝牌同音）。
	 *  <p>「拔北1 + 宝牌1」会同为 {@code dora}：队列按条目逐个入队、每条各自新开音频流，
	 *  同名文件不会去重/覆盖，两条会连续各播一次。</p> */
	private static String doraKey(String token) {
		String num = token.replaceFirst("^(DORA|URA|RED|KITA)", "");
		int count;
		try {
			count = num.isEmpty() ? 1 : Integer.parseInt(num);
		} catch (NumberFormatException e) {
			return null;
		}
		if (count <= 0)
			return null;
		// 拔北（KITA）与表/里/红宝牌共用 dora 系列：按条目入队，同名不会合并
		return count >= 13 ? "dora13" : count == 1 ? "dora" : "dora" + count;
	}
}
