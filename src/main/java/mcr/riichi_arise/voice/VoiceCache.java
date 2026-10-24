package mcr.riichi_arise.voice;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import mcr.riichi_arise.network.MahjongLobbyPayloads;

/**
 * 角色语音本地缓存与获取（客户端）。
 * <ul>
 * <li>离线内置角色：语音随 mod 打包于 {@code assets/riichi_arise/sounds/voice/<id>/act_<ev>.mp3}，
 * 经资源管理器（Minecraft.getResourceManager）直接读取，无需下载。</li>
 * <li>在线角色：语音从 {@code https://chen310.github.io/tenhou-se/character/<id>/act_<ev>.mp3}
 * 下载到 {@code <游戏目录>/riichi_arise-voice/<id>/act_<ev>.mp3} 本地缓存，对局开始前/手动点击时后台下载。</li>
 * </ul>
 * 全部为客户端专用，方法在客户端线程或下载线程调用均安全。
 */
public final class VoiceCache {
	private VoiceCache() {
	}

	/** 在线语音缓存目录（游戏目录/riichi_arise-voice） */
	public static File cacheDir() {
		return new File(Minecraft.getInstance().gameDirectory, "riichi_arise-voice");
	}

	private static final ExecutorService DOWNLOADER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "riichi-voice-download");
		t.setDaemon(true);
		return t;
	});

	private static final Set<String> DOWNLOADING = new HashSet<>();
	/** 等待下载完成的回调（按角色 id） */
	private static final java.util.Map<String, java.util.List<Runnable>> WAITERS = new java.util.HashMap<>();

	/** 缓存文件（在线角色）；不存在返回 null */
	public static File cachedFile(String id, VoiceEvents ev) {
		return new File(cacheDir(), id + "/" + ev.act() + ".mp3");
	}

	/** 该角色 6 条语音是否全部就绪（在线 = 6 条缓存文件齐全；离线 = 内置资源，始终可用） */
	public static boolean isReady(String id) {
		if (id == null || id.isEmpty())
			return false;
		if (VoiceCharacters.isOffline(id))
			return true; // 内置角色随 mod 打包，直接可用
		for (VoiceEvents ev : VoiceEvents.values()) {
			File f = cachedFile(id, ev);
			if (f == null || !f.isFile() || f.length() == 0)
				return false;
		}
		return true;
	}

	/** 当前是否正在下载该角色 */
	public static boolean isDownloading(String id) {
		synchronized (WAITERS) {
			return DOWNLOADING.contains(id);
		}
	}

	/**
	 * 取某角色某事件的语音流：
	 * <ol><li>内置/资源包资源 {@code riichi_arise:sounds/voice/<id>/act_<ev>.mp3}（离线角色优先）；</li>
	 * <li>本地缓存文件（在线角色下载所得）。</li></ol>
	 * 找不到返回 null（调用方静默跳过）。
	 */
	public static InputStream open(String id, VoiceEvents ev) {
		if (id == null || id.isEmpty())
			return null;
		// 1) 内置 / 资源包资源
		try {
			ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("riichi_arise",
					"sounds/voice/" + id + "/" + ev.act() + ".mp3");
			var opt = Minecraft.getInstance().getResourceManager().getResource(loc);
			if (opt.isPresent())
				return opt.get().open();
		} catch (Exception ignored) {
		}
		// 2) 本地缓存
		File f = cachedFile(id, ev);
		if (f != null && f.isFile() && f.canRead()) {
			try {
				return Files.newInputStream(f.toPath());
			} catch (Exception ignored) {
			}
		}
		return null;
	}

	/** 对局开始前/切换语音后，确保该角色 6 条语音已缓存（在线角色下载；离线角色内置无需下载） */
	public static void ensureCached(String id) {
		ensureCached(id, null);
	}

	/**
	 * 确保该角色语音已缓存；就绪后（含已就绪、离线、下载完成，无论成败）执行回调 {@code onDone}。
	 * 回调可能运行在下载线程，如需更新 UI 请自行切回客户端线程（Minecraft.getInstance().execute）。
	 */
	public static void ensureCached(String id, Runnable onDone) {
		if (id == null || id.isEmpty() || VoiceCharacters.isOffline(id)) {
			if (onDone != null)
				onDone.run(); // 离线/空：直接就绪
			return;
		}
		// DOWNLOADING 与 WAITERS 统一在 WAITERS 监视器下维护，避免跨锁竞争
		synchronized (WAITERS) {
			if (isReady(id)) {
				if (onDone != null)
					onDone.run();
				return;
			}
			WAITERS.computeIfAbsent(id, k -> new java.util.ArrayList<>()).add(onDone);
			if (DOWNLOADING.contains(id))
				return; // 已在下载，等完成
			DOWNLOADING.add(id);
		}
		DOWNLOADER.execute(() -> {
			try {
				for (VoiceEvents ev : VoiceEvents.values()) {
					File f = cachedFile(id, ev);
					if (f != null && f.isFile() && f.length() > 0)
						continue;
					String url = VoiceCharacters.actUrl(id, ev);
					if (url == null)
						continue;
					downloadTo(url, f);
				}
			} catch (Exception e) {
				com.mojang.logging.LogUtils.getLogger().warn("[voice] 下载语音失败 {}: {}", id, e.toString());
			} finally {
				java.util.List<Runnable> waiters;
				synchronized (WAITERS) {
					DOWNLOADING.remove(id);
					waiters = WAITERS.remove(id);
				}
				if (waiters != null)
					for (Runnable r : waiters) {
						try {
							if (r != null)
								r.run();
						} catch (Throwable ignored) {
						}
					}
			}
		});
	}

	/** 对局开始：确保当前牌桌所有入座玩家（人类）的语音已缓存 */
	public static void ensureSeatedVoices(long originPacked) {
		MahjongLobbyPayloads.LobbySync sync = MahjongLobbyPayloads.CLIENT_SYNC.get(originPacked);
		if (sync == null || sync.seatVoices() == null)
			return;
		for (String v : sync.seatVoices())
			if (v != null && !v.isEmpty())
				ensureCached(v);
	}

	private static void downloadTo(String url, File target) {
		if (target == null)
			return;
		File dir = target.getParentFile();
		if (dir != null && !dir.isDirectory())
			dir.mkdirs();
		try {
			URLConnection conn = java.net.URI.create(url).toURL().openConnection();
			conn.setConnectTimeout(8000);
			conn.setReadTimeout(15000);
			conn.setRequestProperty("User-Agent", "Mozilla/5.0 (RichiArise voice)");
			try (InputStream in = conn.getInputStream();
					OutputStream out = new FileOutputStream(target)) {
				byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) > 0)
					out.write(buf, 0, n);
			}
		} catch (Exception e) {
			if (target.isFile())
				target.delete(); // 半成品文件清理
			throw new RuntimeException(e);
		}
	}
}
