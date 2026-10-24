package mcr.richi;

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

	public static final ModConfigSpec SPEC;

	static {
		ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
		builder.push("client");
		SPECTATOR_OPEN_HANDS = builder
				.comment("Mahjong spectator view: display all four players' hands face-up (flat). Default: false")
				.define("spectatorOpenHands", false);
		MAHJONG_AVATAR_STANDING = builder
				.comment("Mahjong AI avatar pose: true = standing (default standing animation), false = riding crouch pose. Default: false")
				.define("mahjongAvatarStanding", false);
		SPECTATOR_SHOW_TABLE = builder
				.comment("Mahjong spectator shows table content: when true, spectators within 8 blocks of the table are also shown the win/settlement screens and table chat. Default: true")
				.define("spectatorShowTable", true);
		builder.pop();
		SPEC = builder.build();
	}
}
