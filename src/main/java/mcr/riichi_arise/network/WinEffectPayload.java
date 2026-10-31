package mcr.riichi_arise.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import mcr.riichi_arise.RichiiAriseMod;

/**
 * 和牌特效通知（S -> C）：和牌时由服务端向桌旁玩家各发一次。
 * <p>带和牌者座位与其"个人设定"里的特效风格：所有观看者都按和牌者的设置渲染（他人也看得到）。
 * 具体动画（原版粒子）全部在客户端绘制，范围限制在桌面内。</p>
 */
public class WinEffectPayload {

	public record Notice(BlockPos origin, int seat, int style, double x, double y, double z)
			implements CustomPacketPayload {
		public static final Type<Notice> TYPE = new Type<>(
				ResourceLocation.fromNamespaceAndPath(RichiiAriseMod.MODID, "win_effect"));

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
				context.enqueueWork(() -> mcr.riichi_arise.client.WinEffectClient.play(message));
		}
	}

	public static void register() {
		RichiiAriseMod.addNetworkMessage(Notice.TYPE, Notice.STREAM_CODEC, Notice::handleData);
	}
}
