package justfatlard.minicraft;

import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.Trust;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Building a placed miniature at full size, where it stands: what it costs, whether it can go
 * there, and building it.
 *
 * <p><b>Where it goes.</b> A build never turns: the workshop's north is the world's north, as it
 * is for the miniature itself. The miniature's own block is the build's front left corner, seen
 * from in front of it (its south side) facing north: cell (0, 0, N-1), the bottom south-west one.
 * So the build runs east (+x), north (-z) and up from there, N across, N deep, and as high as it
 * is (N for a block, N/2 for a slab). A top-slab miniature is anchored at its block the same way,
 * the bottom of the build at that block's bottom.
 *
 * <p><b>What it costs</b> is its build as built, by material, counted exactly as building it in the
 * box and copying it at the bench count it ({@link Copying#cells}), with no conversion: at full
 * size one cell is one block of its material, a door one door for both halves, a double slab two
 * slabs. Paid from the player's own inventory at the moment it is built, all of it or none.
 * Nothing in creative.
 *
 * <p><b>Where it can go</b>: every spot one of its blocks lands on must be air or replaceable, as
 * placing a block there would be (grass, water, lava, snow that a block would replace), and free of
 * anybody standing in it, except the miniature's own spot; in loaded chunks; and somewhere the
 * player may build: inside the build height and the world border, not under spawn protection, in a
 * game mode that builds, not in the workshop, and trusted with fire if it holds fire or lava.
 */
public final class Constructing {
	private Constructing() {}

	/** One material: how many blocks of it the build takes, and how many the player carries. */
	public record Line(Item item, long cost, long held) {
		public boolean enough() {
			return held >= cost;
		}
	}

	/** Whether it can be built now, or the first reason why not. */
	public enum Why { EMPTY, WORKSHOP, NOT_ALLOWED, UNLOADED, BLOCKED, WONT_STAND, SHORT, READY }

	/** Why a spot it would land on is not the player's to build on. */
	enum Refusal { HEIGHT, BORDER, PROTECTED }

	/**
	 * Everything the dialog shows and building checks, worked out at one moment.
	 *
	 * @param blocked the spots something is in the way at, for the preview to show
	 * @param refused the spots the player may not build on, and the first reason found
	 * @param build the build as it will stand, turned, mirrored and flipped as asked
	 * @param unflipped how many of its blocks have no upside-down form and are kept as they are
	 * @param fallen how many of those would have nothing to hold them where they land
	 */
	public record Survey(Why why, List<Line> lines, boolean free, List<BlockPos> blocked, int refused, Refusal refusal,
			boolean gameMode, Trust untrusted, int n, int height, Miniature build, Transform transform, int unflipped, int fallen) {
		public boolean ready() {
			return why == Why.READY;
		}
	}

	/** Where cell (x, y, z) of a build cut {@code n} to a side lands, for a miniature at {@code at}. */
	public static BlockPos target(BlockPos at, int n, int x, int y, int z) {
		return at.offset(x, y, z - (n - 1));
	}

	/** The lowest north-west corner of the box it fills, the block at cell (0, 0, 0). */
	public static BlockPos corner(BlockPos at, int n) {
		return target(at, n, 0, 0, 0);
	}

	/** The survey of the placed miniature at this spot, with the transform it keeps; null if there is none. */
	public static Survey survey(ServerPlayer player, ServerLevel level, BlockPos at) {
		if (!(level.getBlockEntity(at) instanceof MiniatureBlockEntity miniature) || miniature.looks().isEmpty()) return null;
		return survey(player, level, at, miniature.looks().get().off(), miniature.transform());
	}

	/**
	 * Look at what building this miniature here would take, for this player, changing nothing.
	 * {@link #construct} surveys again itself at the moment it builds. The cost is the build's as
	 * built whatever is done to it; where it lands is where the transformed build's blocks do.
	 */
	public static Survey survey(ServerPlayer player, ServerLevel level, BlockPos at, Miniature original, Transform transform) {
		Transform.Result result = transform.apply(original);
		Miniature build = result.build();
		int n = build.n();
		int height = build.height();
		List<Line> lines = lines(player, original);
		boolean free = player.hasInfiniteMaterials();
		int unflipped = result.unflipped().size();
		if (build.isEmpty()) return new Survey(Why.EMPTY, lines, free, List.of(), 0, null, false, null, n, height, build, transform, unflipped, 0);
		if (Workshop.isWorkshop(level)) return new Survey(Why.WORKSHOP, lines, free, List.of(), 0, null, false, null, n, height, build, transform, unflipped, 0);

		boolean gameMode = !player.mayBuild();
		Trust untrusted = null;
		int refused = 0;
		Refusal refusal = null;
		boolean unloaded = false;
		List<BlockPos> blocked = new ArrayList<>();
		BlockPos.MutableBlockPos spot = new BlockPos.MutableBlockPos();
		for (int y = 0; y < height; y++) for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
			BlockState state = build.at(x, y, z);
			if (state.isAir()) continue;
			spot.set(target(at, n, x, y, z));
			if (untrusted == null) {
				Trust needs = trustFor(state);
				if (needs != null && !PandoricalApi.trust().may(player, needs)) untrusted = needs;
			}
			Refusal why = refusal(player, level, spot);
			if (why != null) {
				refused++;
				if (refusal == null) refusal = why;
				continue;
			}
			if (!level.hasChunkAt(spot)) {
				unloaded = true;
				continue;
			}
			if (spot.equals(at)) continue;
			if (!clear(level, spot, state)) blocked.add(spot.immutable());
		}
		int fallen = 0;
		if (unflipped > 0 && refused == 0 && !unloaded) {
			// Kept as they were, upside down: each must still have what holds it, in the build or the world.
			Overlay overlay = new Overlay(level, at, build);
			for (BlockPos cell : result.unflipped()) {
				BlockPos where = target(at, n, cell.getX(), cell.getY(), cell.getZ());
				if (!build.at(cell.getX(), cell.getY(), cell.getZ()).canSurvive(overlay, where)) fallen++;
			}
		}
		boolean enough = free || lines.stream().allMatch(Line::enough);
		Why why = refused > 0 || gameMode || untrusted != null ? Why.NOT_ALLOWED
			: unloaded ? Why.UNLOADED
			: !blocked.isEmpty() ? Why.BLOCKED
			: fallen > 0 ? Why.WONT_STAND
			: !enough ? Why.SHORT
			: Why.READY;
		return new Survey(why, lines, free, Collections.unmodifiableList(blocked), refused, refusal, gameMode, untrusted, n, height,
			build, transform, unflipped, fallen);
	}

	/** What each material costs at full size, against what the player carries. */
	static List<Line> lines(ServerPlayer player, Miniature build) {
		List<Line> lines = new ArrayList<>();
		for (Map.Entry<Item, Long> material : Copying.cells(build).entrySet()) {
			lines.add(new Line(material.getKey(), material.getValue(), carried(player.getInventory(), material.getKey())));
		}
		return Collections.unmodifiableList(lines);
	}

	/** Fire and lava need a player trusted with fire; TNT one trusted with explosives. */
	private static Trust trustFor(BlockState state) {
		if (state.is(BlockTags.FIRE) || state.getFluidState().is(FluidTags.LAVA)) return Trust.FIRE;
		if (state.is(Blocks.TNT)) return Trust.EXPLOSIVES;
		return null;
	}

	/** Why the player may not build at this spot, or null if they may. */
	private static Refusal refusal(ServerPlayer player, ServerLevel level, BlockPos spot) {
		if (level.isOutsideBuildHeight(spot)) return Refusal.HEIGHT;
		if (!level.getWorldBorder().isWithinBounds(spot)) return Refusal.BORDER;
		if (!player.mayInteract(level, spot)) return Refusal.PROTECTED;
		return null;
	}

	/**
	 * Whether this block could go at this spot as placing it would: what is there now is air or
	 * replaceable (grass, a flower, water, lava), and nobody stands where it would be solid.
	 */
	static boolean clear(ServerLevel level, BlockPos spot, BlockState state) {
		BlockState there = level.getBlockState(spot);
		if (!there.isAir() && !there.canBeReplaced()) return false;
		return level.isUnobstructed(state, spot, CollisionContext.empty());
	}

	// --- The player's inventory ---

	/** The slots materials are taken from: the pack, the hotbar and the off hand; never armour. */
	private static int[] slots() {
		int[] slots = new int[Inventory.INVENTORY_SIZE + 1];
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) slots[i] = i;
		slots[Inventory.INVENTORY_SIZE] = Inventory.SLOT_OFFHAND;
		return slots;
	}

	static long carried(Inventory inventory, Item item) {
		long count = 0;
		for (int slot : slots()) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(item)) count += stack.getCount();
		}
		return count;
	}

	/** Take every line's cost, if all of it is there; nothing at all if any is short. */
	private static boolean take(Inventory inventory, List<Line> lines) {
		for (Line line : lines) if (carried(inventory, line.item()) < line.cost()) return false;
		for (Line line : lines) {
			long left = line.cost();
			for (int slot : slots()) {
				if (left == 0) break;
				ItemStack stack = inventory.getItem(slot);
				if (!stack.is(line.item())) continue;
				int taken = (int) Math.min(left, stack.getCount());
				stack.shrink(taken);
				if (stack.isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
				left -= taken;
			}
		}
		inventory.setChanged();
		return true;
	}

	// --- Building it ---

	/**
	 * Build this placed miniature at full size where it stands, if it can be built right now:
	 * surveyed again here, on the server, at this moment, whatever the dialog last showed. The
	 * materials are taken, the miniature comes away into the player's inventory exactly as
	 * breaking it would drop it (or at their feet if it is full), and its spot becomes the
	 * build's corner.
	 *
	 * <p>Every block is set exactly as captured with no neighbour updates, so a door keeps both
	 * halves and a stair its shape; then the finished build is settled into the world once, as
	 * vanilla settles a placed structure: the edges between the build and the world updated
	 * both ways (a fence joins a fence beside it; a torch whose support was the box's floor and
	 * is not there now drops as an item, as it would if its support were broken), each block's
	 * shape checked against its neighbours, and every neighbour told, so redstone answers what
	 * it was put beside. Inside the build nothing changes, as it is as it was built.
	 *
	 * @return why it was not built, or {@link Why#READY} if it was
	 */
	public static Why construct(ServerPlayer player, ServerLevel level, BlockPos at) {
		if (!(level.getBlockEntity(at) instanceof MiniatureBlockEntity miniature) || miniature.looks().isEmpty()) return Why.EMPTY;
		Survey survey = survey(player, level, at, miniature.looks().get().off(), miniature.transform());
		if (!survey.ready()) return survey.why();
		Miniature build = survey.build();
		if (!survey.free() && !take(player.getInventory(), survey.lines())) return Why.SHORT;

		// The miniature comes away whole, through its own loot table, as breaking it gives it.
		BlockState held = level.getBlockState(at);
		List<ItemStack> drops = Block.getDrops(held, level, at, miniature, player, ItemStack.EMPTY);
		level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		for (ItemStack drop : drops) player.getInventory().placeItemBackInInventory(drop, Prediction.SERVER_ONLY);

		int n = build.n();
		List<BlockPos> placed = new ArrayList<>();
		for (int y = 0; y < build.height(); y++) for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
			BlockState state = build.at(x, y, z);
			if (state.isAir()) continue;
			BlockPos spot = target(at, n, x, y, z);
			try {
				if (level.setBlock(spot, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) placed.add(spot);
			} catch (RuntimeException e) {
				System.err.println("[" + Main.MOD_ID + "] Could not set " + state + " at " + spot + " building a miniature; left out: " + e);
			}
		}
		settle(level, placed);

		SoundType sound = build.majority().getSoundType();
		BlockPos middle = target(at, n, n / 2, 0, n / 2);
		level.playSound(null, middle, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
		Preview.stop(player, false);
		return Why.READY;
	}

	/** One pass of updates round a build just set without any: its edges, its shapes, its neighbours. */
	private static void settle(ServerLevel level, List<BlockPos> placed) {
		Set<BlockPos> inside = new HashSet<>(placed);
		int quiet = Block.UPDATE_CLIENTS;
		// The world beside the build, answering the face of the build it touches and only that, as
		// vanilla updates a placed structure's edges: a fence joining, grass going under a block.
		for (BlockPos spot : placed) for (Direction direction : Direction.values()) {
			BlockPos next = spot.relative(direction);
			if (inside.contains(next)) continue;
			BlockState was = level.getBlockState(next);
			if (was.isAir()) continue;
			BlockState now = was.updateShape(level, level, next, direction.getOpposite(), spot, level.getBlockState(spot), level.getRandom());
			if (now != was) Block.updateOrDestroy(was, now, level, next, quiet);
		}
		// The build answering the world, and itself: unchanged inside, settled at its edges.
		for (BlockPos spot : placed) {
			BlockState was = level.getBlockState(spot);
			BlockState now = Block.updateFromNeighbourShapes(was, level, spot);
			if (now != was) Block.updateOrDestroy(was, now, level, spot, quiet);
			level.getBlockState(spot).updateIndirectNeighbourShapes(level, spot, quiet);
		}
		// Every neighbour told, as a block placed tells them: redstone, doors, lamps settle.
		for (BlockPos spot : placed) level.updateNeighborsAt(spot, level.getBlockState(spot).getBlock());
	}

	// --- Saying why ---

	/** Why it cannot be built, or that it can, for the dialog and for a refused click. */
	public static Component reason(Survey survey) {
		return switch (survey.why()) {
			case EMPTY -> text("empty", "There is nothing in it to build.");
			case WORKSHOP -> text("workshop", "Not in the workshop: build it full size out in the world.");
			case NOT_ALLOWED -> notAllowed(survey);
			case UNLOADED -> text("unloaded", "Part of where it goes is not loaded. Come closer.");
			case BLOCKED -> survey.blocked().size() == 1
				? text("blocked_one", "1 block is in the way. Clear it first (red in the preview).")
				: text("blocked", "%s blocks are in the way. Clear them first (red in the preview).", survey.blocked().size());
			case WONT_STAND -> text("wont_stand", "Upside down, %s blocks that do not flip would have nothing to hold them.", survey.fallen());
			case SHORT -> text("short", "Not enough: carry more of what is in red.");
			case READY -> survey.free()
				? text("ready_free", "Ready to build. In creative, nothing is taken.")
				: text("ready", "Ready to build. The miniature comes back to you.");
		};
	}

	private static Component notAllowed(Survey survey) {
		if (survey.gameMode()) return text("game_mode", "You cannot build in this game mode.");
		if (survey.untrusted() == Trust.FIRE) return text("untrusted_fire", "It holds fire or lava, and you are not trusted with fire.");
		if (survey.untrusted() != null) return text("untrusted_explosives", "It holds TNT, and you are not trusted with explosives.");
		int count = survey.refused();
		return switch (survey.refusal()) {
			case HEIGHT -> text("height", "%s of its blocks would be outside the world's build height.", count);
			case BORDER -> text("border", "%s of its blocks would be past the world border.", count);
			case PROTECTED -> text("protected", "%s of its blocks would be under spawn protection.", count);
		};
	}

	private static Component text(String key, String fallback, Object... args) {
		return Component.translatableWithFallback("minicraft-justfatlard.construct." + key, fallback, args);
	}
}
