package mcr.riichi_arise.game.riichi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 牌山（纯数据）：四麻 136 张 / 三麻 108 张洗牌；摸牌从牌尾取（draw）。
 * 王牌区 14 张从牌头预留、不参与普通摸牌：10 张宝牌指示区（5 叠×2）+ 4 张岭上牌（杠后补牌）。
 * 杠后新宝：指示牌揭开下一叠（revealedIndicators +1）。
 */
public class RiichiWall {
	/** 牌山（摸牌取尾部，136/108 - 王牌 14 张） */
	private final List<Integer> wall = new ArrayList<>(136);
	/** 宝牌指示区 10 张（5 叠 × 2 张，叠序存放：2i=底张 2i+1=上张/指示牌） */
	private final List<Integer> doraStacks = new ArrayList<>(10);
	/** 岭上牌 4 张（王牌区另一端，杠后补牌专用，普通摸牌不可触及） */
	private final List<Integer> rinshanTiles = new ArrayList<>(4);
	/** 已揭开的指示牌叠数（开局 1，每次杠 +1） */
	private int revealedIndicators = 1;
	/** 三麻将牌集：无 2m~8m（万子仅 1/9）与红五万，共 108 张（雀魂三麻） */
	private final boolean sanma;
	/** 玻璃之间：无红宝牌；每种牌 4 张中随机 3 张标记为透明牌（对局中固定） */
	private final boolean glassMode;
	/** 剧本牌山：编排段在 wall 中的起始下标（此段之前是随机填充，不参与剧本换牌） */
	private int tailFrom;

	public RiichiWall() {
		this(4, false);
	}

	public RiichiWall(int playerCount) {
		this(playerCount, false);
	}

	/**
	 * 按对局人数构建牌山。playerCount=3（三麻）时：
	 * 万子只留 1m、9m 各 4 张（移除 2m~8m），无红五万；筒/索/字照旧 → 总数 108 张。
	 * 王牌区仍 14 张（指示区 10 + 岭上 4）；岭上耗尽时杠/拔北由对局层禁止（见 RiichiGame）。
	 * glassMode（玻璃之间）：不放入任何红五（每花色 5 共 4 张普通五），
	 * 每种牌随机留 1 张不透明、其余 3 张打上 {@link mcr.riichi_arise.game.TileFlag#GLASS} 标志。
	 */
	public RiichiWall(int playerCount, boolean glassMode) {
		this.sanma = playerCount == 3;
		this.glassMode = glassMode;
		boolean redFive = !glassMode;
		if (sanma) {
			for (int d : new int[] { 1, 9 })
				for (int i = 0; i < 4; i++)
					wall.add(d); // 万子仅 1m/9m，无红五万
		} else {
			for (int d = 1; d <= 9; d++)
				// 每种 4 张；5 只放 3 张——第 4 张五由红五（0m/0p/0s）取代，保证总数 136 且每花色五共 4 张
				for (int i = 0; i < (d == 5 && redFive ? 3 : 4); i++)
					wall.add(d);
			if (redFive)
				wall.add(0); // 红五万
		}
		for (int base : new int[] { 10, 20 }) {
			for (int d = 1; d <= 9; d++)
				for (int i = 0; i < (d == 5 && redFive ? 3 : 4); i++)
					wall.add(base + d);
			if (redFive)
				wall.add(base); // 红五筒/索（三麻保留）
		}
		for (int d = 1; d <= 7; d++)
			for (int i = 0; i < 4; i++)
				wall.add(29 + d);
		if (glassMode)
			markGlassCopies();
		Collections.shuffle(wall);
		// 王牌区从牌头预留 14 张：10 张指示区 + 4 张岭上（均不参与普通摸牌）
		for (int i = 0; i < 10; i++)
			doraStacks.add(wall.remove(0));
		for (int i = 0; i < 4; i++)
			rinshanTiles.add(wall.remove(0));
	}

	/** 玻璃之间：每种牌（同牌面代码）的 4 张里随机挑 1 张保持不透明，其余 3 张打透明标志 */
	private void markGlassCopies() {
		java.util.Map<Integer, java.util.List<Integer>> groups = new java.util.LinkedHashMap<>();
		for (int i = 0; i < wall.size(); i++)
			groups.computeIfAbsent(wall.get(i), k -> new ArrayList<>()).add(i);
		for (java.util.List<Integer> idx : groups.values()) {
			Collections.shuffle(idx);
			for (int k = 1; k < idx.size(); k++) {
				int i = idx.get(k);
				wall.set(i, mcr.riichi_arise.game.TileFlag.glass(wall.get(i)));
			}
		}
	}

	/** 是否玻璃之间（无红五、每张牌 3 透明 1 不透明） */
	public boolean isGlassMode() {
		return glassMode;
	}

	/**
	 * 剧本牌山（拍视频用，临时）：drawOrder 为从牌尾开始依次会被摸到的牌（下标 0 最先摸到）。
	 * 其余牌随机垫在牌头；王牌区仍按原规则从牌头预留，但第 1 叠（底张 / 指示牌）固定为
	 * forcedDoraStack[0] / [1]（避免剧本里露出会重复的牌）。某张牌不足时静默跳过，不中断牌局。
	 */
	public RiichiWall(List<Integer> drawOrder, int[] forcedDoraStack) {
		this.sanma = false; // 剧本牌山固定按四麻将牌集编排（136 张）
		this.glassMode = false; // 剧本牌山不使用透明牌
		for (int base : new int[] { 0, 10, 20 }) {
			for (int d = 1; d <= 9; d++)
				for (int i = 0; i < (d == 5 ? 3 : 4); i++)
					wall.add(base + d);
			wall.add(base);
		}
		for (int d = 1; d <= 7; d++)
			for (int i = 0; i < 4; i++)
				wall.add(29 + d);
		for (int code : drawOrder)
			wall.remove(Integer.valueOf(code));
		int bottom = forcedDoraStack.length > 0 ? forcedDoraStack[0] : -1;
		int indicator = forcedDoraStack.length > 1 ? forcedDoraStack[1] : -1;
		if (bottom >= 0)
			wall.remove(Integer.valueOf(bottom));
		if (indicator >= 0)
			wall.remove(Integer.valueOf(indicator));
		Collections.shuffle(wall);
		doraStacks.add(bottom >= 0 ? bottom : wall.remove(0));
		doraStacks.add(indicator >= 0 ? indicator : wall.remove(0));
		for (int i = 2; i < 10; i++)
			doraStacks.add(wall.remove(0));
		for (int i = 0; i < 4; i++)
			rinshanTiles.add(wall.remove(0));
		// 反序压到牌尾：drawOrder[0] 位于列表最末，第一次 draw() 就摸到
		for (int i = drawOrder.size() - 1; i >= 0; i--)
			wall.add(drawOrder.get(i));
		tailFrom = wall.size() - drawOrder.size();
	}

	/** 摸一张（牌尾），空返回 -1 */
	public int draw() {
		return wall.isEmpty() ? -1 : wall.remove(wall.size() - 1);
	}

	/**
	 * 剧本用：摸一张，且保证不摸到 forbidden 命中的牌——命中时与牌尾附近一张可换的牌对调
	 * （违规牌被压向牌山深处，之后仍可能被换回；每次只摸走 1 张，不改变各家摸牌张数）。
	 * 换牌优先在剧本编排段内找；编排段不够时才用牌头随机段的牌，且该牌必须满足 fillerOk
	 * （避免把终局会撞车的牌种换进牌河）。keep = 剧本最后必须摸到的那张牌，永不参与对调。
	 */
	public int drawAvoiding(java.util.function.IntPredicate forbidden,
			java.util.function.IntPredicate fillerOk, int keep) {
		if (wall.isEmpty())
			return -1;
		int tail = wall.size() - 1;
		if (forbidden.test(wall.get(tail))) {
			for (int i = tail - 1; i >= 0; i--) {
				int code = wall.get(i);
				if (code == keep || forbidden.test(code))
					continue;
				if (i < tailFrom && !fillerOk.test(code))
					continue; // 随机填充段只允许"不会与终局手牌撞车"的牌种
				Collections.swap(wall, i, tail);
				break;
			}
		}
		return wall.remove(wall.size() - 1);
	}

	/**
	 * 岭上摸牌（杠/拔北后补牌）：从王牌区岭上段取一张，同时从牌山底（远离摸牌端的一端）
	 * 补一张进王牌，保证王牌区恒为 14 张（牌山总数因此减少 1 张，与日麻规则一致）。
	 * 王牌耗尽（牌山已空、无法补充）返回 -1。
	 */
	public int rinshanDraw() {
		if (rinshanTiles.isEmpty())
			return -1;
		int code = rinshanTiles.remove(rinshanTiles.size() - 1);
		if (!wall.isEmpty())
			rinshanTiles.add(0, wall.remove(0)); // 牌山底补进王牌（补在岭上段最深处，不改变后续摸牌顺序）
		return code;
	}

	/** 岭上剩余张数（三麻拔北/杠前须检查，耗尽则对局层禁止该操作） */
	public int rinshanRemaining() {
		return rinshanTiles.size();
	}

	/** 是否三麻将牌集（108 张，无 2m~8m 与红五万） */
	public boolean isSanma() {
		return sanma;
	}

	public boolean isEmpty() {
		return wall.isEmpty();
	}

	public int remaining() {
		return wall.size();
	}

	/** 当前有效指示牌列表（已揭开的上张；注意是"指示牌"而非宝牌本身） */
	public List<Integer> revealedDoras() {
		List<Integer> doras = new ArrayList<>();
		for (int i = 0; i < revealedIndicators && 2 * i + 1 < doraStacks.size(); i++)
			doras.add(doraStacks.get(2 * i + 1));
		return doras;
	}

	/** 当前有效宝牌列表（指示牌 → 指示牌下一张，供计分与显示；按本牌山规则映射） */
	public List<Integer> actualDoras() {
		List<Integer> doras = new ArrayList<>();
		for (int ind : revealedDoras())
			doras.add(doraTileOf(ind));
		return doras;
	}

	/** 里宝牌列表（已揭开指示牌的同叠底张；立直和牌时才计） */
	public List<Integer> uraDoras() {
		List<Integer> ura = new ArrayList<>();
		for (int i = 0; i < revealedIndicators && 2 * i < doraStacks.size(); i++)
			ura.add(doraTileOf(doraStacks.get(2 * i)));
		return ura;
	}

	/** 里指示牌牌面（已揭开叠数的底张原牌，供结算界面翻开展示） */
	public List<Integer> uraIndicatorTiles() {
		List<Integer> ura = new ArrayList<>();
		for (int i = 0; i < revealedIndicators && 2 * i < doraStacks.size(); i++)
			ura.add(doraStacks.get(2 * i));
		return ura;
	}

	/** 指示牌 → 宝牌（本牌山规则）：三麻万子 1↔9 环状互推，其余同静态四麻映射。
	 *  对局层（RiichiGame）也须用本方法而非静态版，保证三麻宝牌映射一致 */
	public int doraTileOf(int indicator) {
		indicator = mcr.riichi_arise.game.TileFlag.base(indicator);
		if (sanma && indicator < 30 && indicator / 10 == 0) {
			int digit = indicator % 10 == 0 ? 5 : indicator % 10;
			return digit == 1 ? 9 : 1; // 三麻万子仅 1/9：一万↔九万互推（红五万不存在，digit=5 防御归 1）
		}
		return doraTileOfStatic(indicator);
	}

	/** 指示牌 → 宝牌（下一张，四麻标准映射）：万饼索 9 绕回 1；风牌东南西北循环；三元牌白→发→中→白循环 */
	public static int doraTileOfStatic(int indicator) {
		indicator = mcr.riichi_arise.game.TileFlag.base(indicator);
		if (indicator >= 30) {
			int d = indicator - 30;
			if (d < 4)
				return 30 + (d + 1) % 4; // 東南西北循环
			return 34 + (d - 4 + 1) % 3; // 白发中循环
		}
		int suit = indicator / 10;
		int digit = indicator % 10 == 0 ? 5 : indicator % 10;
		int base = suit == 0 ? 0 : suit == 1 ? 10 : 20;
		return base + (digit % 9) + 1;
	}

	/** 杠后揭新宝 */
	public void revealNextDora() {
		if (revealedIndicators < 5)
			revealedIndicators++;
	}

	public int revealedIndicatorCount() {
		return revealedIndicators;
	}

	/** 宝牌区 10 张（渲染用） */
	public List<Integer> doraStackTiles() {
		return new ArrayList<>(doraStacks);
	}

	/** 岭上牌 4 张（牌谱记录/回放重建用） */
	public List<Integer> rinshanStackTiles() {
		return new ArrayList<>(rinshanTiles);
	}

	/** 牌山剩余牌（写回状态渲染/显示枚数用） */
	public List<Integer> remainingCodes() {
		return new ArrayList<>(wall);
	}
}
