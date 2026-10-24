package mcr.riichi_arise;

import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.api.distmarker.Dist;

import mcr.riichi_arise.client.particle.AstralBurstParticle;
import mcr.riichi_arise.client.particle.FengPanEdgeParticle;
import mcr.riichi_arise.client.particle.RiichiBurstParticle;

/**
 * 麻雀内容粒子提供者注册（客户端）。
 */
@EventBusSubscriber(Dist.CLIENT)
public class MahjongParticles {
	@SubscribeEvent
	public static void registerParticles(RegisterParticleProvidersEvent event) {
		event.registerSpriteSet(MahjongParticleTypes.ASTRAL_BURST.get(), AstralBurstParticle::provider);
		event.registerSpriteSet(MahjongParticleTypes.FENG_PAN_EDGE.get(), FengPanEdgeParticle::provider);
		event.registerSpriteSet(MahjongParticleTypes.RIICHI_BURST.get(), RiichiBurstParticle::provider);
	}
}
