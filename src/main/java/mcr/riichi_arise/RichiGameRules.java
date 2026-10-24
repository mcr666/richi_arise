package mcr.riichi_arise;

import net.minecraft.world.level.GameRules;

/**
 * 麻雀模块自定义游戏规则。
 * <ul>
 * <li>{@code useAkochan}：是否使用 akochan 外部 AI 进行打牌判断（默认开）。
 * 关闭后 akochan 流派座位整体回落本地 AI 逻辑，已配置的引擎进程不再参与决策。</li>
 * </ul>
 */
public final class RichiGameRules {
	private RichiGameRules() {
	}

	public static final GameRules.Key<GameRules.BooleanValue> USE_AKOCHAN = GameRules.register(
			"useAkochan", GameRules.Category.PLAYER, GameRules.BooleanValue.create(true));
}
