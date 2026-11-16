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
	 * instead, and the miniature works as it.
	 */
	public static boolean loadable(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem item)) return false;
		if (stack.is(REFUSED)) return false;
		return !(item.getBlock() instanceof EntityBlock);
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
		if (project.capturing()) return say(player, "minicraft-justfatlard.busy", "Somebody is building at this bench.");
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
		if (holdsAnything(stack)) {
			say(player, "minicraft-justfatlard.edit.not_empty", "Empty it first.");
			return;
		}
		Optional<Block> function = held.get().function();
		if (function.isPresent() && !project.function().isEmpty()) {
			say(player, "minicraft-justfatlard.edit.function_full", "Take the block out of the functional slot first.");
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
		if (project.n() != 0 && project.slab() != miniature.isSlab()) {
			say(player, miniature.isSlab() ? "minicraft-justfatlard.edit.slab" : "minicraft-justfatlard.edit.block",
				miniature.isSlab() ? "That miniature is a slab; this bench makes blocks." : "That miniature is a block; this bench makes slabs.");
			return;
		}
		if (!boxEmpty(workshop, plot, miniature.n(), miniature.n())) {
			say(player, "minicraft-justfatlard.edit.full", "Finish what is on this bench first.");
			return;
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
		return project.n() == 0 || project.onlyCrumbs() && boxEmpty(workshop, plot, project.n(), project.n());
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
