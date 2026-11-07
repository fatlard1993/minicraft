package justfatlard.minicraft;

import com.mojang.math.Transformation;
import justfatlard.pandorical.api.BlockEntry;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.RelPos;
import justfatlard.pandorical.api.StructurePose;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a placed miniature is: its looks, its storage, and how it is drawn.
 *
 * <p>Drawn two ways at once, each for the players who can see it. A Pandorical client draws it as
 * a structure scaled to 1/N, anchored to one entity standing at the block: one object, however
 * many cells. Anyone else sees block displays, one per cell that can be seen, the way Bonsai
 * Saplings draws its trees, capped; those displays are never sent to a client that draws the
 * structure. The cells are the record; both drawings, and the shape, are made again from them
 * whenever its look changes.
 */
public class MiniatureBlockEntity extends BlockEntity {
	public static final String FIGURE_TAG = "minicraft_figure";
	public static final String ANCHOR_TAG = "minicraft_anchor";
	/** Past this many showing cells the displays stop; the structure has no such limit. */
	static final int MAX_FIGURES = 1024;
	private static final float VIEW_RANGE = 0.6F;
	private static final double REACH_SQUARED = 8 * 8;

	private Looks looks;
	/** One shape per look, built when first wanted. */
	private final VoxelShape[] shapes = new VoxelShape[4];
	private final NonNullList<ItemStack> items = NonNullList.withSize(Bench.STORAGE_SLOTS, ItemStack.EMPTY);

	public MiniatureBlockEntity(BlockPos pos, BlockState state) {
		super(Main.MINIATURE_BLOCK_ENTITY, pos, state);
	}

	public Optional<Looks> looks() {
		return Optional.ofNullable(looks);
	}

	/** The look it wears now. */
	public Miniature miniature() {
		if (looks == null) return null;
		BlockState state = getBlockState();
		return looks.look(state.getValue(MiniatureBlock.POWERED), state.getValue(MiniatureBlock.HANDLED));
	}

	private int lookIndex() {
		BlockState state = getBlockState();
		return Looks.index(state.getValue(MiniatureBlock.POWERED), state.getValue(MiniatureBlock.HANDLED));
	}

	public List<ItemStack> items() {
		return items;
	}

	// --- Wearing a look ---

	/** Just placed: powered if a neighbour already powers it, then drawn. */
	void placed(ServerLevel level) {
		boolean powered = looks != null && looks.respondsToRedstone() && level.hasNeighborSignal(worldPosition);
		setLook(level, powered, false);
		show(level);
	}

	/** Change look: the state that says which, its light, and the drawing and shape that go with it. */
	void wear(ServerLevel level, boolean powered, boolean handled) {
		boolean wasHandled = getBlockState().getValue(MiniatureBlock.HANDLED);
		setLook(level, powered, handled);
		redraw(level);
		if (wasHandled != getBlockState().getValue(MiniatureBlock.HANDLED)) {
			level.playSound(null, worldPosition, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.3F, handled ? 0.6F : 0.5F);
		}
	}

	private void setLook(ServerLevel level, boolean powered, boolean handled) {
		if (looks == null) return;
		boolean isHandled = handled && looks.hasHandle();
		Miniature look = looks.look(powered, isHandled);
		BlockState next = getBlockState().setValue(MiniatureBlock.POWERED, powered)
			.setValue(MiniatureBlock.HANDLED, isHandled).setValue(MiniatureBlock.LIGHT, brightest(look));
		if (next != getBlockState()) level.setBlock(worldPosition, next, Block.UPDATE_ALL);
	}

	/** The light a look gives: its brightest block's. */
	private static int brightest(Miniature look) {
		int light = 0;
		for (BlockState state : look.palette()) light = Math.max(light, state.getLightEmission());
		return light;
	}

	// --- Drawing ---

	/** Draw it from nothing: every showing cell a display, scaled to a cell and turned with the block, and an anchor for the structure. */
	void show(ServerLevel level) {
		hide(level);
		if (looks == null) return;
		figures(level);
		Display.BlockDisplay anchor = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
		anchor.setPos(Vec3.atLowerCornerOf(worldPosition));
		anchor.setViewRange(VIEW_RANGE);
		anchor.addTag(ANCHOR_TAG);
		// Its structure is built as it loads, which adding it is the first time of.
		level.addFreshEntity(anchor);
	}

	/** Draw the look it wears now on what is already there: new figures, the structure and shape again. */
	private void redraw(ServerLevel level) {
		Vec3 corner = Vec3.atLowerCornerOf(worldPosition);
		Display.BlockDisplay anchor = null;
		for (Display.BlockDisplay piece : piecesHere(level)) {
			if (piece.entityTags().contains(ANCHOR_TAG)) anchor = piece;
			else piece.discard();
		}
		figures(level);
		if (anchor != null) build(anchor);
		else show(level);
	}

	private void figures(ServerLevel level) {
		Miniature look = miniature();
		Direction facing = getBlockState().getValue(MiniatureBlock.FACING);
		Quaternionf turn = new Quaternionf().rotateY(-turns(facing) * (float) Math.PI / 2);
		float cell = 1.0F / look.n();
		Vector3f middle = new Vector3f(0.5F, 0, 0.5F);
		List<Miniature.Cell> showing = look.showing();
		for (int i = 0; i < Math.min(showing.size(), MAX_FIGURES); i++) {
			Miniature.Cell at = showing.get(i);
			Vector3f offset = new Vector3f(at.x() * cell, at.y() * cell, at.z() * cell).sub(middle);
			Vector3f translation = turn.transform(offset).add(middle);
			Display.BlockDisplay piece = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
			piece.setPos(Vec3.atLowerCornerOf(worldPosition));
			piece.setBlockState(at.state());
			piece.setViewRange(VIEW_RANGE);
			piece.setTransformation(new Transformation(translation, new Quaternionf(turn), new Vector3f(cell), new Quaternionf()));
			piece.addTag(FIGURE_TAG);
			level.addFreshEntity(piece);
		}
	}

	/**
	 * The structure a Pandorical client draws, on this anchor, and the shape that goes with it.
	 * Pandorical forgets both when the server stops, so this runs again whenever the anchor loads.
	 */
	void build(Display.BlockDisplay anchor) {
		Miniature look = miniature();
		if (look == null) return;
		List<BlockEntry> blocks = new ArrayList<>();
		for (Miniature.Cell at : turned(look, look.showing())) blocks.add(new BlockEntry(new RelPos(at.x(), at.y(), at.z()), at.state()));
		String id = structureId(anchor);
		PandoricalApi.structures().spawn(anchor, id, blocks, new StructurePose(anchor.getX(), anchor.getY(), anchor.getZ(), 0));
		PandoricalApi.structures().setScale(id, 1.0F / look.n());
		if (level instanceof ServerLevel server) PandoricalApi.blockShapes().setShape(server, worldPosition, shape().toAabbs());
	}

	static String structureId(Display.BlockDisplay anchor) {
		return Main.MOD_ID + ":" + anchor.getUUID();
	}

	/** Quarter turns clockwise from facing south, which is how the build faced its builder. */
	private static int turns(Direction facing) {
		return switch (facing) {
			case WEST -> 1;
			case NORTH -> 2;
			case EAST -> 3;
			default -> 0;
		};
	}

	/** Cells turned with the block: the grid turned and each block state with it. */
	private List<Miniature.Cell> turned(Miniature look, List<Miniature.Cell> cells) {
		int n = look.n();
		int turns = turns(getBlockState().getValue(MiniatureBlock.FACING));
		List<Miniature.Cell> out = new ArrayList<>(cells.size());
		for (Miniature.Cell at : cells) {
			int x = at.x(), z = at.z();
			BlockState state = at.state();
			for (int i = 0; i < turns; i++) {
				int was = x;
				x = n - 1 - z;
				z = was;
				state = state.rotate(Rotation.CLOCKWISE_90);
			}
			out.add(new Miniature.Cell(x, at.y(), z, state));
		}
		return out;
	}

	private List<Display.BlockDisplay> piecesHere(ServerLevel level) {
		Vec3 corner = Vec3.atLowerCornerOf(worldPosition);
		return level.getEntitiesOfClass(Display.BlockDisplay.class, new AABB(worldPosition).inflate(0.01),
			piece -> (piece.entityTags().contains(FIGURE_TAG) || piece.entityTags().contains(ANCHOR_TAG))
				&& piece.position().distanceToSqr(corner) < 1.0E-6);
	}

	void hide(ServerLevel level) {
		for (Display.BlockDisplay piece : piecesHere(level)) {
			if (piece.entityTags().contains(ANCHOR_TAG)) PandoricalApi.structures().despawn(structureId(piece));
			piece.discard();
		}
		PandoricalApi.blockShapes().clearShape(level, worldPosition);
	}

	// --- Shape ---

	/**
	 * What it is solid as in the look it wears: every cell's own collision shape at 1/N, or its
	 * outline where it has none (a flower is a sliver to catch the eye, not nothing), so it is
	 * stood on, walked under and aimed at exactly as it looks. Whole cells are run together along
	 * their rows first, so a solid wall is a handful of boxes, not hundreds. Built once per look.
	 */
	public VoxelShape shape() {
		Miniature look = miniature();
		if (look == null) return Shapes.block();
		int index = lookIndex();
		if (shapes[index] == null) shapes[index] = buildShape(look);
		return shapes[index];
	}

	private VoxelShape buildShape(Miniature look) {
		int n = look.n();
		double cell = 1.0 / n;
		boolean[] whole = new boolean[n * n * n];
		VoxelShape out = Shapes.empty();
		List<Miniature.Cell> all = new ArrayList<>();
		for (int y = 0; y < n; y++) for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
			BlockState state = look.at(x, y, z);
			if (!state.isAir()) all.add(new Miniature.Cell(x, y, z, state));
		}
		for (Miniature.Cell at : turned(look, all)) {
			VoxelShape own = at.state().getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			if (own.isEmpty()) own = at.state().getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			if (own.isEmpty()) continue;
			if (Block.isShapeFullBlock(own)) {
				whole[(at.y() * n + at.z()) * n + at.x()] = true;
				continue;
			}
			for (AABB box : own.toAabbs()) {
				out = Shapes.joinUnoptimized(out, Shapes.box((at.x() + box.minX) * cell, (at.y() + box.minY) * cell, (at.z() + box.minZ) * cell,
					(at.x() + box.maxX) * cell, (at.y() + box.maxY) * cell, (at.z() + box.maxZ) * cell), BooleanOp.OR);
			}
		}
		for (int y = 0; y < n; y++) for (int z = 0; z < n; z++) {
			for (int x = 0; x < n; ) {
				if (!whole[(y * n + z) * n + x]) {
					x++;
					continue;
				}
				int from = x;
				while (x < n && whole[(y * n + z) * n + x]) x++;
				out = Shapes.joinUnoptimized(out, Shapes.box(from * cell, y * cell, z * cell, x * cell, (y + 1) * cell, (z + 1) * cell), BooleanOp.OR);
			}
		}
		out = out.optimize();
		return out.isEmpty() ? Shapes.block() : out;
	}

	// --- Storage ---

	void openStorage(ServerPlayer player) {
		player.openMenu(new SimpleMenuProvider((id, inventory, opener) -> new StorageMenu(id, inventory, new Storage()),
			Component.translatableWithFallback("block.minicraft-justfatlard.miniature", "Miniature")));
		level.playSound(null, worldPosition, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 0.5F, 1.4F);
	}

	/** The storage's slots, as the menu sees them. */
	private final class Storage implements Container {
		@Override
		public int getContainerSize() {
			return items.size();
		}

		@Override
		public boolean isEmpty() {
			return items.stream().allMatch(ItemStack::isEmpty);
		}

		@Override
		public ItemStack getItem(int slot) {
			return items.get(slot);
		}

		@Override
		public ItemStack removeItem(int slot, int count) {
			ItemStack taken = ContainerHelper.removeItem(items, slot, count);
			if (!taken.isEmpty()) setChanged();
			return taken;
		}

		@Override
		public ItemStack removeItemNoUpdate(int slot) {
			return ContainerHelper.takeItem(items, slot);
		}

		@Override
		public void setItem(int slot, ItemStack stack) {
			items.set(slot, stack);
			setChanged();
		}

		@Override
		public boolean canPlaceItem(int slot, ItemStack stack) {
			return StorageMenu.fits(stack);
		}

		@Override
		public void setChanged() {
			MiniatureBlockEntity.this.setChanged();
		}

		@Override
		public boolean stillValid(Player player) {
			return !isRemoved() && player.distanceToSqr(Vec3.atCenterOf(worldPosition)) <= REACH_SQUARED;
		}

		@Override
		public void clearContent() {
			items.clear();
		}
	}

	// --- Kept ---

	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (level instanceof ServerLevel server) hide(server);
	}

	@Override
	protected void applyImplicitComponents(DataComponentGetter components) {
		super.applyImplicitComponents(components);
		CustomData data = components.get(DataComponents.CUSTOM_DATA);
		if (data != null) Looks.of(data).ifPresent(found -> {
			looks = found;
			java.util.Arrays.fill(shapes, null);
		});
		ItemContainerContents contents = components.get(DataComponents.CONTAINER);
		if (contents != null) contents.copyInto(items);
	}

	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder components) {
		super.collectImplicitComponents(components);
		if (looks != null) components.set(DataComponents.CUSTOM_DATA, looks.toData());
		if (looks != null && looks.hasStorage()) components.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (looks != null) output.store("looks", CustomData.CODEC, looks.toData());
		ContainerHelper.saveAllItems(output, items);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		looks = input.read("looks", CustomData.CODEC).flatMap(Looks::of).orElse(null);
		java.util.Arrays.fill(shapes, null);
		items.clear();
		ContainerHelper.loadAllItems(input, items);
	}
}
