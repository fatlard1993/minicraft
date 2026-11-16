package justfatlard.minicraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import justfatlard.minicraft.MiniatureBlockEntity;
import justfatlard.minicraft.Session;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
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
 * A miniature placed sounds like what it is mostly made of, to everyone, the placer too: their
 * client takes it for a plain block and plays nothing of its own for a block it does not predict.
 *
 * <p>Placing in the workshop: only inside the builder's own box, only with material left, and every
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

	@WrapOperation(method = "place", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"))
	private void minicraft$placeLikeTheBuild(Level level, Entity player, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch, Operation<Void> original) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof MiniatureBlockEntity miniature && miniature.looks().isPresent()) {
			SoundType type = miniature.majority().getSoundType();
			original.call(level, null, pos, type.getPlaceSound(), source, (type.getVolume() + 1.0F) / 2.0F, type.getPitch() * 0.8F);
			return;
		}
		original.call(level, player, pos, sound, source, volume, pitch);
	}

	@Inject(method = "place", at = @At("RETURN"))
	private void minicraft$spend(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
		if (!(context.getLevel() instanceof ServerLevel level) || !Workshop.isWorkshop(level)) return;
		if (context.getPlayer() instanceof ServerPlayer player && Session.of(player) != null && cir.getReturnValue().consumesAction()) {
			Visits.placed(player, (Item) (Object) this);
		}
	}
}
