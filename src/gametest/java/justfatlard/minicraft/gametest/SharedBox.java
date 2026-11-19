package justfatlard.minicraft.gametest;

import com.mojang.authlib.GameProfile;
import justfatlard.minicraft.Bench;
import justfatlard.minicraft.BenchBlockEntity;
import justfatlard.minicraft.Ledger;
import justfatlard.minicraft.Main;
import justfatlard.minicraft.Miniature;
import justfatlard.minicraft.Session;
import justfatlard.minicraft.Visits;
import justfatlard.minicraft.Workshop;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Two builders in one bench's box at once: the real client's player, and a stand-in player on the
 * server's player list with no client of her own (a Fabric {@code FakePlayer}, as the suite's other
 * multi-player tests use). Both packs put aside and given back exactly; one budget between them, the
 * last cell of stone placed by one emptying the other's window and refusing her; the last cell
 * placed by both in one tick, and one block broken by both in one tick, each counted once; finishing
 * and trying out refused while either is in, naming who; the bench unbreakable with two inside;
 * materials loaded and withdrawn from outside while both build, never coming out more than went in;
 * one leaving with windows on the cursor and in the crafting grid, carrying none of it out, and
 * coming back while the other is still in; the ledger keeping both through a save.
 *
 * <p>What the stand-in cannot show: she has no client, so her windows are checked on the server's
 * copy of her pack, never drawn, and nothing she does arrives as a packet; her places and breaks
 * are called as the packet handlers call them. "The same tick" is two calls in one task on the
 * server thread, which is exactly how two packets in one tick are handled, but no network race is
 * staged. A real second client's screen, motes and handle hint are not seen.
 */
public final class SharedBox implements FabricClientGameTest {
	private static final int N = 4;
	private static final long PER_BLOCK = N * N * N;
	private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-00000000b0b2");
	private static final UUID PASSERBY = UUID.fromString("00000000-0000-0000-0000-00000000c0c3");

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
			String alex = server.computeOnServer(s -> connection.getServerPlayer().getName().getString());

			// Sam, a second builder, and a passer-by who stays out in the world at the bench.
			FakePlayer[] fakes = new FakePlayer[2];
			server.runOnServer(s -> {
				ServerLevel overworld = s.overworld();
				fakes[0] = FakePlayer.get(overworld, new GameProfile(SAM, "Sam"));
				fakes[0].snapTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 1.5, 0, 0);
				fakes[1] = FakePlayer.get(overworld, new GameProfile(PASSERBY, "Passerby"));
				fakes[1].snapTo(bench.getX() + 0.5, bench.getY(), bench.getZ() + 1.5, 0, 0);
				online(s, fakes[0], true);
				online(s, fakes[1], true);
			});
			FakePlayer sam = fakes[0];
			FakePlayer passerby = fakes[1];
			try {
				run(context, server, connection, bench, plot, alex, sam, passerby);
			} finally {
				server.runOnServer(s -> {
					for (FakePlayer fake : fakes) {
						if (Session.of(fake) != null) Visits.leave(fake);
						if (fake.level().players().contains(fake)) fake.level().removePlayerImmediately(fake, Entity.RemovalReason.DISCARDED);
						online(s, fake, false);
					}
				});
			}
		}
	}

	private static void run(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos bench, int plot,
			String alex, FakePlayer sam, FakePlayer passerby) {
		// The client's player, one object through every change of world, as a player is now.
		ServerPlayer me = server.computeOnServer(s -> connection.getServerPlayer());
		server.runOnServer(s -> Ledger.get(s).setSize(plot, N));
		hold(server, me, new ItemStack(Items.STONE, 2));
		server.runOnServer(s -> { while (!me.getMainHandItem().isEmpty() && Bench.load(me, s.overworld(), bench, me.getMainHandItem())) {} });
		check(cells(server, plot, Items.STONE) == 2 * PER_BLOCK, "two stone did not load as " + 2 * PER_BLOCK + " cells");

		// Each with their own pack, which comes back to each exactly.
		List<ItemStack> alexPack = server.computeOnServer(s -> {
			Inventory inventory = me.getInventory();
			inventory.clearContent();
			inventory.setItem(0, new ItemStack(Items.IRON_SWORD));
			inventory.setItem(5, new ItemStack(Items.DIAMOND, 7));
			inventory.setItem(20, new ItemStack(Items.STONE, 3));
			return pack(me);
		});
		List<ItemStack> samPack = server.computeOnServer(s -> {
			Inventory inventory = sam.getInventory();
			inventory.clearContent();
			inventory.setItem(2, new ItemStack(Items.EMERALD, 3));
			inventory.setItem(30, new ItemStack(Items.GOLDEN_APPLE, 2));
			inventory.setItem(8, new ItemStack(Items.STONE, 5));
			return pack(sam);
		});

		// Both in, through the one bench, into the one box.
		check(server.computeOnServer(s -> Visits.enter(me, plot)), "Alex could not step in");
		check(server.computeOnServer(s -> Visits.enter(sam, plot)), "Sam could not step in beside Alex");
		context.waitTicks(5);
		check(server.computeOnServer(s -> Workshop.isWorkshop(me.level()) && Workshop.isWorkshop(sam.level())),
			"not both in the workshop");
		check(server.computeOnServer(s -> Session.of(me).plot() == plot && Session.of(sam).plot() == plot), "not both at this bench's box");
		// What they carry is the bench's, not theirs: other mods asking Pandorical are told so.
		check(server.computeOnServer(s -> justfatlard.pandorical.api.PandoricalApi.loans().onLoan(me) != null
			&& justfatlard.pandorical.api.PandoricalApi.loans().onLoan(sam) != null), "a builder's pack was not reported on loan");
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).occupants().size()) == 2, "the bench does not count two inside");
		check(server.computeOnServer(s -> Bench.whoInside(Ledger.get(s).project(plot))).equals(alex + ", Sam"), "the bench does not name both");
		check(windowOf(server, me, Items.STONE) == 64 && windowOf(server, sam, Items.STONE) == 64,
			"both windows do not show a stack of stone");
		check(windowOf(server, me, Items.DIAMOND) == 0 && windowOf(server, sam, Items.EMERALD) == 0, "a pack came in");

		// Finishing and trying out wait for everyone to step out, and the one pressing is told who.
		BlockPos box = Workshop.origin(plot);
		check(place(server, me, box.below(), Items.STONE), "Alex could not place in the box");
		check(!server.computeOnServer(s -> Bench.finish(passerby, s.overworld(), bench)), "finished with two in the box");
		server.runOnServer(s -> Ledger.get(s).setRedstone(plot, true));
		check(!server.computeOnServer(s -> Bench.finish(passerby, s.overworld(), bench)), "tried out with two in the box");
		check(server.computeOnServer(s -> !Ledger.get(s).project(plot).capturing() && Workshop.level(s).getBlockState(box).is(Blocks.STONE)),
			"a refused finish touched the box");
		server.runOnServer(s -> Ledger.get(s).setRedstone(plot, false));
		String told = server.computeOnServer(s -> Bench.busyMessage(Ledger.get(s).project(plot)).getString());
		check(told.contains(alex) && told.contains("Sam"), "the refusal does not name both: " + told);
		check(!server.computeOnServer(s -> Bench.resize(passerby, s.overworld(), bench)), "the size was cleared with two in the box");

		// The bench stays while anyone is in its box.
		check(!server.computeOnServer(s -> passerby.gameMode.destroyBlock(bench)), "the passer-by broke the bench with two in its box");
		check(server.computeOnServer(s -> s.overworld().getBlockState(bench).is(Main.BENCH) && Ledger.get(s).project(plot) != null),
			"the bench or its project went with two in its box");

		// One budget: one cell of stone left between them. Alex places it; Sam's window empties, and her stale one is refused.
		server.runOnServer(s -> {
			Ledger.get(s).change(plot, Items.STONE, -(cells(s, plot, Items.STONE) - 1));
			Visits.refreshBuilders(s, plot);
		});
		check(windowOf(server, sam, Items.STONE) == 1 && windowOf(server, me, Items.STONE) == 1, "both windows do not show the one cell");
		BlockPos a = box.east();
		BlockPos b = box.east(2);
		check(place(server, me, a.below(), Items.STONE), "Alex could not place the last stone");
		check(cells(server, plot, Items.STONE) == 0, "the last stone was not spent");
		check(windowOf(server, sam, Items.STONE) == 0, "Sam's window still shows stone after Alex placed the last");
		server.runOnServer(s -> {
			sam.getInventory().setSelectedSlot(0);
			sam.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
		});
		check(!use(server, sam, b.below()), "Sam placed stone from a window the budget had emptied");
		check(server.computeOnServer(s -> Workshop.level(s).getBlockState(b).isAir()) && cells(server, plot, Items.STONE) == 0, "Sam's refused stone stands or was charged");

		// Alex breaks one: one cell back, Sam's window shows it, and she places it.
		server.runOnServer(s -> me.gameMode.destroyBlock(a));
		check(cells(server, plot, Items.STONE) == 1 && windowOf(server, sam, Items.STONE) == 1, "Alex's break did not give Sam her window back");
		check(place(server, sam, b.below(), Items.STONE), "Sam could not place the stone Alex gave back");
		check(cells(server, plot, Items.STONE) == 0 && windowOf(server, me, Items.STONE) == 0, "Sam's stone was not spent from the one budget");

		// The last cell placed by both in one tick: one has it.
		server.runOnServer(s -> me.gameMode.destroyBlock(b));
		check(cells(server, plot, Items.STONE) == 1, "Alex breaking Sam's stone did not give it back");
		boolean[] both = server.computeOnServer(s -> new boolean[] {
			placeNow(s, me, a.below(), Items.STONE),
			placeNow(s, sam, b.below(), Items.STONE)});
		int standing = server.computeOnServer(s -> (Workshop.level(s).getBlockState(a).is(Blocks.STONE) ? 1 : 0) + (Workshop.level(s).getBlockState(b).is(Blocks.STONE) ? 1 : 0));
		check(both[0] != both[1], "the last cell placed by both in one tick went to " + (both[0] ? "both" : "neither"));
		check(standing == 1 && cells(server, plot, Items.STONE) == 0, "the last cell placed twice: " + standing + " standing, " + cells(server, plot, Items.STONE) + " cells");

		// One block broken by both in one tick: given back once.
		BlockPos stood = both[0] ? a : b;
		server.runOnServer(s -> {
			me.gameMode.destroyBlock(stood);
			sam.gameMode.destroyBlock(stood);
		});
		check(cells(server, plot, Items.STONE) == 1, "one stone broken by both came back as " + cells(server, plot, Items.STONE) + " cells");

		// Loaded from outside while they build: both windows pick it up. Withdrawn: only what the ledger holds comes out.
		hold(server, passerby, new ItemStack(Items.STONE, 2));
		server.runOnServer(s -> { while (!passerby.getMainHandItem().isEmpty() && Bench.load(passerby, s.overworld(), bench, passerby.getMainHandItem())) {} });
		check(windowOf(server, sam, Items.STONE) == 64 && windowOf(server, me, Items.STONE) == 64,
			"the stone loaded from outside did not reach both windows");
		server.runOnServer(s -> passerby.getInventory().clearContent());
		server.runOnServer(s -> Bench.withdraw(passerby, s.overworld(), bench));
		check(server.computeOnServer(s -> passerby.getInventory().countItem(Items.STONE)) == 2, "withdrawing did not give back the two loaded");
		check(cells(server, plot, Items.STONE) == 1, "withdrawing left the wrong crumbs: " + cells(server, plot, Items.STONE));
		check(windowOf(server, sam, Items.STONE) == 1 && windowOf(server, me, Items.STONE) == 1,
			"the windows still show what was withdrawn");
		server.runOnServer(s -> Bench.withdraw(passerby, s.overworld(), bench));
		check(server.computeOnServer(s -> passerby.getInventory().countItem(Items.STONE)) == 2, "a second withdraw took a block out of one cell and two windows");
		server.runOnServer(s -> passerby.getInventory().clearContent());

		// Alex leaves holding stone on the cursor and in the crafting grid: none of it comes out.
		server.runOnServer(s -> {
			ServerPlayer player = me;
			player.inventoryMenu.setCarried(new ItemStack(Items.STONE, 1));
			player.inventoryMenu.getCraftSlots().setItem(0, new ItemStack(Items.STONE, 1));
			Visits.leave(player);
		});
		context.waitTicks(5);
		check(server.computeOnServer(s -> !Workshop.isWorkshop(me.level())), "Alex is still in the workshop");
		check(samePack(server, me, alexPack), "Alex did not come back with exactly what he carried: " + server.computeOnServer(s -> pack(me)));
		server.runOnServer(s -> {
			ServerPlayer player = me;
			check(player.inventoryMenu.getCarried().isEmpty() && player.inventoryMenu.getCraftSlots().isEmpty(), "a window stayed on the cursor or in the grid");
			// The client closing its screen afterwards hands nothing more back.
			player.inventoryMenu.removed(player);
		});
		check(samePack(server, me, alexPack), "closing the screen after leaving brought stone out");
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).occupants().keySet().equals(java.util.Set.of(SAM))), "Sam is not the only one counted inside");
		String waiting = server.computeOnServer(s -> Bench.busyMessage(Ledger.get(s).project(plot)).getString());
		check(waiting.contains("Sam") && !waiting.contains(alex), "with Sam alone inside, the refusal says: " + waiting);
		check(!server.computeOnServer(s -> Bench.finish(me, s.overworld(), bench)), "finished with Sam still inside");

		// Watched for a few seconds with Sam inside and Alex out: nobody dropped from the ledger, nobody added.
		context.waitTicks(45);
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).occupants().keySet().equals(java.util.Set.of(SAM))), "the ledger lost or gained a builder while watched");

		// Saved and read back with Sam inside: still Sam, by name.
		server.runOnServer(s -> {
			var ops = s.registryAccess().createSerializationContext(NbtOps.INSTANCE);
			Tag saved = Ledger.CODEC.encodeStart(ops, Ledger.get(s)).getOrThrow();
			Ledger read = Ledger.CODEC.parse(ops, saved).getOrThrow();
			Map<UUID, String> inside = read.project(plot).occupants();
			check(inside.size() == 1 && "Sam".equals(inside.get(SAM)), "the saved ledger does not keep Sam inside: " + inside);
			Tag visit = Session.CODEC.encodeStart(ops, Session.of(sam)).getOrThrow();
			Session back = Session.CODEC.parse(ops, visit).getOrThrow();
			check(back.plot() == plot && back.stash().size() == Session.of(sam).stash().size(), "Sam's visit does not survive a save");
		});

		// Alex back in, beside Sam, then Sam out, then Alex: each pack back exactly, and finishing works once both are out.
		check(server.computeOnServer(s -> Visits.enter(me, plot)), "Alex could not step back in beside Sam");
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).occupants().size()) == 2, "not two inside again");
		check(windowOf(server, me, Items.STONE) == 1, "Alex's window did not show the one cell left on coming back");
		server.runOnServer(s -> Visits.leave(sam));
		check(samePack(server, sam, samPack), "Sam did not come back with exactly what she carried: " + server.computeOnServer(s -> pack(sam)));
		check(server.computeOnServer(s -> Session.of(sam) == null && !Workshop.isWorkshop(sam.level())), "Sam's visit outlived her leaving");
		server.runOnServer(s -> Visits.leave(me));
		check(samePack(server, me, alexPack), "Alex's pack changed over his second visit");
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).occupants().isEmpty()), "somebody is still counted inside");
		check(server.computeOnServer(s -> justfatlard.pandorical.api.PandoricalApi.loans().onLoan(me) == null
			&& justfatlard.pandorical.api.PandoricalApi.loans().onLoan(sam) == null), "a pack was still reported on loan after stepping out");
		check(server.computeOnServer(s -> Bench.finish(me, s.overworld(), bench)), "could not finish with everyone out");
		int filled = server.computeOnServer(s -> {
			Inventory inventory = me.getInventory();
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				var m = Miniature.of(inventory.getItem(i));
				if (m.isPresent()) return m.get().filled();
			}
			return -1;
		});
		// Only the first stone Alex placed is left standing: every other was broken again.
		check(filled == 1, "the shared build did not finish as one stone: " + filled);
		// Stone in, stone out: two loaded by Alex (one placed, 126 cells taken off by the test), two by the passer-by and withdrawn, one cell left.
		check(cells(server, plot, Items.STONE) == 1, "the crumbs are not one cell: " + cells(server, plot, Items.STONE));
		System.out.println("[minicraft-test] two builders in one box passed");
		reslice(context, server, me, passerby, bench.west(4));
	}

	/**
	 * Changing the size with material in: cut again, nothing lost. Whole blocks come through exactly;
	 * a part block between sizes that do not divide each other is rounded down, the rest kept aside
	 * as a sliver and given back on the way home; refused with something built in the box, somebody
	 * in it, or a try-out running; the format unlocked by the same rule.
	 */
	private static void reslice(ClientGameTestContext context, TestServerContext server, ServerPlayer me, FakePlayer passerby, BlockPos bench) {
		int plot = server.computeOnServer(s -> {
			s.overworld().setBlockAndUpdate(bench.below(), Blocks.STONE.defaultBlockState());
			s.overworld().setBlockAndUpdate(bench, Main.BENCH.defaultBlockState());
			int made = ((BenchBlockEntity) s.overworld().getBlockEntity(bench)).plot();
			Ledger.get(s).setSize(made, 4);
			return made;
		});
		// Three stone at 4, cut to 8: exactly three blocks' worth, and three stone out again.
		hold(server, passerby, new ItemStack(Items.STONE, 3));
		server.runOnServer(s -> { while (!passerby.getMainHandItem().isEmpty() && Bench.load(passerby, s.overworld(), bench, passerby.getMainHandItem())) {} });
		check(cells(server, plot, Items.STONE) == 3 * 64, "three stone did not load at 4");
		check(server.computeOnServer(s -> Bench.changeSize(passerby, s.overworld(), bench, 8)), "could not change 4 to 8");
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).n()) == 8 && cells(server, plot, Items.STONE) == 3 * 512,
			"three stone at 8 is not 3 x 512 cells: " + cells(server, plot, Items.STONE));
		server.runOnServer(s -> passerby.getInventory().clearContent());
		server.runOnServer(s -> Bench.withdraw(passerby, s.overworld(), bench));
		check(server.computeOnServer(s -> passerby.getInventory().countItem(Items.STONE)) == 3 && cells(server, plot, Items.STONE) == 0,
			"withdrawing at 8 did not give the three stone back");
		server.runOnServer(s -> passerby.getInventory().clearContent());

		// A part block at 4, to 5 and back: rounded down, never up, and back to the very cells.
		server.runOnServer(s -> {
			Ledger ledger = Ledger.get(s);
			ledger.resize(plot, 4);
			ledger.change(plot, Items.STONE, 64 + 37);
			ledger.change(plot, Items.OAK_PLANKS, 5);
		});
		check(server.computeOnServer(s -> Bench.changeSize(passerby, s.overworld(), bench, 5)), "could not change 4 to 5");
		long stone5 = cells(server, plot, Items.STONE);
		long oak5 = cells(server, plot, Items.OAK_PLANKS);
		// Exact: 101 cells at 4 are 101 x 125 / 64 = 197.27 cells at 5; 5 are 9.77.
		check(stone5 == 197 && oak5 == 9, "4 to 5 did not round down to 197 stone and 9 planks: " + stone5 + ", " + oak5);
		check(stone5 * 64 <= 101L * 125 && oak5 * 64 <= 5L * 125, "4 to 5 made material");
		check(stone5 * 64 > 101L * 125 - 64 && oak5 * 64 > 5L * 125 - 64, "4 to 5 lost more than a cell's sliver");
		check(server.computeOnServer(s -> !Ledger.get(s).project(plot).sliver(Items.STONE).isNone()), "the stone's sliver was not kept");
		check(server.computeOnServer(s -> Bench.changeSize(passerby, s.overworld(), bench, 4)), "could not change 5 back to 4");
		check(cells(server, plot, Items.STONE) == 101 && cells(server, plot, Items.OAK_PLANKS) == 5,
			"4 to 5 to 4 did not give back the very cells: " + cells(server, plot, Items.STONE) + ", " + cells(server, plot, Items.OAK_PLANKS));
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).sliver(Items.STONE).isNone()), "a sliver was left after coming home");
		// 4 to 8 to 4 and 4 to 7 to 13 to 4, each exact on the way home.
		for (int[] trip : new int[][] {{8, 4}, {7, 13, 16, 3, 4}}) {
			for (int size : trip) check(server.computeOnServer(s -> Bench.changeSize(passerby, s.overworld(), bench, size)), "could not change to " + size);
			check(cells(server, plot, Items.STONE) == 101 && cells(server, plot, Items.OAK_PLANKS) == 5,
				"a round trip through " + java.util.Arrays.toString(trip) + " changed the cells: " + cells(server, plot, Items.STONE));
		}
		// Kept through a save, slivers and all.
		server.runOnServer(s -> {
			check(Bench.changeSize(passerby, s.overworld(), bench, 5), "could not change to 5 for the save");
			var ops = s.registryAccess().createSerializationContext(NbtOps.INSTANCE);
			Ledger read = Ledger.CODEC.parse(ops, Ledger.CODEC.encodeStart(ops, Ledger.get(s)).getOrThrow()).getOrThrow();
			check(read.project(plot).sliver(Items.STONE).equals(Ledger.get(s).project(plot).sliver(Items.STONE)), "the sliver did not survive a save");
			read.resize(plot, 4);
			check(read.project(plot).cells(Items.STONE) == 101, "read back and cut to 4, the stone is not 101 cells");
			check(Bench.changeSize(passerby, s.overworld(), bench, 4), "could not change back to 4 after the save");
		});

		// Refused with something built in the box, and nothing changed.
		server.runOnServer(s -> Workshop.level(s).setBlockAndUpdate(Workshop.origin(plot), Blocks.STONE.defaultBlockState()));
		check(!server.computeOnServer(s -> Bench.changeSize(passerby, s.overworld(), bench, 8)), "changed size with a block built in the box");
		check(!server.computeOnServer(s -> Bench.changeFormat(passerby, s.overworld(), bench)), "changed format with a block built in the box");
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).n()) == 4 && cells(server, plot, Items.STONE) == 101, "a refused change touched the bench");
		server.runOnServer(s -> Workshop.level(s).setBlockAndUpdate(Workshop.origin(plot), Blocks.AIR.defaultBlockState()));

		// Refused with somebody inside, and during a try-out.
		check(server.computeOnServer(s -> Visits.enter(me, plot)), "could not step in to test the refusal");
		check(!server.computeOnServer(s -> Bench.changeSize(passerby, s.overworld(), bench, 8)), "changed size with somebody in the box");
		server.runOnServer(s -> Visits.leave(me));
		server.runOnServer(s -> Ledger.get(s).setCapturing(plot, true));
		check(!server.computeOnServer(s -> Bench.changeSize(passerby, s.overworld(), bench, 8)), "changed size during a try-out");
		server.runOnServer(s -> Ledger.get(s).setCapturing(plot, false));

		// The format, unlocked by the same rule: a slab at 4, refused back to odd sizes, and a block again.
		check(server.computeOnServer(s -> Bench.changeFormat(passerby, s.overworld(), bench) && Ledger.get(s).project(plot).slab()), "could not make it a slab with material in");
		check(!server.computeOnServer(s -> Bench.changeSize(passerby, s.overworld(), bench, 5)), "a slab bench took an odd size");
		check(cells(server, plot, Items.STONE) == 101, "changing the format re-sliced the stone");
		check(server.computeOnServer(s -> Bench.changeFormat(passerby, s.overworld(), bench) && !Ledger.get(s).project(plot).slab()), "could not make it a block again");

		// Whoever steps in next sees the budget as cut: 101 cells at 4 to 8 is 808.
		check(server.computeOnServer(s -> Bench.changeSize(passerby, s.overworld(), bench, 8)), "could not change to 8 before stepping in");
		check(server.computeOnServer(s -> Visits.enter(me, plot)), "could not step in after the change");
		check(windowOf(server, me, Items.STONE) == 64 && windowOf(server, me, Items.OAK_PLANKS) == 40, "the windows do not show the budget as cut at 8");
		server.runOnServer(s -> Visits.leave(me));
		System.out.println("[minicraft-test] re-slicing passed");
	}

	/** Every slot of the pack, empty or not, for comparing exactly. */
	private static List<ItemStack> pack(ServerPlayer player) {
		List<ItemStack> slots = new ArrayList<>();
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) slots.add(inventory.getItem(i).copy());
		return slots;
	}

	private static boolean samePack(TestServerContext server, ServerPlayer player, List<ItemStack> before) {
		return server.computeOnServer(s -> {
			List<ItemStack> now = pack(player);
			if (now.size() != before.size()) return false;
			for (int i = 0; i < now.size(); i++) if (!ItemStack.matches(now.get(i), before.get(i))) return false;
			return true;
		});
	}

	/** How much of this item the player's pack shows. */
	private static int windowOf(TestServerContext server, ServerPlayer player, Item item) {
		return server.computeOnServer(s -> player.getInventory().countItem(item));
	}

	private static long cells(TestServerContext server, int plot, Item item) {
		return server.computeOnServer(s -> cells(s, plot, item));
	}

	private static long cells(MinecraftServer server, int plot, Item item) {
		return Ledger.get(server).project(plot).cells(item);
	}

	private static void hold(TestServerContext server, ServerPlayer player, ItemStack stack) {
		server.runOnServer(s -> player.setItemInHand(InteractionHand.MAIN_HAND, stack));
	}

	private static boolean place(TestServerContext server, ServerPlayer player, BlockPos on, Item item) {
		return server.computeOnServer(s -> placeNow(s, player, on, item));
	}

	/** Place from the pack, the way a click does: the material's window selected, the top of a block clicked. */
	private static boolean placeNow(MinecraftServer server, ServerPlayer player, BlockPos on, Item item) {
		for (int i = 0; i < 9; i++) if (player.getInventory().getItem(i).is(item)) player.getInventory().setSelectedSlot(i);
		return useNow(server, player, on);
	}

	private static boolean use(TestServerContext server, ServerPlayer player, BlockPos on) {
		return server.computeOnServer(s -> useNow(s, player, on));
	}

	private static boolean useNow(MinecraftServer server, ServerPlayer player, BlockPos on) {
		return player.gameMode.useItemOn(player, Workshop.level(server), player.getMainHandItem(), InteractionHand.MAIN_HAND,
			new BlockHitResult(Vec3.atCenterOf(on).add(0, 0.5, 0), Direction.UP, on, false)).consumesAction();
	}

	/** On the server's player list, as a joined player is, so lookups by id find them; off it again after. */
	@SuppressWarnings("unchecked")
	private static void online(MinecraftServer server, ServerPlayer player, boolean on) {
		try {
			PlayerList list = server.getPlayerList();
			Field players = PlayerList.class.getDeclaredField("players");
			Field byId = PlayerList.class.getDeclaredField("playersByUUID");
			players.setAccessible(true);
			byId.setAccessible(true);
			List<ServerPlayer> all = (List<ServerPlayer>) players.get(list);
			Map<UUID, ServerPlayer> ids = (Map<UUID, ServerPlayer>) byId.get(list);
			if (on) {
				if (!all.contains(player)) all.add(player);
				ids.put(player.getUUID(), player);
			} else {
				all.remove(player);
				ids.remove(player.getUUID());
			}
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("could not put a stand-in player on the player list", e);
		}
	}

	private static void check(boolean holds, String otherwise) {
		if (!holds) throw new AssertionError(otherwise);
	}
}
