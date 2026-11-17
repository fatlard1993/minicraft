package justfatlard.minicraft.gametest;

import justfatlard.minicraft.Bench;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Nothing of a builder's pack gets out of the workshop but by the bench's own way out: taken out
 * by some other mod's teleport, the visit ends there and then, windows gone and their own things
 * back; dealt a killing blow in the box, they are taken out instead of dying; and nothing that
 * holds an item (another mod's pile of item displays, an interaction) can be set down there.
 */
public final class StaysInTheBox implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			server.runCommand("gamemode survival @a");
			BlockPos bench = server.computeOnServer(s -> connection.getServerPlayer().blockPosition()).east(2);
			int plot = server.computeOnServer(s -> {
				s.overworld().setBlockAndUpdate(bench, Main.BENCH.defaultBlockState());
				int made = ((BenchBlockEntity) s.overworld().getBlockEntity(bench)).plot();
				Ledger.get(s).setSize(made, 4);
				ServerPlayer me = connection.getServerPlayer();
				me.getInventory().clearContent();
				me.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 8));
				Bench.load(me, s.overworld(), bench, me.getMainHandItem());
				me.getInventory().setItem(3, new ItemStack(Items.DIAMOND, 2));
				return made;
			});
			List<ItemStack> mine = server.computeOnServer(s -> pack(connection.getServerPlayer()));
			long stone = server.computeOnServer(s -> Ledger.get(s).project(plot).cells(Items.STONE));

			// Taken out by a teleport that is not the bench's.
			enter(server, connection, plot);
			context.waitTicks(25);
			check(server.computeOnServer(s -> connection.getServerPlayer().getInventory().countItem(Items.STONE)) > 0, "no stone window in the pack to carry out");
			server.runOnServer(s -> {
				ServerPlayer me = connection.getServerPlayer();
				ServerLevel overworld = s.overworld();
				me.teleportTo(overworld, bench.getX() + 0.5, bench.getY() + 1, bench.getZ() + 2.5, Set.<Relative>of(), 0F, 0F, true);
			});
			context.waitTicks(5);
			check(server.computeOnServer(s -> Session.of(connection.getServerPlayer()) == null), "the visit outlived a teleport out of the workshop");
			check(samePack(server, connection, mine), "a teleport out did not give back exactly the pack that went in: " + server.computeOnServer(s -> pack(connection.getServerPlayer())));
			check(server.computeOnServer(s -> Ledger.get(s).project(plot).occupants().isEmpty()), "still counted in the box after a teleport out");
			check(server.computeOnServer(s -> Ledger.get(s).project(plot).cells(Items.STONE)) == stone, "the bench's stone changed over the visit");

			// A killing blow in the box.
			enter(server, connection, plot);
			context.waitTicks(25);
			server.runOnServer(s -> {
				ServerPlayer me = connection.getServerPlayer();
				me.hurtServer((ServerLevel) me.level(), me.damageSources().fellOutOfWorld(), 1000F);
			});
			context.waitTicks(5);
			check(server.computeOnServer(s -> connection.getServerPlayer().isAlive() && !connection.getServerPlayer().isDeadOrDying()), "a builder died in the box");
			check(server.computeOnServer(s -> Session.of(connection.getServerPlayer()) == null && !Workshop.isWorkshop(connection.getServerPlayer().level())),
				"a builder dealt a killing blow was not taken out of the workshop");
			check(samePack(server, connection, mine), "a builder taken out of harm's way did not come back with exactly their pack");

			// No block built in the box opens: an anvil or a crafting table is built, not worked.
			enter(server, connection, plot);
			context.waitTicks(25);
			for (net.minecraft.world.level.block.Block worked : List.of(net.minecraft.world.level.block.Blocks.ANVIL, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) {
				boolean opened = server.computeOnServer(s -> {
					ServerPlayer me = connection.getServerPlayer();
					ServerLevel workshop = Workshop.level(s);
					BlockPos at = Workshop.origin(plot);
					workshop.setBlockAndUpdate(at, worked.defaultBlockState());
					me.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
					me.gameMode.useItemOn(me, workshop, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
						new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(at), net.minecraft.core.Direction.UP, at, false));
					boolean open = me.containerMenu != me.inventoryMenu;
					if (open) me.closeContainer();
					workshop.setBlockAndUpdate(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
					return open;
				});
				check(!opened, "a " + worked + " built in the box opened its screen");
			}
			server.runOnServer(s -> Visits.leave(connection.getServerPlayer()));
			check(samePack(server, connection, mine), "stepping out after trying the anvil did not give back exactly the pack");

			// Nothing that holds an item can be set down in the workshop.
			boolean[] added = server.computeOnServer(s -> {
				ServerLevel workshop = Workshop.level(s);
				Display.ItemDisplay pile = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, workshop);
				pile.setPos(Workshop.arrival(plot, 4));
				Interaction handle = new Interaction(EntityTypes.INTERACTION, workshop);
				handle.setPos(Workshop.arrival(plot, 4));
				return new boolean[] {workshop.addFreshEntity(pile), workshop.addFreshEntity(handle)};
			});
			check(!added[0] && !added[1], "an item display or interaction was set down in the workshop");
			System.out.println("[minicraft-test] stays in the box passed");
		}
	}

	private static void enter(TestServerContext server, TestServerConnection connection, int plot) {
		check(server.computeOnServer(s -> Visits.enter(connection.getServerPlayer(), plot)), "could not step in");
	}

	private static List<ItemStack> pack(ServerPlayer player) {
		List<ItemStack> slots = new ArrayList<>();
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) slots.add(inventory.getItem(i).copy());
		return slots;
	}

	private static boolean samePack(TestServerContext server, TestServerConnection connection, List<ItemStack> before) {
		return server.computeOnServer(s -> {
			List<ItemStack> now = pack(connection.getServerPlayer());
			if (now.size() != before.size()) return false;
			for (int i = 0; i < now.size(); i++) if (!ItemStack.matches(now.get(i), before.get(i))) return false;
			return true;
		});
	}

	private static void check(boolean holds, String otherwise) {
		if (!holds) throw new AssertionError(otherwise);
	}
}
