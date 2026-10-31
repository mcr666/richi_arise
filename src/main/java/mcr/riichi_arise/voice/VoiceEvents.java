package mcr.riichi_arise.voice;

/**
 * 天凤式语音事件：对局中触发某角色语音的动作。
 * <ul>
 * <li>{@link #act()}：语音文件名前缀（act_chi 等），也是离线/在线资源文件名。</li>
 * <li>{@link #code()}：网络包中的事件编码（C→S / S→C 共用）。</li>
 * <li>{@link #seIds()}：天凤 se.json 里该事件对应的 SE id（碰 25/26、杠 27/28、吃 29/30、
 * 立直 31/32、荣 33/34、自摸 35/36），留作按 se.json 精确取 URL 的备选实现。</li>
 * </ul>
 */
public enum VoiceEvents {
	CHI(0, "act_chi", 29, 30),
	PON(1, "act_pon", 25, 26),
	KAN(2, "act_kan", 27, 28),
	RICH(3, "act_rich", 31, 32),
	RON(4, "act_ron", 33, 34),
	TUMO(5, "act_tumo", 35, 36),
	/** 三麻拔北（内部 id babei）：仅内置角色随 mod 打包音频，不参与在线角色下载与齐全校验 */
	BEI(6, "act_babei"),
	/** 流局听牌 / 未听牌：内置角色可选提供音频（缺失时静默跳过，不写日志） */
	TENPAI(7, "act_tenpai"),
	NOTEN(8, "act_noten");

	/** 在线角色需下载的 6 条基础语音（act_babei 不在其中：tenhou-se 无此文件，齐全校验与下载只看这 6 条） */
	public static VoiceEvents[] core() {
		return new VoiceEvents[] { CHI, PON, KAN, RICH, RON, TUMO };
	}

	private final int code;
	private final String act;
	private final int[] seIds;

	VoiceEvents(int code, String act, int... seIds) {
		this.code = code;
		this.act = act;
		this.seIds = seIds;
	}

	public int code() {
		return code;
	}

	public String act() {
		return act;
	}

	public int[] seIds() {
		return seIds;
	}

	/** 网络编码 → 事件；未知编码回退为 CHI */
	public static VoiceEvents byCode(int code) {
		for (VoiceEvents ev : values())
			if (ev.code == code)
				return ev;
		return CHI;
	}
}
