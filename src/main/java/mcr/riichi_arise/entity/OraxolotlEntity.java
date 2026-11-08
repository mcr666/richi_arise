package mcr.riichi_arise.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 桔螈（Oraxolotl）：可驯养外观的被动生物。
 * <p>
 * 动画全部取自 Blockbench 导出的原版动画（见 {@code OraxolotlAnimations}）：
 * <ul>
 * <li>待机 / 行走 / 奔跑（受惊）/ 起跳 / 下落 / 游泳 / 坐下 / 睡觉 / 攻击 / 挖土；</li>
 * <li>尾巴待机、尾巴行走两条叠加动画（坐下/睡觉/攻击/刨土等互斥动作期间暂停）。</li>
 * </ul>
 * 空手右键切换坐姿；坐姿且夜间足够久会趴下睡觉（睡姿动画）；受到伤害会自动站起。
 * </p>
 */
public class OraxolotlEntity extends PathfinderMob {
	private static final EntityDataAccessor<Boolean> DATA_SITTING = SynchedEntityData.defineId(
			OraxolotlEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Boolean> DATA_SLEEPING = SynchedEntityData.defineId(
			OraxolotlEntity.class, EntityDataSerializers.BOOLEAN);

	/** 坐姿累计 tick（夜间坐久了会趴下睡觉） */
	private static final int SLEEP_AFTER_SIT_TICKS = 100;
	/** 挖土动画持续时间（tick）；原动画 0.2917s ≈ 6 tick，留一点余量 */
	private static final int DIG_TICKS = 8;

	public final AnimationState idleAnimationState = new AnimationState();
	public final AnimationState walkAnimationState = new AnimationState();
	public final AnimationState runAnimationState = new AnimationState();
	public final AnimationState jumpAnimationState = new AnimationState();
	public final AnimationState fallAnimationState = new AnimationState();
	public final AnimationState swimAnimationState = new AnimationState();
	public final AnimationState sitAnimationState = new AnimationState();
	public final AnimationState sleepAnimationState = new AnimationState();
	public final AnimationState attackAnimationState = new AnimationState();
	public final AnimationState digAnimationState = new AnimationState();
	public final AnimationState tailIdleAnimationState = new AnimationState();
	public final AnimationState tailWalkAnimationState = new AnimationState();

	/** 客户端挖土动画剩余 tick */
	private int digTicks;
	/** 服务端坐姿累计 tick */
	private int sitTicks;
	/** 客户端当前主动画状态键（渲染器据此做状态切换过渡） */
	private String animationKey = "idle";

	public OraxolotlEntity(EntityType<? extends OraxolotlEntity> type, Level level) {
		super(type, level);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 12.0D)
				.add(Attributes.MOVEMENT_SPEED, 0.3D)
				.add(Attributes.FOLLOW_RANGE, 16.0D)
				.add(Attributes.STEP_HEIGHT, 1.0D);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(1, new PanicGoal(this, 1.25D));
		this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 1.0D));
		this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
		this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_SITTING, false);
		builder.define(DATA_SLEEPING, false);
	}

	public boolean isSitting() {
		return this.entityData.get(DATA_SITTING);
	}

	public void setSitting(boolean sitting) {
		this.entityData.set(DATA_SITTING, sitting);
		if (sitting) {
			this.getNavigation().stop();
			this.sitTicks = 0;
		}
	}

	/** 睡姿（与床铺的 {@link #isSleeping()} 区分：趴卧休息姿态） */
	public boolean isSleepingPose() {
		return this.entityData.get(DATA_SLEEPING);
	}

	public void setSleepingPose(boolean sleeping) {
		this.entityData.set(DATA_SLEEPING, sleeping);
		if (sleeping)
			this.getNavigation().stop();
	}

	/** 当前主动画状态（idle/walk/run/jump/fall/swim/sit/sleep/attack/dig），供渲染过渡使用 */
	public String getAnimationKey() {
		return this.animationKey;
	}

	@Override
	public InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (hand == InteractionHand.MAIN_HAND && player.getItemInHand(hand).isEmpty()) {
			if (!this.level().isClientSide) {
				this.setSitting(!this.isSitting());
				this.playSound(SoundEvents.ARMOR_EQUIP_GENERIC.value(), 0.8F, 1.4F);
			}
			return InteractionResult.sidedSuccess(this.level().isClientSide);
		}
		return super.mobInteract(player, hand);
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.putBoolean("Sitting", this.isSitting());
		tag.putBoolean("SleepingPose", this.isSleepingPose());
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		this.setSitting(tag.getBoolean("Sitting"));
		this.setSleepingPose(tag.getBoolean("SleepingPose"));
	}

	@Override
	public void aiStep() {
		super.aiStep();
		boolean resting = this.isSitting() || this.isSleepingPose();
		if (resting) {
			this.getNavigation().stop();
			Vec3 movement = this.getDeltaMovement();
			this.setDeltaMovement(0.0D, movement.y, 0.0D);
		}
		if (this.level().isClientSide)
			this.tickAnimations();
	}

	/** 坐姿/睡姿时不被推动、不走出原地 */
	@Override
	public void travel(Vec3 travelVector) {
		super.travel(this.isSitting() || this.isSleepingPose() ? Vec3.ZERO : travelVector);
	}

	@Override
	protected void customServerAiStep() {
		super.customServerAiStep();
		// 受伤立即起身
		if (this.hurtTime > 0 && (this.isSitting() || this.isSleepingPose())) {
			this.setSitting(false);
			this.setSleepingPose(false);
		}
		if (this.isSitting()) {
			this.sitTicks++;
			if (this.sitTicks > SLEEP_AFTER_SIT_TICKS && this.level().isNight())
				this.setSleepingPose(true);
		} else {
			this.sitTicks = 0;
		}
		// 天亮自动醒来
		if (this.isSleepingPose() && !this.level().isNight())
			this.setSleepingPose(false);
	}

	/** 客户端动画状态调度：主动画按优先级严格互斥，尾巴附属层按设计叠加（互斥动作期间暂停） */
	private void tickAnimations() {
		int age = this.tickCount;
		boolean sitting = this.isSitting();
		boolean sleeping = this.isSleepingPose();
		boolean inWater = this.isInWater();
		boolean resting = sitting || sleeping;
		boolean attacking = this.swinging || this.attackAnim > 0.0F || this.hurtTime > 0;
		boolean inAir = !this.onGround() && !inWater && !resting;
		boolean moving = !resting && this.getDeltaMovement().horizontalDistanceSqr() > 1.0E-6D;
		boolean running = moving && (this.isPanicking()
				|| this.getDeltaMovement().horizontalDistance() > 0.16D);

		// 站地面朝土块时偶尔刨土（视觉表现，仅客户端；无 AI 的展示实体不自主刨土）
		if (this.digTicks <= 0 && !resting && !inWater && this.onGround() && !this.isNoAi()
				&& this.random.nextInt(220) == 0 && this.onDiggableGround())
			this.digTicks = DIG_TICKS;
		boolean digging = this.digTicks > 0;
		if (digging)
			this.digTicks--;

		// 主动画互斥优先级：睡眠 > 坐 > 游泳 > 攻击 > 刨土 > 起跳/下落 > 奔跑 > 行走 > 待机。
		// 先确定本 tick 唯一的状态键，再用它驱动所有 AnimationState：
		// 选中的 start、其余一律 stop，避免旧状态残留导致多条动画同时写同一批骨骼
		// （原实现里攻击可与行走/奔跑/腾空并存、刨土可与行走并存，正是叠加的来源）。
		String key;
		if (sleeping)
			key = "sleep";
		else if (sitting)
			key = "sit";
		else if (inWater)
			key = "swim";
		else if (attacking)
			key = "attack";
		else if (digging)
			key = "dig";
		else if (inAir)
			key = this.getDeltaMovement().y > 0.0D ? "jump" : "fall";
		else if (running)
			key = "run";
		else if (moving)
			key = "walk";
		else
			key = "idle";
		this.animationKey = key;

		this.sleepAnimationState.animateWhen("sleep".equals(key), age);
		this.sitAnimationState.animateWhen("sit".equals(key), age);
		this.swimAnimationState.animateWhen("swim".equals(key), age);
		this.attackAnimationState.animateWhen("attack".equals(key), age);
		this.digAnimationState.animateWhen("dig".equals(key), age);
		this.jumpAnimationState.animateWhen("jump".equals(key), age);
		this.fallAnimationState.animateWhen("fall".equals(key), age);
		this.runAnimationState.animateWhen("run".equals(key), age);
		this.walkAnimationState.animateWhen("walk".equals(key), age);
		this.idleAnimationState.animateWhen("idle".equals(key), age);

		// 尾巴是设计上允许叠加的附属层（只写尾骨，与主动画骨骼不重叠）；
		// 但在坐/睡/攻击/刨土这些本身互斥的动作期间不叠加，避免与动作姿态互相干扰
		boolean tailActive = !resting && !attacking && !digging;
		this.tailWalkAnimationState.animateWhen(tailActive && moving, age);
		this.tailIdleAnimationState.animateWhen(tailActive && !moving, age);
	}

	/** 脚下是否为可刨的松软方块 */
	private boolean onDiggableGround() {
		BlockPos below = this.blockPosition().below();
		BlockState state = this.level().getBlockState(below);
		return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
				|| state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY);
	}
}
