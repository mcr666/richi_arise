package mcr.riichi_arise.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * 牌桌共用特效定义：和牌特效与立直特效共享同一套 6 种样式
 * （光环 / 星环旋舞 / 光柱 / 星陨华彩 / 金阁爆华 / 极光回旋，0 = 关闭，1 = 随机）。
 * <p>同一套样式定义由和牌与立直共用，仅由调用方传入各自的半径 / 高度 / 时长参数，
 * 从而沿用和牌特效与立直特效原有的范围约束。</p>
 * <p>只使用原版粒子与原版音效，粒子严格夹取在给定水平半径与高度内，粒子量保持不变。</p>
 */
public final class TableTileEffect {
	/**
	 * 持久化取值（与界面顺序一致）：0 = 关闭；1 = 随机；2 = 光环；3 = 星环旋舞；4 = 光柱；
	 * 5 = 星陨华彩；6 = 金阁爆华；7 = 极光回旋。
	 * <p>旧存档由 MahjongTableSavedData 载入时一次性迁移到本套取值。</p>
	 */
	public static final int STYLE_OFF = 0, STYLE_RANDOM = 1, STYLE_HALO = 2, STYLE_STAR = 3, STYLE_PILLAR = 4,
			STYLE_STARFALL = 5, STYLE_GOLDEN = 6, STYLE_AURORA = 7;

	/** 具体样式值（不含关闭 / 随机）：随机档从这里等概率抽取 */
	public static final int[] ACTIVE_STYLES = { STYLE_HALO, STYLE_STAR, STYLE_PILLAR, STYLE_STARFALL, STYLE_GOLDEN,
			STYLE_AURORA };

	/**
	 * 设置界面 / 右键列表的展示顺序（左键循环同此顺序），与持久化数值顺序一致：
	 * 关闭 → 随机 → 光环 → 星环旋舞 → 光柱 → 星陨华彩 → 金阁爆华 → 极光回旋。
	 */
	public static final int[] SETTING_STYLES = { STYLE_OFF, STYLE_RANDOM, STYLE_HALO, STYLE_STAR, STYLE_PILLAR,
			STYLE_STARFALL, STYLE_GOLDEN, STYLE_AURORA };

	/** 设置界面 / 弹出列表的候选数量（关闭 + 随机 + 6 种样式） */
	public static final int STYLE_COUNT = SETTING_STYLES.length;

	/** 风格显示名（按持久化值索引；设置界面 / 弹出列表共用） */
	public static final String[] STYLE_KEYS = {
			"gui.riichi_arise.settings.riichi_effect.off",
			"gui.riichi_arise.settings.riichi_effect.random",
			"gui.riichi_arise.settings.riichi_effect.aura",
			"gui.riichi_arise.settings.riichi_effect.star",
			"gui.riichi_arise.settings.riichi_effect.pillar",
			"gui.riichi_arise.settings.riichi_effect.starfall",
			"gui.riichi_arise.settings.riichi_effect.golden",
			"gui.riichi_arise.settings.riichi_effect.aurora" };

	private TableTileEffect() {
	}

	/**
	 * 展示值解析：随机档从 6 种具体样式中取一种；关闭 / 具体样式原样返回。
	 * <p>正常链路在服务端触发时已解析（全桌看到同一效果），客户端这里仅作兜底，
	 * 且每次播放只解析一次，不会逐 tick 重掷。</p>
	 */
	public static int resolve(int style) {
		if (style == STYLE_RANDOM)
			return ACTIVE_STYLES[java.util.concurrent.ThreadLocalRandom.current().nextInt(ACTIVE_STYLES.length)];
		return style;
	}

	/** 按展示顺序取下一个设置值（左键循环用；未知值回到关闭） */
	public static int nextSettingStyle(int current) {
		for (int i = 0; i < SETTING_STYLES.length; i++) {
			if (SETTING_STYLES[i] == current)
				return SETTING_STYLES[(i + 1) % SETTING_STYLES.length];
		}
		return SETTING_STYLES[0];
	}

	/** 按调用方给定的范围参数创建一个特效时间线实例（随机档先解析为具体样式） */
	public static Animation create(ClientLevel level, Vec3 center, int style, double radius, double height,
			int duration) {
		return new Animation(level, center, Math.max(0, Math.min(STYLE_AURORA, resolve(style))), radius, height,
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
				case STYLE_STARFALL -> starfallBloom(t);
				case STYLE_GOLDEN -> goldenBurst(t);
				case STYLE_AURORA -> auroraSpiral(t);
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

		/** 5：星陨华彩——花冠开爆 + 反向波纹 + 收束余烬 */
		private void starfallBloom(int t) {
			if (t == 0) {
				sound(SoundEvents.FIREWORK_ROCKET_BLAST, 0.9f, 1.3f);
				sound(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.5f, 1.8f);
				// 原版 FLASH 视觉尺寸会越出范围，中心改用短促星芒爆点
				for (int i = 0; i < 10; i++) {
					double a = Math.PI * 2 * i / 10;
					double r = radius * 0.12;
					spawn(i % 2 == 0 ? ParticleTypes.END_ROD : ParticleTypes.CRIT,
							Math.cos(a) * r, 0.12, Math.sin(a) * r, Math.cos(a) * 0.02, 0.03, Math.sin(a) * 0.02);
				}
				// 三圈不同相位的小花冠（半径都在上限以内）
				for (int ringIdx = 0; ringIdx < 3; ringIdx++) {
					double r = radius * (0.33 + ringIdx * 0.27);
					int n = 8 + ringIdx * 4;
					for (int i = 0; i < n; i++) {
						double a = Math.PI * 2 * i / n + ringIdx * 0.4;
						spawn(i % 2 == 0 ? ParticleTypes.FIREWORK : ParticleTypes.END_ROD,
								Math.cos(a) * r, 0.1 + ringIdx * 0.06, Math.sin(a) * r,
								Math.cos(a) * 0.02, 0.04, Math.sin(a) * 0.02);
					}
				}
			}
			// 反向波纹：两圈彩点贴牌面向外扩，扩散到上限后停止
			if (t % 2 == 0 && t <= 10) {
				double r = Math.min(radius, 0.06 + t * 0.024);
				for (int i = 0; i < 10; i++) {
					double a = Math.PI * 2 * i / 10 + t * 0.2;
					spawn(ParticleTypes.ELECTRIC_SPARK, Math.cos(a) * r, 0.02, Math.sin(a) * r, 0, 0, 0);
					spawn(ParticleTypes.WAX_ON, Math.cos(-a) * r, 0.03, Math.sin(-a) * r, 0, 0, 0);
				}
			}
			// 收束的螺旋余烬：从外圈向中心聚拢并上升
			if (t > 8 && t % 2 == 0) {
				double a = t * 0.9;
				double span = Math.max(1, duration - 8);
				double r = Math.max(0.02, radius * (1 - (t - 8) / span));
				double y = Math.min(height, 0.08 + (t - 8) * 0.012);
				spawn(ParticleTypes.END_ROD, Math.cos(a) * r, y, Math.sin(a) * r, 0, 0.01, 0);
				sound(SoundEvents.NOTE_BLOCK_HAT.value(), 0.18f, 1.8f);
			}
		}

		/** 6：金阁爆华——金光爆点 + 金色光柱 + 金色花雨 + 铃音 */
		private void goldenBurst(int t) {
			if (t == 0) {
				sound(SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.8f, 1.2f);
				sound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.7f, 1.6f);
				for (int i = 0; i < 16; i++) {
					double a = Math.PI * 2 * i / 16;
					double r = Math.min(radius, 0.16);
					spawn(ParticleTypes.FIREWORK, Math.cos(a) * r, 0.1, Math.sin(a) * r,
							Math.cos(a) * 0.04, 0.06, Math.sin(a) * 0.04);
				}
			}
			// 金色光柱：小半径螺旋上升
			double a = t * 0.8;
			double y = Math.min(height, t * 0.015);
			for (int k = 0; k < 3; k++) {
				double aa = a + k * (Math.PI * 2 / 3);
				double r = Math.min(radius, 0.05);
				spawn(ParticleTypes.END_ROD, Math.cos(aa) * r, y, Math.sin(aa) * r, 0, 0.01, 0);
				spawn(ParticleTypes.CRIT, Math.cos(aa) * Math.min(radius, 0.14), y * 0.7,
						Math.sin(aa) * Math.min(radius, 0.14), 0, 0.01, 0);
			}
			// 金色花雨：两次小范围抛洒
			if (t == 5 || t == 14) {
				sound(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.45f, 2.0f);
				for (int i = 0; i < 14; i++) {
					double aa = Math.PI * 2 * i / 14;
					double r = Math.min(radius, 0.08 + (i % 3) * 0.07);
					spawn(ParticleTypes.HAPPY_VILLAGER, Math.cos(aa) * r, 0.14, Math.sin(aa) * r, 0, 0.03, 0);
					spawn(ParticleTypes.FIREWORK, Math.cos(aa) * r, 0.14, Math.sin(aa) * r, 0, 0.04, 0);
				}
			}
		}

		/** 7：极光回旋——双向附魔螺旋 + 幻影火束 + 长音 */
		private void auroraSpiral(int t) {
			if (t == 0) {
				sound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.9f, 1.2f);
				sound(SoundEvents.ENDER_DRAGON_FLAP, 0.3f, 1.6f);
				for (int i = 0; i < 20; i++) {
					double a = Math.PI * 2 * i / 20;
					double r = radius * 0.33;
					spawn(ParticleTypes.END_ROD, Math.cos(a) * r, 0.06, Math.sin(a) * r,
							Math.cos(a) * 0.01, 0.04, Math.sin(a) * 0.01);
				}
			}
			// 双向螺旋：呼吸式扩缩，始终在上限内
			double phase = Math.max(0.2, Math.sin(Math.PI * t / duration));
			double r = 0.05 + radius * 0.85 * phase;
			double y = Math.min(height, 0.06 + t * 0.012);
			for (int k = 0; k < 2; k++) {
				double a = t * 0.5 + k * Math.PI;
				spawn(ParticleTypes.ENCHANT, Math.cos(a) * r, y, Math.sin(a) * r, 0, 0, 0);
				spawn(ParticleTypes.FIREWORK, Math.cos(-a) * r * 0.7, y * 0.7, Math.sin(-a) * r * 0.7, 0, 0, 0);
			}
			// 每 10 tick 一圈灵魂火束
			if (t == 10 || t == 20) {
				sound(SoundEvents.SOUL_ESCAPE.value(), 0.5f, 1.5f);
				for (int i = 0; i < 8; i++) {
					double a = Math.PI * 2 * i / 8;
					double rr = Math.min(radius, 0.22);
					spawn(ParticleTypes.SOUL_FIRE_FLAME, Math.cos(a) * rr, 0.02, Math.sin(a) * rr, 0, 0.03, 0);
				}
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
