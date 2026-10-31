package mcr.riichi_arise.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import mcr.riichi_arise.RichiiAriseMod;
import mcr.riichi_arise.game.MahjongLobby;

/**
 * 麻雀大厅（等待队列 / 管理）网络包。
 * <ul>
 * <li>SyncMessage（S→C）：全量同步一张桌的大厅状态（阶段/座位/队列/管理权限），
 * 客户端缓存按原点存放，管理界面与队列渲染据此刷新。</li>
 * <li>ActionMessage（C→S）：玩家操作（排队/退出/开始/安排与移除 AI/踢出）。</li>
 * <li>OpenMessage（S→C）：潜行右击风盘后请求客户端打开管理界面。</li>
 * </ul>
 * 客户端缓存只存原始类型与字符串（该类服务端也会加载）。
 */
public class MahjongLobbyPayloads {
	/** 客户端缓存：原点（打包 long）→ 最近一次 Sync */
	public static final java.util.Map<Long, LobbySync> CLIENT_SYNC = new java.util.HashMap<>();

	/** 座位/队列状态快照（客户端只读）；thinkIdx：思考时间档位；aiSpeedIdx：AI 速度档；
	 * ownVoice：收件人自己当前的角色语音 id（个人设置界面显示）；seatVoices：各座角色语音 id（人类 = 自选，AI = 座位角色内置语音；他人可见，用于对局播报） */
	public record LobbySync(int phase, String[] seatNames, boolean[] seatAI,
			List<String> queueNames, boolean canManage, int gameType, int voteCount,
			boolean openHand, String[] seatAvatarModes, int thinkIdx,
			int aiSpeedIdx, String ownVoice, String[] seatVoices, int playerCount, int roomType) {
	}

	/** C→S 动作类型 */
	public static final int ACT_JOIN_QUEUE = 0, ACT_LEAVE_QUEUE = 1, ACT_START = 2,
			ACT_ADD_AI = 3, ACT_REMOVE_AI = 4, ACT_KICK = 5, ACT_SET_TYPE = 6, ACT_CLEAR = 7,
			ACT_VOTE_END = 8, ACT_SET_OPEN = 10, ACT_SET_AVATAR_SEAT = 12, ACT_SET_THINK = 13, ACT_SET_SPEED = 14,
			ACT_SET_PLAYERS = 15, ACT_SET_ROOM = 16;

	public record SyncMessage(long originPacked, int phase, List<String> seatNames, int aiMask,
			List<String> queueNames, boolean canManage, int gameType, int voteCount,
			boolean openHand, List<String> seatAvatarModes,
			int thinkIdx, int aiSpeedIdx, String ownVoice, List<String> seatVoices, int playerCount,
			int roomType) implements CustomPacketPayload {
		public static final Type<SyncMessage> TYPE = new Type<>(
				ResourceLocation.fromNamespaceAndPath(RichiiAriseMod.MODID, "richi_lobby_sync"));

		public static final StreamCodec<RegistryFriendlyByteBuf, SyncMessage> STREAM_CODEC = StreamCodec.of(
				MahjongLobbyPayloads::writeSync, MahjongLobbyPayloads::readSync);

		@Override
		public Type<SyncMessage> type() {
			return TYPE;
		}

		public static void handleData(final SyncMessage message, final IPayloadContext context) {
			if (context.flow() == PacketFlow.CLIENTBOUND) {
				context.enqueueWork(() -> {
					String[] names = new String[4];
					for (int i = 0; i < 4; i++)
						names[i] = message.seatNames().get(i);
					boolean[] ai = new boolean[4];
					for (int i = 0; i < 4; i++)
						ai[i] = (message.aiMask() & (1 << i)) != 0;
					String[] seatModes = new String[4];
					for (int i = 0; i < 4; i++)
						seatModes[i] = message.seatAvatarModes().get(i);
					String[] seatVoices = new String[4];
					for (int i = 0; i < 4; i++)
						seatVoices[i] = message.seatVoices().get(i);
					CLIENT_SYNC.put(message.originPacked(),
							new LobbySync(message.phase(), names, ai, message.queueNames(),
									message.canManage(), message.gameType(), message.voteCount(),
									message.openHand(), seatModes,
									message.thinkIdx(), message.aiSpeedIdx(), message.ownVoice(), seatVoices,
									message.playerCount(), message.roomType()));
					// 对局开始：确保入座玩家语音已下载到本地缓存（幂等，仅缺时下载）
					if (message.phase() == mcr.riichi_arise.game.RichiTableState.PHASE_PLAYING)
						mcr.riichi_arise.voice.VoiceClient.onGameStart(message.originPacked());
				});
			}
		}
	}

	/** C→S 操作：action 见 ACT_*，extra = 座位（ADD_AI/REMOVE_AI/KICK 用） */
	public record ActionMessage(long originPacked, int action, int extra) implements CustomPacketPayload {
		public static final Type<ActionMessage> TYPE = new Type<>(
				ResourceLocation.fromNamespaceAndPath(RichiiAriseMod.MODID, "richi_lobby_action"));

		public static final StreamCodec<RegistryFriendlyByteBuf, ActionMessage> STREAM_CODEC = StreamCodec.of(
				(RegistryFriendlyByteBuf buf, ActionMessage msg) -> {
					buf.writeLong(msg.originPacked());
					buf.writeVarInt(msg.action());
					buf.writeVarInt(msg.extra());
				},
				(RegistryFriendlyByteBuf buf) -> new ActionMessage(buf.readLong(), buf.readVarInt(), buf.readVarInt()));

		@Override
		public Type<ActionMessage> type() {
			return TYPE;
		}

		public static void handleData(final ActionMessage message, final IPayloadContext context) {
			if (context.flow() == PacketFlow.SERVERBOUND) {
				context.enqueueWork(() -> {
					if (context.player() instanceof net.minecraft.server.level.ServerPlayer sp)
						MahjongLobby.handleAction(sp, BlockPos.of(message.originPacked()),
								message.action(), message.extra());
				});
			}
		}
	}

	/** S→C：打开管理界面 */
	public record OpenMessage(long originPacked) implements CustomPacketPayload {
		public static final Type<OpenMessage> TYPE = new Type<>(
				ResourceLocation.fromNamespaceAndPath(RichiiAriseMod.MODID, "richi_lobby_open"));

		public static final StreamCodec<RegistryFriendlyByteBuf, OpenMessage> STREAM_CODEC = StreamCodec.of(
				(RegistryFriendlyByteBuf buf, OpenMessage msg) -> buf.writeLong(msg.originPacked()),
				(RegistryFriendlyByteBuf buf) -> new OpenMessage(buf.readLong()));

		@Override
		public Type<OpenMessage> type() {
			return TYPE;
		}

		public static void handleData(final OpenMessage message, final IPayloadContext context) {
			if (context.flow() == PacketFlow.CLIENTBOUND) {
				context.enqueueWork(() -> {
					if (net.minecraft.client.Minecraft.getInstance().screen == null)
						net.minecraft.client.Minecraft.getInstance().setScreen(
								new mcr.riichi_arise.client.MahjongLobbyScreen(BlockPos.of(message.originPacked())));
				});
			}
		}
	}

	/**
	 * S→C：对局开始时的房间类型标题（大厅样式的屏幕中央大字）。
	 * langKey = 标题语言键（铁之间 / 玻璃之间）；color = ARGB 颜色。
	 */
	public record RoomTitlePayload(String langKey, int color) implements CustomPacketPayload {
		public static final Type<RoomTitlePayload> TYPE = new Type<>(
				ResourceLocation.fromNamespaceAndPath(RichiiAriseMod.MODID, "richi_room_title"));

		public static final StreamCodec<RegistryFriendlyByteBuf, RoomTitlePayload> STREAM_CODEC = StreamCodec.of(
				(RegistryFriendlyByteBuf buf, RoomTitlePayload msg) -> {
					buf.writeUtf(msg.langKey() == null ? "" : msg.langKey(), 64);
					buf.writeInt(msg.color());
				},
				(RegistryFriendlyByteBuf buf) -> new RoomTitlePayload(buf.readUtf(64), buf.readInt()));

		@Override
		public Type<RoomTitlePayload> type() {
			return TYPE;
		}

		public static void handleData(final RoomTitlePayload message, final IPayloadContext context) {
			if (context.flow() == PacketFlow.CLIENTBOUND) {
				context.enqueueWork(() -> mcr.riichi_arise.client.RoomTitleOverlay.show(
						net.minecraft.network.chat.Component.translatable(message.langKey()), message.color()));
			}
		}
	}

	private static void writeSync(RegistryFriendlyByteBuf buf, SyncMessage msg) {
		buf.writeLong(msg.originPacked());
		buf.writeVarInt(msg.phase());
		for (int i = 0; i < 4; i++) {
			String name = msg.seatNames().get(i);
			buf.writeUtf(name == null ? "" : name, 64);
		}
		buf.writeVarInt(msg.aiMask());
		buf.writeVarInt(msg.queueNames().size());
		for (String name : msg.queueNames())
			buf.writeUtf(name, 64);
		buf.writeBoolean(msg.canManage());
		buf.writeVarInt(msg.gameType());
		buf.writeVarInt(msg.voteCount());
		buf.writeBoolean(msg.openHand());
		for (int i = 0; i < 4; i++) {
			String mode = msg.seatAvatarModes().get(i);
			buf.writeUtf(mode == null ? "" : mode, 32);
		}
		buf.writeVarInt(msg.thinkIdx());
		buf.writeVarInt(msg.aiSpeedIdx());
		String ownVoice = msg.ownVoice();
		buf.writeUtf(ownVoice == null ? "" : ownVoice, 64);
		for (int i = 0; i < 4; i++) {
			String v = msg.seatVoices().get(i);
			buf.writeUtf(v == null ? "" : v, 64);
		}
		buf.writeVarInt(msg.playerCount());
		buf.writeVarInt(msg.roomType());
	}

	private static SyncMessage readSync(RegistryFriendlyByteBuf buf) {
		long origin = buf.readLong();
		int phase = buf.readVarInt();
		List<String> seats = new ArrayList<>(4);
		for (int i = 0; i < 4; i++)
			seats.add(buf.readUtf(64));
		int aiMask = buf.readVarInt();
		int queueSize = buf.readVarInt();
		List<String> queue = new ArrayList<>(queueSize);
		for (int i = 0; i < queueSize; i++)
			queue.add(buf.readUtf(64));
		boolean canManage = buf.readBoolean();
		int gameType = buf.readVarInt();
		int voteCount = buf.readVarInt();
		boolean openHand = buf.readBoolean();
		List<String> seatModes = new ArrayList<>(4);
		for (int i = 0; i < 4; i++)
			seatModes.add(buf.readUtf(32));
		int thinkIdx = buf.readVarInt();
		int aiSpeedIdx = buf.readVarInt();
		String ownVoice = buf.readUtf(64);
		List<String> seatVoices = new ArrayList<>(4);
		for (int i = 0; i < 4; i++)
			seatVoices.add(buf.readUtf(64));
		int playerCount = buf.readVarInt();
		int roomType = buf.readVarInt();
		return new SyncMessage(origin, phase, seats, aiMask, queue, canManage, gameType, voteCount,
				openHand, seatModes, thinkIdx, aiSpeedIdx, ownVoice, seatVoices, playerCount, roomType);
	}

	public static void register() {
		RichiiAriseMod.addNetworkMessage(SyncMessage.TYPE, SyncMessage.STREAM_CODEC, SyncMessage::handleData);
		RichiiAriseMod.addNetworkMessage(ActionMessage.TYPE, ActionMessage.STREAM_CODEC, ActionMessage::handleData);
		RichiiAriseMod.addNetworkMessage(OpenMessage.TYPE, OpenMessage.STREAM_CODEC, OpenMessage::handleData);
		RichiiAriseMod.addNetworkMessage(RoomTitlePayload.TYPE, RoomTitlePayload.STREAM_CODEC,
				RoomTitlePayload::handleData);
		mcr.riichi_arise.network.VoicePayloads.register();
	}
}
