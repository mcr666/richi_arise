package mcr.riichi_arise;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 麻雀模块客户端配置（自 Murmol 移植，仅保留麻雀相关项）。在 mod 构造函数中注册为 CLIENT 配置。
 */
public class RichiConfig {

	/** 麻雀观战者视角：把四家手牌全部平摊面朝上显示（默认关闭，观战时手牌立放） */
	public static final ModConfigSpec.BooleanValue SPECTATOR_OPEN_HANDS;
	/** 麻雀对局 AI 形象姿态：true=站立（默认站立动画），false=骑乘坐姿（现有潜行动作，默认） */
	public static final ModConfigSpec.BooleanValue MAHJONG_AVATAR_STANDING;
	/** 麻雀观战是否显示牌局内容：true=桌心 8 格内观战者也会被打开和牌/结算界面并收到牌局聊天（默认开），false=观战者仅旁观 */
	public static final ModConfigSpec.BooleanValue SPECTATOR_SHOW_TABLE;
	/** 角色语音播放音量（0.0~1.0，默认 0.5）：客户端本地播放，实时作用于 VoicePlayer 输出，不经过原版主音量 */
	public static final ModConfigSpec.DoubleValue VOICE_VOLUME;
	/** 切牌动画（默认开）：副露/切牌/拔北等牌的移动过渡动画；关闭时新牌位直接出现在目标位置 */
	public static final ModConfigSpec.BooleanValue TILE_MOVE_ANIMATIONS;
	/** 左右键功能对调（默认关）：关 = 左键摸切/跳过、右键选中/打牌；开 = 左右互换 */
	public static final ModConfigSpec.BooleanValue SWAP_HAND_BUTTONS;
	/** 紫水晶之间备选牌区（兼选牌框）渲染位置：左上/右上/左下/右下，默认左下 */
	public static final ModConfigSpec.EnumValue<CandidatePosition> CANDIDATE_POSITION;
	/** 透明牌平放（默认关）：开启后雀士视角中其他家的透明牌以平放姿态渲染，便于查看；仅本地渲染 */
	public static final ModConfigSpec.BooleanValue FLAT_GLASS_HAND_TILES;

	/** 备选牌区渲染位置（四角预设） */
	public enum CandidatePosition {
		TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
	}

	public static final ModConfigSpec SPEC;

	static {
		ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
		// NeoForge 内置配置屏的标签默认是 "riichi_arise.configuration.<path>"（未翻译 → 直接显示原始键），
		// 这里为分类与每一项显式指定语言键，与游戏内自建设置面板共用同一套文本
		builder.translation("gui.riichi_arise.settings.cat_client");
		builder.push("client");
		SPECTATOR_OPEN_HANDS = builder
				.comment("Mahjong spectator view: display all four players' hands face-up (flat). Default: false")
				.translation("gui.riichi_arise.settings.client_spectator_open")
				.define("spectatorOpenHands", false);
		MAHJONG_AVATAR_STANDING = builder
				.comment("Mahjong AI avatar pose: true = standing (default standing animation), false = riding crouch pose. Default: false")
				.translation("gui.riichi_arise.settings.client_avatar_stand")
				.define("mahjongAvatarStanding", false);
		SPECTATOR_SHOW_TABLE = builder
				.comment("Mahjong spectator shows table content: when true, spectators within 8 blocks of the table are also shown the win/settlement screens and table chat. Default: true")
				.translation("gui.riichi_arise.settings.client_spectator_show")
				.define("spectatorShowTable", true);
		VOICE_VOLUME = builder
				.comment("Mahjong character voice playback volume, 0.0-1.0 (default 0.5). Applies in real time to the character voice player output.")
				.translation("gui.riichi_arise.voice.volume")
				.defineInRange("voiceVolume", 0.5, 0.0, 1.0);
		TILE_MOVE_ANIMATIONS = builder
				.comment("Mahjong tile move animations: when true, called tiles / discards / kita tiles fly to their target positions. Default: true")
				.translation("gui.riichi_arise.settings.client_action_anim")
				.define("tileMoveAnimations", true);
		SWAP_HAND_BUTTONS = builder
				.comment("Swap left/right hand controls: false = left click tsumogiri/skip, right click select/discard (default); true = swapped")
				.translation("gui.riichi_arise.settings.client_swap_buttons")
				.define("swapHandButtons", false);
		CANDIDATE_POSITION = builder
				.comment("Amethyst room candidate area (also the pick box) screen position: TOP_LEFT / TOP_RIGHT / BOTTOM_LEFT (default) / BOTTOM_RIGHT")
				.translation("gui.riichi_arise.settings.client_candidate_pos")
				.defineEnum("candidatePosition", CandidatePosition.BOTTOM_LEFT);
		FLAT_GLASS_HAND_TILES = builder
				.comment("Lay other players' transparent (glass) hand tiles flat for seated players. Client-side rendering only; default: false")
				.translation("gui.riichi_arise.settings.client_flat_glass")
				.define("flatGlassHandTiles", false);
		builder.pop();
		SPEC = builder.build();
	}
}
