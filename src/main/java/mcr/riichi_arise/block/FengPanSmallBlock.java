package mcr.riichi_arise.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.util.RandomSource;
import net.minecraft.server.level.ServerLevel;

import net.minecraft.network.chat.Component;

/**
 * 小型风盘（紧凑 3×3 牌桌）：单方块薄板变体，模型顶面贴风盘物品贴图、其余面透明（照妖镜式）。
 * 放置后同样能创建牌局（继承 {@link FengPanBlock#useWithoutItem} 开桌入口），但布局由
 * {@link mcr.riichi_arise.render.TableLayout} 按 {@link #isSmall(BlockState)} 切换为紧凑 3×3 几何
 * （手牌/牌河/副露向内靠、座位中心靠外 2.5 格）。
 * <p>与 2×2 大风盘的区别：单方块（无补块、无连锁拆除、无象限 UV）；TX/TZ 恒为 0（原点即自身）。
 * 正常风盘布局保持不变。
 */
public class FengPanSmallBlock extends FengPanBlock {

	public FengPanSmallBlock(Properties properties) {
		super(properties);
	}

	/** 放置检测：只能放在方块顶面；单方块，仅要求目标格可替换（不做 2×2/4×4 空区校验） */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		if (context.getClickedFace() != Direction.UP) {
			if (context.getPlayer() != null)
				context.getPlayer().displayClientMessage(
						Component.translatable("message.riichi_arise.fengpan_ground"), true);
			return null; // 只能放在方块之上
		}
		Direction forward = context.getHorizontalDirection();
		BlockPos target = context.getClickedPos(); // 落块位置（点击面顶上的格子，即风盘单块所在格）
		if (!context.getLevel().getBlockState(target).canBeReplaced()) {
			if (context.getPlayer() != null)
				context.getPlayer().displayClientMessage(
						Component.translatable("message.riichi_arise.fengpan_blocked"), true);
			return null; // 有阻挡，放置失败
		}
		return this.defaultBlockState().setValue(FACING, forward);
	}

	/** 单方块：无需补齐其余部分 */
	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state,
			net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
		// 无操作（单方块，不铺 2×2）
	}

	/** 准心触及范围：单方块整块（高 1 格，便于选中） */
	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
			CollisionContext context) {
		return Shapes.box(0, 0, 0, 1, 1, 1);
	}

	/** 无 2×2 连锁拆除：邻块变化不影响本单方块 */
	@Override
	protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
			LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
		return state;
	}

	/** 单方块无需 2×2 自愈/连锁拆除 */
	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		// 无操作
	}
}
