package justfatlard.minicraft.gametest;

import justfatlard.minicraft.Bench;
import justfatlard.minicraft.BenchBlockEntity;
import justfatlard.minicraft.Ledger;
import justfatlard.minicraft.Main;
import justfatlard.minicraft.Miniature;
import justfatlard.minicraft.MiniatureBlock;
import justfatlard.minicraft.MiniatureBlockEntity;
import justfatlard.minicraft.Session;
import justfatlard.minicraft.Visits;
import justfatlard.minicraft.Workshop;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * The whole loop, through the server's own paths: a bench sized and loaded, a builder in and out
 * with their pack untouched, blocks placed and broken against the budget, the box held to its
 * edges, nothing dropped and nothing growing, a miniature finished, placed, drawn, broken and
 * given back whole, the leftovers taken out whole, and a miniature put back on the bench and
 * changed.
 */
public final class BenchLoop implements FabricClientGameTest {
	private static final int N = 4;
	private static final long PER_BLOCK = N * N * N;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			server.runCommand("gamemode survival @a");
			BlockPos origin = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());
			BlockPos bench = origin.east(2);

			int plot = server.computeOnServer(s -> {
				s.overworld().setBlockAndUpdate(bench, Main.BENCH.defaultBlockState());
				return ((BenchBlockEntity) s.overworld().getBlockEntity(bench)).plot();
			});

			// Sized, then loaded: N³ cells a block, the stack spent, and a shulker box refused.
			server.runOnServer(s -> Ledger.get(s).setSize(plot, N));
			hold(server, connection, new ItemStack(Items.OAK_PLANKS, 3));
			server.runOnServer(s -> Bench.load(connection.getServerPlayer(), s.overworld(), bench, connection.getServerPlayer().getMainHandItem()));
			check(cells(server, plot) == 3 * PER_BLOCK, "three planks did not load as " + 3 * PER_BLOCK + " cells: " + cells(server, plot));
			check(server.computeOnServer(s -> connection.getServerPlayer().getMainHandItem().isEmpty()), "the loaded planks were not spent");
			check(!Bench.loadable(new ItemStack(Items.SHULKER_BOX)), "a shulker box was loadable");

			// In: the pack put aside, the materials handed over, flight allowed.
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
				player.getInventory().setItem(5, new ItemStack(Items.DIAMOND, 7));
			});
			check(server.computeOnServer(s -> Visits.enter(connection.getServerPlayer(), plot)), "could not step in");
			context.waitTicks(5);
			check(server.computeOnServer(s -> Workshop.isWorkshop(connection.getServerPlayer().level())), "not in the workshop");
			check(server.computeOnServer(s -> connection.getServerPlayer().getInventory().countItem(Items.DIAMOND)) == 0, "the diamonds came in");
			check(server.computeOnServer(s -> connection.getServerPlayer().getInventory().countItem(Items.OAK_PLANKS)) == 64, "the planks window does not show a stack");
			check(server.computeOnServer(s -> connection.getServerPlayer().getAbilities().mayfly), "cannot fly in the workshop");

			// Placing in the box spends a cell; outside the box is refused.
			BlockPos box = Workshop.origin(plot);
			check(place(server, connection, box.below(), Items.OAK_PLANKS).consumesAction(), "could not place in the box");
			check(cells(server, plot) == 3 * PER_BLOCK - 1, "placing did not spend a cell: " + cells(server, plot));
			place(server, connection, box.east().below(), Items.OAK_PLANKS);
			BlockPos outside = box.west(2);
			check(!place(server, connection, outside.below(), Items.OAK_PLANKS).consumesAction(), "placed outside the box");
			check(server.computeOnServer(s -> Workshop.level(s).getBlockState(outside).isAir()), "a block stands outside the box");
			check(cells(server, plot) == 3 * PER_BLOCK - 2, "the refused place spent a cell: " + cells(server, plot));

			// The stick in the pack marks a block in the box as the handle, and marks it off again.
			check(server.computeOnServer(s -> justfatlard.minicraft.Visits.isMarker(connection.getServerPlayer().getInventory().getItem(7))), "no stick in the pack");
			Runnable mark = () -> server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				player.getInventory().setSelectedSlot(7);
				player.gameMode.useItemOn(player, Workshop.level(s), player.getMainHandItem(), InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.atCenterOf(box), Direction.UP, box, false));
			});
			mark.run();
			check(server.computeOnServer(s -> BlockPos.ZERO.equals(Ledger.get(s).project(plot).handle())), "the stick did not mark the handle");
			mark.run();
			check(server.computeOnServer(s -> Ledger.get(s).project(plot).handle() == null), "the stick did not mark the handle off");
			check(cells(server, plot) == 3 * PER_BLOCK - 2, "marking the handle touched the budget: " + cells(server, plot));

			// Breaking in the box refunds; the floor cannot be broken; nothing drops.
			place(server, connection, box.above().below(), Items.OAK_PLANKS);
			server.runOnServer(s -> connection.getServerPlayer().gameMode.destroyBlock(box.above()));
			check(cells(server, plot) == 3 * PER_BLOCK - 2, "breaking did not refund: " + cells(server, plot));
			server.runOnServer(s -> connection.getServerPlayer().gameMode.destroyBlock(box.below().west(2)));
			check(server.computeOnServer(s -> !Workshop.level(s).getBlockState(box.below().west(2)).isAir()), "the floor broke");
			int drops = server.computeOnServer(s -> Workshop.level(s).getEntitiesOfClass(ItemEntity.class, new AABB(box).inflate(16)).size());
			check(drops == 0, "something dropped in the workshop: " + drops);

			// Nothing grows: a sapling under a furious random tick stays a sapling.
			BlockPos garden = box.offset(-3, 0, -3);
			server.runOnServer(s -> {
				Workshop.level(s).setBlockAndUpdate(garden.below(), Blocks.DIRT.defaultBlockState());
				Workshop.level(s).setBlockAndUpdate(garden, Blocks.OAK_SAPLING.defaultBlockState());
			});
			server.runCommand("gamerule random_tick_speed 4096");
			context.waitTicks(40);
			server.runCommand("gamerule random_tick_speed 3");
			check(server.computeOnServer(s -> Workshop.level(s).getBlockState(garden).is(Blocks.OAK_SAPLING)), "a sapling grew in the workshop");
			server.runOnServer(s -> {
				Workshop.level(s).setBlockAndUpdate(garden, Blocks.AIR.defaultBlockState());
				Workshop.level(s).setBlockAndUpdate(garden.below(), Blocks.SMOOTH_STONE.defaultBlockState());
			});

			// Out: back where they stood, with exactly what they carried.
			server.runOnServer(s -> Visits.leave(connection.getServerPlayer()));
			context.waitTicks(5);
			check(server.computeOnServer(s -> !Workshop.isWorkshop(connection.getServerPlayer().level())), "still in the workshop");
			check(server.computeOnServer(s -> connection.getServerPlayer().getInventory().getItem(5).getCount()) == 7, "the diamonds did not come back to their slot");
			check(server.computeOnServer(s -> connection.getServerPlayer().getInventory().getItem(0).is(Items.IRON_SWORD)), "the sword did not come back");
			check(server.computeOnServer(s -> connection.getServerPlayer().getInventory().countItem(Items.OAK_PLANKS)) == 0, "planks came out of the workshop");
			check(server.computeOnServer(s -> !connection.getServerPlayer().getAbilities().mayfly), "can still fly outside");
			check(server.computeOnServer(s -> Session.of(connection.getServerPlayer()) == null), "the session outlived the visit");

			// Finished: a miniature of the two planks, and the box empty again.
			check(server.computeOnServer(s -> Bench.finish(connection.getServerPlayer(), s.overworld(), bench)), "could not finish");
			ItemStack made = server.computeOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
					if (player.getInventory().getItem(i).is(Main.MINIATURE_ITEM)) return player.getInventory().getItem(i).copy();
				}
				return ItemStack.EMPTY;
			});
			Optional<Miniature> data = Miniature.of(made);
			check(data.isPresent() && data.get().n() == N && data.get().filled() == 2, "the miniature does not hold the two planks: " + data);
			check(server.computeOnServer(s -> Workshop.level(s).getBlockState(box).isAir()), "the box was not emptied");

			// Placed: drawn as two cells. Broken: given back whole, and the drawing gone.
			BlockPos stand = origin.west(2);
			server.runOnServer(s -> s.overworld().setBlockAndUpdate(stand, Blocks.STONE.defaultBlockState()));
			hold(server, connection, made.copy());
			InteractionResult placed = server.computeOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				return player.gameMode.useItemOn(player, s.overworld(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.atCenterOf(stand).add(0, 0.5, 0), Direction.UP, stand, false));
			});
			check(placed.consumesAction(), "the miniature was not placed");
			BlockPos placedAt = stand.above();
			check(server.computeOnServer(s -> s.overworld().getBlockEntity(placedAt) instanceof MiniatureBlockEntity m && m.miniature() != null), "the placed miniature lost its build");
			check(figures(server, placedAt) == 2, "not drawn as two cells: " + figures(server, placedAt));
			server.runOnServer(s -> connection.getServerPlayer().gameMode.destroyBlock(placedAt));
			context.waitTick();
			check(figures(server, placedAt) == 0, "the drawing outlived the block");
			List<ItemEntity> back = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(placedAt).inflate(2),
				item -> item.getItem().is(Main.MINIATURE_ITEM)));
			check(back.size() == 1 && Miniature.of(back.get(0).getItem()).map(Miniature::filled).orElse(0) == 2, "the miniature did not come back whole");
			ItemStack again = back.get(0).getItem().copy();
			server.runOnServer(s -> back.get(0).discard());

			System.out.println("[minicraft-test] loop reached the leftovers");
			// Leftovers out whole, crumbs kept: 190 cells is two blocks and 62 over.
			server.runOnServer(s -> connection.getServerPlayer().getInventory().clearContent());
			server.runOnServer(s -> Bench.withdraw(connection.getServerPlayer(), s.overworld(), bench));
			check(server.computeOnServer(s -> connection.getServerPlayer().getInventory().countItem(Items.OAK_PLANKS)) == 2, "did not take out two whole planks");
			check(cells(server, plot) == 3 * PER_BLOCK - 2 - 2 * PER_BLOCK, "the crumbs are wrong: " + cells(server, plot));

			// Back on the bench, changed, finished again.
			hold(server, connection, again);
			server.runOnServer(s -> Bench.edit(connection.getServerPlayer(), s.overworld(), bench, connection.getServerPlayer().getMainHandItem()));
			check(server.computeOnServer(s -> Workshop.level(s).getBlockState(box).is(Blocks.OAK_PLANKS)), "the miniature was not stood up in the box");
			check(server.computeOnServer(s -> Visits.enter(connection.getServerPlayer(), plot)), "could not step in to change it");
			server.runOnServer(s -> connection.getServerPlayer().gameMode.destroyBlock(box.east()));
			server.runOnServer(s -> Visits.leave(connection.getServerPlayer()));
			check(server.computeOnServer(s -> Bench.finish(connection.getServerPlayer(), s.overworld(), bench)), "could not finish the change");
			int filled = server.computeOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
					Optional<Miniature> m = Miniature.of(player.getInventory().getItem(i));
					if (m.isPresent()) return m.get().filled();
				}
				return -1;
			});
			check(filled == 1, "the changed miniature does not hold one plank: " + filled);

			// The bench broken with a build on it: the build comes out as a miniature, the plot goes back.
			hold(server, connection, server.computeOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
					if (player.getInventory().getItem(i).is(Main.MINIATURE_ITEM)) return player.getInventory().removeItemNoUpdate(i);
				}
				return ItemStack.EMPTY;
			}));
			server.runOnServer(s -> Bench.edit(connection.getServerPlayer(), s.overworld(), bench, connection.getServerPlayer().getMainHandItem()));
			server.runOnServer(s -> connection.getServerPlayer().gameMode.destroyBlock(bench));
			context.waitTick();
			int dropped = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(bench).inflate(2),
				item -> item.getItem().is(Main.MINIATURE_ITEM)).size());
			check(dropped == 1, "the broken bench did not give up its build: " + dropped);
			check(server.computeOnServer(s -> Ledger.get(s).project(plot) == null), "the broken bench kept its plot");
			System.out.println("[minicraft-test] whole loop passed");
			standsOnIt(context, server, connection, origin.north(4));
			picture(context, server, connection, origin.south(24));
		}
	}

	/**
	 * A miniature that is a floor a quarter of a block thick is a quarter of a block thick to stand
	 * on, on the server and on this client both, and aimed at as one.
	 */
	private static void standsOnIt(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos at) {
		server.runCommand("gamemode survival @a");
		ItemStack floor = server.computeOnServer(s -> {
			ServerLevel workshop = Workshop.level(s);
			BlockPos min = new BlockPos(32, 200, 0);
			for (int x = 0; x < N; x++) for (int z = 0; z < N; z++) workshop.setBlock(min.offset(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
			return Miniature.capture(workshop, min, N).orElseThrow().toItem();
		});
		server.runOnServer(s -> {
			ServerLevel level = s.overworld();
			level.setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
			level.setBlockAndUpdate(at, Main.MINIATURE.defaultBlockState());
			if (level.getBlockEntity(at) instanceof MiniatureBlockEntity m) m.applyComponentsFromItemStack(floor);
			Main.MINIATURE.setPlacedBy(level, at, level.getBlockState(at), null, floor);
		});
		context.waitTicks(5);
		double serverTop = server.computeOnServer(s -> s.overworld().getBlockState(at).getCollisionShape(s.overworld(), at)
			.max(net.minecraft.core.Direction.Axis.Y));
		check(Math.abs(serverTop - 0.25) < 1.0E-6, "the server takes the floor as " + serverTop + " high, not a quarter");
		double clientTop = context.computeOnClient(client -> client.level.getBlockState(at).getCollisionShape(client.level, at)
			.max(net.minecraft.core.Direction.Axis.Y));
		check(Math.abs(clientTop - 0.25) < 1.0E-6, "the client takes the floor as " + clientTop + " high, not a quarter");

		// Dropped onto it, the player comes to rest a quarter of a block up.
		server.runOnServer(s -> connection.getServerPlayer().teleportTo(at.getX() + 0.5, at.getY() + 2, at.getZ() + 0.5));
		context.waitTicks(40);
		double standing = server.computeOnServer(s -> connection.getServerPlayer().getY()) - at.getY();
		check(Math.abs(standing - 0.25) < 0.01, "the player does not stand on the floor: " + standing + " above it");
	}

	/** A little cottage, built straight into a box, finished, and placed four times, each as it was built, to look at by eye. */
	private static void picture(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos at) {
		int n = 8;
		ItemStack cottage = server.computeOnServer(s -> {
			ServerLevel workshop = Workshop.level(s);
			BlockPos min = new BlockPos(0, 200, 0);
			net.minecraft.world.level.block.state.BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
			net.minecraft.world.level.block.state.BlockState log = Blocks.STRIPPED_OAK_LOG.defaultBlockState();
			for (int y = 0; y < 4; y++) for (int x = 1; x < 7; x++) for (int z = 1; z < 7; z++) {
				boolean wall = x == 1 || x == 6 || z == 1 || z == 6;
				boolean corner = (x == 1 || x == 6) && (z == 1 || z == 6);
				if (!wall && y > 0) continue;
				net.minecraft.world.level.block.state.BlockState state = y == 0 ? Blocks.COBBLESTONE.defaultBlockState() : corner ? log : planks;
				if (z == 6 && x == 3 && y < 3 && y > 0) state = Blocks.AIR.defaultBlockState();
				if (y == 2 && (x == 1 || x == 6) && z == 3) state = Blocks.GLASS_PANE.defaultBlockState();
				workshop.setBlock(min.offset(x, y, z), state, 2);
			}
			for (int x = 0; x < 8; x++) for (int step = 0; step < 4; step++) {
				workshop.setBlock(min.offset(x, 4 + step, step), Blocks.BRICK_STAIRS.defaultBlockState()
					.setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.SOUTH), 2);
				workshop.setBlock(min.offset(x, 4 + step, 7 - step), Blocks.BRICK_STAIRS.defaultBlockState()
					.setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.NORTH), 2);
			}
			workshop.setBlock(min.offset(3, 1, 7), Blocks.POPPY.defaultBlockState(), 2);
			return Miniature.capture(workshop, min, n).orElseThrow().toItem();
		});
		// A garden bed in slab format: half as high as it is wide, half a block when placed.
		ItemStack garden = server.computeOnServer(s -> {
			ServerLevel workshop = Workshop.level(s);
			BlockPos min = new BlockPos(0, 200, 32);
			net.minecraft.world.level.block.state.BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
			for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) {
				boolean edge = x == 0 || x == n - 1 || z == 0 || z == n - 1;
				boolean path = x == 3 || x == 4;
				workshop.setBlock(min.offset(x, 0, z), path ? Blocks.DIRT_PATH.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState(), 2);
				net.minecraft.world.level.block.state.BlockState above = edge && !(path && z == n - 1) ? fence
					: path ? Blocks.AIR.defaultBlockState()
					: (x + z) % 3 == 0 ? Blocks.POPPY.defaultBlockState()
					: (x + z) % 3 == 1 ? Blocks.DANDELION.defaultBlockState() : Blocks.CORNFLOWER.defaultBlockState();
				workshop.setBlock(min.offset(x, 1, z), above, 3);
			}
			return Miniature.capture(workshop, min, n, n / 2).orElseThrow().toItem();
		});
		server.runOnServer(s -> {
			for (int i = 0; i < 3; i++) s.overworld().setBlockAndUpdate(at.east(i * 2).below(), Blocks.STONE.defaultBlockState());
			s.overworld().setBlockAndUpdate(at.west(2), Main.BENCH.defaultBlockState());
		});
		ItemStack[] shown = {cottage, garden, cottage};
		for (int i = 0; i < 3; i++) {
			BlockPos spot = at.east(i * 2);
			ItemStack item = shown[i];
			boolean slab = item == garden;
			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				var state = Main.MINIATURE.defaultBlockState();
				if (slab) state = state.setValue(MiniatureBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.BOTTOM);
				level.setBlockAndUpdate(spot, state);
				if (level.getBlockEntity(spot) instanceof MiniatureBlockEntity m) m.applyComponentsFromItemStack(item);
				Main.MINIATURE.setPlacedBy(level, spot, level.getBlockState(spot), null, item);
			});
		}
		justfatlard.pandorical.gametest.Pictures.stage(context, server, justfatlard.pandorical.gametest.Pictures.AFTERNOON);
		context.runOnClient(client -> client.gui.toastManager().clear());
		Vec3 middle = Vec3.atBottomCenterOf(at.east(1)).add(0, 0.4, 0);
		justfatlard.pandorical.gametest.Pictures.look(server, middle.add(0.4, 1.5, 3.4), middle);
		context.waitTicks(20);
		// This client draws structures, so it is sent each miniature's anchor and none of its figures.
		int seen = context.computeOnClient(client -> client.level.getEntitiesOfClass(Display.BlockDisplay.class,
			new AABB(at).expandTowards(5, 1, 0).inflate(0.5)).size());
		check(seen == 3, "the client was sent figures, or no anchors: " + seen + " displays for three miniatures");
		justfatlard.pandorical.gametest.Pictures.frame(context, connection, "miniatures");
	}

	private static long cells(TestServerContext server, int plot) {
		return server.computeOnServer(s -> Ledger.get(s).project(plot).cells(Items.OAK_PLANKS));
	}

	private static void hold(TestServerContext server, TestServerConnection connection, ItemStack stack) {
		server.runOnServer(s -> connection.getServerPlayer().setItemInHand(InteractionHand.MAIN_HAND, stack));
	}

	/** Place from the pack, the way a click does: the material's window selected, the top of a block clicked. */
	private static InteractionResult place(TestServerContext server, TestServerConnection connection, BlockPos on, net.minecraft.world.item.Item item) {
		return server.computeOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			for (int i = 0; i < 9; i++) if (player.getInventory().getItem(i).is(item)) player.getInventory().setSelectedSlot(i);
			ServerLevel level = Workshop.level(s);
			return player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(on).add(0, 0.5, 0), Direction.UP, on, false));
		});
	}

	private static int figures(TestServerContext server, BlockPos pos) {
		return server.computeOnServer(s -> s.overworld().getEntitiesOfClass(Display.BlockDisplay.class, new AABB(pos).inflate(0.1),
			d -> d.entityTags().contains(MiniatureBlockEntity.FIGURE_TAG) && !d.isRemoved()).size());
	}

	private static void check(boolean holds, String otherwise) {
		if (!holds) throw new AssertionError(otherwise);
	}
}
