package mcr.riichi_arise.voice;

import net.neoforged.neoforge.network.PacketDistributor;

import mcr.riichi_arise.network.MahjongLobbyPayloads;
import mcr.riichi_arise.network.VoicePayloads;

/**
 * 角色语音客户端协调器：把对局事件包转成实际播报，并在开局前/切换时触发下载。
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
		VoicePlayer.play(voiceId, VoiceEvents.byCode(event));
	}

	/** 对局开始：确保当前桌所有入座玩家的语音已缓存（客户端本地缓存） */
	public static void onGameStart(long originPacked) {
		VoiceCache.ensureSeatedVoices(originPacked);
	}

	/** 玩家在"个人设置"中选择自己的语音（发 C→S；同时确保自己的语音已缓存以便试听） */
	public static void setOwnVoice(String voiceId) {
		PacketDistributor.sendToServer(new VoicePayloads.VoiceSetMessage(voiceId));
		if (voiceId != null && !voiceId.isEmpty())
			VoiceCache.ensureCached(voiceId);
	}

	/**
	 * "下载完成后才能选用"：选择某角色为语音。
	 * <ul><li>离线内置角色：随 mod 打包，直接选用；</li>
	 * <li>在线角色：须先下载完成（6 条语音全部就绪）才发 C→S 设为语音，否则只下载不选中。</li></ul>
	 * {@code onDone} 在客户端线程回调（可安全更新 UI）；回调里可用 {@link VoiceCache#isReady}
	 * 判断是否成功选中。
	 */
	public static void selectVoice(String voiceId, Runnable onDone) {
		if (voiceId == null || voiceId.isEmpty())
			return;
		if (VoiceCharacters.isOffline(voiceId)) {
			setOwnVoice(voiceId); // 内置直接可用
			if (onDone != null)
				onDone.run();
			return;
		}
		VoiceCache.ensureCached(voiceId, () -> {
			if (VoiceCache.isReady(voiceId))
				setOwnVoice(voiceId); // 下载完成才真正选用
			if (onDone != null)
				onDone.run();
		});
	}
}
