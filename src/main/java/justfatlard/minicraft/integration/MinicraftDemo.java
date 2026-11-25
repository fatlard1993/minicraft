package justfatlard.minicraft.integration;

import justfatlard.minicraft.BenchBlockEntity;
import justfatlard.minicraft.Main;
import justfatlard.pvp_dimensions.api.DemoHook;
import justfatlard.pvp_dimensions.api.DemoRun;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The PvP Dimensions demo: finished once the player holds a miniature of their own, and every
 * bench put down in it given its plot back when they go, since the demo's ground is let go
 * without anything on it being broken.
 */
public final class MinicraftDemo implements DemoHook {
	@Override
	public void tick(DemoRun run) {
		if (!run.finished() && run.player().getInventory().contains(stack -> stack.is(Main.MINIATURE_ITEM))) run.finish();
	}

	@Override
	public void left(DemoRun run) {
		BlockPos corner = run.at(0, 0, 0);
		int width = run.width();
		for (int cx = corner.getX() >> 4; cx <= (corner.getX() + width - 1) >> 4; cx++) {
			for (int cz = corner.getZ() >> 4; cz <= (corner.getZ() + width - 1) >> 4; cz++) {
				LevelChunk chunk = run.level().getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) continue;
				for (BlockEntity entity : java.util.List.copyOf(chunk.getBlockEntities().values())) {
					if (entity instanceof BenchBlockEntity bench) bench.forget();
				}
			}
		}
	}
}
