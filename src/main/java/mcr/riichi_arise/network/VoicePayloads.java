package mcr.riichi_arise.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import mcr.riichi_arise.RichiiAriseMod;
import mcr.riichi_arise.game.MahjongLobby;

/**
 * 天凤式角色语音网络包。
 * <ul>
 * <li>VoiceSetMessage（C→S）：玩家把自己的语音设为某角色（空串 = 关闭语音）。服务端全局按 UUID
 * 持久化，并广播 Sync 使他人可见（他人客户端据此播报该玩家的语音）。</li>
 * <li>VoiceEventPayload（S→C）：某座位发生吃/碰/杠/立直/荣/自摸，通知附近客户端播放该座玩家的语音。</li>
 * </ul>
 */
public class VoicePayloads {
	/** 关闭语音（空串） */
	public static final String NONE = "";

	/** C→S：设置自己的语音 */
	public record VoiceSetMessage(String voiceId) implements CustomPacketPayload {
		public static final Type<VoiceSetMessage> TYPE = new Type<>(
				ResourceLocation.fromNamespaceAndPath(RichiiAriseMod.MODID, "richi_voice_set"));

		public static final StreamCodec<RegistryFriendlyByteBuf, VoiceSetMessage> STREAM_CODEC = StreamCodec.of(
				(RegistryFriendlyByteBuf buf, VoiceSetMessage msg) -> buf.writeUtf(msg.voiceId(), 64),
				(RegistryFriendlyByteBuf buf) -> new VoiceSetMessage(buf.readUtf(64)));

		@Override
		public Type<VoiceSetMessage> type() {
			return TYPE;
		}

		public static void handleData(final VoiceSetMessage message, final IPayloadContext context) {
			if (context.flow() == PacketFlow.SERVERBOUND) {
				context.enqueueWork(() -> {
					if (context.player() instanceof ServerPlayer sp)
						MahjongLobby.setVoice(sp, message.voiceId());
				});
			}
		}
	}

	/** S→C：对局事件 → 播报某座位玩家的语音 */
	public record VoiceEventPayload(long originPacked, int seat, int event) implements CustomPacketPayload {
		public static final Type<VoiceEventPayload> TYPE = new Type<>(
				ResourceLocation.fromNamespaceAndPath(RichiiAriseMod.MODID, "richi_voice_event"));

		public static final StreamCodec<RegistryFriendlyByteBuf, VoiceEventPayload> STREAM_CODEC = StreamCodec.of(
				(RegistryFriendlyByteBuf buf, VoiceEventPayload msg) -> {
					buf.writeLong(msg.originPacked());
					buf.writeVarInt(msg.seat());
					buf.writeVarInt(msg.event());
				},
				(RegistryFriendlyByteBuf buf) -> new VoiceEventPayload(buf.readLong(), buf.readVarInt(),
						buf.readVarInt()));

		@Override
		public Type<VoiceEventPayload> type() {
			return TYPE;
		}

		public static void handleData(final VoiceEventPayload message, final IPayloadContext context) {
			if (context.flow() == PacketFlow.CLIENTBOUND) {
				context.enqueueWork(() -> mcr.riichi_arise.voice.VoiceClient.onVoiceEvent(message.originPacked(),
						message.seat(), message.event()));
			}
		}
	}

	/** 服务端助手：把某座位的语音事件发给桌周附近（24 格）的玩家 */
	public static void sendVoice(ServerLevel level, BlockPos origin, int seat, int event) {
		if (level == null || origin == null)
			return;
		AABB box = AABB.encapsulatingFullBlocks(origin, origin).inflate(24, 24, 24);
		List<ServerPlayer> nearby = level.getEntitiesOfClass(ServerPlayer.class, box);
		if (nearby.isEmpty())
			return;
		VoiceEventPayload pkt = new VoiceEventPayload(origin.asLong(), seat, event);
		for (ServerPlayer p : nearby)
			net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, pkt);
	}

	/** 服务端助手：只把某座位的语音事件发给指定玩家（如流局听牌/未听只让本人听到） */
	public static void sendVoiceTo(ServerPlayer player, BlockPos origin, int seat, int event) {
		if (player == null || origin == null)
			return;
		net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
				new VoiceEventPayload(origin.asLong(), seat, event));
	}

	/**
	 * S→C：某座位和牌后的报番役种列表（英文役名，按结算展示顺序）。
	 * 客户端在该座位的 act 语音之后按序追加播报（仅内置角色有报番音频）。
	 */
	public record VoiceFanPayload(long originPacked, int seat, List<String> keys) implements CustomPacketPayload {
		public static final Type<VoiceFanPayload> TYPE = new Type<>(
				ResourceLocation.fromNamespaceAndPath(RichiiAriseMod.MODID, "richi_voice_fan"));

		public static final StreamCodec<RegistryFriendlyByteBuf, VoiceFanPayload> STREAM_CODEC = StreamCodec.of(
				(RegistryFriendlyByteBuf buf, VoiceFanPayload msg) -> {
					buf.writeLong(msg.originPacked());
					buf.writeVarInt(msg.seat());
					int n = Math.min(msg.keys().size(), 32); // 与读取端同一上限，避免多写导致后续包错位
					buf.writeVarInt(n);
					for (int i = 0; i < n; i++)
						buf.writeUtf(msg.keys().get(i), 32);
				},
				(RegistryFriendlyByteBuf buf) -> {
					long origin = buf.readLong();
					int seat = buf.readVarInt();
					// 宝牌计数键排在列表末尾，截断上限过低会把宝牌整段丢掉（最多 32 键，服务端同上限）
					int n = Math.min(buf.readVarInt(), 32);
					List<String> keys = new ArrayList<>(n);
					for (int i = 0; i < n && buf.isReadable(); i++)
						keys.add(buf.readUtf(32));
					return new VoiceFanPayload(origin, seat, keys);
				});

		@Override
		public Type<VoiceFanPayload> type() {
			return TYPE;
		}

		public static void handleData(final VoiceFanPayload message, final IPayloadContext context) {
			if (context.flow() == PacketFlow.CLIENTBOUND) {
				context.enqueueWork(() -> mcr.riichi_arise.voice.VoiceClient.onFanEvent(message.originPacked(),
						message.seat(), message.keys()));
			}
		}
	}

	/** 服务端助手：把某座位的报番役种发给桌周附近（24 格）的玩家 */
	public static void sendFan(ServerLevel level, BlockPos origin, int seat, List<String> keys) {
		if (level == null || origin == null || keys == null || keys.isEmpty())
			return;
		AABB box = AABB.encapsulatingFullBlocks(origin, origin).inflate(24, 24, 24);
		List<ServerPlayer> nearby = level.getEntitiesOfClass(ServerPlayer.class, box);
		if (nearby.isEmpty())
			return;
		VoiceFanPayload pkt = new VoiceFanPayload(origin.asLong(), seat, keys);
		for (ServerPlayer p : nearby)
			net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, pkt);
	}

	public static void register() {
		RichiiAriseMod.addNetworkMessage(VoiceSetMessage.TYPE, VoiceSetMessage.STREAM_CODEC, VoiceSetMessage::handleData);
		RichiiAriseMod.addNetworkMessage(VoiceEventPayload.TYPE, VoiceEventPayload.STREAM_CODEC, VoiceEventPayload::handleData);
		RichiiAriseMod.addNetworkMessage(VoiceFanPayload.TYPE, VoiceFanPayload.STREAM_CODEC, VoiceFanPayload::handleData);
	}
}
