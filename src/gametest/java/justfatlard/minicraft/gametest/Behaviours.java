package justfatlard.minicraft.gametest;

import justfatlard.minicraft.Bench;
import justfatlard.minicraft.BenchBlockEntity;
import justfatlard.minicraft.Ledger;
import justfatlard.minicraft.Looks;
import justfatlard.minicraft.Main;
import justfatlard.minicraft.MiniatureBlock;
import justfatlard.minicraft.MiniatureBlockEntity;
import justfatlard.minicraft.StorageMenu;
import justfatlard.minicraft.Workshop;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * What a miniature does: a lamp that lights when the world powers it, a lever that is a lever,
 * and a cupboard that keeps what is put in it, each captured from its full-size build.
 */
public final class Behaviours implements FabricClientGameTest {
	private static final int N = 2;

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

			lamp(context, server, connection, bench, plot, origin.west(3));
			relay(context, server, connection, bench, plot);
			lever(context, server, connection, bench, plot, origin.north(3));
			cupboard(context, server, connection, bench, plot, origin.south(3));
			System.out.println("[minicraft-test] behaviours passed");
		}
	}

	/** A redstone lamp built in, responding to redstone: lit when the world powers it, dark when not, and lighting the world as it goes. */
	private static void lamp(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos bench, int plot, BlockPos stand) {
		build(server, plot, List.of(new Cell(0, 0, 0, Blocks.REDSTONE_LAMP.defaultBlockState())));
		server.runOnServer(s -> Ledger.get(s).setRedstone(plot, true));
		check(server.computeOnServer(s -> Bench.finish(connection.getServerPlayer(), s.overworld(), bench)), "could not start finishing the lamp");
		context.waitTicks(30);
		Looks looks = taken(server, connection);
		check(looks.respondsToRedstone(), "the lamp has no powered look");
		String name = server.computeOnServer(s -> connection.getServerPlayer().getName().getString());
		check(looks.maker().equals(Optional.of(name)), "the lamp does not say who made it: " + looks.maker());
		check(looks.powered().get().at(0, 0, 0).getValue(RedstoneLampBlock.LIT), "the powered look is not lit");
		check(!looks.off().at(0, 0, 0).getValue(RedstoneLampBlock.LIT), "the look as built is lit");
		check(server.computeOnServer(s -> Workshop.level(s).getBlockState(Workshop.origin(plot).below()).is(Blocks.CONCRETE.white())),
			"the floor did not go back after the capture");

		BlockPos at = place(server, connection, looks.toItem(), stand);
		check(!state(server, at).getValue(MiniatureBlock.POWERED), "the lamp came down powered");
		server.runOnServer(s -> s.overworld().setBlockAndUpdate(at.east(), Blocks.REDSTONE_BLOCK.defaultBlockState()));
		context.waitTicks(2);
		check(state(server, at).getValue(MiniatureBlock.POWERED), "the lamp did not answer the redstone");
		check(state(server, at).getValue(MiniatureBlock.LIGHT) == 15, "the lit lamp gives no light: " + state(server, at).getValue(MiniatureBlock.LIGHT));
		check(server.computeOnServer(s -> ((MiniatureBlockEntity) s.overworld().getBlockEntity(at)).miniature().at(0, 0, 0)
			.getValue(RedstoneLampBlock.LIT)), "the lamp does not wear its lit look");
		server.runOnServer(s -> s.overworld().setBlockAndUpdate(at.east(), Blocks.AIR.defaultBlockState()));
		context.waitTicks(2);
		check(!state(server, at).getValue(MiniatureBlock.POWERED) && state(server, at).getValue(MiniatureBlock.LIGHT) == 0, "the lamp did not go dark");
	}

	/**
	 * A lamp lit through a repeater, which only switches on a scheduled tick: captured with nobody
	 * in the workshop, so its powered look is lit only if the box kept ticking while it was tried.
	 */
	private static void relay(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos bench, int plot) {
		BlockState repeater = Blocks.REPEATER.defaultBlockState().setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.WEST);
		build(server, plot, 4, List.of(
			new Cell(0, 0, 0, Blocks.REDSTONE_WIRE.defaultBlockState()),
			new Cell(1, 0, 0, repeater),
			new Cell(2, 0, 0, Blocks.STONE.defaultBlockState()),
			new Cell(2, 1, 0, Blocks.REDSTONE_LAMP.defaultBlockState())));
		server.runOnServer(s -> Ledger.get(s).setRedstone(plot, true));
		check(server.computeOnServer(s -> Bench.finish(connection.getServerPlayer(), s.overworld(), bench)), "could not start finishing the relay");
		context.waitTicks(40);
		Looks looks = taken(server, connection);
		check(looks.powered().get().at(2, 1, 0).getValue(RedstoneLampBlock.LIT), "the relay's powered look is dark: the box did not tick while it was tried");
		check(!looks.off().at(2, 1, 0).getValue(RedstoneLampBlock.LIT), "the relay's look as built is lit");
	}

	/** A lever built in and marked as the handle: right-click the miniature and it throws, and powers what is beside it. */
	private static void lever(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos bench, int plot, BlockPos stand) {
		BlockState lever = Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.NORTH);
		build(server, plot, List.of(new Cell(0, 0, 0, Blocks.STONE.defaultBlockState()), new Cell(0, 1, 0, lever)));
		server.runOnServer(s -> Ledger.get(s).setHandle(plot, new BlockPos(0, 1, 0)));
		check(server.computeOnServer(s -> Bench.finish(connection.getServerPlayer(), s.overworld(), bench)), "could not start finishing the lever");
		context.waitTicks(30);
		Looks looks = taken(server, connection);
		check(looks.hasHandle(), "the lever has no handle");
		check(looks.handled().get().at(0, 1, 0).getValue(LeverBlock.POWERED), "the handled look's lever is not thrown");
		check(!looks.off().at(0, 1, 0).getValue(LeverBlock.POWERED), "the lever was left thrown as built");

		BlockPos at = place(server, connection, looks.toItem(), stand);
		server.runOnServer(s -> s.overworld().setBlockAndUpdate(at.east(), Blocks.REDSTONE_LAMP.defaultBlockState()));
		use(server, connection, at);
		context.waitTicks(2);
		check(state(server, at).getValue(MiniatureBlock.HANDLED), "a right-click did not throw the handle");
		check(server.computeOnServer(s -> s.overworld().getBlockState(at.east()).getValue(RedstoneLampBlock.LIT)), "the thrown handle did not light the lamp beside it");
		use(server, connection, at);
		context.waitTicks(10);
		check(!state(server, at).getValue(MiniatureBlock.HANDLED), "a second right-click did not throw it back");
		check(server.computeOnServer(s -> !s.overworld().getBlockState(at.east()).getValue(RedstoneLampBlock.LIT)), "the lamp stayed lit");
	}

	/** A chest built in: the miniature keeps what is put in it, through being broken and placed again, but not a shulker box. */
	private static void cupboard(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos bench, int plot, BlockPos stand) {
		check(Bench.loadable(new ItemStack(Items.CHEST)), "an empty chest is not loadable");
		ItemStack full = new ItemStack(Items.CHEST);
		full.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND))));
		check(!Bench.loadable(full), "a chest with something in it was loadable");

		build(server, plot, List.of(new Cell(0, 0, 0, Blocks.CHEST.defaultBlockState()), new Cell(1, 0, 0, Blocks.BARREL.defaultBlockState())));
		check(!server.computeOnServer(s -> Bench.finish(connection.getServerPlayer(), s.overworld(), bench)), "finished with two chests in it");
		server.runOnServer(s -> Workshop.level(s).setBlockAndUpdate(Workshop.origin(plot).east(), Blocks.AIR.defaultBlockState()));
		check(server.computeOnServer(s -> Bench.finish(connection.getServerPlayer(), s.overworld(), bench)), "could not finish the cupboard");
		Looks looks = taken(server, connection);
		check(looks.storage() == Bench.STORAGE_SLOTS, "the cupboard has no storage: " + looks.storage());

		BlockPos at = place(server, connection, looks.toItem(), stand);
		use(server, connection, at);
		check(server.computeOnServer(s -> connection.getServerPlayer().containerMenu instanceof StorageMenu), "a right-click did not open the cupboard");
		check(!StorageMenu.fits(new ItemStack(Items.SHULKER_BOX)), "the cupboard would take a shulker box");
		check(!StorageMenu.fits(looks.toItem()), "the cupboard would take another cupboard");
		server.runOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			player.containerMenu.getSlot(0).set(new ItemStack(Items.DIAMOND, 3));
			player.closeContainer();
		});

		server.runOnServer(s -> connection.getServerPlayer().gameMode.destroyBlock(at));
		context.waitTick();
		ItemStack back = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(2),
			item -> item.getItem().is(Main.MINIATURE_ITEM)).stream().findFirst().map(item -> {
				ItemStack stack = item.getItem().copy();
				item.discard();
				return stack;
			}).orElse(ItemStack.EMPTY));
		ItemContainerContents kept = back.get(DataComponents.CONTAINER);
		check(kept != null && kept.nonEmptyItemCopyStream().anyMatch(stack -> stack.is(Items.DIAMOND) && stack.getCount() == 3),
			"the broken cupboard did not keep its diamonds");

		server.runOnServer(s -> Bench.edit(connection.getServerPlayer(), s.overworld(), bench, back));
		check(server.computeOnServer(s -> Workshop.level(s).getBlockState(Workshop.origin(plot)).isAir()), "a full cupboard went back on the bench");

		BlockPos again = place(server, connection, back, stand.east(2));
		check(server.computeOnServer(s -> ((MiniatureBlockEntity) s.overworld().getBlockEntity(again)).items().get(0).is(Items.DIAMOND)),
			"placed again, the cupboard lost its diamonds");
	}

	private record Cell(int x, int y, int z, BlockState state) {}

	/** Straight into the box, as a builder would have left it; sized afresh if the last finish ended the project. */
	private static void build(TestServerContext server, int plot, List<Cell> cells) {
		build(server, plot, N, cells);
	}

	private static void build(TestServerContext server, int plot, int n, List<Cell> cells) {
		server.runOnServer(s -> {
			Ledger ledger = Ledger.get(s);
			if (ledger.project(plot).n() == 0) ledger.setSize(plot, n);
			ServerLevel workshop = Workshop.level(s);
			Workshop.prepare(workshop, plot, n);
			for (Cell cell : cells) workshop.setBlock(Workshop.origin(plot).offset(cell.x(), cell.y(), cell.z()), cell.state(), 2);
		});
	}

	/** The miniature just handed over, taken out of the pack. */
	private static Looks taken(TestServerContext server, TestServerConnection connection) {
		ItemStack stack = server.computeOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
				if (player.getInventory().getItem(i).is(Main.MINIATURE_ITEM)) return player.getInventory().removeItemNoUpdate(i);
			}
			return ItemStack.EMPTY;
		});
		Optional<Looks> looks = Looks.of(stack);
		check(looks.isPresent(), "no miniature was handed over");
		return looks.get();
	}

	private static BlockPos place(TestServerContext server, TestServerConnection connection, ItemStack item, BlockPos stand) {
		server.runOnServer(s -> {
			s.overworld().setBlockAndUpdate(stand, Blocks.STONE.defaultBlockState());
			s.overworld().setBlockAndUpdate(stand.above(), Blocks.AIR.defaultBlockState());
			ServerPlayer player = connection.getServerPlayer();
			player.setItemInHand(InteractionHand.MAIN_HAND, item);
			player.gameMode.useItemOn(player, s.overworld(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(stand).add(0, 0.5, 0), Direction.UP, stand, false));
		});
		BlockPos at = stand.above();
		check(server.computeOnServer(s -> s.overworld().getBlockState(at).is(Main.MINIATURE)), "the miniature was not placed");
		return at;
	}

	/** An empty-handed right-click on it. */
	private static void use(TestServerContext server, TestServerConnection connection, BlockPos at) {
		server.runOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			player.gameMode.useItemOn(player, s.overworld(), ItemStack.EMPTY, InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
		});
	}

	private static BlockState state(TestServerContext server, BlockPos at) {
		return server.computeOnServer(s -> s.overworld().getBlockState(at));
	}

	private static void check(boolean holds, String otherwise) {
		if (!holds) throw new AssertionError(otherwise);
	}
}
