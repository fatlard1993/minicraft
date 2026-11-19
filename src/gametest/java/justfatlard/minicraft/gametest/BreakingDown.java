package justfatlard.minicraft.gametest;

import justfatlard.minicraft.BenchBlockEntity;
import justfatlard.minicraft.Ledger;
import justfatlard.minicraft.Main;
import justfatlard.minicraft.Miniature;
import justfatlard.minicraft.MiniatureChoice;
import justfatlard.minicraft.Workshop;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/**
 * A bench clicked with a miniature asks what to do with it: put on the plate, it stands in the
 * box as before; broken down, what it was built from goes into the budget, cut again to the
 * bench's size with nothing lost, and a bench with no size takes the miniature's.
 */
public final class BreakingDown implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			server.runCommand("gamemode survival @a");
			BlockPos origin = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());
			BlockPos unsized = origin.east(2);
			BlockPos coarse = origin.west(2);
			int fresh = bench(server, unsized);
			int two = bench(server, coarse);
			server.runOnServer(s -> Ledger.get(s).setSize(two, 2));

			// Asked, then broken down at a bench with no size: it takes the miniature's, and its cells.
			click(server, connection, unsized, planks(4, 3));
			check(server.computeOnServer(s -> MiniatureChoice.openId(connection.getServerPlayer()) != null), "clicking with a miniature did not ask");
			check(server.computeOnServer(s -> connection.getServerPlayer().getMainHandItem().is(Main.MINIATURE_ITEM)), "asking took the miniature");
			press(server, connection, "salvage");
			check(server.computeOnServer(s -> connection.getServerPlayer().getMainHandItem().isEmpty()), "breaking it down did not spend the miniature");
			check(server.computeOnServer(s -> Ledger.get(s).project(fresh).n()) == 4, "the bench did not take the miniature's size");
			check(planksIn(server, fresh) == 3, "three cells did not come back as three: " + planksIn(server, fresh));

			// At a coarser bench: 9 cells of 4 make one cell of 2 and a sliver; 7 more fill the sliver to an exact second.
			click(server, connection, coarse, planks(4, 9));
			press(server, connection, "salvage");
			check(planksIn(server, two) == 1, "nine fine cells are not one coarse cell: " + planksIn(server, two));
			check(server.computeOnServer(s -> !Ledger.get(s).project(two).sliver(Items.OAK_PLANKS).isNone()), "the finer part was lost");
			click(server, connection, coarse, planks(4, 7));
			press(server, connection, "salvage");
			check(planksIn(server, two) == 2, "sixteen fine cells are not two coarse cells: " + planksIn(server, two));
			check(server.computeOnServer(s -> Ledger.get(s).project(two).sliver(Items.OAK_PLANKS).isNone()), "a sliver was left of an exact amount");

			// Put on the plate instead: it stands in the box, and the budget is untouched.
			click(server, connection, unsized, planks(4, 1));
			press(server, connection, "edit");
			check(server.computeOnServer(s -> Workshop.level(s).getBlockState(Workshop.origin(fresh)).is(Blocks.OAK_PLANKS)), "putting it on the plate did not stand it in the box");
			check(planksIn(server, fresh) == 3, "putting it on the plate touched the budget: " + planksIn(server, fresh));

			// Swapped for another miniature while asked: neither button does anything to the new one.
			click(server, connection, coarse, planks(4, 5));
			server.runOnServer(s -> connection.getServerPlayer().setItemInHand(InteractionHand.MAIN_HAND, planks(4, 6)));
			press(server, connection, "salvage");
			check(server.computeOnServer(s -> connection.getServerPlayer().getMainHandItem().is(Main.MINIATURE_ITEM)), "a miniature not asked about was broken down");
			check(planksIn(server, two) == 2, "the swapped miniature changed the budget: " + planksIn(server, two));
		}
	}

	private static int bench(TestServerContext server, BlockPos at) {
		return server.computeOnServer(s -> {
			s.overworld().setBlockAndUpdate(at, Main.BENCH.defaultBlockState());
			return ((BenchBlockEntity) s.overworld().getBlockEntity(at)).plot();
		});
	}

	/** A miniature cut {@code n} to a side with this many oak plank cells along its bottom. */
	private static ItemStack planks(int n, int filled) {
		byte[] cells = new byte[n * n * n];
		for (int i = 0; i < filled; i++) cells[i] = 1;
		return new Miniature(n, List.<BlockState>of(Blocks.OAK_PLANKS.defaultBlockState()), cells).toItem();
	}

	/** Held, and the bench right-clicked with it, as a click does. */
	private static void click(TestServerContext server, TestServerConnection connection, BlockPos bench, ItemStack stack) {
		server.runOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			player.setItemInHand(InteractionHand.MAIN_HAND, stack);
			player.gameMode.useItemOn(player, s.overworld(), stack, InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(bench).add(0, 0.25, 0), Direction.UP, bench, false));
		});
	}

	private static void press(TestServerContext server, TestServerConnection connection, String button) {
		server.runOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			String id = MiniatureChoice.openId(player);
			check(id != null, "no choice open to press " + button + " on");
			justfatlard.pandorical.screen.ScreenRegistry.INSTANCE.handleAction(player, new justfatlard.pandorical.protocol.ScreenActionC2S(id, button, "click", Map.of()));
		});
	}

	private static long planksIn(TestServerContext server, int plot) {
		return server.computeOnServer(s -> Ledger.get(s).project(plot).cells(Items.OAK_PLANKS));
	}

	private static void check(boolean holds, String otherwise) {
		if (!holds) throw new AssertionError(otherwise);
	}
}
