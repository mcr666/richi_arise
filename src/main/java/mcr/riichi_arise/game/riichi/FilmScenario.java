package mcr.riichi_arise.game.riichi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mcr.riichi_arise.game.MahjongTileNotation;

/**
 * 拍视频用剧本牌局（**临时功能**）：前 10 巡三家按预排顺序手切（不摸切）、全程不鸣牌；
 * 第 11 巡三家手牌瞬间换成目标牌，鹫巢岩/八木唯同巡立直、赤木茂默听，随后玩家（北家，座位 3）
 * 摸到最后一张发，打出发即三家荣和。
 * 另外：玩家一旦听牌，牌山不再给任何人摸到他所听的牌（见 RiichiWall#drawAvoiding）。
 *
 * <p>移除方式：删除本文件，再全局搜索 {@code film} 去掉 RiichiGame / RichiTableState / MahjongLobby /
 * MahjongCommands 里带「剧本」注释的钩子（共 6 处）。</p>
 */
public final class FilmScenario {
	/** 静默巡数：三家各手切 10 巡（玩家随意打） */
	public static final int QUIET_ROUNDS = 10;
	/** 静默摸牌数（4 家 × 10 巡） */
	public static final int QUIET_DRAWS = QUIET_ROUNDS * 4;
	/** 玩家座位（北家：三家先立直，玩家最后摸牌；也是"听牌封牌"的判定对象） */
	public static final int PLAYER_SEAT = 3;
	/** 不立直的座位（赤木茂：默听国士，等其他两家立直后再一起荣和发） */
	public static final int NO_RIICHI_SEAT = 2;

	/** 三家目标手牌（座位 0/1/2） */
	private static final String[] TARGET_HANDS = {
			"222333444666s6z", // 鹫巢岩：绿一色 + 四暗刻单骑，单骑听发
			"111222333444z6z", // 八木唯：字一色 + 大四喜 + 四暗刻单骑，单骑听发
			"119m19p19s1234z57z" // 赤木茂：国士无双，单骑听发
	};
	/** 三家角色 id（预约座位 0/1/2；与 {@link AiCharacters#IDS} 一致） */
	public static final String[] TARGET_CHARS = { "washizu", "bamuwei", "akagi" };

	/** 玩家起手 13 张：全是中张、无对子（远未听牌），且不含任何目标手牌的牌种 */
	private static final String PLAYER_HAND = "235668m2578p578s";
	/** 第 11 巡玩家摸到的牌：发（code 35 = 6z） */
	public static final int WIN_TILE = 35;
	/** 王牌第 1 叠固定为 5m 底张 / 6m 指示牌（宝牌 7m）：均为"安全牌种"，终局翻里宝也不会露出重复牌 */
	private static final int[] FORCED_DORA_STACK = { 5, 6 };

	/** 三家前 10 巡的手切牌（按打出顺序；已按"牌河预算"挑过，推倒时不会与目标手牌冲突） */
	private final int[][] quietDiscards = new int[3][QUIET_ROUNDS];
	/** 各家已打出的剧本手切牌数 */
	private final int[] quietDiscardIdx = new int[3];

	/** 剧本是否已把三家手牌换为目标牌 */
	private boolean injected;

	public boolean isInjected() {
		return injected;
	}

	/** 新一局重置 */
	public void reset() {
		injected = false;
	}

	/**
	 * 剧本下一次手切：返回该牌在手中的下标（找不到时退回最后一张）。
	 * 前 10 巡每家按预排顺序打出手牌，看起来是正常手切而不是摸切。
	 */
	public int nextDiscardIndex(List<Integer> hand, int seat) {
		if (seat < 0 || seat >= 3 || quietDiscardIdx[seat] >= QUIET_ROUNDS)
			return hand.size() - 1;
		int code = quietDiscards[seat][quietDiscardIdx[seat]++];
		int idx = hand.indexOf(Integer.valueOf(code));
		return idx >= 0 ? idx : hand.size() - 1;
	}

	/**
	 * 剧本牌山：按 摸牌顺序 返回 96 张（开局发 52 张 + 第 1..44 次摸牌）。
	 * <p>座位 0/1/2 起手 13 张 = 前 10 巡要手切的 10 张 + 3 张不可见的填充牌（第 11 巡整体换掉）；
	 * 三家全程手切，牌河只出现手切牌与立直宣言牌，但**三家 63 张（手切 30 + 静默摸牌 30 + 立直宣言 3）
	 * 全部按"牌河预算"挑**——因为"听牌封牌"换牌时可能把这些牌换进任何位置，统一合法才不会露第 5 张；
	 * 玩家起手与 10 张摸牌固定取安全牌种；第 44 张摸牌是发。</p>
	 */
	public List<Integer> buildDrawOrder() {
		List<Integer> pool = fullSet();
		// 王牌第 1 叠（5m/6m）会一直可见：先从牌池扣掉，避免与牌河/玩家手牌凑出第 5 张
		for (int code : FORCED_DORA_STACK)
			pool.remove(Integer.valueOf(code));

		// 1. 玩家起手 13 张（固定）
		List<Integer> playerHand = new ArrayList<>();
		for (int code : MahjongTileNotation.parse(PLAYER_HAND))
			if (pool.remove(Integer.valueOf(code)))
				playerHand.add(code);

		// 2. 三家 63 张：优先幺九/字牌（更像真实手切），不足用安全中张补；
		//    每种牌的可用张数 = 4 - 目标手牌占用（東南西北被占满 → 牌河永不出现）
		List<Integer> preferred = new ArrayList<>();
		for (int code = 1; code < 37; code++) {
			if (code == 10 || code == 20)
				continue; // 红五不进牌河，避免牌河出现第 4/5 张五
			if (isSafe(code))
				continue;
			for (int i = 0; i < riverCap(code); i++)
				preferred.add(code);
		}
		Collections.shuffle(preferred);
		List<Integer> fallback = new ArrayList<>();
		for (int code : pool)
			if (isSafe(code))
				fallback.add(code);
		Collections.shuffle(fallback);
		List<Integer> aiTiles = new ArrayList<>(preferred);
		int need = 3 * QUIET_ROUNDS /* 手切牌 */ + 3 * QUIET_ROUNDS /* 静默摸牌 */
				+ 3 /* 立直宣言摸牌 */;
		while (aiTiles.size() < need)
			aiTiles.add(fallback.remove(0));
		Collections.shuffle(aiTiles);
		for (int code : aiTiles)
			pool.remove(Integer.valueOf(code));

		// 3. 玩家 10 张摸牌：只取安全牌种（玩家可能留牌，终局手牌也是可见的）
		List<Integer> safeCandidates = new ArrayList<>();
		for (int code : pool)
			if (isSafe(code))
				safeCandidates.add(code);
		Collections.shuffle(safeCandidates);
		List<Integer> playerDraws = new ArrayList<>(safeCandidates.subList(0, QUIET_ROUNDS));
		for (int code : playerDraws)
			pool.remove(Integer.valueOf(code));

		// 4. 第 44 次摸牌：发（玩家）
		pool.remove(Integer.valueOf(WIN_TILE));

		// 5. 分配：前 30 张作手切牌，中 30 张静默摸牌，最后 3 张立直宣言摸牌
		List<Integer> handCut = new ArrayList<>(aiTiles.subList(0, 3 * QUIET_ROUNDS));
		List<Integer> quietDraws = new ArrayList<>(
				aiTiles.subList(3 * QUIET_ROUNDS, 3 * QUIET_ROUNDS + 3 * QUIET_ROUNDS));
		List<Integer> riichiDraws = new ArrayList<>(
				aiTiles.subList(3 * QUIET_ROUNDS + 3 * QUIET_ROUNDS, aiTiles.size()));

		// 6. 手切牌按"同一牌种尽量不落在同一家牌河"分配
		for (int seat = 0; seat < 3; seat++)
			quietDiscardIdx[seat] = 0;
		int[] next = new int[3];
		for (int code : handCut) {
			int bestSeat = -1, least = Integer.MAX_VALUE;
			for (int s = 0; s < 3; s++) {
				if (next[s] >= QUIET_ROUNDS)
					continue;
				int same = 0;
				for (int i = 0; i < next[s]; i++)
					if (quietDiscards[s][i] == code)
						same++;
				// 先避开重复牌种，再取张数最少的一家（轮转均匀）
				int score = same * 100 + next[s];
				if (score < least) {
					least = score;
					bestSeat = s;
				}
			}
			if (bestSeat < 0)
				break;
			quietDiscards[bestSeat][next[bestSeat]++] = code;
		}

		// 7. 三家起手的 3 张填充牌（不可见，第 11 巡随整手换掉）
		Collections.shuffle(pool);
		List<Integer> fillers = new ArrayList<>(pool.subList(0, 9));

		List<Integer> order = new ArrayList<>(96);
		for (int seat = 0; seat < 3; seat++) {
			for (int i = 0; i < QUIET_ROUNDS; i++)
				order.add(quietDiscards[seat][i]);
			for (int i = 0; i < 3; i++)
				order.add(fillers.remove(0));
		}
		order.addAll(playerHand);
		int k = 0;
		for (int round = 0; round < QUIET_ROUNDS; round++) {
			for (int seat = 0; seat < 3; seat++)
				order.add(quietDraws.get(k++));
			order.add(playerDraws.get(round));
		}
		order.addAll(riichiDraws); // 第 11 巡三家的摸牌（立直宣言牌，摸切打出）
		order.add(WIN_TILE);
		return order;
	}

	/** 剧本牌山（含固定王牌第 1 叠） */
	public RiichiWall buildWall() {
		return new RiichiWall(buildDrawOrder(), FORCED_DORA_STACK);
	}

	/** 第 11 巡开局前调用：把三家手牌瞬间换为目标牌（对局中玩家看不到他家手牌，替换不可见） */
	public void inject(RiichiPlayer[] ps) {
		for (int seat = 0; seat < 3; seat++) {
			RiichiPlayer p = ps[seat];
			p.hand.clear();
			p.hand.addAll(MahjongTileNotation.parse(TARGET_HANDS[seat]));
			p.sortHand();
		}
		injected = true;
	}

	/** 该牌种不在三家目标手牌里（牌头随机填充段换牌只允许这种牌，避免终局推倒时露出第 5 张） */
	public static boolean isNeutral(int code) {
		return riverCap(code) >= 4;
	}

	/** 牌河里该牌最多能出现的张数 = 4 - 三家目标手牌占用；发固定留给玩家摸，不进牌河 */
	private static int riverCap(int code) {
		if (code == WIN_TILE)
			return 0;
		int used = 0;
		for (String h : TARGET_HANDS)
			for (int c : MahjongTileNotation.parse(h))
				if (c == code)
					used++;
		return Math.max(0, 4 - used);
	}

	/** 安全牌种：2-8 万、2-8 饼、5/7/8 索（不含三家目标手牌与发） */
	private static boolean isSafe(int code) {
		if (code >= 30)
			return false;
		int suit = code / 10;
		int digit = code % 10;
		if (suit < 2)
			return digit >= 2 && digit <= 8;
		return digit == 5 || digit == 7 || digit == 8;
	}

	/** 完整 136 张（与 {@link RiichiWall} 同款：每种 4 张，五只有 3 张普通 + 1 张红五） */
	private static List<Integer> fullSet() {
		List<Integer> pool = new ArrayList<>(136);
		for (int base : new int[] { 0, 10, 20 }) {
			for (int d = 1; d <= 9; d++)
				for (int i = 0; i < (d == 5 ? 3 : 4); i++)
					pool.add(base + d);
			pool.add(base); // 红五
		}
		for (int d = 1; d <= 7; d++)
			for (int i = 0; i < 4; i++)
				pool.add(29 + d);
		return pool;
	}
}
