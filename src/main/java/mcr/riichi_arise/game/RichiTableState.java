package mcr.riichi_arise.game;

/**
 * 一张风盘的牌局状态（纯数据，行为在 RichiTableManager）。
 * 各处牌均用牌谱记法字符串存储（如 "12345m123p456s11z"）。
 * 家序索引 0..3 = 東南西北（座位名另见 FengPanBlock）。
 */
public class RichiTableState {
	/** 各家手牌（13 张） */
	public final String[] hands = new String[4];
	/**
	 * 各家手牌中"透明牌"的真实牌面（玻璃之间，理牌序；空串 = 该家无透明牌）。
	 * 同步层对不可见座位只下发此串：对手能透过玻璃牌背看到透明牌的牌面，
	 * 其余位置渲染玻璃牌背（{@link mcr.riichi_arise.game.RichiTableSync}）。
	 */
	public final String[] glassHands = { "", "", "", "" };
	/** 各家牌河（切出的牌，面朝上） */
	public final String[] rivers = new String[4];
	/** 各家副露（吃碰杠，含声明牌） */
	public final String[] melds = new String[4];
	/** 中央宝牌指示区（5 叠 × 2 张 = 10 张，叠序存放） */
	public String doraWall = "";
	/** 牌山剩余（未摸的牌，暂不渲染） */
	public String wall = "";
	/** 各家点数 */
	public final int[] points = new int[4];
	/** 当前回合座位（0..3），-1 = 未开局/已流局（简单对局：回合家摸一张→打出→下家摸牌） */
	public int turnSeat = -1;
	/** 入座玩家（UUID 字符串，null = 空位；座位 0..3 = 東南西北，发牌者坐東家） */
	public final String[] players = new String[4];
	/** 当前局数（東/南 X 局，1..4） */
	public int round = 1;
	/** 当前局风（0=東，1=南；半庄战南场局风） */
	public int roundWind = 0;
	/** 对局长度（开局前在大厅选择）：0=一局 1=东风战(4局) 2=半庄战(8局) */
	public int gameType = 0;
	/** 对局人数（大厅切换）：4=四人麻雀 3=三人麻雀；三麻只使用座位 0/1/2（座位 3 留空不参与） */
	public int playerCount = 4;
	/** 房间类型（大厅切换）：0=铁之间（标准）1=玻璃之间（无红宝牌/无拔北，每种牌随机 3 张透明牌） */
	public static final int ROOM_TIE = 0, ROOM_GLASS = 1;
	public int roomType = ROOM_TIE;
	/**
	 * 各家拔北数（三麻，雀魂规则）：拔出的北风牌张数，每张和牌时 +1 翻。resetHand 清零。
	 * <p>拔北<b>不算副露</b>：不计入 {@link #melds}、不破坏门清；渲染经整桌快照的独立 kita 字段同步
	 * （{@link mcr.riichi_arise.network.MahjongTablePayload.ViewMessage#kita()}），
	 * 客户端在 {@link mcr.riichi_arise.render.TableLayout#plan} 中单独摆位。</p>
	 */
	public final int[] kita = new int[4];
	/** 思考时间档位：{每巡时限秒, 读秒(长思考库)秒}；thinkIdx 由管理界面切换，开局时套用 */
	public static final int[][] THINK_PRESETS = { { 5, 20 }, { 10, 30 }, { 60, 0 }, { 3, 5 } };
	public int thinkIdx = 0;
	/** AI 打牌速度档：0=快(现行) 1=中(×2) 2=慢(×3)；AI 动作延迟统一乘该系数 */
	public static final int[] AI_SPEED_MUL = { 1, 2, 3 };
	public int aiSpeedIdx = 0;
	/** 结算界面节奏（客户端渲染与服务端确认门控共用，刻）：弹出后延迟 1s 开始报番 */
	public static final int SETTLE_ANNOUNCE_DELAY = 20;
	/** 无报番语音时每条役种的显现间隔（0.5s） */
	public static final int SETTLE_ANNOUNCE_STEP = 10;
	/** 报番结束 → 番符/点数/段位依次显现的三段延迟合计（15 tick = 三段各 5 tick） */
	public static final int SETTLE_TIER_OFFSET = 15; // 番数符数/点数/段位三段各间隔 5 tick
	/** 段位显现后的确认倒计时（5s）：归零时客户端自动回 settle_confirm */
	public static final int SETTLE_CONFIRM_TICKS = 5 * 20;
	/** 有真实客户端时的服务端兜底时限（防挂机/掉线；正常由客户端自动确认推进） */
	public static final int SETTLE_FALLBACK_TICKS = 20 * 20;
	/** 本场数（连庄/流局累加，庄家输牌清零；本场费 300/本场） */
	public int honba = 0;

	/** 本桌已开局次数（用于每局轮转席位，避免固定玩家永远坐东） */
	public int gamesStarted = 0;
	/** 各座位独立 AI 形象模式（索引同座位；null/空 = 随机形态） */
	public final String[] aiAvatarModes = new String[4];
	/** 本局各座位的角色形象（开局洗牌时角色随 AI 迁移到此数组；不改 aiAvatarModes——它是"预约座位"的设置，存档按预约座位保存） */
	public final String[] aiSeatModes = new String[4];
	/** AI 形象脏标记：预约/形象模式变化后置 true，resolveAvatars 解析一次后清除（避免每次广播重复解析阻塞主线程） */
	public transient boolean avatarsDirty = true;
	/** 各 AI 座位最终形态 id（deal 时按 aiAvatarModes 解析随机并固定；空 = 非 AI/未开局）。随座位轮转迁移，经同步包下发客户端渲染假玩家 */
	public final String[] aiAvatarForms = new String[4];
	/** 各 AI 座位人类模式显示名（human 模式随机名，其余空串） */
	public final String[] aiAvatarNames = new String[4];
	/** 各 AI 座位人类模式皮肤档案 UUID（空串 = 未解析到正版档案） */
	public final String[] aiAvatarSkins = new String[4];
	/**
	 * 各 AI 座位流派（resolveAvatars 按形象/名字分配）：下标与 {@link RiichiBot#FLOW_NAMES} 对应，
	 * 0 一般流 / 1 野猪流 / 2 门清流 / 3 御无双 / 4 魂天流 / 5 鬼神境
	 */
	public final int[] aiFlows = new int[4];
	/** 明牌（默认关）：关时参战雀士看不到他人手牌牌面（打出/推倒后可见），显示未知牌面；观战者始终可见全部 */
	public boolean openHand = false;
	/** 当前回合截止时刻（游戏刻，0 = 无计时）；超时自动摸切 */
	public long turnDeadline;
	/** 当前回合限时长（刻）：短考 5s（每回合重置）；进入长考后 = 长考银行剩余 */
	public int turnLimit = 5 * 20;
	/** 当前回合是否处于长考（短考耗尽后进入，开始消耗长考银行） */
	public boolean turnInLong;
	/** 各家长考银行（刻）：短考耗尽后逐回合扣减，仅每局重置（新牌局创建时回满） */
	public final int[] longBank = { 20 * 20, 20 * 20, 20 * 20, 20 * 20 };
	/** 牌桌阶段：0=等待中（可排队）1=对局中 2=已结束（等待重开） */
	public static final int PHASE_WAITING = 0, PHASE_PLAYING = 1, PHASE_FINISHED = 2;
	public int phase = PHASE_WAITING;
	/** 等待队列（UUID，先到先坐；开局时按序补入空座位） */
	public final java.util.List<String> queue = new java.util.ArrayList<>();
	/** NPC 参战者 UUID（Murmol NPC 经右键确认加入队列/对局）：开局视作 AI 座位，
	 *  客户端跳过其假玩家（form=npc），真实 NPC 实体钉在座位站位；终局/移出时解除 */
	public final java.util.Set<String> npcUuids = new java.util.HashSet<>();
	/** 每桌可容纳的参牌者（NPC/女仆等）上限：放开多女仆，默认最多 3，至少留 1 个真人座位（可调整） */
	public static final int MAX_NPC_SEATS = 3;
	/** 外部程序（net 端口接入）玩家 UUID（类似 npcUuids）：对局中 aiSeat=false，操作经 net 层转发，
	 *  客户端无对应 ServerPlayer，仅作为数据源参与；观战者视角该座位无真实实体（仅显示名字） */
	public final java.util.Set<String> externalUuids = new java.util.HashSet<>();
	/** 外部玩家显示名：uuid → 名字（join 时设置，构造 RiichiPlayer 用） */
	public final java.util.Map<String, String> externalNames = new java.util.HashMap<>();
	/** 服务端 Marker 代理实体：每个 AI 座位对应一个 Marker，可被选择器选中/tp；key=座位 0..3，value=实体 UUID */
	public final java.util.Map<Integer, java.util.UUID> aiMarkerUuids = new java.util.HashMap<>();
	/** 管理界面预约的 AI 座位（等待阶段设置，开局时该座位固定安排 AI） */
	public final boolean[] reservedAI = new boolean[4];
	/** 各座位是否为 AI（对局中有效，虚拟 UUID 见 RiichiBot） */
	public final boolean[] aiSeat = new boolean[4];
	/** 各家立直点棒数（宣言成功后 +1） */
	public final int[] riichiSticks = new int[4];
	/** 供托：此前局目流局留存、尚未结算的立直棒数（有人和牌时全部取走；渲染在王牌指示叠旁） */
	public int carriedSticks = 0;
	/** 各家立直宣言牌在牌河中的下标（横摆渲染；-1 = 无 / 已被鸣牌取走） */
	public final int[] riichiRiverIdx = { -1, -1, -1, -1 };
	/** 立直宣言牌被鸣后待补标记：该家下一张切牌改为横摆 */
	public final boolean[] riichiPending = new boolean[4];
	/** 局终手牌展示：0=对局中立牌，1=推倒（面朝上，和牌者），2=盖牌（面朝下） */
	public final int[] handsExposed = new int[4];
	/** 对局中"投票结束对局"：各人类座位是否已投票（全员同意 → 强制终局） */
	public final boolean[] endVotes = new boolean[4];
	/** 各家最近一次打牌是否手切（1 = 手切，0 = 摸切/无）：隐藏手牌的牌背动画 key 错位用 */
	public final int[] lastTedashi = new int[4];
	/** 各家最近一次打出的牌在手牌中的位置，编码 seat&lt;&lt;8|index（-1 = 无）：客户端播放切牌飞出的起点 */
	public final int[] lastDiscardPos = { -1, -1, -1, -1 };
	/** 各家和牌特效风格（0..3，来自各自"个人设定"，随快照下发，全桌都按和牌者的设置渲染） */
	public final int[] winEffects = new int[4];
	/** 手牌展示常量 */
	public static final int HAND_STAND = 0, HAND_FACE_UP = 1, HAND_FACE_DOWN = 2;
	/** 已揭开的宝牌指示牌叠数（开局 1，杠后 +1；渲染翻开对应上张） */
	public int revealedIndicators = 1;
	/** 当前选中的手牌（-1 = 无选中；渲染时该牌升高，再次右键打出，见 FengPanBlock） */
	public int selectedSeat = -1;
	public int selectedIndex = -1;
	/** 当前回合家已摸牌未打（-1 = 无）：该家手牌最右一张为刚摸的牌，渲染时与原手牌隔开一个牌位 */
	public int drawnSeat = -1;
	/** 结算横幅文本（瞬态，客户端 text_display 渲染；finishRound 设置，resetHand 清空） */
	public String banner = "";
	/** 剧本牌局（临时，拍视频用）：null = 普通对局；不参与存档。见 FilmScenario */
	public transient mcr.riichi_arise.game.riichi.FilmScenario film;

	public RichiTableState() {
		java.util.Arrays.fill(points, 25000);
	}

	/** 配对起始点数：三麻 35000 / 四麻 25000（雀魂规则，开局 startGame 按人数填充） */
	public static int startPoints(int playerCount) {
		return playerCount == 3 ? 35000 : 25000;
	}

	/** 配对返点：三麻 40000 / 四麻 30000（段位 pt 结算用，见 MahjongRankStore） */
	public static int returnPoints(int playerCount) {
		return playerCount == 3 ? 40000 : 30000;
	}

	/** 清除选牌状态（打出/重置时调用） */
	public void clearSelection() {
		selectedSeat = -1;
		selectedIndex = -1;
	}

	/** 各家手牌解析为牌面代码（渲染用；记法非法时返回空列表，不让渲染中断牌局） */
	public java.util.List<Integer> handCodes(int seat) {
		return safeParse(hands[seat]);
	}

	public java.util.List<Integer> riverCodes(int seat) {
		return safeParse(rivers[seat]);
	}

	/** 各家副露分组解析：melds 字符串以 ";" 分隔多组，每组 "声明家:牌码"（声明家 = 被吃/碰/杠的来源座位）；
	 *  加杠组在牌码后追加 "!"（渲染时第 4 张叠在横放的碰牌上，而非排成明杠） */
	public java.util.List<java.util.List<Integer>> meldGroups(int seat) {
		java.util.List<java.util.List<Integer>> groups = new java.util.ArrayList<>();
		for (MeldGroup g : meldGroupInfos(seat))
			groups.add(g.codes());
		return groups;
	}

	/** 副露组（含声明来源座位与加杠标记） */
	public record MeldGroup(int fromSeat, java.util.List<Integer> codes, boolean kakan) {
	}

	public java.util.List<MeldGroup> meldGroupInfos(int seat) {
		java.util.List<MeldGroup> groups = new java.util.ArrayList<>();
		if (melds[seat] == null || melds[seat].isEmpty())
			return groups;
		for (String part : melds[seat].split(";")) {
			if (part.isEmpty())
				continue;
			boolean kakan = part.endsWith("!"); // 加杠标记（见 RiichiPlayer.meldNotation）
			if (kakan)
				part = part.substring(0, part.length() - 1);
			int from = -1;
			String codes = part;
			int colon = part.indexOf(':');
			if (colon > 0) {
				try {
					from = Integer.parseInt(part.substring(0, colon));
					codes = part.substring(colon + 1);
				} catch (NumberFormatException ignored) {
					from = -1;
					codes = part;
				}
			}
			groups.add(new MeldGroup(from, safeParse(codes), kakan));
		}
		return groups;
	}

	public java.util.List<Integer> doraCodes() {
		return safeParse(doraWall);
	}

	public java.util.List<Integer> wallCodes() {
		return safeParse(wall);
	}

	/** 某座位的入座玩家（未入座/离线返回 null） */
	public net.minecraft.server.level.ServerPlayer playerOf(net.minecraft.server.level.ServerLevel level, int seat) {
		String u = players[seat];
		if (u == null)
			return null;
		try {
			return level.getServer().getPlayerList().getPlayer(java.util.UUID.fromString(u));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static java.util.List<Integer> safeParse(String notation) {
		try {
			return MahjongTileNotation.parse(notation);
		} catch (RuntimeException e) {
			com.mojang.logging.LogUtils.getLogger().error("[Richi] 牌谱解析失败: {}", notation, e);
			return java.util.List.of();
		}
	}
}
