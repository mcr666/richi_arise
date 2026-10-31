package mcr.riichi_arise.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import mcr.riichi_arise.block.FengPanBlock;
import mcr.riichi_arise.block.FengPanSmallBlock;
import mcr.riichi_arise.game.RichiTableState;

/**
 * 风盘桌面公共布局（服务端交互代理与客户端渲染共用）：
 * 桌面几何（{@link Geom}）、手牌位置（{@link #handTarget}）、整桌摆位计划
 * （{@link #plan} → {@link Placement} 列表，含座位标签/手牌/牌河/副露/点棒/宝牌叠的位置与延迟）。
 * 服务端只取 HAND 立牌项生成 Interaction 代理；客户端按全部项生成 client-side 显示实体。
 * handCodes 由调用方传入（服务端传真实手牌、客户端传包内过滤后手牌，隐藏座位传 -1 牌背）。
 */
public final class TableLayout {
	private TableLayout() {
	}

	/** 座位名（家序 0..3） */
	public static final String[] SEAT_NAMES = { "東", "南", "西", "北" };
	/** 每家手牌张数（发牌 13 张） */
	public static final int HAND_SIZE = 13;
	/** 牌河内排每排张数（6 张一排） */
	public static final int RIVER_COLS = 6;
	/** 牌河内排排数（内排三行每排 6 张、从最靠桌心逐排向玩家；第 4 排起不再换行，一直往右延伸） */
	public static final int RIVER_ROWS = 3;
	/** 牌河相邻两排排距（格，≈平躺牌长 0.2083 + 缝） */
	public static final double RIVER_ROW_PITCH = 0.21;
	/** 牌河最靠桌心一排（第 RIVER_ROWS 排）行中心距桌心（格） */
	public static final double RIVER_DIST = 0.65;
	/** 中央宝牌指示牌叠数（日麻规则 5 叠） */
	public static final int DORA_STACKS = 5;
	/** 平躺牌厚度（格）＝模型厚 6px × 缩放 */
	public static final double FLAT_THICKNESS = 6 * (2.5 / 12.0) / 16;
	/** 平躺牌宽（格）＝模型宽 12px × 缩放（副露横牌紧贴计算用） */
	public static final double TILE_WIDTH = 12 * (2.5 / 12.0) / 16;
	/** 平躺牌长（格）＝模型长 16px × 缩放 */
	public static final double TILE_LONG = 16 * (2.5 / 12.0) / 16;
	/** 手牌行离桌边距离（格） */
	public static final double EDGE_INSET = 0.35;
	/** 小型风盘手牌/副露区额外外移量（格，3 个模型单位 = 3/16 格） */
	public static final double SMALL_OUTWARD = 3.0 / 16.0;
	/** 小型风盘手牌区整体向玩家左侧（沿 lefts）偏移量（格，2 个模型单位 = 2/16 格） */
	public static final double SMALL_HAND_LEFT_SHIFT = 2.0 / 16.0;
	/** 副露区外缘与桌沿之间保留的间隙（格，1 个模型单位；仅小型 3×3 桌贴沿时使用） */
	public static final double MELD_EDGE_GAP = 1.0 / 16.0;
	/** 副露行向桌心内收距离（格）——大风盘沿用原布局：与手牌行错开 z 带，避免占用同一条线 */
	public static final double MELD_ROW_INSET = 0.20;
	/** 大风盘副露区外缘上限（格，沿玩家右侧轴）：邻家行列带从 ±(edge - TILE_WIDTH/2)=±1.572 起，留隙取 1.55 */
	public static final double MELD_EDGE_CAP = 1.55;
	/** 手牌相邻牌间距（格）——牌宽 12px×s/16≈0.15625，紧贴（+0.0007 防共面闪烁） */
	public static final double TILE_GAP = 0.157;
	/** 相邻副露组之间的最小间隙（格） */
	public static final double MELD_GAP = 0.02;
	/** 发牌间隔（tick） */
	public static final int DEAL_INTERVAL_TICKS = 1;
	/** 牌渲染缩放：模型宽度 12px（第二窄边），目标 2.5 单位 → 2.5/12 */
	public static final float TILE_SCALE = 2.5f / 12f;
	/** 座位标记离桌心距离（格，水平四方向向外） */
	public static final double SEAT_DISTANCE = 3.5;
	/** 座位标记高度（格，桌面上方） */
	public static final double SEAT_HEIGHT = 2.0;
	/** 选牌抬升高度（格）＝立牌高（16px×s/16）的一半 */
	public static final double SELECT_LIFT = TILE_SCALE / 2.0;

	//摆位类别（Placement.kind）
	public static final String KIND_SEAT = "seat";
	public static final String KIND_HAND = "hand";
	public static final String KIND_RIVER = "river";
	public static final String KIND_MELD = "meld";
	public static final String KIND_KITA = "kita";
	public static final String KIND_STICK = "stick";
	public static final String KIND_DORA = "dora";

	/** 北风牌面代码（记法 4z，拔北牌；与 RiichiGame.TILE_PEI 一致） */
	public static final int TILE_PEI = 33;

	/** 桌面几何：桌心 + f/r 方向 + 各家行中心/切线/朝外/左手方向 + 是否小型风盘（紧凑 3×3 布局） */
	public record Geom(Vec3 center, Vec3 f, Vec3 r, Vec3[] rows, Vec3[] tangents, Vec3[] outs, Vec3[] lefts,
			boolean small) {
		/** 手牌行中心距桌心（正常 1.65 / 小型 3×3 = 1.5−EDGE_INSET+SMALL_OUTWARD = 1.3375） */
		public double edge() {
			return edgeFor(small);
		}

		/** 座位站位距桌心（正常 3.5 / 小型 2.5） */
		public double seatDist() {
			return small ? 2.5 : SEAT_DISTANCE;
		}

		/** 牌河最靠桌心一排行中心距桌心（与大风盘一致，小型不再收窄） */
		public double riverDist() {
			return RIVER_DIST;
		}

		/** 副露区外缘上限：小型紧贴桌沿只留 MELD_EDGE_GAP；大风盘沿用原上限 MELD_EDGE_CAP */
		public double meldEdgeCap() {
			return small ? 1.5 - MELD_EDGE_GAP : MELD_EDGE_CAP;
		}

		/** 副露行中心距桌心：小型贴玩家侧桌沿（外缘距沿 MELD_EDGE_GAP）；大风盘沿用原内收布局 */
		public double meldRowDist() {
			return small
					? 1.5 - MELD_EDGE_GAP - TILE_LONG / 2.0
					: edgeFor(false) - MELD_ROW_INSET;
		}

		/** 第 rowIdx 排（内排 0 = 最靠玩家）行中心距桌心：内排增大即向桌心推进；第 RIVER_ROWS 排起为溢出行，置于最外内排外侧 */
		public double riverRowDist(int rowIdx) {
			if (rowIdx >= RIVER_ROWS)
				return riverDist() + RIVER_ROWS * RIVER_ROW_PITCH;
			return riverDist() + (RIVER_ROWS - 1 - rowIdx) * RIVER_ROW_PITCH;
		}
	}

	/**
	 * 整桌摆位项：kind 见 KIND_* 常量；pos = 显示实体位置（已含贴桌/抬升偏移）；
	 * base = 桌面基准点（交互代理定位/倒牌级联的立牌位置）；code = 牌面（-1 = 未知牌背）；
	 * yRot = 实体朝向；axis = 平躺长轴（null = 立牌）；faceUp = 平躺牌面朝向；
	 * seat/index = 座位与牌序（非座位项为 -1）；delay = 发牌动画延迟（tick，animate=false 时全 0）。
	 */
	public record Placement(String kind, Vec3 pos, Vec3 base, int code, float yRot, Vec3 axis, boolean faceUp,
			int seat, int index, int delay) {
	}

	/** 手牌行中心距桌心（格）：正常 4×4 尺度 1.65 / 小型 3×3 桌 1.15 再外移 SMALL_OUTWARD */
	private static double edgeFor(boolean small) {
		return (small ? 1.5 + SMALL_OUTWARD : 2.0) - EDGE_INSET;
	}

	/** 由方块状态+桌角原点推桌面几何（家序 0=東（远侧）→1=南（右侧）→2=西（近侧）→3=北（左侧）） */
	public static Geom of(BlockGetter level, BlockPos origin) {
		BlockState anyState = level.getBlockState(origin);
		Direction forward = anyState.getValue(FengPanBlock.FACING);
		Direction right = forward.getClockWise();
		Vec3 f = new Vec3(forward.getStepX(), 0, forward.getStepZ());
		Vec3 r = new Vec3(right.getStepX(), 0, right.getStepZ());
		boolean small = anyState.getBlock() instanceof FengPanSmallBlock;
		// 桌面基准 = 风盘方块顶面（y=origin.y）；小型风盘搭在下半砖上时整桌再下移半格（与模型一起贴合砖面）
		double tableY = origin.getY() - (small ? FengPanSmallBlock.slabDrop(anyState) : 0);
		// 桌心：2x2 风盘取区域几何中心、小型单方块取方块中心
		Vec3 center = small
				? new Vec3(origin.getX() + 0.5, tableY, origin.getZ() + 0.5)
				: new Vec3(
						origin.getX() + (forward.getStepX() + right.getStepX() + 1) / 2.0,
						origin.getY(),
						origin.getZ() + (forward.getStepZ() + right.getStepZ() + 1) / 2.0);
		double edge = edgeFor(small); // 行中心距桌心：正常 1.65 / 小型 1.3375（3×3 桌手牌/副露外移 3/16 格）
		Vec3[] rows = { center.subtract(f.scale(edge)), center.add(r.scale(edge)),
				center.add(f.scale(edge)), center.subtract(r.scale(edge)) };
		Vec3[] tangents = { r, f, r, f }; // 行中心沿 f/r 偏移，牌列沿切线（另一轴）展开
		Vec3[] outs = { f.scale(-1), r, f, r.scale(-1) }; // 各家朝外方向
		// 各家视角的"左"方向：面朝桌心（fc = -out）时左 = (fc.z, -fc.x) = (-out.z, out.x)
		// 手牌/牌河统一按各家玩家视角排布（西北家与东家切线方向相反，直接用切线会一半反）
		Vec3[] lefts = new Vec3[4];
		for (int h = 0; h < 4; h++)
			lefts[h] = new Vec3(-outs[h].z, 0, outs[h].x);
		return new Geom(center, f, r, rows, tangents, outs, lefts, small);
	}

	/**
	 * 某家第 index 张手牌的位置（渲染/选牌判定/摸牌共用）：按各家玩家视角左对齐——
	 * index 0 在玩家左手端，index 增大向玩家右侧（1m..9m 从左到右）；
	 * 刚摸的牌（index ≥ 13，无副露时）落在手牌区最右端外，隔开半个牌宽（+0.078）。
	 */
	public static Vec3 handTarget(Geom geom, int seat, int index) {
		return handTarget(geom, seat, index, index >= HAND_SIZE);
	}

	/** 同上（drawnGap = 该张为刚摸的牌：有副露时摸牌 index &lt; 13，由调用方按 drawnSeat 判定） */
	public static Vec3 handTarget(Geom geom, int seat, int index, boolean drawnGap) {
		// 刚摸的牌与手牌之间的额外间隔：大小风盘统一隔半个牌宽
		double gap = TILE_WIDTH / 2.0;
		double offset = (HAND_SIZE - 1) / 2.0 * TILE_GAP - index * TILE_GAP - (drawnGap ? gap : 0);
		if (geom.small())
			offset += SMALL_HAND_LEFT_SHIFT; // 小型风盘：整排手牌再向玩家左侧偏移 2 个模型单位
		return geom.rows()[seat].add(geom.lefts()[seat].scale(offset));
	}

	/** 牌河会换行的内排数：大风盘 3 排；小型风盘只排 2 排，第三排起就一直往右延伸 */
	private static int riverRowCount(boolean small) {
		return small ? 2 : RIVER_ROWS;
	}

	/**
	 * 牌河第 index 张所在排：内排 0 = 最靠玩家、RIVER_ROWS-1 = 最靠桌心，从内侧（靠桌心）往外排；
	 * 大风盘：前 RIVER_ROWS*RIVER_COLS（=18）张排满内排后不再新增排，第 4 排起统一返回 RIVER_ROWS，一直往右延伸；
	 * 小型风盘：前 2*RIVER_COLS（=12）张排满两排后不再换行，第三排（row = 0，最靠玩家）起就一直往右。
	 */
	public static int riverRow(int index, boolean small) {
		int rows = riverRowCount(small);
		if (index < rows * RIVER_COLS)
			return RIVER_ROWS - 1 - index / RIVER_COLS; // 内排：先内（靠桌心）后外（靠玩家）
		return small ? 0 : RIVER_ROWS; // 排满后：小型就地第三排（最靠玩家一行）往右，大风盘另起溢出行
	}

	/**
	 * 牌河第 index 张在本排中的列序（沿玩家视角自左向右，0 起）：内排 0..RIVER_COLS-1；
	 * 溢出排前 RIVER_COLS 张与内排对齐（列 0..RIVER_COLS-1），之后自第 RIVER_COLS 列续增，一直往右（不再折排）。
	 */
	public static int riverCol(int index, boolean small) {
		int rows = riverRowCount(small);
		if (index < rows * RIVER_COLS)
			return index % RIVER_COLS;
		return index - rows * RIVER_COLS; // 溢出排：先补齐本排前 6 列，再继续往右
	}

	/**
	 * 牌河第 index 张的桌面位置（内排每排 6 张、自最靠桌心一排起逐排向玩家（从内往外）、每排沿玩家视角自左向右排；
	 * 大风盘 3 排共 18 张排满后另起第 4 排一直往右延伸，小型风盘 2 排共 12 张排满后就在第三排一直往右；结算粒子定位用）。
	 */
	public static Vec3 riverTarget(Geom geom, int seat, int index) {
		return geom.rows()[seat]
				.subtract(geom.outs()[seat].scale(geom.edge() - geom.riverRowDist(riverRow(index, geom.small()))))
				.add(geom.lefts()[seat].scale(((RIVER_COLS - 1) / 2.0 - riverCol(index, geom.small())) * 0.17));
	}

	/** 手牌定位标签（显示实体与交互代理共用），选中升降时按标签精确找实体 */
	public static String handTag(int seat, int index) {
		return "richi_hand_i_" + seat + "_" + index;
	}

	/** 平躺牌实体 yaw（含读向修正：长轴沿 X（东西向）的牌与沿 Z 的相反朝向才符合读牌方向） */
	public static float flatYaw(Vec3 longAxis) {
		Vec3 a = longAxis.x != 0 ? longAxis.scale(-1) : longAxis;
		return (float) Math.toDegrees(Math.atan2(-a.x, -a.z));
	}

	/** 立直点棒 yaw（无读向修正，与原 spawnRiichiStick 一致） */
	public static float stickYaw(Vec3 longAxis) {
		return (float) Math.toDegrees(Math.atan2(-longAxis.x, -longAxis.z));
	}

	/** 平躺牌显示实体位置：实体位置即牌体中心，牌底贴 target.y + lift（叠放时上张 lift=FLAT_THICKNESS） */
	public static Vec3 flatEntityPos(Vec3 target, double lift) {
		return new Vec3(target.x, target.y + 3 * TILE_SCALE / 16 + lift, target.z);
	}

	/** 立牌显示实体位置：牌底贴 tableY 上方 8px×s/16 处（模型半高） */
	public static Vec3 standingEntityPos(Vec3 tablePos) {
		return new Vec3(tablePos.x, tablePos.y + 8 * TILE_SCALE / 16, tablePos.z);
	}

	/**
	 * 生成整桌摆位计划（原 FengPanBlock.spawnTableDisplays 的全部摆位数学）。
	 * 手牌：四边各 N 张立牌（面朝各家）；牌河：平躺面朝上（长轴指向桌心，8 张一排）；
	 * 副露：平躺纵牌 + 横放声明牌（整组朝向/横牌端位对应喂牌家方位）；点棒：牌河前堆叠；宝牌：中央 5 叠×2 张。
	 * 局终展示（handsExposed=1 推倒/2 盖牌）的手牌项 axis 非空（平躺），animate 时客户端先立后平两段生成。
	 */
	public static List<Placement> plan(Geom geom, RichiTableState st, List<List<Integer>> handCodes, boolean animate) {
		return plan(geom, st, handCodes, animate, null);
	}

	/**
	 * 同上；handSlots 为各座位手牌的槽位覆盖（手切停留用：保留切牌前槽位、切牌处留空，
	 * null = 按顺序排布）。槽位 == 手牌张数时视为"刚摸的牌"分离位。
	 */
	public static List<Placement> plan(Geom geom, RichiTableState st, List<List<Integer>> handCodes, boolean animate,
			int[][] handSlots) {
		List<Placement> out = new ArrayList<>();
		int step = animate ? DEAL_INTERVAL_TICKS : 0;
		// 参战座位数（4=四麻 / 3=三麻）：三麻不为座位 3 生成任何摆位（手牌/牌河/副露/标签/点棒恒空，
		// 东南西三家保持原四麻方位不做 120° 重排）；防御性夹取到 1..4，缺省字段值为 4（四麻兼容）
		int seats = Math.min(4, Math.max(1, st.playerCount));
		Vec3 center = geom.center();
		Vec3 f = geom.f();
		Vec3 r = geom.r();
		double edge = geom.edge(); // 手牌行中心距桌心（与 of() 一致：正常 1.65 / 小型 1.3375）
		double flatStep = 0.17;         // 平躺牌间距
		Vec3[] rows = geom.rows();
		Vec3[] tangents = geom.tangents();
		Vec3[] outs = geom.outs();
		float[] yRots = new float[4];
		for (int h = 0; h < 4; h++)
			yRots[h] = (float) Math.toDegrees(Math.atan2(-outs[h].x, outs[h].z));

		// 0) 座位标记：各家外侧 2.75 格（向桌心收 0.25 + 再内收 0.5）、上方 2 格处（文本由渲染方拼装）
		for (int h = 0; h < seats; h++) {
			Vec3 labelPos = center.add(outs[h].scale(geom.seatDist() - 0.75)).add(0, SEAT_HEIGHT, 0);
			out.add(new Placement(KIND_SEAT, labelPos, labelPos, -1, 0, null, true, h, -1, h * step));
		}
		// 1) 手牌：四家同时发牌（第 k 张四家一起出：delay = (4 + k*4 + h)*step）；选中牌升高 SELECT_LIFT
		for (int h = 0; h < seats; h++) {
			List<Integer> hand = handCodes.get(h);
			int expose = st.handsExposed[h];
			boolean hasDrawn = st.drawnSeat == h && !hand.isEmpty(); // 刚摸的牌 = 手牌最右一张（与原手牌隔开一个牌位）
			int[] slots = handSlots == null || h >= handSlots.length ? null : handSlots[h];
			for (int k = 0; k < hand.size(); k++) {
				int code = hand.get(k);
				int slot = slots != null && k < slots.length ? slots[k] : k;
				// 手切停留槽位里，槽位 == 手牌张数 = 切牌前刚摸那张所在的最右分离位
				boolean drawnGap = slots != null ? slot == hand.size() : (hasDrawn && k == hand.size() - 1);
				Vec3 tilePos = handTarget(geom, h, slot, drawnGap)
						.add(0, st.selectedSeat == h && st.selectedIndex == k ? SELECT_LIFT : 0, 0);
				// 四张四张发：同一批的 4 张同时出现；批次间隔放大，使整桌发牌总时长与逐张发时一致
				// （末批延迟仍为 4 + 3*16 + 座位号 = 原逐张发牌的最大延迟）
				int delay = (4 + (k / 4) * 16 + h) * step;
				if (expose != RichiTableState.HAND_STAND) {
					// 推倒/盖牌：长轴指向桌心方向（与牌河同向，避免"多米诺"横躺），客户端先立后平
					Vec3 flatAxis = outs[h].scale(-1);
					out.add(new Placement(KIND_HAND, flatEntityPos(tilePos, 0), tilePos, code,
							flatYaw(flatAxis), flatAxis, expose == RichiTableState.HAND_FACE_UP, h, k, delay));
				} else {
					out.add(new Placement(KIND_HAND, standingEntityPos(tilePos), tilePos, code,
							yRots[h], null, true, h, k, delay));
				}
			}
		}
		int dealIndex = 4 + HAND_SIZE * 4; // 手牌区占用 4 标签 + 52 张的调度序号
		// 2) 牌河：内排每排 6 张、自最靠桌心一排起逐排向玩家（从内往外）、每排沿玩家视角自左向右排；
		//    大风盘排满 3 排（18 张）后另起第 4 排（先补齐前 6 列）再一直往右延伸（不重叠、不越桌边）；
		//    小型风盘只排 2 排（12 张），第三排同样先补齐前 6 列再一直往右；立直宣言牌横摆（长轴沿切线）
		for (int h = 0; h < seats; h++) {
			Vec3 tangent = tangents[h];
			Vec3 riverDir = outs[h].scale(-1); // 长轴指向桌心
			List<Integer> river = st.riverCodes(h);
			for (int i = 0; i < river.size(); i++) {
				Vec3 target = rows[h].subtract(outs[h].scale(edge - geom.riverRowDist(riverRow(i, geom.small()))))
						.add(geom.lefts()[h].scale(((RIVER_COLS - 1) / 2.0 - riverCol(i, geom.small())) * flatStep));
				boolean riichiSide = i == st.riichiRiverIdx[h];
				Vec3 dir = riichiSide ? tangent : riverDir;
				out.add(new Placement(KIND_RIVER, flatEntityPos(target, 0), target, river.get(i),
						flatYaw(dir), dir, true, h, i, dealIndex++ * step));
			}
		}
		// 3) 副露（吃碰杠）：副露行在小型风盘贴近玩家侧桌沿（外缘只留 MELD_EDGE_GAP），大风盘仍向桌心内收，组序从外向内游标式排开——
		//    每组按实际占宽（平躺自家牌 + 声明牌伸出方向）推进；小型右端贴桌沿只留 MELD_EDGE_GAP，大风盘不超过 MELD_EDGE_CAP，
		//    同一家的多组也不会因声明牌相对而压叠。
		//    所有副露整体靠右排列（游标自外缘内收），新的副露永远紧挨上一组，不插手牌中间。
		//    组内全部平躺：自家牌长轴垂直副露行（水平面内"竖着"、朝桌心，同牌河牌）；
		//    声明牌（副露串末尾那张，见 RiichiPlayer.displayOrder）长轴指向来源玩家——
		//    上家（rel3/左手）横牌在组最左、下家（rel1/右手）在最右、对家（rel2/对面）在中间且长轴朝桌心。
		//    暗杠（来源自己且 4 张）无声明牌：4 张平躺横排，两端扣盖（背面朝上）、中间两张亮明（正面朝上）。
		for (int h = 0; h < seats; h++) {
			Vec3 facingCenter = outs[h].scale(-1);
			Vec3 playerRight = new Vec3(-facingCenter.z, 0, facingCenter.x); // 面朝桌心时的右手（副露行 / 手牌向右延伸）
			Vec3 playerLeft = playerRight.scale(-1);
			List<RichiTableState.MeldGroup> groups = st.meldGroupInfos(h);
			Vec3 meldRow = center.add(outs[h].scale(geom.meldRowDist())); // 贴近玩家侧桌沿（外缘距桌沿 MELD_EDGE_GAP）
			double halfW = TILE_WIDTH / 2.0;
			double halfL = TILE_LONG / 2.0;
			double protrude = halfL - halfW; // 横放声明牌沿行伸出量（长轴与立牌同向时占用更长）
			double cursor = geom.meldEdgeCap();   // 游标：当前可用外缘（沿 playerRight 的标量）
			for (int g = 0; g < groups.size(); g++) {
				List<Integer> meld = groups.get(g).codes();
				if (meld.isEmpty())
					continue;
				int fromSeat = groups.get(g).fromSeat();
				int rel = fromSeat < 0 ? 0 : (fromSeat - h + 4) % 4;
				// 加杠：按碰的 3 张布局（2 平躺 + 1 横放），第 4 张叠在横放牌上，不排成明杠
				boolean kakan = groups.get(g).kakan() && meld.size() == 4;
				int n = kakan ? meld.size() - 1 : meld.size();
				boolean ankan = !kakan && fromSeat == h && n == 4; // 暗杠：来源自己且 4 张
				// 声明牌横放位置：上家(rel3)最左、对家(rel2)中间、下家(rel1)最右；
				// 横放长轴指向来源玩家：上家朝左、下家朝右、对家朝桌心。
				int flatPos = -1;
				Vec3 flatLong = null;
				if (!ankan) {
					flatPos = switch (rel) {
						case 3 -> 0;                    // 上家：横牌在最左 [横][竖]..[竖]
						case 1 -> n - 1;                // 下家：横牌在最右 [竖]..[竖][横]
						default -> 1;                   // 对家：横牌在中间（3 张=第 2 位、4 张=中间偏左）
					};
					if (rel == 3) {
						flatLong = playerLeft;          // 长轴朝左指向左侧上家
					} else if (rel == 1) {
						flatLong = playerRight;         // 长轴朝右指向右侧下家
					} else {
						flatLong = facingCenter;        // 长轴朝桌心指向对家（垂直副露行）
					}
				}
				// 组沿行（playerRight）各侧半宽（中心到外缘），供游标避让（相邻组/相邻家不重叠）
				double pitch = ankan ? flatStep : TILE_GAP;
				double centerOff = (n - 1) / 2.0 * pitch;
				double inner, outer;
				if (ankan) {
					inner = outer = centerOff + halfW;
				} else if (rel == 3) {
					inner = centerOff + halfL + protrude; // 横牌最左且向左伸出
					outer = centerOff + halfW;
				} else if (rel == 1) {
					inner = centerOff + halfW;
					outer = centerOff + halfL + protrude; // 横牌最右且向右伸出
				} else {
					inner = outer = centerOff + halfW;     // 对家横牌在中间、长轴垂直，无伸出
				}
				double c = cursor - outer; // 组中心标量
				cursor = c - inner - MELD_GAP;
				Vec3 groupCenter = meldRow.add(playerRight.scale(c));
				// 每张牌沿 playerRight 的位置标量（相对 groupCenter；声明牌按伸出方向平移，避免压到邻家平躺牌）
				double[] u = new double[n];
				for (int j = 0; j < n; j++)
					u[j] = (j - (n - 1) / 2.0) * pitch;
				if (ankan) {
					for (int j = 0; j < n; j++) {
						Vec3 target = groupCenter.add(playerRight.scale(u[j]));
						boolean faceUp = j == 1 || j == 2; // 两端扣盖（背面朝上），中间两张亮明（正面朝上）
						out.add(new Placement(KIND_MELD, flatEntityPos(target, 0), target, meld.get(j),
								flatYaw(facingCenter), facingCenter, faceUp, h, j, dealIndex++ * step));
					}
					continue;
				}
				if (rel == 3)
					u[flatPos] -= protrude; // 上家横牌向左伸（紧贴邻家平躺牌，无豁口）
				else if (rel == 1)
					u[flatPos] += protrude; // 下家横牌向右伸
				// 横放声明牌向桌心外移半个牌长差：让其外侧长侧面与同组其他牌的外缘齐平
				// （长轴与行垂直的上家/下家才是真正"横着"的；对家声明牌与自家牌同向，不偏移）
				boolean rotatedClaim = flatLong != null && Math.abs(flatLong.dot(facingCenter)) < 0.5;
				Vec3 flatOffset = rotatedClaim ? facingCenter.scale(-protrude) : Vec3.ZERO;
				// 组内摆放序：声明牌（副露串末尾）放横位，其余自家牌按原序平躺
				int[] order = new int[n];
				order[flatPos] = n - 1;
				for (int j = 0, oi = 0; j < n; j++)
					if (j != flatPos)
						order[j] = oi++;
				Vec3 flatTarget = null; // 横放声明牌位置（加杠第 4 张紧贴其近侧）
				for (int j = 0; j < n; j++) {
					Vec3 target = groupCenter.add(playerRight.scale(u[j]));
					if (j == flatPos) {
						Vec3 flatPos3 = target.add(flatOffset);
						flatTarget = flatPos3;
						// 声明牌：平躺，长轴指向来源玩家（上家朝左/下家朝右/对家朝桌心）
						// index = -3 标记"副露声明牌"（客户端播放从牌河飞来的动画用）
						out.add(new Placement(KIND_MELD, flatEntityPos(flatPos3, 0), flatPos3, meld.get(order[j]),
								flatYaw(flatLong), flatLong, true, h, -3, dealIndex++ * step));
					} else {
						// 自家牌：平躺，长轴垂直副露行（水平面内"竖着"、朝桌心）
						out.add(new Placement(KIND_MELD, flatEntityPos(target, 0), target, meld.get(order[j]),
								flatYaw(facingCenter), facingCenter, true, h, j, dealIndex++ * step));
					}
				}
				if (kakan && flatTarget != null) {
					// 加杠的第 4 张：一律与横放牌同一水平面，紧贴其靠桌心一侧摆放（不叠放、不排成明杠）；
					// 对家横牌长轴朝桌心、伸出方向即桌心方向，同样按 facingCenter 相接
					// index = -2 标记"加杠新增牌"（客户端播放从手牌飞来的动画用）
					Vec3 near = flatTarget.add(facingCenter.scale(rotatedClaim ? TILE_WIDTH : TILE_LONG));
					out.add(new Placement(KIND_MELD, flatEntityPos(near, 0), near,
							meld.get(meld.size() - 1), flatYaw(flatLong), flatLong, true, h, -2,
							dealIndex++ * step));
				}
			}
			// 3.4) 拔北位（三麻）：拔北**不算副露**，数量取快照独立字段 st.kita[h]，与副露串无关。
			//      北风牌平躺、长轴指桌心（与牌河牌同向旋转），自手牌区右上角外 2 模型单位起沿行向右单列排列；
			//      整块再向桌心内收一张牌宽（TILE_WIDTH），避免贴着手牌行；
			//      再向桌心内收 3 模型单位、向玩家左侧移 3 模型单位（更靠内侧、不压手牌右端）
			if (st.playerCount == 3 && st.kita[h] > 0) {
				int kitaN = st.kita[h];
				// 手牌区右端（与 handTarget 的 offset 公式一致）：满编 13 张最右槽位中心
				// 再让出半张平躺牌宽；小型风盘整排手牌左移 2/16，右端同样内收
				double rightEnd = (HAND_SIZE - 1) / 2.0 * TILE_GAP + TILE_WIDTH / 2.0
						- (geom.small() ? SMALL_HAND_LEFT_SHIFT : 0);
				double inward = 3.0 / 16.0; // 向桌心再内收 3 模型单位
				double leftShift = 3.0 / 16.0; // 向玩家左侧再移 3 模型单位
				Vec3 kitaRow = rows[h].subtract(outs[h].scale(TILE_WIDTH + inward)); // 内收一张牌宽 + 3 单位
				Vec3 kitaDir = outs[h].scale(-1); // 长轴指桌心（同牌河）
				for (int k = 0; k < kitaN; k++) {
					Vec3 target = kitaRow.add(geom.lefts()[h].scale(
							-(rightEnd + 2.0 / 16.0 + TILE_WIDTH / 2.0 + k * TILE_GAP) + leftShift));
					out.add(new Placement(KIND_KITA, flatEntityPos(target, 0), target, TILE_PEI,
							flatYaw(kitaDir), kitaDir, true, h, k, dealIndex++ * step));
				}
			}
		}
		// 3.5) 立直点棒：牌河前面（比第一排牌河更靠桌心），长轴平行于手牌行，多根依次向牌河方向堆叠
		for (int h = 0; h < seats; h++) {
			for (int k = 0; k < st.riichiSticks[h]; k++) {
				Vec3 stickPos = rows[h].subtract(outs[h].scale(edge - geom.riverDist() + 0.2 + k * 0.12));
				Vec3 toCenter = tangents[h];
				out.add(new Placement(KIND_STICK,
						new Vec3(stickPos.x, stickPos.y + 0.5 * TILE_SCALE, stickPos.z), stickPos,
						-1, stickYaw(toCenter), toCenter, true, h, k, dealIndex++ * step));
			}
		}
		// 4) 中央宝牌指示牌区：5 叠按叠序摆放，底张背面朝上；已揭开的叠数翻开上张（lift=FLAT_THICKNESS）
		Vec3 doraLong = f.scale(-1);
		List<Integer> dora = st.doraCodes();
		for (int i = 0; i < DORA_STACKS && 2 * i + 1 < dora.size(); i++) {
			Vec3 stackPos = center.add(f.scale(0.12)).add(r.scale((i - (DORA_STACKS - 1) / 2.0) * 0.157));
			out.add(new Placement(KIND_DORA, flatEntityPos(stackPos, 0), stackPos, dora.get(2 * i),
					flatYaw(doraLong), doraLong, false, -1, i, dealIndex++ * step));
			boolean revealed = i < st.revealedIndicators;
			out.add(new Placement(KIND_DORA, flatEntityPos(stackPos, FLAT_THICKNESS), stackPos, dora.get(2 * i + 1),
					flatYaw(doraLong), doraLong, revealed, -1, i, dealIndex++ * step));
		}
		// 5) 供托（此前局目流局留存、未结算的立直棒）：王牌指示叠旁的空位堆成一摞（有人和牌即取走清空）
		if (st.carriedSticks > 0) {
			Vec3 potPos = center.add(f.scale(0.12))
					.add(r.scale(-(DORA_STACKS - 1) / 2.0 * 0.157 - TILE_WIDTH / 2.0 - 0.09));
			for (int k = 0; k < st.carriedSticks; k++) {
				out.add(new Placement(KIND_STICK,
						new Vec3(potPos.x, potPos.y + 0.5 * TILE_SCALE + k * 0.03, potPos.z), potPos,
						-1, stickYaw(doraLong), doraLong, true, -1, k, dealIndex++ * step));
			}
		}
		return out;
	}
}
