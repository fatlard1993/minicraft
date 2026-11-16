package justfatlard.minicraft.mixin;

import justfatlard.minicraft.Functions;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A plain slot in any menu over something a miniature keeps (its chest, its furnace's input)
 * will not take a shulker box or another miniature that keeps things. Vanilla's own slots take
 * anything; a slot that already chooses (a furnace's fuel, a brewing stand's bottles) chooses as
 * it always did.
 */
@Mixin(Slot.class)
public abstract class SlotMixin {
	@Shadow
	@Final
	public Container container;

	@Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
	private void minicraft$noBoxInABox(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (!Functions.fits(stack) && Functions.guards(container)) cir.setReturnValue(false);
	}
}
