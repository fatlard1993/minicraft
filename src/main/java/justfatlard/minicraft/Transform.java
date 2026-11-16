package justfatlard.minicraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How a miniature is to be turned, mirrored and flipped when it is built full size: kept on the
 * placed miniature, never changing how the miniature itself is drawn.
 *
 * <p>Applied in this order: upside down, then mirrored left-right (west and east swapped, as seen
 * from in front of the build, its south side), then front-back (north and south swapped), then
 * turned clockwise, seen from above, a quarter at a time. The build stays in the same N by N box
 * whatever is done to it, so it always grows east, north and up from the miniature; upside down,
 * it is its content that turns over, not the way it grows.
 *
 * <p>Turning and mirroring use the game's own {@code BlockState.rotate} and {@code mirror}, so
 * stairs, doors and logs turn with the build. Turning over has no vanilla form, so it is done here
 * by property: stair and trapdoor halves, slab tops and bottoms, anything facing up or down (an
 * observer, a piston, an end rod), levers and buttons on a floor or a ceiling, a lantern standing
 * or hanging, a dripstone's point, and the up and down sides of a mushroom block or lichen.
 * Whatever has none of those and is not the same either way up (a torch, a carpet, a rail, a
 * flower, a bed) has no upside-down form: it is kept as it is, and counted as not flipping. Doors
 * and tall plants, two blocks high, are kept upright, their halves swapped back into place.
 */
public record Transform(int turns, boolean mirrorLeftRight, boolean mirrorFrontBack, boolean upsideDown) {
	public static final Transform NONE = new Transform(0, false, false, false);

	public Transform {
		turns = Math.floorMod(turns, 4);
	}

	public boolean isNone() {
		return equals(NONE);
	}

	public Transform turned(int quarters) {
		return new Transform(turns + quarters, mirrorLeftRight, mirrorFrontBack, upsideDown);
	}

	public Transform mirroredLeftRight() {
		return new Transform(turns, !mirrorLeftRight, mirrorFrontBack, upsideDown);
	}

	public Transform mirroredFrontBack() {
		return new Transform(turns, mirrorLeftRight, !mirrorFrontBack, upsideDown);
	}

	public Transform flipped() {
		return new Transform(turns, mirrorLeftRight, mirrorFrontBack, !upsideDown);
	}

	/** Packed for the block entity: two bits of turn, then a bit each. */
	public int pack() {
		return turns | (mirrorLeftRight ? 4 : 0) | (mirrorFrontBack ? 8 : 0) | (upsideDown ? 16 : 0);
	}

	public static Transform unpack(int bits) {
		return new Transform(bits & 3, (bits & 4) != 0, (bits & 8) != 0, (bits & 16) != 0);
	}

	/** "As built", or what has been done: "Turned 90 clockwise, mirrored left-right, upside down". */
	public Component describe() {
		if (isNone()) return Component.translatableWithFallback("minicraft-justfatlard.transform.none", "As built");
		List<String> parts = new ArrayList<>();
		if (turns != 0) parts.add(Component.translatableWithFallback("minicraft-justfatlard.transform.turned", "turned %s clockwise", turns * 90).getString());
		if (mirrorLeftRight) parts.add(Component.translatableWithFallback("minicraft-justfatlard.transform.mirror_lr", "mirrored left-right").getString());
		if (mirrorFrontBack) parts.add(Component.translatableWithFallback("minicraft-justfatlard.transform.mirror_fb", "mirrored front-back").getString());
		if (upsideDown) parts.add(Component.translatableWithFallback("minicraft-justfatlard.transform.upside_down", "upside down").getString());
		String joined = String.join(", ", parts);
		return Component.literal(Character.toUpperCase(joined.charAt(0)) + joined.substring(1));
	}

	/**
	 * The build as it will stand: every cell moved and every state turned with it.
	 *
	 * @param build the transformed build, in the same box
	 * @param unflipped the cells (of {@code build}) holding a block with no upside-down form, kept as it was
	 */
	public record Result(Miniature build, Set<BlockPos> unflipped) {}

	public Result apply(Miniature source) {
		if (isNone()) return new Result(source, Set.of());
		int n = source.n();
		int height = source.height();
		Map<BlockState, Integer> indices = new HashMap<>();
		List<BlockState> palette = new ArrayList<>();
		byte[] cells = new byte[n * height * n];
		Set<BlockPos> unflipped = new HashSet<>();
		for (int y = 0; y < height; y++) for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
			BlockState state = source.at(x, y, z);
			if (state.isAir()) continue;
			int tx = x, ty = y, tz = z;
			boolean kept = false;
			if (upsideDown) {
				ty = height - 1 - y;
				BlockState over = flip(state);
				kept = over == null;
				if (over == null) over = upright(state);
				state = over;
			}
			if (mirrorLeftRight) {
				tx = n - 1 - tx;
				state = state.mirror(Mirror.FRONT_BACK);
			}
			if (mirrorFrontBack) {
				tz = n - 1 - tz;
				state = state.mirror(Mirror.LEFT_RIGHT);
			}
			for (int i = 0; i < turns; i++) {
				int was = tx;
				tx = n - 1 - tz;
				tz = was;
				state = state.rotate(Rotation.CLOCKWISE_90);
			}
			Integer index = indices.get(state);
			if (index == null) {
				palette.add(state);
				index = palette.size();
				indices.put(state, index);
			}
			// Distinct states can only multiply by turning one over, and a miniature already holds
			// at most 255; past that the cell keeps the nearest one already in the palette.
			if (index > Miniature.MAX_STATES) index = Miniature.MAX_STATES;
			cells[source.index(tx, ty, tz)] = (byte) (int) index;
			if (kept) unflipped.add(new BlockPos(tx, ty, tz));
		}
		if (palette.size() > Miniature.MAX_STATES) palette = palette.subList(0, Miniature.MAX_STATES);
		return new Result(new Miniature(n, height, List.copyOf(palette), cells), Set.copyOf(unflipped));
	}

	/**
	 * A two-high block turned over position for position: its halves swapped back, so it stands
	 * upright where it lands. Anything else, as it was.
	 */
	private static BlockState upright(BlockState state) {
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
			DoubleBlockHalf half = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
			return state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, half == DoubleBlockHalf.LOWER ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
		}
		return state;
	}

	/** This block upside down, or null if it has no upside-down form; itself if it is the same either way up. */
	static BlockState flip(BlockState state) {
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return null;
		BlockState out = state;
		boolean vertical = false;
		if (out.hasProperty(BlockStateProperties.HALF)) {
			out = out.setValue(BlockStateProperties.HALF, out.getValue(BlockStateProperties.HALF) == Half.TOP ? Half.BOTTOM : Half.TOP);
			vertical = true;
		}
		if (out.hasProperty(BlockStateProperties.SLAB_TYPE)) {
			SlabType type = out.getValue(BlockStateProperties.SLAB_TYPE);
			if (type != SlabType.DOUBLE) out = out.setValue(BlockStateProperties.SLAB_TYPE, type == SlabType.TOP ? SlabType.BOTTOM : SlabType.TOP);
			vertical = true;
		}
		if (out.hasProperty(BlockStateProperties.ATTACH_FACE)) {
			AttachFace face = out.getValue(BlockStateProperties.ATTACH_FACE);
			if (face != AttachFace.WALL) out = out.setValue(BlockStateProperties.ATTACH_FACE, face == AttachFace.FLOOR ? AttachFace.CEILING : AttachFace.FLOOR);
			vertical = true;
		}
		if (out.hasProperty(BlockStateProperties.HANGING)) {
			out = out.setValue(BlockStateProperties.HANGING, !out.getValue(BlockStateProperties.HANGING));
			vertical = true;
		}
		if (out.hasProperty(BlockStateProperties.UP) && out.hasProperty(BlockStateProperties.DOWN)) {
			boolean up = out.getValue(BlockStateProperties.UP);
			out = out.setValue(BlockStateProperties.UP, out.getValue(BlockStateProperties.DOWN)).setValue(BlockStateProperties.DOWN, up);
			vertical = true;
		}
		for (Property<?> property : out.getProperties()) {
			if (property instanceof EnumProperty<?> enumProperty && enumProperty.getValueClass() == Direction.class) {
				@SuppressWarnings("unchecked")
				EnumProperty<Direction> facing = (EnumProperty<Direction>) enumProperty;
				Direction now = out.getValue(facing);
				if (now.getAxis() != Direction.Axis.Y) {
					if (facing.getPossibleValues().contains(Direction.UP)) vertical = true;
					continue;
				}
				Direction other = now.getOpposite();
				if (!facing.getPossibleValues().contains(other)) return null;
				out = out.setValue(facing, other);
				vertical = true;
			}
		}
		if (vertical) return out;
		return symmetric(state) ? state : null;
	}

	/** Whether its shape is the same upside down: a full block, a fence, a pane, a log. */
	private static boolean symmetric(BlockState state) {
		VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		if (shape.isEmpty()) return false;
		VoxelShape over = Shapes.empty();
		for (AABB box : shape.toAabbs()) over = Shapes.or(over, Shapes.box(box.minX, 1 - box.maxY, box.minZ, box.maxX, 1 - box.minY, box.maxZ));
		return !Shapes.joinIsNotEmpty(shape, over, BooleanOp.NOT_SAME);
	}
}
