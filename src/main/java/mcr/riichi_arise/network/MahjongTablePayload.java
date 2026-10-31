package mcr.riichi_arise.network;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import mcr.riichi_arise.RichiiAriseMod;

/**
 * 牌局渲染状态包（S→C）：服务端按观看者过滤后的整桌快照，客户端据此全量重建 client-side
 * 显示实体——服务端不再下发带他人牌面的实体 NBT（防抓包看牌）。
 * hands[seat] 为空且 hidden[seat] &gt; 0 = 该家手牌对本次观看者隐藏，客户端渲染为 hidden[seat] 张牌背。
 * phase = WAITING（无对局）时客户端清除该桌显示实体。所有字符串字段判空（writeUtf(null) 会断连）。
 * tedashi[seat] = 1 表示该家最近一次打牌为手切（0 = 摸切/无）：隐藏手牌的牌背 key 据此错位，
 * 使手切动画不会永远表现为刚摸的那张消失（摸切观感）。
 *
 * <p>带宽优化：{@link #avatarIds} 为 AI 形象（form/name/skin）的按内容索引，整局固定不重发；
 * 内容首次出现（或本观看者的缓存不含该索引）时才在 avatarForms/Names/Skins 里附真值。
 * 另外 {@link #dedupe} 在发送前整包比对，与上次完全相同则不发（一次动作通常只改其中一小段）。</p>
 */
public class MahjongTablePayload {
	/** 形象缓存上限（多桌多座位共用）：只淘汰最久未用，不会淘汰刚发出的条目 */
	private static final int PIN_CACHE_CAP = 96;
	/** 索引 0 保留给"空座"，内容为三个空串 */
	private static final int PIN_EMPTY = 0;

	public record ViewMessage(long originPacked, int viewerSeat, boolean openHand, int dealAnim,
			String[] hands, int[] hidden, int[] tedashi, int[] glassMask, String[] rivers, String[] melds,
			int[] kita,
			int[] riichiRiverIdx, int[] riichiSticks, int[] handsExposed, int[] points,
			String[] names, int[] avatarIds, String[] avatarForms, String[] avatarNames, String[] avatarSkins,
			String doraWall, int revealedIndicators, int wallCount,
			int turnSeat, int round, int roundWind, int honba, int carriedSticks,
			int selectedSeat, int selectedIndex, int drawnSeat, int phase, int playerCount,
			String bannerText, int[] discardPos, int roomType, int[] winEffects) implements CustomPacketPayload {

		public static final Type<ViewMessage> TYPE = new Type<>(
				ResourceLocation.fromNamespaceAndPath(RichiiAriseMod.MODID, "richi_table"));

		public static final StreamCodec<RegistryFriendlyByteBuf, ViewMessage> STREAM_CODEC = StreamCodec.of(
				MahjongTablePayload::write, MahjongTablePayload::read);

		@Override
		public Type<ViewMessage> type() {
			return TYPE;
		}

		public static void handleData(final ViewMessage message, final IPayloadContext context) {
			if (context.flow() == PacketFlow.CLIENTBOUND) {
				context.enqueueWork(() -> mcr.riichi_arise.client.MahjongTableClient.apply(message));
			}
		}

		/** 除 selectedSeat/Index 外全部渲染相关字段一致（判定"只是选中变化"用） */
		public boolean sameExceptSelection(ViewMessage b) {
			ViewMessage a = this;
			if (a.dealAnim() != b.dealAnim() || a.revealedIndicators() != b.revealedIndicators()
					|| a.wallCount() != b.wallCount() || a.turnSeat() != b.turnSeat()
					|| a.round() != b.round() || a.roundWind() != b.roundWind() || a.honba() != b.honba()
					|| a.carriedSticks() != b.carriedSticks() || a.drawnSeat() != b.drawnSeat()
					|| a.phase() != b.phase() || a.playerCount() != b.playerCount() || a.roomType() != b.roomType())
				return false;
			return Arrays.equals(a.hands(), b.hands()) && Arrays.equals(a.hidden(), b.hidden())
					&& Arrays.equals(a.tedashi(), b.tedashi()) && Arrays.equals(a.glassMask(), b.glassMask())
					&& Arrays.equals(a.rivers(), b.rivers())
					&& Arrays.equals(a.melds(), b.melds()) && Arrays.equals(a.kita(), b.kita())
					&& Arrays.equals(a.riichiRiverIdx(), b.riichiRiverIdx())
					&& Arrays.equals(a.riichiSticks(), b.riichiSticks())
					&& Arrays.equals(a.handsExposed(), b.handsExposed()) && Arrays.equals(a.points(), b.points())
					&& Arrays.equals(a.discardPos(), b.discardPos()) && Arrays.equals(a.names(), b.names())
					&& Arrays.equals(a.winEffects(), b.winEffects())
					&& Arrays.equals(a.avatarIds(), b.avatarIds())
					&& Arrays.equals(a.avatarForms(), b.avatarForms())
					&& Arrays.equals(a.avatarNames(), b.avatarNames())
					&& Arrays.equals(a.avatarSkins(), b.avatarSkins())
					&& orEmpty(a.doraWall()).equals(orEmpty(b.doraWall()))
					&& orEmpty(a.bannerText()).equals(orEmpty(b.bannerText()));
		}
	}

	// ==================================================================
	// AI 形象去重（服务端）
	// ==================================================================

	/** 内容 → 索引（编号单调递增，不复用） */
	private static final Map<String, Integer> PIN_BY_TEXT = new ConcurrentHashMap<>();
	/** 索引 → 内容 */
	private static final Map<Integer, String> PIN_BY_ID = new ConcurrentHashMap<>();
	private static final java.util.concurrent.atomic.AtomicInteger PIN_NEXT = new java.util.concurrent.atomic.AtomicInteger(1);
	/** 每个观看者已知的索引（决定是否需要附真值） */
	private static final Map<UUID, java.util.Set<Integer>> KNOWN = new ConcurrentHashMap<>();

	public static ViewMessage dedupe(ViewMessage msg, UUID viewer) {
		if (viewer == null)
			return msg;
		java.util.Set<Integer> known = KNOWN.computeIfAbsent(viewer, k -> ConcurrentHashMap.newKeySet());
		int[] ids = new int[4];
		String[] forms = new String[4];
		String[] names = new String[4];
		String[] skins = new String[4];
		for (int s = 0; s < 4; s++) {
			String form = orEmpty(msg.avatarForms()[s]);
			String name = orEmpty(msg.avatarNames()[s]);
			String skin = orEmpty(msg.avatarSkins()[s]);
			int id;
			if (form.isEmpty() && name.isEmpty() && skin.isEmpty()) {
				id = PIN_EMPTY;
			} else {
				String text = pinText(form, name, skin);
				Integer existing = PIN_BY_TEXT.get(text);
				// 新内容：分配新的全局唯一索引（不以"已知表清空"为由重用旧索引）
				id = existing != null ? existing : PIN_NEXT.getAndIncrement();
				if (existing == null) {
					PIN_BY_TEXT.put(text, id);
					PIN_BY_ID.put(id, text);
					if (PIN_BY_TEXT.size() > PIN_CACHE_CAP)
						evict();
				}
			}
			ids[s] = id;
			if (id != PIN_EMPTY && known.add(id)) {
				forms[s] = form; // 该观看者首次见到的索引：附真值
				names[s] = name;
				skins[s] = skin;
			}
		}
		return new ViewMessage(msg.originPacked(), msg.viewerSeat(), msg.openHand(), msg.dealAnim(),
				msg.hands(), msg.hidden(), msg.tedashi(), msg.glassMask(), msg.rivers(), msg.melds(), msg.kita(),
				msg.riichiRiverIdx(), msg.riichiSticks(), msg.handsExposed(), msg.points(), msg.names(),
				ids, forms, names, skins, msg.doraWall(), msg.revealedIndicators(), msg.wallCount(),
				msg.turnSeat(), msg.round(), msg.roundWind(), msg.honba(), msg.carriedSticks(),
				msg.selectedSeat(), msg.selectedIndex(), msg.drawnSeat(), msg.phase(), msg.playerCount(),
				msg.bannerText(), msg.discardPos(), msg.roomType(), msg.winEffects());
	}

	private static void evict() {
		// 超上限时挑出"任何观看者都不再需要"的条目丢弃（只丢已不再有效的索引，避免误伤在场形象）
		var it = PIN_BY_TEXT.entrySet().iterator();
		int drop = PIN_BY_TEXT.size() / 2;
		while (it.hasNext() && drop-- > 0) {
			var e = it.next();
			int id = e.getValue();
			boolean inUse = false;
			for (var k : KNOWN.values())
				if (k.contains(id)) {
					inUse = true;
					break;
				}
			if (inUse)
				continue;
			it.remove();
			PIN_BY_ID.remove(id);
		}
	}

	private static String pinText(String form, String name, String skin) {
		return form + '\u0001' + name + '\u0001' + skin;
	}

	/** 客户端通知：形象缓存已清空（服务端应忘记"已发送过的索引"，下一包重发真值） */
	public record ResetAvatarPins() implements CustomPacketPayload {
		public static final Type<ResetAvatarPins> TYPE = new Type<>(
				ResourceLocation.fromNamespaceAndPath(RichiiAriseMod.MODID, "richi_reset_avatar_pins"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ResetAvatarPins> STREAM_CODEC = StreamCodec
				.unit(new ResetAvatarPins());

		@Override
		public Type<ResetAvatarPins> type() {
			return TYPE;
		}

		public static void handleData(final ResetAvatarPins message, final IPayloadContext context) {
			if (context.flow() == PacketFlow.SERVERBOUND) {
				// 服务端：忘记该观看者的已知索引，下一包重新携带真值
				context.enqueueWork(() -> KNOWN.remove(context.player().getUUID()));
			}
		}
	}

	/** 断开连接（清空该观看者的已知索引） */
	public static void forget(UUID viewer) {
		if (viewer != null)
			KNOWN.remove(viewer);
	}

	/** 序列化整包（用于与上次已发内容比较；不含包头） */
	private static byte[] encode(ViewMessage msg) {
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
		try {
			write(buf, msg);
			byte[] out = new byte[buf.readableBytes()];
			buf.readBytes(out);
			return out;
		} finally {
			buf.release();
		}
	}

	/** 与上次发给该观看者的内容是否完全相同（true = 可整包省掉）；同时记下本次内容 */
	public static boolean isDuplicate(ViewMessage msg, UUID viewer, Map<UUID, byte[]> last) {
		byte[] body = encode(msg);
		byte[] prev = last.put(viewer, body);
		return prev != null && Arrays.equals(prev, body);
	}

	private static String orEmpty(String s) {
		return s == null ? "" : s;
	}

	private static void write(RegistryFriendlyByteBuf buf, ViewMessage msg) {
		buf.writeLong(msg.originPacked());
		buf.writeVarInt(msg.viewerSeat());
		buf.writeBoolean(msg.openHand());
		buf.writeVarInt(msg.dealAnim());
		for (int i = 0; i < 4; i++) {
			buf.writeUtf(orEmpty(msg.hands()[i]), 256);
			buf.writeVarInt(msg.hidden()[i]);
			buf.writeVarInt(msg.tedashi()[i]);
			buf.writeVarInt(msg.glassMask()[i]); // 隐藏手牌的可见透明位掩码（按观者洗牌）
			buf.writeUtf(orEmpty(msg.rivers()[i]), 256);
			buf.writeUtf(orEmpty(msg.melds()[i]), 256);
			buf.writeVarInt(msg.kita()[i]); // 拔北数（三麻）：不算副露，独立字段
			buf.writeVarInt(msg.riichiRiverIdx()[i]);
			buf.writeVarInt(msg.riichiSticks()[i]);
			buf.writeVarInt(msg.handsExposed()[i]);
			buf.writeVarInt(msg.points()[i]);
			buf.writeUtf(orEmpty(msg.names()[i]), 64);
			buf.writeVarInt(msg.avatarIds()[i]); // 形象索引（0 = 空座）
			// 仅首次出现该索引时携带真值，其余为空串
			buf.writeUtf(orEmpty(msg.avatarForms()[i]), 64);
			buf.writeUtf(orEmpty(msg.avatarNames()[i]), 64);
			buf.writeUtf(orEmpty(msg.avatarSkins()[i]), 64);
		}
		buf.writeUtf(orEmpty(msg.doraWall()), 256);
		buf.writeVarInt(msg.revealedIndicators());
		buf.writeVarInt(msg.wallCount());
		buf.writeVarInt(msg.turnSeat());
		buf.writeVarInt(msg.round());
		buf.writeVarInt(msg.roundWind());
		buf.writeVarInt(msg.honba());
		buf.writeVarInt(msg.carriedSticks());
		buf.writeVarInt(msg.selectedSeat());
		buf.writeVarInt(msg.selectedIndex());
		buf.writeVarInt(msg.drawnSeat());
		buf.writeVarInt(msg.phase());
		buf.writeVarInt(msg.playerCount()); // 对局人数（4=四麻 / 3=三麻）：客户端据此跳过空座位渲染
		buf.writeUtf(orEmpty(msg.bannerText()), 1024);
		for (int i = 0; i < 4; i++)
			buf.writeVarInt(msg.discardPos()[i]); // 各家最近打出的牌在原手牌中的位置（飞行起点）
		buf.writeVarInt(msg.roomType()); // 对局类型（0=铁之间 / 1=玻璃之间）：客户端决定牌背是否用玻璃牌背
		for (int i = 0; i < 4; i++)
			buf.writeVarInt(msg.winEffects()[i]); // 各家和牌特效风格：全桌按和牌者自己的设置渲染
	}

	private static ViewMessage read(RegistryFriendlyByteBuf buf) {
		long origin = buf.readLong();
		int viewerSeat = buf.readVarInt();
		boolean openHand = buf.readBoolean();
		int dealAnim = buf.readVarInt();
		String[] hands = new String[4];
		int[] hidden = new int[4];
		int[] tedashi = new int[4];
		int[] glassMask = new int[4];
		String[] rivers = new String[4];
		String[] melds = new String[4];
		int[] kita = new int[4];
		int[] riichiRiverIdx = new int[4];
		int[] riichiSticks = new int[4];
		int[] handsExposed = new int[4];
		int[] points = new int[4];
		String[] names = new String[4];
		int[] avatarIds = new int[4];
		String[] avatarForms = new String[4];
		String[] avatarNames = new String[4];
		String[] avatarSkins = new String[4];
		for (int i = 0; i < 4; i++) {
			hands[i] = buf.readUtf(256);
			hidden[i] = buf.readVarInt();
			tedashi[i] = buf.readVarInt();
			glassMask[i] = buf.readVarInt();
			rivers[i] = buf.readUtf(256);
			melds[i] = buf.readUtf(256);
			kita[i] = buf.readVarInt();
			riichiRiverIdx[i] = buf.readVarInt();
			riichiSticks[i] = buf.readVarInt();
			handsExposed[i] = buf.readVarInt();
			points[i] = buf.readVarInt();
			names[i] = buf.readUtf(64);
			avatarIds[i] = buf.readVarInt();
			String f = buf.readUtf(64);
			String n = buf.readUtf(64);
			String s = buf.readUtf(64);
			if (AvatarPinCache.remember(avatarIds[i], f, n, s)) {
				// 客户端缓存溢出已整体清空：通知服务端重发全部真值
				AvatarPinCache.clear();
				net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ResetAvatarPins());
			}
			avatarForms[i] = f.isEmpty() ? AvatarPinCache.form(avatarIds[i]) : f;
			avatarNames[i] = n.isEmpty() ? AvatarPinCache.name(avatarIds[i]) : n;
			avatarSkins[i] = s.isEmpty() ? AvatarPinCache.skin(avatarIds[i]) : s;
		}
		String doraWall = buf.readUtf(256);
		int revealedIndicators = buf.readVarInt();
		int wallCount = buf.readVarInt();
		int turnSeat = buf.readVarInt();
		int round = buf.readVarInt();
		int roundWind = buf.readVarInt();
		int honba = buf.readVarInt();
		int carriedSticks = buf.readVarInt();
		int selectedSeat = buf.readVarInt();
		int selectedIndex = buf.readVarInt();
		int drawnSeat = buf.readVarInt();
		int phase = buf.readVarInt();
		int playerCount = buf.readVarInt();
		String bannerText = buf.readUtf(1024);
		int[] discardPos = new int[4];
		for (int i = 0; i < 4; i++)
			discardPos[i] = buf.readVarInt();
		int roomType = buf.readVarInt();
		int[] winEffects = new int[4];
		for (int i = 0; i < 4; i++)
			winEffects[i] = buf.readVarInt();
		return new ViewMessage(origin, viewerSeat, openHand, dealAnim, hands, hidden, tedashi, glassMask, rivers,
				melds, kita,
				riichiRiverIdx, riichiSticks, handsExposed, points, names,
				avatarIds, avatarForms, avatarNames, avatarSkins, doraWall,
				revealedIndicators, wallCount, turnSeat, round, roundWind, honba, carriedSticks,
				selectedSeat, selectedIndex, drawnSeat, phase, playerCount, bannerText, discardPos, roomType,
				winEffects);
	}

	public static void register() {
		RichiiAriseMod.addNetworkMessage(ViewMessage.TYPE, ViewMessage.STREAM_CODEC, ViewMessage::handleData);
		RichiiAriseMod.addNetworkMessage(ResetAvatarPins.TYPE, ResetAvatarPins.STREAM_CODEC,
				ResetAvatarPins::handleData);
	}
}
