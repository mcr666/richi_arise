package mcr.riichi_arise.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

import mcr.riichi_arise.network.RiichiEffectPayload;

/**
 * 立直特效客户端播放器：与和牌特效共享 {@link TableTileEffect} 的同一套样式
 * （0 = 关闭 / 1 = 随机 / 2 = 光环 / 3 = 星环旋舞 / 4 = 光柱 / 5..7 星陨·金阁·极光 / 8 = 默认），随 {@link RiichiEffectPayload} 下发。
 * <p>沿用立直特效自身的范围约束：宣言牌旁半径 {@link #RADIUS} 格、高度 {@link #HEIGHT} 格、{@link #DURATION} tick。
 * 多人先后/同时立直时按列表叠加播放（最多 {@link #MAX_ACTIVE} 个），每个特效粒子量很小。</p>
 */
public final class RiichiEffectClient {
	/** 特效水平半径（格） */
	private static final double RADIUS = 0.3;
	/** 特效竖直高度上限（格），与半径共同构成 0.3 半径的球形范围 */
	private static final double HEIGHT = 0.3;
	/** 动画总时长（tick）：20 tick = 1 秒 */
	private static final int DURATION = 20;
	/** 同时存在的立直特效上限（多人立直时防止无限叠加拖慢渲染） */
	private static final int MAX_ACTIVE = 4;

	private static final List<Effect> ACTIVE = new ArrayList<>();

	private RiichiEffectClient() {
	}

	/** 收到服务端通知：使用立直者自己的特效风格（他人也按其设置看到） */
	public static void play(RiichiEffectPayload.Notice notice) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null)
			return;
		// 0 = 关闭，不再播放
		if (notice.style() <= TableTileEffect.STYLE_OFF)
			return;
		if (ACTIVE.size() >= MAX_ACTIVE)
			ACTIVE.remove(0); // 挤掉最旧的一个，保证多人立直时仍有反馈
		Vec3 center = new Vec3(notice.x(), notice.y() + 0.08, notice.z());
		Effect effect = new Effect(level, TableTileEffect.create(level, center, notice.style(), RADIUS, HEIGHT, DURATION));
		ACTIVE.add(effect);
		effect.step(0); // 第 0 帧立即出现，不必等下一 tick
	}

	/** 客户端 tick：推进所有特效，结束后移除 */
	public static void tick() {
		ClientLevel level = Minecraft.getInstance().level;
		Iterator<Effect> it = ACTIVE.iterator();
		while (it.hasNext()) {
			Effect e = it.next();
			e.age++;
			if (e.age > DURATION || e.level != level)
				it.remove();
			else
				e.step(e.age);
		}
	}

	/** 单个特效实例（时间线状态） */
	private static final class Effect {
		private final ClientLevel level;
		private final TableTileEffect.Animation anim;
		private int age;

		private Effect(ClientLevel level, TableTileEffect.Animation anim) {
			this.level = level;
			this.anim = anim;
		}

		private void step(int t) {
			anim.step(t);
		}
	}
}
