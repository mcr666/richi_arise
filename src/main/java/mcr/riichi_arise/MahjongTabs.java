package mcr.riichi_arise;

import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;

/**
 * 麻雀内容创造模式标签页。
 */
public class MahjongTabs {
	public static final DeferredRegister<CreativeModeTab> REGISTRY = DeferredRegister
			.create(Registries.CREATIVE_MODE_TAB, MahjongItems.NAMESPACE);
	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAHJONG = REGISTRY.register("mahjong",
			() -> CreativeModeTab.builder().title(Component.translatable("item_group.riichi_arise.mahjong"))
					.icon(() -> new ItemStack(MahjongItems.FENG_PAN.get()))
					.displayItems((parameters, tabData) -> {
						tabData.accept(MahjongItems.FENG_PAN.get()); // 风盘（麻雀牌桌）
						tabData.accept(MahjongItems.FENG_PAN_SMALL.get()); // 小型风盘（紧凑 3×3 牌桌）
						tabData.accept(MahjongTileItem.create(-1)); // 未知牌（背面）
						tabData.accept(MahjongItems.ITEMS.get(0).get()); // 点棒 1000（立直宣言用）
					}).build());
}
