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
					origin.asLong(), -1, false, 0, empty4, zeros, zeros, zeros, empty4, empty4, zeros, new int[4],
					zeros, zeros, zeros, zeros, empty4, zeros, empty4, empty4, empty4,
					"", 0, 0, -1, 0, 0, 0, 0, -1, -1, -1, 0, 4, "", "", new int[4], RichiTableState.ROOM_TIE,
					"", -1, new int[4]));
		}
	}

	/** animate &gt; 0 时客户端按摆位 delay 重放发牌动画 */
	public static void broadcast(ServerLevel level, BlockPos origin, int animate) {
		RichiTableState st = RichiTableManager.get(origin);
		if (st == null || !(level.getBlockState(origin).getBlock() instanceof FengPanBlock))
			return;
		Vec3 center = FengPanBlock.getTableCenter(level.getBlockState(origin), origin);
		beginBatch();
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
		// 玻璃之间：各家隐藏手牌里"透明牌"的显示位置掩码。
		// 透明牌的牌面本身不泄露（本来就该被他看见），但"摆在哪个位置"会泄露理牌顺序/听哪些牌，
		// 所以对手视角的透明牌一律集中在手牌最左端（刚摸入的那张除外，留在真实摸牌位），
		// 未知牌背排在右侧（见 glassLeftMask）。
		int[] glassMask = new int[4];
		String[] names = new String[4];
		// 本包应按哪一局的房间类型过滤手牌（终局后改大厅配置不影响已结束的一局）
		int renderRoom = renderRoomType(st);
		// AI 形象字段由包级去重器按内容缓存下发（见 MahjongTablePayload.dedupe），这里只填原值
		String[] avatarForms = new String[4];
		String[] avatarNames = new String[4];
		String[] avatarSkins = new String[4];
		for (int s = 0; s < 4; s++) {
			// 镜面接入座位（外部牌谱驱动）：对任何真人观者都只当普通对手——手牌给张数、副露不下发
			// 整桌投影（雀魂等外部转播）由外部快照驱动，四家手牌照常下发（仅作为牌背张数/透明牌处理）
			boolean mirrorSeat = !st.mirrorAll && st.players[s] != null && st.mirrorUuids.contains(st.players[s]);
			boolean visible = viewerSeat < 0 || st.openHand || s == viewerSeat
					|| st.handsExposed[s] == RichiTableState.HAND_FACE_UP;
			if (mirrorSeat && s != viewerSeat)
				visible = false;
			if (visible) {
				hands[s] = st.hands[s] == null ? "" : st.hands[s];
				hidden[s] = 0;
			} else {
				// 其余座位：玻璃之间只下发透明牌的真实牌面，普通牌保持未知占位
				hands[s] = mirrorSeat ? "" : glassOnly(st, s, renderRoom);
				hidden[s] = hiddenCount(s, st);
				// 可见性由上面的 visible 分支决定；走到这里即为隐藏座位，透明位集中在手牌最左端
				glassMask[s] = mirrorSeat ? 0 : glassLeftMask(st, s, renderRoom);
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
				hands, hidden, tedashi, glassMask,
				Arrays.copyOf(st.rivers, 4), meldsForSync(st), Arrays.copyOf(st.kita, 4),
				Arrays.copyOf(st.kitaGlass, 4),
				Arrays.copyOf(st.riichiRiverIdx, 4), Arrays.copyOf(st.riichiSticks, 4),
				Arrays.copyOf(st.handsExposed, 4), Arrays.copyOf(st.points, 4),
				names, new int[4], avatarForms, avatarNames, avatarSkins,
				doraText(st), st.revealedIndicators,
				wallSize(st), st.turnSeat, st.round, st.roundWind, st.honba, st.carriedSticks,
				st.selectedSeat, st.selectedIndex, st.drawnSeat, st.phase, st.playerCount,
				st.banner == null ? "" : st.banner, st.bannerI18n == null ? "" : st.bannerI18n,
				Arrays.copyOf(st.lastDiscardPos, 4), renderRoom,
				st.candidates == null ? "" : st.candidates, st.candidatePickSeat,
				Arrays.copyOf(st.winEffects, 4));
		// 形象去重：整局不重发的内容只留索引
		msg = MahjongTablePayload.dedupe(msg, p.getUUID());
		// 整包与该观看者上次收到的完全相同（如同桌其他座位变化但对他不可见）：不发
		if (MahjongTablePayload.isDuplicate(msg, p.getUUID(), lastBody))
			return;
		net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, msg);
	}

	/** 副露串：镜面接入座位（外部牌谱驱动）的副露不对真人下发（外部牌谱含普通鸣牌，泄露=提前看到他家成形） */
	private static String[] meldsForSync(RichiTableState st) {
		String[] melds = Arrays.copyOf(st.melds, 4);
		for (int s = 0; s < 4; s++)
			if (!st.mirrorAll && st.players[s] != null && st.mirrorUuids.contains(st.players[s]))
				melds[s] = "";
		return melds;
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
	 * 桌面渲染应使用的房间类型：等待阶段用大厅配置（下一局），对局中/终局后固定为开局时捕获的
	 * {@link RichiTableState#gameRoomType}。
	 * <p>否则终局保留的残影在改房间类型后会按新类型重算透明牌位，客户端会把上一局的普通牌背
	 * 重建为玻璃牌背（或反之）。</p>
	 */
	private static int renderRoomType(RichiTableState st) {
		return st.phase == RichiTableState.PHASE_WAITING ? st.roomType : st.gameRoomType;
	}

	/**
	 * 玻璃之间：座位 s 被隐藏的手牌中，"透明牌"的显示位置掩码（bit k = 第 k 张手牌位）。
	 * <p>
	 * 透明牌的真实牌面本来就下发给观者（{@link #glassOnly}），真正会泄露的只有"它在理牌序列里的位置"：
	 * 若透明牌散落在未知牌之间，观者可由自动理牌顺序反推相邻未知牌的范围（例如某张透明 3m 左侧的牌必然小于 3m）。
	 * 因此把全部透明牌集中到手牌最左端（bit0..g-1）、未知牌背放在右侧，位置与牌值无关，
	 * 观者最多看出"哪几张是玻璃牌"，看不出未知牌的大小/种类。
	 * </p>
	 * <p>该掩码只决定显示位（服务端真实手牌顺序不变），且与观者无关、随本包手牌实时计算，
	 * 因此不再需要按观者随机洗牌：透明牌张数本就能从下发的牌面数得出。</p>
	 * <p>唯一例外是"刚摸入的透明牌"：它位于手牌末位的分离摸牌位，若也并进最左端，观者会看到
	 * 左侧玻璃区凭空多出一张、而真实摸入的那张反而变成不透明。因此保持原有透明牌（前 g-1 张）
	 * 的最左排列不变，把摸入的那张标在真实末位（bit n-1），使摸牌不改变已有手牌观感。</p>
	 */
	private static int glassLeftMask(RichiTableState st, int seat, int renderRoom) {
		if (renderRoom != RichiTableState.ROOM_GLASS)
			return 0;
		List<Integer> hand = st.handCodes(seat);
		int n = hand.size();
		int glassCount = 0;
		for (int c : hand)
			if (c >= 0 && mcr.riichi_arise.game.TileFlag.isGlass(c))
				glassCount++;
		int g = Math.min(n, glassCount);
		if (g <= 0)
			return 0;
		// 刚摸入的透明牌：前 g-1 张仍占最左端，摸入的那张标在真实末位（分离摸牌位）
		if (st.drawnSeat == seat && n <= 31 && n > 0
				&& hand.get(n - 1) >= 0 && mcr.riichi_arise.game.TileFlag.isGlass(hand.get(n - 1))) {
			int mask = 0;
			for (int k = 0; k < Math.min(g - 1, 31); k++)
				mask |= 1 << k;
			return mask | (1 << (n - 1));
		}
		return (int) ((1L << Math.min(g, 31)) - 1);
	}


	/** 每次广播前清空一次性解析缓存 */
	private static void beginBatch() {
		Arrays.fill(hiddenCache, -1);
		Arrays.fill(glassCache, null);
		doraCache = null;
		wallCache = -1;
	}

	/** 每次广播（同一张桌）内只解析一次：手牌张数、宝牌串、牌山张数 */
	private static final int[] hiddenCache = { -1, -1, -1, -1 };
	private static final String[] glassCache = new String[4];
	private static String doraCache;
	private static int wallCache = -1;

	/**
	 * 隐藏手牌串（玻璃之间；空串 = 铁之间）：透明牌按牌面顺序下发，
	 * 客户端结合 glassMask 把这些透明牌排到手牌最左端，其余手牌位保持未知（普通牌背）。
	 */
	private static String glassOnly(RichiTableState st, int seat, int renderRoom) {
		if (renderRoom != RichiTableState.ROOM_GLASS)
			return "";
		if (glassCache[seat] == null) {
			glassCache[seat] = st.glassHands[seat] == null ? "" : st.glassHands[seat];
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

	/** 座位显示名：人类 = 游戏档案名，AI = 流派 AI 名，空位 = 空串（与桌面/座位列表显示保持一致） */
	public static String seatName(ServerLevel level, RichiTableState st, int seat) {
		// 整桌投影：座位名用外部对局（雀魂）的玩家昵称
		String override = st.seatNameOverride[seat];
		if (override != null && !override.isEmpty())
			return override;
		ServerPlayer p = st.playerOf(level, seat);
		if (p != null)
			return p.getGameProfile().getName();
		if (st.aiSeat[seat])
			return mcr.riichi_arise.game.riichi.RiichiBot.aiName(seat);
		return "";
	}
}
