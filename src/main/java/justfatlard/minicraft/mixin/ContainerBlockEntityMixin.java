package justfatlard.minicraft.mixin;

import justfatlard.minicraft.Functions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A furnace's or brewing stand's menu stays open while that block entity is the one at its spot.
 * One held by a miniature never is, the miniature is; so it stays open while the miniature holds
 * it and the player is in reach.
 */
@Mixin(BaseContainerBlockEntity.class)
public abstract class ContainerBlockEntityMixin {
	@Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
	private void minicraft$heldByAMiniature(Player player, CallbackInfoReturnable<Boolean> cir) {
		BaseContainerBlockEntity self = (BaseContainerBlockEntity) (Object) this;
		if (Functions.hostOf(self) != null) cir.setReturnValue(player.isWithinBlockInteractionRange(self.getBlockPos(), 4.0));
	}
}
