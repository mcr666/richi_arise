package mcr.riichi_arise.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * 牌桌共用特效定义：和牌特效与立直特效共享同一套样式（光环 / 星环旋舞 / 光柱，0 = 关闭，4 = 随机）。
 * <p>样式 1~3 的动画完全一致，仅由调用方传入各自的半径 / 高度 / 时长参数，
 * 从而沿用和牌特效与立直特效原有的范围约束。</p>
 * <p>只使用原版粒子与原版音效，粒子严格夹取在给定水平半径与高度内，粒子量保持不变。</p>
 */
public final class TableTileEffect {
	/** 0 = 关闭；1 = 光环；2 = 星环旋舞；3 = 光柱；4 = 随机（每次触发时随机取 1..3 之一） */
	public static final int STYLE_OFF = 0, STYLE_HALO = 1, STYLE_STAR = 2, STYLE_PILLAR = 3, STYLE_RANDOM = 4;
	/** 设置界面 / 弹出列表的候选数量（含关闭与随机） */
	public static final int STYLE_COUNT = 5;

	/** 风格显示名（设置界面 / 弹出列表共用） */
	public static final String[] STYLE_KEYS = {
			"gui.riichi_arise.settings.riichi_effect.off",
			"gui.riichi_arise.settings.riichi_effect.aura",
			"gui.riichi_arise.settings.riichi_effect.star",
			"gui.riichi_arise.settings.riichi_effect.pillar",
			"gui.riichi_arise.settings.riichi_effect.random" };

	private TableTileEffect() {
	}

	/**
	 * 展示值解析：随机档取一种实际样式（1..3）；关闭 / 具体样式原样返回。
	 * <p>正常链路在服务端触发时已解析（全桌看到同一效果），客户端这里仅作兜底，
	 * 且每次播放只解析一次，不会逐 tick 重掷。</p>
	 */
	public static int resolve(int style) {
		if (style == STYLE_RANDOM)
			return STYLE_HALO + java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
		return style;
	}

	/** 按调用方给定的范围参数创建一个特效时间线实例（随机档先解析为具体样式） */
	public static Animation create(ClientLevel level, Vec3 center, int style, double radius, double height,
			int duration) {
		return new Animation(level, center, Math.max(0, Math.min(STYLE_PILLAR, resolve(style))), radius, height,
				duration);
	}

	/** 单个特效实例（时间线状态），由调用方决定何时推进 / 结束 */
	public static final class Animation {
		private final ClientLevel level;
		private final Vec3 c;
		private final int style;
		private final double radius;
		private final double height;
		private final int duration;

		Animation(ClientLevel level, Vec3 center, int style, double radius, double height, int duration) {
			this.level = level;
			this.c = center;
			this.style = style;
			this.radius = Math.max(0.05, radius);
			this.height = Math.max(0.05, height);
			this.duration = Math.max(1, duration);
		}

		/** 推进到第 t tick（t=0 为首帧） */
		public void step(int t) {
			switch (style) {
				case STYLE_STAR -> starRing(t);
				case STYLE_PILLAR -> lightPillar(t);
				default -> halo(t);
			}
		}

		private void sound(SoundEvent event, float volume, float pitch) {
			level.playLocalSound(c.x, c.y, c.z, event, SoundSource.PLAYERS, volume, pitch, false);
		}

		/** 1：光环——一圈扩散光环 + 缓慢旋转的悬浮光点 */
		private void halo(int t) {
			if (t == 0)
				sound(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.7f, 1.5f);
			// 扩散光环：半径 0.08 → 上限，两圈相位错开
			if (t <= 12) {
				double r = 0.08 + radius * 0.73 * (t / 12.0);
				for (int i = 0; i < 12; i++) {
					double a = Math.PI * 2 * i / 12 + t * 0.3;
					spawn(ParticleTypes.END_ROD, Math.cos(a) * r, 0.02, Math.sin(a) * r, 0, 0.01, 0);
				}
				if (t % 2 == 0) {
					double r2 = 0.05 + radius * 0.8 * (t / 12.0);
					for (int i = 0; i < 6; i++) {
						double a = -Math.PI * 2 * i / 6 - t * 0.4;
						spawn(ParticleTypes.ELECTRIC_SPARK, Math.cos(a) * r2, 0.05, Math.sin(a) * r2, 0, 0, 0);
					}
				}
			}
			// 悬浮光点：三个相位缓慢上升后收束
			double a = t * 0.6;
			double y = Math.min(height, 0.05 + t * 0.01);
			for (int k = 0; k < 3; k++) {
				double aa = a + k * (Math.PI * 2 / 3);
				spawn(ParticleTypes.END_ROD, Math.cos(aa) * 0.12, y, Math.sin(aa) * 0.12, 0, 0.005, 0);
			}
		}

		/** 2：星环旋舞——反向双层星环 + 铃音，节奏感更强 */
		private void starRing(int t) {
			if (t == 0)
				sound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.6f, 1.6f);
			if (t == 8 || t == 16)
				sound(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.35f, 2.0f);
			double pulse = Math.sin(Math.PI * t / duration);
			double r = 0.08 + radius * 0.7 * Math.max(0.2, pulse);
			double y = 0.05 + Math.min(height - 0.05, t * 0.008);
			for (int i = 0; i < 8; i++) {
				double a = Math.PI * 2 * i / 8 + t * 0.55;
				spawn(ParticleTypes.END_ROD, Math.cos(a) * r, y, Math.sin(a) * r, 0, 0, 0);
			}
			for (int i = 0; i < 8; i++) {
				double a = -Math.PI * 2 * i / 8 - t * 0.75;
				spawn(ParticleTypes.FIREWORK, Math.cos(a) * r * 0.72, y * 0.8, Math.sin(a) * r * 0.72,
						0, 0.01, 0);
			}
			// 中心星芒（不用大范围闪光粒子，保持严格范围）
			if (t % 3 == 0) {
				spawn(ParticleTypes.END_ROD, 0.01, 0.08, 0.01, 0, 0.01, 0);
				spawn(ParticleTypes.CRIT, -0.01, 0.08, -0.01, 0, 0.01, 0);
			}
		}

		/** 3：光柱——中央小光柱 + 底部法阵环 */
		private void lightPillar(int t) {
			if (t == 0)
				sound(SoundEvents.BEACON_ACTIVATE, 0.5f, 1.8f);
			double y = Math.min(height, 0.02 + t * 0.012);
			for (int k = 0; k < 4; k++) {
				double a = t * 0.9 + k * (Math.PI * 2 / 4);
				double r = 0.035;
				spawn(k % 2 == 0 ? ParticleTypes.END_ROD : ParticleTypes.ENCHANT,
						Math.cos(a) * r, y, Math.sin(a) * r, 0, 0.01, 0);
			}
			// 底部法阵环（前 12 tick 由内向外展开）
			if (t <= 12) {
				double r = 0.09 + radius * 0.65 * (t / 12.0);
				for (int i = 0; i < 10; i++) {
					double a = Math.PI * 2 * i / 10 - t * 0.25;
					spawn(i % 3 == 0 ? ParticleTypes.ELECTRIC_SPARK : ParticleTypes.END_ROD,
							Math.cos(a) * r, 0.02, Math.sin(a) * r, 0, 0, 0);
				}
			}
			// 收束余烬：贴柱向上
			if (t > 10 && t % 2 == 0) {
				spawn(ParticleTypes.SOUL_FIRE_FLAME, 0.02 * Math.cos(t), Math.min(height, y + 0.05),
						0.02 * Math.sin(t), 0, 0.02, 0);
			}
		}

		/** 生成一个粒子：按中心点 + 相对偏移计算，并夹取到半径 / 高度范围内 */
		private void spawn(ParticleOptions type, double dx, double dy, double dz, double vx, double vy, double vz) {
			double horiz = Math.sqrt(dx * dx + dz * dz);
			if (horiz > radius) {
				double k = radius / horiz;
				dx *= k;
				dz *= k;
			}
			double y = Math.max(0, Math.min(height, dy));
			level.addParticle(type, c.x + dx, c.y + y, c.z + dz, vx, vy, vz);
		}
	}
}
