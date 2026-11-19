package justfatlard.minicraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;

/**
 * Whether a block could stand in the box: by itself, or on, under or beside the box's floor or
 * something loaded. Seeds want farmland and kelp is water; a box has water never and farmland
 * only once it is loaded, so seeds wait for it and kelp never goes in.
 *
 * <p>Each block's own survival rule is asked, so a modded crop is judged as a vanilla one is. It
 * is asked of a stand-in world holding just the block and one neighbour; everything else that
 * world is asked goes to the workshop. A block whose rule cannot be asked that way is allowed:
 * refused only when sure.
 */
final class Footing {
	private Footing() {}

	private static final BlockPos AT = new BlockPos(0, 128, 0);

	static boolean stands(ServerLevel workshop, Block block, Collection<BlockState> around) {
		BlockState state = block.defaultBlockState();
		if (underwater(state)) return false;
		Neighbourhood world = new Neighbourhood(workshop, state);
		LevelReader reader = (LevelReader) Proxy.newProxyInstance(LevelReader.class.getClassLoader(), new Class<?>[] {LevelReader.class}, world);
		try {
			if (state.canSurvive(reader, AT)) return true;
			for (BlockState support : around) {
				if (support.is(block)) continue;
				for (Direction side : Direction.values()) {
					world.beside(side, support);
					if (state.canSurvive(reader, AT)) return true;
				}
			}
			return false;
		} catch (RuntimeException unaskable) {
			return true;
		}
	}

	/** Water itself, as kelp and seagrass are, rather than something water can fill: the box never has water. */
	private static boolean underwater(BlockState state) {
		return !state.getFluidState().isEmpty() && !state.hasProperty(BlockStateProperties.WATERLOGGED);
	}

	/** The stand-in world: the block at {@link #AT}, at most one neighbour, air elsewhere. */
	private static final class Neighbourhood implements InvocationHandler {
		private final ServerLevel workshop;
		private final BlockState block;
		private Direction side;
		private BlockState support;

		Neighbourhood(ServerLevel workshop, BlockState block) {
			this.workshop = workshop;
			this.block = block;
		}

		void beside(Direction side, BlockState support) {
			this.side = side;
			this.support = support;
		}

		private BlockState at(BlockPos pos) {
			if (pos.equals(AT)) return block;
			if (side != null && pos.equals(AT.relative(side))) return support;
			return Blocks.AIR.defaultBlockState();
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			boolean onePos = args != null && args.length == 1 && args[0] instanceof BlockPos;
			if (onePos) {
				switch (method.getName()) {
					case "getBlockState" -> { return at((BlockPos) args[0]); }
					case "getFluidState" -> { return at((BlockPos) args[0]).getFluidState(); }
					case "getBlockEntity" -> { return null; }
					default -> {}
				}
			}
			try {
				return method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : method.invoke(workshop, args);
			} catch (InvocationTargetException thrown) {
				throw thrown.getCause();
			}
		}
	}
}
