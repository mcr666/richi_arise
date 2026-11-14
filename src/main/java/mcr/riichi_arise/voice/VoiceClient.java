package mcr.riichi_arise.voice;

import net.neoforged.neoforge.network.PacketDistributor;

import mcr.riichi_arise.network.MahjongLobbyPayloads;
import mcr.riichi_arise.network.VoicePayloads;

/**
 * 角色语音客户端协调器：把对局事件包转成实际播报。
 * 语音只从已加载的资源包读取（见 {@link VoiceCache}），不再联网下载；未装对应语音包的玩家静默不播放。
 * 仅客户端使用。
 */
public final class VoiceClient {
	private VoiceClient() {
	}

	/** 收到 S→C 语音事件：查该桌该座玩家的语音并播报 */
	public static void onVoiceEvent(long originPacked, int seat, int event) {
		MahjongLobbyPayloads.LobbySync sync = MahjongLobbyPayloads.CLIENT_SYNC.get(originPacked);
		if (sync == null || sync.seatVoices() == null || seat < 0 || seat >= sync.seatVoices().length)
			return;
		String voiceId = sync.seatVoices()[seat];
		if (voiceId == null || voiceId.isEmpty())
			return;
		VoiceEvents ev = VoiceEvents.byCode(event);
		// 世界播报：音源取牌桌中心，按本地玩家距离衰减（与普通 MC 音效一致）
		net.minecraft.world.phys.Vec3 source = tableSource(originPacked);
		// 和牌语音（荣/自摸）：新语音打断尚未播完的前一条（避免叠音）；结算界面等语音播完才打开
		if (ev == VoiceEvents.RON || ev == VoiceEvents.TUMO)
			VoicePlayer.playConcurrent(voiceId, ev, source);
		else
			VoicePlayer.play(voiceId, ev, source, null); // 其它事件（吃/碰/杠/立直）同样打断式播放
	}

	/** 收到 S→C 报番事件：在该座位的 act 语音之后按役种顺序追加播报（无报番音频的角色回退默认角色） */
	public static void onFanEvent(long originPacked, int seat, java.util.List<String> yakuKeys) {
		MahjongLobbyPayloads.LobbySync sync = MahjongLobbyPayloads.CLIENT_SYNC.get(originPacked);
		if (sync == null || sync.seatVoices() == null || seat < 0 || seat >= sync.seatVoices().length)
			return;
		// 空语音（默认角色）也交给 YakuVoices 处理：它会清空上一局残留的报番/段位语音状态
		YakuVoices.enqueue(sync.seatVoices()[seat], yakuKeys, tableSource(originPacked));
	}

	/** 牌桌中心的世界坐标（方块中心），语音按此位置做距离衰减 */
	private static net.minecraft.world.phys.Vec3 tableSource(long originPacked) {
		return net.minecraft.world.phys.Vec3.atCenterOf(net.minecraft.core.BlockPos.of(originPacked));
	}

	/** 玩家在"个人设置"中选择自己的语音（发 C→S，供其他装了同一语音包的玩家播报） */
	public static void setOwnVoice(String voiceId) {
		PacketDistributor.sendToServer(new VoicePayloads.VoiceSetMessage(voiceId));
	}
}
