package mcr.richi.network;

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

import mcr.richi.RichiiAriseMod;
import mcr.richi.game.MahjongLobby;

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
				context.enqueueWork(() -> mcr.richi.voice.VoiceClient.onVoiceEvent(message.originPacked(),
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

	public static void register() {
		RichiiAriseMod.addNetworkMessage(VoiceSetMessage.TYPE, VoiceSetMessage.STREAM_CODEC, VoiceSetMessage::handleData);
		RichiiAriseMod.addNetworkMessage(VoiceEventPayload.TYPE, VoiceEventPayload.STREAM_CODEC, VoiceEventPayload::handleData);
	}
}
