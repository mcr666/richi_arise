package mcr.riichi_arise.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

import mcr.riichi_arise.voice.VoiceCache;

/**
 * 客户端资源包重载监听：F3+T 或启用新资源包后清空语音索引。
 * <p>{@code Minecraft} 的 {@code ReloadableResourceManager} 在重载时复用同一实例，
 * 只靠实例比较无法发现重载，必须由监听器主动失效缓存，否则语音列表停留在旧扫描结果。</p>
 * <p>在 {@code RichiiAriseMod} 构造函数的客户端分支里注册到 mod 事件总线。</p>
 */
public final class VoicePackReloadHandler {
	private VoicePackReloadHandler() {
	}

	public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
		event.registerReloadListener(new PreparableReloadListener() {
			@Override
			public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resourceManager,
					ProfilerFiller prepareProfiler, ProfilerFiller reloadProfiler, Executor backgroundExecutor,
					Executor gameExecutor) {
				// 只失效缓存，不在这里扫描：重载完成后首次访问（打开语音界面）再重建索引
				VoiceCache.invalidate();
				return barrier.<Void>wait(null);
			}
		});
	}
}
