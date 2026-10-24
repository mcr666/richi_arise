package mcr.riichi_arise.game;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import mcr.riichi_arise.block.FengPanBlock;
import mcr.riichi_arise.game.riichi.RiichiBot;
import mcr.riichi_arise.game.riichi.RiichiGame;
import mcr.riichi_arise.network.MahjongLobbyPayloads;

/**
 * 麻雀大厅（服务端行为拥有者）：等待队列交互、AI 座位预约、开局分配、队列悬浮文本、状态广播。
 * 非潜行右击风盘 = 加入/退出等待队列；潜行右击 = 打开管理界面（GUI 走 Action 包）。
 * 队列人数 + 预约 AI 满 4 自动开局；管理员（2 级权限）或队首可随时开局（空位自动补 AI）。
 */
public final class MahjongLobby {
	private MahjongLobby() {
	}

	/** 队列悬浮文本：桌心上方格数 */
	private static final double QUEUE_HEIGHT = 1.0;

	// ==================================================================
	// 入口
	// ==================================================================

	/** C→S 动作分发（MahjongLobbyPayloads 调用；管理界面与队列交互共用） */
	public static void handleAction(ServerPlayer sp, BlockPos origin, int action, int extra) {
		RichiTableState st = RichiTableManager.get(origin);
		if (st == null)
			return;
		switch (action) {
			case MahjongLobbyPayloads.ACT_JOIN_QUEUE, MahjongLobbyPayloads.ACT_LEAVE_QUEUE ->
				toggleQueue(sp, origin);
			case MahjongLobbyPayloads.ACT_START -> tryStart(sp, origin, st);
			case MahjongLobbyPayloads.ACT_ADD_AI -> {
				if (canManage(sp))
					setReservedAI(sp, origin, st, extra, true);
			}
			case MahjongLobbyPayloads.ACT_REMOVE_AI -> {
				if (canManage(sp))
					setReservedAI(sp, origin, st, extra, false);
			}
			case MahjongLobbyPayloads.ACT_SET_TYPE -> {
				// 对局设置等待/终局未清理阶段可改（对局中不可调整，避免结算期改规则）
				if (canManage(sp) && st.phase != RichiTableState.PHASE_PLAYING && extra >= 0 && extra <= 2) {
					st.gameType = extra;
					saveSettings(sp, origin, st);
					broadcastSync(sp.serverLevel(), origin, st);
				}
			}
			case MahjongLobbyPayloads.ACT_SET_THINK -> {
				// 思考时间档位切换（等待/终局未清理）：extra = 档位索引
				if (canManage(sp) && st.phase != RichiTableState.PHASE_PLAYING
						&& extra >= 0 && extra < RichiTableState.THINK_PRESETS.length) {
					st.thinkIdx = extra;
					saveSettings(sp, origin, st);
					broadcastSync(sp.serverLevel(), origin, st);
				}
			}
			case MahjongLobbyPayloads.ACT_SET_SPEED -> {
				// AI 打牌速度档（等待/终局未清理）：extra = 0 快 / 1 中 / 2 慢
				if (canManage(sp) && st.phase != RichiTableState.PHASE_PLAYING
						&& extra >= 0 && extra < RichiTableState.AI_SPEED_MUL.length) {
					st.aiSpeedIdx = extra;
					saveSettings(sp, origin, st);
					broadcastSync(sp.serverLevel(), origin, st);
				}
			}
		case MahjongLobbyPayloads.ACT_CLEAR -> {
				// 终局后可用：彻底清掉牌面实体与 AI 形象（假玩家/盔甲架），桌面回到空白等待状态
				if (canManage(sp) && st.phase == RichiTableState.PHASE_FINISHED) {
					ServerLevel level = sp.serverLevel();
					RichiTableState fresh = RichiTableManager.reset(level, origin); // 清实体 + 删对局
					FengPanBlock.setGameActive(level, origin, false); // 保险：无论此前 game_active 状态，清桌后风盘必恢复占位模型
					java.util.Arrays.fill(fresh.reservedAI, false); // AI 预约席一并清空
					RichiTableSync.broadcastGone(level, origin); // 客户端清掉牌面 + AI 假玩家/盔甲架
					// 解除所有参牌者（女仆）钉位：清桌后实体不再定住（保留排队资格，下一局开局重新入座）
					for (String uid : fresh.npcUuids) {
						net.minecraft.world.entity.Entity e = level.getEntity(java.util.UUID.fromString(uid));
						MahjongParticipant p = e == null ? null : MahjongParticipantRegistry.of(e);
						if (p != null)
							p.unpin();
					}
					broadcastSync(level, origin, fresh);
					sp.displayClientMessage(Component.translatable("message.riichi_arise.clear_done"), true);
				}
			}
			case MahjongLobbyPayloads.ACT_VOTE_END -> {
				// 对局中投票结束对局：人类座位投票，全员同意 → 强制终局；管理员可直接强制结束（纯 AI 局无人可投票）
				if (st.phase == RichiTableState.PHASE_PLAYING) {
					if (canManage(sp)) {
						RiichiGame g = RichiTableManager.getGame(origin);
						if (g != null)
							g.forceEnd();
						else
							broadcastSync(sp.serverLevel(), origin, st);
					} else {
						int seat = seatIndexOf(st, sp);
						if (seat >= 0 && !st.aiSeat[seat] && !st.endVotes[seat]) {
							st.endVotes[seat] = true;
							// 全员同意 = 所有**人类**座位均已投票（AI 座位不参与，此前误将 AI 席算入导致永不通过）
							boolean all = true;
							for (int i = 0; i < 4; i++)
								if (!st.aiSeat[i] && !st.endVotes[i])
									all = false;
							if (all) {
								RiichiGame g = RichiTableManager.getGame(origin);
								if (g != null)
									g.forceEnd();
								else
									broadcastSync(sp.serverLevel(), origin, st);
							} else
								broadcastSync(sp.serverLevel(), origin, st);
						}
					}
				}
			}
			case MahjongLobbyPayloads.ACT_SET_AVATAR_SEAT -> {
				// extra = 座位<<16 | 角色索引（越界 = 还原为默认角色）；等待/终局阶段均可
				if (canManage(sp) && st.phase != RichiTableState.PHASE_PLAYING) {
					int seat = extra >> 16;
					int modeIdx = extra & 0xFFFF;
					if (seat >= 0 && seat <= 3) {
						st.aiAvatarModes[seat] = modeIdx >= 0 && modeIdx < MahjongLobby.AVATAR_MODES.length
							? MahjongLobby.AVATAR_MODES[modeIdx]
							: mcr.riichi_arise.game.riichi.AiCharacters.DEFAULT;
						st.avatarsDirty = true; // 角色变化：下次广播时重新解析
						saveSettings(sp, origin, st);
						broadcastSync(sp.serverLevel(), origin, st);
					}
				}
			}
			case MahjongLobbyPayloads.ACT_SET_OPEN -> {
				if (canManage(sp) && st.phase != RichiTableState.PHASE_PLAYING) {
					st.openHand = extra == 1;
					saveSettings(sp, origin, st);
					broadcastSync(sp.serverLevel(), origin, st);
				}
			}
			case MahjongLobbyPayloads.ACT_KICK -> {
				if (canManage(sp))
					kick(sp, origin, st, extra);
			}
		}
	}

	/** 非潜行右击风盘：加入/退出等待队列（对局中提示等待） */
	public static void toggleQueue(ServerPlayer sp, BlockPos origin) {
		ServerLevel level = sp.serverLevel();
		RichiTableState st = RichiTableManager.get(origin);
		if (st == null || !(level.getBlockState(origin).getBlock() instanceof FengPanBlock))
			return;
		if (st.phase == RichiTableState.PHASE_PLAYING) {
			sp.displayClientMessage(Component.translatable("message.riichi_arise.lobby_in_game"), true);
			return;
		}
		if (st.phase == RichiTableState.PHASE_FINISHED) {
			// 终局后桌面保留（不自动清空），排队仍可继续
			sp.displayClientMessage(Component.translatable("message.riichi_arise.lobby.finished"), true);
		}
		String uuid = sp.getUUID().toString();
		if (st.queue.contains(uuid)) {
			st.queue.remove(uuid);
			sp.displayClientMessage(Component.translatable("message.riichi_arise.queue_left"), true);
		} else {
			// 座位满且有待约 AI：踢出一个 AI 预约，给该玩家腾位；仍满则请出队内 NPC（可被移出）
			if (st.queue.size() + aiCount(st.reservedAI) >= 4) {
				int freed = -1;
				for (int seat = 0; seat < 4 && freed < 0; seat++)
					if (st.reservedAI[seat])
						freed = seat;
				if (freed >= 0) {
					st.reservedAI[freed] = false;
					sp.displayClientMessage(Component.translatable("message.riichi_arise.queue_ai_freed"), true);
				} else {
					for (String q : new ArrayList<>(st.queue))
						if (st.npcUuids.contains(q)) {
							st.queue.remove(q);
							Entity e = level.getEntity(java.util.UUID.fromString(q));
							MahjongParticipant p = e == null ? null : MahjongParticipantRegistry.of(e);
							if (p != null)
								p.unpin();
							sp.displayClientMessage(Component.literal("§6<幻星麻雀>§r 已请出等候队列中的参牌者"), true);
							break;
						}
				}
			}
			st.queue.add(uuid);
			sp.displayClientMessage(
					Component.translatable("message.riichi_arise.queue_joined", st.queue.size(), 4), true);
		}
		updateQueueDisplay(level, origin, st);
		broadcastSync(level, origin, st);
		// 入队不再自动开局：由管理界面「开始对局」按钮（ACT_START）手动启动
	}

	/** 右击风盘（无论是否潜行）：请求客户端打开管理界面，并先推一包当前大厅状态（界面打开即有数据渲染） */
	public static void openLobby(ServerPlayer sp, BlockPos origin) {
		ServerLevel level = sp.serverLevel();
		RichiTableState st = RichiTableManager.get(origin);
		if (st != null)
			broadcastSync(level, origin, st); // 界面加载依赖 SyncMessage；此前仅潜行路径路过 toggleQueue 才有广播
		net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
				new MahjongLobbyPayloads.OpenMessage(origin.asLong()));
	}

	// ==================================================================
	// 开局
	// ==================================================================

	private static int aiCount(boolean[] flags) {
		int n = 0;
		for (boolean b : flags)
			if (b)
				n++;
		return n;
	}

	/** 手动开局：管理员（2 级权限）或队首；空位自动补 AI。终局未清桌也可直接重开（重开会重置点数/本场） */
	private static void tryStart(ServerPlayer sp, BlockPos origin, RichiTableState st) {
		if (st.phase == RichiTableState.PHASE_PLAYING)
			return;
		boolean isHead = !st.queue.isEmpty() && st.queue.get(0).equals(sp.getUUID().toString());
		if (!canManage(sp) && !isHead) {
			sp.displayClientMessage(Component.translatable("message.riichi_arise.lobby_no_perm"), true);
			return;
		}
		startGame(sp.serverLevel(), origin, st);
	}

		/**
		 * 开局：预约 AI 与队列玩家**随机洗牌**后依次入座 1-4 号位（不按排队顺序，每局随机），
		 * 清队列与悬浮文本，创建 RiichiGame 发牌，并把玩家传送到对应座位上。
		 */
		public static void startGame(ServerLevel level, BlockPos origin, RichiTableState st) {
				if (st.phase == RichiTableState.PHASE_PLAYING)
					return;
			// 开局前自动清桌一次：清掉上一场残影/残留交互实体与延迟任务（对局结束、风盘被挖后遗留）
			mcr.riichi_arise.MahjongTicker.cancel(origin); // 只清残留任务
			st.phase = RichiTableState.PHASE_WAITING; // 客户端按等待快照清掉桌面残影
			FengPanBlock.clearDisplaysAt(level, origin);
			RichiTableSync.broadcast(level, origin);
				record Entry(String uuid, boolean ai) {
				}
				java.util.List<Entry> entries = new ArrayList<>();
				for (int seat = 0; seat < 4; seat++)
					if (st.reservedAI[seat])
						entries.add(new Entry(RiichiBot.aiUuid(seat), true));
				for (String uuid : st.queue)
					entries.add(new Entry(uuid, st.npcUuids.contains(uuid))); // NPC 排队视作 AI 座位
			// 每局随机洗牌入座：洗 4 个座位号，把入场者放进随机座位（避免单人/少人时永远坐东=庄家，也不按排队顺序）；
			// 空位再补 AI。RandomSource 不兼容 Collections.shuffle，手写 Fisher-Yates。
			int[] seatOrder = { 0, 1, 2, 3 };
			for (int i = seatOrder.length - 1; i > 0; i--) {
				int j = level.random.nextInt(i + 1);
				int t = seatOrder[i];
				seatOrder[i] = seatOrder[j];
				seatOrder[j] = t;
			}
			java.util.Arrays.fill(st.players, null);
			java.util.Arrays.fill(st.aiSeat, false);
			int n = Math.min(entries.size(), 4);
			for (int i = 0; i < n; i++) {
				int seat = seatOrder[i];
				Entry en = entries.get(i);
				// AI 座位 uuid 随实际座位生成（AI 名按座位显示）；NPC/女仆排队座位直接存实体 uuid，
				// 供 pinNpcSeats 钉位与 endGame 终局回归定位真实实体
				if (en.ai() && !st.npcUuids.contains(en.uuid()))
					st.players[seat] = RiichiBot.aiUuid(seat);
				else
					st.players[seat] = en.uuid();
				st.aiSeat[seat] = en.ai();
			}
			st.gamesStarted++;
		// 空位补 AI
		for (int seat = 0; seat < 4; seat++) {
			if (st.players[seat] == null) {
				st.players[seat] = RiichiBot.aiUuid(seat);
				st.aiSeat[seat] = true;
			}
		}
		st.queue.clear();
		java.util.Arrays.fill(st.endVotes, false);
		// 开局此刻才确定"随机"形象：按对局中语义重解析（stand 占位 → 具体形态）
		st.phase = RichiTableState.PHASE_PLAYING;
		st.avatarsDirty = true;
		FengPanBlock.resolveAvatars(level, st);
		st.avatarsDirty = false; // 开局已解析完毕：终局时不要重算（重算会换随机名/皮肤 UUID，客户端据此重建假玩家 → 形象突然位移）
		st.honba = 0;
		// 东风战单局：每局点数重置
		java.util.Arrays.fill(st.points, 25000);
		java.util.Arrays.fill(st.riichiSticks, 0);
		java.util.Arrays.fill(st.riichiRiverIdx, -1);
		java.util.Arrays.fill(st.handsExposed, RichiTableState.HAND_STAND);
		// 套用思考时间档位：{每巡时限秒, 读秒秒} → tick
		st.turnLimit = RichiTableState.THINK_PRESETS[st.thinkIdx][0] * 20;
		for (int i = 0; i < 4; i++)
			st.longBank[i] = RichiTableState.THINK_PRESETS[st.thinkIdx][1] * 20;
		RiichiGame game = new RiichiGame(level, origin, st);
		RichiTableManager.setGame(origin, game);
		clearQueueDisplay(level, origin);
		game.deal();
		teleportSeats(level, origin, st);
		pinNpcSeats(level, origin, st);
		broadcastSync(level, origin, st);
	}

	/** 对局中的参牌者座位：把真实参牌者实体钉在座位站位（面向桌心），取代客户端假玩家形象 */
	private static void pinNpcSeats(ServerLevel level, BlockPos origin, RichiTableState st) {
		for (int seat = 0; seat < 4; seat++) {
			String uuid = st.players[seat];
			if (uuid == null || !st.npcUuids.contains(uuid))
				continue;
			Entity e = level.getEntity(java.util.UUID.fromString(uuid));
			MahjongParticipant p = e == null ? null : MahjongParticipantRegistry.of(e);
			if (p == null)
				continue;
			Vec3 pos = FengPanBlock.seatStandPos(level, origin, seat);
			Vec3 center = FengPanBlock.tableCenter(level, origin);
			float yaw = (float) Math.toDegrees(Math.atan2(-(center.x - pos.x), center.z - pos.z));
			p.pinForGame(pos, yaw); // 对局钉定：面向桌心，不注视玩家
		}
	}

	// ==================================================================
	// 参牌者（NPC / 女仆等）参战
	// ==================================================================

	/** 参牌者加入指定桌等候队列（/murmol npc join 与右键确认调用；距离与实体校验在调用侧）。
	 *  经 {@link MahjongParticipantRegistry} 把实体解析为参牌者，解析不到则拒绝。
	 *  每桌参牌者上限 {@link RichiTableState#MAX_NPC_SEATS}（放开多女仆）；占据方式与玩家相同（队列 UUID）；
	 *  满员时顶替 AI 预约或队内参牌者（可被移出）。 */
	public static boolean npcJoinTable(ServerLevel level, Entity npc, BlockPos origin) {
		MahjongParticipant p = MahjongParticipantRegistry.of(npc);
		if (p == null)
			return false;
		RichiTableState st = RichiTableManager.get(origin);
		if (st == null || !(level.getBlockState(origin).getBlock() instanceof FengPanBlock)
				|| st.phase == RichiTableState.PHASE_PLAYING || st.queue.contains(npc.getStringUUID())
				|| st.npcUuids.size() >= RichiTableState.MAX_NPC_SEATS)
			return false;
		String uuid = npc.getStringUUID();
		if (st.queue.size() + aiCount(st.reservedAI) >= 4) {
			int freed = -1;
			for (int seat = 0; seat < 4 && freed < 0; seat++)
				if (st.reservedAI[seat])
					freed = seat;
			if (freed >= 0) {
				st.reservedAI[freed] = false;
			} else {
				// 满队：顶替队内另一名参牌者
				for (String q : new ArrayList<>(st.queue))
					if (st.npcUuids.contains(q)) {
						st.queue.remove(q);
						Entity other = level.getEntity(java.util.UUID.fromString(q));
						MahjongParticipant otherP = other == null ? null : MahjongParticipantRegistry.of(other);
						if (otherP != null)
							otherP.unpin();
						break;
					}
				if (!st.npcUuids.contains(uuid) && st.queue.size() + aiCount(st.reservedAI) >= 4)
					return false;
			}
		}
		st.queue.add(uuid);
		st.npcUuids.add(uuid);
		p.setQueuedTable(origin.asLong());
		p.pinWaiting(npc.position(), npc.getYRot()); // 等候期间原地停驻
		updateQueueDisplay(level, origin, st);
		broadcastSync(level, origin, st);
		return true;
	}

	/** 参牌者退出队列/牌局（右键再点或队列被顶替）：清桌状态并解除钉定 */
	public static void leaveNpc(ServerLevel level, Entity npc) {
		String uuid = npc.getStringUUID();
		for (BlockPos origin : RichiTableManager.originsIn(level)) {
			RichiTableState st = RichiTableManager.get(origin);
			if (st == null)
				continue;
			boolean dirty = false;
			if (st.queue.remove(uuid))
				dirty = true;
			for (int seat = 0; seat < 4; seat++)
				if (uuid.equals(st.players[seat])) {
					st.players[seat] = null;
					dirty = true;
				}
			if (st.npcUuids.remove(uuid))
				dirty = true;
			if (dirty) {
				updateQueueDisplay(level, origin, st);
				broadcastSync(level, origin, st);
			}
		}
		MahjongParticipant p = MahjongParticipantRegistry.of(npc);
		if (p != null) {
			p.unpin();
			p.setQueuedTable(-1); // 退出牌桌后重置排队标记，避免参牌行为误判为"仍在队列"而假死不再入队
		}
	}

	// ==================================================================
	// 外部程序（net 端口接入）玩家
	// ==================================================================

	/**
	 * 外部程序玩家加入指定桌等候队列（net 层 join 调用；无 ServerPlayer/实体）。
	 * 占据方式与真人相同（队列 UUID），额外登记 externalUuids/externalNames；
	 * 满员时与 NPC 一样顶替 AI 预约或队内参牌者。对局中不可加入。
	 */
	public static boolean externalJoinTable(ServerLevel level, BlockPos origin, String uuid, String name) {
		RichiTableState st = RichiTableManager.get(origin);
		if (st == null || !(level.getBlockState(origin).getBlock() instanceof FengPanBlock)
				|| st.phase == RichiTableState.PHASE_PLAYING || st.queue.contains(uuid)
				|| st.externalUuids.contains(uuid))
			return false;
		if (st.queue.size() + aiCount(st.reservedAI) >= 4) {
			int freed = -1;
			for (int seat = 0; seat < 4 && freed < 0; seat++)
				if (st.reservedAI[seat])
					freed = seat;
			if (freed >= 0) {
				st.reservedAI[freed] = false;
			} else {
				for (String q : new ArrayList<>(st.queue))
					if (st.npcUuids.contains(q)) {
						st.queue.remove(q);
						Entity e = level.getEntity(java.util.UUID.fromString(q));
						MahjongParticipant p = e == null ? null : MahjongParticipantRegistry.of(e);
						if (p != null)
							p.unpin();
						break;
					}
				if (st.queue.size() + aiCount(st.reservedAI) >= 4)
					return false;
			}
		}
		st.queue.add(uuid);
		st.externalUuids.add(uuid);
		st.externalNames.put(uuid, name);
		updateQueueDisplay(level, origin, st);
		broadcastSync(level, origin, st);
		return true;
	}

	/** 外部程序玩家离开牌局/队列（net 层断开或 leave 调用）：对局中座位由 AI 接管，等待阶段清空座位 */
	public static void externalLeaveTable(ServerLevel level, BlockPos origin, String uuid) {
		RichiTableState st = RichiTableManager.get(origin);
		if (st == null)
			return;
		boolean dirty = st.queue.remove(uuid);
		st.externalUuids.remove(uuid);
		st.externalNames.remove(uuid);
		for (int seat = 0; seat < 4; seat++) {
			if (!uuid.equals(st.players[seat]))
				continue;
			if (st.phase == RichiTableState.PHASE_PLAYING && !st.aiSeat[seat]) {
				var game = RichiTableManager.getGame(origin);
				if (game != null)
					game.makeAI(seat); // 对局中断开：座位由 AI 接管
			} else if (st.phase != RichiTableState.PHASE_PLAYING) {
				st.players[seat] = null;
			}
			dirty = true;
		}
		if (dirty) {
			updateQueueDisplay(level, origin, st);
			broadcastSync(level, origin, st);
		}
	}

	// ==================================================================
	// 服务端 Marker 代理：每个 AI 座位一个 Marker 实体，可被选择器选中/tp
	// ==================================================================

	/** 同步 AI 座位的服务端 Marker：广播时调用，保证 Marker 生命周期与座位状态一致 */
	private static void syncAiMarkers(ServerLevel level, BlockPos origin, RichiTableState st) {
		// 先移除不再需要的 Marker（座位不再是 AI 或实体已消失）
		for (int seat = 0; seat < 4; seat++) {
			java.util.UUID markerUuid = st.aiMarkerUuids.get(seat);
			if (markerUuid == null)
				continue;
			net.minecraft.world.entity.Entity e = level.getEntity(markerUuid);
			boolean needMarker = needsAiMarker(st, seat);
			if (!needMarker || e == null || e.isRemoved()) {
				if (e != null && !e.isRemoved())
					e.discard();
				st.aiMarkerUuids.remove(seat);
			} else {
				// 更新名字（形象变化后重算）
				String expectedName = RiichiBot.seatDisplayName(st, seat);
				String curName = e.getCustomName() != null ? e.getCustomName().getString() : "";
				if (!expectedName.equals(curName))
					e.setCustomName(net.minecraft.network.chat.Component.literal(expectedName));
			}
		}
		// 为需要但缺失的座位创建 Marker
		for (int seat = 0; seat < 4; seat++) {
			if (!needsAiMarker(st, seat))
				continue;
			if (st.aiMarkerUuids.containsKey(seat))
				continue;
			double x, y, z;
			if (st.phase == RichiTableState.PHASE_PLAYING) {
				Vec3 p = FengPanBlock.seatStandPos(level, origin, seat);
				x = p.x;
				y = p.y;
				z = p.z;
			} else {
				// 等待/终局阶段：固定随机位置（基于 origin+seat 的确定性随机），与客户端随机站位大致对齐
				Vec3 center = FengPanBlock.tableCenter(level, origin);
				java.util.Random r = new java.util.Random(origin.asLong() * 31L + seat);
				double ang = r.nextDouble() * Math.PI * 2;
				double rad = 1.75 + r.nextDouble() * 1.4;
				x = center.x + Math.cos(ang) * rad;
				y = origin.getY();
				z = center.z + Math.sin(ang) * rad;
			}
			net.minecraft.world.entity.Marker marker = new net.minecraft.world.entity.Marker(
					net.minecraft.world.entity.EntityType.MARKER, level);
			marker.moveTo(x, y, z);
			marker.setCustomName(net.minecraft.network.chat.Component.literal(RiichiBot.seatDisplayName(st, seat)));
			marker.setCustomNameVisible(true);
			marker.addTag("richi_ai_marker");
			level.addFreshEntity(marker);
			st.aiMarkerUuids.put(seat, marker.getUUID());
		}
	}

	/** 某座位是否需要 AI Marker：对局中 aiSeat（参牌者除外）；等待/终局阶段 = 本局 AI 座位或下一局预约
	 *  （终局未清桌时本局 AI 的标记不能凭空消失/换座，否则看起来像形象位移） */
	private static boolean needsAiMarker(RichiTableState st, int seat) {
		if (st.phase == RichiTableState.PHASE_PLAYING) {
			// 参牌者（女仆/NPC）座位：真实实体钉在座位，无需 AI Marker 代理
			if (st.players[seat] != null && st.npcUuids.contains(st.players[seat]))
				return false;
			return st.aiSeat[seat];
		}
		return st.aiSeat[seat] || st.reservedAI[seat];
	}

	/** 把四位玩家传送到对应座位外侧站位（面向桌心）：开局随机入座后与每局开始时调用 */
	public static void teleportSeats(ServerLevel level, BlockPos origin, RichiTableState st) {
		for (int seat = 0; seat < 4; seat++) {
			ServerPlayer p = st.playerOf(level, seat);
			if (p == null)
				continue;
			Vec3 pos = FengPanBlock.seatStandPos(level, origin, seat);
			Vec3 center = FengPanBlock.tableCenter(level, origin);
			float yaw = (float) Math.toDegrees(Math.atan2(-(center.x - pos.x), center.z - pos.z));
			p.teleportTo(level, pos.x, pos.y, pos.z, yaw, 0.0f);
		}
	}

	/** 玩家所在座位下标（未入座返回 -1） */
	private static int seatIndexOf(RichiTableState st, ServerPlayer sp) {
		String uuid = sp.getUUID().toString();
		for (int i = 0; i < 4; i++)
			if (uuid.equals(st.players[i]))
				return i;
		return -1;
	}

	// ==================================================================
	// 管理操作
	// ==================================================================

	/**
	 * AI 座位角色（管理界面右键菜单切换，顺序即菜单显示顺序，与
	 * {@link mcr.riichi_arise.game.riichi.AiCharacters#IDS} 一一对应）。
	 * 直接复用 AiCharacters 的 id 列表，避免两处维护。
	 */
	public static final String[] AVATAR_MODES = mcr.riichi_arise.game.riichi.AiCharacters.IDS;

	/** 管理权限：2 级权限（OP/单人作弊） */
	public static boolean canManage(ServerPlayer sp) {
		return sp.hasPermissions(2);
	}

	/**
	 * 玩家设置自己的角色语音（C→S 经 VoiceSetMessage 到达）。
	 * 仅可设置自己的（无管理鉴权要求）；空串 = 关闭语音。校验角色 id 合法后
	 * 全局持久化（跨桌跟随），并广播玩家所在/附近各桌的 Sync，使他人可见其语音。
	 */
	public static void setVoice(ServerPlayer sp, String voiceId) {
		if (voiceId == null)
			voiceId = mcr.riichi_arise.network.VoicePayloads.NONE;
		if (!voiceId.isEmpty() && !mcr.riichi_arise.voice.VoiceCharacters.exists(voiceId))
			return; // 未知角色忽略
		ServerLevel level = sp.serverLevel();
		MahjongTableSavedData.get(level).setVoice(sp.getUUID().toString(), voiceId);
		for (BlockPos origin : RichiTableManager.originsIn(level)) {
			RichiTableState st = RichiTableManager.get(origin);
			if (st != null)
				broadcastSync(level, origin, st);
		}
		String name = voiceId.isEmpty() ? ""
				: mcr.riichi_arise.voice.VoiceCharacters.displayName(voiceId);
		sp.displayClientMessage(Component.translatable(voiceId.isEmpty()
				? "message.riichi_arise.voice_off" : "message.riichi_arise.voice_set", name), true);
	}

	/**
	 * 玩家退出游戏或切换维度时自动退出牌局：
	 * 对局中座位由 AI 接管（makeAI），等待/结束阶段清空座位并移出队列。
	 */
	public static void handlePlayerLeave(net.minecraft.server.MinecraftServer server, String uuid) {
		for (net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
			for (BlockPos origin : RichiTableManager.originsIn(lvl)) {
				RichiTableState st = RichiTableManager.get(origin);
				if (st == null)
					continue;
				boolean dirty = false;
				if (st.queue.remove(uuid))
					dirty = true;
				for (int seat = 0; seat < 4; seat++) {
					if (!uuid.equals(st.players[seat]))
						continue;
					if (st.phase == RichiTableState.PHASE_PLAYING && !st.aiSeat[seat]) {
						var game = RichiTableManager.getGame(origin);
						if (game != null) {
							game.makeAI(seat);
							broadcastLeave(lvl, origin, st, seat);
						}
					} else if (st.phase != RichiTableState.PHASE_PLAYING) {
						st.players[seat] = null;
					}
					dirty = true;
				}
				if (dirty) {
					updateQueueDisplay(lvl, origin, st);
					broadcastSync(lvl, origin, st);
				}
			}
		}
	}

	/** 离席播报（对局四家） */
	private static void broadcastLeave(ServerLevel level, BlockPos origin, RichiTableState st, int seat) {
		String msg = Component.translatable("message.riichi_arise.player_left",
				new String[] { "東", "南", "西", "北" }[seat]).getString();
		for (int s = 0; s < 4; s++) {
			ServerPlayer p = st.playerOf(level, s);
			if (p != null)
				p.sendSystemMessage(Component.literal("§6<幻星麻雀>§r " + msg));
		}
	}

	/**
	 * 预约/取消 AI 座位（等待/终局阶段）。
	 * 座位已被队列玩家占据（非预约座位按序分配给排队玩家）时不能预约为 AI。
	 */
	private static void setReservedAI(ServerPlayer sp, BlockPos origin, RichiTableState st, int seat, boolean on) {
		if (st.phase == RichiTableState.PHASE_PLAYING || seat < 0 || seat > 3)
			return;
		if (on && seatTakenByQueue(st, seat)) {
			sp.displayClientMessage(
					Component.translatable("message.riichi_arise.lobby_seat_taken", seatName(seat)), true);
			return;
		}
		st.reservedAI[seat] = on;
		st.avatarsDirty = true; // 预约变化：下次广播时重新解析形象
		saveSettings(sp, origin, st);
		broadcastSync(sp.serverLevel(), origin, st);
	}

	/** 设置变更后写入风盘持久化存档（记住该桌上一次的配置，服务器重启/清桌后仍保留） */
	private static void saveSettings(ServerPlayer sp, BlockPos origin, RichiTableState st) {
		MahjongTableSavedData.get(sp.serverLevel()).set(sp.serverLevel(), origin, st);
	}

	/** 该座位是否会由队列玩家入座：非预约座位按序分配队列玩家，序号 < 队列长度即被占据 */
	private static boolean seatTakenByQueue(RichiTableState st, int seat) {
		int idx = -1;
		for (int s = 0; s <= seat; s++)
			if (!st.reservedAI[s])
				idx++;
		return idx >= 0 && idx < st.queue.size();
	}

	/** 踢出：对局中该座位由 AI 接管；等待阶段无座位可踢（忽略） */
	private static void kick(ServerPlayer sp, BlockPos origin, RichiTableState st, int seat) {
		if (seat < 0 || seat > 3)
			return;
		if (st.phase == RichiTableState.PHASE_PLAYING) {
			RiichiGame game = RichiTableManager.getGame(origin);
			if (game != null && !st.aiSeat[seat]) {
				game.makeAI(seat);
				sp.displayClientMessage(
						Component.translatable("message.riichi_arise.lobby_kicked", seatName(seat)), true);
				broadcastSync(sp.serverLevel(), origin, st);
			}
		}
	}

	private static String seatName(int seat) {
		return new String[] { "東", "南", "西", "北" }[seat];
	}

	// ==================================================================
	// 队列悬浮文本（text_display，桌心上方 1 格，标签 richi_queue）
	// ==================================================================

	/** 等待期间悬浮提示：首行“等待中 n/4”，随后每行一名排队玩家；空队列显示加入提示；非等待阶段删除 */
	public static void updateQueueDisplay(ServerLevel level, BlockPos origin, RichiTableState st) {
		if (st.phase != RichiTableState.PHASE_WAITING) {
			clearQueueDisplay(level, origin);
			return;
		}
		StringBuilder text = new StringBuilder(
				Component.translatable("message.riichi_arise.queue_status", st.queue.size(), 4).getString());
		int reserved = 0;
		for (boolean b : st.reservedAI)
			if (b)
				reserved++;
		for (int i = 0; i < st.queue.size(); i++) {
			text.append('\n').append(playerName(level, st, st.queue.get(i)))
					.append("（座位 ").append(reserved + i + 1).append('）');
		}
		if (st.queue.isEmpty())
			text.append('\n')
					.append(Component.translatable("message.riichi_arise.queue_hint").getString());
		// 删旧重生（简单可靠；排队事件频率低）
		for (Entity e : level.getEntitiesOfClass(Entity.class, queueBox(origin),
				x -> x.getTags().contains("richi_queue")))
			e.discard();
		Vec3 pos = FengPanBlock.tableCenter(level, origin).add(0, QUEUE_HEIGHT, 0);
		FengPanBlock.spawnTextDisplay(level, pos, "richi_queue", text.toString());
	}

	private static void clearQueueDisplay(ServerLevel level, BlockPos origin) {
		for (Entity e : level.getEntitiesOfClass(Entity.class, queueBox(origin),
				x -> x.getTags().contains("richi_queue")))
			e.discard();
	}

	private static AABB queueBox(BlockPos origin) {
		return AABB.encapsulatingFullBlocks(origin, origin).inflate(6, 5, 6);
	}

	private static String playerName(ServerLevel level, RichiTableState pst, String uuid) {
		// 参牌者（NPC/女仆）直接显示其参牌者名（uuid 是生物实体 id，不可读）
		if (pst != null && pst.npcUuids.contains(uuid)) {
			Entity e = level.getEntity(java.util.UUID.fromString(uuid));
			MahjongParticipant p = e == null ? null : MahjongParticipantRegistry.of(e);
			return p != null ? p.participantName() : "NPC";
		}
		try {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(java.util.UUID.fromString(uuid));
			if (p != null)
				return p.getGameProfile().getName();
		} catch (IllegalArgumentException ignored) {
		}
		return uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
	}

	// ==================================================================
	// 状态广播（Sync 按收件人分别构造：canManage 因人而异）
	// ==================================================================

	public static void broadcastSync(ServerLevel level, BlockPos origin, RichiTableState st) {
		syncAiMarkers(level, origin, st);
		// 等待/终局阶段有预约 AI：解析形象（惰性，预约变化后自动重算）并广播桌面快照，
		// 客户端在大厅即可渲染 AI 假玩家（终局不点清理也能看到/调整下一局预约形象）
		if (st.phase != RichiTableState.PHASE_PLAYING) {
			if (st.avatarsDirty) {
				mcr.riichi_arise.block.FengPanBlock.resolveAvatars(level, st); // 仅预约/形象变化后解析一次
				st.avatarsDirty = false;
			}
			RichiTableSync.broadcast(level, origin);
		}
		AABB box = AABB.encapsulatingFullBlocks(origin, origin).inflate(24, 24, 24);
		List<ServerPlayer> nearby = level.getEntitiesOfClass(net.minecraft.server.level.ServerPlayer.class, box);
		// 座位名与 AI 标记
		String[] seatNames = new String[4];
		java.util.Arrays.fill(seatNames, ""); // 空座位必须给非 null（writeUtf(null) 会断连）
		int aiMask = 0;
		// 对局中显示实际入座玩家；等待/终局阶段显示下一局的预约与队列占位（终局未清桌也可管理）
		boolean playing = st.phase == RichiTableState.PHASE_PLAYING;
		for (int seat = 0; seat < 4; seat++) {
			if (playing) {
				boolean ai = st.aiSeat[seat];
				// 参牌者（女仆/NPC）座位优先显示其名字（aiSeat=true 但非 AI，不标 AI 徽章）
				if (st.players[seat] != null && st.npcUuids.contains(st.players[seat])) {
					seatNames[seat] = playerName(level, st, st.players[seat]);
				} else if (ai) {
					aiMask |= 1 << seat;
					seatNames[seat] = RiichiBot.aiName(seat);
				} else if (st.players[seat] != null) {
					seatNames[seat] = playerName(level, st, st.players[seat]);
				}
			} else {
				// 等待阶段：预约 AI 只占位（名字空串，aiMask 标记）
				if (st.reservedAI[seat])
					aiMask |= 1 << seat;
			}
		}
		// 等待阶段：队列玩家依次占入剩余空座位显示（仅占位，不改 st.players 真值）
		if (!playing) {
			int q = 0;
			for (int seat = 0; seat < 4 && q < st.queue.size(); seat++) {
				if (seatNames[seat].isEmpty() && !st.reservedAI[seat])
					seatNames[seat] = playerName(level, st, st.queue.get(q++));
			}
		}
		List<String> queueNames = new ArrayList<>();
		for (String uuid : st.queue)
			queueNames.add(playerName(level, st, uuid));
		int voteCount = 0;
		for (boolean v : st.endVotes)
			if (v)
				voteCount++;
		// 各座位独立 AI 形象模式（空串 = 随机）
		List<String> seatModes = new ArrayList<>(4);
		for (int seat = 0; seat < 4; seat++) {
			String m = st.aiAvatarModes[seat];
			seatModes.add(m == null ? "" : m);
		}
		// 各座玩家的角色语音（人类 = 个人设置所选；AI = 其座位角色的内置语音；参牌 NPC 无）——对他人可见
		MahjongTableSavedData sd = MahjongTableSavedData.get(level);
		String[] seatVoices = new String[4];
		for (int seat = 0; seat < 4; seat++) {
			String voice = "";
			if (st.players[seat] != null && !st.aiSeat[seat]) {
				String v = sd.getVoice(st.players[seat]);
				voice = v == null ? "" : v;
			} else if (st.aiSeat[seat]) {
				voice = mcr.riichi_arise.game.riichi.AiCharacters.voice(st.aiAvatarModes[seat]);
			}
			seatVoices[seat] = voice;
		}
		for (ServerPlayer p : nearby) {
			String ownVoice = sd.getVoice(p.getUUID().toString());
			net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
					new MahjongLobbyPayloads.SyncMessage(origin.asLong(), st.phase,
						java.util.Arrays.asList(seatNames), aiMask, queueNames, canManage(p), st.gameType,
						voteCount, st.openHand, seatModes, st.thinkIdx, st.aiSpeedIdx,
						ownVoice == null ? "" : ownVoice, java.util.Arrays.asList(seatVoices)));
		}
		// 悬浮提示随状态同步：等待期显示（空队列给加入提示），其它阶段清除
		if (st.phase == RichiTableState.PHASE_WAITING)
			updateQueueDisplay(level, origin, st);
		else
			clearQueueDisplay(level, origin);
	}
}
