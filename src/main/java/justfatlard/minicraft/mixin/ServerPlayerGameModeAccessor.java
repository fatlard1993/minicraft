package justfatlard.minicraft.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which block a player is breaking, for {@link justfatlard.minicraft.Breaking}. */
@Mixin(ServerPlayerGameMode.class)
public interface ServerPlayerGameModeAccessor {
	@Accessor("isDestroyingBlock")
	boolean minicraft$destroying();

	@Accessor("destroyPos")
	BlockPos minicraft$destroyPos();

	@Accessor("hasDelayedDestroy")
	boolean minicraft$delayed();

	@Accessor("delayedDestroyPos")
	BlockPos minicraft$delayedPos();
}
