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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A miniature's display figures are never sent to a client that draws the miniature as a scaled
 * structure, or it would see the build twice. Asked where vanilla asks whether a player is to be
 * sent an entity at all.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Inject(method = "broadcastToPlayer", at = @At("HEAD"), cancellable = true)
	private void minicraft$figuresForOthers(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof Display.BlockDisplay piece && piece.entityTags().contains(MiniatureBlockEntity.FIGURE_TAG)
				&& PandoricalApi.hasCapability(player, Capabilities.SCALED_STRUCTURES)) {
			cir.setReturnValue(false);
		}
	}
}
