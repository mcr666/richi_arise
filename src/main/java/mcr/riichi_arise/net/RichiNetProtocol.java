package mcr.riichi_arise.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 内网接入协议（NDJSON：每条消息一个 JSON 对象，字段 type 区分）。
 * <p>客户端 → 服务端：hello / ping / list_tables / subscribe / unsubscribe / join / leave / discard / action / mirror / mirror_table / quit。
 * 服务端 → 客户端：welcome / tables / snapshot / your_turn / claim_options / riichi_pick / mirror_turn / mirror_claim / settled / ack / error / pong。
 */
public final class RichiNetProtocol {
	private RichiNetProtocol() {
	}

	// ---- 消息类型（客户端→服务端）----
	public static final String C_HELLO = "hello";
	public static final String C_PING = "ping";
	public static final String C_LIST_TABLES = "list_tables";
	public static final String C_SUBSCRIBE = "subscribe";
	public static final String C_UNSUBSCRIBE = "unsubscribe";
	public static final String C_JOIN = "join";
	public static final String C_LEAVE = "leave";
	public static final String C_DISCARD = "discard";
	public static final String C_ACTION = "action";
	/** 镜面推进一步（外部牌谱驱动牌桌）：data =
	 *  {"table","discard","river","melds","kita","hand","draw","riichi","tsumo"}，
	 *  服务端把外部牌谱的当前状态整体投影到本方座位（手牌/牌河/副露/拔北/立直）并补入本次摸牌，
	 *  随后按 discard 打牌（无 discard + tsumo=true = 自摸）。未给的字段沿用牌桌当前值。 */
	public static final String C_MIRROR = "mirror";
	/** 整桌投影一步（外部对局转播）：data = {"table","seats":[{hand,river,melds,kita,riichi,points}×4],
	 *  "drawn_seat","turn_seat","round","round_wind","honba","carried","banner","hands_exposed","points","wall","dora"}，
	 *  服务端整体覆盖四家状态（牌桌不再自行摸打）。未给的字段沿用牌桌当前值。 */
	public static final String C_MIRROR_TABLE = "mirror_table";
	public static final String C_QUIT = "quit";

	// ---- 消息类型（服务端→客户端）----
	public static final String S_WELCOME = "welcome";
	public static final String S_TABLES = "tables";
	public static final String S_SNAPSHOT = "snapshot";
	public static final String S_YOUR_TURN = "your_turn";
	public static final String S_CLAIM_OPTIONS = "claim_options";
	public static final String S_RIICHI_PICK = "riichi_pick";
	/** 牌桌等待镜面座位出牌（外部牌谱的下一张切牌）：附手牌与立直待选标记 */
	public static final String S_MIRROR_TURN = "mirror_turn";
	/** 牌桌等待镜面座位对某张牌的响应（外部牌谱里该家鸣牌/和牌）：附牌河末张与可选动作 */
	public static final String S_MIRROR_CLAIM = "mirror_claim";
	public static final String S_SETTLED = "settled";
	public static final String S_ACK = "ack";
	public static final String S_ERROR = "error";
	public static final String S_PONG = "pong";

	// ---- 动作常量（C_ACTION 的 action 字段；data 透传给 RiichiGame.applyAction）----
	/** 拔北（三麻）：无参动作，data 置空；回合选项推送（your_turn）里出现 "kita" 即可回发该动作 */
	public static final String A_KITA = "kita";

	// ---- 安全取值工具 ----
	public static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	public static int optInt(JsonObject o, String key, int def) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull() || !e.isJsonPrimitive())
			return def;
		try {
			return e.getAsInt();
		} catch (Exception ex) {
			return def;
		}
	}

	public static boolean optBool(JsonObject o, String key, boolean def) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull() || !e.isJsonPrimitive())
			return def;
		try {
			return e.getAsBoolean();
		} catch (Exception ex) {
			return def;
		}
	}

	/** 构造一条基础消息（含 type） */
	public static JsonObject msg(String type) {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		return o;
	}
}
