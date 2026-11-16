package justfatlard.minicraft.mixin;

import justfatlard.minicraft.MiniatureBlockEntity;
import justfatlard.pandorical.api.Capabilities;
import justfatlard.pandorical.api.PandoricalApi;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Steps on a miniature sound like what it is mostly made of, to everyone the server plays them to.
 * A player's own steps are their client's to play, and it plays them from the material Pandorical
 * tells it.
 *
 * <p>A miniature's display figures are never sent to a client that draws the miniature as a scaled
 * structure, or it would see the build twice. Asked where vanilla asks whether a player is to be
 * sent an entity at all.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Inject(method = "playStepSound", at = @At("HEAD"), cancellable = true)
	private void minicraft$stepLikeTheBuild(BlockPos pos, BlockState state, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if (!state.is(justfatlard.minicraft.Main.MINIATURE) || self.level().isClientSide()) return;
		if (!(self.level().getBlockEntity(pos) instanceof MiniatureBlockEntity miniature) || miniature.looks().isEmpty()) return;
		SoundType sound = miniature.majority().getSoundType();
		self.playSound(sound.getStepSound(), sound.getVolume() * 0.15F, sound.getPitch());
		ci.cancel();
	}

	@Inject(method = "broadcastToPlayer", at = @At("HEAD"), cancellable = true)
	private void minicraft$figuresForOthers(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof Display.BlockDisplay piece && piece.entityTags().contains(MiniatureBlockEntity.FIGURE_TAG)
				&& PandoricalApi.hasCapability(player, Capabilities.SCALED_STRUCTURES)) {
			cir.setReturnValue(false);
		}
	}
}
