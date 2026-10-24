package mcr.riichi_arise.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.channel.Channel;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import mcr.riichi_arise.game.MahjongLobby;
import mcr.riichi_arise.game.MahjongTileNotation;
import mcr.riichi_arise.game.RichiTableManager;
import mcr.riichi_arise.game.RichiTableState;
import mcr.riichi_arise.game.riichi.RiichiGame;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内网接入的核心业务（全部在主线程执行）：
 * <ul>
 *   <li>协议分发：handle() 处理来自 IO 线程投递的消息；onDisconnect() 清理会话。</li>
 *   <li>快照构建：把 RichiTableState 序列化为 JSON，推送给订阅该桌的会话（外部玩家本人完整手牌，其余按 hideOthers/openHand）。</li>
 *   <li>外部玩家参赛：join 走 {@link MahjongLobby} 队列，操作经 {@link RiichiGame#externalAction} 执行。</li>
 *   <li>对外推送：被 {@link RiichiGame} 在回合/响应窗口/状态写回时调用。</li>
 * </ul>
 */
public final class RichiNetManager {
	private RichiNetManager() {
	}

	private static MinecraftServer server;
	private static RichiNetServer netServer;

	/** 外部玩家 uuid → 会话（主线程维护） */
	private static final Map<String, RichiNetSession> EXT_SESSIONS = new ConcurrentHashMap<>();

	private static final String NAME_DEFAULT = "外部玩家";

	// ==================================================================
	// 生命周期（命令 /richi net 调用，服务端主线程）
	// ==================================================================

	public static boolean start(MinecraftServer mcServer, int port, String bind) {
		if (netServer != null && netServer.isRunning())
			return false;
		server = mcServer;
		RichiNetServer s = new RichiNetServer(mcServer, port, bind);
		if (!s.start())
			return false;
		netServer = s;
		return true;
	}

	public static void stop() {
		if (netServer != null) {
			netServer.stop();
			netServer = null;
		}
		for (RichiNetSession s : new ArrayList<>(EXT_SESSIONS.values()))
			leaveSession(s);
		EXT_SESSIONS.clear();
	}

	public static boolean isRunning() {
		return netServer != null && netServer.isRunning();
	}

	public static int port() {
		return netServer == null ? RichiNetConfig.PORT : netServer.port();
	}

	public static String bind() {
		return netServer == null ? RichiNetConfig.BIND : netServer.bind();
	}

	public static int connectionCount() {
		return netServer == null ? 0 : netServer.connectionCount();
	}

	/** 服务端停止时清理（ServerStoppingEvent） */
	public static void onServerStopping() {
		stop();
	}

	// ==================================================================
	// IO 回调（经 server.execute 已在主线程）
	// ==================================================================

	public static void handle(RichiNetSession session, String line) {
		if (session == null || !session.isActive() || line == null || line.isEmpty())
			return;
		JsonObject msg;
		try {
			msg = JsonParser.parseString(line).getAsJsonObject();
		} catch (Exception e) {
			sendError(session, "非法 JSON: " + e.getMessage());
			return;
		}
		String type = RichiNetProtocol.str(msg, "type");
		if (type == null) {
			sendError(session, "缺少 type 字段");
			return;
		}
		try {
			switch (type) {
				case RichiNetProtocol.C_HELLO -> onHello(session, msg);
				case RichiNetProtocol.C_PING -> session.send(RichiNetProtocol.msg(RichiNetProtocol.S_PONG));
				case RichiNetProtocol.C_LIST_TABLES -> onListTables(session);
				case RichiNetProtocol.C_SUBSCRIBE -> onSubscribe(session, msg);
				case RichiNetProtocol.C_UNSUBSCRIBE -> onUnsubscribe(session, msg);
				case RichiNetProtocol.C_JOIN -> onJoin(session, msg);
				case RichiNetProtocol.C_LEAVE -> {
					leaveSession(session);
					ack(session, true, "已离开牌局");
				}
				case RichiNetProtocol.C_DISCARD -> onDiscard(session, msg);
				case RichiNetProtocol.C_ACTION -> onAction(session, msg);
				case RichiNetProtocol.C_QUIT -> {
					leaveSession(session);
					sendError(session, "bye");
					session.close();
				}
				default -> sendError(session, "未知消息类型: " + type);
			}
		} catch (Exception e) {
			mcr.riichi_arise.RichiiAriseMod.LOGGER.error("[RichiNet] 处理消息失败 type={}", type, e);
			sendError(session, "处理失败: " + e.getMessage());
		}
	}

	public static void onDisconnect(RichiNetSession session) {
		if (session == null)
			return;
		leaveSession(session); // 外部玩家离开牌局（对局中由 AI 接管）
		if (session.uuid != null)
			EXT_SESSIONS.remove(session.uuid);
		if (netServer != null)
			netServer.sessions.remove(session.channel);
	}

	// ==================================================================
	// 握手 / 列表 / 订阅
	// ==================================================================

	private static void onHello(RichiNetSession session, JsonObject msg) {
		if (RichiNetConfig.TOKEN != null && !RichiNetConfig.TOKEN.isEmpty()) {
			String tk = RichiNetProtocol.str(msg, "token");
			if (tk == null || !tk.equals(RichiNetConfig.TOKEN)) {
				sendError(session, "token 校验失败");
				session.close();
				return;
			}
		}
		if (session.uuid == null)
			session.uuid = UUID.randomUUID().toString();
		session.name = RichiNetProtocol.str(msg, "name");
		if (session.name == null || session.name.isEmpty())
			session.name = NAME_DEFAULT;
		session.greeted = true;
		EXT_SESSIONS.put(session.uuid, session);
		JsonObject w = RichiNetProtocol.msg(RichiNetProtocol.S_WELCOME);
		w.addProperty("uuid", session.uuid);
		w.addProperty("name", session.name);
		w.addProperty("server", "riichi_arise");
		w.addProperty("version", "1.0");
		w.addProperty("port", port());
		session.send(w);
	}

	private static void onListTables(RichiNetSession session) {
		JsonArray arr = new JsonArray();
		if (server != null) {
			for (ServerLevel lvl : server.getAllLevels()) {
				for (BlockPos origin : RichiTableManager.originsIn(lvl)) {
					RichiTableState st = RichiTableManager.get(origin);
					if (st == null)
						continue;
					JsonObject t = new JsonObject();
					t.addProperty("table", key(origin));
					t.addProperty("phase", st.phase);
					t.addProperty("game_type", st.gameType);
					JsonArray names = new JsonArray();
					for (int s = 0; s < 4; s++) {
						String n = st.players[s] == null ? "" : seatName(lvl, st, s);
						names.add(n);
					}
					t.add("players", names);
					t.addProperty("open_hand", st.openHand);
					arr.add(t);
				}
			}
		}
		JsonObject r = RichiNetProtocol.msg(RichiNetProtocol.S_TABLES);
		r.add("tables", arr);
		session.send(r);
	}

	private static void onSubscribe(RichiNetSession session, JsonObject msg) {
		String key = RichiNetProtocol.str(msg, "table");
		TableRef ref = key == null ? null : findTable(key);
		if (ref == null) {
			sendError(session, "未找到牌桌: " + key);
			return;
		}
		session.subscribed.add(ref.key());
		session.hideOthers = RichiNetProtocol.optBool(msg, "hide_others", false);
		// 订阅后立即推一帧当前快照
		session.send(buildSnapshot(ref, session.uuid, session.hideOthers));
	}

	private static void onUnsubscribe(RichiNetSession session, JsonObject msg) {
		String key = RichiNetProtocol.str(msg, "table");
		if (key != null)
			session.subscribed.remove(key);
		ack(session, true, "已退订");
	}

	// ==================================================================
	// 外部玩家参赛
	// ==================================================================

	private static void onJoin(RichiNetSession session, JsonObject msg) {
		if (!session.greeted || session.uuid == null) {
			sendError(session, "请先发送 hello");
			return;
		}
		String key = RichiNetProtocol.str(msg, "table");
		TableRef ref = key == null ? null : findTable(key);
		if (ref == null) {
			sendError(session, "未找到牌桌: " + key);
			return;
		}
		if (ref.st().phase == RichiTableState.PHASE_PLAYING) {
			sendError(session, "该牌桌对局进行中，等待阶段才能加入");
			return;
		}
		// 已在其它桌：先离开
		if (session.joinedTable != null)
			leaveSession(session);
		String name = RichiNetProtocol.str(msg, "name");
		if (name == null || name.isEmpty())
			name = session.name == null ? NAME_DEFAULT : session.name;
		if (!MahjongLobby.externalJoinTable(ref.level(), ref.origin(), session.uuid, name)) {
			sendError(session, "加入队列失败（队列已满或不可加入）");
			return;
		}
		session.joinedTable = ref.key();
		session.subscribed.add(ref.key());
		ack(session, true, "已加入 " + key + " 等候队列");
		session.send(buildSnapshot(ref, session.uuid, false));
	}

	private static void leaveSession(RichiNetSession session) {
		if (session == null || session.joinedTable == null)
			return;
		String key = session.joinedTable;
		session.joinedTable = null;
		session.seat = -1;
		TableRef ref = findTable(key);
		if (ref != null && session.uuid != null)
			MahjongLobby.externalLeaveTable(ref.level(), ref.origin(), session.uuid);
	}

	// ==================================================================
	// 外部玩家操作（打牌 / 鸣牌 / 立直 / 自摸等）
	// ==================================================================

	private static void onDiscard(RichiNetSession session, JsonObject msg) {
		if (!requireJoined(session))
			return;
		TableRef ref = findTable(session.joinedTable);
		RiichiGame game = ref == null ? null : RichiTableManager.getGame(ref.origin());
		if (game == null) {
			sendError(session, "该桌没有进行中的对局");
			return;
		}
		// 优先用手牌下标，否则按牌面记法（如 "5m"）解析
		int index = RichiNetProtocol.optInt(msg, "index", -1);
		if (index < 0) {
			String tile = RichiNetProtocol.str(msg, "tile");
			if (tile == null || tile.isEmpty()) {
				sendError(session, "discard 需要 index 或 tile 字段");
				return;
			}
			List<Integer> codes;
			try {
				codes = MahjongTileNotation.parse(tile);
			} catch (Exception e) {
				sendError(session, "牌面记法无效: " + tile);
				return;
			}
			if (codes.isEmpty()) {
				sendError(session, "牌面记法为空");
				return;
			}
			int seat = game.findSeat(session.uuid);
			if (seat < 0) {
				sendError(session, "你不在该对局中");
				return;
			}
			java.util.List<Integer> hand = game.player(seat).hand;
			index = -1;
			for (int i = 0; i < hand.size(); i++)
				if (hand.get(i) == codes.get(0)) {
					index = i;
					break;
				}
			if (index < 0) {
				sendError(session, "手牌中没有 " + tile);
				return;
			}
		}
		exec(session, "discard", String.valueOf(index));
	}

	private static void onAction(RichiNetSession session, JsonObject msg) {
		if (!requireJoined(session))
			return;
		String action = RichiNetProtocol.str(msg, "action");
		if (action == null || action.isEmpty()) {
			sendError(session, "缺少 action 字段");
			return;
		}
		// data 透传：chi 用组合索引，ankan/kakan 用手牌下标
		String data = RichiNetProtocol.str(msg, "data");
		exec(session, action, data == null ? "" : data);
	}

	private static void exec(RichiNetSession session, String action, String data) {
		TableRef ref = findTable(session.joinedTable);
		RiichiGame game = ref == null ? null : RichiTableManager.getGame(ref.origin());
		if (game == null) {
			sendError(session, "该桌没有进行中的对局");
			return;
		}
		boolean ok = game.externalAction(session.uuid, action, data);
		ack(session, ok, ok ? null : "动作无效（时机/选项不符）");
	}

	private static boolean requireJoined(RichiNetSession session) {
		if (!session.greeted || session.uuid == null) {
			sendError(session, "请先发送 hello");
			return false;
		}
		if (session.joinedTable == null) {
			sendError(session, "尚未加入牌局，请先 join");
			return false;
		}
		return true;
	}

	// ==================================================================
	// 服务端 → 客户端推送（RiichiGame 在回合/窗口/写回时调用，主线程）
	// ==================================================================

	/** 桌状态写回后推送快照给订阅者（writeBack 挂钩） */
	public static void pushTableSnapshot(ServerLevel level, BlockPos origin, RichiTableState st) {
		if (netServer == null || !netServer.isRunning())
			return;
		String key = key(origin);
		for (RichiNetSession s : new ArrayList<>(netServer.sessions.values())) {
			if (!s.isActive() || !s.subscribed.contains(key))
				continue;
			s.send(buildSnapshot(new TableRef(level, origin, st), s.uuid, s.hideOthers));
		}
	}

	/** 轮到外部玩家：推送其手牌与可选动作 */
	public static void pushYourTurn(RiichiGame game, int seat, java.util.List<String> options) {
		RichiNetSession s = sessionForSeat(game, seat);
		if (s == null)
			return;
		JsonObject m = RichiNetProtocol.msg(RichiNetProtocol.S_YOUR_TURN);
		fillTableSeat(game, m, seat);
		m.add("hand", codes(game.player(seat).hand));
		m.add("options", toArray(options));
		m.addProperty("riichi", game.player(seat).riichi);
		s.send(m);
	}

	/** 响应窗口：外部玩家可鸣牌/荣和 */
	public static void pushClaimOptions(RiichiGame game, int seat, java.util.List<String> options) {
		RichiNetSession s = sessionForSeat(game, seat);
		if (s == null)
			return;
		JsonObject m = RichiNetProtocol.msg(RichiNetProtocol.S_CLAIM_OPTIONS);
		fillTableSeat(game, m, seat);
		m.add("options", toArray(options));
		m.addProperty("discarder", game.claimDiscarderSeat());
		s.send(m);
	}

	/** 立直宣言待选：请外部玩家选择打出的牌（随后发 discard） */
	public static void pushRiichiPick(RiichiGame game, int seat) {
		RichiNetSession s = sessionForSeat(game, seat);
		if (s == null)
			return;
		JsonObject m = RichiNetProtocol.msg(RichiNetProtocol.S_RIICHI_PICK);
		fillTableSeat(game, m, seat);
		m.add("hand", codes(game.player(seat).hand));
		s.send(m);
	}

	private static RichiNetSession sessionForSeat(RiichiGame game, int seat) {
		String uuid = game.player(seat).uuid;
		if (!game.tableState().externalUuids.contains(uuid))
			return null;
		RichiNetSession s = EXT_SESSIONS.get(uuid);
		return s != null && s.isActive() ? s : null;
	}

	private static void fillTableSeat(RiichiGame game, JsonObject m, int seat) {
		m.addProperty("table", key(game.origin()));
		m.addProperty("seat", seat);
		m.addProperty("wind", new String[] { "E", "S", "W", "N" }[Math.floorMod(seat - game.gameDealerSeat() + 4, 4)]);
	}

	// ==================================================================
	// 快照构建
	// ==================================================================

	private static JsonObject buildSnapshot(TableRef ref, String viewerUuid, boolean hideOthers) {
		RichiTableState st = ref.st();
		JsonObject o = RichiNetProtocol.msg(RichiNetProtocol.S_SNAPSHOT);
		o.addProperty("table", ref.key());
		o.addProperty("phase", st.phase);
		o.addProperty("round", st.round);
		o.addProperty("round_wind", st.roundWind);
		o.addProperty("honba", st.honba);
		o.addProperty("turn_seat", st.turnSeat);
		o.addProperty("open_hand", st.openHand);
		o.addProperty("game_type", st.gameType);
		o.addProperty("revealed_indicators", st.revealedIndicators);
		// 每家视角：本人座位 / openHand / 订阅者不隐藏 → 完整手牌，否则隐藏他家
		boolean viewerIsPlayer = viewerUuid != null && st.externalUuids.contains(viewerUuid);
		JsonArray hands = new JsonArray();
		for (int s = 0; s < 4; s++) {
			boolean full = (viewerIsPlayer && viewerUuid.equals(st.players[s])) || st.openHand || !hideOthers;
			String h = st.hands[s];
			if (!full && (h == null || h.isEmpty()))
				h = "";
			else if (!full)
				h = hideNotation(h);
			hands.add(h);
		}
		o.add("hands", hands);
		JsonArray rivers = new JsonArray();
		for (int s = 0; s < 4; s++)
			rivers.add(st.rivers[s] == null ? "" : st.rivers[s]);
		o.add("rivers", rivers);
		JsonArray melds = new JsonArray();
		for (int s = 0; s < 4; s++)
			melds.add(st.melds[s] == null ? "" : st.melds[s]);
		o.add("melds", melds);
		o.addProperty("dora_wall", st.doraWall == null ? "" : st.doraWall);
		JsonArray points = new JsonArray();
		for (int p : st.points)
			points.add(p);
		o.add("points", points);
		JsonArray players = new JsonArray();
		for (int s = 0; s < 4; s++)
			players.add(st.players[s] == null ? "" : seatName(ref.level(), st, s));
		o.add("players", players);
		JsonArray aiSeat = new JsonArray();
		for (int s = 0; s < 4; s++)
			aiSeat.add(st.aiSeat[s]);
		o.add("ai_seat", aiSeat);
		JsonArray riichi = new JsonArray();
		for (int s = 0; s < 4; s++)
			riichi.add(st.riichiSticks[s]);
		o.add("riichi_sticks", riichi);
		JsonArray exposed = new JsonArray();
		for (int s = 0; s < 4; s++)
			exposed.add(st.handsExposed[s]);
		o.add("hands_exposed", exposed);
		o.addProperty("banner", st.banner == null ? "" : st.banner);
		return o;
	}

	/** 隐藏手牌：保留张数但牌面替换为 '?'（占位，外部程序按长度即可知张数） */
	private static String hideNotation(String hand) {
		int count = hand.length() == 0 ? 0 : hand.length() - countNonDigit(hand);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < count; i++)
			sb.append('?');
		return sb.toString();
	}

	private static int countNonDigit(String s) {
		int n = 0;
		for (char c : s.toCharArray())
			if (c < '0' || c > '9')
				n++;
		return n;
	}

	// ==================================================================
	// 辅助
	// ==================================================================

	private static JsonArray codes(java.util.List<Integer> list) {
		JsonArray a = new JsonArray();
		for (int c : list)
			a.add(c);
		return a;
	}

	private static JsonArray toArray(java.util.List<String> list) {
		JsonArray a = new JsonArray();
		for (String s : list)
			a.add(s);
		return a;
	}

	private static void ack(RichiNetSession session, boolean ok, String msg) {
		JsonObject m = RichiNetProtocol.msg(RichiNetProtocol.S_ACK);
		m.addProperty("ok", ok);
		if (msg != null)
			m.addProperty("msg", msg);
		session.send(m);
	}

	private static void sendError(RichiNetSession session, String text) {
		JsonObject m = RichiNetProtocol.msg(RichiNetProtocol.S_ERROR);
		m.addProperty("msg", text);
		session.send(m);
	}

	private static String seatName(ServerLevel level, RichiTableState st, int seat) {
		String uuid = st.players[seat];
		if (uuid == null)
			return "";
		if (st.externalUuids.contains(uuid))
			return st.externalNames.getOrDefault(uuid, uuid.length() > 8 ? uuid.substring(0, 8) : uuid);
		if (st.npcUuids.contains(uuid)) {
			net.minecraft.world.entity.Entity e = level.getEntity(java.util.UUID.fromString(uuid));
			mcr.riichi_arise.game.MahjongParticipant p = e == null ? null
					: mcr.riichi_arise.game.MahjongParticipantRegistry.of(e);
			return p != null ? p.participantName() : "NPC";
		}
		net.minecraft.server.level.ServerPlayer p = level.getServer().getPlayerList().getPlayer(java.util.UUID.fromString(uuid));
		return p != null ? p.getGameProfile().getName() : (uuid.length() > 8 ? uuid.substring(0, 8) : uuid);
	}

	/** 桌 key："x,y,z" */
	public static String key(BlockPos origin) {
		return origin.getX() + "," + origin.getY() + "," + origin.getZ();
	}

	record TableRef(ServerLevel level, BlockPos origin, RichiTableState st) {
		String key() {
			return RichiNetManager.key(origin);
		}
	}

	/** 按 key 找桌（含其所在维度），找不到返回 null */
	private static TableRef findTable(String key) {
		if (server == null || key == null)
			return null;
		BlockPos origin = parseKey(key);
		if (origin == null)
			return null;
		RichiTableState st = RichiTableManager.get(origin);
		if (st == null)
			return null;
		for (ServerLevel lvl : server.getAllLevels()) {
			if (RichiTableManager.originsIn(lvl).contains(origin))
				return new TableRef(lvl, origin, st);
		}
		return null;
	}

	private static BlockPos parseKey(String key) {
		String[] parts = key.split(",");
		if (parts.length != 3)
			return null;
		try {
			return new BlockPos(Integer.parseInt(parts[0].trim()),
					Integer.parseInt(parts[1].trim()),
					Integer.parseInt(parts[2].trim()));
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
