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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What a bench clicked with a miniature asks: put it on the plate to work on again, or break it
 * down into the bench for what it is made of, with what breaking it down would give shown first.
 *
 * <p>Either button does what it says only on the miniature asked about, still in the hand it was
 * in, and at a bench still in reach; {@link Bench#edit} and {@link Bench#salvage} check the rest.
 * Without Pandorical's screens there is nothing to ask with, and the miniature goes on the plate.
 */
public final class MiniatureChoice {
	private MiniatureChoice() {}

	static final String TYPE = Main.MOD_ID + ":miniature_choice";
	private static final double REACH = 8.0;

	private static final int PAD = 8;
	private static final int WIDTH = 260;
	private static final int GAP = 4;
	private static final int ROW = 18;
	private static final int SHOWN = 6;

	private static final String DARK = "#404040";
	private static final String GREY = "#707070";

	/** What one player was asked about: the bench, and the miniature in which hand, as it was. */
	private record Asked(ServerLevel level, BlockPos bench, InteractionHand hand, ItemStack miniature, String screenId) {}

	private static final Map<UUID, Asked> asked = new HashMap<>();

	public static void register() {
		ScreenApi screens = PandoricalApi.screens();
		screens.onAction(TYPE, "edit", (player, data) -> act(player, (level, bench, stack) -> Bench.edit(player, level, bench, stack)));
		screens.onAction(TYPE, "salvage", (player, data) -> act(player, (level, bench, stack) -> Bench.salvage(player, level, bench, stack)));
		screens.onClose(TYPE, player -> asked.remove(player.getUUID()));
	}

	public static void forget(UUID player) {
		asked.remove(player);
	}

	/** The screen id this player has open, or null: for a test pressing its buttons as a click would. */
	public static String openId(ServerPlayer player) {
		Asked shown = asked.get(player.getUUID());
		return shown == null ? null : shown.screenId();
	}

	private interface Act {
		void at(ServerLevel level, BlockPos bench, ItemStack stack);
	}

	private static void act(ServerPlayer player, Act act) {
		Asked shown = asked.remove(player.getUUID());
		if (shown == null) return;
		PandoricalApi.screens().close(player, shown.screenId());
		if (player.isRemoved() || player.level() != shown.level()
			|| player.distanceToSqr(Vec3.atCenterOf(shown.bench())) > REACH * REACH
			|| !(shown.level().getBlockEntity(shown.bench()) instanceof BenchBlockEntity)) return;
		ItemStack inHand = player.getItemInHand(shown.hand());
		if (!ItemStack.isSameItemSameComponents(inHand, shown.miniature())) {
			Bench.say(player, "minicraft-justfatlard.choice.gone", "That miniature is no longer in your hand.");
			return;
		}
		act.at(shown.level(), shown.bench(), inHand);
	}

	public static void open(ServerPlayer player, ServerLevel level, BlockPos bench, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		Optional<Looks> looks = Looks.of(stack);
		Ledger.Project project = Ledger.get(level.getServer()).project(Bench.plotOf(level, bench));
		if (looks.isEmpty() || project == null) return;
		if (!PandoricalApi.hasCapability(player, "screens")) {
			Bench.edit(player, level, bench, stack);
			return;
		}
		Miniature build = looks.get().off();
		int to = project.n() == 0 ? build.n() : project.n();
		List<Line> lines = new ArrayList<>();
		Copying.cells(build).forEach((item, cells) -> lines.add(new Line(item, convert(cells, build.n(), to))));
		Optional<Block> function = looks.get().function();

		int shownLines = Math.min(lines.size(), SHOWN);
		int listTop = PAD + 12 + 24 + GAP + 12;
		int buttonsTop = listTop + Math.max(1, shownLines) * ROW + (lines.size() > SHOWN ? 12 : 0) + (function.isPresent() ? 12 : 0) + GAP;
		int height = buttonsTop + 20 + PAD;

		String name = Bench.nameOf(stack);
		String title = name.isEmpty() ? text("minicraft-justfatlard.choice.title", "Miniature") : name;
		ScreenBuilder screen = new ScreenBuilder(TYPE).size(WIDTH, height).title(title);
		screen.panel("frame", 0, 0, WIDTH, height, Map.of());
		screen.text("name", PAD, PAD, Map.of(ComponentType.PROP_TEXT, title, ComponentType.PROP_COLOR, DARK));
		screen.text("question", PAD, PAD + 12, Map.of(
			ComponentType.PROP_TEXT, text("minicraft-justfatlard.choice.question",
				"Put it on the plate to work on again, or break it down for what it is made of?"),
			ComponentType.PROP_COLOR, DARK,
			ComponentType.PROP_WRAP_WIDTH, String.valueOf(WIDTH - PAD * 2), ComponentType.PROP_MAX_LINES, "2"));
		screen.text("gives", PAD, PAD + 12 + 24 + GAP, Map.of(
			ComponentType.PROP_TEXT, text("minicraft-justfatlard.choice.gives", "Breaking it down gives the bench, cut %s to a side:", to),
			ComponentType.PROP_COLOR, GREY));
		if (lines.isEmpty()) {
			screen.text("line0", PAD, listTop + 4, Map.of(
				ComponentType.PROP_TEXT, text("minicraft-justfatlard.choice.no_material", "Nothing: nothing in it counts as material."),
				ComponentType.PROP_COLOR, GREY));
		}
		long perBlock = (long) to * to * to;
		for (int i = 0; i < shownLines; i++) {
			Line line = lines.get(i);
			screen.itemIcon("icon" + i, PAD, listTop + i * ROW, BuiltInRegistries.ITEM.getKey(line.item()).toString(), 1);
			screen.text("line" + i, PAD + 20, listTop + i * ROW + 4, Map.of(
				ComponentType.PROP_TEXT, text("minicraft-justfatlard.choice.line", "%s: %s cells (%s whole)",
					new ItemStack(line.item()).getHoverName().getString(), line.cells(), line.cells() / perBlock),
				ComponentType.PROP_COLOR, DARK));
		}
		int below = listTop + Math.max(1, shownLines) * ROW;
		if (lines.size() > SHOWN) {
			screen.text("more", PAD, below, Map.of(
				ComponentType.PROP_TEXT, text("minicraft-justfatlard.choice.more", "and %s more", lines.size() - SHOWN),
				ComponentType.PROP_COLOR, DARK));
			below += 12;
		}
		if (function.isPresent()) {
			screen.text("function", PAD, below, Map.of(
				ComponentType.PROP_TEXT, text("minicraft-justfatlard.choice.function", "and the %s it works as, back to you.", function.get().getName().getString()),
				ComponentType.PROP_COLOR, DARK));
		}

		int half = (WIDTH - PAD * 2 - GAP) / 2;
		screen.button("edit", PAD, buttonsTop, half, 20, Map.of(
			ComponentType.PROP_LABEL, text("minicraft-justfatlard.choice.edit", "Put it on the plate")));
		screen.button("salvage", PAD + half + GAP, buttonsTop, half, 20, Map.of(
			ComponentType.PROP_LABEL, text("minicraft-justfatlard.choice.salvage", "Break it down"),
			ComponentType.PROP_ENABLED, String.valueOf(!lines.isEmpty() || function.isPresent())));

		asked.put(player.getUUID(), new Asked(level, bench.immutable(), hand, stack.copyWithCount(1), screen.screenId()));
		PandoricalApi.screens().open(player, screen.build());
	}

	private record Line(Item item, long cells) {}

	/** Cells cut {@code from} to a side as whole cells cut {@code to}: what will show in the budget, a sliver aside. */
	private static long convert(long cells, int from, int to) {
		if (from == to) return cells;
		return java.math.BigInteger.valueOf(cells).multiply(java.math.BigInteger.valueOf((long) to * to * to))
			.divide(java.math.BigInteger.valueOf((long) from * from * from)).longValueExact();
	}

	private static String text(String key, String fallback, Object... args) {
		return Component.translatableWithFallback(key, fallback, args).getString();
	}
}
