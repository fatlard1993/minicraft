package justfatlard.minicraft;

import com.mojang.math.Transformation;
import justfatlard.pandorical.api.BlockEntry;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.RelPos;
import justfatlard.pandorical.api.StructurePose;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a placed miniature is: its looks, what it works as, what it is mostly made of, and how it
 * is drawn.
 *
 * <p>Drawn two ways at once, each for the players who can see it. A Pandorical client draws it as
 * a structure scaled to 1/N, anchored to one entity standing at the block: one object, however
 * many cells. Anyone else sees block displays, one per cell that can be seen, the way Bonsai
 * Saplings draws its trees, capped; those displays are never sent to a client that draws the
 * structure. The cells are the record; both drawings, and the shape, are made from them, and
 * when its look changes only the cells that differ are drawn again.
 */
public class MiniatureBlockEntity extends BlockEntity {
	public static final String FIGURE_TAG = "minicraft_figure";
	public static final String ANCHOR_TAG = "minicraft_anchor";
	/** Before a figure's cell, which it is tagged with too, so a change of look finds the figures that changed. */
	private static final String CELL_TAG = "minicraft_cell:";
	/** Past this many showing cells the displays stop; the structure has no such limit. */
	static final int MAX_FIGURES = 1024;
	private static final float VIEW_RANGE = 0.6F;

	/** Where an item keeps what its function holds, beside the looks in its custom data. */
	static final String FUNCTION_KEY = "function_state";

	private Looks looks;
	/** One shape per look, built when first wanted. */
	private final VoxelShape[] shapes = new VoxelShape[4];
	/** What it works as, made from its looks; null for nothing. */
	private Function function;
	/** The block filling most of its build as built, which it sounds and breaks like. Worked out as its looks are set. */
	private BlockState majority = Blocks.STONE.defaultBlockState();
	/** The name it was given at the bench, or null; carried as the item's custom name. */
	private Component name;
	/** How it is to be turned, mirrored and flipped when built full size; kept here only, never on the item. */
	private Transform transform = Transform.NONE;
	/** What its function held, read from an item before it had a world to be loaded into. */
	private CompoundTag pendingFunction;

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

	public Function function() {
		return function;
	}

	/** The name it was given at the bench, or null. */
	public Component name() {
		return name;
	}

	/** What a menu it opens is called: its own name if it has one, as a renamed chest's is, or the block's. */
	public Component title(Component otherwise) {
		return name != null ? name : otherwise;
	}

	/** How it is to be turned, mirrored and flipped when built full size. Changes nothing about how it is drawn. */
	public Transform transform() {
		return transform;
	}

	public void setTransform(Transform transform) {
		this.transform = transform;
		setChanged();
	}

	/** The block it sounds and breaks like: whichever fills the most cells as built. */
	public BlockState majority() {
		return majority;
	}

	/** Take on these looks: the function they name made afresh if it is not the one held, the majority worked out again. */
	private void setLooks(Looks found) {
		Block before = looks == null ? null : looks.function().orElse(null);
		looks = found;
		java.util.Arrays.fill(shapes, null);
		BlockState most = found.off().majority();
		majority = most.isAir() ? Blocks.STONE.defaultBlockState() : most;
		Block now = found.function().orElse(null);
		if (function == null || before != now) {
			if (function != null) function.removed();
			function = now == null ? null : Functions.create(now, this);
		}
	}

	/** It works as something else now, or as nothing: an anvil worn through. What it held is let go, since only an anvil wears. */
	void changeFunction(Optional<Block> block) {
		if (looks == null) return;
		setLooks(looks.withFunction(block.filter(Functions::supported)));
		setChanged();
	}

	/** A tick on the server, for a function that works over time. */
	void tick(ServerLevel level, BlockPos pos, BlockState state) {
		if (function != null && function.ticks()) function.tick(level, pos, state);
	}

	/** How far up the block its cells start: a top slab sits on the upper half. */
	private double lift() {
		Miniature look = miniature();
		if (look == null || !look.isSlab()) return 0;
		return getBlockState().getValue(MiniatureBlock.TYPE) == SlabType.TOP ? (double) (look.n() - look.height()) / look.n() : 0;
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
		Miniature before = miniature();
		double liftBefore = lift();
		setLook(level, powered, handled);
		redraw(level, before, liftBefore);
		if (wasHandled != getBlockState().getValue(MiniatureBlock.HANDLED)) {
			level.playSound(null, worldPosition, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.3F, handled ? 0.6F : 0.5F);
		}
	}

	/** Throw its handle, or throw it back, if it has one: a right-click, a sneak with an item, the dialog's button. */
	public void workHandle(ServerLevel level) {
		if (looks == null || !looks.hasHandle()) return;
		BlockState state = getBlockState();
		wear(level, state.getValue(MiniatureBlock.POWERED), !state.getValue(MiniatureBlock.HANDLED));
	}

	/** Who worked a handle with something in hand, and in which tick: the item is not used as well. */
	private static final java.util.Map<java.util.UUID, Integer> workedWithItem = new java.util.HashMap<>();

	static void workedWithItem(net.minecraft.server.level.ServerPlayer player) {
		workedWithItem.put(player.getUUID(), player.level().getServer().getTickCount());
	}

	/**
	 * Whether this player worked a handle with something in hand this tick. Their client, not
	 * knowing the click was taken, goes on to use the item in the air (a bucket, food) in the
	 * same tick; that is refused.
	 */
	static boolean justWorkedWithItem(net.minecraft.server.level.ServerPlayer player) {
		Integer at = workedWithItem.get(player.getUUID());
		if (at == null) return false;
		if (at == player.level().getServer().getTickCount()) return true;
		workedWithItem.remove(player.getUUID());
		return false;
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

	/**
	 * Draw the look it wears now over the one it wore, on what is already there. Where the cells
	 * sit as they did, only the cells that differ change; otherwise everything is drawn again.
	 */
	private void redraw(ServerLevel level, Miniature before, double liftBefore) {
		Display.BlockDisplay anchor = null;
		List<Display.BlockDisplay> standing = new ArrayList<>();
		for (Display.BlockDisplay piece : piecesHere(level)) {
			if (piece.entityTags().contains(ANCHOR_TAG)) anchor = piece;
			else standing.add(piece);
		}
		if (anchor == null) {
			show(level);
			return;
		}
		Miniature look = miniature();
		if (before != null && before.n() == look.n() && liftBefore == lift()) {
			refigure(level, standing);
			rebuild(anchor, before, look);
		} else {
			for (Display.BlockDisplay piece : standing) piece.discard();
			figures(level);
			build(anchor);
		}
	}

	private void figures(ServerLevel level) {
		List<Miniature.Cell> showing = miniature().showing();
		spawnFigures(level, showing.subList(0, Math.min(showing.size(), MAX_FIGURES)));
	}

	/** The figures standing brought to the look worn now: each kept on its cell, with its block changed if need be, and the rest let go or added. */
	private void refigure(ServerLevel level, List<Display.BlockDisplay> standing) {
		Map<String, Display.BlockDisplay> byCell = new HashMap<>();
		for (Display.BlockDisplay piece : standing) {
			String cell = cellTag(piece);
			if (cell == null || byCell.putIfAbsent(cell, piece) != null) piece.discard();
		}
		List<Miniature.Cell> showing = miniature().showing();
		List<Miniature.Cell> missing = new ArrayList<>();
		for (int i = 0; i < Math.min(showing.size(), MAX_FIGURES); i++) {
			Miniature.Cell at = showing.get(i);
			Display.BlockDisplay piece = byCell.remove(cellTag(at));
			if (piece == null) missing.add(at);
			else piece.setBlockState(at.state());
		}
		for (Display.BlockDisplay piece : byCell.values()) piece.discard();
		spawnFigures(level, missing);
	}

	private static String cellTag(Miniature.Cell at) {
		return CELL_TAG + at.x() + "," + at.y() + "," + at.z();
	}

	private static String cellTag(Display.BlockDisplay piece) {
		for (String tag : piece.entityTags()) if (tag.startsWith(CELL_TAG)) return tag;
		return null;
	}

	private void spawnFigures(ServerLevel level, List<Miniature.Cell> cells) {
		Miniature look = miniature();
		Direction facing = getBlockState().getValue(MiniatureBlock.FACING);
		Quaternionf turn = new Quaternionf().rotateY(-turns(facing) * (float) Math.PI / 2);
		float cell = 1.0F / look.n();
		float lift = (float) lift();
		Vector3f middle = new Vector3f(0.5F, 0, 0.5F);
		for (Miniature.Cell at : cells) {
			Vector3f offset = new Vector3f(at.x() * cell, at.y() * cell, at.z() * cell).sub(middle);
			Vector3f translation = turn.transform(offset).add(middle).add(0, lift, 0);
			Display.BlockDisplay piece = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
			piece.setPos(Vec3.atLowerCornerOf(worldPosition));
			piece.setBlockState(at.state());
			piece.setViewRange(VIEW_RANGE);
			piece.setTransformation(new Transformation(translation, new Quaternionf(turn), new Vector3f(cell), new Quaternionf()));
			piece.addTag(FIGURE_TAG);
			piece.addTag(cellTag(at));
			level.addFreshEntity(piece);
		}
	}

	/**
	 * The structure a Pandorical client draws, on this anchor, and the shape and material that go
	 * with it. Pandorical forgets all three when the server stops, so this runs again whenever the
	 * anchor loads.
	 */
	void build(Display.BlockDisplay anchor) {
		Miniature look = miniature();
		if (look == null) return;
		List<BlockEntry> blocks = new ArrayList<>();
		for (Miniature.Cell at : turned(look, look.showing())) blocks.add(new BlockEntry(new RelPos(at.x(), at.y(), at.z()), at.state()));
		String id = structureId(anchor);
		PandoricalApi.structures().spawn(anchor, id, blocks, new StructurePose(anchor.getX(), anchor.getY() + lift(), anchor.getZ(), 0));
		PandoricalApi.structures().setScale(id, 1.0F / look.n());
		if (level instanceof ServerLevel server) {
			PandoricalApi.blockShapes().setShape(server, worldPosition, shape().toAabbs());
			PandoricalApi.blockMaterials().setMaterial(server, worldPosition, majority);
		}
	}

	/** The structure on this anchor, drawn in the look before, brought to the look worn now by the cells that differ, and the shape that goes with it. */
	private void rebuild(Display.BlockDisplay anchor, Miniature before, Miniature look) {
		Map<RelPos, BlockState> gone = new HashMap<>();
		for (Miniature.Cell at : turned(before, before.showing())) gone.put(new RelPos(at.x(), at.y(), at.z()), at.state());
		List<BlockEntry> added = new ArrayList<>();
		Map<RelPos, BlockState> changed = new HashMap<>();
		for (Miniature.Cell at : turned(look, look.showing())) {
			RelPos pos = new RelPos(at.x(), at.y(), at.z());
			BlockState was = gone.remove(pos);
			if (was == null) added.add(new BlockEntry(pos, at.state()));
			else if (was != at.state()) changed.put(pos, at.state());
		}
		if (!added.isEmpty() || !gone.isEmpty() || !changed.isEmpty()) {
			PandoricalApi.structures().updateBlocks(structureId(anchor), added, List.copyOf(gone.keySet()), changed);
		}
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
		PandoricalApi.blockMaterials().clearMaterial(level, worldPosition);
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
		int height = look.height();
		double cell = 1.0 / n;
		double lift = lift();
		boolean[] whole = new boolean[n * height * n];
		VoxelShape out = Shapes.empty();
		List<Miniature.Cell> all = new ArrayList<>();
		for (int y = 0; y < height; y++) for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
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
				out = Shapes.joinUnoptimized(out, Shapes.box((at.x() + box.minX) * cell, (at.y() + box.minY) * cell + lift, (at.z() + box.minZ) * cell,
					(at.x() + box.maxX) * cell, (at.y() + box.maxY) * cell + lift, (at.z() + box.maxZ) * cell), BooleanOp.OR);
			}
		}
		for (int y = 0; y < height; y++) for (int z = 0; z < n; z++) {
			for (int x = 0; x < n; ) {
				if (!whole[(y * n + z) * n + x]) {
					x++;
					continue;
				}
				int from = x;
				while (x < n && whole[(y * n + z) * n + x]) x++;
				out = Shapes.joinUnoptimized(out, Shapes.box(from * cell, y * cell + lift, z * cell, x * cell, (y + 1) * cell + lift, (z + 1) * cell), BooleanOp.OR);
			}
		}
		out = out.optimize();
		return out.isEmpty() ? MiniatureBlock.fallbackShape(getBlockState()) : out;
	}

	// --- Kept ---

	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (level instanceof ServerLevel server) hide(server);
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		if (function != null) function.removed();
	}

	@Override
	public void setLevel(Level level) {
		super.setLevel(level);
		if (function != null) function.setLevel(level);
		if (pendingFunction != null && function != null) {
			loadFunction(level.registryAccess(), pendingFunction);
			pendingFunction = null;
		}
	}

	private void loadFunction(HolderLookup.Provider registries, CompoundTag tag) {
		if (function != null) function.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
	}

	/** What the function holds, as the item carries it; null if it holds nothing worth carrying. */
	private CompoundTag functionTag(HolderLookup.Provider registries) {
		if (function == null || !looks.keepsThings()) return null;
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
		function.save(output);
		return output.buildResult();
	}

	@Override
	protected void applyImplicitComponents(DataComponentGetter components) {
		super.applyImplicitComponents(components);
		name = components.get(DataComponents.CUSTOM_NAME);
		CustomData data = components.get(DataComponents.CUSTOM_DATA);
		if (data == null) return;
		Optional<Looks> found = Looks.of(data);
		if (found.isEmpty()) return;
		setLooks(found.get());
		CompoundTag held = data.copyTag().getCompound(FUNCTION_KEY).orElse(null);
		// A miniature from before the functional slot kept its chest's things as a container.
		ItemContainerContents contents = components.get(DataComponents.CONTAINER);
		if (held == null && contents != null && function != null && function.container() != null) {
			Container container = function.container();
			NonNullList<ItemStack> items = NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
			contents.copyInto(items);
			for (int i = 0; i < items.size(); i++) container.setItem(i, items.get(i));
			return;
		}
		if (held == null) return;
		if (level != null) loadFunction(level.registryAccess(), held);
		else pendingFunction = held;
	}

	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder components) {
		super.collectImplicitComponents(components);
		if (looks == null) return;
		CompoundTag root = looks.toData().copyTag();
		CompoundTag held = level == null ? pendingFunction : functionTag(level.registryAccess());
		if (held != null && !held.isEmpty()) root.put(FUNCTION_KEY, held);
		components.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		if (name != null) components.set(DataComponents.CUSTOM_NAME, name);
	}

	@Override
	public void removeComponentsFromTag(ValueOutput output) {
		super.removeComponentsFromTag(output);
		output.discard("CustomName");
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (looks != null) output.store("looks", CustomData.CODEC, looks.toData());
		if (function != null) function.save(output.child("function"));
		output.storeNullable("CustomName", ComponentSerialization.CODEC, name);
		if (!transform.isNone()) output.putInt("construct_transform", transform.pack());
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		looks = null;
		function = null;
		name = parseCustomNameSafe(input, "CustomName");
		transform = Transform.unpack(input.getIntOr("construct_transform", 0));
		input.read("looks", CustomData.CODEC).flatMap(Looks::of).ifPresent(this::setLooks);
		java.util.Arrays.fill(shapes, null);
		if (function == null) return;
		Optional<ValueInput> held = input.child("function");
		if (held.isPresent()) function.load(held.get());
		// Saved before the functional slot: the chest's things at the top, as "Items".
		else function.load(input);
	}
}
