package justfatlard.minicraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A build, cut N to a side: what block stands in each of its N³ cells.
 *
 * <p>The cells are indices into a palette of block states, one byte each, zero for air; so a
 * miniature holds at most 255 different states, which is more than anything built in a block
 * will use. Kept on the item as plain custom data rather than a component of this mod's own, so
 * it travels through any chest, hopper or trade without a client having to know a new type.
 *
 * <p>Cells run x fastest, then z, then y: a layer at a time from the bottom up.
 */
public record Miniature(int n, List<BlockState> palette, byte[] cells) {
	public static final int MIN_SIZE = 2;
	public static final int MAX_SIZE = 16;
	public static final int MAX_STATES = 255;

	private static final String KEY = "miniature";

	public static final Codec<Miniature> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.intRange(MIN_SIZE, MAX_SIZE).fieldOf("n").forGetter(Miniature::n),
		BlockState.CODEC.listOf().fieldOf("palette").forGetter(Miniature::palette),
		Codec.BYTE_BUFFER.xmap(Miniature::bytes, ByteBuffer::wrap).fieldOf("cells").forGetter(Miniature::cells)
	).apply(instance, Miniature::new));

	public Miniature {
		if (cells.length != n * n * n) throw new IllegalArgumentException("a miniature cut " + n + " to a side has " + n * n * n + " cells, not " + cells.length);
	}

	private static byte[] bytes(ByteBuffer buffer) {
		byte[] out = new byte[buffer.remaining()];
		buffer.duplicate().get(out);
		return out;
	}

	public int index(int x, int y, int z) {
		return (y * n + z) * n + x;
	}

	public BlockState at(int x, int y, int z) {
		if (x < 0 || y < 0 || z < 0 || x >= n || y >= n || z >= n) return Blocks.AIR.defaultBlockState();
		int cell = cells[index(x, y, z)] & 0xFF;
		return cell == 0 ? Blocks.AIR.defaultBlockState() : palette.get(cell - 1);
	}

	public boolean isEmpty() {
		for (byte cell : cells) if (cell != 0) return false;
		return true;
	}

	public int filled() {
		int count = 0;
		for (byte cell : cells) if (cell != 0) count++;
		return count;
	}

	/**
	 * Read the box at {@code min} into a miniature; empty if nothing is in it, and absent if it
	 * holds more different states than a miniature can.
	 */
	public static Optional<Miniature> capture(ServerLevel level, BlockPos min, int n) {
		List<BlockState> palette = new ArrayList<>();
		Map<BlockState, Integer> indices = new HashMap<>();
		byte[] cells = new byte[n * n * n];
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int y = 0; y < n; y++) for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
			BlockState state = level.getBlockState(at.setWithOffset(min, x, y, z));
			if (state.isAir()) continue;
			Integer index = indices.get(state);
			if (index == null) {
				if (palette.size() >= MAX_STATES) return Optional.empty();
				palette.add(state);
				index = palette.size();
				indices.put(state, index);
			}
			cells[(y * n + z) * n + x] = (byte) (int) index;
		}
		return Optional.of(new Miniature(n, List.copyOf(palette), cells));
	}

	/**
	 * Stand every cell up again as a full-size block in the box at {@code min}, exactly as it was:
	 * no neighbour updates, so a stair keeps the shape it had and a door its other half.
	 */
	public void build(ServerLevel level, BlockPos min) {
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int y = 0; y < n; y++) for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
			BlockState state = at(x, y, z);
			if (!state.isAir()) level.setBlock(at.setWithOffset(min, x, y, z), state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		}
	}

	/** A cell that can be seen from outside, and what is in it. */
	public record Cell(int x, int y, int z, BlockState state) {}

	/** The cells with at least one face not hidden behind a solid neighbour: the only ones worth drawing. */
	public List<Cell> showing() {
		List<Cell> showing = new ArrayList<>();
		for (int y = 0; y < n; y++) for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
			BlockState state = at(x, y, z);
			if (state.isAir()) continue;
			if (hides(x + 1, y, z) && hides(x - 1, y, z) && hides(x, y + 1, z) && hides(x, y - 1, z) && hides(x, y, z + 1) && hides(x, y, z - 1)) continue;
			showing.add(new Cell(x, y, z, state));
		}
		return showing;
	}

	private boolean hides(int x, int y, int z) {
		return at(x, y, z).isSolidRender();
	}

	// --- On an item ---

	public static Optional<Miniature> of(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? Optional.empty() : of(data);
	}

	public static Optional<Miniature> of(CustomData data) {
		Tag tag = data.copyTag().get(KEY);
		if (tag == null) return Optional.empty();
		return CODEC.parse(NbtOps.INSTANCE, tag).result();
	}

	public CustomData toData() {
		CompoundTag root = new CompoundTag();
		root.put(KEY, CODEC.encodeStart(NbtOps.INSTANCE, this).getOrThrow());
		return CustomData.of(root);
	}

	public ItemStack toItem() {
		return Looks.plain(this).toItem();
	}
}
