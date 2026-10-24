package mcr.riichi_arise.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 报番语音（仅内置）：和牌结算时在 act 语音（荣/自摸）之后按役种顺序追加播报。
 * <p>
 * 音频取自雀魂角色的役种语音（mjsdb），随 mod 打包于
 * {@code assets/riichi_arise/sounds/voice/<id>/fan/<key>.mp3}；
 * 其余角色无报番音频，播报时静默跳过。
 * 映射键与 {@code Mahjong4jBridge.WinResult.yakuNames()} 的英文役名一致（同 YakuNames 表），
 * 另含宝牌计数键（DORA/URA/RED + 数量，表/里/红通用按数量报）。
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
	 * <li>宝牌计数键 {@code DORA/URA/RED + 数量}（表/里/红通用按数量报）：作为附加播报，
	 * 不参与界面逐条映射（结算界面宝牌条目在全部播报完成后显现）。</li>
	 * </ul>
	 * 一条役种音频都没有时通知界面回退固定节奏。
	 */
	public static void enqueue(String voiceId, List<String> keys) {
		if (voiceId == null || voiceId.isEmpty() || keys == null)
			return;
		List<String> fanKeys = new ArrayList<>();
		List<String> zh = new ArrayList<>();
		List<String> extraKeys = new ArrayList<>();
		for (String key : keys) {
			if (key == null || key.isEmpty())
				continue;
			if (key.startsWith("DORA") || key.startsWith("URA") || key.startsWith("RED")) {
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
		if (fanKeys.isEmpty()) {
			if (!FanAnnouncer.isActive())
				FanAnnouncer.invalidate(); // 界面回退固定节奏（不打断进行中的上一轮报番）
			return;
		}
		FanAnnouncer.begin(zh, extraKeys.size(), voiceId);
		for (String file : fanKeys)
			VoicePlayer.enqueue(voiceId, file, FanAnnouncer::advance);
		for (String file : extraKeys)
			VoicePlayer.enqueue(voiceId, file, FanAnnouncer::advance);
	}

	/** 去掉自风/场风的方位后缀（JIKAZE_TON → JIKAZE） */
	private static String basicName(String key) {
		int i = key.indexOf('_');
		return i < 0 ? key : key.substring(0, i);
	}

	/** 宝牌计数键 → 音频文件名：无数量或 1 → dora；13+ → dora13（一大堆）；其余 → doraN */
	private static String doraKey(String token) {
		String num = token.replaceFirst("^(DORA|URA|RED)", "");
		int count;
		try {
			count = num.isEmpty() ? 1 : Integer.parseInt(num);
		} catch (NumberFormatException e) {
			return null;
		}
		if (count <= 0)
			return null;
		return count >= 13 ? "dora13" : count == 1 ? "dora" : "dora" + count;
	}
}
