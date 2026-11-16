package justfatlard.minicraft;

import justfatlard.pandorical.api.ComponentBuilder;
import justfatlard.pandorical.api.ComponentType;
import justfatlard.pandorical.api.ComponentUpdateBuilder;
import justfatlard.pandorical.protocol.ComponentUpdate;
import justfatlard.pandorical.screen.PandoricalMenu;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.ScreenApi;
import justfatlard.pandorical.api.ScreenBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The bench, opened: a size and a format (block or slab) to choose while it has none, and after
 * that what it holds, the functional slot, a name for the miniature, and what to do with it.
 * Drawn by Pandorical, which every player of this mod has.
 *
 * <p>The functional slot is a real slot, and the ledger is what it holds: whatever is put in is
 * the project's, wherever the bench's chunk is, until it is spent on a miniature or taken out.
 *
 * <p>Two tabs, swapped in place without reopening: <b>Build</b>, everything above, and
 * <b>Copy</b>, a slot for a miniature and a slot its copy shows in while the bench can pay for it
 * ({@link Copying}), with the functional slot beside them. Both are real server slots: the
 * miniature put in is the viewer's own, given back when the screen closes, and the copy is made
 * and paid for at the moment it is taken, checked again then.
 */
public final class BenchScreen {
	private BenchScreen() {}

	private static final String SIZE = Main.MOD_ID + ":size";
	private static final String MAIN = Main.MOD_ID + ":bench";
	private static final double REACH = 8.0;

	private static final int PAD = 8;
	private static final int WIDTH = 300;
	private static final int BUTTON = 36;
	private static final int GAP = 4;
	private static final int COLUMNS = 5;
	private static final int SHOWN_MATERIALS = 8;
	private static final int SHOWN_COST = 6;
	private static final int TAB = 44;
	/** Where a grid not on the tab shown is put, slots and all, so they can be neither seen nor clicked. */
	private static final int AWAY = -10000;

	private static final String DARK = "#404040";
	private static final String GREY = "#707070";
	private static final String RED = "#A02020";
	private static final String GREEN = "#2E7D32";

	private record Pending(ServerLevel level, BlockPos bench, String screenId) {}

	private static final Map<UUID, Pending> pending = new HashMap<>();
	/** The slots each viewer of a bench has open, for the tabs and for keeping the copy's lines current. */
	private static final Map<UUID, BenchSlots> viewing = new HashMap<>();

	public static void register() {
		ScreenApi screens = PandoricalApi.screens();
		for (int n = Miniature.MIN_SIZE; n <= Miniature.MAX_SIZE; n++) {
			int size = n;
			screens.onAction(SIZE, "n" + n, (player, data) -> act(player, (level, bench) -> {
				Ledger ledger = Ledger.get(level.getServer());
				int plot = Bench.plotOf(level, bench);
				Ledger.Project project = ledger.project(plot);
				// A slab is exactly half a block, so it is cut to an even size.
				if (project != null && project.n() == 0 && (!project.slab() || size % 2 == 0)) ledger.setSize(plot, size);
				open(player, level, bench);
			}));
		}
		screens.onAction(SIZE, "format", (player, data) -> act(player, (level, bench) -> {
			Ledger ledger = Ledger.get(level.getServer());
			int plot = Bench.plotOf(level, bench);
			Ledger.Project project = ledger.project(plot);
			if (project != null && project.n() == 0) ledger.setSlab(plot, !project.slab());
			open(player, level, bench);
		}));
		// Typed into as it changes; the screen stays open.
		screens.onAction(MAIN, "name", (player, data) -> {
			Pending asked = pending.get(player.getUUID());
			if (asked == null || player.level() != asked.level() || player.distanceToSqr(Vec3.atCenterOf(asked.bench())) > REACH * REACH) return;
			if (asked.level().getBlockEntity(asked.bench()) instanceof BenchBlockEntity) Bench.name(asked.level(), asked.bench(), data.getOrDefault("text", ""));
		});
		screens.onAction(MAIN, "enter", (player, data) -> act(player, (level, bench) -> Visits.enter(player, Bench.plotOf(level, bench))));
		screens.onAction(MAIN, "finish", (player, data) -> act(player, (level, bench) -> {
			Bench.finish(player, level, bench);
			open(player, level, bench);
		}));
		screens.onAction(MAIN, "withdraw", (player, data) -> act(player, (level, bench) -> {
			Bench.withdraw(player, level, bench);
			open(player, level, bench);
		}));
		screens.onAction(MAIN, "redstone", (player, data) -> act(player, (level, bench) -> {
			Ledger ledger = Ledger.get(level.getServer());
			int plot = Bench.plotOf(level, bench);
			Ledger.Project project = ledger.project(plot);
			if (project != null && !project.busy()) ledger.setRedstone(plot, !project.redstone());
			open(player, level, bench);
		}));
		screens.onAction(MAIN, "resize", (player, data) -> act(player, (level, bench) -> {
			if (Bench.resize(player, level, bench)) open(player, level, bench);
		}));
		screens.onAction(MAIN, "tab_build", (player, data) -> showTab(player, false));
		screens.onAction(MAIN, "tab_copy", (player, data) -> showTab(player, true));
		// After every click on a slot: the copy's lines as they now are.
		screens.onSlotChange(MAIN, (player, slot, stack) -> {
			BenchSlots open = viewing.get(player.getUUID());
			if (open != null && slot == BenchSlots.SIZE - 1) refresh(open);
		});
		screens.onClose(SIZE, player -> pending.remove(player.getUUID()));
		screens.onClose(MAIN, player -> pending.remove(player.getUUID()));
		screens.onContainerRemoved(MAIN, player -> pending.remove(player.getUUID()));
	}

	public static void forget(UUID player) {
		pending.remove(player);
		viewing.remove(player);
	}

	private interface Act {
		void at(ServerLevel level, BlockPos bench);
	}

	private static void act(ServerPlayer player, Act act) {
		Pending asked = pending.remove(player.getUUID());
		if (asked == null) return;
		PandoricalApi.screens().close(player, asked.screenId());
		if (player.level() != asked.level() || player.distanceToSqr(Vec3.atCenterOf(asked.bench())) > REACH * REACH) return;
		if (!(asked.level().getBlockEntity(asked.bench()) instanceof BenchBlockEntity)) return;
		act.at(asked.level(), asked.bench());
	}

	public static void open(ServerPlayer player, ServerLevel level, BlockPos bench) {
		if (!PandoricalApi.hasCapability(player, "screens")) {
			Bench.say(player, "minicraft-justfatlard.needs_pandorical", "The bench needs the Pandorical mod on your client.");
			return;
		}
		Ledger.Project project = Ledger.get(level.getServer()).project(Bench.plotOf(level, bench));
		if (project == null) return;
		int plot = Bench.plotOf(level, bench);
		if (project.n() == 0) {
			ScreenBuilder screen = sizeScreen(project);
			pending.put(player.getUUID(), new Pending(level, bench.immutable(), screen.screenId()));
			PandoricalApi.screens().open(player, screen.build());
			return;
		}
		Layout layout = new Layout();
		ScreenBuilder screen = mainScreen(level, plot, project, layout);
		layout.screenId = screen.screenId();
		pending.put(player.getUUID(), new Pending(level, bench.immutable(), screen.screenId()));
		BenchSlots slots = new BenchSlots(player, level, bench.immutable(), plot, layout);
		PandoricalApi.screens().openContainer(player, screen.build(), slots, Set.of());
		if (player.containerMenu instanceof PandoricalMenu menu && menu.getModContainer() == slots) viewing.put(player.getUUID(), slots);
	}

	private static ScreenBuilder sizeScreen(Ledger.Project project) {
		int rows = (Miniature.MAX_SIZE - Miniature.MIN_SIZE + COLUMNS) / COLUMNS;
		int width = PAD * 2 + COLUMNS * BUTTON + (COLUMNS - 1) * GAP;
		int sizesTop = 36 + 20 + GAP;
		int height = sizesTop + rows * (BUTTON + GAP) + PAD;
		ScreenBuilder screen = new ScreenBuilder(SIZE).size(width, height).title("Miniature bench");
		screen.panel("frame", 0, 0, width, height, Map.of());
		screen.text("heading", PAD, PAD, Map.of(
			ComponentType.PROP_TEXT, text("minicraft-justfatlard.size.heading", "Cut each block into how many to a side?"),
			ComponentType.PROP_COLOR, "#404040"));
		screen.text("note", PAD, PAD + 12, Map.of(
			ComponentType.PROP_TEXT, text("minicraft-justfatlard.size.note", "Finer is more detail, and more to build."),
			ComponentType.PROP_COLOR, "#707070"));
		screen.button("format", PAD, 36, width - PAD * 2, 20, Map.of(ComponentType.PROP_LABEL, project.slab()
			? text("minicraft-justfatlard.size.slab", "Makes: a slab, half a block (even sizes)")
			: text("minicraft-justfatlard.size.block", "Makes: a block")));
		int shown = 0;
		for (int n = Miniature.MIN_SIZE; n <= Miniature.MAX_SIZE; n++) {
			if (project.slab() && n % 2 != 0) continue;
			int x = PAD + (shown % COLUMNS) * (BUTTON + GAP);
			int y = sizesTop + (shown / COLUMNS) * (BUTTON + GAP);
			screen.button("n" + n, x, y, BUTTON, BUTTON, Map.of(ComponentType.PROP_LABEL, Integer.toString(n)));
			shown++;
		}
		return screen;
	}

	/** Where things go on each tab, and which go with which: kept with the slots to swap the tabs in place. */
	private static final class Layout {
		String screenId;
		final List<String> build = new ArrayList<>();
		final List<String> copy = new ArrayList<>();
		int functionX, functionBuildY, functionCopyY, copyInX, copyOutX, copyY;
	}

	private static Map<String, String> props(Map<String, String> props, List<String> tab, String id, boolean shown) {
		tab.add(id);
		if (shown) return props;
		Map<String, String> hidden = new HashMap<>(props);
		hidden.put(ComponentType.PROP_VISIBLE, "false");
		return hidden;
	}

	private static ScreenBuilder mainScreen(ServerLevel level, int plot, Ledger.Project project, Layout layout) {
		List<Item> materials = project.materials();
		int lines = Math.max(1, Math.min(materials.size(), SHOWN_MATERIALS) + (materials.size() > SHOWN_MATERIALS ? 1 : 0));
		int listTop = 36;
		int optionsTop = listTop + lines * 18 + 8;
		int nameTop = optionsTop + 3 * 14 + 8;
		int buttonsTop = nameTop + 18 + GAP;
		int copyLinesTop = listTop + 76;
		int copyBottom = copyLinesTop + (SHOWN_COST + 1) * 10 + 4;
		int packTop = Math.max(buttonsTop + 2 * (20 + GAP) + 14, copyBottom);
		int height = packTop + 3 * 18 + 4 + 18 + PAD;
		List<String> build = layout.build;
		List<String> copy = layout.copy;
		ScreenBuilder screen = new ScreenBuilder(MAIN).size(WIDTH, height).title("Miniature bench").container(BenchSlots.SIZE, true);
		screen.panel("frame", 0, 0, WIDTH, height, Map.of());
		long n = project.n();
		screen.text("heading", PAD, PAD, Map.of(
			ComponentType.PROP_TEXT, project.slab()
				? text("minicraft-justfatlard.bench.heading_slab", "Cut %s to a side, a slab %s high", n, project.height())
				: text("minicraft-justfatlard.bench.heading", "Cut %s to a side", n),
			ComponentType.PROP_COLOR, DARK));
		screen.text("note", PAD, PAD + 12, Map.of(
			ComponentType.PROP_TEXT, text("minicraft-justfatlard.bench.note", "A block loaded is %s cells. Right-click with blocks to load.", n * n * n),
			ComponentType.PROP_COLOR, GREY));
		int tabsLeft = WIDTH - PAD - 2 * TAB - GAP;
		screen.button("tab_build", tabsLeft, PAD - 3, TAB, 13, Map.of(
			ComponentType.PROP_LABEL, text("minicraft-justfatlard.bench.tab_build", "Build"),
			ComponentType.PROP_ENABLED, "false"));
		screen.button("tab_copy", tabsLeft + TAB + GAP, PAD - 3, TAB, 13, Map.of(
			ComponentType.PROP_LABEL, text("minicraft-justfatlard.bench.tab_copy", "Copy")));

		// --- Build ---
		if (materials.isEmpty()) {
			screen.text("empty", PAD, listTop + 4, props(Map.of(
				ComponentType.PROP_TEXT, text("minicraft-justfatlard.bench.nothing", "Nothing loaded yet."),
				ComponentType.PROP_COLOR, GREY), build, "empty", true));
		}
		for (int i = 0; i < Math.min(materials.size(), SHOWN_MATERIALS); i++) {
			Item item = materials.get(i);
			long cells = project.cells(item);
			long blocks = cells / project.cellsPerBlock();
			int y = listTop + i * 18;
			screen.itemIcon("icon" + i, PAD, y, BuiltInRegistries.ITEM.getKey(item).toString(), 1);
			build.add("icon" + i);
			screen.text("count" + i, PAD + 20, y + 4, props(Map.of(
				ComponentType.PROP_TEXT, text("minicraft-justfatlard.bench.material", "%s: %s cells (%s whole)", new ItemStack(item).getHoverName().getString(), cells, blocks),
				ComponentType.PROP_COLOR, DARK), build, "count" + i, true));
		}
		if (materials.size() > SHOWN_MATERIALS) {
			screen.text("more", PAD, listTop + SHOWN_MATERIALS * 18 + 4, props(Map.of(
				ComponentType.PROP_TEXT, text("minicraft-justfatlard.bench.more", "and %s more", materials.size() - SHOWN_MATERIALS),
				ComponentType.PROP_COLOR, GREY), build, "more", true));
		}

		screen.button("redstone", PAD, optionsTop, WIDTH - PAD * 2, 12, props(Map.of(ComponentType.PROP_LABEL, project.redstone()
			? text("minicraft-justfatlard.bench.redstone_on", "Responds to redstone: yes")
			: text("minicraft-justfatlard.bench.redstone_off", "Responds to redstone: no")), build, "redstone", true));
		screen.text("handle", PAD, optionsTop + 16, props(Map.of(
			ComponentType.PROP_TEXT, project.handle() != null
				? text("minicraft-justfatlard.bench.handle_on", "Handle: marked")
				: text("minicraft-justfatlard.bench.handle_off", "Handle: none (mark one in the box with the stick)"),
			ComponentType.PROP_COLOR, DARK), build, "handle", true));
		ItemStack function = project.function();
		// One grid for the functional slot, moved between the tabs: two would fight over the slot.
		layout.functionX = PAD;
		layout.functionBuildY = optionsTop + 26;
		screen.inventoryGrid("function", PAD, layout.functionBuildY, 1, 1, BenchSlots.FUNCTION, Map.of(ComponentType.PROP_INTERP_TICKS, "1"));
		screen.text("function_note", PAD + 22, optionsTop + 31, props(Map.of(
			ComponentType.PROP_TEXT, function.isEmpty()
				? text("minicraft-justfatlard.bench.function_off", "Works as: nothing (put a crafting table, furnace or chest here)")
				: text("minicraft-justfatlard.bench.function_on", "Works as: %s", function.getHoverName().getString()),
			ComponentType.PROP_COLOR, DARK), build, "function_note", true));
		screen.text("name_label", PAD, nameTop + 5, props(Map.of(
			ComponentType.PROP_TEXT, text("minicraft-justfatlard.bench.name", "Name:"),
			ComponentType.PROP_COLOR, DARK), build, "name_label", true));
		build.add("name");
		screen.component(new ComponentBuilder("name", ComponentType.TEXT_INPUT)
			.bounds(PAD + 40, nameTop, WIDTH - PAD * 2 - 40, 18)
			.prop(ComponentType.PROP_VALUE, project.name())
			.prop(ComponentType.PROP_MAX_LENGTH, String.valueOf(Bench.MAX_NAME))
			.prop(ComponentType.PROP_PLACEHOLDER, text("minicraft-justfatlard.bench.name_hint", "None (optional)")));

		int half = (WIDTH - PAD * 2 - GAP) / 2;
		screen.button("enter", PAD, buttonsTop, half, 20, props(Map.of(ComponentType.PROP_LABEL, text("minicraft-justfatlard.bench.enter", "Step in")), build, "enter", true));
		screen.button("finish", PAD + half + GAP, buttonsTop, half, 20, props(Map.of(ComponentType.PROP_LABEL, text("minicraft-justfatlard.bench.finish", "Finish")), build, "finish", true));
		screen.button("withdraw", PAD, buttonsTop + 20 + GAP, half, 20, props(Map.of(ComponentType.PROP_LABEL, text("minicraft-justfatlard.bench.withdraw", "Take out what is left")), build, "withdraw", true));
		ServerLevel workshop = Workshop.level(level.getServer());
		if (workshop != null && Bench.mayResize(workshop, plot, project)) {
			screen.button("resize", PAD + half + GAP, buttonsTop + 20 + GAP, half, 20, props(Map.of(ComponentType.PROP_LABEL, text("minicraft-justfatlard.bench.resize", "Choose another size")), build, "resize", true));
		}

		// --- Copy: built hidden, its two grids away from the screen until the tab is shown ---
		layout.copyY = listTop;
		layout.copyInX = PAD;
		layout.copyOutX = WIDTH - PAD - 18;
		layout.functionCopyY = listTop + 24;
		screen.inventoryGrid("copy_in", AWAY, layout.copyY, 1, 1, BenchSlots.COPY_IN, Map.of(ComponentType.PROP_INTERP_TICKS, "1", ComponentType.PROP_VISIBLE, "false"));
		screen.inventoryGrid("copy_out", AWAY, layout.copyY, 1, 1, BenchSlots.COPY_OUT, Map.of(ComponentType.PROP_INTERP_TICKS, "1", ComponentType.PROP_VISIBLE, "false"));
		screen.text("copy_in_label", PAD + 22, listTop + 5, props(Map.of(
			ComponentType.PROP_TEXT, text("minicraft-justfatlard.copy.original", "Miniature"),
			ComponentType.PROP_COLOR, DARK), copy, "copy_in_label", false));
		screen.text("copy_arrow", WIDTH / 2 - 6, listTop + 5, props(Map.of(
			ComponentType.PROP_TEXT, "->",
			ComponentType.PROP_COLOR, DARK), copy, "copy_arrow", false));
		screen.text("copy_out_label", layout.copyOutX - 30, listTop + 5, props(Map.of(
			ComponentType.PROP_TEXT, text("minicraft-justfatlard.copy.copy", "Copy"),
			ComponentType.PROP_COLOR, DARK), copy, "copy_out_label", false));
		screen.text("copy_function", PAD + 22, layout.functionCopyY + 5, props(Map.of(
			ComponentType.PROP_TEXT, "", ComponentType.PROP_COLOR, DARK), copy, "copy_function", false));
		screen.text("copy_status", PAD, listTop + 50, props(Map.of(
			ComponentType.PROP_TEXT, "", ComponentType.PROP_COLOR, DARK), copy, "copy_status", false));
		screen.text("copy_size", PAD, listTop + 62, props(Map.of(
			ComponentType.PROP_TEXT, "", ComponentType.PROP_COLOR, GREY), copy, "copy_size", false));
		for (int i = 0; i <= SHOWN_COST; i++) {
			screen.text("cost" + i, PAD, copyLinesTop + i * 10, props(Map.of(
				ComponentType.PROP_TEXT, "", ComponentType.PROP_COLOR, DARK), copy, "cost" + i, false));
		}

		int packLeft = (WIDTH - 9 * 18) / 2;
		screen.inventoryGrid("pack", packLeft, packTop, 3, 9, BenchSlots.SIZE);
		screen.inventoryGrid("hotbar", packLeft, packTop + 3 * 18 + 4, 1, 9, BenchSlots.SIZE + 27);
		return screen;
	}

	/**
	 * Show one tab or the other, in place: the other tab's parts hidden, the grids moved so only
	 * this tab's slots are on the screen. A miniature left in the copy slot stays there, hidden,
	 * until the Copy tab is shown again or the screen is closed.
	 */
	public static void showTab(ServerPlayer player, boolean copying) {
		BenchSlots open = viewing.get(player.getUUID());
		if (open == null || !open.stillHere()) return;
		open.copying = copying;
		Layout layout = open.layout;
		List<ComponentUpdate> updates = new ArrayList<>();
		for (String id : layout.build) updates.add(new ComponentUpdateBuilder(id).prop(ComponentType.PROP_VISIBLE, !copying).build());
		for (String id : layout.copy) updates.add(new ComponentUpdateBuilder(id).prop(ComponentType.PROP_VISIBLE, copying).build());
		updates.add(new ComponentUpdateBuilder("function").pos(layout.functionX, copying ? layout.functionCopyY : layout.functionBuildY).build());
		updates.add(new ComponentUpdateBuilder("copy_in").pos(copying ? layout.copyInX : AWAY, layout.copyY).prop(ComponentType.PROP_VISIBLE, copying).build());
		updates.add(new ComponentUpdateBuilder("copy_out").pos(copying ? layout.copyOutX : AWAY, layout.copyY).prop(ComponentType.PROP_VISIBLE, copying).build());
		updates.add(new ComponentUpdateBuilder("tab_build").prop(ComponentType.PROP_ENABLED, copying).build());
		updates.add(new ComponentUpdateBuilder("tab_copy").prop(ComponentType.PROP_ENABLED, !copying).build());
		PandoricalApi.screens().update(player, layout.screenId, updates);
		open.sent.clear();
		refresh(open);
	}

	/** The copy's lines as they are now, sent only where they changed. */
	static void refresh(BenchSlots open) {
		if (!open.copying) return;
		Copying.Offer offer = open.offer();
		Optional<Copying.Plan> plan = open.plan();
		Map<String, String[]> lines = new LinkedHashMap<>();
		String statusColor = switch (offer.why()) {
			case READY -> GREEN;
			case EMPTY -> GREY;
			default -> RED;
		};
		lines.put("copy_status", new String[] {Copying.reason(offer).getString(), statusColor});
		String function;
		String functionColor = DARK;
		if (plan.isEmpty()) {
			function = text("minicraft-justfatlard.copy.function", "Functional slot");
			functionColor = GREY;
		} else if (offer.needs().isPresent()) {
			boolean has = offer.function().isPresent();
			function = text("minicraft-justfatlard.copy.function_needed", "Works as %s: %s goes here, spent on the copy",
				offer.needs().get().getName().getString(), Copying.needName(offer.needs().get()).getString());
			if (!has) functionColor = RED;
		} else {
			function = text("minicraft-justfatlard.copy.function_none", "Works as nothing: no block needed");
			functionColor = GREY;
		}
		lines.put("copy_function", new String[] {function, functionColor});
		Ledger.Project project = open.project();
		String size = "";
		if (plan.isPresent() && project != null && project.n() != 0) {
			size = plan.get().n() == project.n()
				? text("minicraft-justfatlard.copy.cost", "Cost, in cells as this bench cuts them, and what it holds:")
				: text("minicraft-justfatlard.copy.cost_converted", "Cut %s to a side, this bench %s: cost converted, rounded up",
					plan.get().n(), project.n());
		}
		lines.put("copy_size", new String[] {size, GREY});
		List<Copying.Line> costs = offer.lines();
		for (int i = 0; i <= SHOWN_COST; i++) {
			String line = "";
			String color = DARK;
			if (i < SHOWN_COST && i < costs.size()) {
				Copying.Line cost = costs.get(i);
				line = text("minicraft-justfatlard.copy.line", "%s: %s needed, %s held", new ItemStack(cost.item()).getHoverName().getString(), cost.cost(), cost.held());
				if (!cost.enough()) color = RED;
			} else if (i == SHOWN_COST && costs.size() > SHOWN_COST) {
				long short_ = costs.subList(SHOWN_COST, costs.size()).stream().filter(c -> !c.enough()).count();
				line = text("minicraft-justfatlard.copy.more", "and %s more, %s of them short", costs.size() - SHOWN_COST, short_);
				if (short_ > 0) color = RED;
			}
			lines.put("cost" + i, new String[] {line, color});
		}
		List<ComponentUpdate> updates = new ArrayList<>();
		lines.forEach((id, value) -> {
			String was = open.sent.get(id);
			String now = value[0] + "|" + value[1];
			if (now.equals(was)) return;
			open.sent.put(id, now);
			updates.add(new ComponentUpdateBuilder(id).prop(ComponentType.PROP_TEXT, value[0]).prop(ComponentType.PROP_COLOR, value[1]).build());
		});
		if (!updates.isEmpty()) PandoricalApi.screens().update(open.viewer, open.layout.screenId, updates);
	}

	/**
	 * Four times a second for every open bench: closed if the bench is gone or its viewer has
	 * walked off (giving back what is in the copy slot), and the copy's lines kept current, as
	 * another player at the same bench may have changed what it holds.
	 */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 5 != 0 || viewing.isEmpty()) return;
		for (BenchSlots open : List.copyOf(viewing.values())) {
			if (!(open.viewer.containerMenu instanceof PandoricalMenu menu) || menu.getModContainer() != open) {
				viewing.remove(open.viewer.getUUID(), open);
				continue;
			}
			if (!open.stillHere()) {
				open.viewer.closeContainer();
				continue;
			}
			refresh(open);
		}
	}

	/**
	 * The bench's slots, as one viewer sees them. The functional slot is a window on the ledger:
	 * one block, of a kind a miniature can work as, kept by the project, and shut while a capture
	 * runs, when what is in it is about to be spent. The copy slot is the viewer's own, given back
	 * as the screen closes. The copy shows only while the bench can pay for it, and is paid for
	 * at the moment it is taken, however it is taken: a click, a shift-click, a number key, a drop.
	 */
	static final class BenchSlots implements Container {
		static final int FUNCTION = 0;
		static final int COPY_IN = 1;
		static final int COPY_OUT = 2;
		static final int SIZE = 3;

		final ServerPlayer viewer;
		private final ServerLevel level;
		private final BlockPos bench;
		private final Ledger ledger;
		private final int plot;
		final Layout layout;
		/** Whether the Copy tab is shown; the copy slot takes a miniature only then. */
		boolean copying;
		/** What was last sent for each of the copy's lines. */
		final Map<String, String> sent = new HashMap<>();

		private ItemStack input = ItemStack.EMPTY;
		/** The original as read, for as long as it is the one in the slot. */
		private ItemStack readFrom;
		private Optional<Copying.Plan> plan = Optional.empty();
		/** The copy as shown in the slot, the same stack each time it is asked for until it is taken or changes. */
		private ItemStack shown;
		private Optional<net.minecraft.world.level.block.Block> shownFunction = Optional.empty();
		/** The tick a copy was last taken in: a shift-click takes one, not as many as the bench can pay for. */
		private int takenAt = -1;

		BenchSlots(ServerPlayer viewer, ServerLevel level, BlockPos bench, int plot, Layout layout) {
			this.viewer = viewer;
			this.level = level;
			this.bench = bench;
			this.ledger = Ledger.get(level.getServer());
			this.plot = plot;
			this.layout = layout;
		}

		private Ledger.Project open() {
			Ledger.Project project = ledger.project(plot);
			return project == null || project.capturing() ? null : project;
		}

		/** The bench is where it was, still this project's, and its viewer is at it. */
		boolean stillHere() {
			return !viewer.isRemoved() && viewer.level() == level
				&& viewer.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(bench)) <= REACH * REACH
				&& level.getBlockEntity(bench) instanceof BenchBlockEntity entity && entity.plot() == plot
				&& ledger.project(plot) != null;
		}

		/** The project, or null if the bench is gone or its viewer has left it. */
		Ledger.Project project() {
			return stillHere() ? ledger.project(plot) : null;
		}

		Optional<Copying.Plan> plan() {
			if (input != readFrom) {
				readFrom = input;
				plan = input.isEmpty() ? Optional.empty() : Copying.plan(input);
				shown = null;
			}
			return plan;
		}

		Copying.Offer offer() {
			return Copying.offer(project(), plan());
		}

		@Override
		public int getContainerSize() {
			return SIZE;
		}

		@Override
		public boolean isEmpty() {
			return getItem(FUNCTION).isEmpty() && input.isEmpty();
		}

		/** The functional slot shown empty while shut, so nothing can be taken from it or moved out of it. */
		@Override
		public ItemStack getItem(int slot) {
			switch (slot) {
				case FUNCTION -> {
					Ledger.Project project = open();
					return project == null ? ItemStack.EMPTY : project.function();
				}
				case COPY_IN -> {
					return input;
				}
				case COPY_OUT -> {
					return showing();
				}
				default -> {
					return ItemStack.EMPTY;
				}
			}
		}

		/** The copy, while the Copy tab is shown and the bench can pay for it right now; otherwise nothing. */
		private ItemStack showing() {
			if (!copying || takenAt == level.getServer().getTickCount()) return ItemStack.EMPTY;
			Copying.Offer offer = offer();
			if (!offer.ready()) {
				shown = null;
				return ItemStack.EMPTY;
			}
			if (shown == null || shown.isEmpty() || !shownFunction.equals(offer.function())) {
				shown = Copying.copyOf(input, plan.get(), offer.function());
				shownFunction = offer.function();
			}
			return shown;
		}

		/**
		 * Pay for the copy now, if the bench still can, and give it: everything checked again here,
		 * on the server, at the moment of taking. Nothing is spent and nothing given if not.
		 */
		private ItemStack take() {
			if (!copying || takenAt == level.getServer().getTickCount() || !stillHere() || plan().isEmpty()) return ItemStack.EMPTY;
			Optional<Optional<net.minecraft.world.level.block.Block>> paid = Copying.charge(ledger, plot, plan.get());
			if (paid.isEmpty()) {
				shown = null;
				return ItemStack.EMPTY;
			}
			ItemStack made = shown != null && !shown.isEmpty() && shownFunction.equals(paid.get()) ? shown : Copying.copyOf(input, plan.get(), paid.get());
			shown = null;
			takenAt = level.getServer().getTickCount();
			level.playSound(null, bench, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1.0F, 1.4F);
			return made;
		}

		@Override
		public ItemStack removeItem(int slot, int count) {
			if (count <= 0) return ItemStack.EMPTY;
			switch (slot) {
				case FUNCTION -> {
					Ledger.Project project = open();
					if (project == null || project.function().isEmpty()) return ItemStack.EMPTY;
					ItemStack taken = project.function();
					ledger.setFunction(plot, ItemStack.EMPTY);
					return taken;
				}
				case COPY_IN -> {
					ItemStack taken = input;
					input = ItemStack.EMPTY;
					return taken;
				}
				case COPY_OUT -> {
					return take();
				}
				default -> {
					return ItemStack.EMPTY;
				}
			}
		}

		/** Never the copy: it is only ever had by being taken, and paid for. */
		@Override
		public ItemStack removeItemNoUpdate(int slot) {
			return slot == COPY_OUT ? ItemStack.EMPTY : removeItem(slot, 1);
		}

		/**
		 * The copy slot emptied by the menu itself is the copy shown having gone into the
		 * viewer's pack (a shift-click, a number key): it is paid for here. Checked a moment
		 * before as it was shown, in the same click; should it somehow no longer be payable, the
		 * copy is taken back off the viewer rather than had for nothing.
		 */
		@Override
		public void setItem(int slot, ItemStack stack) {
			switch (slot) {
				case FUNCTION -> {
					if (open() != null) ledger.setFunction(plot, stack);
				}
				case COPY_IN -> input = stack;
				case COPY_OUT -> {
					if (!stack.isEmpty() || shown == null) return;
					ItemStack gone = shown;
					ItemStack snapshot = Copying.copyOf(input, plan.get(), shownFunction);
					if (take().isEmpty()) takeBack(gone, snapshot);
				}
				default -> {}
			}
		}

		private void takeBack(ItemStack gone, ItemStack snapshot) {
			System.err.println("[" + Main.MOD_ID + "] A copy left the bench at " + bench + " without being paid for; taking it back");
			var inventory = viewer.getInventory();
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				ItemStack held = inventory.getItem(i);
				if (held == gone || !held.isEmpty() && ItemStack.isSameItemSameComponents(held, snapshot)) {
					inventory.removeItemNoUpdate(i);
					return;
				}
			}
			gone.setCount(0);
		}

		@Override
		public boolean canPlaceItem(int slot, ItemStack stack) {
			switch (slot) {
				case FUNCTION -> {
					Ledger.Project project = open();
					if (project == null || stack.is(Main.MINIATURE_ITEM)) return false;
					Component refused = Functions.refusal(stack);
					if (refused == null && project.handle() != null && project.function().isEmpty()) {
						refused = Component.translatableWithFallback("minicraft-justfatlard.function.has_handle", Bench.HANDLE_FIRST);
					}
					if (refused != null) viewer.sendOverlayMessage(refused);
					return refused == null;
				}
				case COPY_IN -> {
					return copying && Copying.plan(stack).isPresent();
				}
				default -> {
					return false;
				}
			}
		}

		@Override
		public int getMaxStackSize() {
			return 1;
		}

		@Override
		public void setChanged() {
			ledger.setDirty();
		}

		@Override
		public boolean stillValid(Player player) {
			return stillHere();
		}

		/** Closed, however it closed: the miniature in the copy slot back to its owner, as vanilla gives back a crafting grid. */
		@Override
		public void stopOpen(ContainerUser user) {
			viewing.remove(viewer.getUUID(), this);
			ItemStack back = input;
			input = ItemStack.EMPTY;
			shown = null;
			if (back.isEmpty()) return;
			boolean gone = viewer.isRemoved() && viewer.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION;
			if (gone || viewer.hasDisconnected()) viewer.drop(back, false, net.minecraft.util.Prediction.SERVER_ONLY);
			else viewer.getInventory().placeItemBackInInventory(back, net.minecraft.util.Prediction.SERVER_ONLY);
		}

		@Override
		public void clearContent() {}
	}

	private static String text(String key, String fallback, Object... args) {
		return Component.translatableWithFallback(key, fallback, args).getString();
	}
}
