package mcr.riichi_arise.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import mcr.riichi_arise.RichiiAriseMod;
import mcr.riichi_arise.RichiGameRules;
import mcr.riichi_arise.net.RichiNetConfig;
import mcr.riichi_arise.net.RichiNetManager;

/**
 * 麻雀"内容设置"网络包（风盘设置界面）。
 * <ul>
 * <li>SyncMessage（S→C）：下发世界规则（gamerule）当前值 + 内网端口状态 + 收件人管理权限，
 * 客户端设置界面的"世界规则/网络接口"分类据此渲染。</li>
 * <li>ActionMessage（C→S）：请求同步 / 设置 gamerule / 手动开启与关闭内网端口。</li>
 * </ul>
 * 客户端配置（观战摊手等）与个人语音不改世界规则，不走本包。
 */
public class MahjongSettingsPayloads {
	/** C→S 动作：请求同步 / 设 gamerule / 开端口 / 关端口 */
	public static final int ACT_REQUEST_SYNC = 0, ACT_SET_GAMERULE = 1, ACT_NET_START = 2, ACT_NET_STOP = 3;
	/** gamerule 下标（ACT_SET_GAMERULE 的 extra）：autoOpenNetPort */
	public static final int RULE_AUTO_NET = 0;

	/** S→C：设置状态同步 */
	public record SettingsSync(boolean autoOpenNetPort,
			boolean netRunning, int netPort, String netBind, int netConn, boolean canManage)
			implements CustomPacketPayload {
		public static final Type<SettingsSync> TYPE = new Type<>(
				ResourceLocation.fromNamespaceAndPath(RichiiAriseMod.MODID, "richi_settings_sync"));

		public static final StreamCodec<RegistryFriendlyByteBuf, SettingsSync> STREAM_CODEC = StreamCodec.of(
				(RegistryFriendlyByteBuf buf, SettingsSync m) -> {
					buf.writeBoolean(m.autoOpenNetPort());
					buf.writeBoolean(m.netRunning());
					buf.writeVarInt(m.netPort());
					buf.writeUtf(m.netBind(), 64);
					buf.writeVarInt(m.netConn());
					buf.writeBoolean(m.canManage());
				},
				(RegistryFriendlyByteBuf buf) -> new SettingsSync(buf.readBoolean(),
						buf.readBoolean(), buf.readVarInt(), buf.readUtf(64), buf.readVarInt(), buf.readBoolean()));

		@Override
		public Type<SettingsSync> type() {
			return TYPE;
		}

		public static void handleData(final SettingsSync message, final IPayloadContext context) {
			if (context.flow() == PacketFlow.CLIENTBOUND)
				context.enqueueWork(() -> mcr.riichi_arise.client.RichiSettingsScreen.onSync(message));
		}
	}

	/** C→S：设置动作（extra = gamerule 下标；flag = 布尔值 0/1；开/关端口时 extra/flag 忽略） */
	public record SettingsAction(int action, int extra, int flag) implements CustomPacketPayload {
		public static final Type<SettingsAction> TYPE = new Type<>(
				ResourceLocation.fromNamespaceAndPath(RichiiAriseMod.MODID, "richi_settings_action"));

		public static final StreamCodec<RegistryFriendlyByteBuf, SettingsAction> STREAM_CODEC = StreamCodec.of(
				(RegistryFriendlyByteBuf buf, SettingsAction m) -> {
					buf.writeVarInt(m.action());
					buf.writeVarInt(m.extra());
					buf.writeVarInt(m.flag());
				},
				(RegistryFriendlyByteBuf buf) -> new SettingsAction(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

		@Override
		public Type<SettingsAction> type() {
			return TYPE;
		}

		public static void handleData(final SettingsAction message, final IPayloadContext context) {
			if (context.flow() != PacketFlow.SERVERBOUND)
				return;
			context.enqueueWork(() -> {
				if (context.player() instanceof ServerPlayer sp)
					handleAction(sp, message);
			});
		}
	}

	/** 服务端：执行设置动作（gamerule 与端口开关需管理员权限），随后回发同步 */
	private static void handleAction(ServerPlayer sp, SettingsAction msg) {
		MinecraftServer server = sp.getServer();
		switch (msg.action()) {
			case ACT_REQUEST_SYNC -> sendSync(sp);
			case ACT_SET_GAMERULE -> {
				if (!sp.hasPermissions(2))
					return;
				boolean val = msg.flag() != 0;
				if (msg.extra() == RULE_AUTO_NET) {
					server.getGameRules().getRule(RichiGameRules.AUTO_OPEN_NET_PORT).set(val, server);
					if (val && !RichiNetManager.isRunning())
						RichiNetManager.start(server, RichiNetConfig.PORT, RichiNetConfig.BIND);
				}
				sendSync(sp);
			}
			case ACT_NET_START -> {
				if (!sp.hasPermissions(2))
					return;
				if (!RichiNetManager.isRunning())
					RichiNetManager.start(server, RichiNetConfig.PORT, RichiNetConfig.BIND);
				sendSync(sp);
			}
			case ACT_NET_STOP -> {
				if (!sp.hasPermissions(2))
					return;
				RichiNetManager.stop();
				sendSync(sp);
			}
			default -> {
			}
		}
	}

	/** 向收件人下发设置状态 */
	public static void sendSync(ServerPlayer sp) {
		MinecraftServer server = sp.getServer();
		SettingsSync sync = new SettingsSync(
				server.getGameRules().getBoolean(RichiGameRules.AUTO_OPEN_NET_PORT),
				RichiNetManager.isRunning(),
				RichiNetManager.port(),
				RichiNetManager.bind(),
				RichiNetManager.connectionCount(),
				sp.hasPermissions(2));
		net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, sync);
	}

	public static void register() {
		RichiiAriseMod.addNetworkMessage(SettingsSync.TYPE, SettingsSync.STREAM_CODEC, SettingsSync::handleData);
		RichiiAriseMod.addNetworkMessage(SettingsAction.TYPE, SettingsAction.STREAM_CODEC, SettingsAction::handleData);
	}
}
