package mcr.riichi_arise.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.SlabType;
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
 * <p>搭在下半砖上时（{@link #ON_SLAB}）薄板、选中框与整桌几何一起下移半格贴合砖面；
 * 注意此时几何位于本方块格以下，MC 的选中只遍历射线经过的方块格，远距离瞄准砖面边缘时可能先命中半砖。
 */
public class FengPanSmallBlock extends FengPanBlock {
	/** 是否搭在下半砖上：为 true 时模型薄板、选中框与整桌几何一起下移半格，坐落在下半砖顶面而不是悬空 */
	public static final BooleanProperty ON_SLAB = BooleanProperty.create("on_slab");

	public FengPanSmallBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(ON_SLAB, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(ON_SLAB);
	}

	/** 该状态是否搭在下半砖上（非小型风盘恒为 false） */
	public static boolean isOnSlab(BlockState state) {
		return state.getBlock() instanceof FengPanSmallBlock && state.getValue(ON_SLAB);
	}

	/** 整桌几何下移量（格）：搭在下半砖上时为半格（8/16），与模型/选中框一致 */
	public static double slabDrop(BlockState state) {
		return isOnSlab(state) ? 0.5 : 0;
	}

	/** 下方方块是否为下半砖（上半砖/双砖顶面与整方块齐平，无需下移） */
	private static boolean isBottomSlab(BlockState below) {
		return below.getBlock() instanceof SlabBlock && below.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
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
		// 落在下半砖上：整桌下移半格贴合砖面（上半砖/整方块顶面即格子底面，无需下移）
		boolean onSlab = isBottomSlab(context.getLevel().getBlockState(target.below()));
		return this.defaultBlockState().setValue(FACING, forward).setValue(ON_SLAB, onSlab);
	}

	/** 单方块：无需补齐其余部分 */
	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state,
			net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
		// 无操作（单方块，不铺 2×2）
	}

	/** 准心触及范围：单方块整面（高 1 单位＝模型薄板厚度，便于选中）；搭在下半砖上时随模型一起下移半格 */
	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
			CollisionContext context) {
		double drop = slabDrop(state);
		return Shapes.box(0, -drop, 0, 1, 1.0 / 16.0 - drop, 1);
	}

	/** 无 2×2 连锁拆除；只跟踪下方支撑是否为下半砖（决定整桌是否下移半格） */
	@Override
	protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
			LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
		if (direction == Direction.DOWN) {
			boolean onSlab = isBottomSlab(neighborState);
			if (state.getValue(ON_SLAB) != onSlab)
				return state.setValue(ON_SLAB, onSlab);
		}
		return state;
	}

	/** 单方块无需 2×2 自愈/连锁拆除 */
	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		// 无操作
	}
}
