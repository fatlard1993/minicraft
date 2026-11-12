package justfatlard.minicraft.gametest;

import java.util.Set;
import justfatlard.minicraft.BenchBlockEntity;
import justfatlard.minicraft.Ledger;
import justfatlard.minicraft.Main;
import justfatlard.minicraft.Session;
import justfatlard.minicraft.Visits;
import justfatlard.minicraft.Workshop;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * A bench in a PvP Dimensions arena takes nobody into the workshop, and their pack stays as it is.
 * The arena is a stand-in: a dimension of that mod's namespace the test mod's own datapack adds, since
 * which world a player stands in is all the bench asks.
 */
public final class NotFromAnArena implements FabricClientGameTest {
	private static final ResourceKey<Level> ARENA = ResourceKey.create(Registries.DIMENSION,
		Identifier.fromNamespaceAndPath("pvp-dimensions-justfatlard", "arena_standin"));

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			server.runCommand("gamemode survival @a");

			int plot = server.computeOnServer(s -> {
				ServerLevel arena = s.getLevel(ARENA);
				check(arena != null, "the stand-in arena dimension is not loaded");
				BlockPos bench = new BlockPos(2, arena.getMinY() + 4, 0);
				arena.setBlockAndUpdate(bench, Main.BENCH.defaultBlockState());
				int made = ((BenchBlockEntity) arena.getBlockEntity(bench)).plot();
				Ledger.get(s).setSize(made, 4);
				ServerPlayer player = connection.getServerPlayer();
				player.teleportTo(arena, 0.5, bench.getY(), 0.5, Set.of(), 0, 0, true);
				player.getInventory().clearContent();
				player.getInventory().setItem(3, new ItemStack(Items.DIAMOND, 7));
				return made;
			});
			context.waitTicks(5);
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				check(!Visits.enter(player, plot), "a bench in an arena took the player into the workshop");
				check(player.level().dimension().equals(ARENA) && !Workshop.isWorkshop(player.level()), "the player left the arena anyway");
				check(Session.of(player) == null, "a workshop session was opened for a refused step");
				check(player.getInventory().getItem(3).is(Items.DIAMOND) && player.getInventory().getItem(3).getCount() == 7,
					"the player's pack was touched: " + player.getInventory().getItem(3));
			});
		}
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError(what);
	}
}
