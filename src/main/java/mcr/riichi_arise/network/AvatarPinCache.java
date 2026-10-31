package mcr.riichi_arise.network;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端 AI 形象索引缓存（与服务端 {@link MahjongTablePayload#dedupe} 对应）：
 * 包内只带索引时，按索引取回 form/name/skin。索引 0 = 空座。
 */
public final class AvatarPinCache {
	private static final Map<Integer, String[]> PINS = new ConcurrentHashMap<>();

	private AvatarPinCache() {
	}

	/** 收到带真值的条目时刷新缓存（空真值 = 沿用旧内容）；返回缓存中是否因此发生了淘汰 */
	static boolean remember(int id, String form, String name, String skin) {
		if (id <= 0)
			return false;
		if (form.isEmpty() && name.isEmpty() && skin.isEmpty())
			return false;
		PINS.put(id, new String[] { form, name, skin });
		return PINS.size() > 256;
	}

	/** 缓存溢出：整体清空（服务端通知后下一包会重新携带真值） */
	static void clear() {
		PINS.clear();
	}

	static String form(int id) {
		return get(id, 0);
	}

	static String name(int id) {
		return get(id, 1);
	}

	static String skin(int id) {
		return get(id, 2);
	}

	private static String get(int id, int i) {
		String[] v = PINS.get(id);
		return v == null ? "" : v[i];
	}
}
