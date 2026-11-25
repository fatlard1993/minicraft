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

import java.util.ArrayList;
import java.util.List;
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

	/** The longest name a miniature takes, an anvil's limit. */
	public static final int MAX_NAME = 50;

	/**
	 * Whether this can be loaded: a block, and not one that holds anything (a shulker box full of
	 * diamonds is the obvious way to smuggle). A chest or a furnace goes in the functional slot
	 * instead, and the miniature works as it. Whether it can stand in the box is asked as it is
	 * loaded, of what that bench holds.
	 */
	public static boolean loadable(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem item)) return false;
		if (stack.is(REFUSED)) return false;
		return !(item.getBlock() instanceof EntityBlock);
	}

	/** Whether this block could stand in the plot's box: on its floor, or on, under or beside something loaded. */
	static boolean standsIn(ServerLevel level, Ledger.Project project, Block block) {
		List<BlockState> around = new ArrayList<>();
		around.add(Workshop.FOOTPRINT);
		for (Item material : project.materials()) {
			if (material instanceof BlockItem loaded) around.add(loaded.getBlock().defaultBlockState());
		}
		return Footing.stands(Workshop.level(level.getServer()), block, around);
	}

	/** A name as typed, made fit for a miniature: no formatting codes, no edges of space, an anvil's length at most. */
	public static String cleanName(String typed) {
		if (typed == null) return "";
		String plain = net.minecraft.ChatFormatting.stripFormatting(typed);
		plain = net.minecraft.util.StringUtil.filterText(plain == null ? "" : plain).strip();
		return plain.length() > MAX_NAME ? plain.substring(0, MAX_NAME).strip() : plain;
	}

	/** What a miniature is called on its item, or nothing. */
	static String nameOf(ItemStack stack) {
		Component name = stack.get(DataComponents.CUSTOM_NAME);
		return name == null ? "" : cleanName(name.getString());
	}

	/** Name the bench's next miniature; blank for none. */
	public static void name(ServerLevel level, BlockPos pos, String typed) {
		int plot = plotOf(level, pos);
		Ledger ledger = Ledger.get(level.getServer());
		Ledger.Project project = ledger.project(plot);
		if (project == null || project.capturing()) return;
		ledger.setName(plot, cleanName(typed));
	}

	/**
	 * Put a block in the functional slot, the whole of one: the miniature finished next works as
	 * it. Says why not and returns false if it cannot go in.
	 */
	/** What a player is told when a handle and a function would meet: a miniature has one or the other. */
	static final String HANDLE_FIRST = "It has a handle: a miniature has a handle or a block to work as, not both. Click the handle with the stick to unmark it first.";
	static final String FUNCTION_FIRST = "It works as a block: a miniature has a handle or a block to work as, not both. Take the block out of the functional slot first.";

	public static boolean putFunction(ServerPlayer player, ServerLevel level, BlockPos pos, ItemStack stack) {
		int plot = plotOf(level, pos);
		Ledger ledger = Ledger.get(level.getServer());
		Ledger.Project project = ledger.project(plot);
		if (project == null) return false;
		if (project.capturing()) return refuseBusy(player, project);
		Component refused = Functions.refusal(stack);
		if (refused != null) {
			player.sendOverlayMessage(refused);
			return false;
		}
		if (!project.function().isEmpty()) return say(player, "minicraft-justfatlard.function.full", "Something is in the functional slot already.");
		if (project.handle() != null) return say(player, "minicraft-justfatlard.function.has_handle", HANDLE_FIRST);
		ledger.setFunction(plot, stack.split(1));
		level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1.0F, 1.0F);
		player.sendOverlayMessage(Component.translatableWithFallback("minicraft-justfatlard.function.in",
			"The miniature will work as %s.", project.function().getHoverName()));
		return true;
	}

	/** The block in the functional slot back to the player, before it is spent. */
	public static void takeFunction(ServerPlayer player, ServerLevel level, BlockPos pos) {
		int plot = plotOf(level, pos);
		Ledger ledger = Ledger.get(level.getServer());
		Ledger.Project project = ledger.project(plot);
		if (project == null || project.capturing() || project.function().isEmpty()) return;
		ItemStack back = project.function();
		ledger.setFunction(plot, ItemStack.EMPTY);
		give(player, back);
	}

	public static int plotOf(Level level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof BenchBlockEntity bench ? bench.plot() : -1;
	}

	/** Load one of what is held: N³ cells. Returns whether it went in. */
	public static boolean load(ServerPlayer player, ServerLevel level, BlockPos pos, ItemStack stack) {
		int plot = plotOf(level, pos);
		Ledger ledger = Ledger.get(level.getServer());
		Ledger.Project project = ledger.project(plot);
		if (project == null) return false;
		if (project.n() == 0) {
			say(player, "minicraft-justfatlard.load.no_size", "Choose how finely to cut first.");
			BenchScreen.open(player, level, pos);
			return false;
		}
		if (!loadable(stack)) {
			say(player, "minicraft-justfatlard.load.refused", "That cannot go into a miniature.");
			return false;
		}
		BlockItem item = (BlockItem) stack.getItem();
		if (!standsIn(level, project, item.getBlock())) {
			say(player, "minicraft-justfatlard.load.no_footing", "Nothing loaded can hold that up: load what it stands on first.");
			return false;
		}
		ledger.load(plot, item, 1);
		stack.consume(1, player);
		level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1.0F, 0.8F);
		player.sendOverlayMessage(Component.translatableWithFallback("minicraft-justfatlard.load.done",
			"%s in: %s cells", item.getName(new ItemStack(item)), project.cells(item)));
		Visits.refreshBuilders(level.getServer(), plot);
		return true;
	}

	/** A finished miniature back into the bench, to be built on again: stood up in the box, its cells already paid for. */
	public static boolean edit(ServerPlayer player, ServerLevel level, BlockPos pos, ItemStack stack) {
		int plot = plotOf(level, pos);
		Ledger ledger = Ledger.get(level.getServer());
		Ledger.Project project = ledger.project(plot);
		ServerLevel workshop = Workshop.level(level.getServer());
		Optional<Looks> held = Looks.of(stack);
		if (project == null || workshop == null || held.isEmpty()) return false;
		Miniature miniature = held.get().off();
		if (holdsAnything(stack)) {
			say(player, "minicraft-justfatlard.edit.not_empty", "Empty it first.");
			return false;
		}
		Optional<Block> function = held.get().function();
		if (function.isPresent() && !project.function().isEmpty()) {
			say(player, "minicraft-justfatlard.edit.function_full", "Take the block out of the functional slot first.");
			return false;
		}
		if (project.busy()) {
			return refuseBusy(player, project);
		}
		if (project.n() != 0 && project.n() != miniature.n()) {
			player.sendOverlayMessage(Component.translatableWithFallback("minicraft-justfatlard.edit.size",
				"That miniature is cut %s to a side; this bench cuts %s.", miniature.n(), project.n()));
			return false;
		}
		if (project.n() != 0 && project.slab() != miniature.isSlab()) {
			say(player, miniature.isSlab() ? "minicraft-justfatlard.edit.slab" : "minicraft-justfatlard.edit.block",
				miniature.isSlab() ? "That miniature is a slab; this bench makes blocks." : "That miniature is a block; this bench makes slabs.");
			return false;
		}
		if (!boxEmpty(workshop, plot, miniature.n(), miniature.n())) {
			say(player, "minicraft-justfatlard.edit.full", "Finish what is on this bench first.");
			return false;
		}
		if (project.n() == 0) {
			ledger.setSize(plot, miniature.n());
			ledger.setSlab(plot, miniature.isSlab());
		}
		ledger.setRedstone(plot, held.get().respondsToRedstone());
		// One made with both before that was settled comes back with its function only.
		ledger.setHandle(plot, held.get().hasHandle() ? held.get().handle().orElse(null) : null);
		ledger.setName(plot, nameOf(stack));
		// What it worked as goes back in the functional slot, to keep or take out.
		function.ifPresent(block -> ledger.setFunction(plot, new ItemStack(block)));
		Workshop.prepare(workshop, plot, miniature.n());
		miniature.build(workshop, Workshop.origin(plot));
		stack.consume(1, player);
		level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1.0F, 1.2F);
		say(player, "minicraft-justfatlard.edit.done", "On the bench to be worked on. Step in to change it.");
		return true;
	}

	/**
	 * A finished miniature broken down into the bench's budget: what breaking every block of it in
	 * the box would give back ({@link Copying#cells}), cut again to the bench's size with nothing
	 * lost ({@link Ledger#credit}). A bench with no size yet takes the miniature's. The block it
	 * worked as goes back to the player; its handle and name go with it.
	 */
	public static boolean salvage(ServerPlayer player, ServerLevel level, BlockPos pos, ItemStack stack) {
		int plot = plotOf(level, pos);
		Ledger ledger = Ledger.get(level.getServer());
		Ledger.Project project = ledger.project(plot);
		Optional<Looks> held = Looks.of(stack);
		if (project == null || held.isEmpty()) return false;
		if (holdsAnything(stack)) return say(player, "minicraft-justfatlard.edit.not_empty", "Empty it first.");
		if (project.capturing()) return refuseBusy(player, project);
		Miniature miniature = held.get().off();
		Map<Item, Long> cells = Copying.cells(miniature);
		Optional<Block> function = held.get().function();
		if (cells.isEmpty() && function.isEmpty()) {
			return say(player, "minicraft-justfatlard.salvage.nothing", "Nothing in it counts as material.");
		}
		if (project.n() == 0) {
			ledger.setSize(plot, miniature.n());
			ledger.setSlab(plot, miniature.isSlab());
		}
		cells.forEach((item, count) -> ledger.credit(plot, item, count, miniature.n()));
		stack.consume(1, player);
		function.ifPresent(block -> give(player, new ItemStack(block)));
		level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 1.0F, 0.8F);
		say(player, "minicraft-justfatlard.salvage.done", "Broken down: what it was built from is in the bench.");
		Visits.refreshBuilders(level.getServer(), plot);
		return true;
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
		// Nobody is brought out to finish: a build is finished when everyone building it has
		// stepped out, and the one pressing it is told who has not.
		if (project.busy()) return refuseBusy(player, project);

		// The floor laid as it should be first: a capture cut short by a stopped server could have
		// left it as redstone blocks, and the look as built would be captured powered.
		Workshop.prepare(workshop, plot, project.n());
		Optional<Miniature> captured = Miniature.capture(workshop, Workshop.origin(plot), project.n(), project.height());
		if (captured.isEmpty()) return say(player, "minicraft-justfatlard.finish.too_many", "Too many different blocks to fit in one miniature.");
		if (captured.get().isEmpty()) return say(player, "minicraft-justfatlard.finish.empty", "Nothing has been built yet.");

		BlockPos handle = project.handle();
		if (handle != null && !project.function().isEmpty()) return say(player, "minicraft-justfatlard.finish.both", FUNCTION_FIRST);
		if (handle != null && captured.get().at(handle.getX(), handle.getY(), handle.getZ()).isAir()) {
			say(player, "minicraft-justfatlard.finish.no_handle", "The handle is gone; finishing without it.");
			handle = null;
		}
		if (project.redstone() || handle != null) {
			Capture.start(player, level, pos, plot, project, handle);
			say(player, "minicraft-justfatlard.finish.capturing", "Trying it out...");
			return true;
		}

		Workshop.clear(workshop, plot, project.n());
		handOver(level, pos, plot, player.getUUID(), new Looks(captured.get(), Optional.empty(), Optional.empty(), Optional.empty(),
			Optional.empty(), Optional.empty(), Optional.of(player.getName().getString())));
		return true;
	}

	/**
	 * The finished miniature to whoever finished it, or onto the bench if they have gone; a fresh
	 * start if nothing is left. The block in the functional slot is spent on it here, and the name
	 * given to it, both in the same step as the miniature is made, so neither is ever had twice.
	 */
	static void handOver(ServerLevel level, BlockPos pos, int plot, UUID who, Looks looks) {
		Ledger ledger = Ledger.get(level.getServer());
		Ledger.Project project = ledger.project(plot);
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(who);
		String name = "";
		if (project != null) {
			if (project.function().getItem() instanceof BlockItem item && Functions.supported(item.getBlock())) {
				looks = looks.withFunction(Optional.of(item.getBlock()));
				ledger.setFunction(plot, ItemStack.EMPTY);
			}
			name = project.name();
			ledger.setName(plot, "");
		}
		ItemStack made = looks.toItem();
		if (!name.isEmpty()) made.set(DataComponents.CUSTOM_NAME, Component.literal(name));
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
		// Only what is in the ledger comes out. Builders' windows are views of it, never material,
		// so whatever they show, they show less now, or nothing.
		Visits.refreshBuilders(level.getServer(), plot);
	}

	/**
	 * Whether the size or the format may change now: nobody in the box, no try-out running, and
	 * nothing built in it, as cells placed at one size mean nothing at another. Says why not.
	 */
	public static boolean resize(ServerPlayer player, ServerLevel level, BlockPos pos) {
		int plot = plotOf(level, pos);
		Ledger.Project project = Ledger.get(level.getServer()).project(plot);
		ServerLevel workshop = Workshop.level(level.getServer());
		if (project == null || workshop == null) return false;
		if (project.busy()) return refuseBusy(player, project);
		if (project.n() != 0 && !boxEmpty(workshop, plot, project.n(), project.n())) {
			return say(player, "minicraft-justfatlard.resize.built", "Something is built in the box: finish it, or break it all down, before changing the size.");
		}
		return true;
	}

	/**
	 * Cut the bench to another size. With nothing chosen yet it is simply chosen; with material
	 * loaded, everything held is cut again to the new size, nothing lost ({@link Ledger#resize}).
	 */
	public static boolean changeSize(ServerPlayer player, ServerLevel level, BlockPos pos, int size) {
		int plot = plotOf(level, pos);
		Ledger ledger = Ledger.get(level.getServer());
		Ledger.Project project = ledger.project(plot);
		if (project == null || size < Miniature.MIN_SIZE || size > Miniature.MAX_SIZE) return false;
		// A slab is exactly half a block, so it is cut to an even size.
		if (project.slab() && size % 2 != 0) return say(player, "minicraft-justfatlard.resize.even", "A slab is cut to an even size.");
		if (project.n() == size) return true;
		if (project.n() == 0) {
			ledger.setSize(plot, size);
			return true;
		}
		if (!resize(player, level, pos)) return false;
		int was = project.n();
		List<Item> slivered = ledger.resize(plot, size);
		// The box is empty, so a handle marked in it marks nothing now.
		ledger.setHandle(plot, null);
		level.playSound(null, pos, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 1.0F, 1.0F);
		if (slivered.isEmpty()) {
			player.sendOverlayMessage(Component.translatableWithFallback("minicraft-justfatlard.resize.done",
				"Cut again from %s to %s a side: everything loaded came through.", was, size));
		} else {
			player.sendOverlayMessage(Component.translatableWithFallback("minicraft-justfatlard.resize.sliver",
				"Cut again to %s: a sliver of %s too fine to cut at %s is kept aside, and comes back at a size that cuts it.",
				size, names(slivered), size));
		}
		return true;
	}

	/** Block or slab. Once material is in, only as the size may change: nobody in the box and nothing built in it. */
	public static boolean changeFormat(ServerPlayer player, ServerLevel level, BlockPos pos) {
		int plot = plotOf(level, pos);
		Ledger ledger = Ledger.get(level.getServer());
		Ledger.Project project = ledger.project(plot);
		if (project == null) return false;
		if (project.n() != 0) {
			if (!resize(player, level, pos)) return false;
			if (!project.slab() && project.n() % 2 != 0) {
				return say(player, "minicraft-justfatlard.resize.slab_even", "A slab is cut to an even size: choose an even size first.");
			}
			ledger.setHandle(plot, null);
		}
		ledger.setSlab(plot, !project.slab());
		return true;
	}

	private static String names(List<Item> items) {
		List<String> names = new ArrayList<>();
		for (Item item : items) names.add(new ItemStack(item).getHoverName().getString());
		return String.join(", ", names);
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
			Miniature.capture(workshop, Workshop.origin(plot), project.n(), project.height()).filter(m -> !m.isEmpty())
				.ifPresent(m -> Block.popResource(level, pos, m.toItem()));
			Workshop.clear(workshop, plot, project.n());
		}
		if (!project.function().isEmpty()) Block.popResource(level, pos, project.function());
		ledger.close(plot);
	}

	/** A plot whose bench went with its world (a demo closing): its box emptied and the plot given back, nothing handed out. */
	static void forget(net.minecraft.server.MinecraftServer server, int plot) {
		Ledger ledger = Ledger.get(server);
		Ledger.Project project = ledger.project(plot);
		if (project == null) return;
		ServerLevel workshop = Workshop.level(server);
		if (project.n() > 0 && workshop != null) Workshop.clear(workshop, plot, project.n());
		ledger.close(plot);
	}

	public static boolean occupied(Level level, BlockPos pos) {
		if (!(level instanceof ServerLevel server)) return false;
		Ledger.Project project = Ledger.get(server.getServer()).project(plotOf(level, pos));
		return project != null && project.busy();
	}

	/** Whether nothing stands in the box, read this high: a full N, to be sure of a slab's box too. */
	static boolean boxEmpty(ServerLevel workshop, int plot, int n, int height) {
		return Miniature.capture(workshop, Workshop.origin(plot), n, height).map(Miniature::isEmpty).orElse(false);
	}

	/** Whether a miniature item holds anything in what it works as: a chest's things, a furnace's, a jukebox's disc. */
	public static boolean holdsAnything(ItemStack stack) {
		ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
		if (contents != null && contents.nonEmptyItemCopyStream().findAny().isPresent()) return true;
		net.minecraft.world.item.component.CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return false;
		net.minecraft.nbt.CompoundTag held = data.copyTag().getCompound(MiniatureBlockEntity.FUNCTION_KEY).orElse(null);
		if (held == null) return false;
		return !held.getListOrEmpty("Items").isEmpty() || held.contains("RecordItem");
	}

	/** The most names said in one line; past that, how many more. */
	private static final int NAMED = 4;

	/** Who is in the box, by name: "Alex, Sam and 3 more". */
	public static String whoInside(Ledger.Project project) {
		List<String> names = new ArrayList<>();
		for (String name : project.occupants().values()) names.add(name.isEmpty() ? "somebody" : name);
		if (names.size() <= NAMED) return String.join(", ", names);
		return Component.translatableWithFallback("minicraft-justfatlard.inside.more", "%s and %s more",
			String.join(", ", names.subList(0, NAMED)), names.size() - NAMED).getString();
	}

	/** Why a busy bench will not do that now, naming who is still in its box. */
	public static Component busyMessage(Ledger.Project project) {
		if (project.capturing()) return Component.translatableWithFallback("minicraft-justfatlard.busy.trying", "A build is being tried out at this bench.");
		return project.occupants().size() == 1
			? Component.translatableWithFallback("minicraft-justfatlard.busy.one", "%s is still in the box: they step out first.", whoInside(project))
			: Component.translatableWithFallback("minicraft-justfatlard.busy.many", "Still in the box: %s. Everyone steps out first.", whoInside(project));
	}

	static boolean refuseBusy(ServerPlayer player, Ledger.Project project) {
		player.sendOverlayMessage(busyMessage(project));
		return false;
	}

	/** The message for a bench that cannot be broken now, for the break event: who is in its box. */
	static void tellBusy(ServerPlayer player, Level level, BlockPos pos) {
		Ledger.Project project = Ledger.get(player.level().getServer()).project(plotOf(level, pos));
		if (project != null) refuseBusy(player, project);
	}

	private static void give(ServerPlayer player, ItemStack stack) {
		if (!player.getInventory().add(stack)) player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
	}

	static boolean say(ServerPlayer player, String key, String fallback) {
		player.sendOverlayMessage(Component.translatableWithFallback(key, fallback));
		return false;
	}
}
