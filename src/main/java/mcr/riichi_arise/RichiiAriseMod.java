package mcr.riichi_arise;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.util.thread.SidedThreadGroups;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.IEventBus;

import net.minecraft.server.TickTask;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.FriendlyByteBuf;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;
import java.util.PriorityQueue;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;

import it.unimi.dsi.fastutil.ints.IntObjectPair;
import it.unimi.dsi.fastutil.ints.IntObjectImmutablePair;

@Mod("riichi_arise")
public class RichiiAriseMod {
	public static final Logger LOGGER = LogManager.getLogger(RichiiAriseMod.class);
	public static final String MODID = "riichi_arise";

	public RichiiAriseMod(IEventBus modEventBus, net.neoforged.fml.ModContainer modContainer) {
		// Start of user code block mod constructor
		// 麻雀客户端配置：观战摊手 / AI 形象姿态 / 观战显示牌局内容
		modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, RichiConfig.SPEC);
		if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
			modContainer.registerExtensionPoint(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
					net.neoforged.neoforge.client.gui.ConfigurationScreen::new);
		}
		// End of user code block mod constructor
		// 触发游戏规则静态注册（引用静态字段才会执行 <clinit>；class.getName() 仅加载类不初始化）
		java.util.Objects.requireNonNull(RichiGameRules.USE_AKOCHAN, "akochan gamerule");
		NeoForge.EVENT_BUS.register(this);
		modEventBus.addListener(this::registerNetworking);
		// TouhouLittleMaid 联动（软依赖）：女仆可参与麻雀牌局；未安装时全部联动逻辑静默跳过。
		// 于 CommonSetup 阶段触发（构造阶段不能触碰 TLM 实体类）
		modEventBus.addListener(this::onCommonSetup);
		// 麻雀内容注册表
		MahjongItems.REGISTRY.register(modEventBus);
		MahjongBlocks.REGISTRY.register(modEventBus);
		MahjongBlockEntities.REGISTRY.register(modEventBus);
		MahjongComponents.COMPONENTS.register(modEventBus);
		MahjongLoot.TYPES.register(modEventBus); // 随机牌面 loot function（战利品表用）
		MahjongParticleTypes.REGISTRY.register(modEventBus);
		MahjongTabs.REGISTRY.register(modEventBus);
		// 麻雀回合倒计时叠加层 payload（须在 RegisterPayloadHandlersEvent 前注册）
		mcr.riichi_arise.network.MahjongCountdownPayload.register();
		// 风盘点播唱片（右击风盘临时压制原版 BGM 播放唱片内容）
		mcr.riichi_arise.network.FengPanMusicPayload.register();
		// 麻雀大厅（等待队列/管理）与对局进行 payload
		mcr.riichi_arise.network.MahjongLobbyPayloads.register();
		mcr.riichi_arise.network.MahjongGamePayloads.register();
		// 麻雀结算界面（和牌结算/终局结算）+ 点数变化界面
		mcr.riichi_arise.network.MahjongSettlementPayload.register();
		mcr.riichi_arise.network.MahjongPointDeltaPayload.register();
		// 麻雀牌局渲染状态包（服务端过滤后整桌快照，客户端自建显示实体）
		mcr.riichi_arise.network.MahjongTablePayload.register();
		mcr.riichi_arise.network.PaipuPayload.register();
		// Start of user code block mod init
		// End of user code block mod init
	}

	// Start of user code block mod methods
	// End of user code block mod methods
	private void onCommonSetup(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) {
		event.enqueueWork(mcr.riichi_arise.compat.TlmCompatBridge::init);
	}
	private static boolean networkingRegistered = false;
	private static final Map<CustomPacketPayload.Type<?>, NetworkMessage<?>> MESSAGES = new HashMap<>();

	private record NetworkMessage<T extends CustomPacketPayload>(StreamCodec<? extends FriendlyByteBuf, T> reader, IPayloadHandler<T> handler) {
	}

	public static <T extends CustomPacketPayload> void addNetworkMessage(CustomPacketPayload.Type<T> id, StreamCodec<? extends FriendlyByteBuf, T> reader, IPayloadHandler<T> handler) {
		if (networkingRegistered)
			throw new IllegalStateException("Cannot register new network messages after networking has been registered");
		MESSAGES.put(id, new NetworkMessage<>(reader, handler));
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void registerNetworking(final RegisterPayloadHandlersEvent event) {
		final PayloadRegistrar registrar = event.registrar(MODID);
		MESSAGES.forEach((id, networkMessage) -> registrar.playBidirectional(id, ((NetworkMessage) networkMessage).reader(), ((NetworkMessage) networkMessage).handler()));
		networkingRegistered = true;
	}

	private static final Queue<IntObjectPair<Runnable>> workToBeScheduled = new ConcurrentLinkedQueue<>();
	private static final PriorityQueue<TickTask> workQueue = new PriorityQueue<>(Comparator.comparingInt(TickTask::getTick));

	public static void queueServerWork(int delay, Runnable action) {
		if (Thread.currentThread().getThreadGroup() == SidedThreadGroups.SERVER)
			workToBeScheduled.add(new IntObjectImmutablePair<>(delay, action));
	}

	@SubscribeEvent
	public void tick(ServerTickEvent.Post event) {
		int currentTick = event.getServer().getTickCount();
		IntObjectPair<Runnable> work;
		while ((work = workToBeScheduled.poll()) != null) {
			workQueue.add(new TickTask(currentTick + work.leftInt(), work.right()));
		}
		while (!workQueue.isEmpty() && currentTick >= workQueue.peek().getTick()) {
			workQueue.poll().run();
		}
	}
}