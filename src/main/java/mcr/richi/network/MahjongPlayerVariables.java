package mcr.richi.network;

import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.attachment.AttachmentType;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;

import mcr.richi.MahjongItems;

import java.util.function.Supplier;

/**
 * 麻雀模块玩家附件（自 Murmol 移植，仅保留麻雀用到的形态标识）。
 * <p>客户端为 AI 座位假玩家（RemotePlayer）本地写入 {@link PlayerVariables#feralFormId}，
 * 供村民形象渲染（{@code mcr.richi.client.VillagerAvatarHandler}）与头顶名字隐藏判定读取。</p>
 */
public class MahjongPlayerVariables {
	public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister
			.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MahjongItems.NAMESPACE);
	public static final Supplier<AttachmentType<PlayerVariables>> PLAYER_VARIABLES = ATTACHMENT_TYPES
			.register("player_variables", () -> AttachmentType.serializable(() -> new PlayerVariables()).build());

	/** 形态标识默认值：人类 */
	public static final String HUMAN_FORM = "human";

	public static class PlayerVariables implements INBTSerializable<CompoundTag> {
		/** 形态标识（字符串）：human / villager / 各形态 id */
		public String feralFormId = HUMAN_FORM;

		@Override
		public CompoundTag serializeNBT(HolderLookup.Provider lookupProvider) {
			CompoundTag nbt = new CompoundTag();
			nbt.putString("feralFormId", feralFormId);
			return nbt;
		}

		@Override
		public void deserializeNBT(HolderLookup.Provider lookupProvider, CompoundTag nbt) {
			feralFormId = nbt.contains("feralFormId", net.minecraft.nbt.Tag.TAG_STRING)
					? nbt.getString("feralFormId") : HUMAN_FORM;
		}
	}
}
