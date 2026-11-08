package mcr.riichi_arise.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

import mcr.riichi_arise.network.WinEffectPayload;

/**
 * 和牌特效客户端播放器：与立直特效共享 {@link TableTileEffect} 的同一套样式
 * （0 = 关闭 / 1 = 光环 / 2 = 星环旋舞 / 3 = 光柱），随 {@link WinEffectPayload} 下发。
 * <p>沿用和牌特效自身的范围约束：和牌那张牌旁半径 {@link #RADIUS} 格、高度 {@link #HEIGHT} 格、
 * 一次性时间线 {@link #DURATION} tick（1.5 秒）内播完。</p>
 */
public final class WinEffectClient {
	/** 特效水平半径（格）：和牌那张牌旁边的 0.3 格范围，粒子严格不超出 */
	private static final double RADIUS = 0.3;
	/** 特效竖直高度上限（格） */
	private static final double HEIGHT = 0.45;
	/** 动画总时长（tick）：30 tick = 1.5 秒 */
	private static final int DURATION = 30;

	private static Effect active;

	private WinEffectClient() {
	}

	/** 收到服务端通知：使用和牌者自己的特效风格（他人也按其设置看到）；0 = 关闭 */
	public static void play(WinEffectPayload.Notice notice) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null)
			return;
		if (notice.style() <= TableTileEffect.STYLE_OFF) {
			active = null; // 关闭 / 未知风格：不播放
			return;
		}
		Vec3 center = new Vec3(notice.x(), notice.y() + 0.1, notice.z());
		active = new Effect(level,
				TableTileEffect.create(level, center, notice.style(), RADIUS, HEIGHT, DURATION));
		active.step(0); // 第 0 帧立即出现，不必等下一 tick
	}

	/** 客户端 tick：推进当前特效，结束后清空 */
	public static void tick() {
		if (active == null)
			return;
		active.age++;
		if (active.age > DURATION || active.level != Minecraft.getInstance().level)
			active = null;
		else
			active.step(active.age);
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
