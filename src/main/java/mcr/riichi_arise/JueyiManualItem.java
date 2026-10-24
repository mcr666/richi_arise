package mcr.riichi_arise;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * 《雀魂绝艺总纲》：帕秋莉手册（Patchouli）联动手册。
 * <p>右键打开手册；未安装 Patchouli 时只给提示，不影响模组其余功能。
 */
public class JueyiManualItem extends Item {
	public static final String PATCHOULI_MODID = "patchouli";
	/** 手册 id：data/riichi_arise/patchouli_books/jueyi/book.json */
	public static final ResourceLocation BOOK_ID = ResourceLocation.fromNamespaceAndPath(MahjongItems.NAMESPACE, "jueyi");

	public JueyiManualItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (!ModList.get().isLoaded(PATCHOULI_MODID)) {
			if (!level.isClientSide())
				player.displayClientMessage(Component.translatable("message.riichi_arise.patchouli_missing"), true);
			return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
		}
		if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer)
			openBook(serverPlayer);
		return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
	}

	/** 单独方法持有 PatchouliAPI 引用：未安装 Patchouli 时不会走到这里，避免类解析失败 */
	private static void openBook(ServerPlayer player) {
		vazkii.patchouli.api.PatchouliAPI.get().openBookGUI(player, BOOK_ID);
	}
}
