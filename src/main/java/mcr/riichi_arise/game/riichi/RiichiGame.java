package mcr.riichi_arise.game.riichi;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import mcr.riichi_arise.block.FengPanBlock;
import mcr.riichi_arise.game.MahjongGameLog;
import mcr.riichi_arise.game.MahjongTileNotation;
import mcr.riichi_arise.game.RichiTableState;
import mcr.riichi_arise.game.RichiTableSync;
import mcr.riichi_arise.network.MahjongSettlementPayload;

/**
 * 立直麻雀完整对局状态机（服务端行为拥有者）。
 * 持有 4 家 RiichiPlayer + RiichiWall，行为执行后写回 RichiTableState 字符串并按需
 * 实体级渲染同步（摸打）或整桌重建（吃碰杠/结算）。东风战单局，庄家 = 座位 0。
 *
 * <p>回合流：beginTurn 摸牌 → 打牌 → 响应窗口 60t（荣和 &gt; 碰/明杠 &gt; 吃，先响应先生效）
 * → 无人响应下家摸牌。杠后岭上摸牌 + 揭新宝。立直需门清听牌且 ≥1000 点。</p>
 */
public class RiichiGame {
	/** 响应窗口保底时长（刻，= 满长考银行 20s；实际结束由各待副露家银行耗尽驱动） */
	private static final int CLAIM_WINDOW_TICKS = 400;
	/** 北风牌 code（1z..7z = 30..36，北 = 4z = 33；三麻拔北专用） */
	private static final int TILE_PEI = 33;

	private final ServerLevel level;
	private final BlockPos origin;
	private final RichiTableState st;
	private final RiichiPlayer[] ps = new RiichiPlayer[4];
	private RiichiWall wall = new RiichiWall();
	/** 剧本牌局（临时，拍视频用）：null = 普通对局 */
	private final FilmScenario film;
	/** 剧本已摸牌次数（开局发牌不计） */
	private int filmDraws;

	private int turn = -1;
	/** 当前局（0 起，庄家 = handIndex % playerCount；场风 = handIndex / playerCount） */
	private int handIndex;
	/** 总局数（对局类型：一局 1 / 东风战 playerCount 局 / 半庄战 2×playerCount 局） */
	private final int totalHands;
	/** 和牌/终局结算数据缓存（finishRound 前构造；多家荣和逐个推送：确认或超时后再弹下一家） */
	private final List<MahjongSettlementPayload.Settlement> pendingSettlements = new ArrayList<>();
	/** 待展示的结算队列（含和牌座位；逐个展示，前一家确认/超时后才推下一家，语音与界面同步不互相打断） */
	private record SettlementShow(MahjongSettlementPayload.Settlement data, int seat) {
	}

	private final java.util.ArrayDeque<SettlementShow> settlementQueue = new java.util.ArrayDeque<>();
	/** 结算确认：全员（人类）确认或 10s 倒计时结束才清桌续局/终局 */
	private final boolean[] settleConfirmed = new boolean[4];
	/** 是否处于结算等待窗口（防重复推进） */
	private boolean settleWaiting;
	/** 结算阶段：0=无 1=和牌结算界面（播报+确认） 2=点数变化界面（5s 自动确认） */
	private int settlePhase;
	/** 本局开始时点数快照（点数变化界面展示全场收支） */
	private int[] pointsBeforeRound = new int[4];
	/** 待结算界面全部关闭后播报的聊天行（和牌/流局满贯播报延后到点数变化界面结束） */
	private String pendingChat = "";
	/** 报番役种缓存（seat → 英文役名列表）：doMultiRon/doTsumo 填入，finishRound 发送报番包后清空 */
	private final java.util.Map<Integer, List<String>> pendingFan = new java.util.LinkedHashMap<>();
	/** 本局结算后是否轮庄（庄家输牌 / 荒牌流局庄家未听牌 = true；连庄/庄家听牌流局 = false，本场累加） */
	private boolean roundAdvance;
	/** 九种九牌宣言窗口（本回合首次摸牌有效；确认后途中流局） */
	private boolean kyuushuAvailable;
	/** 本局已完成的打牌巡数（天地和判定；每局重置） */
	private int discardsThisHand;
	/** 本局是否已有人鸣牌（双立直判定） */
	private boolean anyCall;
	/** 摸进的是牌山最后一张（海底捞月判定） */
	private boolean lastTileHand;
	/** 打出的是牌山最后一张（河底捞鱼判定） */
	private boolean houteiFlag;
	/** 岭上摸牌后（岭上开花判定） */
	private boolean rinshanFlag;
	/** 抢杠窗口激活（加杠的牌可被荣和） */
	private boolean chankanWindow;
	/** 抢杠荣和标记（计分用） */
	private boolean chankanFlag;
	/** 抢杠窗口对应的加杠家座位 */
	private int chankanKongSeat = -1;
	/** 拔北窗口激活（三麻抢北：该北风牌可被他家荣和，不加抢杠番） */
	private boolean kitaWindow;
	/** 拔北窗口对应的拔北家座位 */
	private int kitaSeat = -1;
	/** 本打牌阶段是否源于碰（碰后当巡禁止拔北；杠/拔北后的岭上巡允许） */
	private boolean discardFromPon;
	/** 本局是否已有人拔北（拔北后天地和作废） */
	private boolean tenhouVoid;
	/** 响应窗口中已宣言荣和的座位（多家荣和，窗口收口统一结算） */
	private final List<Integer> pendingRons = new ArrayList<>();
	/** 当前回合家已摸牌（或吃碰杠后）等待打牌 */
	private boolean waitingDiscard;
	/** 本回合是否刚摸牌（决定能否自摸） */
	private boolean justDrew;
	/** 本回合是否宣言立直（打牌时生效） */
	private boolean pendingRiichi;
	/** 一发标记（立直宣言巡内无人鸣牌前有效） */
	private final boolean[] ippatsu = new boolean[4];

	// ---- 响应窗口（别人打出的牌） ----
	private long claimWindowEnd;
	/** 响应窗口短考截止时刻：短考（思考档位每巡时限）走完才开始消耗长考银行（与打牌回合同款长短考） */
	private long claimShortEnd;
	/** boss 栏横幅显示截止游戏时间（0 = 无超时）；设置横幅时刷新为 now+60，到期自动清空 */
	private long bannerUntil;
	private int claimDiscarder = -1;
	private int claimTile = -1;
	/** 各家可用响应选项（null = 无） */
	@SuppressWarnings("unchecked")
	private final List<String>[] claimOptions = new List[4];
	/** 吃的组合缓存：选项 "chi:<idx>" → 手牌两张 code */
	private final List<int[]> chiCombos = new ArrayList<>();

	// ---- 延迟任务（多条并存：结算轮播 / AI 荣和延迟宣言 / 10s 续局） ----
	private record DelayedTask(long at, Runnable run) {
	}

	private final List<DelayedTask> tasks = new ArrayList<>();
	/** 10s 续局任务引用（全员结算确认后取消） */
	private Runnable settleTask;
	/** 对局已彻底结束（投票强制终局 / 破产 / 局数打完）：此后不再推进任何延迟任务与响应窗口 */
	private boolean ended;
	/** 待延迟宣言的 AI 荣和数（AI 和牌停 0.5s 再宣布，期间响应窗口不收口） */
	private int aiRonPending;
	/** 待延迟决策的 AI 副露数（速度档中/慢时决策延后，期间响应窗口不收口） */
	private int aiMeldPending;
	/** 当前鸣牌窗口的打牌者座位（-1 = 无窗口）；延迟决策任务用 */
	private int discarderSeat = -1;
	/** 玻璃之间：无红宝牌；每种牌随机 3 张透明牌（见 {@link mcr.riichi_arise.game.TileFlag}） */
	private final boolean glassMode;

	@SuppressWarnings("unchecked")
	public RiichiGame(ServerLevel level, BlockPos origin, RichiTableState st) {
		this.level = level;
		this.origin = origin;
		this.st = st;
		this.glassMode = st.roomType == RichiTableState.ROOM_GLASS;
		this.film = st.film; // 剧本牌局（临时）：null = 普通对局
		this.totalHands = switch (st.gameType) {
			case 1 -> st.playerCount; // 东风战（三麻 3 局 / 四麻 4 局）
			case 2 -> 2 * st.playerCount; // 半庄战（三麻 6 局 / 四麻 8 局）
			default -> 1; // 一局
		};
		for (int seat = 0; seat < 4; seat++) {
			boolean ai = st.aiSeat[seat];
			String uuid = st.players[seat] == null ? RiichiBot.aiUuid(seat) : st.players[seat];
			String pname;
			// 外部程序（net 接入）座位：名字取 externalNames（join 时登记），否则退回 uuid 前 8 位
			if (st.players[seat] != null && st.externalUuids.contains(st.players[seat]))
				pname = st.externalNames.getOrDefault(uuid, nameOf(uuid));
			else if (st.players[seat] != null && st.npcUuids.contains(st.players[seat]))
				pname = participantNameOf(uuid);
			else
				pname = ai ? aiSeatName(seat) : nameOf(uuid);
			ps[seat] = new RiichiPlayer(seat, uuid, pname, ai, st.points[seat]);
		}
	}

	/** AI 结算显示名：统一走 RiichiBot.seatDisplayName（与 Marker 名牌/管理界面形态名同源）。
	 *  直接用角色名，无 "AI:" 前缀；同角色被多个 AI 座位选中时才编号（如 福姬1 / 福姬2）；Murmol NPC 直接叫 "Murmol" */
	private String aiSeatName(int seat) {
		return RiichiBot.seatDisplayName(st, seat);
	}

	private String nameOf(String uuid) {
		try {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(java.util.UUID.fromString(uuid));
			if (p != null)
				return p.getGameProfile().getName();
		} catch (IllegalArgumentException ignored) {
		}
		return uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
	}

	/** 参牌者（女仆/NPC）结算名：经注册表取参牌者名（而非 AI 名） */
	private String participantNameOf(String uuid) {
		Entity e = level.getEntity(java.util.UUID.fromString(uuid));
		mcr.riichi_arise.game.MahjongParticipant p = e == null ? null : mcr.riichi_arise.game.MahjongParticipantRegistry.of(e);
		return p != null ? p.participantName() : (uuid.length() > 8 ? uuid.substring(0, 8) : uuid);
	}

	/** NPC 座位动作反馈：Murmol NPC 执行操作（打牌/鸣牌/和牌等）时挥手 */
	private void swingIfNpc(int seat) {
		if (!ps[seat].ai)
			return;
		String u = st.players[seat];
		if (u == null || !st.npcUuids.contains(u))
			return;
		if (level.getEntity(java.util.UUID.fromString(u)) instanceof net.minecraft.world.entity.LivingEntity living)
			living.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
	}

	// ==================================================================
	// 开局
	// ==================================================================

	/** 本局参战人数（3=三麻只使用座位 0/1/2；4=四麻）：所有座位轮转/结算循环以此为界 */
	private int pc() {
		return st.playerCount;
	}

	/** 当前局庄家座位（東1局=座位0，此后每局按 playerCount 轮转） */
	private int dealerSeat() {
		return handIndex % pc();
	}

	/** 开局/下一局：重建牌山、清各家手牌区、发 4×13 理牌（点数与立直棒跨局保留，长考银行回满）。
	 *  首局（deal）同时为 AI 座位生成随机形态形象。 */
	public void deal() {
		handIndex = 0;
		st.carriedSticks = 0; // 新对局清空供托（上一局的留存棒不带入）
		// 提前进入对局 + 先解析角色：使本局角色在 resetHand 的 writeBack 广播前就写入，
		// 客户端开局即渲染本局角色，避免先显示上一局角色、发牌后才切换
		st.phase = RichiTableState.PHASE_PLAYING;
		FengPanBlock.resolveAvatars(level, st); // 角色解析同时分配各 AI 流派
		resetHand();
		// 角色重解析后刷新 AI 结算名——否则名字停留在构造时的旧角色
		for (int s = 0; s < 4; s++)
			if (ps[s].ai && !(st.players[s] != null && st.npcUuids.contains(st.players[s])))
				ps[s].name = aiSeatName(s);
		String[] names = new String[4];
		for (int i = 0; i < 4; i++)
			names[i] = i < pc() ? ps[i].name : "-"; // 三麻第 4 席占位（G 行固定 4 席位）
		MahjongGameLog.gameStart(level, origin, totalHands, names, st.points);
		// 开局标题（每位参与者屏幕中央弹出）推给座位 0..pc()-1
		pushRoomTitle();
	}

	/** 对局开始标题：glassMode ? "玻璃之间"（浅蓝）: "铁之间"（灰） */
	private static final String[] ROOM_TITLE_KEYS = { "title.riichi_arise.room_tie",
			"title.riichi_arise.room_glass" };
	private static final int[] ROOM_TITLE_COLORS = { 0xFF9E9E9E, 0xFF9BD8FF };

	private void pushRoomTitle() {
		int room = glassMode ? RichiTableState.ROOM_GLASS : RichiTableState.ROOM_TIE;
		var payload = new mcr.riichi_arise.network.MahjongLobbyPayloads.RoomTitlePayload(ROOM_TITLE_KEYS[room],
				ROOM_TITLE_COLORS[room]);
		for (int s = 0; s < pc(); s++) {
			ServerPlayer p = st.playerOf(level, s);
			if (p != null && !ps[s].ai)
				net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, payload);
		}
	}

	/** 发一局的公共流程（deal 首局 / nextHand 续局） */
	/** 设置桌心横幅（boss 栏）并刷新显示截止：显示 60 tick 后自动清空 */
	private void setBanner(String text) {
		st.banner = text;
		bannerUntil = text.isEmpty() ? 0 : level.getGameTime() + 60;
	}

	private void resetHand() {
		// 剧本（临时）：用编排好的牌山；其余牌按对局人数构建（三麻 108 张 / 四麻 136 张）
		wall = film != null ? film.buildWall() : new RiichiWall(pc(), glassMode);
		if (film != null) {
			film.reset();
			filmDraws = 0;
		}
		pointsBeforeRound = st.points.clone(); // 本局起点点数（点数变化界面用）
		for (RiichiPlayer p : ps) {
			p.hand.clear();
			p.river.clear();
			p.melds.clear();
			p.riichi = false; // 立直不跨局（此前漏重置：下一局每巡被锁定自动摸切）
			p.doubleRiichi = false;
			p.riichiFuriten = false;
			p.tempFuriten = false;
			p.firstDrawDone = false;
			p.discardCount = 0;
			p.kitaCount = 0; // 拔北数每局清零
		}
		discardsThisHand = 0;
		anyCall = false;
		setBanner("");
		lastTileHand = false;
		houteiFlag = false;
		rinshanFlag = false;
		chankanFlag = false;
		tenhouVoid = false; // 本局尚未拔北：天地和仍可成立
		kitaWindow = false;
		kitaSeat = -1;
		discardFromPon = false;
		java.util.Arrays.fill(ippatsu, false);
		java.util.Arrays.fill(st.kita, 0);
		java.util.Arrays.fill(st.riichiRiverIdx, -1);
		java.util.Arrays.fill(st.riichiPending, false);
		java.util.Arrays.fill(st.handsExposed, RichiTableState.HAND_STAND);
		java.util.Arrays.fill(st.lastTedashi, 0);
		java.util.Arrays.fill(st.lastDiscardPos, -1);
		st.drawnSeat = -1;
		// 读秒库按思考时限档位重置（此前硬编码 20s，覆盖开局套用的档位）
		for (int i = 0; i < 4; i++)
			st.longBank[i] = RichiTableState.THINK_PRESETS[st.thinkIdx][1] * 20;
		// 发牌：仅参战座位各 13 张（三麻发 3×13，座位 3 留空不参与）
		for (int s = 0; s < pc(); s++) {
			RiichiPlayer p = ps[s];
			for (int i = 0; i < 13; i++) {
				int code = wall.draw();
				if (code < 0)
					break;
				p.hand.add(code);
			}
			p.sortHand();
		}
		st.round = handIndex % pc() + 1;
		st.roundWind = handIndex / pc();
		st.revealedIndicators = 1;
		// S 行：开局快照（回放用，末段 = 王牌区岭上 4 张）
		{
			String[] hs = new String[4];
			for (int i = 0; i < 4; i++)
				hs[i] = MahjongTileNotation.format(ps[i].hand);
			MahjongGameLog.handStart(level, origin, hs, MahjongTileNotation.format(wall.remainingCodes()),
					MahjongTileNotation.format(wall.doraStackTiles()),
					MahjongTileNotation.format(wall.rinshanStackTiles()));
		}
		writeBack();
		st.phase = RichiTableState.PHASE_PLAYING;
		int dealTicks = FengPanBlock.startDealAnimation(level, origin, st);
		// 局目/本场已由副标题栏显示，不再向动作栏发提示
		// 座位切换（轮庄）只调换牌局中的东南西北，不再把玩家传送归位——世界位置保持不动
		broadcastRoundStart();
		schedule(dealTicks, this::beginFirstTurn);
	}

	/** 每局开始向参与玩家聊天播报局目/本场数及各自座位（自风字黄色），如：[東1局-1本场] 您的座位：西 */
	private void broadcastRoundStart() {
		String[] winds = { "東", "南", "西", "北" };
		// 副标题栏提示：局目 + 本场（如 -東1局 1本场-）；只发给参战玩家
		net.minecraft.network.chat.Component sub = net.minecraft.network.chat.Component.literal(
				"-" + (st.roundWind == 0 ? "東" : "南") + st.round + "局"
						+ (st.honba > 0 ? " " + st.honba + "本场" : "") + "-");
		for (int seat = 0; seat < pc(); seat++) {
			net.minecraft.server.level.ServerPlayer p = st.playerOf(level, seat);
			if (p == null)
				continue;
			String selfWind = winds[Math.floorMod(seat - dealerSeat() + 4, 4)];
			p.displayClientMessage(Component.translatable("message.riichi_arise.round_start",
					(st.roundWind == 0 ? "東" : "南"), st.round, st.honba,
					Component.literal(selfWind).withStyle(net.minecraft.ChatFormatting.YELLOW)), false);
			// 每局开始副标题栏显示一次局目/本场（空格标题触发副标题显现）
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(sub));
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
					net.minecraft.network.chat.Component.literal(" ")));
		}
	}

	private void beginFirstTurn() {
		beginTurn(dealerSeat());
	}

	// ==================================================================
	// 回合推进
	// ==================================================================

	/** 摸牌回合开始：牌尾摸一张，短考重置，检查九种九牌/自摸/立直/杠选项 */
	private void beginTurn(int seat) {
		// 剧本（临时）：前 10 巡摸牌结束后（第 11 巡首次摸牌前），三家手牌瞬间换为目标牌
		if (film != null && !film.isInjected() && filmDraws >= FilmScenario.QUIET_DRAWS)
			film.inject(ps);
		// 剧本（临时）：玩家（座位 3）听牌后，牌山不再让任何人摸到他听的牌（也就不会有人打出）
		java.util.Set<Integer> waits = film == null ? null : filmPlayerWaits();
		int drawn = waits == null || waits.isEmpty() ? wall.draw()
				: wall.drawAvoiding(c -> waits.contains(normCode(c)), FilmScenario::isNeutral, FilmScenario.WIN_TILE);
		if (drawn < 0) {
			ryuukyoku();
			return;
		}
		filmDraws++;
		lastTileHand = wall.isEmpty(); // 海底判定：摸进的是最后一张
		rinshanFlag = false;
		ps[seat].hand.add(drawn);
		// 九种九牌（本局首次摸牌且幺九字牌 ≥9 种）：确认制——本回合可宣言，按数字键确认，不宣则继续
		if (!ps[seat].firstDrawDone) {
			ps[seat].firstDrawDone = true;
			kyuushuAvailable = countTerminalKinds(ps[seat].hand) >= 9;
		}
		startDiscardPhase(seat, true, drawn, false);
	}

	/** 幺九字牌种类数（九种九牌判定） */
	private static int countTerminalKinds(List<Integer> hand) {
		java.util.Set<Integer> kinds = new java.util.HashSet<>();
		for (int raw : hand) {
			int c = mcr.riichi_arise.game.TileFlag.base(raw);
			int d = c < 30 ? c % 10 : c - 29;
			if (c >= 30 || d == 1 || d == 9)
				kinds.add(c);
		}
		return kinds.size();
	}

	/** 红五归一到普通五（听牌张比对用） */
	private static int normCode(int code) {
		int c = mcr.riichi_arise.game.TileFlag.base(code);
		return c == 0 ? 5 : c == 10 ? 15 : c == 20 ? 25 : c;
	}

	/**
	 * 剧本（临时）：玩家（座位 {@link FilmScenario#PLAYER_SEAT}）当前听牌的和牌张；未听牌返回空集。
	 * 命中后牌山不再发这些牌给任何人（三家只会摸切，也就不会打出）。
	 */
	private java.util.Set<Integer> filmPlayerWaits() {
		java.util.Set<Integer> waits = new java.util.HashSet<>();
		RiichiPlayer p = ps[FilmScenario.PLAYER_SEAT];
		if (p.hand.size() % 3 != 1)
			return waits; // 13/10/7 张（刚摸牌或已副露的张数不符时不算）
		for (int code = 1; code < 37; code++) {
			if (code == 10 || code == 20)
				continue; // 红五按普通五同判
			List<Integer> test = new ArrayList<>(p.hand);
			test.add(code);
			if (Mahjong4jBridge.isWinnableShape(test, p.melds))
				waits.add(code);
		}
		return waits;
	}

	/** 打牌阶段开始（beginTurn 摸牌后 / 吃碰后不摸 / 杠后岭上摸牌；fromPon=碰后当巡，禁拔北） */
	private void startDiscardPhase(int seat, boolean drew, int drawnCode, boolean fromPon) {
		turn = seat;
		waitingDiscard = true;
		justDrew = drew;
		discardFromPon = fromPon; // 碰后当巡标记（canKita 判定；摸牌/杠/拔北巡恒 false）
		st.drawnSeat = drew ? seat : -1; // 刚摸的牌在手牌末尾（渲染与原手牌隔开一个牌位）
		pendingRiichi = false;
		st.turnSeat = seat;
		st.turnInLong = false;
		st.turnLimit = RichiTableState.THINK_PRESETS[st.thinkIdx][0] * 20; // 每巡时限按思考时限档位
		st.turnDeadline = level.getGameTime() + st.turnLimit;
		if (drew) {
			writeBack(); // 广播含刚摸的牌（客户端整桌重建）
			// 摸到的牌补交互代理（发牌时只有 13 张常驻代理，否则摸牌点不中）
			FengPanBlock.spawnDrawnInteractor(level, origin, seat, ps[seat].hand.size() - 1);
		}
		// 自家选项（自摸/立直/杠）推给本人
		sendSelfOptions(seat);
		// 立直后摸切（日麻规则：立直者打牌锁定）：无自摸/暗杠/拔北机会则自动打出刚摸的牌（AI 与人类一致）；
		// 暗杠需岭上有余，岭上耗尽时同样视为无机会；立直家摸到北时可选拔北（canKita），选跳过则摸切
		if (ps[seat].riichi && !(justDrew && canWinNow(seat, true))
				&& (findAnkan(seat) < 0 || wall.rinshanRemaining() <= 0) && !canKita(seat)) {
			int last = ps[seat].hand.size() - 1;
			schedule((8 + level.random.nextInt(6)) * aiMul(), () -> playerDiscard(seat, last));
			return;
		}
		if (ps[seat].ai) {
			int delay = (8 + level.random.nextInt(8)) * aiMul();
			// 副露（吃/碰）后先停 1s 再切牌（原先"切牌后再停 1s"的节奏移到出牌之前）
			if (!drew)
				delay += 20;
			schedule(delay, this::aiTakeTurn);
		}
	}

	/** AI 打牌速度倍率（快=1 中=2 慢=3）：所有 AI 动作延迟统一乘该系数 */
	private int aiMul() {
		return RichiTableState.AI_SPEED_MUL[Math.floorMod(st.aiSpeedIdx, RichiTableState.AI_SPEED_MUL.length)];
	}

	/** 自家回合可用动作：tsumo / kyuushu / riichi / ankan:code / kakan:code（和牌 > 流局 > 宣言 > 副露） */
	private void sendSelfOptions(int seat) {
		List<String> opts = new ArrayList<>();
		// 自摸（含天和/地和——第一巡且确实构成和牌形时无需常规役）
		if (justDrew && (canWinNow(seat, true) || firstTurnShapeWin(seat)))
			opts.add("tsumo:" + ps[seat].hand.get(ps[seat].hand.size() - 1)); // 带摸牌 code（HUD 画牌面）
		if (kyuushuAvailable)
			opts.add("kyuushu");
		if (canRiichi(seat))
			opts.add("riichi");
		int ankan = findAnkan(seat);
		// 杠需摸岭上补牌：岭上耗尽不再提供杠选项（三麻拔北/杠高频时可能耗尽）
		if (ankan >= 0 && wall.rinshanRemaining() > 0)
			opts.add("ankan:" + ankan);
		int kakan = findKakan(seat);
		if (kakan >= 0 && wall.rinshanRemaining() > 0)
			opts.add("kakan:" + kakan);
		// 拔北（三麻）：选项 token = "kita"（客户端 HUD 与 net 层按该串回传 action）
		if (canKita(seat))
			opts.add("kita");
		// 外部程序（net 接入）座位：无 ServerPlayer，回合选项改由 net 层推送（含手牌）
		if (st.externalUuids.contains(ps[seat].uuid)) {
			mcr.riichi_arise.net.RichiNetManager.pushYourTurn(this, seat, opts);
			return;
		}
		if (ps[seat].ai)
			return;
		ServerPlayer p = st.playerOf(level, seat);
		if (p != null) {
			mcr.riichi_arise.network.MahjongGamePayloads.sendOptions(p, origin, String.join(";", opts));
			if (!opts.isEmpty())
				playOptionSound(p);
		}
	}

	/** 有可选动作时对本人播放一声拾取经验球音效（仅该玩家听得见，不公开） */
	private void playOptionSound(ServerPlayer p) {
		p.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.6f, 1.0f);
	}

	/**
	 * 推给客户端的选项 token 追加荣和牌 code（HUD 画牌面用）：
	 * ron/pon/minkan → "ron:&lt;claimTile&gt;"。chi 的 token 在构建时已带完整三张。
	 * 内部 claimOptions 保持原 token（contains 匹配不受影响）。
	 */
	private List<String> withClaimTile(List<String> opts) {
		List<String> out = new ArrayList<>(opts.size());
		for (String t : opts) {
			if (t.equals("ron") || t.equals("pon") || t.equals("minkan"))
				out.add(t + ":" + claimTile);
			else
				out.add(t);
		}
		return out;
	}

	/** 清除所有玩家的选项 HUD */
	private void clearAllOptions() {
		for (int seat = 0; seat < pc(); seat++) {
			if (ps[seat].ai)
				continue;
			ServerPlayer p = st.playerOf(level, seat);
			if (p != null) {
				mcr.riichi_arise.network.MahjongGamePayloads.sendOptions(p, origin, "");
				FengPanBlock.clearCountdown(p); // 倒计时叠加层一并清掉
			}
		}
	}

	/**
	 * 打出（玩家与 AI 共用）：理牌 → 牌河末尾；实体级同步；立直宣言在此生效；
	 * 随后开响应窗口。
	 */
	public void playerDiscard(int seat, int index) {
		if (!waitingDiscard || turn != seat)
			return;
		RiichiPlayer p = ps[seat];
		if (index < 0 || index >= p.hand.size())
			return;
		swingIfNpc(seat); // NPC 打牌挥手
		// 副露后 1s 停顿已改到切出之前（见 startDiscardPhase 的 AI 回合调度）
		// 立直宣言待选：校验所选牌打出后仍听牌（防"宣言后打出破坏听牌的牌仍成立立直"漏洞）
		if (pendingRiichi) {
			int removed = p.hand.remove(index);
			boolean keepsTenpai = Mahjong4jBridge.isTenpai(p.hand, p.melds);
			p.hand.add(index, removed);
			if (!keepsTenpai) {
				cancelRiichi();
				ServerPlayer cp = st.playerOf(level, seat);
				if (cp != null)
					cp.displayClientMessage(Component.translatable("message.riichi_arise.riichi_invalid"), true);
				return;
			}
		}
		// 一发过期：立直后再次打牌（一巡已过）；宣言当巡不受影响（此时 p.riichi 尚未置位）
		if (p.riichi && ippatsu[seat])
			ippatsu[seat] = false;
		p.discardCount++;
		discardsThisHand++;
		kyuushuAvailable = false; // 打出后九种九牌机会消失
		houteiFlag = lastTileHand; // 河底判定：打出的是牌山最后一张
		lastTileHand = false;
		rinshanFlag = false;
		// 长考阶段打出：结算本回合实际消耗的长考银行
		if (st.turnInLong)
			st.longBank[seat] = (int) Math.max(0, st.turnDeadline - level.getGameTime());
		waitingDiscard = false;
		justDrew = false;
		// 回合结束：清掉该玩家的选项叠加层与倒计时（直接点手牌出牌、不点"跳过"时，自摸/立直等按钮与读秒不应残留到下一巡）
		ServerPlayer discarder = st.playerOf(level, seat);
		if (discarder != null) {
			mcr.riichi_arise.network.MahjongGamePayloads.sendOptions(discarder, origin, "");
			FengPanBlock.clearCountdown(discarder);
		}
		// 手切/摸切标记（隐藏手牌的牌背动画 key 错位用）：摸切 = 本巡刚摸牌且打出最右一张
		boolean tsumogiri = st.drawnSeat == seat && index == p.hand.size() - 1;
		st.lastTedashi[seat] = tsumogiri ? 0 : 1;
		// 被切出的那张在打牌前手牌中的位置（各座位独立字段）：客户端据此把手牌飞行动画从正确槽位起飞
		st.lastDiscardPos[seat] = index;
		st.drawnSeat = -1; // 摸牌已打出/摸切
		// 摸牌位的交互代理随打出移除（其余 13 张代理与理牌后的固定槽位对齐）
		FengPanBlock.removeHandInteractor(level, origin, seat, p.hand.size() - 1);
		st.clearSelection(); // 选中牌随广播降下（客户端处理升降）
		p.tempFuriten = false; // 同巡振听到自己的打牌为止
		int code = p.hand.remove(index);
		boolean riichiDeclared = pendingRiichi; // 牌谱事件旗（置位在下方立直块消费）
		p.sortHand();
		p.river.add(code);
		// 立直宣言牌被鸣后：下一张切牌改为横摆，补回立直标记
		if (st.riichiPending[seat]) {
			st.riichiPending[seat] = false;
			st.riichiRiverIdx[seat] = p.river.size() - 1;
		}
		if (pendingRiichi) {
			p.riichi = true;
			p.doubleRiichi = p.discardCount == 1 && !anyCall; // 第一巡立直且无人鸣牌 = 双立直
			st.riichiSticks[seat]++;
			st.riichiRiverIdx[seat] = p.river.size() - 1; // 宣言牌横摆渲染
			st.points[seat] -= 1000;
			p.points -= 1000;
			ippatsu[seat] = true;
			pendingRiichi = false;
			// boss 栏：最近一次行动（xxx立直）
			setBanner(p.name + "立直");
			// 宣言牌特效：旋转扩散的 astral_burst 粒子环
			FengPanBlock.spawnRiichiBurst(level, origin,
					FengPanBlock.riverTilePos(level, origin, seat, st.riichiRiverIdx[seat]));
			broadcast(Component.translatable("message.riichi_arise.riichi_chat", p.name).getString());
			voiceEvent(seat, mcr.riichi_arise.voice.VoiceEvents.RICH); // 天凤式角色语音：立直宣言
			// 立直音效：敲铁砧
			Vec3 c = FengPanBlock.tableCenter(level, origin);
			level.playSound(null, c.x, c.y, c.z, SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 0.6f, 1.3f);
		}
		st.clearSelection();
		writeBack();
		// T 行：切牌事件（旗 r = 本巡宣言立直）
		MahjongGameLog.turnEvent(level, origin, seat, "d",
				MahjongTileNotation.format(List.of(code)) + (riichiDeclared ? "|r" : ""));
		Vec3 riverPos = FengPanBlock.riverTilePos(level, origin, seat, st.riverCodes(seat).size() - 1);
		level.playSound(null, riverPos.x, riverPos.y, riverPos.z,
				SoundEvents.DECORATED_POT_HIT, SoundSource.PLAYERS, 0.9f, 1.25f);
		// 开响应窗口（无人可选 → 直接下家）；AI 刚立直宣言后打出的牌额外停 1s 再推进（观感节奏）
		openClaimWindow(seat, code, ps[seat].ai && riichiDeclared);
	}

	/** 超时自动处理：可自摸则自动胡牌（倒计时结束自动胡），否则摸切（打最右一张）。
	 *  超时若处于立直宣言待选：取消宣言（不替玩家自动成立立直），再常规摸切。 */
	public void autoDiscard() {
		if (!waitingDiscard || turn < 0)
			return;
		if (pendingRiichi)
			pendingRiichi = false;
		if (justDrew && (canWinNow(turn, true) || firstTurnShapeWin(turn))) {
			clearAllOptions();
			doTsumo(turn);
			return;
		}
		playerDiscard(turn, ps[turn].hand.size() - 1);
	}

	// ==================================================================
	// 响应窗口
	// ==================================================================

	/** 打出后开响应窗口：荣和 > 碰/明杠 > 吃（下家），先响应先生效；多家可同时荣和 */
	private void openClaimWindow(int discarder, int tile, boolean pauseAdvance) {
		claimDiscarder = discarder;
		claimTile = tile;
		discarderSeat = discarder; // 延迟副露决策任务引用
		claimWindowEnd = level.getGameTime() + CLAIM_WINDOW_TICKS;
		claimShortEnd = 0; // 选项收集完由 armClaimTiming 统一设定
		chiCombos.clear();
		pendingRons.clear();
		boolean anyHumanOption = false;
		// 剧本（临时）：换牌前三家还是随机手牌，禁止任何响应（防误荣和把戏搅黄）；
		// 换牌后仍不提供吃碰杠（只保留 AI 荣和，保证玩家打出发时三家能荣和）
		boolean filmQuiet = film != null && !film.isInjected();
		for (int seat = 0; seat < pc(); seat++) {
			claimOptions[seat] = null;
			if (seat == discarder)
				continue;
			List<String> opts = new ArrayList<>();
			// 地和：闲家荣和本局第一张打出的牌（须构成和牌形）
			if (!filmQuiet && (canRon(seat) || isChihoWin(seat))) {
				opts.add("ron");
				if (ps[seat].ai)
					scheduleAiRon(seat); // AI 停 0.5s 再宣布和牌
			}
			if (!ps[seat].riichi && film == null) {
				// 红五与普通五等价计数（如手里 1 红5 + 2 普通5 也可碰红5声明牌）
				int equiv = 0;
				for (int c : ps[seat].hand)
					if (sameTile(c, tile))
						equiv++;
				if (equiv >= 2)
					opts.add("pon");
				// 明杠需摸岭上牌：岭上耗尽（三麻拔北/杠高频时可能）不再提供
				if (equiv >= 3 && wall.rinshanRemaining() > 0)
					opts.add("minkan");
				// 吃：仅下家、数牌；三麻禁吃（雀魂规则，不生成 chi 选项）
				if (pc() == 4 && seat == (discarder + 1) % 4
						&& mcr.riichi_arise.game.TileFlag.base(tile) < 30) {
						int t = mcr.riichi_arise.game.TileFlag.base(tile);
						t = t == 0 ? 5 : t;
						if (t / 10 < 3) {
							for (int[] combo : chiCandidates(ps[seat].hand, t)) {
								chiCombos.add(combo);
								// token 携带完整三张（声明牌 + 手牌两张），HUD 画组合区分不同吃法
								opts.add("chi:" + (chiCombos.size() - 1) + ":" + tile + ","
										+ combo[0] + "," + combo[1]);
							}
						}
					}
			}
			if (!opts.isEmpty()) {
				claimOptions[seat] = opts;
				if (!ps[seat].ai) {
					anyHumanOption = true;
					// 外部程序（net 接入）：无 ServerPlayer，响应选项由 net 层推送
					if (st.externalUuids.contains(ps[seat].uuid)) {
						mcr.riichi_arise.net.RichiNetManager.pushClaimOptions(this, seat, withClaimTile(opts));
					} else {
						ServerPlayer p = st.playerOf(level, seat);
						if (p != null) {
							mcr.riichi_arise.network.MahjongGamePayloads.sendOptions(p, origin,
									String.join(";", withClaimTile(opts)));
							playOptionSound(p); // 待副露提示音：仅该玩家听得见（不公开谁可副露）
						}
						// 等待倒计时只推给该家（不公开）：短考秒数 + 长考银行
						sendClaimCountdown(seat,
								RichiTableState.THINK_PRESETS[st.thinkIdx][0],
								(st.longBank[seat] + 19) / 20);
					}
				}
			}
		}
		// AI 副露决策（按流派：仅 3 御无双不副露；2 门清流副露权重低，走 bestMeld 高门槛）：
		// 荣和优先级最高（已在 pendingRons/延迟宣言）；有人可荣和时 AI 不副露（不抢人类荣和机会），
		// 有人可碰/杠时 AI 不吃。决策延迟按速度档执行（快=立即，中/慢停顿后再吃碰）
		armClaimTiming();
		if (pendingRons.isEmpty() && aiRonPending == 0) {
			aiMeldPending++;
			schedule(10 * aiMul(), () -> {
				aiMeldPending--;
				if (claimWindowEnd <= 0 || discarderSeat < 0)
					return;
				int dsc = discarderSeat;
				boolean humanRon = false, humanMeld = false;
				for (int s = 0; s < pc(); s++) {
					if (ps[s].ai || claimOptions[s] == null)
						continue;
					if (claimOptions[s].contains("ron"))
						humanRon = true;
					if (claimOptions[s].contains("pon") || claimOptions[s].contains("minkan"))
						humanMeld = true;
				}
				if (humanRon)
					return; // 有人类可荣和：AI 不副露/不抢荣和（AI 荣和已在开窗时延迟宣言）
				for (int seat = 0; seat < pc(); seat++) {
					if (!ps[seat].ai || seat == dsc || claimOptions[seat] == null)
						continue;
					int flow = Math.floorMod(st.aiFlows[seat], RiichiBot.FLOW_COUNT);
					if (flow == 3)
						continue; // 御无双：不副露（门清流副露权重低，仍在 bestMeld 内按高门槛取舍）
					List<String> opts = claimOptions[seat];
					if (opts.contains("minkan")) {
						execAiMeld(seat, Fuuro.Type.MINKAN, null);
						return;
					}
					boolean canChi = !humanMeld && opts.stream().anyMatch(t -> t.startsWith("chi:"));
					if (opts.contains("pon") || canChi) {
						// CalculateMeld 鸣牌评估：与不鸣牌基线分比较（场况：巡目/手内宝牌/场风/自风）
						int turns = ps[0].river.size() + ps[1].river.size() + ps[2].river.size()
								+ ps[3].river.size();
						List<Integer> doraInds = wall.revealedDoras();
						int ownDora = 0;
						for (int raw : ps[seat].hand) {
							int c = mcr.riichi_arise.game.TileFlag.base(raw);
							if (c == 0 || c == 10 || c == 20)
								ownDora++; // 赤5
							for (int ind : doraInds)
								if (c == wall.doraTileOf(ind)) { // 三麻感知映射（万子 1↔9）
									ownDora++;
									break;
								}
						}
						int selfWind = 27 + Math.floorMod(seat - dealerSeat() + 4, 4);
						TileEfficiency.MeldOption m = RiichiBot.bestMeld(ps[seat], buildVisible34(seat),
								claimTile, canChi, turns, ownDora, 27 + st.roundWind, selfWind, flow);
						if (m != null && (!m.pon || opts.contains("pon"))) {
							execAiMeld(seat, m.pon ? Fuuro.Type.PON : Fuuro.Type.CHII,
									m.pon ? null : m.selfCodes);
							return;
						}
					}
				}
				// 无 AI 副露：若窗口本应由本方法收口且人类已全部表态，则收口
				boolean anyHuman = false;
				for (int s = 0; s < pc(); s++)
					if (!ps[s].ai && claimOptions[s] != null)
						anyHuman = true;
				if (!anyHuman && pendingRons.isEmpty() && aiRonPending == 0) {
					claimWindowEnd = 0;
					advanceAfterDiscard(pauseAdvance, discarder);
				}
			});
			return;
		}
		if (!anyHumanOption && pendingRons.isEmpty() && aiRonPending == 0 && aiMeldPending == 0) {
			claimWindowEnd = 0;
			advanceAfterDiscard(pauseAdvance, discarder);
		} else {
			resolveWindow(); // 有 AI 荣和宣言且人类均已表态 → 立即多家结算
		}
	}

	/** AI 荣和延迟宣言（按速度档制造"宣布和牌"节奏；期间响应窗口不收口） */
	private void scheduleAiRon(int seat) {
		aiRonPending++;
		schedule(10 * aiMul(), () -> {
			aiRonPending--;
			if (claimWindowEnd <= 0 || claimOptions[seat] == null || !claimOptions[seat].contains("ron"))
				return;
			pendingRons.add(seat);
			claimOptions[seat] = null;
			swingIfNpc(seat); // NPC 荣和宣言挥手
			resolveWindow();
		});
	}

	/** AI 吃决策已并入 RiichiBot.bestMeld（CalculateMeld 鸣牌评估，综合分取舍） */

	/** AI 执行副露（等价 tryClaim 的碰/杠/吃路径） */
	private void execAiMeld(int seat, Fuuro.Type type, int[] chiPair) {
		swingIfNpc(seat); // NPC 副露挥手
		claimWindowEnd = 0;
		clearAllOptions();
		doOpenMeld(seat, type, claimTile, chiPair);
	}

	/**
	 * 响应窗口收口判定：人类全部表态后——有多家荣和 → 统一结算；
	 * 抢杠窗口无人荣和 → 杠家继续岭上；否则正常推进下家。
	 */
	private void resolveWindow() {
		if (claimWindowEnd <= 0)
			return;
		if (aiRonPending > 0)
			return; // 有 AI 荣和待宣言（0.5s 延迟），窗口保持开放
		for (int s = 0; s < pc(); s++)
			if (!ps[s].ai && claimOptions[s] != null)
				return; // 还有未表态的人类
		claimWindowEnd = 0;
		if (!pendingRons.isEmpty()) {
			List<Integer> winners = new ArrayList<>(pendingRons);
			pendingRons.clear();
			boolean wasChankan = chankanWindow;
			chankanWindow = false;
			kitaWindow = false; // 抢北荣和：chankanFlag 保持 false（抢北不加抢杠番，见 openKitaWindow）
			chankanFlag = wasChankan; // 结算时保持抢杠标记
			doMultiRon(winners);
			chankanFlag = false;
		} else if (chankanWindow) {
			chankanWindow = false;
			chankanFlag = false;
			finishKong(chankanKongSeat);
		} else if (kitaWindow) {
			// 抢北无人荣和 → 拔北家继续（岭上补牌已在 actionKita 摸入手中）
			kitaWindow = false;
			finishKita(kitaSeat);
		} else {
			closeWindowAndAdvance();
		}
	}

	/** 吃组合枚举：hand 中能与 tile 组成顺子的两两组合（tile 可为最小/中间/最大张） */
	private List<int[]> chiCandidates(List<Integer> hand, int tile) {
		List<int[]> result = new ArrayList<>();
		tile = mcr.riichi_arise.game.TileFlag.base(tile);
		int suit = tile / 10, d = tile % 10 == 0 ? 5 : tile % 10; // 红五(0/10/20)按 5 计
		// 组合 1：tile 为最小（d+1, d+2）；组合 2：中间（d-1, d+1）；组合 3：最大（d-2, d-1）
		int[][] wants = { { d + 1, d + 2 }, { d - 1, d + 1 }, { d - 2, d - 1 } };
		for (int[] w : wants) {
			if (w[0] < 1 || w[1] > 9)
				continue;
			int a = findCode(hand, suit * 10 + w[0]);
			int b = findCode(hand, suit * 10 + w[1], a);
			if (a >= 0 && b >= 0)
				result.add(new int[] { hand.get(a), hand.get(b) });
		}
		return result;
	}

	private int findCode(List<Integer> hand, int code) {
		return findCode(hand, code, -1);
	}

	private int findCode(List<Integer> hand, int code, int exclude) {
		for (int i = 0; i < hand.size(); i++)
			if (i != exclude && mcr.riichi_arise.game.TileFlag.base(hand.get(i)) == code)
				return i;
		// 找 5 时可用红五（code-5）替代
		if (code % 10 == 5 && code < 30) {
			for (int i = 0; i < hand.size(); i++)
				if (i != exclude && mcr.riichi_arise.game.TileFlag.base(hand.get(i)) == code - 5)
					return i;
		}
		return -1;
	}

	/** 窗口超时：清窗口 → 下家 */
	private void closeWindowAndAdvance() {
		claimWindowEnd = 0;
		claimDiscarder = -1;
		claimTile = -1;
		clearAllOptions();
		advanceTurn();
	}

	/** 无人鸣牌 → 下家回合（一发标记保留：立直一巡内有效，直到该家再次打牌才过期） */
	private void advanceTurn() {
		beginTurn((turn + 1) % pc()); // 三麻轮转跳过北家座位（0→1→2→0）
	}

	/** 本巡推进下家；pause=true（AI 刚立直宣言后打牌）时额外停 1s 再推进，便于看清宣言；副露的 1s 停顿在切牌前 */
	private void advanceAfterDiscard(boolean pause, int discarder) {
		if (!pause) {
			advanceTurn();
			return;
		}
		schedule(20, () -> {
			// 期间已被其他路径推进（或进入结算）则不重复推进
			if (waitingDiscard || claimWindowEnd > 0 || turn != discarder)
				return;
			advanceTurn();
		});
	}

	// ==================================================================
	// 响应处理（吃/碰/明杠/荣和）
	// ==================================================================

	/** 玩家响应（先响应先生效；选项必须仍在窗口内且属于该玩家） */
	private void tryClaim(int seat, String action, int comboIdx) {
		if (claimWindowEnd <= 0 || seat == claimDiscarder)
			return;
		List<String> opts = claimOptions[seat];
		if (opts == null)
			return;
		switch (action) {
				case "ron" -> {
					if (!opts.contains("ron"))
						return;
					// 多家荣和：先记录，待全员表态后统一结算
					pendingRons.add(seat);
					claimOptions[seat] = null;
					ServerPlayer rp = st.playerOf(level, seat);
					if (rp != null)
						mcr.riichi_arise.network.MahjongGamePayloads.sendOptions(rp, origin, "");
					resolveWindow();
				}
			case "pon", "minkan" -> {
				if (!opts.contains(action))
					return;
				claimWindowEnd = 0;
				clearAllOptions();
				doOpenMeld(seat, action.equals("pon") ? Fuuro.Type.PON : Fuuro.Type.MINKAN, claimTile, null);
			}
			case "chi" -> {
				// token 为完整三张格式（"chi:<idx>:<tile>,<a>,<b>"），须按前缀匹配组合索引
				// （全串 contains 永远失配 → 数字键吃牌静默无效；与 bestAiChi 的解析方式一致）
				String key = "chi:" + comboIdx + ":";
				if (comboIdx < 0 || comboIdx >= chiCombos.size() || opts.stream().noneMatch(t -> t.startsWith(key)))
					return;
				int[] combo = chiCombos.get(comboIdx);
				claimWindowEnd = 0;
				clearAllOptions();
				doOpenMeld(seat, Fuuro.Type.CHII, claimTile, combo);
			}
		}
	}

	/** 执行吃/碰/明杠：手牌移除对应牌 + 声明牌移出打牌者牌河 → 副露 → 打牌阶段（不摸牌） */
	private void doOpenMeld(int seat, Fuuro.Type type, int calledTile, int[] chiPair) {
		RiichiPlayer p = ps[seat];
		RiichiPlayer from = ps[claimDiscarder];
		int[] tiles;
		switch (type) {
			case CHII -> {
				// AI 给出的吃组合是不带透明标志的牌面 code：按牌面（忽略标志）移出手牌，保留实际 code（透明标志）
				int[] pair = new int[2];
				for (int k = 0; k < 2; k++) {
					for (int i = 0; i < p.hand.size(); i++)
						if (mcr.riichi_arise.game.TileFlag.same(p.hand.get(i), chiPair[k])) {
							pair[k] = p.hand.remove(i);
							break;
						}
				}
				tiles = new int[] { pair[0], pair[1], calledTile };
			}
			case PON -> tiles = takeMeldTiles(p, calledTile, 2);
			default -> tiles = takeMeldTiles(p, calledTile, 3); // MINKAN
		}
		// 声明牌移出打牌者牌河（渲染随整桌重建更新）
		if (!from.river.isEmpty())
			from.river.remove(from.river.size() - 1);
		// 被取走的若是该家立直宣言牌 → 取消横摆标记，该家下一张切牌改为横摆补回
		if (st.riichiRiverIdx[claimDiscarder] >= from.river.size()) {
			st.riichiRiverIdx[claimDiscarder] = -1;
			st.riichiPending[claimDiscarder] = true;
		}
		// 有人鸣牌 → 全部一发失效；双立直判定失效
		java.util.Arrays.fill(ippatsu, false);
		anyCall = true;
		st.drawnSeat = -1; // 副露家未摸牌（声明牌不在手牌中）
		p.melds.add(new Fuuro(type, tiles, calledTile, claimDiscarder));
		// T 行：吃(c)/碰(p)/明杠(k)
		MahjongGameLog.turnEvent(level, origin, seat,
				type == Fuuro.Type.CHII ? "c" : type == Fuuro.Type.PON ? "p" : "k",
				MahjongTileNotation.format(java.util.Arrays.stream(tiles).boxed().toList())
						+ "|" + claimDiscarder);
		// boss 栏：最近一次行动（xxx碰 / xxx吃 / xxx杠）
		setBanner(p.name + (type == Fuuro.Type.CHII ? "吃" : type == Fuuro.Type.PON ? "碰" : "杠"));
		writeBack();
		clearAllOptions();
		if (type == Fuuro.Type.MINKAN)
			finishKong(seat); // 明杠：与暗杠/加杠一致，岭上摸牌 + 揭新宝
		else
			startDiscardPhase(seat, false, -1, type == Fuuro.Type.PON); // 碰后当巡标记：禁拔北
		messageTable("message.riichi_arise.called", p.name);
		broadcast(Component.translatable("message.riichi_arise.called_chat", p.name, tileName(calledTile)).getString());
		// 天凤式角色语音：吃/碰（明杠的杠语音在 finishKong 内播，避免重复）
		if (type == Fuuro.Type.CHII)
			voiceEvent(seat, mcr.riichi_arise.voice.VoiceEvents.CHI);
		else if (type == Fuuro.Type.PON)
			voiceEvent(seat, mcr.riichi_arise.voice.VoiceEvents.PON);
		// 鸣牌音效：吃 = 吃完食物；碰 = 末影珍珠触地；明杠 = 铁砧放置（finishKong 内已放，避免双重）
		Vec3 c = FengPanBlock.tableCenter(level, origin);
		if (type != Fuuro.Type.MINKAN) {
			var sound = switch (type) {
				case CHII -> SoundEvents.PLAYER_BURP;
				case PON -> SoundEvents.ENDERMAN_TELEPORT;
				default -> SoundEvents.ANVIL_PLACE;
			};
			level.playSound(null, c.x, c.y, c.z, sound, SoundSource.PLAYERS, 1.0f, 1.0f);
		}
	}

	/**
	 * 碰/明杠取牌：手牌中与声明牌同种（红五与普通五等价）的 count 张移入副露，
	 * 红五优先（红宝牌立即可见），避免声明牌为红五时按原码匹配凑不齐三张。
	 */
	private int[] takeMeldTiles(RiichiPlayer p, int calledTile, int count) {
		int[] tiles = new int[count + 1];
		tiles[0] = calledTile;
		int n = 1;
		for (int i = 0; i < p.hand.size() && n < tiles.length; i++)
			if (sameTile(p.hand.get(i), calledTile)) {
				tiles[n++] = p.hand.get(i);
				p.hand.remove(i--);
			}
		return tiles;
	}

	// ==================================================================
	// 杠（暗杠/加杠，明杠走 doOpenMeld）
	// ==================================================================

	/** 同种牌判定（红五万 0 与五万 5 视为同牌；忽略透明标志） */
	private static boolean sameTile(int a, int b) {
		int na = mcr.riichi_arise.game.TileFlag.base(a), nb = mcr.riichi_arise.game.TileFlag.base(b);
		na = na == 0 ? 5 : na;
		nb = nb == 0 ? 5 : nb;
		return na == nb;
	}

	/** 暗杠候选：手牌中某牌恰有 4 张，返回手牌中该张实际 code（带透明标志，便于按值移出） */
	private int findAnkan(int seat) {
		int[] count = new int[37];
		for (int c : ps[seat].hand)
			count[mcr.riichi_arise.game.TileFlag.base(c)]++;
		for (int c = 0; c < 37; c++)
			if (count[c] == 4) {
				for (int h : ps[seat].hand)
					if (mcr.riichi_arise.game.TileFlag.base(h) == c)
						return h; // 返回实际手牌 code（保留透明标志）
			}
		return -1;
	}

	/** 加杠候选：已有碰副露 + 手牌中有同种牌（红五/普通五互通），返回手牌中实际 code */
	private int findKakan(int seat) {
		RiichiPlayer p = ps[seat];
		if (p.riichi)
			return -1;
		for (Fuuro f : p.melds) {
			if (f.type != Fuuro.Type.PON)
				continue;
			for (int h : p.hand)
				if (sameTile(h, f.calledTile))
					return h; // 返回实际手牌 code（保留透明标志）
		}
		return -1;
	}

	private void doKong(int seat, String kind, int code) {
		// 岭上耗尽守卫：杠必须摸岭上补牌，耗尽时禁止杠（三麻拔北/杠高频时可能），本回合继续打牌
		if (wall.rinshanRemaining() <= 0) {
			ServerPlayer hp = st.playerOf(level, seat);
			if (hp != null)
				hp.displayClientMessage(Component.literal("§c岭上牌已耗尽，无法杠"), true);
			return;
		}
		swingIfNpc(seat);
		if (code < 0)
			return;
		RiichiPlayer p = ps[seat];
		st.drawnSeat = -1; // 摸进的牌被杠消耗（岭上摸牌后由 startDiscardPhase 重新置位）
		Fuuro fuuro;
		if (kind.equals("ankan")) {
			int n = 0;
			int[] tiles = new int[4];
			for (int i = p.hand.size() - 1; i >= 0 && n < 4; i--) {
				if (sameTile(p.hand.get(i), code)) {
					tiles[n++] = p.hand.get(i); // 保留每张实际 code（透明标志）
					p.hand.remove(i);
				}
			}
			if (n < 4)
				return;
			fuuro = new Fuuro(Fuuro.Type.ANKAN, tiles, code, seat);
		} else { // kakan：找同 code 的碰副露升级
			Fuuro pon = null;
			for (Fuuro f : p.melds) {
				if (f.type == Fuuro.Type.PON && sameTile(f.calledTile, code)) {
					pon = f;
					break;
				}
			}
			if (pon == null || !p.hand.contains(code))
				return;
			p.hand.remove(Integer.valueOf(code));
			int[] tiles = new int[4];
			System.arraycopy(pon.tiles, 0, tiles, 0, 3);
			tiles[3] = code;
			p.melds.remove(pon);
			fuuro = new Fuuro(Fuuro.Type.KAKAN, tiles, code, pon.fromSeat);
		}
		p.melds.add(fuuro);
		anyCall = true;
		// boss 栏：最近一次行动（xxx暗杠 / xxx加杠）
		setBanner(p.name + (kind.equals("ankan") ? "暗杠" : "加杠"));
		// T 行：暗杠(a 四张)/加杠(g 一张)
		MahjongGameLog.turnEvent(level, origin, seat, kind.equals("ankan") ? "a" : "g",
				MahjongTileNotation.format(java.util.Arrays.stream(fuuro.tiles).boxed().toList()));
		broadcast(Component.translatable("message.riichi_arise.kong_chat", p.name, tileName(code)).getString());
		// 加杠 → 抢杠检查：他家可荣和该张则开抢杠窗口，无人抢才岭上摸牌
		if (kind.equals("kakan")) {
			List<Integer> claimers = new ArrayList<>();
			for (int s = 0; s < pc(); s++)
				if (s != seat && canRonTile(s, code))
					claimers.add(s);
			if (!claimers.isEmpty()) {
				openChankanWindow(seat, code, claimers);
				return;
			}
		}
		finishKong(seat);
	}

	/** 抢杠窗口开启：仅允许荣和该加杠张；全员响应后统一结算 */
	private void openChankanWindow(int kongSeat, int tile, List<Integer> claimers) {
		chankanWindow = true;
		chankanFlag = true;
		chankanKongSeat = kongSeat;
		claimDiscarder = kongSeat;
		claimTile = tile;
		claimWindowEnd = level.getGameTime() + CLAIM_WINDOW_TICKS;
		claimShortEnd = 0;
		pendingRons.clear();
		chiCombos.clear();
		for (int s : claimers) {
			claimOptions[s] = List.of("ron");
			if (ps[s].ai) {
				scheduleAiRon(s); // AI 抢杠同样停 0.5s 再宣布
			} else {
				ServerPlayer p = st.playerOf(level, s);
				if (p != null) {
					mcr.riichi_arise.network.MahjongGamePayloads.sendOptions(p, origin, "ron:" + tile);
					playOptionSound(p);
					sendClaimCountdown(s, RichiTableState.THINK_PRESETS[st.thinkIdx][0],
							(st.longBank[s] + 19) / 20);
				}
			}
		}
		armClaimTiming();
		writeBack();
		resolveWindow();
	}

	/** 杠收尾：岭上摸牌 + 揭新宝（明牌）+ 进入打牌阶段 */
	private void finishKong(int seat) {
		RiichiPlayer p = ps[seat];
		int rinshan = wall.rinshanDraw();
		if (rinshan >= 0)
			p.hand.add(rinshan);
		wall.revealNextDora();
		st.revealedIndicators = wall.revealedIndicatorCount();
		rinshanFlag = true;
		lastTileHand = wall.isEmpty();
		writeBack();
		clearAllOptions();
		startDiscardPhase(seat, true, rinshan, false);
		voiceEvent(seat, mcr.riichi_arise.voice.VoiceEvents.KAN); // 天凤式角色语音：杠（暗杠/加杠/明杠统一在此）
		// 杠音效：铁砧放置
		Vec3 c = FengPanBlock.tableCenter(level, origin);
		level.playSound(null, c.x, c.y, c.z, SoundEvents.ANVIL_PLACE, SoundSource.PLAYERS, 0.6f, 1.0f);
	}

	// ==================================================================
	// 拔北（三麻专用）
	// ==================================================================

	/** 是否可拔北（三麻）：手中持有北风 + 岭上有余 + 非碰后当巡。
	 *  立直家也可拔北，但只能拔刚摸到的那张北（手牌最右一张 = 北）；未立直时手中任意北均可拔。 */
	private boolean canKita(int seat) {
		if (pc() != 3 || discardFromPon)
			return false; // 仅三麻；玻璃之间同样允许拔北
		if (wall.rinshanRemaining() <= 0)
			return false; // 岭上耗尽禁止拔北（北风横牌需岭上补牌）
		if (ps[seat].riichi)
			return justDrew && !ps[seat].hand.isEmpty()
					&& mcr.riichi_arise.game.TileFlag.base(ps[seat].hand.get(ps[seat].hand.size() - 1)) == TILE_PEI;
		return countIn(ps[seat].hand, TILE_PEI) > 0;
	}

	/** 执行拔北（三麻）：手中北风移出 → 摸岭上补牌（不揭新宝牌）→ 抢北窗口（无人抢则继续打牌）。
	 *  拔北后一发全体失效、天地和作废；拔北本身不破坏门清、不计入副露。 */
	private void actionKita(int seat) {
		if (!waitingDiscard || turn != seat || !canKita(seat))
			return;
		swingIfNpc(seat);
		RiichiPlayer p = ps[seat];
		int peiIdx = -1;
		for (int i = 0; i < p.hand.size(); i++)
			if (mcr.riichi_arise.game.TileFlag.base(p.hand.get(i)) == TILE_PEI) {
				peiIdx = i;
				break;
			}
		if (peiIdx < 0)
			return;
		p.hand.remove(peiIdx);
		p.kitaCount++;
		st.kita[seat]++;
		tenhouVoid = true; // 本局已拔北：天地和不再成立（雀魂规则）
		anyCall = true; // 拔北同鸣牌：此后不能再成立双立直
		kyuushuAvailable = false; // 拔北后九种九牌途中流局作废
		java.util.Arrays.fill(ippatsu, false); // 拔北为一巡内行动：一发全体失效
		// T 行：拔北（b + 北风牌面，三麻牌谱专用事件）
		MahjongGameLog.turnEvent(level, origin, seat, "b", MahjongTileNotation.format(List.of(TILE_PEI)));
		setBanner(p.name + "拔北"); // boss 栏：最近一次行动
		broadcast(p.name + " 拔北 " + tileName(TILE_PEI));
		// 摸岭上补牌（不调 revealNextDora：拔北不翻新宝牌）；此后自摸可成立岭上开花
		int rinshan = wall.rinshanDraw();
		if (rinshan >= 0)
			p.hand.add(rinshan);
		rinshanFlag = true;
		lastTileHand = wall.isEmpty();
		writeBack(); // 拔北数经快照 kita 字段同步（不算副露）
		voiceEvent(seat, mcr.riichi_arise.voice.VoiceEvents.BEI); // 天凤式角色语音：拔北（内置角色 act_babei）
		// 抢北检查：他家可荣和该张北风则开抢北窗口（复用抢杠窗口机制，不加抢杠番、不触发岭上开花）
		List<Integer> claimers = new ArrayList<>();
		for (int s = 0; s < pc(); s++)
			if (s != seat && canRonTile(s, TILE_PEI))
				claimers.add(s);
		if (!claimers.isEmpty()) {
			openKitaWindow(seat, claimers);
			return;
		}
		resumeAfterKita(seat, rinshan);
	}

	/** 拔北后进入打牌阶段：AI 延迟 10 tick（先让岭上补牌/北风横牌渲染出来），人类立即继续。 */
	private void resumeAfterKita(int seat, int rinshan) {
		if (!ps[seat].ai) {
			startDiscardPhase(seat, true, rinshan, false);
			return;
		}
		schedule(10 * aiMul(), () -> {
			if (!waitingDiscard || turn != seat)
				return; // 期间被终局/换局打断
			startDiscardPhase(seat, true, rinshan, false);
		});
	}

	/** 抢北窗口开启：仅允许荣和该张拔出的北风（复用响应窗口机制；kitaWindow 标记，
	 *  chankanWindow/chankanFlag 保持 false → 荣和结算不加抢杠番）。全员响应后统一结算。 */
	private void openKitaWindow(int kitaBy, List<Integer> claimers) {
		kitaWindow = true;
		kitaSeat = kitaBy;
		claimDiscarder = kitaBy;
		claimTile = TILE_PEI;
		claimWindowEnd = level.getGameTime() + CLAIM_WINDOW_TICKS;
		claimShortEnd = 0;
		pendingRons.clear();
		chiCombos.clear();
		for (int s : claimers) {
			claimOptions[s] = List.of("ron");
			if (ps[s].ai) {
				scheduleAiRon(s); // AI 抢北同样停 0.5s 再宣布
			} else {
				ServerPlayer p = st.playerOf(level, s);
				if (p != null) {
					mcr.riichi_arise.network.MahjongGamePayloads.sendOptions(p, origin, "ron:" + TILE_PEI);
					playOptionSound(p);
					sendClaimCountdown(s, RichiTableState.THINK_PRESETS[st.thinkIdx][0],
							(st.longBank[s] + 19) / 20);
				}
			}
		}
		armClaimTiming();
		resolveWindow();
	}

	/** 抢北无人荣和 → 拔北家继续：进入打牌阶段（岭上补牌已在 actionKita 摸入手中；AI 同样延迟 10 tick） */
	private void finishKita(int seat) {
		claimDiscarder = -1;
		claimTile = -1;
		clearAllOptions();
		resumeAfterKita(seat, ps[seat].hand.isEmpty() ? -1 : ps[seat].hand.get(ps[seat].hand.size() - 1));
	}

	// ==================================================================
	// 荣和 / 自摸 / 流局 / 结算
	// ==================================================================

	/** 荣和结算（多家荣和支持）：放铳者按各家点数支付，本场费每家 300×本场，立直棒给最靠右的和牌者 */
	private void doMultiRon(List<Integer> winners) {
		winners.sort(Integer::compareTo); // 结算按座次顺序展示
		int sticks = totalRiichiSticks() + st.carriedSticks * 1000;
		// 立直棒归最靠近放铳者右边的和牌者（头跳；三麻无北家，沿参战座位轮转）
		int stickWinner = -1;
		for (int k = 1; k <= pc() && stickWinner < 0; k++)
			if (winners.contains((claimDiscarder + k) % pc()))
				stickWinner = (claimDiscarder + k) % pc();
		winEffect(FengPanBlock.riverTilePos(level, origin, claimDiscarder, ps[claimDiscarder].river.size() - 1));
		var resultBySeat = new java.util.LinkedHashMap<Integer, Mahjong4jBridge.WinResult>();
		String names = "", details = "", bannerDetails = "";
		int total = 0;
		for (int w : winners) {
			RiichiPlayer p = ps[w];
			List<Integer> full = new ArrayList<>(p.hand);
			full.add(claimTile);
			// 地和：闲家荣和本局第一张打出的牌（须构成和牌形）
			boolean chiho = isChihoWin(w);
			var r = chiho
					? tenhouChihoResult(w, false, false, p, full)
					: Mahjong4jBridge.score(full, p.melds, w, dealerSeat(), st.roundWind, false,
							p.riichi, ippatsu[w], ronFlags(p), wall.actualDoras(), p.kitaCount); // 拔北每张 +1 翻
			if (!r.win())
				continue;
			resultBySeat.put(w, r);
			pendingFan.put(w, buildFanKeys(r, w)); // 报番：荣和役种 + 宝牌计数
			int gain = r.score().getRon() + 300 * st.honba;
			st.points[claimDiscarder] -= gain;
			st.points[w] += gain + (w == stickWinner ? sticks : 0);
			ps[claimDiscarder].points -= gain;
			p.points += gain + (w == stickWinner ? sticks : 0);
			total += gain;
			names = names.isEmpty() ? p.name : names + "、" + p.name;
			details = details.isEmpty() ? describeResult(r) : details + "；" + describeResult(r);
			bannerDetails = bannerDetails.isEmpty() ? shortDescribe(r) : bannerDetails + "；" + shortDescribe(r);
		}
		if (resultBySeat.isEmpty())
			return; // 无人真正和牌：不清立直棒、不结束本局（防软锁与棒凭空消失）
		java.util.Arrays.fill(st.riichiSticks, 0);
		st.carriedSticks = 0;
		// 各和牌家结算缓存（finishRound 延迟 2s 起按座次每 2s 轮流推送）
		for (var e : resultBySeat.entrySet()) {
			RiichiPlayer p = ps[e.getKey()];
			List<Integer> full = new ArrayList<>(p.hand);
			full.add(claimTile);
			// 结算界面显示的 gain 不含场供/立直棒（点数变化界面展示全场收支）
			int gain = e.getValue().score().getRon();
			pendingSettlements.add(new MahjongSettlementPayload.Settlement(
					MahjongSettlementPayload.MODE_HAND_WIN, origin.asLong(), p.name, "ron",
					describeResult(e.getValue()), csv(full), csvMelds(p), claimTile,
					p.riichi ? csv(wall.uraIndicatorTiles()) : "", csv(wall.revealedDoras()),
					buildYakuList(p, full, e.getValue()), e.getValue().han(), e.getValue().fu(), gain, e.getKey() == dealerSeat(),
					seatNames(), st.points.clone(), pc()));
			MahjongGameLog.win(level, origin, handIndex + 1, true, e.getKey(), claimDiscarder,
					String.join("+", e.getValue().yakuNames().stream().map(YakuNames::zh).toList()),
					e.getValue().han(), e.getValue().fu(),
					e.getValue().score().getRon() + 300 * st.honba,
					MahjongTileNotation.format(p.hand), csvMelds(p));
		}
		String pay = Component.translatable("message.riichi_arise.pay", ps[claimDiscarder].name, total).getString();
		finishRound("message.riichi_arise.win_ron", names, details, bannerDetails, pay,
				resultBySeat.keySet().stream().mapToInt(Integer::intValue).toArray());
		// 聊天播报延后：结算界面（含点数变化）全部关闭后再发
		pendingChat = Component.translatable("message.riichi_arise.win_ron", names, details).getString() + " " + pay;
	}

	/** 自摸计分 flags：双立直/岭上开花/海底捞月 + 里宝牌 */
	private Mahjong4jBridge.Flags tsumoFlags(RiichiPlayer p) {
		return new Mahjong4jBridge.Flags(p.doubleRiichi, false, rinshanFlag, lastTileHand, false, wall.uraDoras());
	}

	/** 荣和计分 flags：双立直/抢杠/河底捞鱼 + 里宝牌 */
	private Mahjong4jBridge.Flags ronFlags(RiichiPlayer p) {
		return new Mahjong4jBridge.Flags(p.doubleRiichi, chankanFlag, false, false, houteiFlag, wall.uraDoras());
	}

	/** 天和：庄家在本局无任何打牌时自摸（本局已拔北则作废） */
	private boolean isTenhou(int seat) {
		return !tenhouVoid && discardsThisHand == 0 && seat == dealerSeat();
	}

	/** 第一巡（天地和窗口）且手里 14 张确实构成和牌形——散牌第一巡不能白吃役满（本局已拔北则作废） */
	private boolean firstTurnShapeWin(int seat) {
		return !tenhouVoid && discardsThisHand == 0 && Mahjong4jBridge.isWinnableShape(ps[seat].hand, ps[seat].melds);
	}

	/** 地和：闲家荣和本局第一张打出的牌且能构成和牌形（本局已拔北则作废） */
	private boolean isChihoWin(int seat) {
		if (tenhouVoid || discardsThisHand != 1 || seat == dealerSeat())
			return false;
		List<Integer> full = new ArrayList<>(ps[seat].hand);
		full.add(claimTile);
		return Mahjong4jBridge.isWinnableShape(full, ps[seat].melds);
	}

	private void doTsumo(int seat) {
		swingIfNpc(seat);
		if (!waitingDiscard || !justDrew)
			return;
		RiichiPlayer p = ps[seat];
		// 天和：庄家开局第一摸即和；地和：闲家在第一巡（无任何打牌）自摸——都必须先构成和牌形
		boolean tenhou = isTenhou(seat);
		boolean chihoTsumo = !tenhouVoid && discardsThisHand == 0 && seat != dealerSeat();
		boolean shapeWin = Mahjong4jBridge.isWinnableShape(new ArrayList<>(p.hand), p.melds);
		if (!(tenhou || chihoTsumo) && (!shapeWin || !canWinNow(seat, true)))
			return;
		var r = tenhou || chihoTsumo
				? tenhouChihoResult(seat, true, tenhou, p, new ArrayList<>(p.hand))
				: Mahjong4jBridge.score(new ArrayList<>(p.hand), p.melds, seat, dealerSeat(), st.roundWind, true,
						p.riichi, ippatsu[seat], tsumoFlags(p), wall.actualDoras(), p.kitaCount); // 拔北每张 +1 翻
		if (!r.win())
			return;
		winEffect(FengPanBlock.handTilePos(level, origin, seat, p.hand.size() - 1));
		boolean parent = seat == dealerSeat();
		int[] pays = new int[4];
		if (parent) {
			int each = r.score().getParentTsumo();
			// 庄家自摸：其余参战家各付亲分（三麻=两家，雀魂自摸损；庄家可能不在 0 号位）
			for (int i = 0; i < pc(); i++)
				if (i != seat)
					pays[i] = each;
		} else {
			// 闲家自摸：庄家付亲分，其余闲家付子分（三麻仅两家支付 = 自摸损；含 0 号位庄家）
			for (int i = 0; i < pc(); i++)
				if (i != seat)
					pays[i] = i == dealerSeat() ? r.score().getParent() : r.score().getChild();
		}
		int baseTotal = 0;
		for (int i = 0; i < pc(); i++) {
			if (i == seat)
				continue;
			baseTotal += pays[i];
		}
		// 自摸本场费：其余参战家各付 100×本场数（不计入结算界面 gain，只计入实际转帐）
		if (st.honba > 0)
			for (int i = 0; i < pc(); i++)
				if (i != seat)
					pays[i] += 100 * st.honba;
		int total = 0;
		for (int i = 0; i < pc(); i++) {
			if (i == seat)
				continue;
			st.points[i] -= pays[i];
			ps[i].points -= pays[i];
			total += pays[i];
		}
		st.points[seat] += total + totalRiichiSticks() + st.carriedSticks * 1000;
		p.points += total;
		java.util.Arrays.fill(st.riichiSticks, 0);
		st.carriedSticks = 0;
		StringBuilder payInfo = new StringBuilder();
		for (int i = 0; i < pc(); i++) {
			if (i != seat && pays[i] > 0)
				payInfo.append(ps[i].name).append(" -").append(pays[i]).append(' ');
		}
		pendingSettlements.add(new MahjongSettlementPayload.Settlement(
				MahjongSettlementPayload.MODE_HAND_WIN, origin.asLong(), p.name, "tsumo", describeResult(r),
				csv(p.hand), csvMelds(p), p.hand.isEmpty() ? -1 : p.hand.get(p.hand.size() - 1),
				p.riichi ? csv(wall.uraIndicatorTiles()) : "", csv(wall.revealedDoras()),
				buildYakuList(p, new ArrayList<>(p.hand), r), r.han(), r.fu(), baseTotal, seat == dealerSeat(), seatNames(),
				st.points.clone(), pc()));
		MahjongGameLog.win(level, origin, handIndex + 1, false, seat, -1,
				String.join("+", r.yakuNames().stream().map(YakuNames::zh).toList()),
				r.han(), r.fu(), 0, MahjongTileNotation.format(p.hand), csvMelds(p));
		pendingFan.put(seat, buildFanKeys(r, seat)); // 报番：自摸役种 + 宝牌计数
		finishRound("message.riichi_arise.win_tsumo", p.name, describeResult(r), shortDescribe(r),
				payInfo.toString().trim(), seat);
		pendingChat = Component.translatable("message.riichi_arise.win_tsumo", p.name, describeResult(r)).getString()
				+ " " + payInfo.toString().trim();
	}

	/** 和牌展示文案：役种（中文，与报番同序）+ 翻符（役满显示役名） */
	private String describeResult(Mahjong4jBridge.WinResult r) {
		String names = orderFanYaku(r.yakuNames()).stream().map(YakuNames::zh)
				.reduce((a, b) -> a + "·" + b).orElse("");
		// 宝牌条目按总数追加（宝牌×N、里宝牌×K、红宝牌×M），不显示具体牌面
		List<String> extras = new ArrayList<>();
		appendDora(extras, "宝牌", r.doraHits());
		appendDora(extras, "红宝牌", r.redHits());
		appendDora(extras, "里宝牌", r.uraHits());
		if (!extras.isEmpty())
			names = (names.isEmpty() ? "" : names + "·") + String.join("·", extras);
		if (r.yakuman())
			return names + "（" + yakumanTier(r) + "）";
		return names + "　" + r.han() + "翻" + r.fu() + "符";
	}

	/** 天和/地和结算：+1 倍役满，并与手牌役满叠加（雀魂倍数累计；非役满牌型仍按 1 倍役满计）。fullHand 为含和牌张的 14 张手牌 */
	private Mahjong4jBridge.WinResult tenhouChihoResult(int seat, boolean tsumo, boolean tenhou, RiichiPlayer p,
			List<Integer> fullHand) {
		String flag = tenhou ? "TENHO" : "CHIHO";
		// 先按普通牌型打分：若本身成立役满则叠加倍数（雀魂：天地和与手牌役满累计）
		var inner = Mahjong4jBridge.score(fullHand, p.melds, seat, dealerSeat(), st.roundWind, tsumo,
				p.riichi, ippatsu[seat], tsumo ? tsumoFlags(p) : ronFlags(p), wall.actualDoras());
		int count = 1;
		List<String> names = new ArrayList<>(List.of(flag));
		if (inner.win() && inner.yakuman()) {
			count += inner.han(); // inner.han 编码役满倍数
			names.addAll(inner.yakuNames());
		}
		return new Mahjong4jBridge.WinResult(true, true, count, 0, names,
				Mahjong4jBridge.ScoreVal.yakuman(seat == dealerSeat(), count));
	}

	/** 役满段位文案：han 编码倍数（fu=0），1 倍显示"役满"，n 倍显示"n倍役满" */
	private String yakumanTier(Mahjong4jBridge.WinResult r) {
		return r.han() > 1 ? r.han() + "倍役满" : "役满";
	}

	/** 桌心横幅短描述：不含役种说明，只保留番数符数（役满显示倍数段位） */
	private String shortDescribe(Mahjong4jBridge.WinResult r) {
		if (r.yakuman())
			return "（" + yakumanTier(r) + "）";
		return "　" + r.han() + "翻" + r.fu() + "符";
	}

	/**
	 * 结算界面役种条目（";" 分隔，逐条播报）：役种（中文）→ 宝牌×N → 红宝牌×M → 里宝牌×K。
	 * full = 手牌（荣和含荣和牌）；副露牌面另行计入。
	 */
	private String buildYakuList(RiichiPlayer p, List<Integer> full, Mahjong4jBridge.WinResult r) {
		List<String> entries = new ArrayList<>();
		// 报番顺序与语音一致：岭上 > 海底/河底 > 立直 > 一发 > 自摸 > 其他
		for (String n : orderFanYaku(r.yakuNames()))
			if (!n.equals("RED_DORA") && !n.equals("URADORA"))
				entries.add(YakuNames.zh(n));
		if (r.yakuman())
			return String.join(";", entries); // 役满：只列役满役种，不追加宝牌/里宝牌/红宝牌
		List<Integer> doras = wall.actualDoras();
		List<Integer> uras = p.riichi ? wall.uraDoras() : List.of();
		// 宝牌逐张按牌面聚合（如：宝牌·發×2、里宝牌·白×1、红宝牌·5万×1），不堆叠多条"宝牌"
		List<Integer> reds = new ArrayList<>();
		List<Integer> doraHits = new ArrayList<>();
		List<Integer> uraHits = new ArrayList<>();
		for (int raw : full) {
			int c = mcr.riichi_arise.game.TileFlag.base(raw); // 透明标志不参与宝牌判定
			if (c == 0 || c == 10 || c == 20) {
				reds.add(c);
				continue;
			}
			if (doras.contains(c))
				doraHits.add(c);
			if (uras.contains(c))
				uraHits.add(c);
		}
		for (Fuuro f : p.melds)
			for (int raw : f.tiles) {
				int t = mcr.riichi_arise.game.TileFlag.base(raw);
				if (t == 0 || t == 10 || t == 20) {
					reds.add(t);
					continue;
				}
				if (doras.contains(t))
					doraHits.add(t);
			}
		// 拔北：拔出的北风若为宝牌（三麻指示牌为西），按张数计入表宝牌
		if (p.kitaCount > 0 && doras.contains(TILE_PEI))
			for (int i = 0; i < p.kitaCount; i++)
				doraHits.add(TILE_PEI);
		appendDora(entries, "宝牌", doraHits);
		appendDora(entries, "红宝牌", reds);
		// 拔北（三麻）：宝牌计数条目，排在表/红宝牌之后、里宝牌之前（与语音键顺序一致）
		if (p.kitaCount > 0)
			entries.add("拔北×" + p.kitaCount);
		appendDora(entries, "里宝牌", uraHits);
		return String.join(";", entries);
	}

	/** 宝牌条目只计总数（宝牌×2），不显示具体牌面 */
	private static void appendDora(List<String> entries, String label, List<Integer> codes) {
		if (!codes.isEmpty())
			entries.add(label + "×" + codes.size());
	}

	/** 报番顺序：岭上 > 海底/河底 > 立直 > 一发 > 自摸 > 其他（其他保持原顺序，稳定排序）。
	 *  拔北不在此列：它作为宝牌计数条目（表/红之后、里之前）由 buildYakuList/buildFanKeys 追加 */
	private static List<String> orderFanYaku(List<String> names) {
		List<String> out = new ArrayList<>(names);
		out.sort(java.util.Comparator.comparingInt(RiichiGame::fanPriority));
		return out;
	}

	private static int fanPriority(String name) {
		return switch (name) {
			case "RINSHANKAIHOH" -> 0; // 岭上开花
			case "HAITEI", "HOUTEI" -> 1; // 海底捞月 / 河底捞鱼
			case "REACH", "DOUBLE_REACH" -> 2; // 立直 / 双立直
			case "IPPATSU" -> 3; // 一发
			case "TSUMO" -> 5; // 门清自摸
			default -> 6;
		};
	}

	/** 和牌特效：烟花发射音 + 和牌位置 astral_burst 粒子圆圈（水平半径 0.2，桌面略上方） */
	private void winEffect(Vec3 tilePos) {
		level.playSound(null, tilePos.x, tilePos.y, tilePos.z,
				SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.0f, 1.0f);
		int points = 12;
		for (int i = 0; i < points; i++) {
			double a = Math.PI * 2 * i / points;
			level.sendParticles(mcr.riichi_arise.MahjongParticleTypes.ASTRAL_BURST.get(),
					tilePos.x + Math.cos(a) * 0.2, tilePos.y + 0.1, tilePos.z + Math.sin(a) * 0.2,
					1, 0, 0, 0, 0);
		}
	}

	private int totalRiichiSticks() {
		int sum = 0;
		for (int n : st.riichiSticks)
			sum += n * 1000;
		return sum;
	}

	/** 九种九牌宣言确认（C→S）：途中流局（本场+1、连庄、无听牌罚符） */
	private void doKyuushu(int seat) {
		swingIfNpc(seat);
		if (!kyuushuAvailable || turn != seat || !waitingDiscard)
			return;
		kyuushuAvailable = false;
		MahjongGameLog.turnEvent(level, origin, seat, "q", "");
		// 九种九牌途中流局：流局者亮牌，其余三家盖牌
		finishRound("message.riichi_arise.kyuushu", "", "", "", new int[0], new int[] { seat });
		broadcast(Component.translatable("message.riichi_arise.kyuushu", ps[seat].name).getString());
	}

	/** 流局满贯（幺九字牌宣言失败）：满贯自摸向其余三家收款，立直棒归流满家 */
	private void doNagashiMangan(java.util.List<Integer> winners) {
		int sticks = totalRiichiSticks() + st.carriedSticks * 1000;
		int stickWinner = winners.get(winners.size() - 1); // 多家成立归最后一家（简化）
		String names = "", pay = "";
		for (int seat : winners) {
			RiichiPlayer p = ps[seat];
			boolean parent = seat == dealerSeat();
			org.mahjong4j.Score score = org.mahjong4j.Score.calculateScore(parent, 5, 30); // 满贯
			int[] pays = new int[4];
			if (parent) {
				// 满贯自摸：其余参战家各付亲分（三麻=两家）
				for (int i = 0; i < pc(); i++)
					if (i != seat)
						pays[i] = score.getParentTsumo();
			} else {
				// 庄家付亲分，其余闲家付子分（三麻仅两家支付 = 自摸损；跳过和牌家自己）
				for (int i = 0; i < pc(); i++)
					if (i != seat)
						pays[i] = i == dealerSeat() ? score.getParent() : score.getChild();
			}
			int total = 0;
			for (int i = 0; i < pc(); i++) {
				if (i == seat)
					continue;
				st.points[i] -= pays[i];
				ps[i].points -= pays[i];
				total += pays[i];
			}
			int gain = total + (seat == stickWinner ? sticks : 0);
			st.points[seat] += gain;
			p.points += total;
			names = names.isEmpty() ? p.name : names + "、" + p.name;
			String payLine = Component.translatable("message.riichi_arise.pay", p.name, gain).getString();
			pay = pay.isEmpty() ? payLine : pay + " " + payLine;
			// 结算界面显示的 gain 不含立直棒
			pendingSettlements.add(new MahjongSettlementPayload.Settlement(
					MahjongSettlementPayload.MODE_HAND_WIN, origin.asLong(), p.name, "nagashi", "流局满贯",
					csv(p.hand), csvMelds(p), -1, "", csv(wall.revealedDoras()), "流局满贯",
					0, 0, total, seat == dealerSeat(), seatNames(), st.points.clone(), pc()));
		}
		java.util.Arrays.fill(st.riichiSticks, 0);
		st.carriedSticks = 0;
		finishRound("message.riichi_arise.nagashi_mangan", names, "", pay,
				winners.stream().mapToInt(Integer::intValue).toArray());
		pendingChat = Component.translatable("message.riichi_arise.nagashi_mangan", names).getString() + " " + pay;
	}

	/** 流局：牌山摸尽 + 流局满贯检查 + 听牌罚符（不听向听牌者支付；三麻总池 2000 / 四麻 3000） */
	private void ryuukyoku() {
		// 流局满贯：手牌与牌河全部为幺九字牌 → 按自摸满贯向其余参战家收款（多家成立各自结算）
		java.util.List<Integer> nagashi = new ArrayList<>();
		for (int seat = 0; seat < pc(); seat++) {
			boolean all = !ps[seat].hand.isEmpty() && !ps[seat].river.isEmpty();
			for (int raw : ps[seat].hand) {
				int c = mcr.riichi_arise.game.TileFlag.base(raw);
				if (!(c >= 30 || c % 10 == 1 || c % 10 == 9)) {
					all = false;
					break;
				}
			}
			if (all)
				for (int raw : ps[seat].river) {
					int c = mcr.riichi_arise.game.TileFlag.base(raw);
					if (!(c >= 30 || c % 10 == 1 || c % 10 == 9)) {
						all = false;
						break;
					}
				}
			if (all)
				nagashi.add(seat);
		}
		if (!nagashi.isEmpty()) {
			doNagashiMangan(nagashi);
			return;
		}
		java.util.List<Integer> tenpai = new ArrayList<>();
		java.util.List<Integer> noten = new ArrayList<>();
		for (int seat = 0; seat < pc(); seat++) {
			(Mahjong4jBridge.isTenpai(ps[seat].hand, ps[seat].melds) ? tenpai : noten).add(seat);
		}
		announceDrawVoices(tenpai); // 流局语音：本人只听自己的听牌/未听；观战者听最近一家
		StringBuilder pay = new StringBuilder();
		if (!tenpai.isEmpty() && !noten.isEmpty()) {
			// 标准 1-2-1 拆分：总罚符按人数（三麻 2000 / 四麻 3000），每对（听牌×不听）支付 池/(听牌数×不听数)；
			// 三麻各组合（2000/2=1000）恒整除、支付总额守恒（1听2不听=1000+1000，2听1不听=1000×2）
			int pool = pc() == 3 ? 2000 : 3000;
			int payPair = pool / (tenpai.size() * noten.size());
			for (int t : tenpai) {
				for (int n : noten) {
					st.points[n] -= payPair;
					ps[n].points -= payPair;
					st.points[t] += payPair;
					ps[t].points += payPair;
				}
			}
			pay.append(Component.translatable("message.riichi_arise.noten_pay").getString());
		}
		// 流局时亮出各家听牌：听牌家显示等待张，未听显示"未听牌"
		boolean[] logTenpai = new boolean[4];
		String[] logWaits = new String[4];
		for (int seat = 0; seat < pc(); seat++) {
			String wait = tenpai.contains(seat) ? waitNotation(ps[seat].hand) : "";
			broadcast(ps[seat].name + " "
					+ Component.translatable(wait.isEmpty() ? "message.riichi_arise.noten" : "message.riichi_arise.tenpai_wait",
							wait.isEmpty() ? "" : wait).getString());
			logTenpai[seat] = !wait.isEmpty();
			logWaits[seat] = wait;
		}
		MahjongGameLog.draw(level, origin, handIndex + 1, logTenpai, logWaits);
		// 摊牌规则：听牌家推倒亮牌；全员立直时全部亮牌（含未听家）（三麻=两家立直，原四家立直自然不成立）
		java.util.List<Integer> faceUp = new ArrayList<>(tenpai);
		boolean allRiichi = true;
		for (int s = 0; s < pc(); s++)
			if (!ps[s].riichi) {
				allRiichi = false;
				break;
			}
		if (allRiichi)
			for (int s = 0; s < pc(); s++)
				if (!faceUp.contains(s))
					faceUp.add(s);
		// 荒牌流局：本局各家立直棒转为供托留存（下一局仍摆在王牌指示叠旁，直到有人和牌取走）
		for (int n : st.riichiSticks)
			st.carriedSticks += n;
		java.util.Arrays.fill(st.riichiSticks, 0);
		finishRound("message.riichi_arise.draw", "", "", pay.toString().trim(),
				new int[0], faceUp.stream().mapToInt(Integer::intValue).toArray(),
				!tenpai.contains(dealerSeat())); // 荒牌流局：庄家未听牌 → 轮庄（推进局目）
		broadcast(Component.translatable("message.riichi_arise.draw").getString()
				+ (pay.length() > 0 ? " " + pay : ""));
	}

	/** 局终了：横幅展示结果 + 延迟 2s 推和牌结算界面（多家荣和按座次每 2s 轮流展示；流局不推）；
	 *  全员确认或 10s 后续局/终局。手牌展示：和牌者推倒（面朝上），其余三家仍立着；
	 *  流局听牌家推倒（面朝上，faceUpSeats）、未听家盖牌（四家立直时全部亮牌）；
	 *  九种九牌途中流局仅流局者亮牌。 */
	private void finishRound(String msgKey, String winner, String detail, String pay, int... winnerSeats) {
		finishRound(msgKey, winner, detail, detail, pay, winnerSeats, new int[0], false);
	}

	/** 同上（bannerDetail：桌心横幅专用短描述，如不含役种） */
	private void finishRound(String msgKey, String winner, String detail, String bannerDetail, String pay,
			int... winnerSeats) {
		finishRound(msgKey, winner, detail, bannerDetail, pay, winnerSeats, new int[0], false);
	}

	/** 同上（faceUpSeats：流局时听牌家列表，推倒亮牌面） */
	private void finishRound(String msgKey, String winner, String detail, String pay,
			int[] winnerSeats, int[] faceUpSeats) {
		finishRound(msgKey, winner, detail, detail, pay, winnerSeats, faceUpSeats, false);
	}

	/** 同上（dealerFlowAdvance：荒牌流局庄家未听牌 → 轮庄推进局目） */
	private void finishRound(String msgKey, String winner, String detail, String pay,
			int[] winnerSeats, int[] faceUpSeats, boolean dealerFlowAdvance) {
		finishRound(msgKey, winner, detail, detail, pay, winnerSeats, faceUpSeats, dealerFlowAdvance);
	}

	/** 局终了主体：bannerDetail 专用于桌心横幅（可传不含役种的短描述），detail 用于聊天播报 */
	private void finishRound(String msgKey, String winner, String detail, String bannerDetail, String pay,
			int[] winnerSeats, int[] faceUpSeats, boolean dealerFlowAdvance) {
		st.phase = RichiTableState.PHASE_FINISHED;
		st.turnSeat = -1;
		st.turnDeadline = 0;
		st.turnInLong = false;
		waitingDiscard = false;
		st.drawnSeat = -1;
		clearAllOptions();
		for (int seat = 0; seat < pc(); seat++) {
			if (contains(winnerSeats, seat) || contains(faceUpSeats, seat))
				st.handsExposed[seat] = RichiTableState.HAND_FACE_UP;
			else if (winnerSeats.length > 0)
				st.handsExposed[seat] = RichiTableState.HAND_STAND;
			else
				st.handsExposed[seat] = RichiTableState.HAND_FACE_DOWN;
		}
		// boss 栏：只显示最近一次行动（xxx放铳xxx / xxx自摸 / xxx流局满贯），点数放第二行；
		// 荒牌流局等其他情况沿用原文案
		String payLine = pay.isEmpty() ? "" : "\n" + pay;
		if (msgKey != null && msgKey.contains("win_ron")) {
			String discarder = claimDiscarder >= 0 && claimDiscarder < 4 ? ps[claimDiscarder].name : "";
			setBanner(discarder + "放铳" + winner + payLine);
		} else if (msgKey != null && msgKey.contains("win_tsumo")) {
			setBanner(winner + "自摸" + payLine);
		} else if (msgKey != null && msgKey.contains("nagashi_mangan")) {
			setBanner(winner + "流局满贯" + payLine);
		} else {
			setBanner(Component.translatable(msgKey, winner, bannerDetail).getString());
		}
		writeBack();
		// 和牌播报只走桌心横幅 + 结算界面，不再刷动作栏文本（流局等其他情况仍保留动作栏提示）
		boolean tsumoWin = msgKey != null && msgKey.contains("win_tsumo");
		boolean ronWin = msgKey != null && msgKey.contains("win_ron");
		boolean winAnnounce = tsumoWin || ronWin
				|| (msgKey != null && msgKey.contains("nagashi_mangan"));
		if (!winAnnounce)
			messageTable(msgKey, winner, detail);
		Vec3 c = FengPanBlock.tableCenter(level, origin);
		if (winnerSeats.length > 0) {
			// 和牌升级音只发给和牌者本人（此前公开播在桌心，放铳者/旁观者也会听到"叮"）
			for (int w : winnerSeats) {
				ServerPlayer wp = st.playerOf(level, w);
				if (wp != null)
					wp.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0f, 1.0f);
			}
		} else
			level.playSound(null, c.x, c.y, c.z, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0f, 1.0f);
		// 天凤式角色语音：和牌（荣 / 自摸按 msgKey 区分）。多家荣和同时喊（客户端并行播放），
		// 结算界面在语音播完后才由客户端打开；报番仍随各自结算界面逐个播放
		int voiceKind = tsumoWin ? 2 : (ronWin ? 1 : 0); // 0=无（流局满贯等）1=荣和 2=自摸
		if (voiceKind > 0)
			for (int w : winnerSeats)
				voiceEvent(w, voiceKind == 2
						? mcr.riichi_arise.voice.VoiceEvents.TUMO
						: mcr.riichi_arise.voice.VoiceEvents.RON);
		mcr.riichi_arise.game.MahjongLobby.broadcastSync(level, origin, st);
		// 预置点数变化数据：玩家关闭和牌结算界面后客户端本地立即展示点数变化（不必等其他人确认）
		for (int seat = 0; seat < pc(); seat++) {
			ServerPlayer p = st.playerOf(level, seat);
			if (p != null)
				mcr.riichi_arise.network.MahjongPointDeltaPayload.sendPrepare(p, origin.asLong(), pc(), seatNames(),
						pointsBeforeRound, st.points);
		}
		// 结算确认门控：全员（人类）确定或 10s 倒计时结束 → 清桌续局 / 终局
		// 连庄规则：庄家和牌或流局 → 本场+1、庄家连庄；庄家输牌 → 局数+1、本场清零
		// 荒牌流局修正：庄家听牌才连庄，庄家未听牌 → 轮庄（推进局目）
		roundAdvance = false;
		for (int w : winnerSeats)
			if (w != dealerSeat())
				roundAdvance = true;
		if (dealerFlowAdvance)
			roundAdvance = true; // 荒牌流局且庄家未听牌 → 轮庄（局数+1、本场清零）
		if (roundAdvance)
			st.honba = 0;
		else
			st.honba++;
		java.util.Arrays.fill(settleConfirmed, false);
		settleWaiting = true;
		settlePhase = 1;
		cancelSettleTask(); // 防御：清掉上一局遗留的结算计时
		settlementQueue.clear();
		for (int i = 0; i < pendingSettlements.size(); i++)
			settlementQueue.add(new SettlementShow(pendingSettlements.get(i),
					i < winnerSeats.length ? winnerSeats[i] : -1));
		if (settlementQueue.isEmpty()) {
			// 无结算界面（荒牌流局 / 九种九牌）：3s 后直接进点数变化界面（不留和牌报番那么长的等待）
			settleTask = this::showPointDelta;
			schedule(3 * 20, settleTask);
		} else {
			// 和牌后等 2s 弹第一个结算界面；后续结算在确认/超时后逐个推送
			settleTask = this::showNextSettlement;
			schedule(2 * 20, settleTask);
		}
	}

	/** 是否至少有一名参战人类玩家（有客户端 → 结算由客户端倒计时自动确认，服务端只留兜底时限） */
	private boolean hasRealClient() {
		for (int i = 0; i < pc(); i++)
			if (!ps[i].ai && !st.externalUuids.contains(ps[i].uuid))
				return true;
		return false;
	}

	/**
	 * 推送队列里的下一个结算界面（含该家的 act 语音与报番），并重置确认状态与兜底计时。
	 * 多家荣和逐个展示：前一家确认/超时后才推下一家，语音与界面同步、不会互相打断；队列空 → 进点数变化界面。
	 */
	private void showNextSettlement() {
		if (!settleWaiting || settlePhase != 1)
			return;
		cancelSettleTask();
		SettlementShow next = settlementQueue.poll();
		if (next == null) {
			showPointDelta();
			return;
		}
		// act 语音已在和牌时统一发出（多家同时喊）；这里只发该家的报番包，与结算界面同步
		if (next.seat() >= 0) {
			List<String> fan = pendingFan.remove(next.seat());
			if (fan != null && !fan.isEmpty())
				mcr.riichi_arise.network.VoicePayloads.sendFan(level, origin, next.seat(), fan);
		}
		sendSettlementToAll(next.data());
		java.util.Arrays.fill(settleConfirmed, false);
		// 确认时限与客户端界面同一时间线；有真实客户端时只留兜底（客户端倒计时归零自动确认）
		int n = next.data().yakuList().isEmpty() ? 0 : next.data().yakuList().split(";").length;
		int announce = n == 0
				? RichiTableState.SETTLE_ANNOUNCE_DELAY + 20
				: RichiTableState.SETTLE_ANNOUNCE_DELAY + 15
						+ RichiTableState.SETTLE_ANNOUNCE_STEP * (n - 1) + 20;
		int delay = announce + RichiTableState.SETTLE_TIER_OFFSET + RichiTableState.SETTLE_CONFIRM_TICKS;
		if (hasRealClient())
			delay += RichiTableState.SETTLE_FALLBACK_TICKS;
		settleTask = this::showNextSettlement;
		schedule(delay, settleTask);
	}

	private static boolean contains(int[] arr, int v) {
		for (int a : arr)
			if (a == v)
				return true;
		return false;
	}

	/** 向对局四家（人类）推送结算界面；规则开启时 8 格内观战者也会被打开 */
	private void sendSettlementToAll(MahjongSettlementPayload.Settlement s) {
		for (int seat = 0; seat < pc(); seat++) {
			ServerPlayer p = st.playerOf(level, seat);
			if (p != null)
				MahjongSettlementPayload.sendTo(p, s);
		}
		sendToSpectators(p -> MahjongSettlementPayload.sendTo(p, s));
	}

	/** 结算阶段 1 结束 → 推送点数变化界面（阶段 2；客户端动画+3s 倒计时后自动确认） */
	private void showPointDelta() {
		if (!settleWaiting || settlePhase != 1)
			return;
		settlePhase = 2;
		// 阶段 2 重新计确认：每人类座位都要在点数变化界面点确定，全员确认后才续局
		java.util.Arrays.fill(settleConfirmed, false);
		settleTask = this::proceedNext;
		for (int seat = 0; seat < pc(); seat++) {
			if (ps[seat].ai)
				continue;
			ServerPlayer p = st.playerOf(level, seat);
			if (p != null)
				mcr.riichi_arise.network.MahjongPointDeltaPayload.sendTo(p, origin.asLong(), pc(), seatNames(),
						pointsBeforeRound, st.points);
		}
		sendToSpectators(p -> mcr.riichi_arise.network.MahjongPointDeltaPayload.sendTo(p, origin.asLong(), pc(),
				seatNames(), pointsBeforeRound, st.points));
		// 兜底：客户端确认包丢失/掉线时仍能续局（正常流程由全员确认提前推进）。
		// 纯 AI 对局没有人类确认包，用短兜底（否则旁观者要等很久才进下一局）
		schedule(hasRealClient() ? RichiTableState.SETTLE_FALLBACK_TICKS * 3 : 7 * 20, settleTask);
	}

	/** 结算确认后推进：连庄（局数不变、本场累加）或轮庄（局数+1）；终局不清桌，下一把开始前才清 */
	private void proceedNext() {
		settleWaiting = false;
		settlePhase = 0;
		pendingSettlements.clear();
		settlementQueue.clear();
		pendingFan.clear();
		settleTask = null;
		// 关闭各家的结算界面与点数变化界面
		for (int seat = 0; seat < 4; seat++) {
			ServerPlayer p = st.playerOf(level, seat);
			if (p != null) {
				MahjongSettlementPayload.sendClose(p);
				mcr.riichi_arise.network.MahjongPointDeltaPayload.sendClose(p);
			}
		}
		sendToSpectators(p -> {
			MahjongSettlementPayload.sendClose(p);
			mcr.riichi_arise.network.MahjongPointDeltaPayload.sendClose(p);
		});
		// 聊天播报在结算界面全部消失后发出
		if (!pendingChat.isEmpty()) {
			broadcast(pendingChat);
			pendingChat = "";
		}
		// 击飞（雀魂规则）：任一家点数 < 0 立即破产终局，不再续局
		int busted = -1;
		for (int i = 0; i < pc(); i++)
			if (st.points[i] < 0) {
				busted = i;
				break;
			}
		if (busted >= 0) {
			broadcast(Component.translatable("message.riichi_arise.bankrupt", ps[busted].name).getString());
			endGame();
			return;
		}
		if (roundAdvance && handIndex + 1 >= totalHands)
			endGame();
		else
			nextHand();
	}

	/** 结算界面确认（C→S）：阶段 1 确认 → 进点数变化界面；阶段 2 确认 → 立即推进 */
	private void confirmSettle(int seat) {
		if (!settleWaiting)
			return;
		settleConfirmed[seat] = true;
		for (int i = 0; i < pc(); i++) {
			// AI 与外部程序接入座位没有结算界面、不会回确认，不计入等待
			if (ps[i].ai || st.externalUuids.contains(ps[i].uuid))
				continue;
			if (!settleConfirmed[i])
				return;
		}
		cancelSettleTask();
		if (settlePhase == 1)
			showNextSettlement(); // 还有下一家荣和 → 继续逐个展示；否则进点数变化界面
		else
			proceedNext();
	}

	/** 取消当前结算阶段的倒计时任务 */
	private void cancelSettleTask() {
		if (settleTask != null) {
			tasks.removeIf(t -> t.run() == settleTask);
			settleTask = null;
		}
	}

	/** 续局：连庄则局数不变（本场累加），轮庄则局数+1 重新发牌。
	 *  注意：轮庄只推进 handIndex（庄家 = handIndex%4，自风按相对庄家偏移计算），
	 *  玩家不换座位——此前额外做了一次玩家座位轮转，导致東2局起庄家判定/自风整体错位，
	 *  连庄与轮庄判断、庄家支付随之出错（终局尤甚）。 */
	private void nextHand() {
		if (roundAdvance)
			handIndex++;
		resetHand();
	}

	/** 投票结束：全员同意后强制终局（聊天播报 + 终局结算界面，不清桌） */
	public void forceEnd() {
		if (st.phase != RichiTableState.PHASE_PLAYING)
			return;
		st.turnSeat = -1;
		st.turnDeadline = 0;
		st.turnInLong = false;
		waitingDiscard = false;
		st.drawnSeat = -1;
		tasks.clear();
		aiRonPending = 0;
		aiMeldPending = 0;
		discarderSeat = -1;
		// 关闭响应窗口：否则 tick 仍会走窗口超时 → closeWindowAndAdvance → AI 继续摸打（牌自己打）
		claimWindowEnd = 0;
		claimShortEnd = 0;
		claimDiscarder = -1;
		claimTile = -1;
		java.util.Arrays.fill(claimOptions, null);
		chiCombos.clear();
		pendingRons.clear();
		kitaWindow = false; // 拔北窗口一并关闭（强制终局不再回收窗口）
		kitaSeat = -1;
		clearAllOptions();
		endGame();
	}

	/** 终局：聊天框向全场播报排名 + 风盘处挑战解锁音效 + 推终局结算界面，回到等待重开 */
	private void endGame() {
		ended = true;
		st.phase = RichiTableState.PHASE_FINISHED;
		// 上一局人类玩家自动回到等待队列：管理界面（含清桌重置后）保留上一局玩家的队列座位，可直接再开
		for (int seat = 0; seat < pc(); seat++)
			if (!st.aiSeat[seat] && st.players[seat] != null && !st.queue.contains(st.players[seat]))
				st.queue.add(st.players[seat]);
		// NPC 参战者终局回到等候队列：保留排队资格并解除钉位（对局结束即可动弹，下一局开局重新入座）
		// 以 pc() 为界：三麻座位 3 无玩家（players[3]=null），fromString(null) 会抛 NPE
		for (int seat = 0; seat < pc(); seat++) {
			String u = st.players[seat];
			Entity e = level.getEntity(java.util.UUID.fromString(u));
			mcr.riichi_arise.game.MahjongParticipant p = e == null ? null : mcr.riichi_arise.game.MahjongParticipantRegistry.of(e);
			if (u != null && st.npcUuids.contains(u) && p != null) {
				if (!st.queue.contains(u))
					st.queue.add(u);
				p.unpin(); // 解除钉位：女仆恢复自由，可走动/被击退；保留排队资格
			}
		}
		st.turnSeat = -1;
		st.turnDeadline = 0;
		writeBack(); // phase=FINISHED：同步包不再携带形象信息，客户端假玩家随之移除
		mcr.riichi_arise.game.MahjongLobby.broadcastSync(level, origin, st);
		Integer[] order = new Integer[pc()];
		for (int i = 0; i < pc(); i++)
			order[i] = i;
		java.util.Arrays.sort(order, (a, b) -> Integer.compare(st.points[b], st.points[a]));
		MahjongGameLog.gameEnd(level, origin, st.points);
		// 天凤算法段位结算：顺位马 + pt（AI 席只播报不入库；pt 增量 一局×0/东风×0.5/半庄×1；
		// 数据存存档 data/mahjong/ranks.json；三麻传 pc() 供马点/基点按人数取值）
		String[] rankUuids = new String[4];
		for (int i = 0; i < pc(); i++)
			rankUuids[i] = st.aiSeat[i] ? null : st.players[i];
		double[][] uma = mcr.riichi_arise.game.MahjongRankStore.recordEnd(level, seatNames(), rankUuids, st.points,
				st.gameType, pc());

		// 女仆/NPC 参牌者：按本场天凤 pt 增量结算好感度（与 pt 量成正比，见 MahjongParticipant.onGameSettled）
		for (int seat = 0; seat < pc(); seat++) {
			String u = st.players[seat];
			if (u == null || !st.npcUuids.contains(u))
				continue;
			Entity e = level.getEntity(java.util.UUID.fromString(u));
			mcr.riichi_arise.game.MahjongParticipant p = e == null ? null : mcr.riichi_arise.game.MahjongParticipantRegistry.of(e);
			if (p != null)
				p.onGameSettled(uma[seat][2]);
		}
		// 聊天框播报（全场玩家）：顺位 + 点数（不含马点）
		var header = Component.translatable("message.riichi_arise.gameover");
		for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
			p.sendSystemMessage(header);
			for (int rank = 0; rank < order.length; rank++) {
				int s = order[rank];
				p.sendSystemMessage(Component.translatable("message.riichi_arise.standing_pt",
						rank + 1, ps[s].name, st.points[s]));
			}
		}
		// 风盘位置挑战进度解锁音效
		Vec3 c = FengPanBlock.tableCenter(level, origin);
		level.playSound(null, c.x, c.y, c.z,
				net.minecraft.sounds.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0f, 1.0f);
		// 终局结算界面（对局四家）
		MahjongSettlementPayload.Settlement finalS = new MahjongSettlementPayload.Settlement(
				MahjongSettlementPayload.MODE_FINAL, origin.asLong(), ps[order[0]].name, "", "",
				"", "", -1, "", "", "", 0, 0, 0, false, seatNames(), st.points.clone(), pc());
		for (int seat = 0; seat < pc(); seat++) {
			ServerPlayer p = st.playerOf(level, seat);
			if (p != null)
				MahjongSettlementPayload.sendTo(p, finalS);
		}
		sendToSpectators(p -> MahjongSettlementPayload.sendTo(p, finalS));
	}

	// ---- 结算数据工具 ----

	private String[] seatNames() {
		String[] names = new String[4];
		for (int i = 0; i < 4; i++)
			names[i] = ps[i].name;
		return names;
	}

	private static String csv(List<Integer> codes) {
		StringBuilder sb = new StringBuilder();
		for (int c : codes) {
			if (sb.length() > 0)
				sb.append(',');
			sb.append(c);
		}
		return sb.toString();
	}

	/** 副露组 → "a,b,c;d,e,f,g"（组间 ';'，声明牌在组末） */
	private static String csvMelds(RiichiPlayer p) {
		StringBuilder sb = new StringBuilder();
		for (Fuuro f : p.melds) {
			if (sb.length() > 0)
				sb.append(';');
			for (int t : f.tiles)
				sb.append(t).append(',');
			sb.setLength(sb.length() - 1);
		}
		return sb.toString();
	}

	// ==================================================================
	// 玩家操作入口
	// ==================================================================

	/** C→S 动作分发（MahjongGamePayloads 调用） */
	public static void handleAction(ServerPlayer sp, BlockPos origin, String action, String data) {
		RiichiGame g = mcr.riichi_arise.game.RichiTableManager.getGame(origin);
		if (g != null)
			g.onAction(sp, action, data);
	}

	private void onAction(ServerPlayer sp, String action, String data) {
		int seat = seatOf(sp.getUUID().toString());
		if (seat < 0)
			return;
		applyAction(seat, action, data);
	}

	/** 外部程序（net 端口接入）操作入口：按外部玩家 uuid 定位座位并执行动作。
	 *  返回 false = 未入座或动作无效（供 net 层 ack）。 */
	public boolean externalAction(String uuid, String action, String data) {
		int seat = findSeat(uuid);
		if (seat < 0)
			return false;
		applyAction(seat, action, data);
		return true;
	}

	/** 按玩家 uuid 定位座位（外部程序用；-1 = 不在本局） */
	public int findSeat(String uuid) {
		return seatOf(uuid);
	}

	private void applyAction(int seat, String action, String data) {
		switch (action) {
			case "discard" -> {
				// 外部程序打牌：data = 手牌下标（net 层已把牌面记法解析为下标）
				if (turn == seat && waitingDiscard)
					playerDiscard(seat, parseInt(data, -1));
			}
			case "chi" -> {
				// data = "<组合索引>:<荣和牌>"（荣和牌仅客户端画牌面用）
				String idxPart = data.contains(":") ? data.substring(0, data.indexOf(':')) : data;
				tryClaim(seat, "chi", parseInt(idxPart, -1));
			}
			case "pon" -> tryClaim(seat, "pon", -1);
			case "minkan" -> tryClaim(seat, "minkan", -1);
			case "ron" -> tryClaim(seat, "ron", -1);
			case "tsumo" -> {
				if (turn == seat && waitingDiscard)
					doTsumo(seat);
			}
			case "riichi" -> {
				if (turn == seat && waitingDiscard && canRiichi(seat) && !pendingRiichi) {
					// 立直两段式：先宣言（清选项 HUD），随后确定打出哪张（真人右键点牌 / 外部程序收 riichi_pick 后发 discard）
					pendingRiichi = true;
					if (st.externalUuids.contains(ps[seat].uuid)) {
						mcr.riichi_arise.net.RichiNetManager.pushRiichiPick(this, seat);
					} else {
						ServerPlayer p = st.playerOf(level, seat);
						if (p != null) {
							mcr.riichi_arise.network.MahjongGamePayloads.sendOptions(p, origin, "");
							p.displayClientMessage(Component.translatable("message.riichi_arise.riichi_pick"), true);
						}
					}
				}
			}
			case "skip" -> onSkip(seat);
			case "settle_confirm" -> confirmSettle(seat);
			case "kyuushu" -> doKyuushu(seat);
			case "ankan" -> {
				if (turn == seat && waitingDiscard)
					doKong(seat, "ankan", parseInt(data, -1));
			}
			case "kakan" -> {
				if (turn == seat && waitingDiscard)
					doKong(seat, "kakan", parseInt(data, -1));
			}
			case "kita" -> {
				// 拔北（三麻）：本回合打牌阶段执行（摸岭上补牌，开抢北响应窗口）
				if (turn == seat && waitingDiscard)
					actionKita(seat);
			}
		}
	}

	/** 手牌点击（FengPanBlock.handleHandInteract 转发）：本家回合选中/打出；响应窗口期右键 = 无操作 */
	public void onTileClick(Player player, int seat, int index) {
		// 立直待选：本家右键手牌 = 确定立直打出该张
		if (pendingRiichi && seat == turn) {
			playerDiscard(seat, index);
			return;
		}
		// 响应窗口期右键：无操作（荣和走键盘数字键，左键 = 跳过）
		if (turn != seat && claimWindowEnd > 0)
			return;
		if (turn != seat || !waitingDiscard) {
			if (pendingRiichi) {
				// 点到错误的牌（别家手牌）→ 取消立直
				cancelRiichi();
				player.displayClientMessage(Component.translatable("message.riichi_arise.riichi_cancel"), true);
			} else {
				player.displayClientMessage(
						Component.translatable("message.riichi_arise.not_your_turn", turn >= 0 ? ps[turn].name : "-"), true);
			}
			return;
		}
		if (st.selectedSeat == seat && st.selectedIndex == index) {
			playerDiscard(seat, index);
		} else {
			st.selectedSeat = seat;
			st.selectedIndex = index;
			writeBack(); // 广播选中状态，客户端抬升对应手牌（±SELECT_LIFT）
		}
	}

	/** 左键手牌：立直待选时取消 / 回合家可自摸则确认自摸，否则摸切 / 窗口期可荣和则确认荣和，否则跳过 */
	public void onTileAttack(Player player, int seat) {
		if (pendingRiichi && seat == turn) {
			cancelRiichi();
			player.displayClientMessage(Component.translatable("message.riichi_arise.riichi_cancel"), true);
			return;
		}
		if (turn == seat && waitingDiscard) {
			// 可自摸时左键 = 确认自摸（防止误摸切跳过自摸）
			if (justDrew && canWinNow(seat, true)) {
				clearAllOptions();
				doTsumo(seat);
			} else {
				playerDiscard(seat, ps[seat].hand.size() - 1); // 摸切
			}
			return;
		}
		// 响应窗口：左键自己手牌 = 可荣和则确认荣和（防止误跳过；计入多家荣和），否则跳过
		if (claimWindowEnd > 0 && seat != claimDiscarder && claimOptions[seat] != null) {
			if (claimOptions[seat].contains("ron") && canRon(seat)) {
				claimOptions[seat] = null;
				pendingRons.add(seat);
				resolveWindow();
			} else {
				onSkip(seat);
			}
		}
	}

	/** 取消立直宣言（左键点/点错牌）：恢复本家选项 */
	private void cancelRiichi() {
		pendingRiichi = false;
		sendSelfOptions(turn);
	}

	/** 跳过：响应窗口中放弃鸣牌/荣和（所有人表态后提前收口）；自家回合放弃自摸/杠选项 */
	private void onSkip(int seat) {
		if (claimWindowEnd > 0 && seat != claimDiscarder && claimOptions[seat] != null) {
			// 放过可荣和的牌 → 振听（立直家永久振听，否则同巡振听到自己下次打牌）
			if (claimOptions[seat].contains("ron")) {
				if (ps[seat].riichi)
					ps[seat].riichiFuriten = true;
				else
					ps[seat].tempFuriten = true;
			}
			claimOptions[seat] = null;
			ServerPlayer p = st.playerOf(level, seat);
			if (p != null) {
				mcr.riichi_arise.network.MahjongGamePayloads.sendOptions(p, origin, "");
				FengPanBlock.clearCountdown(p);
				p.displayClientMessage(Component.translatable("message.riichi_arise.skip_call"), true);
			}
			resolveWindow(); // 人类全部表态后收口（多家荣和 / 抢杠 / 正常推进）
			return;
		}
		// 自家回合：放弃自摸/暗杠/加杠（保留打牌，选项清空即可）
		if (turn == seat && waitingDiscard) {
			ServerPlayer p = st.playerOf(level, seat);
			if (p != null) {
				mcr.riichi_arise.network.MahjongGamePayloads.sendOptions(p, origin, "");
				FengPanBlock.clearCountdown(p);
				p.displayClientMessage(Component.translatable("message.riichi_arise.skip_call"), true);
			}
			// 立直家跳过自摸/拔北选项 → 立即摸切刚摸的牌（不等超时）
			if (ps[seat].riichi && justDrew && st.drawnSeat == seat)
				playerDiscard(seat, ps[seat].hand.size() - 1);
		}
	}

	/** 管理员踢出 → AI 接管该座位 */
	public void makeAI(int seat) {
		if (seat < 0 || seat > 3)
			return;
		ps[seat].ai = true;
		ps[seat].name = aiSeatName(seat);
		st.aiSeat[seat] = true;
		st.players[seat] = RiichiBot.aiUuid(seat);
	}

	public RiichiPlayer player(int seat) {
		return ps[seat];
	}

	/** 桌角原点（net 层推送定位用） */
	public net.minecraft.core.BlockPos origin() {
		return origin;
	}

	/** 当前局庄家座位（net 层推算外部玩家自风用） */
	public int gameDealerSeat() {
		return dealerSeat();
	}

	/** 当前响应窗口的出牌家座位（net 层 claim_options 附带用；-1 = 无窗口） */
	public int claimDiscarderSeat() {
		return claimDiscarder;
	}

	/** 牌局状态（net 层读取渲染层数据源） */
	public RichiTableState tableState() {
		return st;
	}

	// ==================================================================
	// 判定辅助
	// ==================================================================

	private boolean canRon(int seat) {
		return canRonTile(seat, claimTile);
	}

	/** 是否可荣和指定牌（含地和/河底等荣和役判定；振听时不可荣和，自摸不受限） */
	private boolean canRonTile(int seat, int tile) {
		if (ps[seat].riichiFuriten || ps[seat].tempFuriten || riverFuriten(seat))
			return false;
		List<Integer> full = new ArrayList<>(ps[seat].hand);
		full.add(tile);
		return Mahjong4jBridge.score(full, ps[seat].melds, seat, dealerSeat(), st.roundWind, false,
				ps[seat].riichi, ippatsu[seat], ronFlags(ps[seat]), wall.actualDoras()).win();
	}

	/** 临时振听（河内和牌张）：自己的牌河中存在当前听牌时，荣和全部振听（按牌面去重逐张试和） */
	private boolean riverFuriten(int seat) {
		RiichiPlayer p = ps[seat];
		for (int t : new java.util.TreeSet<>(p.river)) {
			int code = mcr.riichi_arise.game.TileFlag.base(t);
			code = code < 30 && code % 10 == 0 ? code + 5 : code; // 红五与普通五同判
			List<Integer> full = new ArrayList<>(p.hand);
			full.add(code);
			if (Mahjong4jBridge.score(full, p.melds, seat, dealerSeat(), st.roundWind, false,
					p.riichi, ippatsu[seat], ronFlags(p), wall.actualDoras()).win())
				return true;
		}
		return false;
	}

	private boolean canWinNow(int seat, boolean tsumo) {
		return Mahjong4jBridge.score(new ArrayList<>(ps[seat].hand), ps[seat].melds,
				seat, dealerSeat(), st.roundWind, tsumo, ps[seat].riichi, ippatsu[seat],
				tsumoFlags(ps[seat]), wall.actualDoras()).win();
	}

	private boolean canRiichi(int seat) {
		RiichiPlayer p = ps[seat];
		if (p.riichi || !p.isMenzen() || st.points[seat] < 1000 || p.hand.isEmpty())
			return false;
		// 尾巡：牌山只剩 4 张以下时不能立直（摸牌后剩余，含本次摸牌已扣除）
		if (wall.remaining() <= 4)
			return false;
		// 立直宣言牌待定：打出任一张后仍听牌
		for (int i = 0; i < p.hand.size(); i++) {
			int removed = p.hand.remove(i);
			boolean tenpai = Mahjong4jBridge.isTenpai(p.hand, p.melds);
			p.hand.add(i, removed);
			if (tenpai)
				return true;
		}
		return false;
	}

	private static int countIn(List<Integer> hand, int code) {
		int n = 0;
		int b = mcr.riichi_arise.game.TileFlag.base(code);
		for (int c : hand)
			if (mcr.riichi_arise.game.TileFlag.base(c) == b)
				n++;
		return n;
	}

	// ==================================================================
	// AI / tick / 工具
	// ==================================================================

	/** 每服务端 tick（MahjongTicker 转发） */
	public void tick(long now) {
		if (ended)
			return; // 已终局：延迟任务与响应窗口一律不再推进（否则窗口超时会把牌局继续打下去）
		if (!tasks.isEmpty()) {
			List<DelayedTask> due = null;
			for (DelayedTask t : tasks)
				if (now >= t.at())
					(due == null ? due = new ArrayList<>() : due).add(t);
			if (due != null) {
				tasks.removeAll(due);
				for (DelayedTask t : due)
					t.run().run();
			}
		}
		if (claimWindowEnd > 0)
			tickClaimWindow(now);
		// boss 栏横幅显示 60 tick 后自动清空（无新行动覆盖时；新行动会刷新 bannerUntil）
		if (bannerUntil > 0 && now >= bannerUntil) {
			st.banner = "";
			bannerUntil = 0;
		}
	}

	/**
	 * 响应窗口长短考计时：短考段 = 思考档位每巡时限；之后逐 tick 消耗各家长考银行（与打牌回合同款）。
	 * 全局保底 cap = 短考 + 可响应人类中最大的长考银行（正常由各家表态/耗尽提前关窗）。
	 */
	private void armClaimTiming() {
		long now = level.getGameTime();
		int shortTicks = RichiTableState.THINK_PRESETS[st.thinkIdx][0] * 20;
		claimShortEnd = now + shortTicks;
		int maxBank = 0;
		for (int s = 0; s < pc(); s++)
			if (!ps[s].ai && claimOptions[s] != null)
				maxBank = Math.max(maxBank, st.longBank[s]);
		claimWindowEnd = claimShortEnd + maxBank + 40; // 40t 余量防边界竞态
	}

	/**
	 * 响应窗口倒计时：短考段先行（与打牌回合同款），耗尽后实时消耗各待副露家长考银行
	 * （不公开——倒计时只推给该家自己）。长短考均耗尽：可荣和则自动胡牌，否则自动跳过。
	 */
	private void tickClaimWindow(long now) {
		for (int seat = 0; seat < pc(); seat++) {
			if (seat == claimDiscarder || ps[seat].ai || claimOptions[seat] == null)
				continue;
			if (now < claimShortEnd) {
				// 短考段：每秒推送（短考秒数 + 长考银行秒数）
				if ((claimShortEnd - now) % 20 == 0) {
					int sec = (int) ((claimShortEnd - now) / 20);
					sendClaimCountdown(seat, sec, (st.longBank[seat] + 19) / 20);
					if (sec <= 3 && st.longBank[seat] <= 0)
						playHarp(seat); // 最后 3 秒每秒一声竖琴提示（还有长考银行时不提示）
				}
				continue;
			}
			if (st.longBank[seat] > 0) {
				// 长考段：逐 tick 消耗银行
				st.longBank[seat]--;
				if (st.longBank[seat] % 20 == 0) {
					sendClaimCountdown(seat, 0, (st.longBank[seat] + 19) / 20);
					if (st.longBank[seat] <= 60)
						playHarp(seat);
				}
				continue;
			}
			// 短考与长考均耗尽：有荣和 → 自动荣和（计入多家荣和）；否则自动跳过
			if (claimOptions[seat].contains("ron") && canRon(seat)) {
				pendingRons.add(seat);
				claimOptions[seat] = null;
				resolveWindow();
				return;
			}
			onSkip(seat);
			if (claimWindowEnd == 0)
				return; // 全员表态关窗
		}
		if (claimWindowEnd > 0 && now >= claimWindowEnd)
			closeWindowAndAdvance(); // 保底（正常由各家表态/银行耗尽驱动结束）
	}

	/** 副露/荣和等待倒计时（短考白色 + 长考黄色，只发给该家） */
	private void sendClaimCountdown(int seat, int shortSec, int longSec) {
		ServerPlayer p = st.playerOf(level, seat);
		if (p != null)
			net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
					new mcr.riichi_arise.network.MahjongCountdownPayload.CountdownMessage(
							Math.max(0, shortSec), Math.max(0, longSec)));
	}

	/** 倒计时末 3 秒提示音：音符盒竖琴 A 音，仅该家可闻 */
	private void playHarp(int seat) {
		ServerPlayer p = st.playerOf(level, seat);
		if (p != null)
			p.playNotifySound(SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.PLAYERS, 1.0f, 0.891f);
	}

	private void schedule(long delayTicks, Runnable task) {
		tasks.add(new DelayedTask(level.getGameTime() + delayTicks, task));
	}

	private void aiTakeTurn() {
		int seat = turn;
		if (!waitingDiscard || seat < 0 || !ps[seat].ai)
			return;
		// 剧本（临时）：前 10 巡按预排顺序手切（不鸣牌/不立直/不自摸/不九种九牌）；
		// 换牌后鹫巢岩/八木唯强制立直，赤木默听（只摸切），三家都摸切
		if (film != null) {
			if (film.isInjected() && !ps[seat].riichi && seat != FilmScenario.NO_RIICHI_SEAT)
				pendingRiichi = true;
			int index = film.isInjected() ? ps[seat].hand.size() - 1
					: film.nextDiscardIndex(ps[seat].hand, seat);
			playerDiscard(seat, index);
			return;
		}
		if (kyuushuAvailable) {
			doKyuushu(seat); // AI 直接宣言九种九牌
			return;
		}
		// 决策优先级：①自摸 ②立直 ③拔北 ④其余（暗杠/打牌）。
		// ① 自摸：能和即和（天和/地和/岭上开花同路径），按速度档延迟后宣布
		// （立直摸切家立即和，避免与自动摸切任务竞态）
		if (justDrew && (canWinNow(seat, true) || firstTurnShapeWin(seat))) {
			if (ps[seat].riichi)
				doTsumo(seat);
			else
				schedule(10 * aiMul(), () -> doTsumo(seat));
			return;
		}
		int flow = Math.floorMod(st.aiFlows[seat], RiichiBot.FLOW_COUNT);
		boolean defensiveFlow = flow == 2 || flow == 4;
		boolean opponentRiichi = defendActive(seat);
		// 鬼神境：知他家手牌——可见牌计入他家手牌（剩余枚数即真实牌山）
		int[] visible = flow == mcr.riichi_arise.game.riichi.AiCharacters.FLOW_ONIGAMI
				? buildOmniscient34(seat) : buildVisible34(seat);
		// 立直决策（原为门清听牌无条件立直）：宣言立直则本巡直接切牌成立，优先于拔北/杠
		// 听 0 张（无牌可和）绝不立直；早巡立直用于压制对手并提升打点（无役带宝牌尤其需要），
		// 好型（存活听 ≥4）同样立直——两者优先于防守流克制；打点够高（荣和 ≥5200）时听张少也立；
		// 仅终盘防守流（门清流/魂天流）面对别家立直、听张少（≤4）且打点不高时放弃立直
		if (canRiichi(seat)) {
			int waits = countWaits(seat, visible);
			boolean early = wall.remaining() >= 60;
			boolean goodShape = waits >= 4;
			boolean highValue = waits > 0 && estimateRiichiValue(seat, visible) >= 5200;
			if (waits > 0 && (early || goodShape || highValue
					|| !(defensiveFlow && opponentRiichi && waits <= 4)))
				pendingRiichi = true;
		}
		// 拔北（三麻）：手中有北且允许即拔（每张 +1 翻；立直宣言让位；立直家摸到北时同样在此拔，
		// 仅刚摸的那张，见 canKita）
		if (!pendingRiichi && canKita(seat)) {
			actionKita(seat);
			return;
		}
		// 开杠决策：防守型（门清流/魂天流）视情况——场上有立直家时不杠（不暴露新宝牌信息、
		// 加杠有被抢杠风险且延迟防守）；无立直威胁时照常开杠，其余流派恒开；
		// 宣言立直当巡不再杠（直接按立直要求切牌）
		int ankan = pendingRiichi ? -1 : findAnkan(seat);
		// 岭上耗尽守卫：杠需摸岭上补牌，耗尽时 AI 不再选择杠
		if (ankan >= 0 && wall.rinshanRemaining() > 0 && !(defensiveFlow && opponentRiichi)) {
			doKong(seat, "ankan", ankan);
			return;
		}
		// 立直后打牌锁定（规则）：除自摸/暗杠/拔北外只能摸切，杜绝任何手切路径
		if (ps[seat].riichi) {
			playerDiscard(seat, ps[seat].hand.size() - 1);
			return;
		}
		int kakan = pendingRiichi ? -1 : findKakan(seat);
		if (kakan >= 0 && wall.rinshanRemaining() > 0 && !(defensiveFlow && opponentRiichi)) {
			doKong(seat, "kakan", kakan);
			return;
		}
		// 防守权重按流派：野猪流 0（不防）；一般流/御无双 1.0；魂天流 1.2（略高）；
		// 门清流 1.5（高）；鬼神境全知他家手牌——不主动防守，唯一例外：打得出去的牌"确实是炮"绝不打
		double[] danger = null;
		double dangerWeight = 0;
		boolean onigami = flow == mcr.riichi_arise.game.riichi.AiCharacters.FLOW_ONIGAMI;
		switch (flow) {
			case 1 -> dangerWeight = 0; // 野猪流：不防守
			case 3 -> dangerWeight = 1.0; // 御无双：防守适中
			case 4 -> dangerWeight = 1.2; // 魂天流：防守略高
			case 2 -> dangerWeight = 1.5; // 门清流：防守高
			case mcr.riichi_arise.game.riichi.AiCharacters.FLOW_ONIGAMI -> {
				danger = omniscientDanger(seat);
				dangerWeight = 1.0;
			}
			default -> dangerWeight = 1.0; // 一般流：标准防守
		}
		if (dangerWeight > 0 && !onigami && defendActive(seat)) {
			// 非鬼神境：场上有立直家才生成铳率表
			List<Integer> doraCodes = new ArrayList<>();
			for (int c : wall.revealedDoras())
				doraCodes.add(c);
			List<Integer> riichiSeats = new ArrayList<>();
			List<List<Integer>> rivers = new ArrayList<>();
			List<Integer> kitaCounts = new ArrayList<>(); // 各立直家拔北数（三麻打点修正：拔北多打点高）
			for (int s = 0; s < 4; s++)
				if (s != seat && ps[s].riichi) {
					riichiSeats.add(s);
					rivers.add(ps[s].river);
					kitaCounts.add(st.kita[s]);
				}
			danger = RiichiBot.defenseDanger(riichiSeats, rivers, visible, doraCodes, 27 + st.roundWind,
					dealerSeat(), kitaCounts);
		}
		playerDiscard(seat, RiichiBot.chooseDiscard(ps[seat], visible, buildDora34(),
				level.random::nextDouble, pendingRiichi, danger, dangerWeight, 0));
	}

	/**
	 * 鬼神境铳率表（全知）：不主动防守（不算安全牌、不做终局电报权衡），唯一例外是
	 * "打得出去的牌确实是炮"——他家手牌可见，能直接和出的牌 = 100% 铳率绝不打；
	 * 其余牌铳率 0，评分自然按进攻价值选择。
	 */
	private double[] omniscientDanger(int seat) {
		double[] danger = new double[34];
		// 鬼神境不防守（不做安全牌/终局电报权衡），唯一的例外：打得出去的牌"确实是炮"
		// （他家手牌可见，能直接和出的牌）——这种牌 100 罚分绝不打，其余照常评分自然回落
		for (int s = 0; s < 4; s++) {
			if (s == seat)
				continue;
			for (int i = 0; i < 34; i++) {
				if (danger[i] > 0)
					continue;
				// 索引 → 牌面码：1m..9m=1..9、1p..9p=11..19、1s..9s=21..29、1z..7z=30..36
				int code = i < 27 ? (i / 9) * 10 + i % 9 + 1 : i - 27 + 30;
				List<Integer> test = new ArrayList<>(ps[s].hand);
				test.add(code);
				if (Mahjong4jBridge.isWinnableShape(test, ps[s].melds))
					danger[i] = 100;
			}
		}
		return danger;
	}

	/** 防守启用判定：场上有立直家（除自己）即防 */
	private boolean defendActive(int seat) {
		for (int s = 0; s < 4; s++)
			if (s != seat && ps[s].riichi)
				return true;
		return false;
	}

	/** 全知可见牌 34 数组（自己手牌 + 他家手牌 + 各家牌河/副露；鬼神境用，剩余枚数 = 真实牌山） */
	private int[] buildOmniscient34(int seat) {
		int[] visible = buildVisible34(seat);
		for (int s = 0; s < 4; s++)
			if (s != seat)
				for (int c : ps[s].hand)
					visible[TileEfficiency.codeToIndex(c)]++;
		return visible;
	}

	/** 全场可见牌 34 数组（自己手牌 + 各家牌河 + 各家副露；供 AI 推算剩余枚数） */
	private int[] buildVisible34(int seat) {
		int[] visible = new int[34];
		for (int c : ps[seat].hand)
			visible[TileEfficiency.codeToIndex(c)]++;
		for (int s = 0; s < 4; s++) {
			for (int c : ps[s].river)
				visible[TileEfficiency.codeToIndex(c)]++;
			for (Fuuro f : ps[s].melds)
				for (int c : f.tiles)
					visible[TileEfficiency.codeToIndex(c)]++;
		}
		return visible;
	}

	/** 立直打点估算：对每种打牌选择的每个存活听牌按“立直荣和（点闲家）”试算，取最大支付（打点高时听张少也值得立直） */
	private int estimateRiichiValue(int seat, int[] visible) {
		int[] own = new int[34];
		for (int c : ps[seat].hand)
			own[TileEfficiency.codeToIndex(c)]++;
		List<Integer> doraInd = new ArrayList<>();
		for (int c : wall.revealedDoras())
			doraInd.add(mcr.riichi_arise.game.TileFlag.base(c)); // 透明标志不影响宝牌匹配
		int best = 0;
		List<Integer> hand = ps[seat].hand;
		// 枚举每种打牌（14 张去掉任意一张 = 13 张基牌）——只试“打刚摸牌”会漏掉打别的牌才成立的听牌形
		for (int d = 0; d < hand.size(); d++) {
			int removed = hand.remove(d);
			for (int i = 0; i < 34; i++) {
				if (4 - (visible[i] - own[i]) <= 0)
					continue;
				int code = i < 27 ? (i / 9) * 10 + i % 9 + 1 : i - 27 + 30;
				List<Integer> test = new ArrayList<>(hand);
				test.add(code);
				if (!Mahjong4jBridge.isWinnableShape(test, ps[seat].melds))
					continue;
				var r = Mahjong4jBridge.score(test, ps[seat].melds, seat, dealerSeat(), st.roundWind,
						false, true, false, Mahjong4jBridge.Flags.NONE, doraInd);
				if (r.win())
					best = Math.max(best, r.score().getRon());
				if (best >= 5200)
					break; // 早停：打点门槛只有一档
			}
			hand.add(d, removed);
			if (best >= 5200)
				break;
		}
		return best;
	}

	/** 当前听牌的可和枚数：枚举每种打牌选择，对每张仍“存活”的候选牌试和，取最大活听
	 *  （剩余 4 - 场外可见数，自己手牌不计入场外；只试“打刚摸牌”会漏掉打别的牌才成立的听牌形 → waits=0 → 拖一巡才立） */
	private int countWaits(int seat, int[] visible) {
		int[] own = new int[34];
		for (int c : ps[seat].hand)
			own[TileEfficiency.codeToIndex(c)]++;
		int best = 0;
		List<Integer> hand = ps[seat].hand;
		for (int d = 0; d < hand.size(); d++) {
			int removed = hand.remove(d);
			int waits = 0;
			for (int i = 0; i < 34; i++) {
				int remain = 4 - (visible[i] - own[i]); // 场外可见 = visible - own
				if (remain <= 0)
					continue;
				int code = i < 27 ? (i / 9) * 10 + i % 9 + 1 : i - 27 + 30;
				List<Integer> test = new ArrayList<>(hand);
				test.add(code);
				if (Mahjong4jBridge.isWinnableShape(test, ps[seat].melds))
					waits += remain;
			}
			hand.add(d, removed);
			best = Math.max(best, waits);
		}
		return best;
	}

	/** 当前宝牌（指示牌下一张）34 布尔 */
	private boolean[] buildDora34() {
		boolean[] dora = new boolean[34];
		for (int c : wall.revealedDoras())
			dora[TileEfficiency.codeToIndex(c)] = true;
		return dora;
	}

	/** 听牌等待张记法（流局显示用，如 "37m1z"；无等待张返回 ""） */
	private String waitNotation(List<Integer> hand) {
		java.util.Set<Integer> waitIdx = new java.util.LinkedHashSet<>();
		for (int code = 0; code <= 36; code++) {
			int idx = TileEfficiency.codeToIndex(code);
			int cnt = 0;
			for (int c : hand)
				if (TileEfficiency.codeToIndex(c) == idx)
					cnt++;
			if (cnt >= 4)
				continue; // 该种牌已全可见，不可能等
			List<Integer> h = new ArrayList<>(hand);
			h.add(code);
			if (RiichiBot.shantenOf(h) == -1)
				waitIdx.add(idx);
		}
		StringBuilder sb = new StringBuilder();
		for (int idx : waitIdx) {
			// 34 索引 → code（红五按普通 5 显示）
			int code = idx < 9 ? idx + 1 : idx < 18 ? idx - 8 + 10 : idx < 27 ? idx - 17 + 20 : idx - 27 + 30;
			if (sb.length() > 0)
				sb.append(' ');
			sb.append(MahjongTileNotation.format(List.of(code)));
		}
		return sb.toString();
	}

	/** 管理指令：替换某座位手牌（/murmol mahjong replace）。仅对局中有效；返回 false = 座位/张数非法 */
	public boolean replaceHand(int seat, List<Integer> codes) {
		if (seat < 0 || seat > 3 || codes.isEmpty() || codes.size() > 14)
			return false;
		RiichiPlayer p = ps[seat];
		p.hand.clear();
		p.hand.addAll(codes);
		p.sortHand();
		st.clearSelection();
		if (turn == seat && waitingDiscard) {
			// 该家正待打牌：14 张 = 含刚摸的牌（保留摸牌位渲染），否则视为无摸牌
			boolean hasDrawn = codes.size() % 3 == 2;
			justDrew = hasDrawn;
			st.drawnSeat = hasDrawn ? seat : -1;
			st.turnDeadline = level.getGameTime() + st.turnLimit; // 重置本巡思考时间
		} else {
			st.drawnSeat = -1;
		}
		writeBack();
		FengPanBlock.rerenderTable(level, origin); // 重建交互代理（手牌张数可能变化）
		broadcast(p.name + " 的手牌已被管理员替换为 " + MahjongTileNotation.format(codes));
		return true;
	}

	private int seatOf(String uuid) {
		for (int i = 0; i < 4; i++)
			if (ps[i].uuid.equals(uuid))
				return i;
		return -1;
	}

	/** 写回 RichiTableState（渲染层唯一数据源）并按观看者过滤广播整桌快照 */
	private void writeBack() {
		for (int i = 0; i < pc(); i++) {
			st.hands[i] = ps[i].handNotation();
			// 拔北不算副露：只更新 st.kita（快照独立字段同步），不掺进副露串
			st.melds[i] = ps[i].meldNotation();
			st.rivers[i] = ps[i].riverNotation();
			st.points[i] = ps[i].points;
		}
		// 三麻空座（座位 3）：手牌/副露/牌河清空串，防止残留上一局渲染
		if (pc() == 3) {
			st.hands[3] = "";
			st.melds[3] = "";
			st.rivers[3] = "";
		}
		st.wall = MahjongTileNotation.format(wall.remainingCodes());
		st.doraWall = MahjongTileNotation.format(wall.doraStackTiles());
		st.revealedIndicators = wall.revealedIndicatorCount();
		RichiTableSync.broadcast(level, origin);
		mcr.riichi_arise.net.RichiNetManager.pushTableSnapshot(level, origin, st); // 内网订阅者快照同步
	}

	private static int parseInt(String s, int def) {
		try {
			return Integer.parseInt(s.trim());
		} catch (Exception e) {
			return def;
		}
	}

	private void messageTable(String key, Object... args) {
		FengPanBlock.messageTable(level, st, key, args);
	}

	/** 天凤式角色语音：对局事件 → 通知桌周附近客户端播报某座玩家的语音（声音文件由客户端按各座语音自行播） */
	private void voiceEvent(int seat, mcr.riichi_arise.voice.VoiceEvents ev) {
		mcr.riichi_arise.network.VoicePayloads.sendVoice(level, origin, seat, ev.code());
	}

	/**
	 * 流局听牌/未听语音：每位参战玩家只听到自己那家的"听牌/未听"语音（不同结果不交叉播报）；
	 * 未入座的观战者则按距离取最近一家（角色或玩家），播报该家的结果语音。
	 */
	private void announceDrawVoices(java.util.List<Integer> tenpai) {
		for (int seat = 0; seat < pc(); seat++) {
			ServerPlayer p = st.playerOf(level, seat);
			if (p != null)
				mcr.riichi_arise.network.VoicePayloads.sendVoiceTo(p, origin, seat, drawVoiceCode(tenpai, seat));
		}
		mcr.riichi_arise.render.TableLayout.Geom geom = mcr.riichi_arise.render.TableLayout.of(level, origin);
		for (ServerPlayer p : level.players()) {
			if (p.level() != level || seatOf(p.getUUID().toString()) >= 0)
				continue; // 参战者已按自己的结果单独播报
			if (p.distanceToSqr(geom.center()) > 24 * 24)
				continue;
			int nearest = -1;
			double best = Double.MAX_VALUE;
			for (int seat = 0; seat < pc(); seat++) {
				double d = p.distanceToSqr(geom.rows()[seat]);
				if (d < best) {
					best = d;
					nearest = seat;
				}
			}
			if (nearest >= 0)
				mcr.riichi_arise.network.VoicePayloads.sendVoiceTo(p, origin, nearest,
						drawVoiceCode(tenpai, nearest));
		}
	}

	/** 流局语音事件码：听牌家 TENPAI / 未听家 NOTEN */
	private static int drawVoiceCode(java.util.List<Integer> tenpai, int seat) {
		return (tenpai.contains(seat) ? mcr.riichi_arise.voice.VoiceEvents.TENPAI
				: mcr.riichi_arise.voice.VoiceEvents.NOTEN).code();
	}

	/** 自风/场风方位后缀（索引 0..3 = 東南西北，与 mjsdb 报番音频文件对应） */
	private static final String[] WIND_KEYS = { "TON", "NAN", "SHAA", "PEI" };

	/**
	 * 报番键列表（S→C 发给客户端，客户端 YakuVoices 解析）：
	 * <ol><li>役种英文名，其中自风/场风替换为带具体方位的 JIKAZE_TON / BAKAZE_NAN 等（报牌名）</li>
	 * <li>宝牌计数：表宝牌、红宝牌、拔北（三麻）、里宝牌按 DORA/RED/KITA/URA + 数量（如 DORA3、URA13），
	 * 按数量分开报，顺序为表 → 红 → 拔北 → 里</li></ol>
	 */
	private List<String> buildFanKeys(Mahjong4jBridge.WinResult r, int seat) {
		List<String> keys = new ArrayList<>();
		int selfWind = (seat - dealerSeat() + 4) % 4;
		for (String n : orderFanYaku(r.yakuNames())) {
			if (n.equals("JIKAZE"))
				keys.add("JIKAZE_" + WIND_KEYS[selfWind]);
			else if (n.equals("BAKAZE"))
				keys.add("BAKAZE_" + WIND_KEYS[st.roundWind]);
			else
				keys.add(n);
		}
		appendDoraKeys(keys, "DORA", r.doraHits().size()); // 表宝牌
		appendDoraKeys(keys, "RED", r.redHits().size()); // 红宝牌
		appendDoraKeys(keys, "KITA", ps[seat].kitaCount); // 拔北（三麻）：表/红之后、里之前，宝牌计数通道
		appendDoraKeys(keys, "URA", r.uraHits().size()); // 里宝牌
		return keys;
	}

	/** 追加宝牌计数键：0 张不报；1 张报牌名；2-12 报数量；13+ 报"一大堆" */
	private static void appendDoraKeys(List<String> keys, String base, int count) {
		if (count > 0)
			keys.add(count == 1 ? base : base + Math.min(count, 13));
	}

	/** 聊天框向桌心 16 格内所有玩家播报（副露/立直/和牌/流局等每次操作实时通知；个人提示走动作栏不受影响）。
	 *  观战者是否收到桌况聊天跟随客户端配置"观战显示牌局内容"（关闭时观战者收不到牌局聊天，参战四家始终可见） */
	private void broadcast(String text) {
		Vec3 c = FengPanBlock.tableCenter(level, origin);
		var box = new net.minecraft.world.phys.AABB(c.x - 16, c.y - 16, c.z - 16,
				c.x + 16, c.y + 16, c.z + 16);
		for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, box))
			if (isGamePlayer(p) || spectatorShowEnabled())
				p.sendSystemMessage(Component.literal("§6<麻雀>§r " + text));
	}

	/** 该玩家是否为本桌参战雀士（4 家中任一家） */
	private boolean isGamePlayer(ServerPlayer p) {
		String u = p.getUUID().toString();
		for (int s = 0; s < 4; s++)
			if (u.equals(st.players[s]))
				return true;
		return false;
	}

	/** 观战显示牌局内容（mod 客户端配置，默认开）；专用服务器未加载客户端配置时按默认启用 */
	private static boolean spectatorShowEnabled() {
		try {
			return mcr.riichi_arise.RichiConfig.SPECTATOR_SHOW_TABLE.get();
		} catch (RuntimeException e) {
			return true;
		}
	}

	/** 配置开启时，向桌心 8 格内观战者（非参战玩家）执行 action（和牌/结算界面同步用） */
	private void sendToSpectators(java.util.function.Consumer<ServerPlayer> action) {
		if (!spectatorShowEnabled())
			return;
		Vec3 c = FengPanBlock.tableCenter(level, origin);
		var box = new net.minecraft.world.phys.AABB(c.x - 8, c.y - 8, c.z - 8, c.x + 8, c.y + 8, c.z + 8);
		for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, box))
			if (!isGamePlayer(p))
				action.accept(p);
	}

	/** 牌面显示名（播报用） */
	private static String tileName(int code) {
		return mcr.riichi_arise.MahjongTileItem.create(code).getHoverName().getString();
	}
}

