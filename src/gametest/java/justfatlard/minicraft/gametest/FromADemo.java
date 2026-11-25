package justfatlard.minicraft.gametest;

import justfatlard.minicraft.BenchBlockEntity;
import justfatlard.minicraft.Ledger;
import justfatlard.minicraft.Main;
import justfatlard.minicraft.Session;
import justfatlard.minicraft.Visits;
import justfatlard.minicraft.Workshop;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Visit;
import justfatlard.pvp_dimensions.onboarding.Demos;
import justfatlard.pvp_dimensions.onboarding.Progress;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The PvP Dimensions demo: a bench there takes the player into the workshop and the demo waits for
 * them; coming back hands them the demo's kit, not their own things; a miniature in hand finishes
 * the demo; and the demo ending while they build calls them out of the box first, so only their own
 * things come home, and gives the bench's plot back.
 */
public final class FromADemo implements FabricClientGameTest {
	private static final String DEMO = Main.MOD_ID;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			server.runCommand("gamemode survival @a");

			server.runOnServer(s -> {
				check(Demos.get(DEMO) != null, "the demo was not read out of the jar: " + Demos.all());
				ServerPlayer player = connection.getServerPlayer();
				player.getInventory().clearContent();
				player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
				Demos.play(player, DEMO);
			});
			server.waitFor(s -> Visit.of(connection.getServerPlayer()) != null, 2400);

			int[] plot = new int[1];
			int[] kit = new int[1];
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				check(player.getInventory().countItem(Main.BENCH_ITEM) > 0, "the demo's kit has no bench");
				kit[0] = packSize(player);
				ServerLevel level = (ServerLevel) player.level();
				BlockPos at = player.blockPosition().east(2);
				level.setBlockAndUpdate(at, Main.BENCH.defaultBlockState());
				plot[0] = ((BenchBlockEntity) level.getBlockEntity(at)).plot();
				Ledger.get(s).setSize(plot[0], 4);
				Ledger.get(s).load(plot[0], (BlockItem) Items.OAK_PLANKS, 1);
				check(Visits.enter(player, plot[0]), "a bench in the demo did not take the player into the workshop");
				check(Workshop.isWorkshop(player.level()), "the player is not in the workshop");
			});
			// Longer than PvP Dimensions' three seconds of grace for anyone gone from their arena.
			context.waitTicks(120);

			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				Arena arena = Arenas.of(player);
				check(arena != null && arena.phase == Arena.Phase.LIVE, "the demo did not wait for the player in the workshop");
				check(Session.of(player) != null && Workshop.isWorkshop(player.level()), "the player was taken out of the workshop");
				check(player.getInventory().countItem(Items.DIAMOND) == 0, "the player's own things were handed back in the workshop");
				check(player.getInventory().countItem(Items.OAK_PLANKS) > 0, "the workshop pack lost its material");

				Visits.leave(player);
				check(player.level().dimension().equals(arena.dimension), "coming back did not set the player down in the demo");
				check(Visit.of(player) != null, "coming back ended the demo visit");
				check(packSize(player) == kit[0] && player.getInventory().countItem(Items.DIAMOND) == 0,
					"back from the workshop, the player does not hold the demo's kit: " + packSize(player) + " of " + kit[0]);
				player.getInventory().add(new ItemStack(Main.MINIATURE_ITEM));
			});
			server.waitFor(s -> Progress.of(connection.getServerPlayer()).finished().contains(DEMO), 100);

			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				check(Visits.enter(player, plot[0]), "the bench did not take the player in a second time");
				Arenas.end(s, Arenas.of(player), "a test ending it");
			});
			server.waitFor(s -> Visit.of(connection.getServerPlayer()) == null, 400);

			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				check(Session.of(player) == null && !Workshop.isWorkshop(player.level()), "the demo ended with the player left in the workshop");
				check(player.getInventory().countItem(Items.DIAMOND) == 3 && packSize(player) == 3,
					"the player's own things did not come home alone: " + packSize(player));
				check(Ledger.get(s).project(plot[0]) == null, "the demo's bench kept its plot after the demo closed");
			});
		}
	}

	private static int packSize(ServerPlayer player) {
		int count = 0;
		for (ItemStack stack : player.getInventory()) count += stack.getCount();
		return count;
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError(what);
	}
}
