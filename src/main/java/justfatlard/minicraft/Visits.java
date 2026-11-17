package justfatlard.minicraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Going into the workshop and coming out, and what holds while a builder is there.
 *
 * <p>In there a builder is in survival, not creative: creative's inventory is endless, and the
 * whole point is building with what was loaded. They fly, blocks come away at a touch, they reach
 * a little further, and their pack holds their materials: one stack per material, each a window
 * onto that material's budget, topped up from it as it is spent. The budget is the record, never
 * the pack; the pack is emptied on the way out and what they came in with is put back.
 *
 * <p>Any number may build in one box at once. Each has their own pack put aside on their own
 * player, as one would; all of them build from the bench's one budget. Their windows are views of
 * it and nothing more: every place and break is checked against the ledger and changes it on the
 * server thread, one at a time, and everyone's windows are laid out again from it after each. An
 * item in a window is never material: nothing in the workshop counts it, it cannot leave, and it is
 * gone when its builder steps out.
 */
public final class Visits {
	private Visits() {}

	/** Where the way out sits in the hotbar, and the stick that marks a handle beside it. */
	private static final int LEAVE_SLOT = 8;
	private static final int MARK_SLOT = 7;
	/** The pack's main slots: hotbar and the three rows above it. */
	private static final int PACK = Inventory.INVENTORY_SIZE;

	private static final Identifier FAST_HANDS = Identifier.fromNamespaceAndPath(Main.MOD_ID, "fast_hands");
	private static final Identifier LONG_ARMS = Identifier.fromNamespaceAndPath(Main.MOD_ID, "long_arms");
	private static final String LEAVE_MARK = "minicraft_leave";
	private static final String HANDLE_MARK = "minicraft_handle_marker";

	// --- In and out ---

	/**
	 * Worlds whose mod holds the player's own things the way the workshop does: a PvP Dimensions arena,
	 * the Learning Blocks classroom. Stepping in from one would put that mod's pack aside here as theirs,
	 * while crossing out of its world made it hand the real one back in the workshop; coming back, the
	 * other mod's pack would come home as theirs. So the bench there takes nobody in.
	 */
	private static final java.util.Set<String> HELD_ELSEWHERE = java.util.Set.of("pvp-dimensions-justfatlard", "learning-blocks-justfatlard");

	/** Take this player into the plot's box. Says why not and returns false if they cannot go. */
	public static boolean enter(ServerPlayer player, int plot) {
		MinecraftServer server = player.level().getServer();
		Ledger ledger = Ledger.get(server);
		Ledger.Project project = ledger.project(plot);
		ServerLevel workshop = Workshop.level(server);
		if (project == null || workshop == null || player.isSpectator()) return false;
		if (project.n() == 0) return tell(player, "minicraft-justfatlard.enter.no_size", "Choose a size first.");
		if (Session.of(player) != null) return tell(player, "minicraft-justfatlard.enter.already", "You are already in a workshop.");
		if (HELD_ELSEWHERE.contains(player.level().dimension().identifier().getNamespace())) {
			return tell(player, "minicraft-justfatlard.enter.held", "Not from here: leave this world first.");
		}
		if (project.capturing()) {
			return tell(player, "minicraft-justfatlard.enter.trying", "A build is being tried out at this bench.");
		}

		player.stopRiding();
		// Anything on the cursor or in the little crafting grid back into the pack first, so it is
		// put aside with the rest rather than turning up in the workshop and being cleared away.
		if (player.containerMenu != player.inventoryMenu) player.closeContainer();
		player.inventoryMenu.removed(player);
		Session.set(player, new Session(plot, Session.Home.of(player), Session.stashOf(player),
			player.gameMode().getName(), player.getAbilities().mayfly));
		ledger.arrive(plot, player.getUUID(), player.getName().getString());

		player.getInventory().clearContent();
		player.removeAllEffects();
		if (player.gameMode() != GameType.SURVIVAL) player.setGameMode(GameType.SURVIVAL);

		Workshop.prepare(workshop, plot, project.n());
		Vec3 arrival = Workshop.arrival(plot, project.n());
		player.teleportTo(workshop, arrival.x, arrival.y, arrival.z, Set.<Relative>of(), 180F, 20F, true);
		equip(player);
		player.getAbilities().flying = true;
		player.onUpdateAbilities();
		refreshPack(player);
		explainHandle(player);
		workshop.playSound(null, arrival.x, arrival.y, arrival.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.2F);
		return true;
	}

	/** Back to exactly where they stood, with exactly what they carried. */
	public static void leave(ServerPlayer player) {
		Session session = Session.of(player);
		if (session == null) return;
		MinecraftServer server = player.level().getServer();
		Session.Home home = session.home();
		ServerLevel there = server.getLevel(home.dimension());
		if (there != null) {
			player.teleportTo(there, home.x(), home.y(), home.z(), Set.<Relative>of(), home.yaw(), home.pitch(), true);
		} else {
			// The world they came from is gone. Spawn always exists, and beats a sealed workshop.
			ServerLevel overworld = server.overworld();
			BlockPos spawn = overworld.getRespawnData().pos();
			player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, Set.<Relative>of(), 0F, 0F, true);
		}
		// Crossing out of the workshop finishes the visit as it happens (see Main); finished once only.
		if (Session.of(player) != null) finish(player, session);
	}

	/** Everything about being in the workshop undone, wherever the player now is. */
	static void finish(ServerPlayer player, Session session) {
		Ledger ledger = Ledger.get(player.level().getServer());
		ledger.depart(session.plot(), player.getUUID());

		// Windows held anywhere but the pack go too: on the cursor, in the little crafting grid, or
		// in a crafting table, stonecutter or anvil built in the box, any of which the game would
		// otherwise hand back to the restored pack as its screen closed. Closed first, so what it
		// hands back comes to the workshop pack, and is cleared with it.
		if (player.containerMenu != player.inventoryMenu) player.closeContainer();
		player.inventoryMenu.setCarried(ItemStack.EMPTY);
		player.inventoryMenu.getCraftSlots().clearContent();
		Inventory inventory = player.getInventory();
		inventory.clearContent();
		for (Session.Slot slot : session.stash()) {
			ItemStack stack = slot.stack().copy();
			if (slot.slot() >= 0 && slot.slot() < inventory.getContainerSize() && inventory.getItem(slot.slot()).isEmpty()) {
				inventory.setItem(slot.slot(), stack);
			} else if (!inventory.add(stack)) {
				player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
			}
		}

		GameType before = GameType.byName(session.gameMode(), GameType.SURVIVAL);
		if (player.gameMode() != before) player.setGameMode(before);
		if (!player.gameMode().isCreative() && !player.isSpectator()) {
			player.getAbilities().mayfly = session.mayFly();
			if (!session.mayFly()) player.getAbilities().flying = false;
			player.onUpdateAbilities();
		}
		unequip(player);
		Session.set(player, null);
		player.containerMenu.broadcastChanges();
	}

	// --- While there ---

	/** Flight allowed, quick hands, long arms. Kept up once a second, so it never lapses; flying itself is the builder's to switch. */
	private static void equip(ServerPlayer player) {
		if (!player.getAbilities().mayfly) {
			player.getAbilities().mayfly = true;
			player.onUpdateAbilities();
		}
		modify(player.getAttribute(Attributes.BLOCK_BREAK_SPEED), FAST_HANDS, 1023);
		modify(player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE), LONG_ARMS, 4);
	}

	private static void unequip(ServerPlayer player) {
		AttributeInstance speed = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
		if (speed != null) speed.removeModifier(FAST_HANDS);
		AttributeInstance range = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
		if (range != null) range.removeModifier(LONG_ARMS);
	}

	private static void modify(AttributeInstance attribute, Identifier id, double amount) {
		if (attribute != null && !attribute.hasModifier(id)) {
			attribute.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
		}
	}

	/**
	 * Lay the pack out to match the budget: every material a stack, as full as the budget allows,
	 * in the slot the builder left it in; anything that is not a material gone; the way out in its
	 * slot.
	 */
	public static void refreshPack(ServerPlayer player) {
		Session session = Session.of(player);
		if (session == null) return;
		Ledger.Project project = Ledger.get(player.level().getServer()).project(session.plot());
		if (project == null) return;
		Inventory inventory = player.getInventory();
		List<Item> materials = project.materials();
		Set<Item> shown = new HashSet<>();

		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (slot == LEAVE_SLOT) {
				if (!isLeave(stack)) inventory.setItem(slot, leaveItem());
				continue;
			}
			if (slot == MARK_SLOT) {
				if (!isMarker(stack)) inventory.setItem(slot, markerItem());
				continue;
			}
			if (stack.isEmpty()) continue;
			Item item = stack.getItem();
			if (slot < PACK && materials.contains(item) && shown.add(item)) {
				int want = (int) Math.min(project.cells(item), stack.getMaxStackSize());
				if (stack.getCount() != want || !stack.getComponentsPatch().isEmpty()) inventory.setItem(slot, new ItemStack(item, want));
			} else {
				inventory.setItem(slot, ItemStack.EMPTY);
			}
		}
		for (Item item : materials) {
			if (shown.contains(item)) continue;
			for (int slot = 0; slot < PACK; slot++) {
				if (slot == LEAVE_SLOT || slot == MARK_SLOT || !inventory.getItem(slot).isEmpty()) continue;
				ItemStack stack = new ItemStack(item);
				stack.setCount((int) Math.min(project.cells(item), stack.getMaxStackSize()));
				inventory.setItem(slot, stack);
				break;
			}
		}
		player.containerMenu.broadcastChanges();
	}

	static ItemStack leaveItem() {
		ItemStack stack = new ItemStack(Items.ENDER_EYE);
		CompoundTag mark = new CompoundTag();
		mark.putBoolean(LEAVE_MARK, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(mark));
		stack.set(DataComponents.CUSTOM_NAME, Component.translatableWithFallback("minicraft-justfatlard.leave", "Back to the bench"));
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		return stack;
	}

	public static boolean isLeave(ItemStack stack) {
		return marked(stack, LEAVE_MARK);
	}

	/** The handle explained in chat on stepping in, the first few times, and never again once one has been marked. */
	static final int HANDLE_HINTS = 3;

	private static void explainHandle(ServerPlayer player) {
		int told = player.getAttachedOrElse(Session.HANDLE_HINTS, 0);
		if (told >= HANDLE_HINTS) return;
		player.setAttached(Session.HANDLE_HINTS, told + 1);
		net.minecraft.ChatFormatting grey = net.minecraft.ChatFormatting.GRAY;
		player.sendSystemMessage(Component.translatableWithFallback("minicraft-justfatlard.hint.handle1",
			"Optional, a handle: the stick in your pack marks one lever, button or door in the box as the miniature's handle.").withStyle(net.minecraft.ChatFormatting.YELLOW));
		player.sendSystemMessage(Component.translatableWithFallback("minicraft-justfatlard.hint.handle2",
			"Right-click the placed miniature to work it, and while it is on it gives off a redstone signal, like a lever: a tiny door that opens, a tiny lever that runs a real circuit.").withStyle(grey));
		player.sendSystemMessage(Component.translatableWithFallback("minicraft-justfatlard.hint.handle3",
			"It is instead of a block in the bench's functional slot: a miniature has one or the other.").withStyle(grey));
	}

	static ItemStack markerItem() {
		ItemStack stack = new ItemStack(Items.STICK);
		CompoundTag mark = new CompoundTag();
		mark.putBoolean(HANDLE_MARK, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(mark));
		stack.set(DataComponents.CUSTOM_NAME, Component.translatableWithFallback("minicraft-justfatlard.marker", "Mark the handle (optional)"));
		net.minecraft.network.chat.Style plain = net.minecraft.network.chat.Style.EMPTY.withItalic(false).withColor(net.minecraft.ChatFormatting.GRAY);
		stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
			Component.translatableWithFallback("minicraft-justfatlard.marker.lore1", "Right-click a lever, button or door in the box").withStyle(plain),
			Component.translatableWithFallback("minicraft-justfatlard.marker.lore2", "to make it the miniature's handle. Placed,").withStyle(plain),
			Component.translatableWithFallback("minicraft-justfatlard.marker.lore3", "a right-click works it: a redstone signal").withStyle(plain),
			Component.translatableWithFallback("minicraft-justfatlard.marker.lore4", "while it is on, like a lever.").withStyle(plain),
			Component.translatableWithFallback("minicraft-justfatlard.marker.lore5", "Instead of a block in the functional slot.").withStyle(plain))));
		return stack;
	}

	public static boolean isMarker(ItemStack stack) {
		return marked(stack, HANDLE_MARK);
	}

	private static boolean marked(ItemStack stack, String key) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().getBooleanOr(key, false);
	}

	/**
	 * The stick on a block in the box: that block is the handle, worked when the miniature is
	 * right-clicked. On the handle again, there is no handle.
	 */
	public static void markHandle(ServerPlayer player, BlockPos pos) {
		Session session = Session.of(player);
		if (session == null) return;
		Ledger ledger = Ledger.get(player.level().getServer());
		Ledger.Project project = ledger.project(session.plot());
		if (project == null || !Workshop.inBox(pos, session.plot(), project.n(), project.height()) || player.level().getBlockState(pos).isAir()) return;
		BlockPos cell = pos.subtract(Workshop.origin(session.plot()));
		if (!cell.equals(project.handle()) && !project.function().isEmpty()) {
			player.sendOverlayMessage(Component.translatableWithFallback("minicraft-justfatlard.handle.has_function", Bench.FUNCTION_FIRST));
			return;
		}
		if (cell.equals(project.handle())) {
			ledger.setHandle(session.plot(), null);
			player.sendOverlayMessage(Component.translatableWithFallback("minicraft-justfatlard.handle.cleared", "No handle."));
		} else {
			ledger.setHandle(session.plot(), cell);
			player.setAttached(Session.HANDLE_HINTS, HANDLE_HINTS);
			player.sendOverlayMessage(Component.translatableWithFallback("minicraft-justfatlard.handle.set",
				"The %s is the handle.", player.level().getBlockState(pos).getBlock().getName()));
		}
	}

	/** How many of its item a block stands for when it is broken: two slabs in a double slab, four candles in a cluster. */
	static int blocksIn(BlockState state) {
		if (state.hasProperty(SlabBlock.TYPE) && state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) return 2;
		for (IntegerProperty counted : List.of(BlockStateProperties.LAYERS, BlockStateProperties.CANDLES,
				BlockStateProperties.PICKLES, BlockStateProperties.EGGS, BlockStateProperties.FLOWER_AMOUNT,
				BlockStateProperties.SEGMENT_AMOUNT)) {
			if (state.hasProperty(counted)) return state.getValue(counted);
		}
		return 1;
	}

	/**
	 * The material a block in the box stands for: the item it is made from, and what placing it
	 * took off the budget. A potted plant is its pot and a cake with a candle its cake, as the
	 * plant and the candle went in from the pack with a click, not placed. Air for a block no
	 * material stands for (a piston's head, fire). Copying a miniature charges by this too
	 * ({@link Copying}), so a copy costs what breaking the build in the box would give back.
	 */
	static Item materialOf(BlockState state) {
		net.minecraft.world.level.block.Block block = state.getBlock();
		if (block instanceof net.minecraft.world.level.block.FlowerPotBlock && block != net.minecraft.world.level.block.Blocks.FLOWER_POT) return Items.FLOWER_POT;
		if (block instanceof net.minecraft.world.level.block.CandleCakeBlock) return Items.CAKE;
		return block.asItem();
	}

	/** A block broken in the box goes back into the budget as the item it is made from. */
	public static void broke(ServerPlayer player, BlockPos pos, BlockState state) {
		Session session = Session.of(player);
		if (session == null) return;
		Ledger ledger = Ledger.get(player.level().getServer());
		Ledger.Project project = ledger.project(session.plot());
		if (project == null || !Workshop.inBox(pos, session.plot(), project.n(), project.height())) return;
		Item item = materialOf(state);
		if (item == Items.AIR) return;
		ledger.change(session.plot(), item, blocksIn(state));
		refreshBuilders(player.level().getServer(), session.plot());
	}

	/** A block placed in the box comes out of the budget. */
	public static void placed(ServerPlayer player, Item item) {
		Session session = Session.of(player);
		if (session == null) return;
		Ledger.get(player.level().getServer()).change(session.plot(), item, -1);
		refreshBuilders(player.level().getServer(), session.plot());
	}

	/**
	 * Every builder in this plot's box who is here, their windows laid out again from the budget:
	 * after any change to it, whoever made it, so one placing the last of something empties it for
	 * all and one breaking it gives it back to all.
	 */
	public static void refreshBuilders(MinecraftServer server, int plot) {
		Ledger.Project project = Ledger.get(server).project(plot);
		if (project == null) return;
		for (java.util.UUID id : List.copyOf(project.occupants().keySet())) {
			ServerPlayer builder = server.getPlayerList().getPlayer(id);
			Session session = builder == null ? null : Session.of(builder);
			if (session != null && session.plot() == plot) refreshPack(builder);
		}
	}

	/**
	 * Whether this player may put a block of this item at this spot: in their box, with some of it
	 * left in the budget, whatever their window shows. Asked at the start of every placing and
	 * spent at its end, on the server thread, so two builders after the last cell of something get
	 * it one at a time: the first has it, and the second is refused.
	 */
	public static boolean mayPlace(ServerPlayer player, BlockPos pos, Item item) {
		Session session = Session.of(player);
		if (session == null) return player.isCreative();
		Ledger.Project project = Ledger.get(player.level().getServer()).project(session.plot());
		return project != null && Workshop.inBox(pos, session.plot(), project.n(), project.height()) && project.cells(item) > 0;
	}

	/**
	 * Twice a second for everyone in the workshop: back inside the bounds if they have strayed,
	 * home if they have no business there, and once a second the pack set straight, flight kept
	 * on, and the box's edges drawn for them.
	 */
	public static void watch(MinecraftServer server) {
		ServerLevel workshop = Workshop.level(server);
		if (workshop == null || server.getTickCount() % 10 != 0) return;
		boolean everySecond = server.getTickCount() % 20 == 0;
		Ledger ledger = Ledger.get(server);
		if (everySecond) reconcile(server, ledger);
		for (ServerPlayer player : List.copyOf(workshop.players())) {
			Session session = Session.of(player);
			if (session == null) {
				if (!player.isCreative() && !player.isSpectator()) {
					ServerLevel overworld = server.overworld();
					BlockPos spawn = overworld.getRespawnData().pos();
					player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, Set.<Relative>of(), 0F, 0F, true);
				}
				continue;
			}
			Ledger.Project project = ledger.project(session.plot());
			if (project == null || project.n() == 0) {
				leave(player);
				continue;
			}
			// Inside by the ledger too, should a stop between the two saves have left them out of it,
			// and by the name they have now (a world from before had no names kept).
			ledger.arrive(session.plot(), player.getUUID(), player.getName().getString());
			AABB bounds = Workshop.bounds(session.plot(), project.n());
			if (!bounds.contains(player.position())) {
				Vec3 arrival = Workshop.arrival(session.plot(), project.n());
				player.teleportTo(workshop, arrival.x, arrival.y, arrival.z, Set.<Relative>of(), player.getYRot(), player.getXRot(), false);
			}
			if (!everySecond) continue;
			if (player.gameMode() != GameType.SURVIVAL) player.setGameMode(GameType.SURVIVAL);
			equip(player);
			refreshPack(player);
			outline(workshop, player, session.plot(), project.n(), project.height());
		}
	}

	/**
	 * Nobody counted inside a box who is here and is not in it: their visit is kept on them, saved
	 * with their pack, so it is the word on where they are. One away is still inside, as their visit
	 * is, and comes back to the box when they return.
	 */
	private static void reconcile(MinecraftServer server, Ledger ledger) {
		for (int plot : ledger.occupiedPlots()) {
			for (java.util.UUID id : List.copyOf(ledger.project(plot).occupants().keySet())) {
				ServerPlayer player = server.getPlayerList().getPlayer(id);
				if (player == null) continue;
				Session session = Session.of(player);
				if (session == null || session.plot() != plot) ledger.depart(plot, id);
			}
		}
	}

	/** The box's twelve edges, a mote a block, for this builder alone, sent together as one bundle. */
	private static void outline(ServerLevel level, ServerPlayer player, int plot, int n, int height) {
		BlockPos min = Workshop.origin(plot);
		BlockPos handle = Ledger.get(level.getServer()).project(plot).handle();
		BlockPos near = player.blockPosition();
		List<Packet<? super ClientGamePacketListener>> motes = new ArrayList<>();
		if (handle != null) {
			BlockPos at = min.offset(handle);
			add(motes, near, new ClientboundLevelParticlesPacket(ParticleTypes.HAPPY_VILLAGER, false, false,
				at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 0.3F, 0.3F, 0.3F, 0, 4));
		}
		for (int i = 0; i <= n; i++) {
			for (int a = 0; a <= 1; a++) for (int b = 0; b <= 1; b++) {
				mote(motes, near, min.getX() + i, min.getY() + a * height, min.getZ() + b * n);
				if (i <= height) mote(motes, near, min.getX() + a * n, min.getY() + i, min.getZ() + b * n);
				mote(motes, near, min.getX() + a * n, min.getY() + b * height, min.getZ() + i);
			}
		}
		if (!motes.isEmpty()) player.connection.send(new ClientboundBundlePacket(motes));
	}

	private static void mote(List<Packet<? super ClientGamePacketListener>> motes, BlockPos near, double x, double y, double z) {
		add(motes, near, new ClientboundLevelParticlesPacket(ParticleTypes.END_ROD, false, false, x, y, z, 0, 0, 0, 0, 1));
	}

	/** Only the motes vanilla would send them one by one: those within its particle range of the builder. */
	private static void add(List<Packet<? super ClientGamePacketListener>> motes, BlockPos near, ClientboundLevelParticlesPacket mote) {
		if (near.closerToCenterThan(new Vec3(mote.x(), mote.y(), mote.z()), 32.0)) motes.add(mote);
	}

	private static boolean tell(ServerPlayer player, String key, String fallback) {
		player.sendOverlayMessage(Component.translatableWithFallback(key, fallback));
		return false;
	}
}
