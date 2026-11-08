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

	/** 桌 key → 发起/订阅该桌雀魂连接的会话 UUID（控制面板"有连接"判定） */
	private static final Map<String, String> TABLE_LINKS = new ConcurrentHashMap<>();

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
		TABLE_LINKS.clear();
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

	// ==================================================================
	// 外部接管（雀魂连接控制面板）
	// ==================================================================

	/** 该桌当前是否有外部连接：任一在线会话加入/订阅了该桌 */
	public static boolean linked(BlockPos origin) {
		if (netServer == null || !netServer.isRunning())
			return false;
		String key = key(origin);
		String owner = TABLE_LINKS.get(key);
		if (owner != null && isLive(owner))
			return true;
		for (RichiNetSession s : netServer.sessions.values())
			if (s.isActive() && (key.equals(s.joinedTable) || s.subscribed.contains(key)))
				return true;
		return false;
	}

	/**
	 * 建立雀魂连接并接管该桌：确保内网端口运行 → 置接管标记 → 若对局中则接管一个可投影座位
	 * （空位 / 未预约 AI 位），供桥接器直接 join。
	 *
	 * @return 描述结果（成功/失败原因），同时置位接管标记时返回 null 并在调用侧广播
	 */
	public static String takeover(MinecraftServer mcServer, BlockPos origin, String source) {
		RichiTableState st = RichiTableManager.get(origin);
		if (st == null)
			return "该位置没有牌桌";
		if (!isRunning() && !start(mcServer, RichiNetConfig.PORT, RichiNetConfig.BIND))
			return "内网端口启动失败（可能被占用）";
		st.externalTakeover = true;
		st.takeoverSource = source == null ? "" : source;
		RichiTableManager.markDirty(origin);
		return null;
	}

	/** 关闭外部接管（恢复该桌常规交互）：把已加入该桌的外部会话请出，座位归还本地 */
	public static void release(BlockPos origin) {
		RichiTableState st = RichiTableManager.get(origin);
		if (st == null)
			return;
		st.externalTakeover = false;
		st.takeoverSource = "";
		String key = key(origin);
		TABLE_LINKS.remove(key);
		if (netServer != null) {
			for (RichiNetSession s : new ArrayList<>(netServer.sessions.values())) {
				if (!key.equals(s.joinedTable))
					continue;
				leaveSession(s);
				JsonObject notice = new JsonObject();
				notice.addProperty("type", "released");
				notice.addProperty("table", key);
				s.send(notice);
			}
		}
		RichiTableManager.markDirty(origin);
	}

	/** 桌状态摘要（控制面板展示） */
	public static RichiTableState.NetStatus status(BlockPos origin) {
		RichiTableState st = RichiTableManager.get(origin);
		return new RichiTableState.NetStatus(String.valueOf(port()), isRunning(), connectionCount(),
				linked(origin), st != null && st.externalTakeover,
				st == null || st.takeoverSource == null ? "" : st.takeoverSource,
				st == null ? "unknown" : phaseName(st.phase));
	}

	private static String phaseName(int phase) {
		return switch (phase) {
			case RichiTableState.PHASE_PLAYING -> "playing";
			case RichiTableState.PHASE_FINISHED -> "finished";
			default -> "waiting";
		};
	}

	private static boolean isLive(String uuid) {
		RichiNetSession s = uuid == null ? null : EXT_SESSIONS.get(uuid);
		return s != null && s.isActive();
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
				case RichiNetProtocol.C_MIRROR -> onMirror(session, msg);
				case RichiNetProtocol.C_MIRROR_TABLE -> onMirrorAll(session, msg);
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
		// 已在其它桌：先离开
		if (session.joinedTable != null)
			leaveSession(session);
		// 桥接器声明的事件来源：接管该桌（控制面板可据此显示"由雀魂连接接管"）
		String source = RichiNetProtocol.str(msg, "source");
		if (source != null && !source.isEmpty()) {
			ref.st().externalTakeover = true;
			ref.st().takeoverSource = source;
			RichiTableManager.markDirty(ref.origin());
		}
		TABLE_LINKS.put(ref.key(), session.uuid);
		boolean mirror = RichiNetProtocol.optBool(msg, "mirror", false);
		// 整桌投影：四家全部由外部快照驱动（雀魂等外部对局转播），不占用某一席做本地 AI 推进
		boolean mirrorAll = RichiNetProtocol.optBool(msg, "all", false);
		if (mirrorAll) {
			if (!MahjongLobby.externalJoinAll(ref.level(), ref.origin(), session.uuid, nameOf(msg, session))) {
				sendError(session, "整桌接管失败（该桌不在对局中）");
				return;
			}
			session.joinedTable = ref.key();
			session.subscribed.add(ref.key());
			ack(session, true, "已整桌接管进行中的对局 " + key, 0);
			return;
		}
		if (ref.st().phase == RichiTableState.PHASE_PLAYING) {
			// 镜面接入允许中途加入进行中的对局（接管空位/现有座位做投影）
			if (!mirror) {
				sendError(session, "该牌桌对局进行中，等待阶段才能加入");
				return;
			}
			RichiTableState st = ref.st();
			if (st.externalUuids.contains(session.uuid) || st.queue.contains(session.uuid)) {
				session.joinedTable = ref.key();
				session.subscribed.add(ref.key());
				ack(session, true, "已接入进行中的对局 " + key, seatOf(st, session.uuid));
				session.send(buildSnapshot(ref, session.uuid, false));
				return;
			}
			if (!MahjongLobby.externalJoinPlaying(ref.level(), ref.origin(), session.uuid,
					nameOf(msg, session))) {
				sendError(session, "中途接入失败（对局没有可接管的空位）");
				return;
			}
			session.joinedTable = ref.key();
			session.subscribed.add(ref.key());
			ack(session, true, "已接管进行中的对局 " + key, seatOf(st, session.uuid));
			session.send(buildSnapshot(ref, session.uuid, false));
			return;
		}
		String name = nameOf(msg, session);
		if (!MahjongLobby.externalJoinTable(ref.level(), ref.origin(), session.uuid, name)) {
			sendError(session, "加入队列失败（队列已满或不可加入）");
			return;
		}
		session.joinedTable = ref.key();
		session.subscribed.add(ref.key());
		ack(session, true, "已加入 " + key + " 等候队列");
		session.send(buildSnapshot(ref, session.uuid, false));
	}

	/** 会话在该桌的座位（-1 = 尚未入座） */
	private static int seatOf(RichiTableState st, String uuid) {
		for (int s = 0; s < 4; s++)
			if (uuid.equals(st.players[s]))
				return s;
		return -1;
	}

	/** join 显示名：消息 name → hello name → 默认名 */
	private static String nameOf(JsonObject msg, RichiNetSession session) {
		String name = RichiNetProtocol.str(msg, "name");
		if (name == null || name.isEmpty())
			name = session.name == null ? NAME_DEFAULT : session.name;
		return name;
	}

	private static void leaveSession(RichiNetSession session) {
		if (session == null || session.joinedTable == null)
			return;
		String key = session.joinedTable;
		session.joinedTable = null;
		session.seat = -1;
		if (session.uuid != null && session.uuid.equals(TABLE_LINKS.get(key)))
			TABLE_LINKS.remove(key);
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
		// 拔北（三麻）：无参动作，data 置空走 externalAction → applyAction 的 case "kita"
		// （回合选项 your_turn 推送的 options 含 "kita" 时，外部程序回发本动作即可）
		if (RichiNetProtocol.A_KITA.equals(action)) {
			exec(session, action, "");
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
	// 镜面接入（外部牌谱驱动牌桌）
	// ==================================================================

	/**
	 * 整桌投影：用外部对局的完整快照一次性覆盖四家（手牌/牌河/副露/拔北/立直/点数）与局目。
	 * <p>载荷：{@code {"table","seats":[{hand,river,melds,kita,riichi,points,riichi_river_idx}×4],
	 * "drawn_seat","turn_seat","round","round_wind","honba","carried","banner","hands_exposed","points",
	 * "wall","dora"}}。未给的字段沿用牌桌当前值。
	 */
	private static void onMirrorAll(RichiNetSession session, JsonObject msg) {
		if (!requireJoined(session))
			return;
		TableRef ref = findTable(session.joinedTable);
		RiichiGame game = ref == null ? null : RichiTableManager.getGame(ref.origin());
		if (game == null) {
			sendError(session, "该桌没有进行中的对局");
			return;
		}
		List<JsonObject> seats = new ArrayList<>();
		if (msg.has("seats") && msg.get("seats").isJsonArray()) {
			for (var e : msg.getAsJsonArray("seats"))
				seats.add(e.isJsonObject() ? e.getAsJsonObject() : new JsonObject());
		}
		int[] exposed = null;
		if (msg.has("hands_exposed") && msg.get("hands_exposed").isJsonArray())
			exposed = intArray(msg.getAsJsonArray("hands_exposed"), -1);
		int[] points = null;
		if (msg.has("points") && msg.get("points").isJsonArray())
			points = intArray(msg.getAsJsonArray("points"), Integer.MIN_VALUE);
		String[] names = null;
		if (msg.has("names") && msg.get("names").isJsonArray()) {
			var arr = msg.getAsJsonArray("names");
			names = new String[arr.size()];
			for (int i = 0; i < names.length; i++) {
				var e = arr.get(i);
				names[i] = e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
			}
		}
		game.mirrorTable(seats,
				RichiNetProtocol.optInt(msg, "drawn_seat", -1),
				RichiNetProtocol.optInt(msg, "turn_seat", -1),
				RichiNetProtocol.str(msg, "banner"),
				RichiNetProtocol.optInt(msg, "round", -1),
				RichiNetProtocol.optInt(msg, "round_wind", -1),
				RichiNetProtocol.optInt(msg, "honba", -1),
				RichiNetProtocol.optInt(msg, "carried", -1),
				exposed, points,
				RichiNetProtocol.str(msg, "wall"),
				parseCodes(RichiNetProtocol.str(msg, "dora")),
				names);
		ack(session, true, null);
	}

	/** JSON 数组 → int[]（非法项取 def） */
	private static int[] intArray(JsonArray arr, int def) {
		int[] out = new int[arr.size()];
		for (int i = 0; i < out.length; i++) {
			try {
				out[i] = arr.get(i).getAsInt();
			} catch (Exception e) {
				out[i] = def;
			}
		}
		return out;
	}

	/**
	 * 镜面推进：把外部牌谱的"当前状态"整体投影到指定座位，并让牌桌自己走出这一步。
	 * <p>载荷：{@code {"table","seat","discard","river","melds","kita","hand","draw","riichi","tsumo"}}。
	 * river/melds 为整串记法（如 "123m4p" / "0:123m;2:555z!"）。hand = 该座位的"摸牌前手牌"
	 * （不含本次摸牌，含副露以外的全部暗牌），由 net 层按 {@link RiichiGame#mirrorInjectDraw} 补入摸牌，
	 * 从而保证牌桌侧摸牌动作与外部牌谱一致。未给的字段沿用牌桌当前值。
	 * <p>处理顺序：同步座位状态（牌河/副露/拔北/手牌+补牌）→ 立直 → 打牌（无 discard = 自摸）→ 开响应窗口。
	 */
	private static void onMirror(RichiNetSession session, JsonObject msg) {
		if (!requireJoined(session))
			return;
		TableRef ref = findTable(session.joinedTable);
		RiichiGame game = ref == null ? null : RichiTableManager.getGame(ref.origin());
		if (game == null) {
			sendError(session, "该桌没有进行中的对局");
			return;
		}
		int seat = game.findSeat(session.uuid);
		if (seat < 0) {
			// 本次会话尚未被对局登记（中途接管后 RiichiGame 内的座位仍是旧 UUID）：尝试原地换座
			if (ref.st().phase == RichiTableState.PHASE_PLAYING
					&& RichiTableManager.replaceSeatUuid(ref.origin(), session.uuid, session.name)) {
				seat = game.findSeat(session.uuid);
			}
		}
		if (seat < 0) {
			sendError(session, "你不在该对局中");
			return;
		}
		ref.st().mirrorUuids.add(session.uuid);
		String discardStr = RichiNetProtocol.str(msg, "discard");
		if (discardStr == null || discardStr.isEmpty())
			discardStr = RichiNetProtocol.str(msg, "tile");
		int discardCode = -1;
		if (discardStr != null && !discardStr.isEmpty()) {
			try {
				List<Integer> codes = MahjongTileNotation.parse(discardStr);
				if (!codes.isEmpty())
					discardCode = codes.get(0);
			} catch (Exception e) {
				sendError(session, "discard 牌面记法无效: " + discardStr);
				return;
			}
		}
		// 1) 状态同步：牌河/副露/拔北/手牌（外部牌谱是权威值）
		List<Integer> draw = parseCodes(RichiNetProtocol.str(msg, "draw"));
		applyMirrorState(game, seat, msg, draw);
		// 1.5) 加杠（外部牌谱该家把已有的碰升级为杠）：在打牌前执行
		String kakanStr = RichiNetProtocol.str(msg, "kakan");
		if (kakanStr != null && !kakanStr.isEmpty()) {
			List<Integer> kc = parseCodes(kakanStr);
			if (!kc.isEmpty())
				game.externalAction(session.uuid, "kakan", String.valueOf(kc.get(0)));
		}
		// 2) 尚未轮到本家：先让牌桌自动摸切/等 AI 走完（最多推进 playerCount 步，避免死循环）
		for (int guard = 0; guard <= game.playerCount(); guard++) {
			if (game.mirrorWaitingDiscard() && game.mirrorTurnSeat() != seat)
				game.mirrorAutoDiscard();
			// 已轮到我方 / 进入响应窗口（等 AI 表态）都停止推进
			if (game.mirrorWaitingDiscard() || game.mirrorWaitingDiscardAfterClaim())
				break;
		}
		if (!game.mirrorWaitingDiscard() || game.mirrorTurnSeat() != seat) {
			ack(session, false, "未轮到该座位（等待其他家动作或响应窗口结束）");
			return;
		}
		// 3) 立直宣言（外部牌谱 riichi=true 且未立直）
		if (RichiNetProtocol.optBool(msg, "riichi", false) && !game.player(seat).riichi)
			game.externalAction(session.uuid, "riichi", "");
		// 4) 和牌（外部牌谱该家自摸）：走牌桌自摸结算
		if (RichiNetProtocol.optBool(msg, "tsumo", false)) {
			boolean ok = game.externalAction(session.uuid, "tsumo", "");
			ack(session, ok, ok ? null : "自摸失败（时机不符）");
			return;
		}
		// 5) 打牌：按牌面找手牌下标（找不到红五等价/取最后一张）
		List<Integer> hand = game.player(seat).hand;
		int index = hand.size() - 1;
		if (discardCode >= 0) {
			index = -1;
			for (int i = 0; i < hand.size(); i++)
				if (hand.get(i) == discardCode) {
					index = i;
					break;
				}
			if (index < 0)
				for (int i = 0; i < hand.size(); i++)
					if (normalize(hand.get(i)) == normalize(discardCode)) {
						index = i;
						break;
					}
			if (index < 0) {
				ack(session, false, "手牌中没有 " + discardStr);
				return;
			}
		}
		boolean ok = game.mirrorDiscard(seat, index);
		ack(session, ok, ok ? null : "镜面打牌失败（时机不符）");
	}

	/** 记法 → 牌码列表（null/非法 = 空列表） */
	private static List<Integer> parseCodes(String notation) {
		if (notation == null || notation.isEmpty())
			return List.of();
		try {
			return MahjongTileNotation.parse(notation);
		} catch (Exception e) {
			return List.of();
		}
	}

	/** 红五归一到普通五（镜面比对用） */
	private static int normalize(int code) {
		int base = mcr.riichi_arise.game.TileFlag.base(code);
		return base == 0 ? 5 : base == 10 ? 15 : base == 20 ? 25 : base;
	}

	/** 把外部牌谱的牌河/副露/拔北/手牌整串写回座位（手牌 = 摸牌前暗牌，随后补入 draw）。
	 *  牌河按"含本次打出"理解：这里整串写入（含本次那张），随后 mirrorDiscard 会按
	 *  {@code wasDrawn} 判重，不会重复追加。 */
	private static void applyMirrorState(RiichiGame game, int seat, JsonObject msg, List<Integer> draw) {
		var p = game.player(seat);
		String river = RichiNetProtocol.str(msg, "river");
		if (river != null) {
			List<Integer> codes = parseCodes(river);
			p.river.clear();
			p.river.addAll(codes);
		}
		String melds = RichiNetProtocol.str(msg, "melds");
		if (melds != null) {
			p.melds.clear();
			if (!melds.isEmpty()) {
				for (String part : melds.split(";")) {
					if (part.isEmpty())
						continue;
					int from = -1;
					boolean kakan = part.endsWith("!");
					if (kakan)
						part = part.substring(0, part.length() - 1);
					// 兼容带来源席前缀的写法（"2:123m"）
					int colon = part.indexOf(':');
					if (colon > 0) {
						try {
							from = Integer.parseInt(part.substring(0, colon));
						} catch (NumberFormatException ignored) {
							from = -1;
						}
						part = part.substring(colon + 1);
					}
					List<Integer> codes = parseCodes(part);
					if (codes.isEmpty())
						continue;
					int[] tiles = new int[codes.size()];
					for (int i = 0; i < tiles.length; i++)
						tiles[i] = codes.get(i);
					p.melds.add(new mcr.riichi_arise.game.riichi.Fuuro(fuuroType(tiles, kakan, from, seat),
							tiles, tiles[tiles.length - 1], from));
				}
			}
		}
		int kita = RichiNetProtocol.optInt(msg, "kita", -1);
		if (kita >= 0) {
			p.kitaCount = kita;
			game.tableState().kita[seat] = kita;
		}
		String hand = RichiNetProtocol.str(msg, "hand");
		if (hand != null) {
			p.hand.clear();
			p.hand.addAll(parseCodes(hand));
			for (int c : draw)
				game.mirrorInjectDraw(seat, c);
			p.sortHand();
		}
		RichiTableManager.markDirty(game.origin());
	}

	/** 副露类型推断：4 张同种 = 明杠/加杠（加杠看 '!' 标记），3 张同种 = 碰，3 张连续 = 吃 */
	private static mcr.riichi_arise.game.riichi.Fuuro.Type fuuroType(int[] tiles, boolean kakan, int from, int seat) {
		boolean same = true;
		for (int t : tiles)
			if (normalize(t) != normalize(tiles[0]))
				same = false;
		if (tiles.length >= 4)
			return kakan ? mcr.riichi_arise.game.riichi.Fuuro.Type.KAKAN
					: (from < 0 || from == seat) ? mcr.riichi_arise.game.riichi.Fuuro.Type.ANKAN
							: mcr.riichi_arise.game.riichi.Fuuro.Type.MINKAN;
		return same ? mcr.riichi_arise.game.riichi.Fuuro.Type.PON : mcr.riichi_arise.game.riichi.Fuuro.Type.CHII;
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

	/** 镜面座位轮到打牌：推 hand/options/摸牌家/时限（该家手牌由外部牌谱投影，桥按此推进） */
	public static void pushMirrorTurn(RiichiGame game, int seat, java.util.List<String> options, int drawnCode) {
		RichiNetSession s = sessionForSeat(game, seat);
		if (s == null)
			return;
		JsonObject m = RichiNetProtocol.msg(RichiNetProtocol.S_MIRROR_TURN);
		fillTableSeat(game, m, seat);
		m.add("hand", codes(game.player(seat).hand));
		m.add("options", toArray(options));
		m.addProperty("drawn_seat", game.drawnSeat());
		m.addProperty("drawn", drawnCode);
		m.addProperty("riichi", game.player(seat).riichi);
		m.addProperty("waiting", game.mirrorWaitingDiscard());
		m.addProperty("window", game.mirrorClaimWindowEnd());
		s.send(m);
	}

	/** 镜面座位可响应他家打出的牌：推 claim 选项（含该张牌码与出牌家） */
	public static void pushMirrorClaim(RiichiGame game, int seat, java.util.List<String> options) {
		RichiNetSession s = sessionForSeat(game, seat);
		if (s == null)
			return;
		JsonObject m = RichiNetProtocol.msg(RichiNetProtocol.S_MIRROR_CLAIM);
		fillTableSeat(game, m, seat);
		m.addProperty("discarder", game.claimDiscarderSeat());
		m.add("options", toArray(options));
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
		m.addProperty("wind", new String[] { "E", "S", "W", "N" }[Math.floorMod(seat - game.gameDealerSeat(),
				Math.max(1, game.playerCount()))]);
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
		o.addProperty("player_count", st.playerCount); // 4=四麻 / 3=三麻（三麻座位 3 恒空）
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
		JsonArray kita = new JsonArray(); // 拔北数（三麻）：不算副露，独立字段
		for (int s = 0; s < 4; s++)
			kita.add(st.kita[s]);
		o.add("kita", kita);
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
		ack(session, ok, msg, -1);
	}

	/** 带座位号的 ack（mirror 接入用；seat >= 0 才写入，桥据此对齐 mirror_seat） */
	private static void ack(RichiNetSession session, boolean ok, String msg, int seat) {
		JsonObject m = RichiNetProtocol.msg(RichiNetProtocol.S_ACK);
		m.addProperty("ok", ok);
		if (msg != null)
			m.addProperty("msg", msg);
		if (seat >= 0)
			m.addProperty("seat", seat);
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
