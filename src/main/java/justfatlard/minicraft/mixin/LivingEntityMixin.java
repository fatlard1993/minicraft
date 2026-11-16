package justfatlard.minicraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import justfatlard.minicraft.Main;
import justfatlard.minicraft.MiniatureBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Landing hard on a miniature sounds like what it is mostly made of, and throws up its dust, for
 * everyone the server plays them to. The one landing hears their own from their client, which
 * Pandorical tells the material.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@WrapOperation(method = "playBlockFallSound", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/block/state/BlockState;getSoundType()Lnet/minecraft/world/level/block/SoundType;"))
	private SoundType minicraft$fallLikeTheBuild(BlockState state, Operation<SoundType> original) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!state.is(Main.MINIATURE) || self.level().isClientSide()) return original.call(state);
		// Where vanilla read the block from.
		BlockPos pos = BlockPos.containing(self.getX(), self.getY() - 0.2F, self.getZ());
		return original.call(minicraft$majority(self.level(), pos, state));
	}

	@ModifyArg(method = "checkFallDamage", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/core/particles/BlockParticleOption;<init>(Lnet/minecraft/core/particles/ParticleType;Lnet/minecraft/world/level/block/state/BlockState;)V"), index = 1)
	private BlockState minicraft$dustLikeTheBuild(BlockState state, @Local(argsOnly = true) BlockPos pos) {
		if (!state.is(Main.MINIATURE)) return state;
		return minicraft$majority(((LivingEntity) (Object) this).level(), pos, state);
	}

	private static BlockState minicraft$majority(Level level, BlockPos pos, BlockState otherwise) {
		return level.getBlockEntity(pos) instanceof MiniatureBlockEntity miniature && miniature.looks().isPresent() ? miniature.majority() : otherwise;
	}
}
