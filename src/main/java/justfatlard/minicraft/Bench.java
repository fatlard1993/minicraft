package justfatlard.minicraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What a bench does: take materials in, hand a finished miniature out, give back what is left,
 * take a miniature in to be worked on again, and settle up when it is broken.
 */
public final class Bench {
	private Bench() {}

	/** Blocks that are never taken as material, beyond the ones that hold things. */
	public static final TagKey<Item> REFUSED = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Main.MOD_ID, "refused"));

	/** Slots a miniature gets from the one chest or barrel in it. */
	public static final int STORAGE_SLOTS = 27;

	/**
	 * Whether this can be loaded: a block, and not one that holds anything (a shulker box full of
	 * diamonds is the obvious way to smuggle), except an empty chest or barrel, which is how a
	 * miniature gets storage.
	 */
	public static boolean loadable(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem item)) return false;
		if (stack.is(REFUSED)) return false;
		if (isStorage(item.getBlock().defaultBlockState())) {
			ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
			return contents == null || contents.nonEmptyItemCopyStream().findAny().isEmpty();
		}
		return !(item.getBlock() instanceof EntityBlock);
	}

	/** A block that gives a miniature its storage. */
	public static boolean isStorage(BlockState state) {
		return state.is(Blocks.CHEST) || state.is(Blocks.BARREL);
	}

	public static int plotOf(Level level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof BenchBlockEntity bench ? bench.plot() : -1;
	}

	/** Load the whole stack held: N³ cells a block. */
	public static void load(ServerPlayer player, ServerLevel level, BlockPos pos, ItemStack stack) {
		int plot = plotOf(level, pos);
		Ledger ledger = Ledger.get(level.getServer());
		Ledger.Project project = ledger.project(plot);
		if (project == null) return;
		if (project.n() == 0) {
			say(player, "minicraft-justfatlard.load.no_size", "Choose how finely to cut first.");
			BenchScreen.open(player, level, pos);
			return;
		}
		if (!loadable(stack)) {
			say(player, "minicraft-justfatlard.load.refused", "That cannot go into a miniature.");
			return;
		}
		BlockItem item = (BlockItem) stack.getItem();
		int count = stack.getCount();
		ledger.load(plot, item, count);
		stack.consume(count, player);
		level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1.0F, 0.8F);
		player.sendOverlayMessage(Component.translatableWithFallback("minicraft-justfatlard.load.done",
			"%s %s in: %s cells", count, item.getName(new ItemStack(item)), project.cells(item)));
		refreshOccupant(level, project);
	}

	/** A finished miniature back into the bench, to be built on again: stood up in the box, its cells already paid for. */
	public static void edit(ServerPlayer player, ServerLevel level, BlockPos pos, ItemStack stack) {
		int plot = plotOf(level, pos);
		Ledger ledger = Ledger.get(level.getServer());
		Ledger.Project project = ledger.project(plot);
		ServerLevel workshop = Workshop.level(level.getServer());
		Optional<Looks> held = Looks.of(stack);
		if (project == null || workshop == null || held.isEmpty()) return;
		Miniature miniature = held.get().off();
		ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
		if (contents != null && contents.nonEmptyItemCopyStream().findAny().isPresent()) {
			say(player, "minicraft-justfatlard.edit.not_empty", "Empty it first.");
			return;
		}
		if (project.busy()) {
			say(player, "minicraft-justfatlard.busy", "Somebody is building at this bench.");
			return;
		}
		if (project.n() != 0 && project.n() != miniature.n()) {
			player.sendOverlayMessage(Component.translatableWithFallback("minicraft-justfatlard.edit.size",
				"That miniature is cut %s to a side; this bench cuts %s.", miniature.n(), project.n()));
			return;
		}
		if (!boxEmpty(workshop, plot, miniature.n())) {
			say(player, "minicraft-justfatlard.edit.full", "Finish what is on this bench first.");
			return;
		}
		if (project.n() == 0) ledger.setSize(plot, miniature.n());
		ledger.setRedstone(plot, held.get().respondsToRedstone());
		ledger.setHandle(plot, held.get().handle().orElse(null));
		Workshop.prepare(workshop, plot, miniature.n());
		miniature.build(workshop, Workshop.origin(plot));
		stack.consume(1, player);
		level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1.0F, 1.2F);
		say(player, "minicraft-justfatlard.edit.done", "On the bench to be worked on. Step in to change it.");
	}

	/**
	 * Read the box into a miniature, hand it over, and empty the box. With behaviours chosen, the
	 * looks are captured over a few ticks and the miniature comes when they are done.
	 */
	public static boolean finish(ServerPlayer player, ServerLevel level, BlockPos pos) {
		int plot = plotOf(level, pos);
		Ledger ledger = Ledger.get(level.getServer());
		Ledger.Project project = ledger.project(plot);
		ServerLevel workshop = Workshop.level(level.getServer());
		if (project == null || workshop == null || project.n() == 0) return false;
		if (project.busy()) return say(player, "minicraft-justfatlard.busy", "Somebody is building at this bench.");

		// The floor laid as it should be first: a capture cut short by a stopped server could have
		// left it as redstone blocks, and the look as built would be captured powered.
		Workshop.prepare(workshop, plot, project.n());
		Optional<Miniature> captured = Miniature.capture(workshop, Workshop.origin(plot), project.n());
		if (captured.isEmpty()) return say(player, "minicraft-justfatlard.finish.too_many", "Too many different blocks to fit in one miniature.");
		if (captured.get().isEmpty()) return say(player, "minicraft-justfatlard.finish.empty", "Nothing has been built yet.");
		int stores = storesIn(captured.get());
		if (stores > 1) return say(player, "minicraft-justfatlard.finish.two_stores", "One chest or barrel to a miniature.");
		int storage = stores == 1 ? STORAGE_SLOTS : 0;

		BlockPos handle = project.handle();
		if (handle != null && captured.get().at(handle.getX(), handle.getY(), handle.getZ()).isAir()) {
			say(player, "minicraft-justfatlard.finish.no_handle", "The handle is gone; finishing without it.");
			handle = null;
		}
		if (project.redstone() || handle != null) {
			Capture.start(player, level, pos, plot, project, handle, storage);
			say(player, "minicraft-justfatlard.finish.capturing", "Trying it out...");
			return true;
		}

		Workshop.clear(workshop, plot, project.n());
		handOver(level, pos, plot, player.getUUID(), new Looks(captured.get(), Optional.empty(), Optional.empty(), Optional.empty(),
			Optional.empty(), storage, Optional.of(player.getName().getString())));
		return true;
	}

	private static int storesIn(Miniature miniature) {
		int count = 0;
		for (int i = 0; i < miniature.cells().length; i++) {
			int cell = miniature.cells()[i] & 0xFF;
			if (cell != 0 && isStorage(miniature.palette().get(cell - 1))) count++;
		}
		return count;
	}

	/** The finished miniature to whoever finished it, or onto the bench if they have gone; a fresh start if nothing is left. */
	static void handOver(ServerLevel level, BlockPos pos, int plot, UUID who, Looks looks) {
		Ledger ledger = Ledger.get(level.getServer());
		Ledger.Project project = ledger.project(plot);
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(who);
		ItemStack made = looks.toItem();
		if (player != null) give(player, made);
		else Block.popResource(level, pos.above(), made);
		if (project != null && !project.hasMaterial()) ledger.setSize(plot, 0);
		Capture.playDone(level, pos);
		if (player != null) say(player, "minicraft-justfatlard.finish.done", "Finished.");
	}

	/** Every whole block left, back to the player; the crumbs stay for the next build. */
	public static void withdraw(ServerPlayer player, ServerLevel level, BlockPos pos) {
		int plot = plotOf(level, pos);
		Ledger ledger = Ledger.get(level.getServer());
		if (ledger.project(plot) == null) return;
		Map<Item, Long> taken = ledger.withdraw(plot);
		taken.forEach((item, blocks) -> {
			for (long left = blocks; left > 0; ) {
				int count = (int) Math.min(left, item.getDefaultMaxStackSize());
				give(player, new ItemStack(item, count));
				left -= count;
			}
		});
		refreshOccupant(level, ledger.project(plot));
	}

	/** Throw the crumbs away and forget the size, so another can be chosen. Only with an empty box and less than a block of anything. */
	public static boolean resize(ServerPlayer player, ServerLevel level, BlockPos pos) {
		int plot = plotOf(level, pos);
		Ledger ledger = Ledger.get(level.getServer());
		Ledger.Project project = ledger.project(plot);
		ServerLevel workshop = Workshop.level(level.getServer());
		if (project == null || workshop == null) return false;
		if (project.busy()) return say(player, "minicraft-justfatlard.busy", "Somebody is building at this bench.");
		if (!mayResize(workshop, plot, project)) return say(player, "minicraft-justfatlard.resize.not_empty", "Finish the build and take out what is left first.");
		ledger.sweep(plot);
		return true;
	}

	static boolean mayResize(ServerLevel workshop, int plot, Ledger.Project project) {
		return project.n() == 0 || project.onlyCrumbs() && boxEmpty(workshop, plot, project.n());
	}

	/**
	 * The bench is gone: every whole block comes out, and whatever stands in the box comes out as
	 * a miniature, finished or not, so it can go into another bench and carry on. The plot is
	 * given back.
	 */
	static void broken(ServerLevel level, BlockPos pos, int plot) {
		Ledger ledger = Ledger.get(level.getServer());
		Ledger.Project project = ledger.project(plot);
		ServerLevel workshop = Workshop.level(level.getServer());
		if (project == null || workshop == null) return;
		if (project.n() > 0) {
			for (Map.Entry<Item, Long> taken : ledger.withdraw(plot).entrySet()) {
				for (long left = taken.getValue(); left > 0; ) {
					int count = (int) Math.min(left, taken.getKey().getDefaultMaxStackSize());
					Block.popResource(level, pos, new ItemStack(taken.getKey(), count));
					left -= count;
				}
			}
			Miniature.capture(workshop, Workshop.origin(plot), project.n()).filter(m -> !m.isEmpty())
				.ifPresent(m -> Block.popResource(level, pos, m.toItem()));
			Workshop.clear(workshop, plot, project.n());
		}
		ledger.close(plot);
	}

	public static boolean occupied(Level level, BlockPos pos) {
		if (!(level instanceof ServerLevel server)) return false;
		Ledger.Project project = Ledger.get(server.getServer()).project(plotOf(level, pos));
		return project != null && project.busy();
	}

	static boolean boxEmpty(ServerLevel workshop, int plot, int n) {
		return Miniature.capture(workshop, Workshop.origin(plot), n).map(Miniature::isEmpty).orElse(false);
	}

	private static void refreshOccupant(ServerLevel level, Ledger.Project project) {
		if (project == null) return;
		UUID occupant = project.occupant();
		ServerPlayer builder = occupant == null ? null : level.getServer().getPlayerList().getPlayer(occupant);
		if (builder != null) Visits.refreshPack(builder);
	}

	private static void give(ServerPlayer player, ItemStack stack) {
		if (!player.getInventory().add(stack)) player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
	}

	static boolean say(ServerPlayer player, String key, String fallback) {
		player.sendOverlayMessage(Component.translatableWithFallback(key, fallback));
		return false;
	}
}
