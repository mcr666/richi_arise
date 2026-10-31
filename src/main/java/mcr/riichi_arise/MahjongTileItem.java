package mcr.riichi_arise;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 麻雀牌物品：单个物品，牌面由数据组件 mahjong_tile_code（即 NBT）驱动。
 * 同时为 {@link BlockItem}：右键可摆放为平放麻将牌方块（牌面代码经 applyComponents 转入方块实体）。
 * 模型切换：客户端 ItemProperties "code" + 模型 overrides（见 MahjongClient）。
 * 牌面代码：0m..9m=0..9，0p..9p=10..19，0s..9s=20..29，1z..7z=30..36；无组件 = 未知牌。
 */
public class MahjongTileItem extends BlockItem {
	/** 牌面代码 → 牌名后缀（如 1m、7z），按注册顺序排列 */
	public static final Map<Integer, String> CODE_TO_NAME = new LinkedHashMap<>();

	static {
		for (String suit : new String[] { "m", "p", "s" }) {
			CODE_TO_NAME.put(CODE_TO_NAME.size(), "0" + suit);
			for (int i = 1; i <= 9; i++)
				CODE_TO_NAME.put(CODE_TO_NAME.size(), i + suit);
		}
		for (int i = 1; i <= 7; i++)
			CODE_TO_NAME.put(CODE_TO_NAME.size(), i + "z");
	}

	public MahjongTileItem(Block block, Properties properties) {
		super(block, properties);
	}

	/** 创建指定牌面的物品堆（无牌面代码 = 未知牌）。
	 *  牌面代码带 {@link mcr.riichi_arise.game.TileFlag#GLASS} 时渲染为玻璃牌体。 */
	public static ItemStack create(int code) {
		ItemStack stack = new ItemStack(MahjongItems.MAHJONG_TILE.get());
		if (code >= 0) {
			stack.set(MahjongComponents.TILE_CODE.get(), mcr.riichi_arise.game.TileFlag.base(code));
			if (mcr.riichi_arise.game.TileFlag.isGlass(code))
				stack.set(MahjongComponents.TILE_GLASS.get(), true);
		}
		return stack;
	}

	/** 读取牌面代码，无组件时返回 -1（未知牌） */
	public static int getCode(ItemStack stack) {
		Integer code = stack.get(MahjongComponents.TILE_CODE.get());
		return code == null ? -1 : code;
	}

	/**
	 * 玻璃牌背物品堆（玻璃之间的牌背/隐藏手牌）：带牌面代码但附加 back 标记，
	 * 模型 overrides 据此选玻璃牌背模型（六面玻璃，内侧朝南渲染 #front 牌面，
	 * 因此从背面也能看到透出来的牌面）。code&nbsp;&lt;&nbsp;0 = 未知牌。
	 */
	public static ItemStack createGlassBack(int code) {
		ItemStack stack = create(code);
		stack.set(MahjongComponents.TILE_GLASS_BACK.get(), true);
		return stack;
	}

	/** 玻璃牌背 → 当前牌面纹理名（如 1m / 0m / 5z）；未知牌返回 null */
	public static String glassFaceTexture(ItemStack stack) {
		int code = getCode(stack);
		return code >= 0 && code < CODE_TO_NAME.size() ? CODE_TO_NAME.get(code) : null;
	}

	/** 按牌面显示名称（复用旧 34 张牌的翻译键） */
	@Override
	public Component getName(ItemStack stack) {
		int code = getCode(stack);
		String name = CODE_TO_NAME.get(code);
		if (name != null)
			return Component.translatable("item.riichi_arise.mahjong_" + name);
		return Component.translatable("item.riichi_arise.mahjong_tile_unknown");
	}

	/** 牌面代码列表（工具方法，供标签页/模型生成遍历） */
	public static List<Integer> validCodes() {
		return List.copyOf(CODE_TO_NAME.keySet());
	}
}
