package justfatlard.minicraft;

import justfatlard.pandorical.api.ComponentType;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.ScreenApi;
import justfatlard.pandorical.api.ScreenBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The bench, opened: a size to choose while it has none, and after that what it holds and what
 * to do with it. Drawn by Pandorical, which every player of this mod has.
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

	private record Pending(ServerLevel level, BlockPos bench, String screenId) {}

	private static final Map<UUID, Pending> pending = new HashMap<>();

	public static void register() {
		ScreenApi screens = PandoricalApi.screens();
		for (int n = Miniature.MIN_SIZE; n <= Miniature.MAX_SIZE; n++) {
			int size = n;
			screens.onAction(SIZE, "n" + n, (player, data) -> act(player, (level, bench) -> {
				Ledger ledger = Ledger.get(level.getServer());
				int plot = Bench.plotOf(level, bench);
				if (ledger.project(plot) != null && ledger.project(plot).n() == 0) ledger.setSize(plot, size);
				open(player, level, bench);
			}));
		}
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
		screens.onClose(SIZE, player -> pending.remove(player.getUUID()));
		screens.onClose(MAIN, player -> pending.remove(player.getUUID()));
	}

	public static void forget(UUID player) {
		pending.remove(player);
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
		ScreenBuilder screen = project.n() == 0 ? sizeScreen() : mainScreen(level, Bench.plotOf(level, bench), project);
		pending.put(player.getUUID(), new Pending(level, bench.immutable(), screen.screenId()));
		PandoricalApi.screens().open(player, screen.build());
	}

	private static ScreenBuilder sizeScreen() {
		int rows = (Miniature.MAX_SIZE - Miniature.MIN_SIZE + COLUMNS) / COLUMNS;
		int width = PAD * 2 + COLUMNS * BUTTON + (COLUMNS - 1) * GAP;
		int height = 36 + rows * (BUTTON + GAP) + PAD;
		ScreenBuilder screen = new ScreenBuilder(SIZE).size(width, height).title("Miniature bench");
		screen.panel("frame", 0, 0, width, height, Map.of());
		screen.text("heading", PAD, PAD, Map.of(
			ComponentType.PROP_TEXT, text("minicraft-justfatlard.size.heading", "Cut each block into how many to a side?"),
			ComponentType.PROP_COLOR, "#404040"));
		screen.text("note", PAD, PAD + 12, Map.of(
			ComponentType.PROP_TEXT, text("minicraft-justfatlard.size.note", "Finer is more detail, and more to build."),
			ComponentType.PROP_COLOR, "#707070"));
		for (int n = Miniature.MIN_SIZE; n <= Miniature.MAX_SIZE; n++) {
			int i = n - Miniature.MIN_SIZE;
			int x = PAD + (i % COLUMNS) * (BUTTON + GAP);
			int y = 36 + (i / COLUMNS) * (BUTTON + GAP);
			screen.button("n" + n, x, y, BUTTON, BUTTON, Map.of(ComponentType.PROP_LABEL, Integer.toString(n)));
		}
		return screen;
	}

	private static ScreenBuilder mainScreen(ServerLevel level, int plot, Ledger.Project project) {
		List<Item> materials = project.materials();
		int lines = Math.max(1, Math.min(materials.size(), SHOWN_MATERIALS) + (materials.size() > SHOWN_MATERIALS ? 1 : 0));
		int listTop = 36;
		int optionsTop = listTop + lines * 18 + 8;
		int buttonsTop = optionsTop + 3 * 14 + 8;
		int height = buttonsTop + 2 * (20 + GAP) + PAD;
		ScreenBuilder screen = new ScreenBuilder(MAIN).size(WIDTH, height).title("Miniature bench");
		screen.panel("frame", 0, 0, WIDTH, height, Map.of());
		long n = project.n();
		screen.text("heading", PAD, PAD, Map.of(
			ComponentType.PROP_TEXT, text("minicraft-justfatlard.bench.heading", "Cut %s to a side", n),
			ComponentType.PROP_COLOR, "#404040"));
		screen.text("note", PAD, PAD + 12, Map.of(
			ComponentType.PROP_TEXT, text("minicraft-justfatlard.bench.note", "A block loaded is %s cells. Right-click with blocks to load.", n * n * n),
			ComponentType.PROP_COLOR, "#707070"));

		if (materials.isEmpty()) {
			screen.text("empty", PAD, listTop + 4, Map.of(
				ComponentType.PROP_TEXT, text("minicraft-justfatlard.bench.nothing", "Nothing loaded yet."),
				ComponentType.PROP_COLOR, "#707070"));
		}
		for (int i = 0; i < Math.min(materials.size(), SHOWN_MATERIALS); i++) {
			Item item = materials.get(i);
			long cells = project.cells(item);
			long blocks = cells / project.cellsPerBlock();
			int y = listTop + i * 18;
			screen.itemIcon("icon" + i, PAD, y, BuiltInRegistries.ITEM.getKey(item).toString(), 1);
			screen.text("count" + i, PAD + 20, y + 4, Map.of(
				ComponentType.PROP_TEXT, text("minicraft-justfatlard.bench.material", "%s: %s cells (%s whole)", new ItemStack(item).getHoverName().getString(), cells, blocks),
				ComponentType.PROP_COLOR, "#404040"));
		}
		if (materials.size() > SHOWN_MATERIALS) {
			screen.text("more", PAD, listTop + SHOWN_MATERIALS * 18 + 4, Map.of(
				ComponentType.PROP_TEXT, text("minicraft-justfatlard.bench.more", "and %s more", materials.size() - SHOWN_MATERIALS),
				ComponentType.PROP_COLOR, "#707070"));
		}

		ServerLevel workshopLevel = Workshop.level(level.getServer());
		screen.button("redstone", PAD, optionsTop, WIDTH - PAD * 2, 12, Map.of(ComponentType.PROP_LABEL, project.redstone()
			? text("minicraft-justfatlard.bench.redstone_on", "Responds to redstone: yes")
			: text("minicraft-justfatlard.bench.redstone_off", "Responds to redstone: no")));
		screen.text("handle", PAD, optionsTop + 16, Map.of(
			ComponentType.PROP_TEXT, project.handle() != null
				? text("minicraft-justfatlard.bench.handle_on", "Handle: marked")
				: text("minicraft-justfatlard.bench.handle_off", "Handle: none (mark one in the box with the stick)"),
			ComponentType.PROP_COLOR, "#404040"));
		boolean stores = workshopLevel != null && hasStorage(workshopLevel, plot, project.n());
		screen.text("storage", PAD, optionsTop + 30, Map.of(
			ComponentType.PROP_TEXT, stores
				? text("minicraft-justfatlard.bench.storage_on", "Storage: %s slots, from the chest or barrel in it", Bench.STORAGE_SLOTS)
				: text("minicraft-justfatlard.bench.storage_off", "Storage: none (build a chest or barrel into it)"),
			ComponentType.PROP_COLOR, "#404040"));

		int half = (WIDTH - PAD * 2 - GAP) / 2;
		screen.button("enter", PAD, buttonsTop, half, 20, Map.of(ComponentType.PROP_LABEL, text("minicraft-justfatlard.bench.enter", "Step in")));
		screen.button("finish", PAD + half + GAP, buttonsTop, half, 20, Map.of(ComponentType.PROP_LABEL, text("minicraft-justfatlard.bench.finish", "Finish")));
		screen.button("withdraw", PAD, buttonsTop + 20 + GAP, half, 20, Map.of(ComponentType.PROP_LABEL, text("minicraft-justfatlard.bench.withdraw", "Take out what is left")));
		ServerLevel workshop = Workshop.level(level.getServer());
		if (workshop != null && Bench.mayResize(workshop, plot, project)) {
			screen.button("resize", PAD + half + GAP, buttonsTop + 20 + GAP, half, 20, Map.of(ComponentType.PROP_LABEL, text("minicraft-justfatlard.bench.resize", "Choose another size")));
		}
		return screen;
	}

	private static boolean hasStorage(ServerLevel workshop, int plot, int n) {
		return Miniature.capture(workshop, Workshop.origin(plot), n)
			.map(m -> m.palette().stream().anyMatch(Bench::isStorage)).orElse(false);
	}

	private static String text(String key, String fallback, Object... args) {
		return Component.translatableWithFallback(key, fallback, args).getString();
	}
}
