package mcr.riichi_arise.game.riichi;

import net.minecraft.resources.ResourceLocation;

import mcr.riichi_arise.RichiiAriseMod;

/**
 * AI 座位角色（风盘管理界面右键菜单可选）：默认 + 5 名内置角色。
 * <p>
 * 角色决定两件事：
 * <ul>
 * <li><b>AI 流派</b>（{@link #flow}，索引见 {@link RiichiBot#FLOW_NAMES}）；</li>
 * <li><b>语音</b>（{@link #voice}，即 {@link mcr.riichi_arise.voice.VoiceCharacters} 的离线角色 id，随 mod 内置）。</li>
 * </ul>
 * 外观：默认 = 随机名 + 在线同名玩家皮肤；5 名角色 = 本地皮肤贴图
 * {@code assets/riichi_arise/textures/entity/ai/<id>.png}（缺文件时客户端回退原版默认皮肤）。
 * </p>
 */
public final class AiCharacters {
	private AiCharacters() {
	}

	/** 默认（未选角色）：一般流；随机名 + 在线玩家皮肤；无语音 */
	public static final String DEFAULT = "default";

	/** 可选角色 id（顺序即右键菜单顺序）；同时是语音离线角色 id 与本地皮肤贴图文件名 */
	public static final String[] IDS = { DEFAULT, "bamuwei", "linlang", "fuji", "washizu", "akagi", "yiji" };

	/** 鬼神境流派索引（与 {@link RiichiBot#FLOW_NAMES} 对应；瓦西子/赤木与 NPC 参战者共用） */
	public static final int FLOW_ONIGAMI = 5;

	/**
	 * 角色 → 流派索引（与 {@link RiichiBot#FLOW_NAMES} 对应）：
	 * 默认/琳琅 = 一般流 0；八木唯 = 门清流 2；福姬 = 魂天流 4；瓦西子/赤木 = 鬼神境 5；一姬 = 野猪流 1。
	 */
	private static final int[] FLOWS = { 0, 2, 0, 4, FLOW_ONIGAMI, FLOW_ONIGAMI, 1 };

	/** 服务端座位显示名（与语言文件 gui.riichi_arise.lobby.ch_* 的 UI 名保持一致；默认无角色名，用随机名） */
	private static final String[] NAMES = { "", "八木唯", "琳琅", "福姬", "瓦西子", "赤木", "一姬" };

	/** 菜单/列表显示名数组下标；未知 id 返回 -1 */
	public static int index(String id) {
		if (id == null)
			return -1;
		for (int i = 0; i < IDS.length; i++)
			if (IDS[i].equals(id))
				return i;
		return -1;
	}

	public static boolean isKnown(String id) {
		return index(id) >= 0;
	}

	/** 该 id 是否为「有角色」的内置角色（不含默认） */
	public static boolean isCharacter(String id) {
		int i = index(id);
		return i > 0;
	}

	/** 角色对应的 AI 流派索引（未知/默认 = 一般流 0） */
	public static int flow(String id) {
		int i = index(id);
		return i < 0 ? 0 : FLOWS[i];
	}

	/** 角色语音 id（默认/未知 = 空串，即不播报语音） */
	public static String voice(String id) {
		return isCharacter(id) ? id : "";
	}

	/** 服务端座位显示名（默认 = 空串，由随机名填充） */
	public static String displayName(String id) {
		int i = index(id);
		return i < 0 ? "" : NAMES[i];
	}

	/** 本地皮肤贴图位置（默认/未知 = null，用玩家档案皮肤） */
	public static ResourceLocation skin(String id) {
		return isCharacter(id)
				? ResourceLocation.fromNamespaceAndPath(RichiiAriseMod.MODID, "textures/entity/ai/" + id + ".png")
				: null;
	}
}
