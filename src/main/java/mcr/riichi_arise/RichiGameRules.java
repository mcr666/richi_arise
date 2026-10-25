package mcr.riichi_arise;

import net.minecraft.world.level.GameRules;

/**
 * 麻雀模块自定义游戏规则。
 * <ul>
 * <li>{@code autoOpenNetPort}：网络接口是否自动开启端口（默认关）。启用后服务端每次启动
 * 会自动开启内网接入端口（绑定 127.0.0.1:45678）；关闭时只能通过命令 {@code /richi net start} 手动开启。</li>
 * </ul>
 */
public final class RichiGameRules {
	private RichiGameRules() {
	}

	public static final GameRules.Key<GameRules.BooleanValue> AUTO_OPEN_NET_PORT = GameRules.register(
			"autoOpenNetPort", GameRules.Category.MISC, GameRules.BooleanValue.create(false));
}
