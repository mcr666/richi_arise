package mcr.riichi_arise.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import mcr.riichi_arise.block.FengPanBlock;
import mcr.riichi_arise.network.MahjongTablePayload;

/**
 * 牌局渲染同步（防抓包看牌核心）：把 RichiTableState 按观看者过滤后整桌广播
 * （RiichiGame.writeBack / FengPanBlock 重渲染与发牌动画时调用）。
 * 过滤规则：visible(座位 s) = 观战者(viewerSeat&lt;0) || openHand || s==viewer || handsExposed[s]==HAND_FACE_UP
 * （盖牌 HAND_FACE_DOWN 不下发真实牌面，客户端渲染牌背——流局未听/途中流局的防抓包处理）；
 * 不可见座位只发牌数（hidden），客户端渲染牌背。牌河/副露/立直棒永远真实；
 * 宝牌指示区仅已揭开的上张下发真实牌面（底张=里宝与未翻开上张发未知占位 "?"，防抓包/实体检视泄露）；
 * 局终盖牌（FACE_DOWN）也不下发牌面，客户端按 hidden 数渲染牌背平躺。
 */
public final class RichiTableSync {
	/** 同步范围：桌心 16 格内同维玩家 */
	private static final double RANGE = 16;

	private RichiTableSync() {
	}

	public static void broadcast(ServerLevel level, BlockPos origin) {
		broadcast(level, origin, 0);
	}

	/**
	 * 桌已拆除：广播一包空等待快照（无形象），客户端据此清掉牌面实体与 AI 假玩家/盔甲架。
	 * 风盘被挖/连锁拆除后调用，避免上一局角色残留在原地。
	 */
	public static void broadcastGone(ServerLevel level, BlockPos origin) {
		Vec3 center = new Vec3(origin.getX() + 1.0, origin.getY() + 0.5, origin.getZ() + 1.0);
		String[] empty4 = { "", "", "", "" };
		int[] zeros = new int[4];
		for (ServerPlayer p : level.players()) {
			if (p.level() != level)
				continue;
			if (p.distanceToSqr(center.x, center.y, center.z) > RANGE * RANGE)
				continue;
			net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new MahjongTablePayload.ViewMessage(
					origin.asLong(), -1, false, 0, empty4, zeros, zeros, zeros, empty4, empty4, zeros,
					zeros, zeros, zeros, zeros, empty4, zeros, empty4, empty4, empty4,
					"", 0, 0, -1, 0, 0, 0, 0, -1, -1, -1, 0, 4, "", new int[4], RichiTableState.ROOM_TIE,
					new int[4]));
		}
	}

	/** animate &gt; 0 时客户端按摆位 delay 重放发牌动画 */
	public static void broadcast(ServerLevel level, BlockPos origin, int animate) {
		RichiTableState st = RichiTableManager.get(origin);
		if (st == null || !(level.getBlockState(origin).getBlock() instanceof FengPanBlock))
			return;
		Vec3 center = FengPanBlock.getTableCenter(level.getBlockState(origin), origin);
		ensureBatch(origin.asLong());
		for (ServerPlayer p : level.players()) {
			if (p.level() != level)
				continue;
			if (p.distanceToSqr(center.x, center.y, center.z) > RANGE * RANGE)
				continue;
			sendTo(level, origin, st, p, animate);
		}
	}

	/** 对单个观看者构造过滤快照并发送 */
	private static void sendTo(ServerLevel level, BlockPos origin, RichiTableState st, ServerPlayer p, int animate) {
		// 掩码/牌数/宝牌/牌山在本轮广播内同表只解析一次（#ensureBatch）
		int viewerSeat = -1;
		for (int s = 0; s < 4; s++) {
			String u = st.players[s];
			if (u != null && u.equals(p.getStringUUID()))
				viewerSeat = s;
		}
		String[] hands = new String[4];
		int[] hidden = new int[4];
		int[] tedashi = new int[4];
		// 玻璃之间：各家手牌里"该座位观者看不见的透明牌位置"掩码。
		// 透明牌的牌面本身不泄露（本来就该被他看见），但"摆在哪个位置"会泄露理牌顺序/听哪些牌，
		// 所以每个观者拿到的是自己那一份位置：其余人渲染时把这几位换成普通牌背。
		int[] glassMask = new int[4];
		String[] names = new String[4];
		// AI 形象字段由包级去重器按内容缓存下发（见 MahjongTablePayload.dedupe），这里只填原值
		String[] avatarForms = new String[4];
		String[] avatarNames = new String[4];
		String[] avatarSkins = new String[4];
		for (int s = 0; s < 4; s++) {
			boolean visible = viewerSeat < 0 || st.openHand || s == viewerSeat
					|| st.handsExposed[s] == RichiTableState.HAND_FACE_UP;
			if (visible) {
				hands[s] = st.hands[s] == null ? "" : st.hands[s];
				hidden[s] = 0;
			} else {
				// 其余座位：牌面只给"透明牌的牌面"（本来就该被看穿），普通牌为占位；
				// 哪几位是透明牌由 glassMask 按观者洗牌后决定
				hands[s] = glassOnly(st, s);
				hidden[s] = hiddenCount(s, st);
				if (st.openHand || s == viewerSeat)
					glassMask[s] = 0;
				else
					glassMask[s] = glassShuffle(st, s, p);
			}
			tedashi[s] = st.lastTedashi[s];
			names[s] = seatName(level, st, s);
			// AI 形象只要座位是 AI 就下发：对局中与终局都按本局 aiSeat（终局保持形象不消失）；
			// 等待阶段再补充"下一局预约"（预约座位与洗牌后的实际座位无关，终局若按预约出形象会在人类座位上多出一个假玩家）。
			// 三麻（playerCount=3）不存在的第 4 席恒不下发（四麻切三麻后该席形象必须消失）
			boolean aiSeat = s < st.playerCount
					&& (st.aiSeat[s] || (st.phase == RichiTableState.PHASE_WAITING && st.reservedAI[s]));
			if (aiSeat) {
				avatarForms[s] = st.aiAvatarForms[s] == null ? "" : st.aiAvatarForms[s];
				avatarNames[s] = st.aiAvatarNames[s] == null ? "" : st.aiAvatarNames[s];
				avatarSkins[s] = st.aiAvatarSkins[s] == null ? "" : st.aiAvatarSkins[s];
			}
		}
		// 宝牌指示区按公开程度过滤：底张（里宝）恒为未知占位；上张仅已揭开叠数下发真实牌面
		MahjongTablePayload.ViewMessage msg = new MahjongTablePayload.ViewMessage(
				origin.asLong(), viewerSeat, st.openHand, animate,
				hands, hidden, tedashi, glassMask, Arrays.copyOf(st.rivers, 4), Arrays.copyOf(st.melds, 4),
				Arrays.copyOf(st.kita, 4),
				Arrays.copyOf(st.riichiRiverIdx, 4), Arrays.copyOf(st.riichiSticks, 4),
				Arrays.copyOf(st.handsExposed, 4), Arrays.copyOf(st.points, 4),
				names, new int[4], avatarForms, avatarNames, avatarSkins,
				doraText(st), st.revealedIndicators,
				wallSize(st), st.turnSeat, st.round, st.roundWind, st.honba, st.carriedSticks,
				st.selectedSeat, st.selectedIndex, st.drawnSeat, st.phase, st.playerCount,
				st.banner == null ? "" : st.banner, Arrays.copyOf(st.lastDiscardPos, 4), st.roomType,
				Arrays.copyOf(st.winEffects, 4));
		// 形象去重：整局不重发的内容只留索引
		msg = MahjongTablePayload.dedupe(msg, p.getUUID());
		// 整包与该观看者上次收到的完全相同（如同桌其他座位变化但对他不可见）：不发
		if (MahjongTablePayload.isDuplicate(msg, p.getUUID(), lastBody))
			return;
		net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, msg);
	}

	/** 每个观看者上次实际发出的包内容（完全相同则跳过发送） */
	private static final java.util.Map<java.util.UUID, byte[]> lastBody = new java.util.concurrent.ConcurrentHashMap<>();

	/** 观看者离线/换桌：清掉去重记录 */
	public static void forget(java.util.UUID viewer) {
		forgetViewer(viewer);
		MahjongTablePayload.forget(viewer);
	}

	/** 只清"上次已发包"记录（形象索引缓存保留，避免凭空多发真值） */
	public static void forgetViewer(java.util.UUID viewer) {
		if (viewer != null)
			lastBody.remove(viewer);
	}

	/**
	 * 玻璃之间：座位 s 的手牌中，观者 p 这一局"能看见"的透明牌位置掩码（bit k = 第 k 张透明）。
	 * 每局开局把该座位的透明牌位置洗牌后分发给观者（各自独立、整局固定）：透明牌的存在与张数对所有
	 * 观者相同，但对手看不到它摆在手牌第几位，从而不会泄露理牌顺序/待张/听牌。
	 */
	private static int glassShuffle(RichiTableState st, int seat, ServerPlayer viewer) {
		String[] arr = GLASS_SHUFFLE.computeIfAbsent(viewer.getUUID(), k -> new String[4]);
		String seen = arr[seat];
		if (seen != null && !seen.isEmpty())
			return Integer.parseInt(seen);
		int glassCount = st.glassHands[seat] == null ? 0 : MahjongTileNotation.parse(st.glassHands[seat]).size();
		int n = st.handCodes(seat).size();
		int mask = 0;
		if (glassCount > 0 && n > glassCount) {
			// 其余位置里随机挑同样多的"可见透明位"：对手只知道有 glassCount 张透明牌
			java.util.List<Integer> positions = new java.util.ArrayList<>();
			for (int k = 0; k < n; k++)
				positions.add(k);
			java.util.Collections.shuffle(positions, random);
			for (int k = 0; k < glassCount; k++)
				mask |= 1 << Math.min(30, positions.get(k));
		} else {
			for (int k = 0; k < glassCount; k++)
				mask |= 1 << Math.min(30, k);
		}
		arr[seat] = String.valueOf(mask);
		return mask;
	}

	private static final java.util.Random random = new java.util.Random();

	/** 新一局开始：清掉所有观者的玻璃位置分配（下一包重新洗牌） */
	public static void resetGlassShuffle() {
		GLASS_SHUFFLE.clear();
	}

	/** 各观者 → 座位 → 玻璃位置掩码（整局固定） */
	private static final java.util.Map<java.util.UUID, String[]> GLASS_SHUFFLE = new java.util.concurrent.ConcurrentHashMap<>();


	/** 每次广播前清空一次性解析缓存 */
	private static void beginBatch() {
		Arrays.fill(hiddenCache, -1);
		Arrays.fill(glassCache, null);
		doraCache = null;
		wallCache = -1;
	}

	/** 一次性缓存所属的桌（originPacked）：广播按桌连续遍历观看者，换桌即重算 */
	private static long batchOrigin = Long.MIN_VALUE;

	private static void ensureBatch(long originPacked) {
		if (batchOrigin == originPacked)
			return;
		beginBatch();
		batchOrigin = originPacked;
	}

	/** 每次广播（同一张桌）内只解析一次：手牌张数、宝牌串、牌山张数 */
	private static final int[] hiddenCache = { -1, -1, -1, -1 };
	private static final String[] glassCache = new String[4];
	private static String doraCache;
	private static int wallCache = -1;

	/**
	 * 隐藏手牌里"透明牌的真实牌面"（玻璃之间；空串 = 该家无透明牌）：
	 * 客户端按 glassMask 把这些牌面填回自己的随机位置，其余显示玻璃牌背。
	 */
	private static String glassOnly(RichiTableState st, int seat) {
		if (glassCache[seat] == null) {
			String g = st.glassHands[seat];
			if (g == null)
				g = "";
			else if (st.roomType != RichiTableState.ROOM_GLASS)
				g = "";
			glassCache[seat] = g;
		}
		return glassCache[seat];
	}

	/** 隐藏手牌张数（同一次广播内每座位只解析一次） */
	private static int hiddenCount(int seat, RichiTableState st) {
		if (hiddenCache[seat] < 0)
			hiddenCache[seat] = st.handCodes(seat).size();
		return hiddenCache[seat];
	}

	/** 宝牌指示区串（底张/未翻开上张为未知占位），同一次广播内只算一次 */
	private static String doraText(RichiTableState st) {
		if (doraCache == null) {
			List<Integer> doraAll = st.doraCodes();
			List<Integer> filtered = new ArrayList<>(doraAll.size());
			for (int i = 0; i < doraAll.size(); i++)
				filtered.add(i % 2 == 1 && i / 2 < st.revealedIndicators ? doraAll.get(i) : -1);
			doraCache = MahjongTileNotation.format(filtered);
		}
		return doraCache;
	}

	/** 牌山剩余张数（同一次广播内只算一次） */
	private static int wallSize(RichiTableState st) {
		if (wallCache < 0)
			wallCache = st.wallCodes().size();
		return wallCache;
	}

	/** 座位显示名：人类 = 游戏档案名，AI = 流派 AI 名，空位 = 空串 */
	private static String seatName(ServerLevel level, RichiTableState st, int seat) {
		ServerPlayer p = st.playerOf(level, seat);
		if (p != null)
			return p.getGameProfile().getName();
		if (st.aiSeat[seat])
			return mcr.riichi_arise.game.riichi.RiichiBot.aiName(seat);
		return "";
	}
}
