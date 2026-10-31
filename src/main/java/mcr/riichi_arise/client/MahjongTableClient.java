package mcr.riichi_arise.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.math.Transformation;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Display.TextDisplay;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import mcr.riichi_arise.client.MahjongTileBlockRenderer;
import mcr.riichi_arise.MahjongTileItem;
import mcr.riichi_arise.block.FengPanBlock;
import mcr.riichi_arise.game.MahjongTileNotation;
import mcr.riichi_arise.game.RichiTableState;
import mcr.riichi_arise.network.MahjongTablePayload;
import mcr.riichi_arise.render.DisplayEntityNbt;
import mcr.riichi_arise.render.TableLayout;

/**
 * 麻雀牌桌客户端渲染：收到 {@link MahjongTablePayload}（服务端按观看者过滤后的快照）后
 * 增量更新 client-side 显示实体。每个逻辑牌位有稳定 key（手牌:座位:牌面:内序 / 牌河:座位:下标 /
 * 副露:座位:序 / 点棒:座位:序 / 宝牌:叠:底顶 / 座位标签等），按"签名"（kind+牌面+正反面+立平形态，
 * 文本实体为文本内容）比对：签名不变仅位置/朝向变化 → 复用实体并走 display 自带 pos-rot 插值
 * （teleport_duration，见 lerpTo）平滑移动；签名变化或实体丢失 → 只重建该实体；新 key 生成；
 * 消失 key 删除。不变的实体不再 discard/respawn，杜绝全桌闪烁。
 * 发牌动画（dealAnim&gt;0）仍整桌清场后按 Placement.delay 逐张生成（倒牌/盖牌两段：先立牌 delay、
 * 后平躺 delay+10，同 key 二次生成自动替换立牌）；选中升降增量移动对应实体（handIds 下标索引）。
 * 座位标签为 text_display，内容变化就地 load 更新 text NBT 而不重建；
 * 局目/宝牌/牌山与和牌结算横幅改由 boss 栏渲染（不再在桌心生成悬浮文本）。
 */
public final class MahjongTableClient {
	private MahjongTableClient() {
	}

	/** 桌上某逻辑牌位的实体记录：实体 id + 外观签名（文本实体签名 = 文本内容） */
	private record Entry(int entityId, String sig) {
	}

	/** 摆位项 + 预计算稳定 key / 外观签名 / 文本内容（仅座位标签非空） */
	private record Slot(TableLayout.Placement p, String slotKey, String sig, String text) {
	}

	/** 一张桌的客户端渲染记录：key→实体清单 + 手牌(座位,下标)→实体 id（选中升降用） + 最近一包 */
	private static final class TableRender {
		final Map<String, Entry> keyed = new HashMap<>();
		final Map<Integer, Integer> handIds = new HashMap<>();
		MahjongTablePayload.ViewMessage last;
		int centerId = -1;    // 桌心信息悬浮文本实体 id（-1 = 无）
		String centerSig = ""; // 桌心信息上次文本（内容变化就地 load 更新，不重建）
		/** 手切归位延迟截止（tickCounter）：该座位刚手切，其刚摸的牌在分离位停留到此刻再滑回手牌序列；-1 = 无 */
		final int[] handCutHoldUntil = { -1, -1, -1, -1 };
	}

	private static final Map<Long, TableRender> TABLES = new HashMap<>();
	/** 各桌 AI 座位假玩家（key = 桌 originPacked，value = 座位 → 假玩家记录） */
	private static final Map<Long, Map<Integer, Avatar>> AVATARS = new HashMap<>();
	/** 各桌牌桌中心（桌 key → 桌心世界坐标），供形象落地后重调视线使用 */
	private static final Map<Long, Vec3> TABLE_CENTERS = new HashMap<>();
	/** 各形象上一 tick 是否落地（onGround），用于检测"刚落定"后重调视线 */
	private static final Map<Avatar, Boolean> AVATAR_GROUND = new HashMap<>();
	/** 假玩家客户端实体 id 计数（负数且递减，与服务端实体 id 区间隔离） */
	private static int nextAvatarEntityId = -1_000_000;
	/** 角色皮肤是否细手臂（slim）的解析缓存（贴图 → slim） */
	private static final Map<net.minecraft.resources.ResourceLocation, Boolean> SLIM_CACHE = new HashMap<>();

	/** AI 座位假玩家记录：实体 + 隐形骑乘载具（站姿模式为 null，直接钉位）+ 角色 id + 档案 UUID + 是否站姿 */
	private record Avatar(RemotePlayer player,
			net.minecraft.world.entity.decoration.ArmorStand seat,
			String form, UUID profileUuid, boolean standing) {
	}

	/** 待执行的动画任务（发牌 stagger / 倒牌级联），按桌分组 */
	private static final Map<Long, List<Scheduled>> SCHEDULED = new HashMap<>();
	private static int tickCounter;

	private record Scheduled(long at, Runnable run) {
	}

	/** 客户端 tick（MahjongClient 转发）：驱动发牌/倒牌动画与推倒过渡 */
	public static void tick() {
		tickCounter++;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			SCHEDULED.clear();
			FALLS.clear();
			return;
		}
		for (Map.Entry<Long, List<Scheduled>> entry : SCHEDULED.entrySet()) {
			List<Scheduled> list = entry.getValue();
			List<Scheduled> rest = null;
			List<Runnable> due = null;
			for (Scheduled s : list) {
				if (s.at() <= tickCounter)
					(due == null ? due = new ArrayList<>() : due).add(s.run());
				else
					(rest == null ? rest = new ArrayList<>() : rest).add(s);
			}
			list.clear();
			if (rest != null)
				list.addAll(rest);
			if (due != null && TABLES.containsKey(entry.getKey()))
				due.forEach(Runnable::run);
		}
		SCHEDULED.entrySet().removeIf(e -> e.getValue().isEmpty());
		// 手切归位延迟到期：清标记并重放该桌（刚摸的牌从分离位滑回手牌序列）
		for (java.util.Map.Entry<Long, TableRender> en : TABLES.entrySet()) {
			TableRender t = en.getValue();
			if (t.last == null)
				continue;
			boolean expired = false;
			for (int s = 0; s < 4; s++)
				if (t.handCutHoldUntil[s] > 0 && tickCounter >= t.handCutHoldUntil[s]) {
					t.handCutHoldUntil[s] = -1;
					expired = true;
				}
			if (expired)
				applyInternal(t.last, true);
		}
		// 局终推倒过渡动画：手牌立牌旋转倒下到平躺
		FALLS.entrySet().removeIf(en -> advanceFall(level, en.getValue()));
		// 拔北飞行动画：手中北风浮起 → 飘到拔北位 → 降落
		FLIGHTS.entrySet().removeIf(en -> advanceFlight(level, en.getValue()));
		// AI 假玩家挥手动画：坐姿实体 tick 为空，须在此逐 tick 驱动 swingTime 推进、播完复位；
		// 站姿已由原版 tick 自行推进。
		driveAvatarWaves();
		// 坐姿载具落定：载具有乘客时 aiStep 不自动重力，改由原版碰撞逐 tick 下落落定（站姿原版重力自落）
		settleAvatars(level);
		// AI 形象落地后重新对准桌心一次：重力落定瞬间（onGround 由 false→true）重算俯角/朝向
		for (Map.Entry<Long, Map<Integer, Avatar>> te : AVATARS.entrySet()) {
			Vec3 center = TABLE_CENTERS.get(te.getKey());
			if (center == null)
				continue;
			for (Avatar a : te.getValue().values()) {
				net.minecraft.world.entity.Entity g = a.standing() ? a.player() : a.seat();
				if (g == null || g.isRemoved())
					continue;
				boolean ground = g.onGround();
				Boolean prev = AVATAR_GROUND.get(a);
				if (prev != null && !prev && ground)
					lookAtTableCenter(a, center, a.player().position());
				AVATAR_GROUND.put(a, ground);
			}
		}
	}

	/** 推进单个推倒动画；返回 true 表示动画结束（移出 FALLS） */
	private static boolean advanceFall(ClientLevel level, Fall fall) {
		float t = (tickCounter - fall.startTick()) / (float) fall.duration();
		Entity e = level.getEntity(fall.entityId());
		if (e == null || e.isRemoved())
			return true; // 实体消失：结束
		// 每帧先应用当前姿态（含 t≥1 时精确落定到平躺终点，避免停在 95% 处）
		float k = easeInOut(Math.max(0f, Math.min(1f, t)));
		applyTransform(e, fall.from().slerp(fall.to(), k));
		e.setPos(fall.start().x + (fall.end().x - fall.start().x) * k,
				fall.start().y + (fall.end().y - fall.start().y) * k,
				fall.start().z + (fall.end().z - fall.start().z) * k);
		e.setOldPosAndRot();
		return t >= 1f; // 落定后移除
	}

	private static void schedule(long key, int delay, Runnable run) {
		if (delay <= 0) {
			run.run();
			return;
		}
		// 发牌节流：计划延迟以半 tick 为单位（dealIndex*DEAL_INTERVAL_TICKS 的半粒度），隔 1 调度 1，实现两倍速
		SCHEDULED.computeIfAbsent(key, k -> new ArrayList<>()).add(new Scheduled(tickCounter + delay / 2, run));
	}

	// ==================================================================
	// 手牌推倒过渡动画：局终和牌/流局时，手牌从立牌绕底边旋转倒下到平躺（所有手牌同步）
	// ==================================================================
	/** 推倒动画时长（tick，≈0.4s） */
	private static final int FALL_TICKS = 8;
	/** 进行中的推倒动画，key = 被动画的显示实体 id（自清洁：实体消失即移除） */
	private static final Map<Integer, Fall> FALLS = new HashMap<>();

	/** 推倒动画：实体 id + 起止变换（站立纯缩放→平躺矩阵）+ 起止位置 + 起始 tick/时长 */
	private record Fall(int entityId, Transformation from, Transformation to,
			Vec3 start, Vec3 end, int startTick, int duration) {
	}

	// ==================================================================
	// 牌位飞行动画：牌位从起手处浮起，飘到目标位后降落（立牌 → 平躺）；
	// 打牌 / 副露声明牌 / 加杠新增牌 / 拔北共用
	// ==================================================================
	/** 飞行时长（tick） */
	private static final int KITA_FLIGHT_TICKS = 10;
	/** 飞行弧线高度（格）：起终点之间先抬高再落下 */
	private static final double KITA_FLIGHT_ARC = 0.175;
	/** 进行中的拔北飞行，key = 实体 id（自清洁：实体消失即移除） */
	private static final Map<Integer, Flight> FLIGHTS = new HashMap<>();

	private record Flight(int entityId, Vec3 from, Vec3 to, float fromYaw, float toYaw,
			int startTick, int duration) {
	}

	/** 启动牌位飞行：实体先从起点（起点姿态为立牌）开始，之后逐 tick 飘向目标位 */
	private static void startTileFlight(Entity e, TableLayout.Placement p, Vec3 from) {
		float fromYaw = (float) Math.toDegrees(Math.atan2(-(p.pos().x - from.x), p.pos().z - from.z));
		e.setPos(from.x, from.y, from.z);
		e.setYRot(fromYaw);
		e.setOldPosAndRot();
		applyTransform(e, standingTransform(TableLayout.TILE_SCALE));
		FLIGHTS.put(e.getId(), new Flight(e.getId(), from, p.pos(), fromYaw, p.yRot(),
				tickCounter, KITA_FLIGHT_TICKS));
	}

	/**
	 * 新生成牌位的飞行起点（null = 不播动画）：
	 * <ul><li>打出的牌（牌河）→ 该家上一包手牌里被移出那张的位置；</li>
	 * <li>副露声明牌（index=-3）→ 被取走那张在打牌者牌河中的位置；</li>
	 * <li>加杠新增牌（index=-2）→ 该家手牌位置；</li>
	 * <li>拔北牌 → 该家手牌位置。</li></ul>
	 */
	private static Vec3 flightSource(TableLayout.Placement p, Vec3[] handSrc, Vec3 claimSrc) {
		int seat = p.seat();
		if (seat < 0 || seat >= 4)
			return null;
		if (TableLayout.KIND_RIVER.equals(p.kind()) || TableLayout.KIND_KITA.equals(p.kind()))
			return handSrc[seat];
		if (TableLayout.KIND_MELD.equals(p.kind())) {
			if (p.index() == -3)
				return claimSrc;
			if (p.index() == -2)
				return handSrc[seat];
		}
		return null;
	}

	/** 上一包到手牌少一张时，返回被移出那张在上一包中的下标；否则 -1 */
	private static int firstRemovedIndex(List<Integer> prevHand, List<Integer> newHand) {
		if (newHand.isEmpty() || prevHand.size() != newHand.size() + 1)
			return -1;
		for (int i = 0; i < newHand.size(); i++)
			if (!prevHand.get(i).equals(newHand.get(i)))
				return i;
		return prevHand.size() - 1;
	}

	/** 推进单个拔北飞行；返回 true 表示动画结束（移出 FLIGHTS） */
	private static boolean advanceFlight(ClientLevel level, Flight f) {
		Entity e = level.getEntity(f.entityId());
		if (e == null || e.isRemoved())
			return true;
		float t = Math.max(0f, Math.min(1f, (tickCounter - f.startTick()) / (float) f.duration()));
		float k = easeInOut(t);
		double x = f.from().x + (f.to().x - f.from().x) * k;
		double z = f.from().z + (f.to().z - f.from().z) * k;
		// 弧线：飞行途中抬高，落点回到目标高度（先浮起再降落）
		double y = f.from().y + (f.to().y - f.from().y) * k + Math.sin(Math.PI * t) * KITA_FLIGHT_ARC;
		float yaw = f.fromYaw() + Mth.wrapDegrees(f.toYaw() - f.fromYaw()) * k;
		e.setPos(x, y, z);
		e.setYRot(yaw);
		e.setOldPosAndRot();
		applyTransform(e, standingTransform(TableLayout.TILE_SCALE)
				.slerp(flatTransform(TableLayout.TILE_SCALE, true), k));
		if (t >= 1f) {
			e.setPos(f.to().x, f.to().y, f.to().z);
			e.setYRot(f.toYaw());
			e.setOldPosAndRot();
			applyTransform(e, flatTransform(TableLayout.TILE_SCALE, true));
			return true;
		}
		return false;
	}

	/** Display 变换四件套 SynchedEntityData 访问器（Display 内为 private static final，反射取一次） */
	private static final EntityDataAccessor<Vector3f> ACC_TRANSLATION = displayAccessor("DATA_TRANSLATION_ID");
	private static final EntityDataAccessor<Vector3f> ACC_SCALE = displayAccessor("DATA_SCALE_ID");
	private static final EntityDataAccessor<Quaternionf> ACC_LEFT_ROT = displayAccessor("DATA_LEFT_ROTATION_ID");
	private static final EntityDataAccessor<Quaternionf> ACC_RIGHT_ROT = displayAccessor("DATA_RIGHT_ROTATION_ID");

	@SuppressWarnings("unchecked")
	private static <T> EntityDataAccessor<T> displayAccessor(String name) {
		try {
			Field f = Display.class.getDeclaredField(name);
			f.setAccessible(true);
			return (EntityDataAccessor<T>) f.get(null);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	/** legacy 16-float 列主序矩阵 → JOML Matrix4f（行主序构造，索引转置） */
	private static Matrix4f legacyMatrix(float[] m) {
		return new Matrix4f(
				m[0], m[4], m[8], m[12],
				m[1], m[5], m[9], m[13],
				m[2], m[6], m[10], m[14],
				m[3], m[7], m[11], m[15]);
	}

	/** 立牌变换：纯缩放（entity yaw 保持立牌朝向） */
	private static Transformation standingTransform(float s) {
		return new Transformation(new Matrix4f().scale(s, s, s));
	}

	/** 平躺变换：目标矩阵即平躺矩阵本身（立牌 yaw == 平躺 yaw，故无 yaw 折叠） */
	private static Transformation flatTransform(float s, boolean faceUp) {
		return new Transformation(legacyMatrix(DisplayEntityNbt.flatMatrix(s, faceUp)));
	}

	/** smoothstep 缓动 */
	private static float easeInOut(float t) {
		return t * t * (3f - 2f * t);
	}

	/** 把动画变换写进显示实体的 SynchedEntityData（经反射访问器） */
	private static void applyTransform(Entity e, Transformation t) {
		SynchedEntityData data = e.getEntityData();
		data.set(ACC_TRANSLATION, t.getTranslation());
		data.set(ACC_SCALE, t.getScale());
		data.set(ACC_LEFT_ROT, t.getLeftRotation());
		data.set(ACC_RIGHT_ROT, t.getRightRotation());
	}

	/** 为一张立牌手牌注册推倒动画（保留实体，由 tick 驱动旋转倒下） */
	private static void startFall(Entity e, TableLayout.Placement p, int delay) {
		FALLS.put(e.getId(), new Fall(e.getId(),
				standingTransform(TableLayout.TILE_SCALE),
				flatTransform(TableLayout.TILE_SCALE, p.faceUp()),
				new Vec3(e.getX(), e.getY(), e.getZ()), p.pos(),
				tickCounter + delay, FALL_TICKS));
	}

	/**
	 * 应用一包状态：仅选中变化 → 移动对应实体；发牌动画 → 清场逐张生成；其余 → 增量 diff 更新。
	 * 牌谱回放期间（{@link #beginReplay} 起至 {@link #endReplay}）屏蔽该桌实时同步，
	 * 改由回放侧用 {@link #applyReplay} 驱动同一渲染管线；结束时回放最后一包真实状态。
	 */
	public static void apply(MahjongTablePayload.ViewMessage msg) {
		if (replayOrigin != null && replayOrigin == msg.originPacked())
			return; // 回放中：实时包丢弃（结束后用最后真实包恢复）
		applyInternal(msg, false);
	}

	/** 回放专用：合成快照走同一渲染管线（不更新真实缓存、选中优化跳过） */
	public static void applyReplay(MahjongTablePayload.ViewMessage msg) {
		if (replayOrigin != null && replayOrigin == msg.originPacked())
			applyInternal(msg, true);
	}

	/**
	 * 客户端配置（观战摊手 / AI 形象姿态 / 观战显示牌局内容）变更后立刻按最近一次快照重建，
	 * 不必等下一次服务端广播。发牌动画等有在途任务的桌跳过，等下一包自然生效。
	 */
	public static void refreshAll() {
		for (Long key : new ArrayList<>(TABLES.keySet())) {
			if (SCHEDULED.containsKey(key))
				continue;
			TableRender t = TABLES.get(key);
			if (t != null && t.last != null)
				apply(t.last);
		}
	}

	/** 进入回放模式：此后该桌的实时 ViewMessage 被忽略 */
	public static void beginReplay(long originPacked) {
		replayOrigin = originPacked;
	}

	/** 退出回放模式：用回放前最后收到的真实包恢复桌面 */
	public static void endReplay() {
		Long key = replayOrigin;
		replayOrigin = null;
		if (key == null)
			return;
		TableRender table = TABLES.get(key);
		if (table != null && table.last != null) {
			applyInternal(table.last, false);
		} else {
			ClientLevel level = Minecraft.getInstance().level;
			if (level != null && table != null)
				discardAll(level, table);
			discardAvatars(key);
			clearBossBar(key);
			TABLES.remove(key);
		}
	}

	private static Long replayOrigin;

	/** 当前参战桌的最近快照（viewerSeat>=0 = 该玩家为桌上参战者）；未参战任何桌/无快照返回 null */
	public static MahjongTablePayload.ViewMessage participatingView() {
		for (TableRender t : TABLES.values())
			if (t.last != null && t.last.viewerSeat() >= 0)
				return t.last;
		return null;
	}

	/**
	 * 手切停留用的槽位映射：按切牌前的排列（理牌 13 张 + 末位刚摸的牌）为新的 13 张牌分配旧槽位，
	 * 切掉的那张处留空 → 牌不会整体右移/回弹，10 tick 后再按新序列归位。
	 * 摸切（切掉刚摸的那张）或数据对不上时返回 null（无需停留）。
	 */
	private static int[] cutHoldSlots(List<Integer> prevHand, List<Integer> newHand, int tedashi) {
		if (tedashi != 1 || newHand.isEmpty() || prevHand.size() != newHand.size() + 1)
			return null;
		// 切掉的牌面 = prev 与 new 多重集之差（计数多出的那张）
		java.util.Map<Integer, Integer> cnt = new HashMap<>();
		for (int c : prevHand)
			cnt.merge(c, 1, Integer::sum);
		for (int c : newHand)
			cnt.merge(c, -1, Integer::sum);
		Integer removedCode = null;
		for (var e : cnt.entrySet())
			if (e.getValue() == 1) {
				removedCode = e.getKey();
				break;
			}
		if (removedCode == null)
			return null;
		// 手切：在理牌区（末位为刚摸的牌）里从右往左找该牌，作为切牌位置
		int r = -1;
		for (int i = prevHand.size() - 2; i >= 0; i--)
			if (prevHand.get(i).intValue() == removedCode) {
				r = i;
				break;
			}
		if (r < 0)
			return null;
		// 其余 13 张的旧槽位（0..size-1，去掉切牌位）
		int[] oldSlots = new int[prevHand.size() - 1];
		for (int i = 0, m = 0; i < prevHand.size(); i++)
			if (i != r)
				oldSlots[m++] = i;
		// 新牌按顺序贪心匹配同牌面的旧槽位（重复牌取首个未占用槽位）
		boolean[] used = new boolean[oldSlots.length];
		int[] slots = new int[newHand.size()];
		for (int k = 0; k < newHand.size(); k++) {
			int code = newHand.get(k);
			int slot = -1;
			for (int j = 0; j < oldSlots.length; j++)
				if (!used[j] && prevHand.get(oldSlots[j]).intValue() == code) {
					used[j] = true;
					slot = oldSlots[j];
					break;
				}
			if (slot < 0)
				return null;
			slots[k] = slot;
		}
		return slots;
	}

	private static void applyInternal(MahjongTablePayload.ViewMessage msg, boolean synthetic) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null)
			return;
		long key = msg.originPacked();
		TableRender table = TABLES.get(key);
		MahjongTablePayload.ViewMessage prev = table == null ? null : table.last; // 上一包（动作反馈用，此处须在 table.last 覆写前取）
		// 仅选中状态变化（升降）且无在途动画：移动对应实体，不重建
		if (!synthetic && table != null && table.last != null && table.last.dealAnim() == 0
				&& !SCHEDULED.containsKey(key) && sameExceptSelection(table.last, msg)) {
			moveSelection(level, table, msg);
			table.last = msg;
			return;
		}
		SCHEDULED.remove(key); // 重建即取消在途动画任务
		if (msg.phase() == RichiTableState.PHASE_WAITING) {
			// 无对局（局目间重置/大厅等待）：清除该桌客户端牌面实体；
			// 形象仍走 updateAvatars 增删建（预约即渲染，随机模式 = 盔甲架占位）
			clearBossBar(key); // 对局状态栏随对局清除
			if (table != null) {
				discardAll(level, table);
				TABLES.remove(key);
			}
			boolean anyForm = false;
			for (String f : msg.avatarForms())
				if (f != null && !f.isEmpty())
					anyForm = true;
			if (!anyForm)
				discardAvatars(key);
			BlockPos waitingOrigin = BlockPos.of(key);
			if (level.getBlockState(waitingOrigin).getBlock() instanceof FengPanBlock)
				updateAvatars(level, key, waitingOrigin, TableLayout.of(level, waitingOrigin), msg);
			return;
		}
		if (table == null) {
			table = new TableRender();
			TABLES.put(key, table);
		}
		if (!synthetic)
			table.last = msg;
		BlockPos origin = BlockPos.of(key);
		if (!(level.getBlockState(origin).getBlock() instanceof FengPanBlock)) {
			discardAll(level, table); // 区块未加载/桌子不存在：先清场，等下一包
			discardAvatars(key);
			clearBossBar(key);
			return;
		}
		TableLayout.Geom geom = TableLayout.of(level, origin);
		// 状态镜像（plan 只依赖摆位相关字段）
		RichiTableState st = new RichiTableState();
		for (int i = 0; i < 4; i++) {
			st.hands[i] = orEmpty(msg.hands()[i]);
			st.rivers[i] = orEmpty(msg.rivers()[i]);
			st.melds[i] = orEmpty(msg.melds()[i]);
			st.kita[i] = msg.kita()[i]; // 拔北数（三麻）：不算副露，独立字段
			st.points[i] = msg.points()[i];
			st.riichiRiverIdx[i] = msg.riichiRiverIdx()[i];
			st.riichiSticks[i] = msg.riichiSticks()[i];
			st.handsExposed[i] = msg.handsExposed()[i];
		}
		st.doraWall = orEmpty(msg.doraWall());
		st.revealedIndicators = msg.revealedIndicators();
		st.round = msg.round();
		st.roundWind = msg.roundWind();
		st.honba = msg.honba();
		st.carriedSticks = msg.carriedSticks();
		st.turnSeat = msg.turnSeat();
		st.selectedSeat = msg.selectedSeat();
		st.selectedIndex = msg.selectedIndex();
		st.drawnSeat = msg.drawnSeat();
		st.phase = msg.phase();
		st.playerCount = msg.playerCount(); // 三麻（3）时 plan() 跳过座位 3；拔北数走单独的 kita 字段（不算副露）
		// 手切归位延迟：上一包该家刚摸牌、本包已打出且为手切 → 记下分离位停留截止（10 tick）
		if (prev != null && !synthetic)
			for (int s = 0; s < 4; s++)
				if (prev.drawnSeat() == s && msg.drawnSeat() != s && msg.tedashi()[s] == 1)
					table.handCutHoldUntil[s] = tickCounter + 10;
		// 手切停留窗口内：保留切牌前的槽位排列（切掉的那张处留空、刚摸的牌仍停最右分离位），
		// 10 tick 后再按新理牌序列归位。隐藏/盖牌手牌无法还原空位，直接归位。
		int[][] handSlots = null;
		for (int s = 0; s < 4; s++) {
			if (table.handCutHoldUntil[s] <= tickCounter)
				continue;
			if (prev == null || msg.hidden()[s] > 0 || msg.handsExposed()[s] != RichiTableState.HAND_STAND) {
				table.handCutHoldUntil[s] = -1;
				continue;
			}
			int[] slots = cutHoldSlots(MahjongTileNotation.parse(orEmpty(prev.hands()[s])),
					MahjongTileNotation.parse(orEmpty(msg.hands()[s])), msg.tedashi()[s]);
			if (slots == null) {
				table.handCutHoldUntil[s] = -1;
				continue;
			}
			if (handSlots == null)
				handSlots = new int[4][];
			handSlots[s] = slots;
		}
		// 观战摊手（客户端配置）：观战者视角把立牌手牌全部改为面朝上平摊显示
		boolean forcedOpen = mcr.riichi_arise.RichiConfig.SPECTATOR_OPEN_HANDS.get() && msg.viewerSeat() < 0;
		if (forcedOpen)
			for (int s = 0; s < 4; s++)
				if (st.handsExposed[s] == RichiTableState.HAND_STAND)
					st.handsExposed[s] = RichiTableState.HAND_FACE_UP;
		// 流局未听/九种九牌盖牌：自己的手牌对自己始终可见（改为面朝上平摊，不再显示为未知牌背）
		int viewerSeat = msg.viewerSeat();
		if (viewerSeat >= 0 && viewerSeat < 4 && msg.hidden()[viewerSeat] == 0
				&& st.handsExposed[viewerSeat] == RichiTableState.HAND_FACE_DOWN)
			st.handsExposed[viewerSeat] = RichiTableState.HAND_FACE_UP;
		// 手牌码：可见家解析记法；隐藏家按 hidden 数量渲染未知牌背（-1 = 无牌面组件）；
		// 盖牌（HAND_FACE_DOWN，流局未听/九种九牌途中流局）渲染牌背（自己那家已按上面改为面朝上）
		List<List<Integer>> handCodes = new ArrayList<>(4);
		for (int s = 0; s < 4; s++) {
			int count;
			if (msg.hidden()[s] > 0) {
				count = msg.hidden()[s];
			} else {
				try {
					count = MahjongTileNotation.parse(orEmpty(msg.hands()[s])).size();
				} catch (RuntimeException e) {
					count = 0;
				}
			}
			if (st.handsExposed[s] == RichiTableState.HAND_FACE_DOWN) {
				List<Integer> backs = new ArrayList<>();
				for (int k = 0; k < count; k++)
					backs.add(-1);
				handCodes.add(backs);
			} else if (msg.hidden()[s] > 0) {
				// 隐藏手牌：服务端下发"透明牌真实牌面 + 其余未知占位"的记法：透明牌保持原位（玻璃牌面可透视），
				// 普通牌统一排到末尾显示为牌背——既向对手隐藏牌面，也隐藏"哪几个位置是透明牌"之外的排列信息
				List<Integer> masked = null;
				try {
					masked = MahjongTileNotation.parse(orEmpty(msg.hands()[s]));
				} catch (RuntimeException ignored) {
				}
				if (masked == null || masked.size() != msg.hidden()[s]) {
					List<Integer> backs = new ArrayList<>();
					for (int k = 0; k < msg.hidden()[s]; k++)
						backs.add(-1);
					handCodes.add(backs);
				} else {
					List<Integer> ordered = new ArrayList<>(masked.size());
					int backCount = 0;
					for (int c : masked)
						if (c < 0)
							backCount++;
						else
							ordered.add(c);
					for (int k = 0; k < backCount; k++)
						ordered.add(-1);
					handCodes.add(ordered);
				}
			} else {
				try {
					handCodes.add(MahjongTileNotation.parse(orEmpty(msg.hands()[s])));
				} catch (RuntimeException e) {
					handCodes.add(List.of());
				}
			}
		}
		List<Slot> slots = slotPlan(TableLayout.plan(geom, st, handCodes, msg.dealAnim() > 0, handSlots), msg);
		// 牌位飞行动画的起点（打牌/加杠/拔北 = 上一包手牌里被移出那张的位置；副露声明牌 = 牌河被取走那张的位置）
		Vec3[] handSrc = new Vec3[4];
		Vec3 claimSrc = null;
		if (prev != null && !synthetic && msg.dealAnim() == 0) {
			for (int s = 0; s < 4; s++) {
				List<Integer> prevHand = MahjongTileNotation.parse(orEmpty(prev.hands()[s]));
				List<Integer> newHand = MahjongTileNotation.parse(orEmpty(msg.hands()[s]));
				// 牌河多一张 = 该家打牌：起点取服务端记录的被切牌位置（隐藏手牌也能对准槽位；
				// 不能用首次差异推位置——理牌后差异点常在最左侧，会让右侧切出的牌从最左边飞起）
				List<Integer> prevRiver = MahjongTileNotation.parse(orEmpty(prev.rivers()[s]));
				List<Integer> newRiver = MahjongTileNotation.parse(orEmpty(msg.rivers()[s]));
				if (newRiver.size() > prevRiver.size()) {
					int idx = s < msg.discardPos().length && msg.discardPos()[s] >= 0
							? msg.discardPos()[s]
							: firstRemovedIndex(prevHand, newHand);
					handSrc[s] = idx >= 0 && idx < TableLayout.HAND_SIZE
							? TableLayout.standingEntityPos(TableLayout.handTarget(geom, s, idx))
							: TableLayout.standingEntityPos(geom.rows()[s]);
				} else {
					// 手牌少一张（副露/杠）：起点为被移出那张的位置
					int idx = firstRemovedIndex(prevHand, newHand);
					if (idx >= 0)
						handSrc[s] = TableLayout.standingEntityPos(TableLayout.handTarget(geom, s, idx));
				}
				// 牌河少一张 = 被副露：起点为被取走那张在牌河中的位置
				if (newRiver.size() < prevRiver.size())
					claimSrc = TableLayout.riverTarget(geom, s, prevRiver.size() - 1);
			}
		}
		if (msg.dealAnim() > 0) {
			// 发牌动画：整桌清场后按 delay 逐张生成
			discardAll(level, table);
			for (Slot slot : slots)
				spawnSlot(level, table, key, geom, slot, true, true);
		} else {
			// 强制摊手时服务端的"推倒/盖牌"（立→平）在客户端早已摊平、外观签名不变 → 推倒动画被吞掉；
			// 隐藏手牌亮牌时 key 由"牌背"变成"牌面"（旧实体不在新 key 下）→ 同样没有立牌可倒。
			// 两种情形都先按新 key 生成一张立牌，再交给 diffApply 走推倒过渡动画（先复位再播放）
			if (prev != null && !synthetic)
				resetHandsForReveal(level, table, geom, slots, msg, prev, forcedOpen);
			diffApply(level, table, geom, st, msg, slots, handSrc, claimSrc);
		}
		// 对局状态：桌心悬浮文本显示局目/本场/牌山剩余；结算横幅由 boss 栏渲染
		updateCenterInfo(level, table, geom.center(), st, msg);
		updateBossBar(key, st, msg);
		// AI 座位假玩家（回放合成包不驱动；回放结束后由最后真实包恢复）
		if (!synthetic)
			updateAvatars(level, key, origin, geom, msg);
		// 动作反馈：AI 假玩家打牌/鸣牌/和牌时挥手（对比上一包，仅对局中、非发牌、非回放触发）
		if (prev != null && prev.dealAnim() == 0 && msg.dealAnim() == 0 && !synthetic)
			triggerAvatarWaves(key, prev, msg);
	}

	// ==================================================================
	// AI 座位假玩家（客户端-only RemotePlayer，替代旧 MahjongAvatarEntity）
	// ==================================================================

	/**
	 * 按同步包增删建本桌各 AI 座位的假玩家：avatarForms[seat] 空 = 无假玩家（移除）；
	 * 角色/档案变化或实体丢失 → 重建。每次调用把隐形载具钉在座位站位（面朝桌心），
	 * 玩家位置由原版骑乘定位（rideTick → positionRider 挂点跟随载具）驱动，不直接钉玩家坐标。
	 */
	private static void updateAvatars(ClientLevel level, long key, BlockPos origin, TableLayout.Geom geom,
			MahjongTablePayload.ViewMessage msg) {
		Map<Integer, Avatar> avatars = AVATARS.computeIfAbsent(key, k -> new HashMap<>());
		Vec3 center = geom.center();
		TABLE_CENTERS.put(key, center); // 记录桌心，供形象落地后重调视线
		boolean waiting = msg.phase() == RichiTableState.PHASE_WAITING;
		// 等待阶段（未开局/局间）恒为站姿：站立并受原版重力落地；对局中才按客户端"AI 形象姿态"配置
		boolean wantStanding = waiting || mcr.riichi_arise.RichiConfig.MAHJONG_AVATAR_STANDING.get();
		for (int seat = 0; seat < 4; seat++) {
			// 三麻（playerCount=3）不存在的第 4 席按空座处理：四麻切三麻后该席已有假玩家会被移除
			String form = seat >= msg.playerCount() ? "" : orEmpty(msg.avatarForms()[seat]);
			Avatar cur = avatars.get(seat);
			if (form.isEmpty() || form.equals("npc")) {
				// 空座；或 NPC 参战座位（真实实体在服务端钉位渲染，客户端不建假玩家）
				if (cur != null) {
					avatars.remove(seat);
					removeAvatar(cur);
				}
				continue;
			}
			Vec3 pos = waiting
					// 等待阶段：预约后即出现，站在桌边随机位置（创建时定一次，不随同步重摆）；同座位标记向内收 0.25
				? center.add(level.random.nextDouble() * 3.5 - 1.75, 0,
						level.random.nextDouble() * 3.5 - 1.75)
					: center.add(geom.outs()[seat].scale(geom.seatDist() + (geom.small() ? 0.25 : -0.25))); // 小型外移 0.5 格；大风盘保持原内收 0.25 格
			float yaw = (float) Math.toDegrees(Math.atan2(-(center.x - pos.x), center.z - pos.z));
			UUID uuid = profileUuidOf(key, seat, msg);
			// 显示名：默认角色 = 随机名、有角色 = 角色名（均由服务端解析下发）；缺失时才回退语言键
			String name = orEmpty(msg.avatarNames()[seat]);
			if (name.isEmpty())
				name = Component.translatable("gui.riichi_arise.lobby.ch_" + form).getString();
			if (cur == null || cur.player().isRemoved() || !cur.form().equals(form)
					|| !cur.profileUuid().equals(uuid)
					|| cur.standing() != wantStanding) {
				if (cur != null) {
					avatars.remove(seat);
					removeAvatar(cur);
				}
				cur = createAvatar(level, form, uuid, name, pos, yaw, wantStanding);
				if (cur != null)
					avatars.put(seat, cur);
			}
			// 等待阶段不钉位（保持创建时的随机站位）；开局后钉到座位站位
			if (cur != null && !waiting) {
				pinAvatar(origin.getY(), pos, yaw, cur);
				lookAtTableCenter(cur, center, pos); // 对局中始终看向牌桌中心点
			}
		}
		if (avatars.isEmpty())
			AVATARS.remove(key);
	}

	/**
	 * 角色本地皮肤（{@code assets/riichi_arise/textures/entity/ai/<角色 id>.png}）；
	 * 默认角色或贴图缺失时返回 null（由原版玩家渲染器使用档案/默认皮肤）。
	 * 手臂模型按贴图自动判定（{@link #isSlimSkin}）：细手臂皮肤 4px 展开区只画 3px，最右两列透明。
	 */
	private static net.minecraft.client.resources.PlayerSkin localSkin(String form) {
		var tex = mcr.riichi_arise.game.riichi.AiCharacters.skin(form);
		if (tex == null)
			return null;
		Minecraft mc = Minecraft.getInstance();
		if (mc.getResourceManager() == null || mc.getResourceManager().getResource(tex).isEmpty())
			return null;
		var model = isSlimSkin(tex)
				? net.minecraft.client.resources.PlayerSkin.Model.SLIM
				: net.minecraft.client.resources.PlayerSkin.Model.WIDE;
		return new net.minecraft.client.resources.PlayerSkin(tex, "",
				null, null, model, false);
	}

	/** 皮肤是否为细手臂（slim）：64×64 且手臂展开区最右两列（x=54/55）透明；旧版 64×32 皮肤一律经典手臂。 */
	private static boolean isSlimSkin(net.minecraft.resources.ResourceLocation tex) {
		return SLIM_CACHE.computeIfAbsent(tex, t -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.getResourceManager() == null)
				return false;
			var res = mc.getResourceManager().getResource(t);
			if (res.isEmpty())
				return false;
			try (var in = res.get().open()) {
				com.mojang.blaze3d.platform.NativeImage img = com.mojang.blaze3d.platform.NativeImage.read(in);
				try {
					if (img.getWidth() != 64 || img.getHeight() != 64)
						return false;
					// getPixelRGBA 为内存 RGBA 字节序（小端）→ alpha 在最高字节
					return (img.getPixelRGBA(54, 20) >>> 24) == 0 && (img.getPixelRGBA(55, 20) >>> 24) == 0;
				} finally {
					img.close();
				}
			} catch (java.io.IOException e) {
				return false;
			}
		});
	}

	/**
	 * 创建客户端-only 假玩家（原版玩家模型）：注入 PlayerInfo（渲染/皮肤查询）→ 加入 ClientLevel。
	 * 皮肤：有角色的座位用本地贴图 {@code textures/entity/ai/<角色id>.png}（缺文件回退档案/默认皮肤）；
	 * 默认角色用档案皮肤（服务端下发的在线同名玩家 UUID，无则原版默认皮肤）。
	 * 站姿模式：无载具，tick 跑原版（真实重力/碰撞自然落定），由 pinAvatar 每包钉水平站位；
	 * 坐姿模式：隐形 marker 盔甲架作载具，isPassenger → riding 坐姿，tick 置空，位置由载具维护。
	 *
	 * @param standing 是否站姿：对局中取客户端配置，等待阶段恒为 true（站立并受重力落地）
	 */
	private static Avatar createAvatar(ClientLevel level, String form, UUID uuid, String name,
			Vec3 pos, float yaw, boolean standing) {
		GameProfile profile = new GameProfile(uuid, name);
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() != null)
			mc.getConnection().playerInfoMap.put(uuid, new PlayerInfo(profile, false));
		net.minecraft.client.resources.PlayerSkin localSkin = localSkin(form);
		net.minecraft.world.entity.decoration.ArmorStand seat = null;
		double rideY = pos.y; // 初始置于站位高度，随后由 settleAvatars 用原版碰撞落定到脚下实际表面
		if (!standing) {
			seat = new net.minecraft.world.entity.decoration.ArmorStand(
					net.minecraft.world.entity.EntityType.ARMOR_STAND, level) {
				@Override
				public boolean isPushable() {
					return false; // 不被玩家推动
				}
				@Override
				public boolean canBeCollidedWith() {
					return false; // 不阻挡玩家移动（保留碰撞箱仅用于落定地面）
				}
				@Override
				protected net.minecraft.world.phys.Vec3 getPassengerAttachmentPoint(
						net.minecraft.world.entity.Entity entity,
						net.minecraft.world.entity.EntityDimensions dimensions, float f) {
					// 骑乘点 = 载具自身高度（模型高度偏移清零）：骑手实体 y 即载具 y，
					// 坐姿模型按原版骑乘渲染下压（若下压后脚沉入地表，可把 0.60 改回）
					return new net.minecraft.world.phys.Vec3(0.0, 0.0, 0.0);
				}
			};
			seat.setId(--nextAvatarEntityId); // 客户端唯一负数 id
			seat.setInvisible(true); // 不渲染（不再设 marker 位：marker 无碰撞箱 → 原版重力无法落定、骑乘点异常）
			seat.setSilent(true);
			seat.noPhysics = false; // 保留碰撞箱供 settleAvatars 用原版碰撞落定（载具有乘客时 aiStep 不自动落定）
			seat.moveTo(pos.x, rideY, pos.z, yaw, 0.0f);
			seat.setOldPosAndRot();
			level.addEntity(seat);
		}
		RemotePlayer player = new RemotePlayer(level, profile) {
			@Override
			public boolean isControlledByLocalInstance() {
				return true; // RemotePlayer 默认返回 false，导致 aiStep 不执行重力/碰撞 → 悬空不掉落；强制本地模拟
			}

			@Override
			public net.minecraft.client.resources.PlayerSkin getSkin() {
				// 有角色的座位用本地贴图；默认角色走 PlayerInfo 的档案皮肤
				return localSkin != null ? localSkin : super.getSkin();
			}

			@Override
			public boolean isModelPartShown(net.minecraft.world.entity.player.PlayerModelPart part) {
				// 皮肤外层（帽子/外套/袖子/裤腿）是否显示取决于服务端同步的部件位掩码，
				// 客户端假玩家没有该同步（默认全 0）→ 只显示内层皮肤；这里强制全部显示
				return true;
			}

			@Override
			public void tick() {
				// 站姿：跑原版 tick，受原版重力/碰撞自然落定到地面（真实玩家假人，不会穿方块）；
				// 坐姿：乘客位置由载具维护，不走物理
				if (standing)
					super.tick();
			}
		};
		player.setId(--nextAvatarEntityId); // 客户端唯一负数 id
		player.noPhysics = false; // 参与碰撞：站姿落到地面/平台而非穿过方块
		if (!standing) {
			player.startRiding(seat, true);
			// 出生即在载具挂点，避免首帧闪现原点；此后位置由 rideTick 维护
			player.setPos(pos.x, rideY, pos.z);
		} else {
			// 站姿：初始置于站位高度，随后由原版重力自然下落落定
			player.setPos(pos.x, pos.y, pos.z);
		}
		player.setOldPosAndRot();
		level.addEntity(player);
		return new Avatar(player, seat, form, uuid, standing);
	}

	/**
	 * 钉住形象在座位站位（面朝桌心）：坐姿模式更新隐形载具，坐标由 rideTick 挂点跟随；
	 * 站姿模式每包把假玩家钉到站位水平 x/z。两种模式均同步头身朝向。
	 * 竖直：站姿与坐姿均由原版重力自行落定（此处保持当前 y 不干预），载具/假人落到脚下实际表面。
	 * 水平始终钉住站位 x/z。
	 */
	private static void pinAvatar(double baseY, Vec3 pos, float yaw, Avatar avatar) {
		RemotePlayer player = avatar.player();
		if (player.isRemoved())
			return;
		if (avatar.standing()) {
			player.moveTo(pos.x, player.getY(), pos.z, yaw, 0.0f);
			player.setOldPosAndRot();
			player.yBodyRot = yaw;
			player.yBodyRotO = yaw;
			player.yHeadRot = yaw;
			player.yHeadRotO = yaw;
			return;
		}
		net.minecraft.world.entity.decoration.ArmorStand st = avatar.seat();
		st.moveTo(pos.x, st.getY(), pos.z, yaw, 0.0f);
		st.setOldPosAndRot();
		st.yBodyRot = yaw;
		st.yBodyRotO = yaw;
		// 玩家朝向：renderHumanoid 在 isPassenger 时身体 yaw 取自载具，这里补头部朝向
		player.yBodyRot = yaw;
		player.yBodyRotO = yaw;
		player.yHeadRot = yaw;
		player.yHeadRotO = yaw;
	}

	/** 删除假玩家实体（含隐形骑乘载具）；无其他假玩家引用同一档案时清理注入的 PlayerInfo（防标签页/皮肤缓存残留） */
	private static void removeAvatar(Avatar avatar) {
		AVATAR_GROUND.remove(avatar); // 清理落地跟踪
		if (avatar.seat() != null)
			avatar.seat().discard();
		avatar.player().discard();
		boolean inUse = AVATARS.values().stream().flatMap(m -> m.values().stream())
				.anyMatch(a -> a.profileUuid().equals(avatar.profileUuid()));
		if (!inUse) {
			var connection = Minecraft.getInstance().getConnection();
			if (connection != null)
				connection.playerInfoMap.remove(avatar.profileUuid());
		}
	}

	/** 删除本桌全部 AI 假玩家（等待阶段/桌子销毁/回放恢复失败） */
	private static void discardAvatars(long key) {
		Map<Integer, Avatar> avatars = AVATARS.remove(key);
		if (avatars != null)
			for (Avatar avatar : avatars.values())
				removeAvatar(avatar);
		TABLE_CENTERS.remove(key);
	}

	/** 动作反馈：对比上一包，座位有动作（打牌/鸣牌/和牌等）则让对应 AI 假玩家挥手 */
	private static void triggerAvatarWaves(long key, MahjongTablePayload.ViewMessage prev,
			MahjongTablePayload.ViewMessage msg) {
		Map<Integer, Avatar> avatars = AVATARS.get(key);
		if (avatars == null || avatars.isEmpty())
			return;
		for (int seat = 0; seat < 4; seat++) {
			Avatar a = avatars.get(seat);
			if (a == null || a.player().isRemoved())
				continue;
			boolean acted = !java.util.Objects.equals(prev.rivers()[seat], msg.rivers()[seat])
					|| !java.util.Objects.equals(prev.melds()[seat], msg.melds()[seat])
					|| prev.handsExposed()[seat] != msg.handsExposed()[seat];
			if (!acted)
				continue;
			// 触发挥手：置 swinging，swingTime 由 driveAvatarWaves 逐 tick 推进（空手挥动 6 tick）
			a.player().swinging = true;
			a.player().swingTime = -1;
			a.player().swingingArm = net.minecraft.world.InteractionHand.MAIN_HAND;
		}
	}

	/** 挥手动画时长（tick，与原版 LivingEntity#getCurrentSwingDuration 默认值一致） */
	private static final int SWING_TICKS = 6;

	/**
	 * AI 假玩家挥手驱动：坐姿假玩家的 {@code tick()} 为空实现（乘客位置由载具维护、不走物理），
	 * 原版的 {@code updateSwingTime()} 因此不会执行——只置 {@code swinging} 手臂不会动。
	 * 这里按原版等价推进 {@code oAttackAnim/attackAnim}，渲染层（LivingEntityRenderer →
	 * HumanoidModel.setupAttackAnimation）才会真正挥臂；站姿由原版 tick 自行推进。
	 */
	private static void driveAvatarWaves() {
		for (Map<Integer, Avatar> seats : AVATARS.values()) {
			for (Avatar a : seats.values()) {
				if (a.standing())
					continue; // 站姿：原版 tick 自行推进挥手动画
				net.minecraft.client.player.RemotePlayer p = a.player();
				if (p.isRemoved())
					continue;
				p.oAttackAnim = p.attackAnim; // 原版在 baseTick 里做；坐姿不走 tick，须在此补
				if (p.swinging) {
					p.swingTime++;
					if (p.swingTime >= SWING_TICKS) {
						p.swingTime = 0;
						p.swinging = false;
					}
				} else {
					p.swingTime = 0;
				}
				p.attackAnim = p.swingTime / (float) SWING_TICKS;
			}
		}
	}

	/**
	 * 形象落地修正（每 tick）：坐姿载具与站姿假人都不会跑原版物理（{@code RemotePlayer#aiStep} 不调 super →
	 * 没有 travel/重力），统一在此处理——先抬出方块（出生点低于地表时就是"沉进地里"），再让悬空的下落。
	 */
	private static void settleAvatars(ClientLevel level) {
		for (Map<Integer, Avatar> seats : AVATARS.values()) {
			for (Avatar a : seats.values()) {
				// 站姿：假人本体；坐姿：隐形载具（乘客位置由载具挂点维护）
				net.minecraft.world.entity.Entity body = a.standing() ? a.player() : a.seat();
				if (body == null || body.isRemoved())
					continue;
				liftOutOfBlocks(level, body);
				if (!body.onGround()) // 尚未落地：用原版碰撞向下移动（受方块支撑即停）
					body.move(net.minecraft.world.entity.MoverType.SELF, new net.minecraft.world.phys.Vec3(0.0, -0.04, 0.0));
				body.setOldPosAndRot();
			}
		}
	}

	/** 实体包围盒卡进方块时逐 1/16 抬出（最多 2 格）：修复出生点低于地表导致的"沉进地里"（只看方块，不算其他实体） */
	private static void liftOutOfBlocks(ClientLevel level, net.minecraft.world.entity.Entity e) {
		for (int i = 0; i < 32 && !level.noBlockCollision(e, e.getBoundingBox()); i++) {
			e.setPos(e.getX(), e.getY() + 1.0 / 16.0, e.getZ());
			e.setOldPosAndRot();
		}
	}

	/** 让假玩家头部朝向牌桌中心点（横向 yaw + 垂直俯角），对局中始终看向桌心 */
	private static void lookAtTableCenter(Avatar a, Vec3 center, Vec3 pos) {
		net.minecraft.client.player.RemotePlayer p = a.player();
		if (p.isRemoved())
			return;
		float yaw = (float) Math.toDegrees(Math.atan2(-(center.x - pos.x), center.z - pos.z));
		double dy = p.getY() + p.getEyeHeight() - center.y;
		double dist = Math.max(1.0E-4, Math.hypot(center.x - pos.x, center.z - pos.z));
		float pitch = (float) Math.toDegrees(Math.atan2(dy, dist)); // 中心低于视线 → 向下俯视（xRot 为正）
		p.setYRot(yaw);
		p.yBodyRot = yaw;
		p.yHeadRot = yaw;
		p.yRotO = yaw;
		p.yBodyRotO = yaw;
		p.yHeadRotO = yaw;
		p.setXRot(pitch);
		p.xRotO = pitch;
		// 坐姿模式：载具随同朝向（身体 yaw 取自载具），头部已随上
		if (!a.standing() && a.seat() != null) {
			a.seat().setYRot(yaw);
			a.seat().yBodyRot = yaw;
			a.seat().yBodyRotO = yaw;
			a.seat().setYHeadRot(yaw);
			a.seat().yHeadRotO = yaw;
		}
	}

	/** 假玩家档案 UUID：human 有皮肤 UUID 用之（拉取正版皮肤），否则按桌+座位派生稳定 UUID */
	private static UUID profileUuidOf(long key, int seat, MahjongTablePayload.ViewMessage msg) {
		String skin = orEmpty(msg.avatarSkins()[seat]);
		if (!skin.isEmpty()) {
			try {
				return UUID.fromString(skin);
			} catch (IllegalArgumentException ignored) {
			}
		}
		return UUID.nameUUIDFromBytes(("murmol_avatar:" + key + ":" + seat)
				.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	/**
	 * 为整桌摆位项建立稳定 key 与外观签名（纯客户端索引，服务端数据/协议不变）：
	 * 手牌 hand:座位:牌面:同面内序（摸打/重排只换位置不换 key → 移动走插值）；
	 * 牌河 river:座位:下标；副露 meld:座位:序（组追加式，声明牌随组追加）；拔北 kita:座位:序；
	 * 点棒 stick:座位:序；宝牌 dora:叠:0底/1顶；座位标签 seat:座位。签名 = kind:牌面:正反面:立平（item）/ 文本（text）。
	 */
	private static List<Slot> slotPlan(List<TableLayout.Placement> plan, MahjongTablePayload.ViewMessage msg) {
		List<Slot> out = new ArrayList<>(plan.size());
		@SuppressWarnings("unchecked")
		Map<Integer, Integer>[] handOcc = new HashMap[4];
		int[] meldSeq = new int[4];
		Map<Integer, Integer> doraOcc = new HashMap<>();
		for (TableLayout.Placement p : plan) {
			switch (p.kind()) {
				case TableLayout.KIND_SEAT -> {
					// 局目轮换（轮庄）后世界位置的东南西北随之改变：庄家位 = (round-1)%4，
					// 各位置风 = SEAT_NAMES[floorMod(座位 - 庄家位, 4)]（与服务端 broadcastRoundStart 同式）
					int dealer = (msg.round() - 1) % 4;
					String wind = msg.round() > 0
							? TableLayout.SEAT_NAMES[Math.floorMod(p.seat() - dealer, 4)]
							: TableLayout.SEAT_NAMES[p.seat()];
					String text = (msg.turnSeat() == p.seat() ? "▶ " : "") + wind
							+ "\n" + msg.points()[p.seat()];
					out.add(new Slot(p, "seat:" + p.seat(), "T:" + text, text));
				}
				case TableLayout.KIND_HAND -> {
					Map<Integer, Integer> occ = handOcc[p.seat()];
					if (occ == null)
						occ = handOcc[p.seat()] = new HashMap<>();
					int n = occ.merge(p.code(), 1, Integer::sum);
					String key;
					if (msg.hidden()[p.seat()] > 0) {
						// 牌背全同（code=-1）：按槽位编号。刚摸的那张固定 "d"；手切后由牌行末位继承 "d"，
						// 于是"分离的牌滑进牌行、牌行消失一张"——连续手切也不会退化成摸切观感
						//（旧做法每帧统一 +1 偏移，连续手切时前后帧偏移相同，仍表现为刚摸的那张消失）
						boolean last = p.index() == msg.hidden()[p.seat()] - 1;
						boolean drawn = msg.drawnSeat() == p.seat() && last;
						boolean handCut = msg.drawnSeat() != p.seat() && msg.tedashi()[p.seat()] == 1 && last;
						key = "hand:" + p.seat() + ":h:" + (drawn || handCut ? "d" : String.valueOf(p.index()));
					} else {
						key = "hand:" + p.seat() + ":" + p.code() + ":" + (n - 1);
					}
					out.add(new Slot(p, key, itemSig(p), null));
				}
				case TableLayout.KIND_RIVER ->
					out.add(new Slot(p, "river:" + p.seat() + ":" + p.index(), itemSig(p), null));
				case TableLayout.KIND_MELD ->
					out.add(new Slot(p, "meld:" + p.seat() + ":" + meldSeq[p.seat()]++, itemSig(p), null));
				case TableLayout.KIND_KITA ->
					// 拔北牌位（三麻）：独立 key 序列，不随副露组增减换号（新增副露时拔北牌只平移不重建）
					out.add(new Slot(p, "kita:" + p.seat() + ":" + p.index(), itemSig(p), null));
				case TableLayout.KIND_STICK ->
					out.add(new Slot(p, "stick:" + p.seat() + ":" + p.index(), itemSig(p), null));
				default -> {
					// KIND_DORA：底张固定 0，上张（翻开与否只影响签名）固定 1
					int n = doraOcc.merge(p.index(), 1, Integer::sum);
					out.add(new Slot(p, "dora:" + p.index() + ":" + (n - 1), itemSig(p), null));
				}
			}
		}
		return out;
	}

	/** item 外观签名：类别 + 牌面 + 正反面 + 立牌/平躺（位置与朝向不参与，变化走实体插值） */
	private static String itemSig(TableLayout.Placement p) {
		return p.kind() + ":" + p.code() + ":" + p.faceUp() + ":" + (p.axis() == null);
	}

	/** 该签名是否为立牌手牌（axis==null → 末位 ":true"；平躺/牌河等均为 ":false"） */
	private static boolean isStandingHandSig(String sig) {
		return sig.endsWith(":true");
	}

	/**
	 * 增量 diff：签名未变 → 复用（位置/朝向变化走 display 自带插值 lerpTo 平滑移动）；
	 * 签名变化/实体丢失 → 只重建该实体（同帧替换）；新 key → 生成；消失 key → 删除。
	 * 座位标签为文本牌位，内容变化就地 load 更新。
	 */
	private static void diffApply(ClientLevel level, TableRender table, TableLayout.Geom geom,
			RichiTableState st, MahjongTablePayload.ViewMessage msg, List<Slot> slots,
			Vec3[] handSrc, Vec3 claimSrc) {
		Map<String, Entry> fresh = new HashMap<>();
		table.handIds.clear();
		for (Slot slot : slots) {
			TableLayout.Placement p = slot.p();
			if (TableLayout.KIND_SEAT.equals(p.kind())) {
				applyText(level, table, fresh, slot.slotKey(), p.pos(), "richi_seat_" + p.seat(), slot.text());
				continue;
			}
			Entry old = table.keyed.get(slot.slotKey());
			Entity e = old == null ? null : level.getEntity(old.entityId());
			if (e != null && e.isRemoved())
				e = null;
			// 局终推倒：手牌由立牌转平躺（和牌/流局）→ 保留实体走旋转倒下过渡动画（所有手牌同帧触发）
			if (e != null && !old.sig().equals(slot.sig())
					&& TableLayout.KIND_HAND.equals(p.kind()) && p.axis() != null
					&& isStandingHandSig(old.sig())) {
				// hands 已改为记号串：解析后按张数判断最后一张（刚摸的牌无倒下延迟）
				boolean isDrawn = st.drawnSeat == p.seat() && p.index() >= 0
						&& p.index() == mcr.riichi_arise.game.MahjongTileNotation.parse(st.hands[p.seat()]).size() - 1;
				startFall(e, p, isDrawn ? 0 : FALL_TICKS);
				fresh.put(slot.slotKey(), new Entry(e.getId(), slot.sig()));
				if (p.index() >= 0)
					table.handIds.put((p.seat() << 8) | p.index(), e.getId());
				continue;
			}
			if (e != null && !old.sig().equals(slot.sig())) {
				e.discard(); // 外观变化：只重建该实体（同帧替换，不闪烁）
				e = null;
			}
			if (e == null) {
				e = spawnSlot(level, table, 0, geom, slot, false, false);
				// 新生成的牌：播放"浮起→飞到目标位→降落"的短动画
				Vec3 src = flightSource(p, handSrc, claimSrc);
				if (e != null && src != null)
					startTileFlight(e, p, src);
			} else if (!FLIGHTS.containsKey(e.getId()) && moved(e, p)) {
				// 仅位置/朝向变化：实体自带插值（teleport_duration）平滑过渡，不重建
				// （拔北飞行中的实体由飞行动画逐 tick 驱动，跳过插值以免抖动）
				e.lerpTo(p.pos().x, p.pos().y, p.pos().z, p.yRot(), 0, 0);
			}
			if (e != null) {
				fresh.put(slot.slotKey(), new Entry(e.getId(), slot.sig()));
				if (TableLayout.KIND_HAND.equals(p.kind()) && p.index() >= 0)
					table.handIds.put((p.seat() << 8) | p.index(), e.getId());
			}
		}
		// 消失的牌位 → 删除实体（中心信息/结算横幅已改由 boss 栏渲染）
		for (Map.Entry<String, Entry> en : table.keyed.entrySet()) {
			if (fresh.containsKey(en.getKey()))
				continue;
			Entity e = level.getEntity(en.getValue().entityId());
			if (e != null && !e.isRemoved())
				e.discard();
		}
		table.keyed.clear();
		table.keyed.putAll(fresh);
	}

	/**
	 * 局终推倒的"先复位再播放"：服务端把某家手牌从立牌改为推倒/盖牌时，客户端可能找不到可倒的立牌实体——
	 * 强制摊手视角下早已摊平（外观签名不变），参与者视角下该家手牌此前是隐藏牌背（key 由牌背变为牌面）。
	 * 这里按新 key 就地生成一张立牌（登记立牌签名），随后 diffApply 检测到"立牌 → 平躺"即播放推倒动画。
	 */
	private static void resetHandsForReveal(ClientLevel level, TableRender table, TableLayout.Geom geom,
			List<Slot> slots, MahjongTablePayload.ViewMessage msg, MahjongTablePayload.ViewMessage prev,
			boolean forcedOpen) {
		for (Slot slot : slots) {
			TableLayout.Placement p = slot.p();
			if (!TableLayout.KIND_HAND.equals(p.kind()) || p.axis() == null)
				continue;
			int seat = p.seat();
			// 仅"服务端本包由立牌改为推倒/盖牌"的家需要复位
			if (prev.handsExposed()[seat] != RichiTableState.HAND_STAND
					|| msg.handsExposed()[seat] == RichiTableState.HAND_STAND)
				continue;
			// 普通视角下 key 未变（可见手牌）时，diffApply 本来就有立牌可倒，无需复位
			if (!forcedOpen && table.keyed.get(slot.slotKey()) != null)
				continue;
			float standYaw = (float) Math.toDegrees(Math.atan2(-geom.outs()[seat].x, geom.outs()[seat].z));
			TableLayout.Placement stand = new TableLayout.Placement(TableLayout.KIND_HAND,
					TableLayout.standingEntityPos(p.base()), p.base(), p.code(), standYaw, null, p.faceUp(),
					seat, p.index(), 0);
			spawnKeyed(level, table, slot.slotKey(), itemSig(stand),
					handNbt(p, true, TableLayout.handTag(seat, p.index()), level), stand.pos(), standYaw);
		}
	}

	/** 位置或朝向是否变化（决定是否走插值移动） */
	private static boolean moved(Entity e, TableLayout.Placement p) {
		return e.distanceToSqr(p.pos().x, p.pos().y, p.pos().z) > 1.0E-8
				|| Math.abs(Mth.wrapDegrees(e.getYRot() - p.yRot())) > 0.01F;
	}

	/**
	 * 纯文本牌位（座位标签/中心信息/横幅）：签名 = 文本内容。
	 * 实体健在且位置一致：文本不变 → 复用；文本变化 → 就地 load 更新 text NBT（不重建）。
	 * 实体丢失/位置不符 → 重建。结果登记进 fresh。
	 */
	private static void applyText(ClientLevel level, TableRender table, Map<String, Entry> fresh,
			String slotKey, Vec3 pos, String tag, String text) {
		Entry old = table.keyed.get(slotKey);
		Entity e = old == null ? null : level.getEntity(old.entityId());
		if (e instanceof TextDisplay td && !td.isRemoved()
				&& td.distanceToSqr(pos.x, pos.y, pos.z) <= 1.0E-8) {
			if (old.sig().equals(text)) {
				fresh.put(slotKey, old); // 完全不变：复用
				return;
			}
			// 文本变化：就地更新 NBT（Entity.load 需带 Pos 保持位置）
			td.load(withPose(DisplayEntityNbt.textDisplay(level.registryAccess(), tag, text),
					new Vec3(td.getX(), td.getY(), td.getZ()), td.getYRot()));
			fresh.put(slotKey, new Entry(td.getId(), text));
			return;
		}
		if (e != null && !e.isRemoved())
			e.discard();
		Entity ne = spawnLocal(level, DisplayEntityNbt.textDisplay(level.registryAccess(), tag, text), pos, 0);
		if (ne != null)
			fresh.put(slotKey, new Entry(ne.getId(), text));
	}

	/** text NBT 附带 Pos/Motion/Rotation（Entity.load 缺 Pos 会把实体归零） */
	private static CompoundTag withPose(CompoundTag nbt, Vec3 pos, float yRot) {
		ListTag posTag = new ListTag();
		posTag.add(DoubleTag.valueOf(pos.x));
		posTag.add(DoubleTag.valueOf(pos.y));
		posTag.add(DoubleTag.valueOf(pos.z));
		nbt.put("Pos", posTag);
		nbt.put("Motion", new ListTag());
		ListTag rotTag = new ListTag();
		rotTag.add(FloatTag.valueOf(yRot));
		rotTag.add(FloatTag.valueOf(0.0F));
		nbt.put("Rotation", rotTag);
		return nbt;
	}

	/**
	 * 生成单个摆位项：animate 时按延迟调度（发牌 stagger / 推倒级联），否则立即生成并返回实体。
	 * 推倒/盖牌级联：先立牌（登记过渡签名），delay+10 平躺——同 key 二次生成自动替换立牌。
	 */
	/** 发牌时逐张的落牌音效：深板岩放置声（客户端本地播放，音量压低） */
	private static void playDealSound(ClientLevel level, Vec3 pos) {
		level.playLocalSound(pos.x, pos.y, pos.z,
				net.minecraft.sounds.SoundEvents.DEEPSLATE_PLACE,
				net.minecraft.sounds.SoundSource.BLOCKS, 0.5f, 0.85f, false);
	}

	private static Entity spawnSlot(ClientLevel level, TableRender table, long tableKey, TableLayout.Geom geom,
			Slot slot, boolean animate, boolean deal) {
		TableLayout.Placement p = slot.p();
		Entity out = null;
		switch (p.kind()) {
			case TableLayout.KIND_SEAT -> {
				if (animate)
					schedule(tableKey, p.delay(), () -> spawnKeyed(level, table, slot.slotKey(), slot.text(),
							DisplayEntityNbt.textDisplay(level.registryAccess(), "richi_seat_" + p.seat(), slot.text()),
							p.pos(), 0));
				else
					out = spawnKeyed(level, table, slot.slotKey(), slot.text(),
							DisplayEntityNbt.textDisplay(level.registryAccess(), "richi_seat_" + p.seat(), slot.text()),
							p.pos(), 0);
			}
			case TableLayout.KIND_HAND -> {
				String tag = TableLayout.handTag(p.seat(), p.index());
				if (p.axis() == null) {
					// 立牌（隐藏座位为未知牌面 ItemStack，不带座位标记）
					if (animate)
						schedule(tableKey, p.delay(), () -> {
							// 四张四张发：每批只在第一张播一次落牌音效（避免同帧叠 4 声）
							if (deal && p.index() % 4 == 0)
								playDealSound(level, p.pos());
							recordHand(table, p,
									spawnKeyed(level, table, slot.slotKey(), slot.sig(), handNbt(p, true, tag, level),
											p.pos(), p.yRot()));
						});
					else
						out = recordHand(table, p, spawnKeyed(level, table, slot.slotKey(), slot.sig(),
								handNbt(p, true, tag, level), p.pos(), p.yRot()));
				} else if (animate) {
					// 局终推倒/盖牌：先立牌后平躺（级联），立牌登记过渡签名以便中途 diff 重建
					float standYaw = (float) Math.toDegrees(
							Math.atan2(-geom.outs()[p.seat()].x, geom.outs()[p.seat()].z));
					Vec3 standPos = TableLayout.standingEntityPos(p.base());
					schedule(tableKey, p.delay(), () -> recordHand(table, p, spawnKeyed(level, table, slot.slotKey(),
							"standing:" + slot.sig(), handNbt(p, true, tag, level), standPos, standYaw)));
					schedule(tableKey, p.delay() + 10, () -> recordHand(table, p, spawnKeyed(level, table,
							slot.slotKey(), slot.sig(), handNbt(p, false, null, level), p.pos(), p.yRot())));
				} else {
					out = recordHand(table, p, spawnKeyed(level, table, slot.slotKey(), slot.sig(),
							handNbt(p, false, null, level), p.pos(), p.yRot()));
				}
			}
			case TableLayout.KIND_RIVER, TableLayout.KIND_MELD, TableLayout.KIND_KITA, TableLayout.KIND_DORA -> {
				if (animate)
					schedule(tableKey, p.delay(), () -> {
						if (deal)
							playDealSound(level, p.pos());
						spawnKeyed(level, table, slot.slotKey(), slot.sig(),
								flatNbt(p, level), p.pos(), p.yRot());
					});
				else
					out = spawnKeyed(level, table, slot.slotKey(), slot.sig(), flatNbt(p, level), p.pos(), p.yRot());
			}
			default -> {
				// KIND_STICK：立直点棒
				if (animate)
					schedule(tableKey, p.delay(), () -> spawnKeyed(level, table, slot.slotKey(), slot.sig(),
							stickNbt(level), p.pos(), p.yRot()));
				else
					out = spawnKeyed(level, table, slot.slotKey(), slot.sig(), stickNbt(level), p.pos(), p.yRot());
			}
		}
		return out;
	}

	/** 手牌立牌/平躺 item_display NBT */
	private static CompoundTag handNbt(TableLayout.Placement p, boolean standing, String tag, ClientLevel level) {
		return DisplayEntityNbt.itemDisplay(MahjongTileItem.create(p.code()),
				standing ? DisplayEntityNbt.standingMatrix(TableLayout.TILE_SCALE)
						: DisplayEntityNbt.flatMatrix(TableLayout.TILE_SCALE, p.faceUp()),
				standing ? tag : null, null, level.registryAccess());
	}

	/** 平躺牌（牌河/副露/宝牌）item_display NBT */
	private static CompoundTag flatNbt(TableLayout.Placement p, ClientLevel level) {
		return DisplayEntityNbt.itemDisplay(MahjongTileItem.create(p.code()),
				DisplayEntityNbt.flatMatrix(TableLayout.TILE_SCALE, p.faceUp()), null, null, level.registryAccess());
	}

	/** 立直点棒 item_display NBT */
	private static CompoundTag stickNbt(ClientLevel level) {
		return DisplayEntityNbt.itemDisplay(DisplayEntityNbt.riichiStickStack(),
				DisplayEntityNbt.stickMatrix(TableLayout.TILE_SCALE), null, null, level.registryAccess());
	}

	/** 手牌(座位,下标) → 实体 id 记录（选中升降增量定位用） */
	private static Entity recordHand(TableRender table, TableLayout.Placement p, Entity e) {
		if (e != null && p.index() >= 0)
			table.handIds.put((p.seat() << 8) | p.index(), e.getId());
		return e;
	}

	/** 桌心信息悬浮文本文案（两行）：第一行"东一局 1本场"，第二行"剩余xx张" */
	private static String centerText(RichiTableState st, MahjongTablePayload.ViewMessage msg) {
		String wind = st.roundWind == 0 ? "东" : "南";
		String roundNum = switch (st.round) {
			case 1 -> "一";
			case 2 -> "二";
			case 3 -> "三";
			default -> "四";
		};
		return wind + roundNum + "局 " + st.honba + "本场\n剩余" + msg.wallCount() + "张";
	}

	/**
	 * 更新桌心信息悬浮文本：局目/本场/牌山剩余，置于桌心中央往上 1.5 格。
	 * 文本不变 → 复用；文本变化 → 就地 load 更新 text NBT（不重建）；位置不符/实体丢失 → 重建。
	 */
	private static void updateCenterInfo(ClientLevel level, TableRender table, Vec3 center,
			RichiTableState st, MahjongTablePayload.ViewMessage msg) {
		String text = centerText(st, msg);
		Vec3 pos = center.add(0, 1.5, 0); // 桌心中央往上 1.5 格
		Entity e = table.centerId < 0 ? null : level.getEntity(table.centerId);
		if (e instanceof TextDisplay td && !td.isRemoved()
				&& td.distanceToSqr(pos.x, pos.y, pos.z) <= 1.0E-8) {
			if (table.centerSig.equals(text))
				return; // 完全不变：复用
			// 文本变化：就地更新 NBT（Entity.load 需带 Pos 保持位置）
			td.load(withPose(DisplayEntityNbt.textDisplay(level.registryAccess(), "richi_center", text),
					new Vec3(td.getX(), td.getY(), td.getZ()), td.getYRot()));
			table.centerSig = text;
			return;
		}
		if (e != null && !e.isRemoved())
			e.discard();
		Entity ne = spawnLocal(level, DisplayEntityNbt.textDisplay(level.registryAccess(), "richi_center", text), pos, 0);
		table.centerId = ne == null ? -1 : ne.getId();
		table.centerSig = text;
	}

	// ==================================================================
	// 对局状态 → boss 栏（仅结算横幅；局目/本场/牌山剩余已改桌心悬浮文本）
	// ==================================================================

	/** boss 栏所在的 BossHealthOverlay.events（包级私有，反射获取，缓存 Field） */
	private static Field bossEventsField;

	@SuppressWarnings("unchecked")
	private static Map<UUID, LerpingBossEvent> bossEvents() {
		try {
			if (bossEventsField == null) {
				bossEventsField = BossHealthOverlay.class.getDeclaredField("events");
				bossEventsField.setAccessible(true);
			}
			return (Map<UUID, LerpingBossEvent>) bossEventsField.get(Minecraft.getInstance().gui.getBossOverlay());
		} catch (ReflectiveOperationException | NullPointerException e) {
			return null; // 反射失败：静默降级（不显示状态栏，不影响对局）
		}
	}

	/** 本桌 boss 栏 UUID（由桌 originPacked 派生，稳定唯一；多桌各自独立一条） */
	private static UUID bossBarUuid(long key) {
		return new UUID(0x5249434849424152L ^ key, key);
	}

	/**
	 * 更新本桌 boss 栏：仅显示和牌/结算横幅（局目/本场/牌山剩余已改为桌心悬浮文本）。
	 * 无横幅时移除 boss 栏；桌不可见/等待阶段由调用方传入移除。
	 */
	private static void updateBossBar(long key, RichiTableState st, MahjongTablePayload.ViewMessage msg) {
		Map<UUID, LerpingBossEvent> events = bossEvents();
		if (events == null)
			return;
		String banner = orEmpty(msg.bannerText());
		if (banner.isEmpty()) {
			clearBossBar(key);
			return;
		}
		UUID id = bossBarUuid(key);
		float progress = Mth.clamp(msg.wallCount() / 70.0f, 0.0f, 1.0f);
		LerpingBossEvent bar = events.get(id);
		if (bar == null) {
			bar = new LerpingBossEvent(id, Component.literal(banner), progress,
					net.minecraft.world.BossEvent.BossBarColor.BLUE,
					net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS, false, false, false);
			events.put(id, bar);
		} else {
			bar.setName(Component.literal(banner));
			bar.setProgress(progress);
		}
	}

	/** 移除本桌 boss 栏（对局结束/桌子不存在时） */
	private static void clearBossBar(long key) {
		Map<UUID, LerpingBossEvent> events = bossEvents();
		if (events != null)
			events.remove(bossBarUuid(key));
	}

	/**
	 * 客户端本地生成实体：LevelWriter.addFreshEntity 在 ClientLevel 上是空操作（默认 return false，
	 * 仅 ServerLevel 重写），必须走 ClientLevel.addEntity（与原版 ClientPacketListener.handleAddEntity
	 * 同路径），实体才会进入渲染/tick 管理。
	 */
	private static Entity spawnLocal(ClientLevel level, CompoundTag nbt, Vec3 pos, float yRot) {
		return net.minecraft.world.entity.EntityType.loadEntityRecursive(nbt, level, entity -> {
			entity.moveTo(pos.x, pos.y, pos.z, yRot, 0);
			level.addEntity(entity);
			return entity;
		});
	}

	/** 按 key 生成并登记实体；同 key 旧实体先删除（签名变化重建 / 动画两段替换共用） */
	private static Entity spawnKeyed(ClientLevel level, TableRender table, String slotKey, String sig,
			CompoundTag nbt, Vec3 pos, float yRot) {
		discardKeyed(level, table, slotKey);
		Entity e = spawnLocal(level, nbt, pos, yRot);
		if (e != null)
			table.keyed.put(slotKey, new Entry(e.getId(), sig));
		return e;
	}

	/** 删除某 key 登记的实体并从清单剔除 */
	private static void discardKeyed(ClientLevel level, TableRender table, String slotKey) {
		Entry old = table.keyed.remove(slotKey);
		if (old == null)
			return;
		Entity e = level.getEntity(old.entityId());
		if (e != null && !e.isRemoved())
			e.discard();
	}

	/** 删除本桌全部自建实体（服务端不再有对应真实实体，直接 discard 本端实体） */
	private static void discardAll(ClientLevel level, TableRender table) {
		for (Entry en : table.keyed.values()) {
			Entity e = level.getEntity(en.entityId());
			if (e != null && !e.isRemoved())
				e.discard();
		}
		if (table.centerId >= 0) {
			Entity ce = level.getEntity(table.centerId);
			if (ce != null && !ce.isRemoved())
				ce.discard();
			table.centerId = -1;
		}
		table.keyed.clear();
		table.handIds.clear();
	}

	/** 选中升降：先降旧的、再升新的（±SELECT_LIFT），只移动对应显示实体 */
	private static void moveSelection(ClientLevel level, TableRender table, MahjongTablePayload.ViewMessage msg) {
		shiftHand(level, table, table.last.selectedSeat(), table.last.selectedIndex(), -TableLayout.SELECT_LIFT);
		shiftHand(level, table, msg.selectedSeat(), msg.selectedIndex(), TableLayout.SELECT_LIFT);
	}

	/** 按 (座位, 手牌下标) 找实体并走插值移动（display 自带 pos-rot 插值平滑升降） */
	private static void shiftHand(ClientLevel level, TableRender table, int seat, int index, double dy) {
		if (seat < 0 || index < 0)
			return;
		Integer id = table.handIds.get((seat << 8) | index);
		if (id == null)
			return;
		Entity e = level.getEntity(id);
		if (e == null || e.isRemoved())
			return;
		e.lerpTo(e.getX(), e.getY() + dy, e.getZ(), e.getYRot(), e.getXRot(), 0);
	}

	/** 除 selectedSeat/Index 外全部渲染相关字段一致（record 含数组需逐项比较） */
	private static boolean sameExceptSelection(MahjongTablePayload.ViewMessage a, MahjongTablePayload.ViewMessage b) {
		return a.dealAnim() == b.dealAnim()
				&& Arrays.equals(a.hands(), b.hands())
				&& Arrays.equals(a.hidden(), b.hidden())
				&& Arrays.equals(a.rivers(), b.rivers())
				&& Arrays.equals(a.melds(), b.melds())
				&& Arrays.equals(a.kita(), b.kita())
				&& Arrays.equals(a.riichiRiverIdx(), b.riichiRiverIdx())
				&& Arrays.equals(a.riichiSticks(), b.riichiSticks())
				&& Arrays.equals(a.handsExposed(), b.handsExposed())
				&& Arrays.equals(a.points(), b.points())
				&& Arrays.equals(a.names(), b.names())
				&& Arrays.equals(a.avatarForms(), b.avatarForms())
				&& Arrays.equals(a.avatarNames(), b.avatarNames())
				&& Arrays.equals(a.avatarSkins(), b.avatarSkins())
				&& orEmpty(a.doraWall()).equals(orEmpty(b.doraWall()))
				&& a.revealedIndicators() == b.revealedIndicators()
				&& a.wallCount() == b.wallCount()
				&& a.turnSeat() == b.turnSeat()
				&& a.round() == b.round()
				&& a.roundWind() == b.roundWind()
				&& a.honba() == b.honba()
				&& a.carriedSticks() == b.carriedSticks()
				&& a.drawnSeat() == b.drawnSeat()
				&& a.phase() == b.phase()
				&& a.playerCount() == b.playerCount()
				&& orEmpty(a.bannerText()).equals(orEmpty(b.bannerText()));
	}

	private static String orEmpty(String s) {
		return s == null ? "" : s;
	}
}
