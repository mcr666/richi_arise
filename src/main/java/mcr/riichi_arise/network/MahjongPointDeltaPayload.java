package mcr.riichi_arise.network;

import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import mcr.riichi_arise.RichiiAriseMod;

/**
 * 点数变化界面推送（S→C）：和牌结算界面结束后展示本局四家点数变化（局前 → 局后）。
 * mode=0 展示 / mode=1 关闭 / mode=2 预置（只缓存数据，等本地关闭结算界面后再打开）。
 * 客户端 6s 自动确认（回 settle_confirm 推进）。
 */
public class MahjongPointDeltaPayload {
	public static final int MODE_SHOW = 0, MODE_CLOSE = 1, MODE_PREPARE = 2;

	/** 预置的本局点数变化数据（关闭结算界面后本地直接打开，不必等服务端推送 SHOW） */
	public static volatile PointDeltaMessage prepared;

	/** 取指定桌的预置数据（origin 不符/未预置返回 null） */
	public static PointDeltaMessage preparedFor(long originPacked) {
		PointDeltaMessage p = prepared;
		return p != null && p.originPacked() == originPacked ? p : null;
	}

	public record PointDeltaMessage(int mode, long originPacked, int playerCount, java.util.List<String> names,
			java.util.List<Integer> before, java.util.List<Integer> after) implements CustomPacketPayload {
		public static final Type<PointDeltaMessage> TYPE = new Type<>(
				ResourceLocation.fromNamespaceAndPath(RichiiAriseMod.MODID, "richi_point_delta"));

		public static final StreamCodec<RegistryFriendlyByteBuf, PointDeltaMessage> STREAM_CODEC = StreamCodec.of(
				MahjongPointDeltaPayload::write, MahjongPointDeltaPayload::read);

		@Override
		public Type<PointDeltaMessage> type() {
			return TYPE;
		}

		public static void handleData(final PointDeltaMessage message, final IPayloadContext context) {
			if (context.flow() == PacketFlow.CLIENTBOUND) {
				context.enqueueWork(() -> {
					if (message.mode() == MODE_CLOSE) {
						prepared = null;
						mcr.riichi_arise.client.MahjongPointDeltaScreen.closeIfOpen();
						return;
					}
					prepared = message;
					if (message.mode() == MODE_PREPARE)
						return; // 只缓存：等玩家关闭和牌结算界面时再本地打开
					mcr.riichi_arise.client.MahjongPointDeltaScreen.open(message);
				});
			}
		}
	}

	private static void write(RegistryFriendlyByteBuf buf, PointDeltaMessage msg) {
		buf.writeVarInt(msg.mode());
		buf.writeLong(msg.originPacked());
		buf.writeVarInt(msg.playerCount());
		for (int i = 0; i < 4; i++)
			buf.writeUtf(msg.names().get(i) == null ? "" : msg.names().get(i), 64);
		for (int i = 0; i < 4; i++)
			buf.writeVarInt(msg.before().get(i));
		for (int i = 0; i < 4; i++)
			buf.writeVarInt(msg.after().get(i));
	}

	private static PointDeltaMessage read(RegistryFriendlyByteBuf buf) {
		int mode = buf.readVarInt();
		long origin = buf.readLong();
		int playerCount = buf.readVarInt();
		java.util.List<String> names = new java.util.ArrayList<>(4);
		for (int i = 0; i < 4; i++)
			names.add(buf.readUtf(64));
		java.util.List<Integer> before = new java.util.ArrayList<>(4);
		for (int i = 0; i < 4; i++)
			before.add(buf.readVarInt());
		java.util.List<Integer> after = new java.util.ArrayList<>(4);
		for (int i = 0; i < 4; i++)
			after.add(buf.readVarInt());
		return new PointDeltaMessage(mode, origin, playerCount, names, before, after);
	}

	/** 服务端便捷方法：向单个玩家推送点数变化界面 */
	public static void sendTo(net.minecraft.server.level.ServerPlayer player, long originPacked, int playerCount,
			String[] names, int[] before, int[] after) {
		net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
				new PointDeltaMessage(MODE_SHOW, originPacked, playerCount,
						java.util.Arrays.asList(names),
						java.util.List.of(before[0], before[1], before[2], before[3]),
						java.util.List.of(after[0], after[1], after[2], after[3])));
	}

	/** 服务端便捷方法：向单个玩家预置点数变化数据（不打开界面） */
	public static void sendPrepare(net.minecraft.server.level.ServerPlayer player, long originPacked, int playerCount,
			String[] names, int[] before, int[] after) {
		net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
				new PointDeltaMessage(MODE_PREPARE, originPacked, playerCount,
						java.util.Arrays.asList(names),
						java.util.List.of(before[0], before[1], before[2], before[3]),
						java.util.List.of(after[0], after[1], after[2], after[3])));
	}

	/** 服务端便捷方法：关闭某玩家已打开的点数变化界面 */
	public static void sendClose(net.minecraft.server.level.ServerPlayer player) {
		net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
				new PointDeltaMessage(MODE_CLOSE, 0, 4,
						java.util.List.of("", "", "", ""),
						java.util.List.of(0, 0, 0, 0), java.util.List.of(0, 0, 0, 0)));
	}

	public static void register() {
		RichiiAriseMod.addNetworkMessage(PointDeltaMessage.TYPE, PointDeltaMessage.STREAM_CODEC,
				PointDeltaMessage::handleData);
	}
}
