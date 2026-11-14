package mcr.riichi_arise.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import mcr.riichi_arise.RichiiAriseMod;

/**
 * 立直特效：与和牌特效同构——个人设定持久化在服务端，立直宣言时按声明者设置向全桌下发通知，
 * 粒子动画完全在客户端绘制（范围限制在宣言牌旁半径 0.3 格内）。
 */
public class RiichiEffectPayload {

	/** S→C：立直宣言特效通知（声明者座位、风格、宣言牌位置） */
	public record Notice(BlockPos origin, int seat, int style, double x, double y, double z)
			implements CustomPacketPayload {
		public static final Type<Notice> TYPE = new Type<>(
				ResourceLocation.fromNamespaceAndPath(RichiiAriseMod.MODID, "riichi_effect"));

		public static final StreamCodec<RegistryFriendlyByteBuf, Notice> STREAM_CODEC = StreamCodec.of(
				(RegistryFriendlyByteBuf buf, Notice msg) -> {
					buf.writeBlockPos(msg.origin());
					buf.writeVarInt(msg.seat());
					buf.writeVarInt(msg.style());
					buf.writeDouble(msg.x());
					buf.writeDouble(msg.y());
					buf.writeDouble(msg.z());
				},
				(RegistryFriendlyByteBuf buf) -> new Notice(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(),
						buf.readDouble(), buf.readDouble(), buf.readDouble()));

		@Override
		public Type<Notice> type() {
			return TYPE;
		}

		public static void handleData(final Notice message, final IPayloadContext context) {
			if (context.flow() == PacketFlow.CLIENTBOUND)
				context.enqueueWork(() -> mcr.riichi_arise.client.RiichiEffectClient.play(message));
		}
	}

	/** C→S：设置自己的立直特效风格（0 = 关闭 / 1 = 随机 / 2..7 各风格 / 8 = 默认，个人设定；全桌可见） */
	public record SetMessage(int style) implements CustomPacketPayload {
		public static final Type<SetMessage> TYPE = new Type<>(
				ResourceLocation.fromNamespaceAndPath(RichiiAriseMod.MODID, "riichi_effect_set"));

		public static final StreamCodec<RegistryFriendlyByteBuf, SetMessage> STREAM_CODEC = StreamCodec.of(
				(RegistryFriendlyByteBuf buf, SetMessage msg) -> buf.writeVarInt(msg.style()),
				(RegistryFriendlyByteBuf buf) -> new SetMessage(buf.readVarInt()));

		@Override
		public Type<SetMessage> type() {
			return TYPE;
		}

		public static void handleData(final SetMessage message, final IPayloadContext context) {
			if (context.flow() == PacketFlow.SERVERBOUND) {
				context.enqueueWork(() -> {
					if (context.player() instanceof ServerPlayer sp)
						mcr.riichi_arise.game.MahjongLobby.setRiichiEffect(sp, message.style());
				});
			}
		}
	}

	public static void register() {
		RichiiAriseMod.addNetworkMessage(Notice.TYPE, Notice.STREAM_CODEC, Notice::handleData);
		RichiiAriseMod.addNetworkMessage(SetMessage.TYPE, SetMessage.STREAM_CODEC, SetMessage::handleData);
	}
}
