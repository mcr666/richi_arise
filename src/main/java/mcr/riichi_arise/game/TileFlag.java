package mcr.riichi_arise.game;

/**
 * 牌面标志位：透明牌（玻璃之间）与万能牌（青金石之间）都记在牌面代码的高位。
 * <p>牌面代码本身只占低 8 位（0..36），第 {@link #GLASS} 位置 1 表示这张物理牌是透明牌；
 * 万能牌（青金石之间）用独立码值 {@link #WILD_BASE}（37，0..36 已满）+ {@link #WILD} 标志表示。</p>
 * <p>标志随牌（摸/打/鸣/杠）在整局中保持稳定。所有对牌面做算术（花色/数字/红五判定）或送入
 * AI/和牌判定前，必须先用 {@link #base(int)} 归一；只做同牌比较（列表增删/相等）时可直接带标志。</p>
 */
public final class TileFlag {
	/** 透明牌标志位（不与 0..36 的牌面代码冲突） */
	public static final int GLASS = 0x100;
	/** 万能牌标志位（青金石之间；与 {@link #WILD_BASE} 组合成万能牌码） */
	public static final int WILD = 0x200;
	/** 万能牌的牌面基值（0..36 已被普通牌占满，故取 37） */
	public static final int WILD_BASE = 37;

	private TileFlag() {
	}

	/** 去掉透明/万能标志，得到普通牌面代码（-1 未知牌原样返回） */
	public static int base(int code) {
		return code < 0 ? code : code & ~(GLASS | WILD);
	}

	/** 是否为透明牌 */
	public static boolean isGlass(int code) {
		return code >= 0 && (code & GLASS) != 0;
	}

	/** 是否为万能牌（青金石之间） */
	public static boolean isWild(int code) {
		return code >= 0 && (code & WILD) != 0;
	}

	/** 构造一张万能牌码 */
	public static int wild() {
		return WILD | WILD_BASE;
	}

	/** 给牌面代码打上透明标志（-1 未知牌原样返回） */
	public static int glass(int code) {
		return code < 0 ? code : base(code) | GLASS;
	}

	/** 同种牌判定（忽略透明/万能标志） */
	public static boolean same(int a, int b) {
		return base(a) == base(b);
	}
}
