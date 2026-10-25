package mcr.riichi_arise;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import mcr.riichi_arise.block.FengPanBlock;
import mcr.riichi_arise.game.MahjongLobby;
import mcr.riichi_arise.game.MahjongTileNotation;
import mcr.riichi_arise.game.RichiTableManager;
import mcr.riichi_arise.game.RichiTableState;

import net.minecraft.commands.SharedSuggestionProvider;

/**
 * 麻雀指令：
 * /richi mahjong refresh —— 按服务端牌局状态重建所有桌面渲染（实时渲染的兜底手段）；
 * /richi mahjong clean   —— 清理最近的风盘（对局中不可清，清除状态与桌面渲染）；
 * /richi npc join <id>   —— 外部 NPC 加入 16 格内最近牌桌等候队列（右键[接受]触发）；
 * /richi npc decline     —— 右键[拒绝]。
 */
@EventBusSubscriber
public class MahjongCommands {
	@SubscribeEvent
	public static void onRegisterCommands(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("richi")
				.then(Commands.literal("mahjong")
						.then(Commands.literal("refresh")
								.executes(ctx -> {
									MahjongTicker.clear();
									ServerLevel level = ctx.getSource().getLevel();
									var origins = RichiTableManager.originsIn(level);
									for (BlockPos origin : origins)
										FengPanBlock.rerenderTable(level, origin);
									final int n = origins.size();
									ctx.getSource().sendSuccess(
											() -> Component.translatable("message.riichi_arise.refresh_done", n), true);
									return n;
									}))
						.then(Commands.literal("clean")
								.executes(ctx -> {
									// 清理最近的风盘：删对局/对局中不可清 + 清实体 + 通知客户端清 AI 形象
									ServerLevel level = ctx.getSource().getLevel();
									var player = ctx.getSource().getPlayerOrException();
									BlockPos nearest = RichiTableManager.nearest(level, player.position(), 16);
									if (nearest == null) {
										ctx.getSource().sendFailure(Component.translatable("message.riichi_arise.quit_none"));
										return 0;
									}
									var st = RichiTableManager.get(nearest);
									if (st != null && st.phase == mcr.riichi_arise.game.RichiTableState.PHASE_PLAYING) {
										ctx.getSource().sendFailure(
												Component.translatable("message.riichi_arise.fengpan_locked"));
										return 0;
									}
									MahjongTicker.clearAll(nearest);
									RichiTableManager.remove(nearest);
									FengPanBlock.clearDisplays(level, nearest);
									FengPanBlock.setGameActive(level, nearest, false);
									mcr.riichi_arise.game.RichiTableSync.broadcastGone(level, nearest); // 清客户端 AI 假玩家/盔甲架
									ctx.getSource().sendSuccess(
											() -> Component.translatable("message.riichi_arise.clean_done",
													nearest.toShortString()), true);
									return 1;
								}))
							.then(Commands.literal("replace")
									// 替换对局中指定玩家的手牌（4 级权限）：hand=牌谱记法（如 123m123p123456s11z），
									// seat 可选 e/s/w/n（东南西北），留空为自己
									.requires(src -> src.hasPermission(4))
									.then(Commands.argument("hand", StringArgumentType.string())
											.executes(ctx -> replaceHand(ctx, -1))
											.then(Commands.argument("seat", StringArgumentType.word())
													.suggests((c, b) -> SharedSuggestionProvider.suggest(
															new String[] { "e", "s", "w", "n" }, b))
													.executes(ctx -> replaceHand(ctx, seatFromArg(ctx))))))
							.then(Commands.literal("film")
									// 剧本牌局（临时，拍视频用）：start = 布置并直接开局；stop = 关闭剧本
									.requires(src -> src.hasPermission(2))
									.then(Commands.literal("start").executes(MahjongCommands::filmStart))
									.then(Commands.literal("stop").executes(ctx -> {
										ServerLevel level = ctx.getSource().getLevel();
										ServerPlayer player = ctx.getSource().getPlayerOrException();
										BlockPos nearest = RichiTableManager.nearest(level, player.position(), 16);
										RichiTableState st = nearest == null ? null : RichiTableManager.get(nearest);
										if (st == null) {
											ctx.getSource().sendFailure(Component.literal("§c16 格内没有牌桌"));
											return 0;
										}
										st.film = null;
										ctx.getSource().sendSuccess(
												() -> Component.literal("§6<麻雀>§r 剧本已关闭（下一局按普通对局进行）"), false);
										return 1;
									})))
							.then(Commands.literal("rank")
									// 段位排行榜（天凤 pt）：无参 = 前 10 位；带玩家名 = 查询该玩家 pt 与总排名（离线可查）
									.executes(ctx -> {
										ctx.getSource().sendSuccess(
												() -> Component.translatable("message.riichi_arise.rank_header"), false);
										var top = mcr.riichi_arise.game.MahjongRankStore.top(ctx.getSource().getLevel(), 10);
										if (top.isEmpty()) {
											ctx.getSource().sendSuccess(
													() -> Component.translatable("message.riichi_arise.rank_none"), false);
											return 0;
										}
										int rank = 0;
										for (var e : top) {
											final int r = ++rank;
											ctx.getSource().sendSuccess(() -> Component.translatable(
													"message.riichi_arise.rank_line", r, e.name(),
													String.format("%+.1f", e.pt()), e.games()), false);
										}
										return top.size();
									})
									.then(Commands.argument("player", StringArgumentType.word())
											.executes(ctx -> {
												String name = StringArgumentType.getString(ctx, "player");
												var e = mcr.riichi_arise.game.MahjongRankStore.find(
														ctx.getSource().getLevel(), name);
												if (e == null) {
													ctx.getSource().sendFailure(
															Component.translatable("message.riichi_arise.rank_notfound", name));
													return 0;
												}
												int rank = mcr.riichi_arise.game.MahjongRankStore.rankOf(
														ctx.getSource().getLevel(), e);
												ctx.getSource().sendSuccess(() -> Component.translatable(
														"message.riichi_arise.rank_query", e.name(),
														String.format("%+.1f", e.pt()), rank, e.games()), false);
												return 1;
											})))
						.then(Commands.literal("npc")
							.then(Commands.literal("join")
									.then(Commands.argument("npcId", IntegerArgumentType.integer(1))
											.executes(ctx -> {
												ServerPlayer player = ctx.getSource().getPlayerOrException();
												int id = IntegerArgumentType.getInteger(ctx, "npcId");
												net.minecraft.world.entity.Entity target = player.level().getEntity(id);
												if (target == null
														|| mcr.riichi_arise.game.MahjongParticipantRegistry.of(target) == null) {
													ctx.getSource().sendFailure(Component.literal("§c找不到可加入牌局的参牌者"));
													return 0;
												}
												ServerLevel level = player.serverLevel();
												BlockPos nearest = RichiTableManager.nearest(level, target.position(), 16);
												if (nearest == null
														|| !MahjongLobby.npcJoinTable(level, target, nearest)) {
													ctx.getSource().sendFailure(
															Component.literal("§c16 格内没有可加入的牌桌等候队列"));
													return 0;
												}
												ctx.getSource().sendSuccess(
														() -> Component.literal("§6<麻雀>§r 参牌者已加入等候队列"), false);
												return 1;
											})))
							.then(Commands.literal("decline")
									.executes(ctx -> {
										ctx.getSource().sendSuccess(
												() -> Component.literal("§6<Murmol>§r 下次再约吧～"), false);
										return 1;
									}))))
							.then(Commands.literal("net")
									.requires(src -> src.hasPermission(2))
									.then(Commands.literal("start")
											.executes(ctx -> netStart(ctx, mcr.riichi_arise.net.RichiNetConfig.PORT))
											.then(Commands.argument("port", IntegerArgumentType.integer(1, 65535))
													.executes(ctx -> netStart(ctx, IntegerArgumentType.getInteger(ctx, "port"))))
											)
									.then(Commands.literal("stop")
											.executes(ctx -> {
													mcr.riichi_arise.net.RichiNetManager.stop();
													ctx.getSource().sendSuccess(() -> Component.literal("§6<麻雀>§r 内网端口已停止"), true);
													return 1;
											}))
									.then(Commands.literal("status")
											.executes(MahjongCommands::netStatus))
									.then(Commands.literal("port")
											.then(Commands.argument("port", IntegerArgumentType.integer(1, 65535))
													.executes(ctx -> {
															mcr.riichi_arise.net.RichiNetConfig.PORT = IntegerArgumentType.getInteger(ctx, "port");
															ctx.getSource().sendSuccess(() -> Component.literal("§6<麻雀>§r 默认端口设为 " + mcr.riichi_arise.net.RichiNetConfig.PORT + "（下次 start 生效）"), true);
															return 1;
													}))
											)
									.then(Commands.literal("bind")
											.then(Commands.argument("addr", StringArgumentType.word())
													.executes(ctx -> {
															mcr.riichi_arise.net.RichiNetConfig.BIND = StringArgumentType.getString(ctx, "addr");
															ctx.getSource().sendSuccess(() -> Component.literal("§6<麻雀>§r 绑定地址设为 " + mcr.riichi_arise.net.RichiNetConfig.BIND), true);
															return 1;
													}))
											)
									.then(Commands.literal("token")
											.then(Commands.argument("token", StringArgumentType.greedyString())
													.executes(ctx -> {
															String t = StringArgumentType.getString(ctx, "token");
															mcr.riichi_arise.net.RichiNetConfig.TOKEN = t.equalsIgnoreCase("clear") ? "" : t;
															ctx.getSource().sendSuccess(() -> Component.literal("§6<麻雀>§r token " + (mcr.riichi_arise.net.RichiNetConfig.TOKEN.isEmpty() ? "已清除" : "已设置")), true);
															return 1;
													})))));
	}

	/** /richi mahjong film start：布置拍视频用剧本牌局（临时）并直接开局 */
	private static int filmStart(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerLevel level = ctx.getSource().getLevel();
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		BlockPos nearest = RichiTableManager.nearest(level, player.position(), 16);
		if (nearest == null) {
			ctx.getSource().sendFailure(Component.literal("§c16 格内没有牌桌"));
			return 0;
		}
		RichiTableState st = RichiTableManager.getOrCreate(level, nearest);
		if (st.phase == RichiTableState.PHASE_PLAYING) {
			ctx.getSource().sendFailure(Component.literal("§c该牌桌正在对局中"));
			return 0;
		}
		st.film = new mcr.riichi_arise.game.riichi.FilmScenario();
		st.gameType = 0; // 一局制：打完这一局即终局
		st.aiSpeedIdx = 0;
		st.thinkIdx = 0;
		java.util.Arrays.fill(st.reservedAI, false);
		for (int seat = 0; seat < 3; seat++) {
			st.reservedAI[seat] = true;
			st.aiAvatarModes[seat] = mcr.riichi_arise.game.riichi.FilmScenario.TARGET_CHARS[seat];
		}
		if (!st.queue.contains(player.getStringUUID()))
			st.queue.add(player.getStringUUID());
		MahjongLobby.startGame(level, nearest, st);
		ctx.getSource().sendSuccess(() -> Component.literal(
				"§6<麻雀>§r 剧本牌局已开局：前 10 巡三家手切，第 11 巡鹫巢岩/八木唯立直（赤木默听）后由你摸到发"), false);
		return 1;
	}

	/** replace 指令座位参数解析：e/s/w/n → 0..3，其余抛异常 */
	private static int seatFromArg(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String s = StringArgumentType.getString(ctx, "seat").toLowerCase();
		int seat = switch (s) {
			case "e" -> 0;
			case "s" -> 1;
			case "w" -> 2;
			case "n" -> 3;
			default -> -1;
		};
		if (seat < 0)
			throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
					Component.literal("§c座位须为 e/s/w/n")).create();
		return seat;
	}

	/** replace 指令执行：seatArg < 0 = 自己座位 */
	private static int replaceHand(CommandContext<CommandSourceStack> ctx, int seatArg)
			throws CommandSyntaxException {
		ServerLevel level = ctx.getSource().getLevel();
		var player = ctx.getSource().getPlayerOrException();
		BlockPos nearest = RichiTableManager.nearest(level, player.position(), 16);
		if (nearest == null) {
			ctx.getSource().sendFailure(Component.literal("§c16 格内没有牌桌"));
			return 0;
		}
		RichiTableState st = RichiTableManager.get(nearest);
		var game = RichiTableManager.getGame(nearest);
		if (st == null || game == null || st.phase != RichiTableState.PHASE_PLAYING) {
			ctx.getSource().sendFailure(Component.literal("§c该牌桌当前没有进行中的对局"));
			return 0;
		}
		int seat = seatArg;
		if (seat < 0) {
			seat = -1;
			for (int i = 0; i < 4; i++)
				if (player.getStringUUID().equals(st.players[i]))
					seat = i;
			if (seat < 0) {
				ctx.getSource().sendFailure(Component.literal("§c你不在这场对局中，请指定座位 e/s/w/n"));
				return 0;
			}
		}
		java.util.List<Integer> codes;
		try {
			codes = MahjongTileNotation.parse(StringArgumentType.getString(ctx, "hand"));
		} catch (IllegalArgumentException e) {
			ctx.getSource().sendFailure(Component.literal("§c牌谱记法无效: " + e.getMessage()));
			return 0;
		}
		if (game.replaceHand(seat, codes)) {
			int replaced = seat;
			ctx.getSource().sendSuccess(
					() -> Component.literal("§6<麻雀>§r 已替换 " + seatName(replaced) + " 家手牌"), true);
			return 1;
		}
		ctx.getSource().sendFailure(Component.literal("§c手牌张数须为 1..14"));
		return 0;
	}

	/** 座位 0..3 → 座位名（東南西北，与 RichiTableState 家序一致） */
	private static String seatName(int seat) {
		return new String[] { "東", "南", "西", "北" }[seat];
	}

	/** /richi net start：开启内网接入端口（默认绑定 127.0.0.1，端口可覆盖配置） */
	private static int netStart(CommandContext<CommandSourceStack> ctx, int port) {
		CommandSourceStack src = ctx.getSource();
		if (mcr.riichi_arise.net.RichiNetManager.start(src.getServer(), port, mcr.riichi_arise.net.RichiNetConfig.BIND)) {
			src.sendSuccess(() -> Component.literal("§6<麻雀>§r 内网端口已开启: "
					+ mcr.riichi_arise.net.RichiNetConfig.BIND + ":" + port
					+ (mcr.riichi_arise.net.RichiNetConfig.TOKEN.isEmpty() ? "（无 token）" : "（需 token）")), true);
			return 1;
		}
		src.sendFailure(Component.literal("§c端口启动失败（可能已开启或端口被占用）"));
		return 0;
	}

	/** /richi net status：显示运行状态 */
	private static int netStatus(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		if (!mcr.riichi_arise.net.RichiNetManager.isRunning()) {
			src.sendSuccess(() -> Component.literal("§6<麻雀>§r 内网端口未开启（默认 "
					+ mcr.riichi_arise.net.RichiNetConfig.BIND + ":" + mcr.riichi_arise.net.RichiNetConfig.PORT + "）"), false);
			return 0;
		}
		src.sendSuccess(() -> Component.literal("§6<麻雀>§r 内网端口运行中: "
				+ mcr.riichi_arise.net.RichiNetManager.bind() + ":" + mcr.riichi_arise.net.RichiNetManager.port()
				+ "，连接 " + mcr.riichi_arise.net.RichiNetManager.connectionCount() + " 条"
				+ (mcr.riichi_arise.net.RichiNetConfig.TOKEN.isEmpty() ? "" : "（token 已启用）")), false);
		return 1;
	}
}

