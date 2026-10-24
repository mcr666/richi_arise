package mcr.richi;

import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;

/**
 * 麻雀内容粒子类型注册（自 Murmol 移植，独立到 richi 命名空间）。
 */
public class MahjongParticleTypes {
	public static final DeferredRegister<ParticleType<?>> REGISTRY = DeferredRegister.create(Registries.PARTICLE_TYPE,
			MahjongItems.NAMESPACE);

	public static final DeferredHolder<ParticleType<?>, SimpleParticleType> ASTRAL_BURST = REGISTRY
			.register("astral_burst", () -> new SimpleParticleType(false));
	public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FENG_PAN_EDGE = REGISTRY
			.register("fengpan_edge", () -> new SimpleParticleType(false));
	public static final DeferredHolder<ParticleType<?>, SimpleParticleType> RIICHI_BURST = REGISTRY
			.register("riichi_burst", () -> new SimpleParticleType(false));
}
