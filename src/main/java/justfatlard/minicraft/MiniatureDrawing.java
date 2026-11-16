package justfatlard.minicraft;

import justfatlard.pandorical.api.CellsDrawing;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * A miniature carried in a Pandorical structure, such as a Big Boats ship at sea, drawn as its
 * build: the look its state says it wears, raised for a top slab and turned to its facing, read
 * from the block entity data saved with it. Registered with Pandorical's structure drawings, so
 * the mod carrying it needs to know nothing about miniatures.
 */
public final class MiniatureDrawing {
	private MiniatureDrawing() {}

	public static Optional<CellsDrawing> draw(BlockState state, @Nullable CompoundTag saved) {
		if (!state.is(Main.MINIATURE) || saved == null) return Optional.empty();
		Tag kept = saved.get("looks");
		if (kept == null) return Optional.empty();
		Optional<Looks> looks = CustomData.CODEC.parse(NbtOps.INSTANCE, kept).result().flatMap(Looks::of);
		if (looks.isEmpty()) return Optional.empty();
		Miniature look = looks.get().look(state.getValue(MiniatureBlock.POWERED), state.getValue(MiniatureBlock.HANDLED));
		float lift = look.isSlab() && state.getValue(MiniatureBlock.TYPE) == SlabType.TOP ? (float) (look.n() - look.height()) / look.n() : 0;
		return Optional.of(new CellsDrawing(look.n(), look.height(), look.palette(), look.cells(), lift, turns(state.getValue(MiniatureBlock.FACING))));
	}

	/** Quarter turns clockwise from facing south, as the miniature turns its own drawing. */
	private static int turns(Direction facing) {
		return switch (facing) {
			case WEST -> 1;
			case NORTH -> 2;
			case EAST -> 3;
			default -> 0;
		};
	}
}
