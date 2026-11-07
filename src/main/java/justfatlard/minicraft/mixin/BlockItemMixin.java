package justfatlard.minicraft.mixin;

import justfatlard.minicraft.Session;
import justfatlard.minicraft.Visits;
import justfatlard.minicraft.Workshop;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Placing in the workshop: only inside the builder's own box, only with material left, and every
 * block placed taken off the budget. Caught here, where every way of placing a block item ends.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
	@Inject(method = "place", at = @At("HEAD"), cancellable = true)
	private void minicraft$gate(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
		if (!(context.getLevel() instanceof ServerLevel level) || !Workshop.isWorkshop(level)) return;
		if (!(context.getPlayer() instanceof ServerPlayer player) || !Visits.mayPlace(player, context.getClickedPos(), (Item) (Object) this)) {
			cir.setReturnValue(InteractionResult.FAIL);
		}
	}

	@Inject(method = "place", at = @At("RETURN"))
	private void minicraft$spend(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
		if (!(context.getLevel() instanceof ServerLevel level) || !Workshop.isWorkshop(level)) return;
		if (context.getPlayer() instanceof ServerPlayer player && Session.of(player) != null && cir.getReturnValue().consumesAction()) {
			Visits.placed(player, (Item) (Object) this);
		}
	}
}
