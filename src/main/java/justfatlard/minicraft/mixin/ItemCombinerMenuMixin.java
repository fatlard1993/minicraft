package justfatlard.minicraft.mixin;

import justfatlard.minicraft.Functions;
import justfatlard.minicraft.MiniatureBlock;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ItemCombinerMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** An anvil's or smithing table's menu, at a miniature working as one: as {@link MenuValidityMixin}. */
@Mixin(ItemCombinerMenu.class)
public abstract class ItemCombinerMenuMixin {
	@Shadow
	@Final
	protected ContainerLevelAccess access;

	@Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
	private void minicraft$atAMiniature(Player player, CallbackInfoReturnable<Boolean> cir) {
		Optional<Boolean> working = access.evaluate((level, pos) -> level.getBlockState(pos).getBlock() instanceof MiniatureBlock
			? Optional.of(Functions.stillWorking(level, pos, player)) : Optional.<Boolean>empty(), Optional.<Boolean>empty());
		working.ifPresent(cir::setReturnValue);
	}
}
