package justfatlard.minicraft.mixin;

import justfatlard.minicraft.Functions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * A crafting table's menu, and every menu like it, stays open while its block stands at its spot.
 * At a miniature working as that block, it stays open while the miniature still works as
 * something and the player is in reach.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class MenuValidityMixin {
	@Inject(method = "stillValid(Lnet/minecraft/world/inventory/ContainerLevelAccess;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/block/Block;)Z",
		at = @At("HEAD"), cancellable = true)
	private static void minicraft$atAMiniature(ContainerLevelAccess access, Player player, Block block, CallbackInfoReturnable<Boolean> cir) {
		Optional<Boolean> working = access.evaluate((level, pos) -> level.getBlockState(pos).getBlock() instanceof justfatlard.minicraft.MiniatureBlock
			? Optional.of(Functions.stillWorking(level, pos, player)) : Optional.<Boolean>empty(), Optional.<Boolean>empty());
		working.ifPresent(cir::setReturnValue);
	}
}
