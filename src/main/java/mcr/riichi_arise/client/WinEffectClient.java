package mcr.riichi_arise.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import mcr.riichi_arise.network.WinEffectPayload;

/**
 * 和牌特效客户端播放器：只使用原版粒子 + 原版音效，在和牌那张牌旁边的小范围内绘制华丽动画。
 * <p>风格由和牌者自己的"个人设定"决定（随 {@link WinEffectPayload} 下发，全桌可见）：
 * 0 = 粒子环（旧表现），1 = 星陨华彩，2 = 金阁爆华，3 = 极光回旋。</p>
 * <p>每个特效是一次性时间线，1.5 秒（30 tick）内播完；粒子水平范围限制在牌旁 {@link #RADIUS} 格内。</p>
 */
public final class WinEffectClient {
	/** 特效水平半径（格）：和牌那张牌旁边的 0.3 格范围，粒子严格不超出 */
	private static final double RADIUS = 0.3;
	/** 特效竖直高度上限（格） */
	private static final double HEIGHT = 0.45;
	/** 动画总时长（tick）：30 tick = 1.5 秒 */
	private static final int DURATION = 30;

	/** 当前播放中的特效（null = 无） */
	private static Effect active;

	private WinEffectClient() {
	}

	/** 收到服务端通知：使用和牌者自己的特效风格（他人也按其设置看到） */
	public static void play(WinEffectPayload.Notice notice) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null)
			return;
		int style = Math.max(0, Math.min(3, notice.style()));
		Vec3 center = new Vec3(notice.x(), notice.y() + 0.1, notice.z());
		active = new Effect(level, center, style);
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
		private final Vec3 c;
		private final int style;
		private int age;

		private Effect(ClientLevel level, Vec3 center, int style) {
			this.level = level;
			this.c = center;
			this.style = style;
		}

		private void step(int t) {
			switch (style) {
				case 0 -> ring(t);
				case 2 -> goldenBurst(t);
				case 3 -> auroraSpiral(t);
				default -> starfallBloom(t);
			}
		}

		/** 在牌旁播放一个音效（音量/音高按风格微调，实际位置即和牌那张牌） */
		private void sound(SoundEvent event, float volume, float pitch) {
			level.playLocalSound(c.x, c.y, c.z, event, SoundSource.PLAYERS, volume, pitch, false);
		}

		/** 0：旧表现——和牌位置一个小粒子环（保留为可选） */
		private void ring(int t) {
			if (t != 0)
				return;
			sound(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.7f, 1.4f);
			for (int i = 0; i < 12; i++) {
				double a = Math.PI * 2 * i / 12;
				spawn(ParticleTypes.END_ROD, Math.cos(a) * 0.18, 0.05, Math.sin(a) * 0.18, 0, 0, 0);
			}
		}

		/** 1：星陨华彩——闪光开爆 + 三圈小焰火花冠 + 反向波纹 + 收束余烬 */
		private void starfallBloom(int t) {
			if (t == 0) {
				sound(SoundEvents.FIREWORK_ROCKET_BLAST, 0.9f, 1.3f);
				sound(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.5f, 1.8f);
				spawn(ParticleTypes.FLASH, 0, 0.12, 0, 0, 0, 0);
				// 三圈不同相位的小花冠（半径都在 0.3 以内）
				for (int ringIdx = 0; ringIdx < 3; ringIdx++) {
					double r = 0.1 + ringIdx * 0.08;
					int n = 8 + ringIdx * 4;
					for (int i = 0; i < n; i++) {
						double a = Math.PI * 2 * i / n + ringIdx * 0.4;
						spawn(i % 2 == 0 ? ParticleTypes.FIREWORK : ParticleTypes.END_ROD,
								Math.cos(a) * r, 0.1 + ringIdx * 0.06, Math.sin(a) * r,
								Math.cos(a) * 0.02, 0.04, Math.sin(a) * 0.02);
					}
				}
			}
			// 反向波纹：两圈彩点贴牌面向外扩，扩散到 0.3 后停止
			if (t % 2 == 0 && t <= 10) {
				double r = 0.06 + t * 0.024;
				for (int i = 0; i < 10; i++) {
					double a = Math.PI * 2 * i / 10 + t * 0.2;
					spawn(ParticleTypes.ELECTRIC_SPARK, Math.cos(a) * r, 0.02, Math.sin(a) * r, 0, 0, 0);
					spawn(ParticleTypes.WAX_ON, Math.cos(-a) * r, 0.03, Math.sin(-a) * r, 0, 0, 0);
				}
			}
			// 收束的螺旋余烬：从外圈向牌中心聚拢并上升
			if (t > 8 && t % 2 == 0) {
				double a = t * 0.9;
				double r = Math.max(0.02, 0.3 * (1 - (t - 8) / 22.0));
				double y = Math.min(HEIGHT, 0.08 + (t - 8) * 0.012);
				spawn(ParticleTypes.END_ROD, Math.cos(a) * r, y, Math.sin(a) * r, 0, 0.01, 0);
				sound(SoundEvents.NOTE_BLOCK_HAT.value(), 0.18f, 1.8f);
			}
		}

		/** 2：金阁爆华——金光爆点 + 小型金色光柱 + 金色花雨 + 铃音 */
		private void goldenBurst(int t) {
			if (t == 0) {
				sound(SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.8f, 1.2f);
				sound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.7f, 1.6f);
				spawn(ParticleTypes.FLASH, 0, 0.15, 0, 0, 0, 0);
				for (int i = 0; i < 16; i++) {
					double a = Math.PI * 2 * i / 16;
					spawn(ParticleTypes.FIREWORK, Math.cos(a) * 0.16, 0.1, Math.sin(a) * 0.16,
							Math.cos(a) * 0.04, 0.06, Math.sin(a) * 0.04);
				}
			}
			// 金色光柱：小半径螺旋上升
			double a = t * 0.8;
			double y = Math.min(HEIGHT, t * 0.015);
			for (int k = 0; k < 3; k++) {
				double aa = a + k * (Math.PI * 2 / 3);
				spawn(ParticleTypes.END_ROD, Math.cos(aa) * 0.05, y, Math.sin(aa) * 0.05, 0, 0.01, 0);
				spawn(ParticleTypes.CRIT, Math.cos(aa) * 0.14, y * 0.7, Math.sin(aa) * 0.14, 0, 0.01, 0);
			}
			// 金色花雨：两次小范围抛洒
			if (t == 5 || t == 14) {
				sound(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.45f, 2.0f);
				for (int i = 0; i < 14; i++) {
					double aa = Math.PI * 2 * i / 14;
					double r = 0.08 + (i % 3) * 0.07;
					spawn(ParticleTypes.HAPPY_VILLAGER, Math.cos(aa) * r, 0.14, Math.sin(aa) * r, 0, 0.03, 0);
					spawn(ParticleTypes.FIREWORK, Math.cos(aa) * r, 0.14, Math.sin(aa) * r, 0, 0.04, 0);
				}
			}
		}

		/** 3：极光回旋——双向附魔螺旋 + 幻影火束 + 长音 */
		private void auroraSpiral(int t) {
			if (t == 0) {
				sound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.9f, 1.2f);
				sound(SoundEvents.ENDER_DRAGON_FLAP, 0.3f, 1.6f);
				spawn(ParticleTypes.FLASH, 0, 0.15, 0, 0, 0, 0);
				for (int i = 0; i < 20; i++) {
					double a = Math.PI * 2 * i / 20;
					spawn(ParticleTypes.END_ROD, Math.cos(a) * 0.1, 0.06, Math.sin(a) * 0.1,
							Math.cos(a) * 0.01, 0.04, Math.sin(a) * 0.01);
				}
			}
			// 双向螺旋：呼吸式扩缩，始终在 0.3 内
			double phase = Math.sin(Math.PI * t / DURATION);
			double r = 0.05 + RADIUS * 0.85 * phase;
			double y = Math.min(HEIGHT, 0.06 + t * 0.012);
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
					spawn(ParticleTypes.SOUL_FIRE_FLAME, Math.cos(a) * 0.22, 0.02, Math.sin(a) * 0.22, 0, 0.03, 0);
				}
			}
		}

		/**
		 * 生成一个粒子：坐标按和牌那张牌 + 相对偏移计算，并做水平半径 / 竖直高度夹取，
		 * 保证特效严格限制在牌旁 {@link WinEffectClient#RADIUS} 格范围内。
		 */
		private void spawn(ParticleOptions type, double dx, double dy, double dz, double vx, double vy, double vz) {
			double horiz = Math.sqrt(dx * dx + dz * dz);
			if (horiz > RADIUS) {
				double k = RADIUS / horiz;
				dx *= k;
				dz *= k;
			}
			double y = Math.max(0, Math.min(HEIGHT, dy));
			level.addParticle(type, c.x + dx, c.y + y, c.z + dz, vx, vy, vz);
		}
	}
}
