package justfatlard.minicraft;

import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Copying a miniature at a bench: what a copy costs, whether the bench can pay for it, and the
 * copy itself.
 *
 * <p><b>The cost</b> is the original's build as built, charged per material against the bench's
 * budget exactly as building it was: each block counted as the material breaking it in the box
 * gives back ({@link Visits#materialOf}, {@link Visits#blocksIn}), and a door's or a tall
 * flower's upper half and a bed's head counted with the half they belong to, as placing one
 * spends a single cell for both. The other looks (powered, handle worked) are captures of the
 * same build and cost nothing more. An original cut to another size is converted to the bench's
 * size a material at a time, rounding up: {@code ceil(cells * Nbench³ / Nmini³)}, so a copy is
 * never cheaper than building it.
 *
 * <p><b>What it works as</b>: an original that works as a block needs that block in the bench's
 * functional slot, spent on the copy. Any anvil serves for an anvil, and the copy works as the
 * anvil spent, chipped or not. What the original holds is never copied: a copy of a chest, a
 * furnace or a jukebox comes out empty.
 *
 * <p><b>What is copied</b>: every look, the handle, the format, the maker and the name. A copy is
 * made fresh from those, so nothing else on the original's item comes with it.
 */
public final class Copying {
	private Copying() {}

	/** An original, read once: its looks and what its build is, by material, in its own size. */
	public record Plan(Looks looks, Map<Item, Long> cells) {
		public int n() {
			return looks.off().n();
		}

		/** The block the copy must be given in the functional slot, if the original works as one. */
		public Optional<Block> needs() {
			return looks.function().filter(Functions::supported);
		}
	}

	/** One material: what the copy costs of it at the bench's size, and what the bench holds. */
	public record Line(Item item, long cost, long held) {
		public boolean enough() {
			return held >= cost;
		}
	}

	/** Why there is a copy to take, or why not. */
	public enum Why { EMPTY, NO_BENCH, NO_SIZE, BUSY, UNCOUNTED, SHORT, NEEDS_FUNCTION, READY }

	/**
	 * Whether the bench can make a copy right now, and the lines to show for it. {@code function}
	 * is what the copy will work as, from the block in the functional slot.
	 */
	public record Offer(Why why, List<Line> lines, Optional<Block> needs, Optional<Block> function) {
		public boolean ready() {
			return why == Why.READY;
		}

		static Offer of(Why why) {
			return new Offer(why, List.of(), Optional.empty(), Optional.empty());
		}
	}

	/** The original read off its item; empty if it is not a miniature. */
	public static Optional<Plan> plan(ItemStack original) {
		if (!original.is(Main.MINIATURE_ITEM)) return Optional.empty();
		return Looks.of(original).map(looks -> new Plan(looks, cells(looks.off())));
	}

	/**
	 * What a build stands for, by material, in cells of its own size: what breaking every block of
	 * it in the box would give back, which is what placing them took. In the order first met.
	 */
	public static Map<Item, Long> cells(Miniature build) {
		Map<Item, Long> cells = new LinkedHashMap<>();
		for (int y = 0; y < build.height(); y++) for (int z = 0; z < build.n(); z++) for (int x = 0; x < build.n(); x++) {
			BlockState state = build.at(x, y, z);
			if (state.isAir() || otherHalf(build, x, y, z, state)) continue;
			Item item = Visits.materialOf(state);
			if (item == Items.AIR) continue;
			cells.merge(item, (long) Visits.blocksIn(state), Long::sum);
		}
		return cells;
	}

	/**
	 * Whether this cell is the second half of a two-block thing whose first half is in the build:
	 * the top of a door or a tall flower, the head of a bed. Placing it spent one cell, and
	 * breaking either half gives one back, as the other goes with it.
	 */
	private static boolean otherHalf(Miniature build, int x, int y, int z, BlockState state) {
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
			BlockState below = build.at(x, y - 1, z);
			return below.is(state.getBlock()) && below.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
				&& below.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
		}
		if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD) {
			Direction toFoot = state.getValue(BedBlock.FACING).getOpposite();
			BlockState foot = build.at(x + toFoot.getStepX(), y, z + toFoot.getStepZ());
			return foot.is(state.getBlock()) && foot.getValue(BedBlock.PART) == BedPart.FOOT;
		}
		return false;
	}

	/** Cells cut {@code from} to a side, as cells cut {@code to} to a side, rounded up: never less material. */
	public static long convert(long cells, int from, int to) {
		if (from == to) return cells;
		long into = (long) to * to * to;
		long out = (long) from * from * from;
		return Math.ceilDiv(cells * into, out);
	}

	/** Whether the block in the functional slot will do for one the original works as. */
	public static boolean matches(Block needed, ItemStack slot) {
		if (!(slot.getItem() instanceof BlockItem item) || Functions.refusal(slot) != null) return false;
		if (isAnvil(needed)) return isAnvil(item.getBlock());
		return item.getBlock() == needed;
	}

	private static boolean isAnvil(Block block) {
		return block instanceof AnvilBlock && block.defaultBlockState().is(BlockTags.ANVIL);
	}

	/**
	 * What the bench can do with this original now. Nothing is changed; {@link #charge} checks
	 * again with what it is given and changes everything in one step.
	 *
	 * @param project the bench's project, or null if the bench is gone
	 */
	public static Offer offer(Ledger.Project project, Optional<Plan> plan) {
		if (plan.isEmpty()) return Offer.of(Why.EMPTY);
		if (project == null) return Offer.of(Why.NO_BENCH);
		if (project.n() == 0) return Offer.of(Why.NO_SIZE);
		Plan original = plan.get();
		List<Line> lines = new ArrayList<>();
		boolean enough = true;
		for (Map.Entry<Item, Long> material : original.cells().entrySet()) {
			Line line = new Line(material.getKey(), convert(material.getValue(), original.n(), project.n()), project.cells(material.getKey()));
			lines.add(line);
			enough &= line.enough();
		}
		lines = Collections.unmodifiableList(lines);
		Optional<Block> needs = original.needs();
		ItemStack slot = project.function();
		Optional<Block> function = needs.isPresent() && matches(needs.get(), slot)
			? Optional.of(((BlockItem) slot.getItem()).getBlock()) : Optional.empty();
		Why why = project.busy() ? Why.BUSY
			: lines.isEmpty() ? Why.UNCOUNTED
			: !enough ? Why.SHORT
			: needs.isPresent() && function.isEmpty() ? Why.NEEDS_FUNCTION
			: Why.READY;
		return new Offer(why, lines, needs, function);
	}

	/**
	 * Spend what a copy costs, if the bench can pay all of it right now: every material and the
	 * block in the functional slot, together, or nothing at all. What the copy works as, if it
	 * was paid for; empty if it was not.
	 */
	public static Optional<Optional<Block>> charge(Ledger ledger, int plot, Plan plan) {
		Offer offer = offer(ledger.project(plot), Optional.of(plan));
		if (!offer.ready()) return Optional.empty();
		for (Line line : offer.lines()) ledger.change(plot, line.item(), -line.cost());
		if (offer.function().isPresent()) ledger.setFunction(plot, ItemStack.EMPTY);
		return Optional.of(offer.function());
	}

	/** The copy: the original's looks, handle, format, maker and name, working as {@code function}, and holding nothing. */
	public static ItemStack copyOf(ItemStack original, Plan plan, Optional<Block> function) {
		ItemStack made = plan.looks().withFunction(function).toItem();
		Component name = original.get(DataComponents.CUSTOM_NAME);
		if (name != null) made.set(DataComponents.CUSTOM_NAME, name.copy());
		return made;
	}

	/** Why there is no copy, for the screen; null when there is one. */
	public static Component reason(Offer offer) {
		return switch (offer.why()) {
			case EMPTY -> Component.translatableWithFallback("minicraft-justfatlard.copy.empty", "Put a miniature in to copy it.");
			case NO_BENCH -> Component.translatableWithFallback("minicraft-justfatlard.copy.no_bench", "The bench is gone.");
			case NO_SIZE -> Component.translatableWithFallback("minicraft-justfatlard.copy.no_size", "Choose a size and load materials first.");
			case BUSY -> Component.translatableWithFallback("minicraft-justfatlard.copy.busy", "Somebody is in the box, or a build is being tried out: no copies until it is done.");
			case UNCOUNTED -> Component.translatableWithFallback("minicraft-justfatlard.copy.uncounted", "Nothing in it counts as material to copy.");
			case SHORT -> Component.translatableWithFallback("minicraft-justfatlard.copy.short", "Not enough material: load more of what is in red.");
			case NEEDS_FUNCTION -> Component.translatableWithFallback("minicraft-justfatlard.copy.needs_function", "Needs %s in the functional slot.",
				needName(offer.needs().get()));
			case READY -> Component.translatableWithFallback("minicraft-justfatlard.copy.ready", "Take the copy. The original stays.");
		};
	}

	/** What to put in the functional slot: "an anvil" of any wear for an anvil. */
	static Component needName(Block block) {
		return isAnvil(block) ? Component.translatableWithFallback("minicraft-justfatlard.copy.any_anvil", "an anvil") : block.getName();
	}
}
