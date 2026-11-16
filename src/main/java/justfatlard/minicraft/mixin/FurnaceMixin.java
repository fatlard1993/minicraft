package justfatlard.minicraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import justfatlard.minicraft.MiniatureBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A furnace a miniature works as sets itself lit or out by putting a furnace in its own spot,
 * which would put a furnace where the miniature stands. Held by a miniature, it keeps the change
 * to itself instead.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class FurnaceMixin {
	@WrapOperation(method = "serverTick", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
	private static boolean minicraft$keepLitToItself(ServerLevel level, BlockPos pos, BlockState state, Operation<Boolean> original,
			ServerLevel sameLevel, BlockPos samePos, BlockState before, AbstractFurnaceBlockEntity furnace) {
		if (level.getBlockState(pos).getBlock() instanceof MiniatureBlock) {
			furnace.setBlockState(state);
			return true;
		}
		return original.call(level, pos, state);
	}
}
