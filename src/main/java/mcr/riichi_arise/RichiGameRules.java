package mcr.riichi_arise;

import net.minecraft.world.level.GameRules;

/**
 * 麻雀模块自定义游戏规则。
 * <ul>
 * <li>{@code useAkochan}：是否使用 akochan 外部 AI 进行打牌判断（默认开）。
 * 关闭后 akochan 流派座位整体回落本地 AI 逻辑，已配置的引擎进程不再参与决策。</li>
 * <li>{@code autoOpenNetPort}：网络接口是否自动开启端口（默认关）。启用后服务端每次启动
 * 会自动开启内网接入端口（绑定 127.0.0.1:45678）；关闭时只能通过命令 {@code /richi net start} 手动开启。</li>
 * </ul>
 */
public final class RichiGameRules {
	private RichiGameRules() {
	}

	public static final GameRules.Key<GameRules.BooleanValue> USE_AKOCHAN = GameRules.register(
			"useAkochan", GameRules.Category.PLAYER, GameRules.BooleanValue.create(true));

	public static final GameRules.Key<GameRules.BooleanValue> AUTO_OPEN_NET_PORT = GameRules.register(
			"autoOpenNetPort", GameRules.Category.MISC, GameRules.BooleanValue.create(false));
}
