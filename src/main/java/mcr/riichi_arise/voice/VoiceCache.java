package mcr.riichi_arise.voice;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * 角色语音获取（客户端）。
 * <p>act 与报番语音都完全由已加载的资源包提供：
 * {@code assets/<命名空间>/sounds/voice/<角色id>/act_<事件>.mp3} 与 {@code .../fan/<音键>.mp3}。
 * 主命名空间 {@code riichi_arise} 的路径优先（资源包沿用该路径即可覆盖），其他命名空间按
 * 「角色 id + 相对路径」兜底；都找不到时返回 null，调用方静默跳过。
 * 因此只有加载了同一语音包的玩家才会听到该角色的喊话，未加载者不播放。</p>
 */
public final class VoiceCache {
	private VoiceCache() {
	}

	/** 取某角色某事件的 act 语音流；找不到返回 null（调用方静默跳过） */
	public static InputStream open(String id, VoiceEvents ev) {
		return openVoice(id, ev.act());
	}

	/** 报番语音：{@code sounds/voice/<id>/fan/<key>.mp3}；找不到返回 null */
	public static InputStream openFan(String id, String key) {
		if (key == null || key.isEmpty())
			return null;
		return openVoice(id, "fan/" + key);
	}

	/**
	 * 已加载资源包中可用的角色 id（至少提供一条 act 语音），按字母序。
	 * 资源包里没有的角色不会出现在列表中；语音文件缺失的角色选中后静默不播放。
	 */
	public static List<String> voiceIds() {
		index();
		synchronized (VoiceCache.class) {
			return new ArrayList<>(actIds);
		}
	}

	/**
	 * 是否已装入报番语音资源包：任一角色带 {@code fan/*.mp3} 即视为已安装。
	 * 未安装时结算报番会回退固定节奏，客户端据此在进游戏时提示玩家。
	 */
	public static boolean hasFanVoicePack() {
		try {
			for (String key : index().keySet())
				if (key.contains("|fan/"))
					return true;
			return false;
		} catch (Throwable e) {
			return false;
		}
	}

	/** 索引所属的资源管理器（F3+T 重载后实例变化 → 自动重建索引） */
	private static Object indexOwner;
	/** 「角色id|相对路径（不含 .mp3）」→ 语音定位符 */
	private static Map<String, ResourceLocation> voiceIndex = Map.of();
	/** 至少有一条 act 语音的角色 id（升序） */
	private static List<String> actIds = List.of();

	private static Map<String, ResourceLocation> index() {
		ResourceManager rm = Minecraft.getInstance().getResourceManager();
		synchronized (VoiceCache.class) {
			if (indexOwner != rm) {
				buildIndex(rm);
				indexOwner = rm;
			}
			return voiceIndex;
		}
	}

	/** 扫描所有已启用资源包的 sounds/voice/<id>/**\/*.mp3，主命名空间优先 */
	private static void buildIndex(ResourceManager rm) {
		final String prefix = "sounds/voice/";
		final String mainNs = "riichi_arise";
		Map<String, ResourceLocation> out = new HashMap<>();
		TreeSet<String> ids = new TreeSet<>();
		try {
			for (ResourceLocation loc : rm
					.listResources("sounds/voice", l -> l.getPath().endsWith(".mp3")).keySet()) {
				String ns = loc.getNamespace();
				if ("minecraft".equals(ns))
					continue;
				String path = loc.getPath();
				if (!path.startsWith(prefix))
					continue;
				String rest = path.substring(prefix.length(), path.length() - ".mp3".length());
				int slash = rest.indexOf('/');
				if (slash <= 0)
					continue;
				String id = rest.substring(0, slash);
				String rel = rest.substring(slash + 1);
				if (rel.isEmpty() || !VoiceCharacters.isValidId(id))
					continue;
				String key = id + "|" + rel;
				ResourceLocation prev = out.get(key);
				// 主命名空间优先；同一命名空间内由资源包优先级决定（listResources 已解析）
				if (prev == null || (!mainNs.equals(prev.getNamespace()) && mainNs.equals(ns)))
					out.put(key, loc);
				if (rel.startsWith("act_"))
					ids.add(id);
			}
		} catch (Throwable ignored) {
		}
		voiceIndex = Map.copyOf(out);
		actIds = List.copyOf(ids);
	}

	/**
	 * 取 {@code sounds/voice/<id>/<relative>.mp3}：
	 * 先按主命名空间直接查找（遵循资源包优先级），再按其他命名空间兜底。
	 */
	private static InputStream openVoice(String id, String relative) {
		if (!VoiceCharacters.isValidId(id) || relative == null || relative.isEmpty())
			return null;
		try {
			ResourceManager rm = Minecraft.getInstance().getResourceManager();
			ResourceLocation direct = ResourceLocation.fromNamespaceAndPath("riichi_arise",
					"sounds/voice/" + id + "/" + relative + ".mp3");
			var opt = rm.getResource(direct);
			if (opt.isPresent())
				return opt.get().open();
		} catch (Throwable ignored) {
		}
		try {
			ResourceLocation loc = index().get(id + "|" + relative);
			if (loc == null)
				return null;
			var opt = Minecraft.getInstance().getResourceManager().getResource(loc);
			if (opt.isPresent())
				return opt.get().open();
		} catch (Throwable ignored) {
		}
		return null;
	}
}
