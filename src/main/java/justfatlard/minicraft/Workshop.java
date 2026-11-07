package justfatlard.minicraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where builds are made: one empty dimension, cut into plots, a box in each.
 *
 * <p>One dimension, not one per bench, for the reason Amethyst Door gives: a dimension is
 * registered when the server loads its data and not afterwards. A plot a few hundred blocks from
 * the next in an empty world is as private as a dimension and needs no restart.
 *
 * <p>A box is N blocks to a side, one block of it for each cell of the miniature, standing on a
 * floor that reaches a little past it on every side so there is somewhere to stand back and look.
 * Nothing walls it in: the box has to be built from outside as much as in, and a box two blocks
 * across has no inside to stand in.
 */
public final class Workshop {
	private Workshop() {}

	public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
		Identifier.fromNamespaceAndPath(Main.MOD_ID, "workshop"));

	private static final int PLOT_SPACING = 256;
	private static final int PLOTS_PER_ROW = 64;
	private static final int FLOOR_Y = 64;
	/** How far the floor reaches past the box on every side. */
	static final int MARGIN = 4;
	/** How far past the floor a builder may fly before being brought back. */
	private static final int REACH = 6;

	private static final BlockState FLOOR = Blocks.SMOOTH_STONE.defaultBlockState();
	static final BlockState FOOTPRINT = Blocks.CONCRETE.white().defaultBlockState();

	public static ServerLevel level(MinecraftServer server) {
		return server.getLevel(DIMENSION);
	}

	public static boolean isWorkshop(Level level) {
		return level.dimension().equals(DIMENSION);
	}

	/** The box's lowest corner. */
	public static BlockPos origin(int plot) {
		return new BlockPos((plot % PLOTS_PER_ROW) * PLOT_SPACING, FLOOR_Y, (plot / PLOTS_PER_ROW) * PLOT_SPACING);
	}

	public static int plotAt(BlockPos pos) {
		int column = Math.floorDiv(pos.getX() + PLOT_SPACING / 2, PLOT_SPACING);
		int row = Math.floorDiv(pos.getZ() + PLOT_SPACING / 2, PLOT_SPACING);
		return row * PLOTS_PER_ROW + column;
	}

	public static boolean inBox(BlockPos pos, int plot, int n) {
		BlockPos min = origin(plot);
		return pos.getX() >= min.getX() && pos.getX() < min.getX() + n
			&& pos.getY() >= min.getY() && pos.getY() < min.getY() + n
			&& pos.getZ() >= min.getZ() && pos.getZ() < min.getZ() + n;
	}

	/** Where a builder may be: over the floor and a little beyond, from just under the box to well over it. */
	public static AABB bounds(int plot, int n) {
		BlockPos min = origin(plot);
		int out = MARGIN + REACH;
		return new AABB(min.getX() - out, min.getY() - 1, min.getZ() - out,
			min.getX() + n + out, min.getY() + n + REACH + 4, min.getZ() + n + out);
	}

	/** Where a builder arrives: on the floor, south of the box, facing it. */
	public static Vec3 arrival(int plot, int n) {
		BlockPos min = origin(plot);
		return new Vec3(min.getX() + n / 2.0, min.getY(), min.getZ() + n + MARGIN - 1.5);
	}

	/** Lay the floor for a box this size: plain stone, with the box's own footprint in white under it. */
	public static void prepare(ServerLevel level, int plot, int n) {
		BlockPos min = origin(plot);
		int widest = Miniature.MAX_SIZE + MARGIN;
		for (int x = -widest; x < widest + Miniature.MAX_SIZE; x++) for (int z = -widest; z < widest + Miniature.MAX_SIZE; z++) {
			BlockPos at = min.offset(x, -1, z);
			boolean floor = x >= -MARGIN && z >= -MARGIN && x < n + MARGIN && z < n + MARGIN;
			boolean footprint = x >= 0 && z >= 0 && x < n && z < n;
			BlockState want = footprint ? FOOTPRINT : floor ? FLOOR : Blocks.AIR.defaultBlockState();
			if (level.getBlockState(at) != want) level.setBlock(at, want, Block.UPDATE_CLIENTS);
		}
	}

	/** Empty the box, quietly: nothing drops here anyway, and nothing should react to it going. */
	public static void clear(ServerLevel level, int plot, int n) {
		BlockPos min = origin(plot);
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int y = 0; y < n; y++) for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
			at.setWithOffset(min, x, y, z);
			if (!level.getBlockState(at).isAir()) level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		}
	}
}
