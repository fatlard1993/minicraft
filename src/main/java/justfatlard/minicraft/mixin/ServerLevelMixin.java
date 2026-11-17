package justfatlard.minicraft.mixin;

import justfatlard.minicraft.Workshop;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The workshop held still, so nothing in it makes or moves material on its own: no item is ever
 * dropped there, by a break, a blast or a death, nor set down as another mod's pile of item displays, and nothing ticks at random, so a sapling does
 * not grow into a tree of free logs and ice does not melt mid-build.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	@Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
	private void minicraft$noDrops(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if ((entity instanceof ItemEntity || entity instanceof ExperienceOrb || entity instanceof Display.ItemDisplay || entity instanceof Interaction)
				&& Workshop.isWorkshop((ServerLevel) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	@ModifyVariable(method = "tickChunk", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int minicraft$still(int randomTickSpeed) {
		return Workshop.isWorkshop((ServerLevel) (Object) this) ? 0 : randomTickSpeed;
	}
}
