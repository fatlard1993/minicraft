package justfatlard.minicraft;

import justfatlard.pandorical.api.ComponentType;
import justfatlard.pandorical.api.ComponentUpdateBuilder;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.ScreenApi;
import justfatlard.pandorical.api.ScreenBuilder;
import justfatlard.pandorical.protocol.ComponentUpdate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The dialog a sneaking, empty-handed right-click on a placed miniature opens: what building it at
 * full size takes, against what the player carries; why it cannot be built, if it cannot; and
 * Construct, Preview and, for a miniature with a handle, Work the handle.
 *
 * <p>Kept current four times a second while open, so a stack picked up or a block cleared shows
 * at once. Construct is only a request: {@link Constructing#construct} surveys everything again on
 * the server at the moment it is pressed. Drawn by Pandorical, as the bench is.
 */
public final class ConstructScreen {
	private ConstructScreen() {}

	static final String TYPE = Main.MOD_ID + ":construct";
	private static final double REACH = 8.0;

	private static final int PAD = 8;
	private static final int WIDTH = 280;
	private static final int GAP = 4;
	private static final int SHOWN = 8;
	private static final int ROW = 18;

	private static final String DARK = "#404040";
	private static final String GREY = "#707070";
	private static final String RED = "#A02020";
	private static final String GREEN = "#2E7D32";

	/** What one player has open: the miniature, the screen, and what was last sent of each line. */
	private record Open(ServerLevel level, BlockPos at, String screenId, int lines, Map<String, String> sent) {}

	private static final Map<UUID, Open> open = new HashMap<>();

	public static void register() {
		ScreenApi screens = PandoricalApi.screens();
		screens.onAction(TYPE, "construct", (player, data) -> at(player, (level, at) -> {
			Constructing.Why why = Constructing.construct(player, level, at);
			if (why == Constructing.Why.READY) {
				close(player);
				return;
			}
			// Refused: say why where they are looking, and show it as it is now.
			Constructing.Survey survey = Constructing.survey(player, level, at);
			if (survey != null) player.sendOverlayMessage(Constructing.reason(survey));
			refresh(player);
		}));
		screens.onAction(TYPE, "preview", (player, data) -> at(player, (level, at) -> {
			Preview.toggle(player, level, at);
			refresh(player);
		}));
		transform(screens, "turn_left", transform -> transform.turned(-1));
		transform(screens, "turn_right", transform -> transform.turned(1));
		transform(screens, "mirror_lr", Transform::mirroredLeftRight);
		transform(screens, "mirror_fb", Transform::mirroredFrontBack);
		transform(screens, "flip", Transform::flipped);
		transform(screens, "reset", transform -> Transform.NONE);
		screens.onClose(TYPE, player -> open.remove(player.getUUID()));
	}

	/** A button that changes how the build will stand, kept on the miniature; the dialog and the preview follow. */
	private static void transform(ScreenApi screens, String id, java.util.function.UnaryOperator<Transform> change) {
		screens.onAction(TYPE, id, (player, data) -> at(player, (level, at) -> {
			if (level.getBlockEntity(at) instanceof MiniatureBlockEntity miniature) miniature.setTransform(change.apply(miniature.transform()));
			refresh(player);
		}));
	}

	public static void forget(UUID player) {
		open.remove(player);
	}

	/** The screen id this player has open, or null: for a test pressing its buttons as a click would. */
	public static String openId(ServerPlayer player) {
		Open shown = open.get(player.getUUID());
		return shown == null ? null : shown.screenId();
	}

	private interface Act {
		void at(ServerLevel level, BlockPos at);
	}

	/** A button pressed: only on the miniature the dialog is for, still there, and still in reach. */
	private static void at(ServerPlayer player, Act act) {
		Open shown = open.get(player.getUUID());
		if (shown == null) return;
		if (!stillHere(player, shown)) {
			close(player);
			return;
		}
		act.at(shown.level(), shown.at());
	}

	private static boolean stillHere(ServerPlayer player, Open shown) {
		return !player.isRemoved() && player.level() == shown.level()
			&& player.distanceToSqr(Vec3.atCenterOf(shown.at())) <= REACH * REACH
			&& build(shown.level(), shown.at()) != null;
	}

	private static Miniature build(ServerLevel level, BlockPos at) {
		return level.getBlockEntity(at) instanceof MiniatureBlockEntity miniature ? miniature.looks().map(Looks::off).orElse(null) : null;
	}

	private static void close(ServerPlayer player) {
		Open shown = open.remove(player.getUUID());
		if (shown != null) PandoricalApi.screens().close(player, shown.screenId());
	}

	public static void open(ServerPlayer player, ServerLevel level, BlockPos at) {
		if (!(level.getBlockEntity(at) instanceof MiniatureBlockEntity miniature) || miniature.looks().isEmpty()) return;
		if (!PandoricalApi.hasCapability(player, "screens")) {
			player.sendOverlayMessage(Component.translatableWithFallback("minicraft-justfatlard.construct.needs_pandorical",
				"Building a miniature full size needs the Pandorical mod on your client."));
			return;
		}
		Constructing.Survey survey = Constructing.survey(player, level, at);
		int lines = survey.lines().size();
		int rows = Math.max(1, Math.min(lines, SHOWN) + (lines > SHOWN ? 1 : 0));
		int listTop = 48;
		int transformTop = listTop + rows * ROW + 6;
		int turnTop = transformTop + 24;
		int statusTop = turnTop + 2 * (16 + GAP) + 4;
		int buttonsTop = statusTop + 32;
		int height = buttonsTop + 20 + PAD;

		String title = miniature.name() != null ? miniature.name().getString()
			: text("minicraft-justfatlard.construct.title", "Miniature");
		ScreenBuilder screen = new ScreenBuilder(TYPE).size(WIDTH, height).title(title);
		screen.panel("frame", 0, 0, WIDTH, height, Map.of());
		screen.text("name", PAD, PAD, Map.of(ComponentType.PROP_TEXT, title, ComponentType.PROP_COLOR, DARK));
		screen.text("heading", PAD, PAD + 12, Map.of(
			ComponentType.PROP_TEXT, text("minicraft-justfatlard.construct.heading", "Build it full size: %s across, %s deep, %s high",
				survey.n(), survey.n(), survey.height()),
			ComponentType.PROP_COLOR, DARK));
		screen.text("note", PAD, PAD + 24, Map.of(
			ComponentType.PROP_TEXT, text("minicraft-justfatlard.construct.note", "Runs north, east and up; this is its front left corner."),
			ComponentType.PROP_COLOR, GREY));
		if (lines == 0) {
			screen.text("line0", PAD, listTop + 4, Map.of(
				ComponentType.PROP_TEXT, text("minicraft-justfatlard.construct.costs_nothing", "Costs nothing: nothing in it counts as material."),
				ComponentType.PROP_COLOR, GREY));
		}
		for (int i = 0; i < Math.min(lines, SHOWN); i++) {
			Constructing.Line line = survey.lines().get(i);
			screen.itemIcon("icon" + i, PAD, listTop + i * ROW, BuiltInRegistries.ITEM.getKey(line.item()).toString(), 1);
			screen.text("line" + i, PAD + 20, listTop + i * ROW + 4, Map.of(ComponentType.PROP_TEXT, "", ComponentType.PROP_COLOR, DARK));
		}
		if (lines > SHOWN) {
			screen.text("more", PAD, listTop + SHOWN * ROW + 4, Map.of(ComponentType.PROP_TEXT, "", ComponentType.PROP_COLOR, DARK));
		}
		screen.text("transform", PAD, transformTop, Map.of(ComponentType.PROP_TEXT, "", ComponentType.PROP_COLOR, DARK));
		screen.text("unflipped", PAD, transformTop + 11, Map.of(ComponentType.PROP_TEXT, "", ComponentType.PROP_COLOR, GREY));
		int quarter = (WIDTH - PAD * 2 - 3 * GAP) / 4;
		String[][] row1 = {{"turn_left", text("minicraft-justfatlard.construct.turn_left", "Turn left")},
			{"turn_right", text("minicraft-justfatlard.construct.turn_right", "Turn right")},
			{"mirror_lr", text("minicraft-justfatlard.construct.mirror_lr", "Mirror L-R")},
			{"mirror_fb", text("minicraft-justfatlard.construct.mirror_fb", "Mirror F-B")}};
		String[][] row2 = {{"flip", text("minicraft-justfatlard.construct.flip", "Flip")},
			{"reset", text("minicraft-justfatlard.construct.reset", "Reset")}};
		for (int i = 0; i < row1.length; i++) {
			screen.button(row1[i][0], PAD + i * (quarter + GAP), turnTop, quarter, 16, Map.of(ComponentType.PROP_LABEL, row1[i][1]));
		}
		for (int i = 0; i < row2.length; i++) {
			screen.button(row2[i][0], PAD + i * (quarter + GAP), turnTop + 16 + GAP, quarter, 16, Map.of(ComponentType.PROP_LABEL, row2[i][1]));
		}
		screen.text("status", PAD, statusTop, Map.of(ComponentType.PROP_TEXT, "", ComponentType.PROP_COLOR, DARK,
			ComponentType.PROP_WRAP_WIDTH, String.valueOf(WIDTH - PAD * 2), ComponentType.PROP_MAX_LINES, "3"));
		int half = (WIDTH - PAD * 2 - GAP) / 2;
		screen.button("construct", PAD, buttonsTop, half, 20, Map.of(
			ComponentType.PROP_LABEL, text("minicraft-justfatlard.construct.construct", "Construct"),
			ComponentType.PROP_ENABLED, String.valueOf(survey.ready())));
		screen.button("preview", PAD + half + GAP, buttonsTop, half, 20, Map.of(ComponentType.PROP_LABEL, previewLabel(player, at)));
		Map<String, String> sent = new HashMap<>();
		sent.put("construct", String.valueOf(survey.ready()));
		sent.put("preview", previewLabel(player, at));
		Open shown = new Open(level, at.immutable(), screen.screenId(), lines, sent);
		open.put(player.getUUID(), shown);
		PandoricalApi.screens().open(player, screen.build());
		refresh(player, shown, survey);
	}

	private static String previewLabel(ServerPlayer player, BlockPos at) {
		return Preview.showing(player, at)
			? text("minicraft-justfatlard.construct.preview_on", "Preview: on")
			: text("minicraft-justfatlard.construct.preview_off", "Preview: off");
	}

	/** Four times a second for every open dialog: closed if its miniature is gone or out of reach, and kept current. */
	public static void tick(MinecraftServer server) {
		if (open.isEmpty() || server.getTickCount() % 5 != 0) return;
		for (Map.Entry<UUID, Open> entry : List.copyOf(open.entrySet())) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			Open shown = entry.getValue();
			if (player == null || !shown.screenId().equals(PandoricalApi.getOpenScreenId(player.getUUID()))) {
				open.remove(entry.getKey(), shown);
				continue;
			}
			if (!stillHere(player, shown)) {
				close(player);
				continue;
			}
			refresh(player);
		}
	}

	static void refresh(ServerPlayer player) {
		Open shown = open.get(player.getUUID());
		if (shown == null) return;
		Constructing.Survey survey = Constructing.survey(player, shown.level(), shown.at());
		if (survey != null) refresh(player, shown, survey);
	}

	/** Each line as it is now, sent only where it changed. */
	private static void refresh(ServerPlayer player, Open shown, Constructing.Survey survey) {
		Map<String, String[]> texts = new LinkedHashMap<>();
		List<Constructing.Line> lines = survey.lines();
		for (int i = 0; i < Math.min(lines.size(), Math.min(shown.lines(), SHOWN)); i++) {
			Constructing.Line line = lines.get(i);
			String name = new ItemStack(line.item()).getHoverName().getString();
			texts.put("line" + i, new String[] {
				text("minicraft-justfatlard.construct.line", "%s: %s needed, %s carried", name, line.cost(), line.held()),
				survey.free() || line.enough() ? DARK : RED});
		}
		if (shown.lines() > SHOWN && lines.size() > SHOWN) {
			long short_ = lines.subList(SHOWN, lines.size()).stream().filter(line -> !line.enough()).count();
			texts.put("more", new String[] {
				text("minicraft-justfatlard.construct.more", "and %s more, %s of them short", lines.size() - SHOWN, short_),
				short_ > 0 && !survey.free() ? RED : DARK});
		}
		texts.put("transform", new String[] {text("minicraft-justfatlard.construct.transform", "Stands: %s", survey.transform().describe().getString()), DARK});
		texts.put("unflipped", new String[] {survey.transform().upsideDown() && survey.unflipped() > 0
			? text("minicraft-justfatlard.construct.unflipped", "Won't flip: %s blocks, kept as they are", survey.unflipped()) : "",
			survey.fallen() > 0 ? RED : GREY});
		texts.put("status", new String[] {Constructing.reason(survey).getString(), survey.ready() ? GREEN : RED});
		List<ComponentUpdate> updates = new ArrayList<>();
		texts.forEach((id, value) -> {
			String now = value[0] + "|" + value[1];
			if (now.equals(shown.sent().put(id, now))) return;
			updates.add(new ComponentUpdateBuilder(id).prop(ComponentType.PROP_TEXT, value[0]).prop(ComponentType.PROP_COLOR, value[1]).build());
		});
		String ready = String.valueOf(survey.ready());
		if (!ready.equals(shown.sent().put("construct", ready))) {
			updates.add(new ComponentUpdateBuilder("construct").prop(ComponentType.PROP_ENABLED, ready).build());
		}
		String preview = previewLabel(player, shown.at());
		if (!preview.equals(shown.sent().put("preview", preview))) {
			updates.add(new ComponentUpdateBuilder("preview").prop(ComponentType.PROP_LABEL, preview).build());
		}
		if (!updates.isEmpty()) PandoricalApi.screens().update(player, shown.screenId(), updates);
	}

	private static String text(String key, String fallback, Object... args) {
		return Component.translatableWithFallback(key, fallback, args).getString();
	}
}
