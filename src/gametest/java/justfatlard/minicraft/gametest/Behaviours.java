package justfatlard.minicraft.gametest;

import justfatlard.minicraft.Bench;
import justfatlard.minicraft.BenchBlockEntity;
import justfatlard.minicraft.BenchScreen;
import justfatlard.minicraft.ConstructScreen;
import justfatlard.minicraft.Constructing;
import justfatlard.minicraft.Copying;
import justfatlard.minicraft.Preview;
import justfatlard.minicraft.Transform;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.minicraft.Miniature;
import justfatlard.minicraft.Ledger;
import justfatlard.minicraft.Looks;
import justfatlard.minicraft.Main;
import justfatlard.minicraft.MiniatureBlock;
import justfatlard.minicraft.MiniatureBlockEntity;
import justfatlard.minicraft.Function;
import justfatlard.minicraft.Functions;
import justfatlard.minicraft.Visits;
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
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What a miniature does: a lamp that lights when the world powers it, a lever that is a lever,
 * a crafting table, a furnace and a cupboard from the functional slot, breaking like what it is
 * mostly made of, a slab half a block high, and copying one at the bench.
 */
public final class Behaviours implements FabricClientGameTest {
	/** A builder counted inside a box while not on the server: still inside, as one who logged out in the box is. */
	private static final UUID AWAY_BUILDER = UUID.fromString("00000000-0000-0000-0000-00000000a3a7");
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
			glow(context, server, connection, bench, plot, origin.north(9));
			relay(context, server, connection, bench, plot);
			lever(context, server, connection, bench, plot, origin.north(3));
			refusals(server, connection, bench, plot);
			workbench(context, server, connection, bench, plot, origin.north(6));
			furnace(context, server, connection, bench, plot, origin.west(6));
			cupboard(context, server, connection, bench, plot, origin.south(3));
			majority(context, server, connection, origin.south(6));
			slab(context, server, connection, bench, plot, origin.east(6));
			oneOrTheOther(context, server, connection, bench, plot);
			copying(context, server, connection, bench, plot, origin.south(9));
			fullSize(context, server, connection, origin);
			shipped(context, server, connection, origin.west(9));
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
		context.waitTicks(10);
		int serverLight = server.computeOnServer(s -> s.overworld().getBrightness(LightLayer.BLOCK, at.above()));
		int clientLight = context.computeOnClient(client -> client.level.getBrightness(LightLayer.BLOCK, at.above()));
		check(serverLight == 14 && clientLight == 14, "the lit lamp does not light the air above it: server " + serverLight + ", client " + clientLight);
		check(server.computeOnServer(s -> ((MiniatureBlockEntity) s.overworld().getBlockEntity(at)).miniature().at(0, 0, 0)
			.getValue(RedstoneLampBlock.LIT)), "the lamp does not wear its lit look");
		server.runOnServer(s -> s.overworld().setBlockAndUpdate(at.east(), Blocks.AIR.defaultBlockState()));
		context.waitTicks(2);
		check(!state(server, at).getValue(MiniatureBlock.POWERED) && state(server, at).getValue(MiniatureBlock.LIGHT) == 0, "the lamp did not go dark");
	}

	/** A shroomlight built in, among stone: lights the world as soon as it is placed, on the server and on the client. */
	private static void glow(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos bench, int plot, BlockPos stand) {
		build(server, plot, List.of(new Cell(0, 0, 0, Blocks.SHROOMLIGHT.defaultBlockState()), new Cell(1, 0, 0, Blocks.STONE.defaultBlockState()),
			new Cell(0, 0, 1, Blocks.STONE.defaultBlockState()), new Cell(1, 0, 1, Blocks.STONE.defaultBlockState())));
		check(server.computeOnServer(s -> Bench.finish(connection.getServerPlayer(), s.overworld(), bench)), "could not start finishing the shroomlight");
		context.waitTicks(30);
		BlockPos at = placeByHand(context, server, connection, taken(server, connection).toItem(), stand);
		check(state(server, at).getValue(MiniatureBlock.LIGHT) == 15, "the shroomlight gives no light: " + state(server, at).getValue(MiniatureBlock.LIGHT));
		context.waitTicks(10);
		int serverLight = server.computeOnServer(s -> s.overworld().getBrightness(LightLayer.BLOCK, at.above()));
		int clientLight = context.computeOnClient(client -> client.level.getBrightness(LightLayer.BLOCK, at.above()));
		int clientState = context.computeOnClient(client -> client.level.getBlockState(at).getLightEmission());
		check(clientState == 15, "the client takes the shroomlight miniature for a block giving " + clientState + " light");
		check(serverLight == 14 && clientLight == 14, "the shroomlight does not light the air above it: server " + serverLight
			+ ", client " + clientLight + ", client emission " + clientState);
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

	/** The bench's functional slot, filled as a player fills it: the stack held, a block of it taken. */
	private static boolean putFunction(TestServerContext server, TestServerConnection connection, BlockPos bench, ItemStack stack) {
		return server.computeOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			player.setItemInHand(InteractionHand.MAIN_HAND, stack);
			return Bench.putFunction(player, s.overworld(), bench, player.getMainHandItem());
		});
	}

	private static Function function(TestServerContext server, BlockPos at) {
		return server.computeOnServer(s -> ((MiniatureBlockEntity) s.overworld().getBlockEntity(at)).function());
	}

	/** Broken with a pickaxe, which takes anything here whole, and the miniature that dropped picked up. */
	private static ItemStack breakAndTake(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos at) {
		return breakAndTake(context, server, connection, at, new ItemStack(Items.IRON_PICKAXE));
	}

	/** Broken with this in hand, and the miniature that dropped picked up, if one did. */
	private static ItemStack breakAndTake(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos at, ItemStack tool) {
		server.runOnServer(s -> {
			connection.getServerPlayer().setItemInHand(InteractionHand.MAIN_HAND, tool);
			connection.getServerPlayer().gameMode.destroyBlock(at);
		});
		context.waitTick();
		return server.computeOnServer(s -> s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(2),
			item -> item.getItem().is(Main.MINIATURE_ITEM)).stream().findFirst().map(item -> {
				ItemStack stack = item.getItem().copy();
				item.discard();
				return stack;
			}).orElse(ItemStack.EMPTY));
	}

	/** Only what a miniature can work as goes in the slot; a block with things in it does not. */
	private static void refusals(TestServerContext server, TestServerConnection connection, BlockPos bench, int plot) {
		build(server, plot, List.of(new Cell(0, 0, 0, Blocks.STONE.defaultBlockState())));
		check(!Bench.loadable(new ItemStack(Items.CHEST)), "a chest loads as a material");
		check(!Bench.loadable(new ItemStack(Items.FURNACE)), "a furnace loads as a material");
		check(!putFunction(server, connection, bench, new ItemStack(Items.BEACON)), "a beacon went in the functional slot");
		check(!putFunction(server, connection, bench, new ItemStack(Items.LECTERN)), "a lectern went in the functional slot");
		check(!putFunction(server, connection, bench, new ItemStack(Items.STONE)), "stone went in the functional slot");
		check(!putFunction(server, connection, bench, new ItemStack(Items.DIAMOND)), "a diamond went in the functional slot");
		ItemStack full = new ItemStack(Items.CHEST);
		full.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND))));
		check(!putFunction(server, connection, bench, full), "a chest with a diamond in it went in the functional slot");
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).function().isEmpty()), "something refused is in the slot");
		check(Functions.refusal(new ItemStack(Items.BEACON)) != null && Functions.refusal(new ItemStack(Items.CRAFTING_TABLE)) == null, "the refusals are wrong");
		// In and out again: given back whole, the slot empty.
		check(putFunction(server, connection, bench, new ItemStack(Items.CRAFTING_TABLE, 3)), "a crafting table was refused");
		check(server.computeOnServer(s -> connection.getServerPlayer().getMainHandItem().getCount()) == 2, "the slot took more than one crafting table");
		server.runOnServer(s -> {
			connection.getServerPlayer().getInventory().clearContent();
			Bench.takeFunction(connection.getServerPlayer(), s.overworld(), bench);
		});
		check(server.computeOnServer(s -> connection.getServerPlayer().getInventory().countItem(Items.CRAFTING_TABLE)) == 1, "the crafting table did not come back");
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).function().isEmpty()), "the crafting table is still in the slot");
		server.runOnServer(s -> connection.getServerPlayer().getInventory().clearContent());
	}

	/** A crafting table in the slot: the placed miniature opens a crafting menu, and it stays open. */
	private static void workbench(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos bench, int plot, BlockPos stand) {
		build(server, plot, List.of(new Cell(0, 0, 0, Blocks.OAK_PLANKS.defaultBlockState())));
		check(putFunction(server, connection, bench, new ItemStack(Items.CRAFTING_TABLE)), "the crafting table was refused");
		check(server.computeOnServer(s -> Bench.finish(connection.getServerPlayer(), s.overworld(), bench)), "could not finish the workbench");
		Looks looks = taken(server, connection);
		check(looks.function().equals(Optional.of(Blocks.CRAFTING_TABLE)), "the workbench does not work as a crafting table: " + looks.function());
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).function().isEmpty()), "the crafting table was not spent");
		BlockPos at = place(server, connection, looks.toItem(), stand);
		use(server, connection, at);
		check(server.computeOnServer(s -> connection.getServerPlayer().containerMenu instanceof CraftingMenu), "a right-click did not open a crafting menu");
		context.waitTicks(3);
		check(server.computeOnServer(s -> connection.getServerPlayer().containerMenu instanceof CraftingMenu), "the crafting menu closed by itself");
		server.runOnServer(s -> connection.getServerPlayer().closeContainer());

		// The menus that check their block in other ways: an anvil's, an enchanting table's, an ender chest's.
		ItemStack plain = Looks.plain(looks.off()).toItem();
		menu(context, server, connection, plain, Blocks.ANVIL, stand.east(2), net.minecraft.world.inventory.AnvilMenu.class);
		menu(context, server, connection, plain, Blocks.SMITHING_TABLE, stand.west(2), net.minecraft.world.inventory.SmithingMenu.class);
		menu(context, server, connection, plain, Blocks.ENCHANTING_TABLE, stand.south(2).east(2), net.minecraft.world.inventory.EnchantmentMenu.class);
		menu(context, server, connection, plain, Blocks.ENDER_CHEST, stand.south(2).west(2), ChestMenu.class);
	}

	/** A miniature working as this block opens this menu, and it stays open. */
	private static void menu(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, ItemStack plain,
			net.minecraft.world.level.block.Block block, BlockPos stand, Class<?> menu) {
		ItemStack item = Looks.of(plain).orElseThrow().withFunction(Optional.of(block)).toItem();
		BlockPos at = place(server, connection, item, stand);
		use(server, connection, at);
		check(server.computeOnServer(s -> menu.isInstance(connection.getServerPlayer().containerMenu)), "working as " + block + ", it did not open its menu");
		context.waitTicks(3);
		check(server.computeOnServer(s -> menu.isInstance(connection.getServerPlayer().containerMenu)), "working as " + block + ", its menu closed by itself");
		server.runOnServer(s -> connection.getServerPlayer().closeContainer());
	}

	/**
	 * A furnace in the slot: the placed miniature smelts on its own, stays a miniature while lit,
	 * and keeps its things and its progress through being broken and placed again.
	 */
	private static void furnace(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos bench, int plot, BlockPos stand) {
		build(server, plot, List.of(new Cell(0, 0, 0, Blocks.COBBLESTONE.defaultBlockState())));
		check(putFunction(server, connection, bench, new ItemStack(Items.FURNACE)), "the furnace was refused");
		check(server.computeOnServer(s -> Bench.finish(connection.getServerPlayer(), s.overworld(), bench)), "could not finish the furnace");
		Looks looks = taken(server, connection);
		check(looks.keepsThings(), "a furnace miniature does not keep things");
		BlockPos at = place(server, connection, looks.toItem(), stand);
		use(server, connection, at);
		check(server.computeOnServer(s -> connection.getServerPlayer().containerMenu instanceof FurnaceMenu), "a right-click did not open a furnace menu");
		server.runOnServer(s -> {
			Container held = ((MiniatureBlockEntity) s.overworld().getBlockEntity(at)).function().container();
			held.setItem(0, new ItemStack(Items.RAW_IRON, 2));
			held.setItem(1, new ItemStack(Items.COAL));
		});
		context.waitTicks(5);
		check(server.computeOnServer(s -> connection.getServerPlayer().containerMenu instanceof FurnaceMenu), "the furnace menu closed by itself");
		check(server.computeOnServer(s -> ((FurnaceMenu) connection.getServerPlayer().containerMenu).isLit()), "the menu does not show the furnace lit");
		server.runOnServer(s -> connection.getServerPlayer().closeContainer());
		check(state(server, at).is(Main.MINIATURE), "lighting the furnace put a furnace where the miniature stood");
		context.waitTicks(205);
		Container held = function(server, at).container();
		check(server.computeOnServer(s -> held.getItem(2).is(Items.IRON_INGOT) && held.getItem(2).getCount() == 1 && held.getItem(0).getCount() == 1),
			"the furnace did not smelt one raw iron in ten seconds: " + server.computeOnServer(s -> held.getItem(0) + " / " + held.getItem(2)));
		context.waitTicks(100);

		// Broken halfway through the second: the item keeps it all, and the bench will not take it while it does.
		ItemStack back = breakAndTake(context, server, connection, at);
		check(Bench.holdsAnything(back), "the broken furnace kept nothing");
		server.runOnServer(s -> Bench.edit(connection.getServerPlayer(), s.overworld(), bench, back));
		check(server.computeOnServer(s -> Workshop.level(s).getBlockState(Workshop.origin(plot)).isAir()), "a full furnace went back on the bench");
		BlockPos again = place(server, connection, back, stand.east(2));
		context.waitTicks(110);
		Container kept = function(server, again).container();
		check(server.computeOnServer(s -> kept.getItem(2).getCount() == 2 && kept.getItem(0).isEmpty()),
			"placed again, the furnace did not finish the second where it left off: " + server.computeOnServer(s -> kept.getItem(0) + " / " + kept.getItem(2)));

		// Emptied, it goes back on the bench, and the furnace comes back into the functional slot.
		server.runOnServer(s -> kept.clearContent());
		ItemStack emptied = breakAndTake(context, server, connection, again);
		check(!Bench.holdsAnything(emptied), "an emptied furnace still holds something");
		server.runOnServer(s -> Bench.edit(connection.getServerPlayer(), s.overworld(), bench, emptied));
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).function().is(Items.FURNACE)), "the furnace did not come back into the functional slot");
		check(server.computeOnServer(s -> Workshop.level(s).getBlockState(Workshop.origin(plot)).is(Blocks.COBBLESTONE)), "the emptied furnace was not stood up in the box");
		server.runOnServer(s -> {
			Bench.takeFunction(connection.getServerPlayer(), s.overworld(), bench);
			Workshop.clear(Workshop.level(s), plot, N);
			connection.getServerPlayer().getInventory().clearContent();
		});
	}

	/** A chest in the slot: the miniature keeps what is put in it, through being broken and placed again, but not a shulker box. */
	private static void cupboard(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos bench, int plot, BlockPos stand) {
		build(server, plot, List.of(new Cell(0, 0, 0, Blocks.OAK_PLANKS.defaultBlockState()), new Cell(1, 0, 0, Blocks.OAK_PLANKS.defaultBlockState())));
		check(putFunction(server, connection, bench, new ItemStack(Items.CHEST)), "the chest was refused");
		server.runOnServer(s -> Bench.name(s.overworld(), bench, "\u00a7cThe \u00a7lCupboard   "));
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).name()).equals("The Cupboard"), "the name was not cleaned: "
			+ server.computeOnServer(s -> Ledger.get(s).project(plot).name()));
		check(server.computeOnServer(s -> Bench.finish(connection.getServerPlayer(), s.overworld(), bench)), "could not finish the cupboard");
		ItemStack made = server.computeOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
				if (player.getInventory().getItem(i).is(Main.MINIATURE_ITEM)) return player.getInventory().removeItemNoUpdate(i);
			}
			return ItemStack.EMPTY;
		});
		check(made.has(DataComponents.CUSTOM_NAME) && made.getHoverName().getString().equals("The Cupboard"), "the cupboard is not named: " + made.getHoverName().getString());
		Looks looks = Looks.of(made).orElseThrow();
		check(looks.function().equals(Optional.of(Blocks.CHEST)), "the cupboard does not work as a chest");

		BlockPos at = place(server, connection, made, stand);
		check(server.computeOnServer(s -> ((MiniatureBlockEntity) s.overworld().getBlockEntity(at)).name().getString()).equals("The Cupboard"), "the placed cupboard lost its name");
		use(server, connection, at);
		check(server.computeOnServer(s -> connection.getServerPlayer().containerMenu instanceof ChestMenu), "a right-click did not open the cupboard");
		check(!Functions.fits(new ItemStack(Items.SHULKER_BOX)), "the cupboard would take a shulker box");
		check(!Functions.fits(looks.toItem()), "the cupboard would take another cupboard");
		check(server.computeOnServer(s -> !connection.getServerPlayer().containerMenu.getSlot(0).mayPlace(new ItemStack(Items.SHULKER_BOX))), "the cupboard's slot takes a shulker box");
		check(server.computeOnServer(s -> connection.getServerPlayer().containerMenu.getSlot(0).mayPlace(new ItemStack(Items.DIAMOND))), "the cupboard's slot will not take a diamond");
		server.runOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			player.containerMenu.getSlot(0).set(new ItemStack(Items.DIAMOND, 3));
			player.closeContainer();
		});

		ItemStack back = breakAndTake(context, server, connection, at);
		check(back.getHoverName().getString().equals("The Cupboard"), "the broken cupboard lost its name");
		check(Bench.holdsAnything(back), "the broken cupboard did not keep its diamonds");
		server.runOnServer(s -> Bench.edit(connection.getServerPlayer(), s.overworld(), bench, back));
		check(server.computeOnServer(s -> Workshop.level(s).getBlockState(Workshop.origin(plot)).isAir()), "a full cupboard went back on the bench");

		// Placed and broken over and over: three diamonds, never more.
		ItemStack carried = back;
		for (int round = 0; round < 3; round++) {
			BlockPos spot = place(server, connection, carried, stand.east(2));
			int inside = server.computeOnServer(s -> ((MiniatureBlockEntity) s.overworld().getBlockEntity(spot)).function().container().countItem(Items.DIAMOND));
			check(inside == 3, "placed again, the cupboard holds " + inside + " diamonds, not three");
			carried = breakAndTake(context, server, connection, spot);
			int loose = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(spot).inflate(3), item -> item.getItem().is(Items.DIAMOND)).size());
			check(loose == 0 && !carried.isEmpty(), "breaking the cupboard spilled diamonds or lost it");
		}

		// Emptied and edited: the name comes back to the bench.
		BlockPos last = place(server, connection, carried, stand.east(2));
		server.runOnServer(s -> ((MiniatureBlockEntity) s.overworld().getBlockEntity(last)).function().container().clearContent());
		ItemStack emptied = breakAndTake(context, server, connection, last);
		server.runOnServer(s -> Bench.edit(connection.getServerPlayer(), s.overworld(), bench, emptied));
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).name()).equals("The Cupboard"), "the name did not come back to the bench");
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).function().is(Items.CHEST)), "the chest did not come back to the slot");
		server.runOnServer(s -> {
			Bench.takeFunction(connection.getServerPlayer(), s.overworld(), bench);
			Bench.name(s.overworld(), bench, "x".repeat(80));
		});
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).name().length()) == Bench.MAX_NAME, "a long name was not cut to fifty");
		server.runOnServer(s -> {
			Bench.name(s.overworld(), bench, "");
			Workshop.clear(Workshop.level(s), plot, N);
			connection.getServerPlayer().getInventory().clearContent();
		});
	}

	/**
	 * Mostly stone: it breaks as slowly as stone, slower still by hand, and by hand it still comes
	 * away whole, its build, name and things kept; mostly wool: quick, and it sounds like wool.
	 */
	private static void majority(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos stand) {
		ItemStack stony = server.computeOnServer(s -> {
			ServerLevel workshop = Workshop.level(s);
			BlockPos min = new BlockPos(48, 200, 0);
			workshop.setBlock(min, Blocks.OAK_PLANKS.defaultBlockState(), 2);
			workshop.setBlock(min.east(), Blocks.STONE.defaultBlockState(), 2);
			workshop.setBlock(min.south(), Blocks.STONE.defaultBlockState(), 2);
			ItemStack item = justfatlard.minicraft.Miniature.capture(workshop, min, N).orElseThrow().toItem();
			workshop.setBlock(min, Blocks.AIR.defaultBlockState(), 2);
			workshop.setBlock(min.east(), Blocks.AIR.defaultBlockState(), 2);
			workshop.setBlock(min.south(), Blocks.AIR.defaultBlockState(), 2);
			ItemStack quarry = Looks.of(item).orElseThrow().withFunction(Optional.of(Blocks.CHEST)).toItem();
			quarry.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("The Quarry"));
			return quarry;
		});
		byte[] cells = justfatlard.minicraft.Miniature.of(stony).orElseThrow().cells();
		BlockPos at = place(server, connection, stony.copy(), stand);
		check(server.computeOnServer(s -> ((MiniatureBlockEntity) s.overworld().getBlockEntity(at)).majority().is(Blocks.STONE)), "the majority is not stone");
		// The client is told the majority as the position's material, for its own steps, hits and break.
		context.waitFor(client -> material(at) != null && material(at).is(Blocks.STONE));
		server.runOnServer(s -> ((MiniatureBlockEntity) s.overworld().getBlockEntity(at)).function().container().setItem(0, new ItemStack(Items.DIAMOND, 2)));
		server.runOnServer(s -> connection.getServerPlayer().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
		float[] progress = server.computeOnServer(s -> new float[] {
			s.overworld().getBlockState(at).getDestroyProgress(connection.getServerPlayer(), s.overworld(), at),
			Blocks.STONE.defaultBlockState().getDestroyProgress(connection.getServerPlayer(), s.overworld(), at)});
		check(Math.abs(progress[0] - progress[1]) < 1.0E-6, "the stone miniature does not break like stone by hand: " + progress[0] + " against " + progress[1]);
		ItemStack byHand = breakAndTake(context, server, connection, at, ItemStack.EMPTY);
		check(!byHand.isEmpty(), "a bare hand broke a stone miniature and it was lost");
		check(byHand.getHoverName().getString().equals("The Quarry"), "broken by hand, the miniature lost its name: " + byHand.getHoverName().getString());
		check(justfatlard.minicraft.Miniature.of(byHand).map(m -> java.util.Arrays.equals(m.cells(), cells)).orElse(false), "broken by hand, the miniature lost its build");
		check(Bench.holdsAnything(byHand), "broken by hand, the miniature did not keep its diamonds");
		int loose = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(3), item -> item.getItem().is(Items.DIAMOND)).size());
		check(loose == 0, "broken by hand, the miniature spilled its diamonds");
		context.waitFor(client -> material(at) == null);

		BlockPos second = place(server, connection, byHand, stand);
		server.runOnServer(s -> connection.getServerPlayer().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE)));
		float picked = server.computeOnServer(s -> s.overworld().getBlockState(second).getDestroyProgress(connection.getServerPlayer(), s.overworld(), second));
		float stone = server.computeOnServer(s -> Blocks.STONE.defaultBlockState().getDestroyProgress(connection.getServerPlayer(), s.overworld(), second));
		check(Math.abs(picked - stone) < 1.0E-6, "a pickaxe does not break the stone miniature as it breaks stone");
		// Vanilla's wrong-tool divisor alone makes a hand three and a third times slower, before the pickaxe's own speed.
		check(progress[0] * 3 < picked, "by hand the stone miniature broke no slower than with a pickaxe: " + progress[0] + " against " + picked);
		ItemStack pickedUp = breakAndTake(context, server, connection, second);
		check(!pickedUp.isEmpty() && Bench.holdsAnything(pickedUp), "a pickaxe broke the stone miniature and got nothing, or lost its diamonds");

		ItemStack woolly = server.computeOnServer(s -> {
			ServerLevel workshop = Workshop.level(s);
			BlockPos min = new BlockPos(48, 200, 0);
			workshop.setBlock(min, Blocks.WOOL.white().defaultBlockState(), 2);
			ItemStack item = justfatlard.minicraft.Miniature.capture(workshop, min, N).orElseThrow().toItem();
			workshop.setBlock(min, Blocks.AIR.defaultBlockState(), 2);
			return item;
		});
		server.runOnServer(s -> connection.getServerPlayer().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
		BlockPos soft = place(server, connection, woolly, stand);
		check(server.computeOnServer(s -> ((MiniatureBlockEntity) s.overworld().getBlockEntity(soft)).majority().getSoundType() == SoundType.WOOL), "the wool miniature does not sound like wool");
		float[] woolProgress = server.computeOnServer(s -> new float[] {
			s.overworld().getBlockState(soft).getDestroyProgress(connection.getServerPlayer(), s.overworld(), soft),
			Blocks.WOOL.white().defaultBlockState().getDestroyProgress(connection.getServerPlayer(), s.overworld(), soft)});
		check(Math.abs(woolProgress[0] - woolProgress[1]) < 1.0E-6, "the wool miniature does not break like wool");
		check(!breakAndTake(context, server, connection, soft, ItemStack.EMPTY).isEmpty(), "a bare hand broke a wool miniature and got nothing");
	}

	/**
	 * Slab format: a box half as high, a miniature half a block high, on the half clicked; and
	 * half a block of material per solid slab, rounded so the bench never gives back more than went in.
	 */
	private static void slab(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos bench, int plot, BlockPos stand) {
		server.runOnServer(s -> {
			Ledger ledger = Ledger.get(s);
			ledger.sweep(plot);
			ledger.setSlab(plot, true);
			ledger.setSize(plot, N);
			connection.getServerPlayer().getInventory().clearContent();
			connection.getServerPlayer().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 1));
			Bench.load(connection.getServerPlayer(), s.overworld(), bench, connection.getServerPlayer().getMainHandItem());
		});
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).height()) == 1, "a slab cut 2 to a side is not 1 high");
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).cells(Items.STONE)) == 8, "a block did not load as 8 cells");

		check(server.computeOnServer(s -> Visits.enter(connection.getServerPlayer(), plot)), "could not step into the slab box");
		context.waitTicks(5);
		BlockPos box = server.computeOnServer(s -> Workshop.origin(plot));
		for (int x = 0; x < N; x++) for (int z = 0; z < N; z++) placeIn(server, connection, box.offset(x, -1, z));
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).cells(Items.STONE)) == 4, "a solid slab did not cost half a block");
		placeIn(server, connection, box);
		check(server.computeOnServer(s -> Workshop.level(s).getBlockState(box.above()).isAir()), "a block went in above the slab's box");
		server.runOnServer(s -> Visits.leave(connection.getServerPlayer()));
		context.waitTicks(5);

		check(server.computeOnServer(s -> Bench.finish(connection.getServerPlayer(), s.overworld(), bench)), "could not finish the slab");
		ItemStack made = server.computeOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
				if (player.getInventory().getItem(i).is(Main.MINIATURE_ITEM)) return player.getInventory().removeItemNoUpdate(i);
			}
			return ItemStack.EMPTY;
		});
		justfatlard.minicraft.Miniature data = justfatlard.minicraft.Miniature.of(made).orElseThrow();
		check(data.n() == N && data.height() == 1 && data.cells().length == N * N && data.filled() == 4, "the slab's data is wrong: " + data.height() + " high, " + data.cells().length + " cells");
		net.minecraft.nbt.CompoundTag tag = made.get(DataComponents.CUSTOM_DATA).copyTag().getCompoundOrEmpty("miniature");
		check(tag.getIntOr("height", -1) == 1 && tag.getIntOr("n", -1) == N, "the slab's height is not beside n in its data: " + tag);

		// Half a block left: it stays a crumb, nothing comes out whole.
		server.runOnServer(s -> Bench.withdraw(connection.getServerPlayer(), s.overworld(), bench));
		check(server.computeOnServer(s -> connection.getServerPlayer().getInventory().countItem(Items.STONE)) == 0, "half a block of stone came out as a whole one");
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).cells(Items.STONE)) == 4, "the crumbs went missing");

		// Bottom half on a top face, top half on the upper half of a side.
		server.runOnServer(s -> connection.getServerPlayer().setYRot(90));
		BlockPos bottom = place(server, connection, made.copy(), stand);
		check(state(server, bottom).getValue(MiniatureBlock.TYPE) == SlabType.BOTTOM, "clicked on top, the slab is not a bottom slab");
		check(state(server, bottom).getValue(MiniatureBlock.FACING) == Direction.SOUTH, "the miniature turned to face its placer");
		double[] low = server.computeOnServer(s -> {
			var shape = s.overworld().getBlockState(bottom).getCollisionShape(s.overworld(), bottom);
			return new double[] {shape.min(Direction.Axis.Y), shape.max(Direction.Axis.Y)};
		});
		check(Math.abs(low[0]) < 1.0E-6 && Math.abs(low[1] - 0.5) < 1.0E-6, "the bottom slab is not the bottom half: " + low[0] + " to " + low[1]);
		BlockPos top = server.computeOnServer(s -> {
			BlockPos wall = stand.south(3);
			s.overworld().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
			s.overworld().setBlockAndUpdate(wall.east(), Blocks.AIR.defaultBlockState());
			ServerPlayer player = connection.getServerPlayer();
			player.setItemInHand(InteractionHand.MAIN_HAND, made.copy());
			player.gameMode.useItemOn(player, s.overworld(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
				new BlockHitResult(new Vec3(wall.getX() + 1.0, wall.getY() + 0.75, wall.getZ() + 0.5), Direction.EAST, wall, false));
			return wall.east();
		});
		check(state(server, top).is(Main.MINIATURE) && state(server, top).getValue(MiniatureBlock.TYPE) == SlabType.TOP, "clicked high on a side, the slab is not a top slab");
		double[] high = server.computeOnServer(s -> {
			var shape = s.overworld().getBlockState(top).getCollisionShape(s.overworld(), top);
			return new double[] {shape.min(Direction.Axis.Y), shape.max(Direction.Axis.Y)};
		});
		check(Math.abs(high[0] - 0.5) < 1.0E-6 && Math.abs(high[1] - 1.0) < 1.0E-6, "the top slab is not the top half: " + high[0] + " to " + high[1]);

		// The slab back on the bench and taken apart: the whole block comes back, once.
		server.runOnServer(s -> s.overworld().setBlockAndUpdate(top, Blocks.AIR.defaultBlockState()));
		ItemStack back = breakAndTake(context, server, connection, bottom);
		check(!back.isEmpty(), "the slab did not come back");
		server.runOnServer(s -> Bench.edit(connection.getServerPlayer(), s.overworld(), bench, back));
		check(server.computeOnServer(s -> Workshop.level(s).getBlockState(Workshop.origin(plot)).is(Blocks.STONE)), "the slab was not stood up in its box");
		check(server.computeOnServer(s -> Visits.enter(connection.getServerPlayer(), plot)), "could not step back into the slab box");
		context.waitTicks(5);
		for (int x = 0; x < N; x++) for (int z = 0; z < N; z++) {
			BlockPos cell = box.offset(x, 0, z);
			server.runOnServer(s -> connection.getServerPlayer().gameMode.destroyBlock(cell));
		}
		server.runOnServer(s -> Visits.leave(connection.getServerPlayer()));
		context.waitTicks(5);
		server.runOnServer(s -> {
			connection.getServerPlayer().getInventory().clearContent();
			Bench.withdraw(connection.getServerPlayer(), s.overworld(), bench);
		});
		check(server.computeOnServer(s -> connection.getServerPlayer().getInventory().countItem(Items.STONE)) == 1, "taken apart, the slab did not give back its one block");
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).cells(Items.STONE)) == 0, "stone is left over from one block");
		server.runOnServer(s -> {
			connection.getServerPlayer().getInventory().clearContent();
			Ledger.get(s).sweep(plot);
		});
	}

	/**
	 * Copying at the bench, through the bench's own menu and the same server calls a click on a
	 * slot makes: a copy costs exactly what building the original did, a door's two halves as one
	 * door; no copy shows without the material, and one shown is checked again as it is taken;
	 * an original cut to another size is charged converted and rounded up; one working as a chest
	 * needs a chest, and its copy comes out empty while the original keeps its diamonds; the
	 * name and maker come along; the original comes back when the screen closes, and when the
	 * bench is broken under it.
	 */
	private static void copying(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos bench, int plot, BlockPos stand) {
		int n = 4;
		server.runOnServer(s -> {
			Ledger ledger = Ledger.get(s);
			ledger.sweep(plot);
			ledger.setSize(plot, n);
			ServerPlayer player = connection.getServerPlayer();
			player.getInventory().clearContent();
			player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_PLANKS, 2));
			while (!player.getMainHandItem().isEmpty() && Bench.load(player, s.overworld(), bench, player.getMainHandItem())) {}
			player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_DOOR, 1));
			Bench.load(player, s.overworld(), bench, player.getMainHandItem());
			Bench.name(s.overworld(), bench, "The Shed");
		});
		long planks0 = cells(server, plot, Items.OAK_PLANKS);
		long doors0 = cells(server, plot, Items.OAK_DOOR);
		check(planks0 == 128 && doors0 == 64, "the shed's materials did not load: " + planks0 + " planks, " + doors0 + " door");

		// Built as a builder builds: two planks and a door, placed from the pack.
		check(server.computeOnServer(s -> Visits.enter(connection.getServerPlayer(), plot)), "could not step in to build the shed");
		context.waitTicks(5);
		BlockPos box = server.computeOnServer(s -> Workshop.origin(plot));
		placeWith(server, connection, box.below(), Items.OAK_PLANKS);
		placeWith(server, connection, box.offset(2, -1, 0), Items.OAK_PLANKS);
		placeWith(server, connection, box.offset(1, -1, 1), Items.OAK_DOOR);
		check(server.computeOnServer(s -> Workshop.level(s).getBlockState(box.offset(1, 1, 1)).is(Blocks.OAK_DOOR)), "the door did not stand two high");
		server.runOnServer(s -> Visits.leave(connection.getServerPlayer()));
		context.waitTicks(5);
		check(server.computeOnServer(s -> Bench.finish(connection.getServerPlayer(), s.overworld(), bench)), "could not finish the shed");
		ItemStack shed = server.computeOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
				if (player.getInventory().getItem(i).is(Main.MINIATURE_ITEM)) return player.getInventory().removeItemNoUpdate(i);
			}
			return ItemStack.EMPTY;
		});
		long builtPlanks = planks0 - cells(server, plot, Items.OAK_PLANKS);
		long builtDoors = doors0 - cells(server, plot, Items.OAK_DOOR);
		check(builtPlanks == 2 && builtDoors == 1, "building the shed did not cost two planks and a door: " + builtPlanks + ", " + builtDoors);
		check(Copying.plan(shed).orElseThrow().cells().equals(Map.of(Items.OAK_PLANKS, 2L, Items.OAK_DOOR, 1L)),
			"the shed is reckoned at " + Copying.plan(shed).orElseThrow().cells() + ", not what it cost");
		String maker = server.computeOnServer(s -> connection.getServerPlayer().getName().getString());

		// The bench opened and turned to Copy, the shed put in the way a click puts it.
		AbstractContainerMenu menu = openCopy(server, connection, bench);
		check(!slotAccepts(server, menu, 1, shed), "the copy slot takes a miniature with the Build tab shown");
		server.runOnServer(s -> BenchScreen.showTab(connection.getServerPlayer(), true));
		check(!slotAccepts(server, menu, 1, new ItemStack(Items.STONE)), "the copy slot takes stone");
		putIn(server, connection, menu, 1, shed.copy());
		check(server.computeOnServer(s -> menu.getSlot(1).getItem().is(Main.MINIATURE_ITEM)), "the shed did not go in the copy slot");
		check(server.computeOnServer(s -> !menu.getSlot(2).getItem().isEmpty()), "no copy shows with the bench able to pay for it");
		long planks1 = cells(server, plot, Items.OAK_PLANKS);
		long doors1 = cells(server, plot, Items.OAK_DOOR);
		context.waitTicks(10);
		// Pandorical's shootScreen crops even when no picture was taken, so it is only asked for one with -Ppictures.
		if (justfatlard.pandorical.gametest.Pictures.wanted()) justfatlard.pandorical.gametest.Pictures.shootScreen(context, connection, "bench_copy");

		// Taken with a click: charged exactly what building it took, the shed left in.
		ItemStack copy = server.computeOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			menu.clicked(2, 0, ContainerInput.PICKUP, player);
			ItemStack carried = menu.getCarried();
			menu.setCarried(ItemStack.EMPTY);
			return carried;
		});
		check(copy.is(Main.MINIATURE_ITEM), "a click on the copy did not take it");
		check(planks1 - cells(server, plot, Items.OAK_PLANKS) == builtPlanks && doors1 - cells(server, plot, Items.OAK_DOOR) == builtDoors,
			"the copy did not cost what building it did: " + (planks1 - cells(server, plot, Items.OAK_PLANKS)) + " planks, " + (doors1 - cells(server, plot, Items.OAK_DOOR)) + " doors");
		check(server.computeOnServer(s -> menu.getSlot(1).getItem().is(Main.MINIATURE_ITEM)), "taking the copy took the shed too");
		Looks original = Looks.of(shed).orElseThrow();
		Looks copied = Looks.of(copy).orElseThrow();
		check(sameBuild(original.off(), copied.off()), "the copy is not the same build");
		check(copy.getHoverName().getString().equals("The Shed"), "the copy lost the name: " + copy.getHoverName().getString());
		check(copied.maker().equals(Optional.of(maker)), "the copy lost its maker: " + copied.maker());

		// A copy is had once a tick at most, so a shift-click takes one, not all the bench can pay for.
		context.waitTick();
		// Shift-clicked: one copy into the pack, paid for once, however much more the bench could pay for.
		long planks2 = cells(server, plot, Items.OAK_PLANKS);
		int before = miniatures(server, connection);
		server.runOnServer(s -> menu.clicked(2, 0, ContainerInput.QUICK_MOVE, connection.getServerPlayer()));
		check(miniatures(server, connection) == before + 1, "a shift-click did not put one copy in the pack: " + (miniatures(server, connection) - before));
		check(planks2 - cells(server, plot, Items.OAK_PLANKS) == builtPlanks, "a shift-click charged " + (planks2 - cells(server, plot, Items.OAK_PLANKS)) + " planks");

		context.waitTick();
		// No door left: no copy, and a click on the empty slot takes nothing and spends nothing.
		long doorsLeft = cells(server, plot, Items.OAK_DOOR);
		server.runOnServer(s -> Ledger.get(s).change(plot, Items.OAK_DOOR, -doorsLeft));
		check(server.computeOnServer(s -> menu.getSlot(2).getItem().isEmpty()), "a copy shows with no door to pay for it");
		check(server.computeOnServer(s -> Copying.offer(Ledger.get(s).project(plot), Copying.plan(shed)).why()) == Copying.Why.SHORT, "short of a door, it is not short");
		long planks3 = cells(server, plot, Items.OAK_PLANKS);
		server.runOnServer(s -> menu.clicked(2, 0, ContainerInput.PICKUP, connection.getServerPlayer()));
		check(server.computeOnServer(s -> menu.getCarried().isEmpty()) && cells(server, plot, Items.OAK_PLANKS) == planks3, "with no door, a click had a copy or spent planks");

		context.waitTick();
		// Shown, then the material gone before it is taken: the take fails, nothing spent, nothing given.
		server.runOnServer(s -> Ledger.get(s).change(plot, Items.OAK_DOOR, 64));
		check(server.computeOnServer(s -> {
			boolean shown = !menu.getSlot(2).getItem().isEmpty();
			menu.broadcastChanges();
			return shown;
		}), "the door back, no copy shows");
		int held = miniatures(server, connection);
		long planks4 = cells(server, plot, Items.OAK_PLANKS);
		server.runOnServer(s -> {
			Ledger.get(s).change(plot, Items.OAK_DOOR, -64);
			ServerPlayer player = connection.getServerPlayer();
			menu.clicked(2, 0, ContainerInput.PICKUP, player);
			menu.clicked(2, 0, ContainerInput.QUICK_MOVE, player);
			menu.clicked(2, 0, ContainerInput.SWAP, player);
			menu.getSlot(2).container.removeItem(2, 1);
		});
		check(server.computeOnServer(s -> menu.getCarried().isEmpty()) && miniatures(server, connection) == held, "taken after the door was gone, a copy was had");
		check(cells(server, plot, Items.OAK_PLANKS) == planks4, "taken after the door was gone, planks were spent");

		// Somebody in the box: no copy, as nothing else at a busy bench is done.
		server.runOnServer(s -> {
			Ledger.get(s).change(plot, Items.OAK_DOOR, 64);
			Ledger.get(s).arrive(plot, AWAY_BUILDER, "Away");
		});
		check(server.computeOnServer(s -> menu.getSlot(2).getItem().isEmpty()), "a copy shows while somebody is building at the bench");
		server.runOnServer(s -> Ledger.get(s).depart(plot, AWAY_BUILDER));
		check(server.computeOnServer(s -> !menu.getSlot(2).getItem().isEmpty()), "nobody building, no copy shows");

		// Closed with the shed in: it comes back to the pack.
		server.runOnServer(s -> connection.getServerPlayer().getInventory().clearContent());
		server.runOnServer(s -> connection.getServerPlayer().closeContainer());
		check(server.computeOnServer(s -> {
			var inventory = connection.getServerPlayer().getInventory();
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				if (inventory.getItem(i).is(Main.MINIATURE_ITEM) && inventory.getItem(i).getHoverName().getString().equals("The Shed")) return true;
			}
			return false;
		}) && miniatures(server, connection) == 1, "closing the screen did not give the shed back, once");
		server.runOnServer(s -> connection.getServerPlayer().getInventory().clearContent());

		// Cut 3 to a side, copied at a bench cutting 4: each material converted and rounded up.
		ItemStack fine = Looks.plain(new Miniature(3, List.of(Blocks.STONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState()),
			cells(27, 1, 2, 2, 2, 2, 2))).toItem();
		check(Copying.convert(1, 3, 4) == 3 && Copying.convert(5, 3, 4) == 12 && Copying.convert(8, 2, 4) == 64, "the conversion is wrong");
		server.runOnServer(s -> {
			Ledger.get(s).load(plot, (net.minecraft.world.item.BlockItem) Items.STONE, 1);
			Ledger.get(s).load(plot, (net.minecraft.world.item.BlockItem) Items.COBBLESTONE, 1);
		});
		AbstractContainerMenu again = openCopy(server, connection, bench);
		server.runOnServer(s -> BenchScreen.showTab(connection.getServerPlayer(), true));
		putIn(server, connection, again, 1, fine);
		ItemStack fineCopy = take(server, connection, again);
		check(Miniature.of(fineCopy).map(Miniature::n).orElse(0) == 3, "the copy is not cut 3 to a side as its original is");
		check(cells(server, plot, Items.STONE) == 64 - 3 && cells(server, plot, Items.COBBLESTONE) == 64 - 12,
			"cut 3, copied at 4, it cost " + (64 - cells(server, plot, Items.STONE)) + " stone and " + (64 - cells(server, plot, Items.COBBLESTONE)) + " cobblestone, not 3 and 12");
		takeOut(server, connection, again, 1);

		// A chest of drawers holding diamonds: its copy needs a chest, and comes out empty.
		ItemStack drawers = original.withFunction(Optional.of(Blocks.CHEST)).toItem();
		drawers.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Drawers"));
		server.runOnServer(s -> connection.getServerPlayer().closeContainer());
		BlockPos at = place(server, connection, drawers, stand);
		server.runOnServer(s -> ((MiniatureBlockEntity) s.overworld().getBlockEntity(at)).function().container().setItem(0, new ItemStack(Items.DIAMOND, 3)));
		ItemStack full = breakAndTake(context, server, connection, at);
		check(Bench.holdsAnything(full), "the drawers did not keep their diamonds");
		server.runOnServer(s -> connection.getServerPlayer().getInventory().clearContent());
		AbstractContainerMenu third = openCopy(server, connection, bench);
		server.runOnServer(s -> BenchScreen.showTab(connection.getServerPlayer(), true));
		putIn(server, connection, third, 1, full.copy());
		check(server.computeOnServer(s -> third.getSlot(2).getItem().isEmpty()), "the drawers' copy shows with no chest in the functional slot");
		check(server.computeOnServer(s -> Copying.offer(Ledger.get(s).project(plot), Copying.plan(full)).why()) == Copying.Why.NEEDS_FUNCTION, "with no chest, it does not say a chest is needed");
		putIn(server, connection, third, 0, new ItemStack(Items.CHEST));
		long planks5 = cells(server, plot, Items.OAK_PLANKS);
		context.waitTick();
		ItemStack drawersCopy = take(server, connection, third);
		Looks drawn = Looks.of(drawersCopy).orElseThrow();
		check(drawn.function().equals(Optional.of(Blocks.CHEST)), "the drawers' copy does not work as a chest: " + drawn.function());
		check(!Bench.holdsAnything(drawersCopy), "the drawers' copy came out holding something");
		check(drawersCopy.getHoverName().getString().equals("Drawers"), "the drawers' copy lost its name");
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).function().isEmpty()), "the chest was not spent on the copy");
		check(planks5 - cells(server, plot, Items.OAK_PLANKS) == builtPlanks, "the drawers' copy cost the wrong planks");
		check(server.computeOnServer(s -> Bench.holdsAnything(third.getSlot(1).getItem())), "the original lost its diamonds");
		context.waitTick();
		check(server.computeOnServer(s -> third.getSlot(2).getItem().isEmpty()), "a second copy shows with the chest spent");

		// An anvil: any anvil does, and the copy wears the one spent.
		server.runOnServer(s -> Ledger.get(s).setFunction(plot, new ItemStack(Items.CHIPPED_ANVIL)));
		check(server.computeOnServer(s -> Copying.offer(Ledger.get(s).project(plot), Copying.plan(original.withFunction(Optional.of(Blocks.ANVIL)).toItem()))
			.function()).equals(Optional.of(Blocks.CHIPPED_ANVIL)), "a chipped anvil does not serve for an anvil, or the copy is not chipped");
		server.runOnServer(s -> Ledger.get(s).setFunction(plot, ItemStack.EMPTY));

		// The bench broken with the drawers in the copy slot: the screen closes and they come back.
		server.runOnServer(s -> s.overworld().destroyBlock(bench, false));
		context.waitTicks(10);
		check(server.computeOnServer(s -> !(connection.getServerPlayer().containerMenu == third)), "the screen stayed open on a broken bench");
		check(server.computeOnServer(s -> {
			var inventory = connection.getServerPlayer().getInventory();
			for (int i = 0; i < inventory.getContainerSize(); i++) if (Bench.holdsAnything(inventory.getItem(i))) return true;
			return false;
		}), "the drawers did not come back when the bench was broken");
		server.runOnServer(s -> connection.getServerPlayer().getInventory().clearContent());
	}

	// --- Building one full size ---

	private static final int FULL = 4;

	/**
	 * A miniature built full size where it stands, driven as a player drives it: the dialog opened
	 * by a sneaking, empty-handed right-click through the server's own use of a block, and its
	 * buttons pressed through Pandorical's own handler for a button's packet.
	 */
	private static void fullSize(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos origin) {
		BlockState doorLower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
		BlockState doorUpper = doorLower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
		BlockState stair = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
		BlockState doubleSlab = Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE);
		Miniature hut = mini(FULL, FULL, List.of(
			new Cell(0, 0, 3, Blocks.STONE.defaultBlockState()),
			new Cell(3, 3, 0, Blocks.GLASS.defaultBlockState()),
			new Cell(1, 0, 1, doorLower), new Cell(1, 1, 1, doorUpper),
			new Cell(2, 0, 2, stair),
			new Cell(0, 0, 0, Blocks.OAK_SLAB.defaultBlockState()),
			new Cell(2, 0, 3, doubleSlab)));
		Map<net.minecraft.world.item.Item, Long> cost = Map.of(Items.STONE, 1L, Items.GLASS, 1L, Items.OAK_DOOR, 1L, Items.OAK_STAIRS, 1L,
			Items.OAK_SLAB, 1L, Items.SMOOTH_STONE_SLAB, 2L);
		check(Copying.cells(hut).equals(cost), "the hut is reckoned at " + Copying.cells(hut) + ", not one block a cell with the door once");
		ItemStack item = Looks.plain(hut).withFunction(Optional.of(Blocks.CHEST)).toItem();
		item.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Hut"));

		server.runCommand("gamemode survival @a");
		BlockPos at = site(server, connection, item, origin.east(20));
		server.runOnServer(s -> ((MiniatureBlockEntity) s.overworld().getBlockEntity(at)).function().container().setItem(0, new ItemStack(Items.DIAMOND, 3)));

		// The dialog's lines are the copy's cost at full size, against what is carried.
		openDialog(server, connection, at);
		List<Constructing.Line> lines = server.computeOnServer(s -> Constructing.survey(connection.getServerPlayer(), s.overworld(), at).lines());
		check(lines.size() == cost.size() && lines.stream().allMatch(line -> cost.get(line.item()) == line.cost() && line.held() == 0),
			"the dialog lists " + lines + ", not the copy's cost with nothing carried");

		// Not enough: refused, nothing taken, nothing placed.
		carry(server, connection, Map.of(Items.STONE, 1L, Items.GLASS, 1L, Items.OAK_DOOR, 1L, Items.OAK_STAIRS, 1L, Items.OAK_SLAB, 1L, Items.SMOOTH_STONE_SLAB, 1L));
		press(server, connection, "construct");
		untouched(server, connection, at, "short of a slab");
		check(carried(server, connection, Items.SMOOTH_STONE_SLAB) == 1 && carried(server, connection, Items.STONE) == 1, "a refused construct took something");

		// Enough, but a block in the way: refused, nothing taken, nothing placed; the preview finds it.
		carry(server, connection, Map.of(Items.STONE, 6L, Items.GLASS, 1L, Items.OAK_DOOR, 1L, Items.OAK_STAIRS, 1L, Items.OAK_SLAB, 1L, Items.SMOOTH_STONE_SLAB, 2L));
		BlockPos inTheWay = at.offset(0, 0, -3); // where the slab goes
		server.runOnServer(s -> s.overworld().setBlockAndUpdate(inTheWay, Blocks.DIRT.defaultBlockState()));
		Constructing.Survey blocked = server.computeOnServer(s -> Constructing.survey(connection.getServerPlayer(), s.overworld(), at));
		check(blocked.why() == Constructing.Why.BLOCKED && blocked.blocked().equals(List.of(inTheWay)), "the dirt in the way was not found: " + blocked.why() + " " + blocked.blocked());
		press(server, connection, "construct");
		untouched(server, connection, at, "with dirt in the way");
		check(carried(server, connection, Items.STONE) == 6, "a construct refused for dirt took something");
		// A block whose spot is replaceable is no obstacle: grass where the stair goes.
		server.runOnServer(s -> {
			s.overworld().setBlockAndUpdate(inTheWay, Blocks.AIR.defaultBlockState());
			s.overworld().setBlockAndUpdate(at.offset(2, 0, -1), Blocks.SHORT_GRASS.defaultBlockState());
		});
		check(server.computeOnServer(s -> Constructing.survey(connection.getServerPlayer(), s.overworld(), at).ready()), "grass in the way stopped it");

		// Checked again at the moment of building: the door drained after the dialog showed ready.
		server.runOnServer(s -> connection.getServerPlayer().getInventory().clearOrCountMatchingItems(stack -> stack.is(Items.OAK_DOOR), false, 64,
			connection.getServerPlayer().inventoryMenu.getCraftSlots()));
		press(server, connection, "construct");
		untouched(server, connection, at, "with the door gone since the dialog opened");
		check(carried(server, connection, Items.STONE) == 6, "a construct refused at the last moment took something");

		// Everything carried: built, exactly as captured, at the anchor, charged exactly.
		carry(server, connection, Map.of(Items.STONE, 6L, Items.GLASS, 1L, Items.OAK_DOOR, 1L, Items.OAK_STAIRS, 1L, Items.OAK_SLAB, 1L, Items.SMOOTH_STONE_SLAB, 2L));
		press(server, connection, "construct");
		check(state(server, at).is(Blocks.STONE), "the corner is not the stone: " + state(server, at));
		check(state(server, at.offset(3, 3, -3)).is(Blocks.GLASS), "the far north-east top is not the glass");
		check(state(server, at.offset(1, 0, -2)).equals(doorLower) && state(server, at.offset(1, 1, -2)).equals(doorUpper), "the door does not stand both halves");
		check(state(server, at.offset(2, 0, -1)).is(Blocks.OAK_STAIRS) && state(server, at.offset(2, 0, -1)).getValue(StairBlock.FACING) == Direction.EAST, "the stair is not as captured");
		check(state(server, at.offset(0, 0, -3)).equals(Blocks.OAK_SLAB.defaultBlockState()), "the slab is not at the back left");
		check(state(server, at.offset(2, 0, 0)).equals(doubleSlab), "the double slab is not beside the corner");
		check(carried(server, connection, Items.STONE) == 5 && carried(server, connection, Items.SMOOTH_STONE_SLAB) == 0 && carried(server, connection, Items.OAK_DOOR) == 0
			&& carried(server, connection, Items.GLASS) == 0, "the construct did not charge exactly what it cost");
		ItemStack back = server.computeOnServer(s -> {
			var inventory = connection.getServerPlayer().getInventory();
			for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i).is(Main.MINIATURE_ITEM)) return inventory.getItem(i).copy();
			return ItemStack.EMPTY;
		});
		check(back.getHoverName().getString().equals("Hut") && Bench.holdsAnything(back) && sameBuild(Looks.of(back).orElseThrow().off(), hut),
			"the miniature did not come back with its name, build and diamonds");
		check(server.computeOnServer(s -> ConstructScreen.openId(connection.getServerPlayer()) == null), "the dialog stayed open after building");

		// Creative: nothing charged.
		server.runOnServer(s -> connection.getServerPlayer().getInventory().clearContent());
		server.runCommand("gamemode creative @a");
		BlockPos free = site(server, connection, Looks.plain(hut).toItem(), origin.east(20).south(8));
		openDialog(server, connection, free);
		press(server, connection, "construct");
		check(state(server, free.offset(3, 3, -3)).is(Blocks.GLASS), "creative did not build it");
		check(carried(server, connection, Main.MINIATURE_ITEM) == 1 && server.computeOnServer(s -> {
			var inventory = connection.getServerPlayer().getInventory();
			int stacks = 0;
			for (int i = 0; i < inventory.getContainerSize(); i++) if (!inventory.getItem(i).isEmpty()) stacks++;
			return stacks;
		}) == 1, "creative was charged, or the miniature did not come back");

		// A slab builds half as high.
		Miniature slab = mini(FULL, 2, List.of(new Cell(0, 0, 3, Blocks.STONE.defaultBlockState()), new Cell(3, 1, 0, Blocks.GLASS.defaultBlockState())));
		BlockPos low = site(server, connection, Looks.plain(slab).toItem(), origin.east(20).south(16));
		check(server.computeOnServer(s -> Constructing.survey(connection.getServerPlayer(), s.overworld(), low).height()) == 2, "the slab is not 2 high full size");
		openDialog(server, connection, low);
		press(server, connection, "construct");
		check(state(server, low).is(Blocks.STONE) && state(server, low.offset(3, 1, -3)).is(Blocks.GLASS), "the slab did not build at its height");

		transforms(context, server, connection, origin);
		handles(context, server, connection, origin);
		pictures(context, server, connection, origin, hut);
		server.runCommand("gamemode survival @a");
		server.runOnServer(s -> connection.getServerPlayer().getInventory().clearContent());
	}

	/** Turned, mirrored and turned over before it is built, remembered on the miniature. */
	private static void transforms(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos origin) {
		BlockState stair = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
		Miniature asym = mini(FULL, FULL, List.of(
			new Cell(0, 0, 3, stair),
			new Cell(3, 0, 3, Blocks.STONE.defaultBlockState()),
			new Cell(0, 0, 0, Blocks.GLASS.defaultBlockState())));

		// A quarter turn clockwise: the corner's stair goes to the back left and faces south.
		BlockPos turned = site(server, connection, Looks.plain(asym).toItem(), origin.east(28));
		openDialog(server, connection, turned);
		press(server, connection, "turn_right");
		server.runOnServer(s -> PandoricalApi.screens().close(connection.getServerPlayer(), ConstructScreen.openId(connection.getServerPlayer())));
		openDialog(server, connection, turned);
		check(server.computeOnServer(s -> ((MiniatureBlockEntity) s.overworld().getBlockEntity(turned)).transform()).equals(new Transform(1, false, false, false)),
			"the turn was not remembered through closing and opening the dialog");
		check(server.computeOnServer(s -> Constructing.survey(connection.getServerPlayer(), s.overworld(), turned).transform().describe().getString()).equals("Turned 90 clockwise"),
			"the dialog does not say it is turned");
		press(server, connection, "construct");
		check(state(server, turned).is(Blocks.STONE), "turned, the front right stone is not at the corner");
		check(state(server, turned.offset(0, 0, -3)).is(Blocks.OAK_STAIRS) && state(server, turned.offset(0, 0, -3)).getValue(StairBlock.FACING) == Direction.SOUTH,
			"turned, the stair is not at the back left facing south: " + state(server, turned.offset(0, 0, -3)));
		check(state(server, turned.offset(3, 0, -3)).is(Blocks.GLASS), "turned, the glass is not at the back right");

		// Mirrored left-right: west and east swap, and the stair faces west.
		BlockPos mirrored = site(server, connection, Looks.plain(asym).toItem(), origin.east(28).south(8));
		openDialog(server, connection, mirrored);
		press(server, connection, "mirror_lr");
		press(server, connection, "construct");
		check(state(server, mirrored).is(Blocks.STONE), "mirrored, the stone is not at the corner");
		check(state(server, mirrored.offset(3, 0, 0)).is(Blocks.OAK_STAIRS) && state(server, mirrored.offset(3, 0, 0)).getValue(StairBlock.FACING) == Direction.WEST,
			"mirrored, the stair is not at the front right facing west");
		check(state(server, mirrored.offset(3, 0, -3)).is(Blocks.GLASS), "mirrored, the glass is not at the back right");

		// Upside down: a bottom slab becomes a top one up top, a stair's half flips, the stone comes down.
		BlockState low = Blocks.OAK_SLAB.defaultBlockState();
		BlockState step = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
		Miniature over = mini(FULL, FULL, List.of(new Cell(0, 0, 3, low), new Cell(1, 0, 3, step), new Cell(3, 3, 3, Blocks.STONE.defaultBlockState())));
		BlockPos flipped = site(server, connection, Looks.plain(over).toItem(), origin.east(28).south(16));
		openDialog(server, connection, flipped);
		press(server, connection, "flip");
		// The miniature itself turns over with the button, not only the outline: drawn, shaped and carried flipped.
		check(server.computeOnServer(s -> {
				Miniature shown = ((MiniatureBlockEntity) s.overworld().getBlockEntity(flipped)).miniature();
				Miniature upside = Transform.NONE.flipped().apply(over).build();
				return java.util.Arrays.equals(shown.cells(), upside.cells()) && shown.palette().equals(upside.palette()) && shown.height() == upside.height();
			}),
			"the miniature was not drawn upside down when Flip was pressed");
		check(server.computeOnServer(s -> !s.overworld().getEntitiesOfClass(net.minecraft.world.entity.Display.BlockDisplay.class, new net.minecraft.world.phys.AABB(flipped).inflate(0.01),
				d -> d.entityTags().contains(MiniatureBlockEntity.FIGURE_TAG) && d.getBlockState().is(Blocks.STONE) && d.entityTags().stream().anyMatch(tag -> tag.endsWith("3,0,3"))).isEmpty()),
			"the miniature's figures were not redrawn upside down: its stone is not at the bottom");
		check(server.computeOnServer(s -> {
				var saved = s.overworld().getBlockEntity(flipped).saveWithoutMetadata(s.registryAccess());
				Miniature upside = Transform.NONE.flipped().apply(over).build();
				return justfatlard.minicraft.MiniatureDrawing.draw(s.overworld().getBlockState(flipped), saved)
					.map(d -> java.util.Objects.deepEquals(d.cells(), upside.cells()) && d.palette().equals(upside.palette())).orElse(false);
			}), "carried to sea, the miniature would not be drawn upside down");
		press(server, connection, "construct");
		check(state(server, flipped.offset(0, 3, 0)).equals(low.setValue(SlabBlock.TYPE, SlabType.TOP)), "upside down, the slab is not a top slab up top");
		check(state(server, flipped.offset(1, 3, 0)).equals(step.setValue(StairBlock.HALF, net.minecraft.world.level.block.state.properties.Half.TOP)), "upside down, the stair's half did not flip");
		check(state(server, flipped.offset(3, 0, 0)).is(Blocks.STONE), "upside down, the stone did not come down");

		// A torch has no upside-down form: kept as it is, hanging under its stone with nothing to stand on, so refused until it is the right way up.
		Miniature torch = mini(FULL, FULL, List.of(new Cell(0, 0, 3, Blocks.STONE.defaultBlockState()), new Cell(0, 1, 3, Blocks.TORCH.defaultBlockState())));
		BlockPos lamp = site(server, connection, Looks.plain(torch).toItem(), origin.east(36));
		openDialog(server, connection, lamp);
		press(server, connection, "flip");
		Constructing.Survey survey = server.computeOnServer(s -> Constructing.survey(connection.getServerPlayer(), s.overworld(), lamp));
		check(survey.why() == Constructing.Why.WONT_STAND && survey.unflipped() == 1 && survey.fallen() == 1, "an upside-down torch was not refused: " + survey.why());
		press(server, connection, "construct");
		check(state(server, lamp).is(Main.MINIATURE) && state(server, lamp.above(3)).isAir(), "an upside-down torch was built anyway");
		press(server, connection, "reset");
		press(server, connection, "construct");
		check(state(server, lamp).is(Blocks.STONE) && state(server, lamp.above()).is(Blocks.TORCH), "reset, the torch did not build the right way up");
	}

	/**
	 * A handle or a function, never both: the bench refuses the two together both ways round, a
	 * handle is worked by every click but a sneaking empty hand, which opens the dialog, and a
	 * sneaking block, which is placed; an item from before with both works as its function, its
	 * things kept.
	 */
	private static void handles(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos origin) {
		BlockState lever = Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR);
		Miniature off = mini(FULL, FULL, List.of(new Cell(0, 0, 3, Blocks.STONE.defaultBlockState()), new Cell(0, 1, 3, lever)));
		Miniature on = mini(FULL, FULL, List.of(new Cell(0, 0, 3, Blocks.STONE.defaultBlockState()), new Cell(0, 1, 3, lever.setValue(LeverBlock.POWERED, true))));
		Looks handled = new Looks(off, Optional.empty(), Optional.of(on), Optional.empty(), Optional.of(new BlockPos(0, 1, 3)), Optional.empty(), Optional.empty());
		server.runCommand("gamemode survival @a");
		BlockPos at = site(server, connection, handled.toItem(), origin.east(36).south(8));

		use(server, connection, at);
		check(state(server, at).getValue(MiniatureBlock.HANDLED), "a plain right-click did not work the handle");
		sneakUse(server, connection, at, new ItemStack(Items.STICK), Direction.UP);
		check(!state(server, at).getValue(MiniatureBlock.HANDLED), "a sneaking click with a stick did not work the handle");
		sneakUse(server, connection, at, ItemStack.EMPTY, Direction.UP);
		check(!state(server, at).getValue(MiniatureBlock.HANDLED), "a sneaking empty hand worked the handle");
		check(server.computeOnServer(s -> ConstructScreen.openId(connection.getServerPlayer()) != null), "a sneaking empty hand did not open the dialog");
		server.runOnServer(s -> PandoricalApi.screens().close(connection.getServerPlayer(), ConstructScreen.openId(connection.getServerPlayer())));
		sneakUse(server, connection, at, new ItemStack(Items.COBBLESTONE), Direction.UP);
		check(state(server, at.above()).is(Blocks.COBBLESTONE), "a sneaking click with a block did not place it on the miniature");
		check(!state(server, at).getValue(MiniatureBlock.HANDLED), "placing a block on it worked the handle");

		// From before: a handle and a chest both. It works as the chest, and keeps what it holds.
		Looks both = handled.withFunction(Optional.of(Blocks.CHEST));
		check(!both.hasHandle(), "a miniature with a function still has a handle");
		BlockPos old = site(server, connection, both.toItem(), origin.east(36).south(16));
		server.runOnServer(s -> ((MiniatureBlockEntity) s.overworld().getBlockEntity(old)).function().container().setItem(0, new ItemStack(Items.DIAMOND, 2)));
		use(server, connection, old);
		check(server.computeOnServer(s -> connection.getServerPlayer().containerMenu instanceof ChestMenu), "an old handle-and-chest did not open as a chest");
		check(!state(server, old).getValue(MiniatureBlock.HANDLED), "an old handle-and-chest worked its handle");
		server.runOnServer(s -> connection.getServerPlayer().closeContainer());
		ItemStack kept = breakAndTake(context, server, connection, old);
		check(Bench.holdsAnything(kept) && Looks.of(kept).orElseThrow().function().equals(Optional.of(Blocks.CHEST)), "the old handle-and-chest lost its diamonds or its chest");
	}

	/** The bench will not make a miniature with a handle and a function, whichever comes first. */
	private static void oneOrTheOther(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos bench, int plot) {
		build(server, plot, List.of(new Cell(0, 0, 0, Blocks.STONE.defaultBlockState()), new Cell(1, 0, 0, Blocks.STONE.defaultBlockState())));
		// A handle marked: a block for the slot is refused, by hand and through the screen's slot.
		server.runOnServer(s -> Ledger.get(s).setHandle(plot, new BlockPos(0, 0, 0)));
		check(!putFunction(server, connection, bench, new ItemStack(Items.CHEST)), "a chest went in the slot with a handle marked");
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).function().isEmpty()), "the slot holds something with a handle marked");
		// The slot filled: the stick marks nothing.
		server.runOnServer(s -> Ledger.get(s).setHandle(plot, null));
		check(putFunction(server, connection, bench, new ItemStack(Items.CHEST)), "the chest was refused with no handle marked");
		check(server.computeOnServer(s -> Visits.enter(connection.getServerPlayer(), plot)), "could not step in to mark a handle");
		context.waitTicks(5);
		server.runOnServer(s -> Visits.markHandle(connection.getServerPlayer(), Workshop.origin(plot)));
		check(server.computeOnServer(s -> Ledger.get(s).project(plot).handle() == null), "the stick marked a handle with a chest in the slot");
		server.runOnServer(s -> Visits.leave(connection.getServerPlayer()));
		context.waitTicks(5);
		// Both at once, however it came about: finishing refuses.
		server.runOnServer(s -> Ledger.get(s).setHandle(plot, new BlockPos(0, 0, 0)));
		check(!server.computeOnServer(s -> Bench.finish(connection.getServerPlayer(), s.overworld(), bench)), "finished with a handle and a chest both");
		server.runOnServer(s -> {
			Ledger.get(s).setHandle(plot, null);
			Bench.takeFunction(connection.getServerPlayer(), s.overworld(), bench);
			Workshop.clear(Workshop.level(s), plot, N);
			connection.getServerPlayer().getInventory().clearContent();
		});
	}

	/** With -Ppictures: the dialog, and the preview in the world with a spot in the way. */
	private static void pictures(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos origin, Miniature hut) {
		if (!justfatlard.pandorical.gametest.Pictures.wanted()) return;
		server.runCommand("gamemode survival @a");
		server.runOnServer(s -> connection.getServerPlayer().getInventory().clearContent());
		carry(server, connection, Map.of(Items.STONE, 1L, Items.GLASS, 1L, Items.OAK_DOOR, 1L, Items.OAK_STAIRS, 1L, Items.OAK_SLAB, 1L));
		ItemStack named = Looks.plain(hut).toItem();
		named.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Hut"));
		BlockPos at = site(server, connection, named, origin.east(44));
		server.runOnServer(s -> s.overworld().setBlockAndUpdate(at.offset(2, 1, -2), Blocks.DIRT.defaultBlockState()));
		openDialog(server, connection, at);
		context.waitTicks(10);
		justfatlard.pandorical.gametest.Pictures.shootScreen(context, connection, "construct_dialog");
		press(server, connection, "preview");
		check(server.computeOnServer(s -> Preview.showing(connection.getServerPlayer(), at)), "the preview did not come on");
		server.runOnServer(s -> PandoricalApi.screens().close(connection.getServerPlayer(), ConstructScreen.openId(connection.getServerPlayer())));
		context.runOnClient(client -> client.gui.setScreen(null));
		justfatlard.pandorical.gametest.Pictures.look(server, Vec3.atCenterOf(at).add(-3.5, 2.5, 5.5), Vec3.atCenterOf(at).add(2, 1, -2));
		context.waitTicks(20);
		check(server.computeOnServer(s -> Preview.showing(connection.getServerPlayer(), at)), "the preview stopped by itself");
		justfatlard.pandorical.gametest.Pictures.frame(context, connection, "construct_preview");
		// The dialog was closed for the shot, so the preview is stopped directly, not by its button.
		server.runOnServer(s -> Preview.stop(connection.getServerPlayer(), false));
	}

	/** A build cut {@code n} to a side and this high, from its filled cells. */
	private static Miniature mini(int n, int height, List<Cell> cells) {
		List<BlockState> palette = new java.util.ArrayList<>();
		byte[] bytes = new byte[n * height * n];
		for (Cell cell : cells) {
			int index = palette.indexOf(cell.state());
			if (index < 0) {
				palette.add(cell.state());
				index = palette.size() - 1;
			}
			bytes[(cell.y() * n + cell.z()) * n + cell.x()] = (byte) (index + 1);
		}
		return new Miniature(n, height, List.copyOf(palette), bytes);
	}

	/**
	 * Place a miniature on a stone, on a stone floor laid where its build would stand and the air
	 * above it cleared, with the player stood just in front of it, out of its way.
	 */
	private static BlockPos site(TestServerContext server, TestServerConnection connection, ItemStack item, BlockPos stand) {
		server.runOnServer(s -> {
			ServerLevel level = s.overworld();
			for (int x = -1; x <= FULL; x++) for (int z = -FULL; z <= 1; z++) {
				level.setBlockAndUpdate(stand.offset(x, 0, z), Blocks.STONE.defaultBlockState());
				for (int y = 1; y <= FULL + 1; y++) level.setBlockAndUpdate(stand.offset(x, y, z), Blocks.AIR.defaultBlockState());
			}
			connection.getServerPlayer().teleportTo(stand.getX() + 0.5, stand.getY() + 1, stand.getZ() + 3.5);
		});
		return place(server, connection, item, stand);
	}

	/** The dialog opened as a sneaking empty-handed right-click opens it. */
	private static void openDialog(TestServerContext server, TestServerConnection connection, BlockPos at) {
		sneakUse(server, connection, at, ItemStack.EMPTY, Direction.SOUTH);
		check(server.computeOnServer(s -> ConstructScreen.openId(connection.getServerPlayer()) != null), "a sneaking empty-handed right-click did not open the dialog");
	}

	/** A sneaking right-click on this face, holding this, through the server's own use of a block. */
	private static void sneakUse(TestServerContext server, TestServerConnection connection, BlockPos at, ItemStack held, Direction face) {
		server.runOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			player.setItemInHand(InteractionHand.MAIN_HAND, held.copy());
			player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
			player.setShiftKeyDown(true);
			player.gameMode.useItemOn(player, s.overworld(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(at).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5), face, at, false));
			player.setShiftKeyDown(false);
			player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		});
	}

	/** A button on the dialog pressed, as its packet arrives: through Pandorical's own handler. */
	private static void press(TestServerContext server, TestServerConnection connection, String button) {
		server.runOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			String id = ConstructScreen.openId(player);
			check(id != null, "no dialog open to press " + button + " on");
			justfatlard.pandorical.screen.ScreenRegistry.INSTANCE.handleAction(player, new justfatlard.pandorical.protocol.ScreenActionC2S(id, button, "click", Map.of()));
		});
	}

	/** The pack emptied and given exactly this. */
	private static void carry(TestServerContext server, TestServerConnection connection, Map<net.minecraft.world.item.Item, Long> kit) {
		server.runOnServer(s -> {
			var inventory = connection.getServerPlayer().getInventory();
			for (int i = 0; i < inventory.getContainerSize(); i++) if (!inventory.getItem(i).is(Main.MINIATURE_ITEM)) inventory.setItem(i, ItemStack.EMPTY);
			int slot = 9;
			for (Map.Entry<net.minecraft.world.item.Item, Long> entry : kit.entrySet()) inventory.setItem(slot++, new ItemStack(entry.getKey(), (int) (long) entry.getValue()));
		});
	}

	private static long carried(TestServerContext server, TestServerConnection connection, net.minecraft.world.item.Item item) {
		return server.computeOnServer(s -> (long) connection.getServerPlayer().getInventory().countItem(item));
	}

	/** Refused: the miniature still there and nothing of its build placed. */
	private static void untouched(TestServerContext server, TestServerConnection connection, BlockPos at, String when) {
		check(state(server, at).is(Main.MINIATURE), "the miniature went, " + when);
		check(state(server, at.offset(3, 3, -3)).isAir() && !state(server, at.offset(1, 0, -2)).is(Blocks.OAK_DOOR), "something was placed, " + when);
	}

	private static long cells(TestServerContext server, int plot, net.minecraft.world.item.Item item) {
		return server.computeOnServer(s -> Ledger.get(s).project(plot).cells(item));
	}

	/** A build's cells: the first {@code filled} given, the rest air. */
	private static byte[] cells(int size, int... filled) {
		byte[] cells = new byte[size];
		for (int i = 0; i < filled.length; i++) cells[i] = (byte) filled[i];
		return cells;
	}

	private static boolean sameBuild(Miniature a, Miniature b) {
		if (a.n() != b.n() || a.height() != b.height()) return false;
		for (int y = 0; y < a.height(); y++) for (int z = 0; z < a.n(); z++) for (int x = 0; x < a.n(); x++) {
			if (!a.at(x, y, z).equals(b.at(x, y, z))) return false;
		}
		return true;
	}

	private static int miniatures(TestServerContext server, TestServerConnection connection) {
		return server.computeOnServer(s -> connection.getServerPlayer().getInventory().countItem(Main.MINIATURE_ITEM));
	}

	/** The bench opened as an empty-handed right-click opens it, and its menu. */
	private static AbstractContainerMenu openCopy(TestServerContext server, TestServerConnection connection, BlockPos bench) {
		AbstractContainerMenu menu = server.computeOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			player.gameMode.useItemOn(player, s.overworld(), ItemStack.EMPTY, InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(bench), Direction.UP, bench, false));
			return player.containerMenu;
		});
		check(menu instanceof justfatlard.pandorical.screen.PandoricalMenu, "the bench did not open as a container screen: " + menu);
		return menu;
	}

	private static boolean slotAccepts(TestServerContext server, AbstractContainerMenu menu, int slot, ItemStack stack) {
		return server.computeOnServer(s -> menu.getSlot(slot).mayPlace(stack));
	}

	/** Carried onto the slot and clicked, as a player puts something in. */
	private static void putIn(TestServerContext server, TestServerConnection connection, AbstractContainerMenu menu, int slot, ItemStack stack) {
		server.runOnServer(s -> {
			menu.setCarried(stack);
			menu.clicked(slot, 0, ContainerInput.PICKUP, connection.getServerPlayer());
		});
		check(server.computeOnServer(s -> menu.getCarried().isEmpty()), "slot " + slot + " did not take " + stack.getHoverName().getString());
	}

	/** The copy taken with a click, off the cursor. */
	private static ItemStack take(TestServerContext server, TestServerConnection connection, AbstractContainerMenu menu) {
		ItemStack taken = server.computeOnServer(s -> {
			menu.clicked(2, 0, ContainerInput.PICKUP, connection.getServerPlayer());
			ItemStack carried = menu.getCarried();
			menu.setCarried(ItemStack.EMPTY);
			return carried;
		});
		check(taken.is(Main.MINIATURE_ITEM), "no copy was taken");
		return taken;
	}

	/** Whatever is in the slot picked up and dropped into the pack. */
	private static void takeOut(TestServerContext server, TestServerConnection connection, AbstractContainerMenu menu, int slot) {
		server.runOnServer(s -> {
			menu.clicked(slot, 0, ContainerInput.PICKUP, connection.getServerPlayer());
			connection.getServerPlayer().getInventory().add(menu.getCarried());
			menu.setCarried(ItemStack.EMPTY);
		});
	}

	/** Place from the pack in the workshop with this material's window, the top of a block clicked. */
	private static void placeWith(TestServerContext server, TestServerConnection connection, BlockPos on, net.minecraft.world.item.Item item) {
		server.runOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			for (int i = 0; i < 9; i++) if (player.getInventory().getItem(i).is(item)) player.getInventory().setSelectedSlot(i);
			check(player.getMainHandItem().is(item), "no " + item + " in the pack");
			player.gameMode.useItemOn(player, Workshop.level(s), player.getMainHandItem(), InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(on).add(0, 0.5, 0), Direction.UP, on, false));
		});
	}

	/** The material Pandorical's client holds for a position, on the client's thread; its client classes are not on this source set's compile path. */
	private static BlockState material(BlockPos at) {
		try {
			return (BlockState) Class.forName("justfatlard.pandorical.client.renderer.ClientBlockMaterials").getMethod("at", BlockPos.class).invoke(null, at);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Pandorical's client materials could not be read", e);
		}
	}

	/** Place from the pack in the workshop, the top of a block clicked, as a builder does. */
	private static void placeIn(TestServerContext server, TestServerConnection connection, BlockPos on) {
		server.runOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			for (int i = 0; i < 9; i++) if (player.getInventory().getItem(i).is(Items.STONE)) player.getInventory().setSelectedSlot(i);
			player.gameMode.useItemOn(player, Workshop.level(s), player.getMainHandItem(), InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(on).add(0, 0.5, 0), Direction.UP, on, false));
		});
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

	/**
	 * Carried off and set down the way Big Boats moves a block, which neither places nor breaks
	 * it: the block entity saved and taken first, then the block cleared with no neighbour update;
	 * and later the block set and the saved block entity loaded back. Its drawing goes with it
	 * both ways, rather than staying where it stood and never coming back where it lands.
	 */
	private static void shipped(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, BlockPos stand) {
		ItemStack item = Looks.plain(mini(N, N, List.of(new Cell(0, 0, 0, Blocks.STONE.defaultBlockState()),
			new Cell(1, 0, 1, Blocks.OAK_PLANKS.defaultBlockState())))).toItem();
		BlockPos at = place(server, connection, item, stand);
		context.waitTicks(3);
		check(drawn(server, at) > 0, "the placed miniature is not drawn");

		int flags = net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE
			| net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS;
		net.minecraft.nbt.CompoundTag saved = server.computeOnServer(s -> {
			ServerLevel level = s.overworld();
			var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
			level.getBlockEntity(at).saveWithId(output);
			level.removeBlockEntity(at);
			level.setBlock(at, Blocks.AIR.defaultBlockState(), flags);
			return output.buildResult();
		});
		context.waitTicks(3);
		check(drawn(server, at) == 0, "a miniature carried off left its drawing where it stood: " + drawn(server, at) + " pieces");
		check(server.computeOnServer(s -> s.overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(at).inflate(2)).isEmpty()),
			"a miniature carried off dropped something");

		BlockPos landed = at.east(3);
		server.runOnServer(s -> {
			ServerLevel level = s.overworld();
			level.setBlockAndUpdate(landed.below(), Blocks.STONE.defaultBlockState());
			BlockState state = Main.MINIATURE.defaultBlockState();
			level.setBlock(landed, state, flags);
			var restored = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(landed, state, saved, level.registryAccess());
			check(restored != null, "the saved miniature did not load back");
			level.setBlockEntity(restored);
		});
		context.waitTicks(3);
		check(drawn(server, landed) > 0, "a miniature set down by something that never places it was left undrawn");
		int once = drawn(server, landed);
		context.waitTicks(5);
		check(drawn(server, landed) == once, "the set-down miniature was drawn again: " + once + " pieces, then " + drawn(server, landed));
		check(server.computeOnServer(s -> s.overworld().getBlockEntity(landed) instanceof MiniatureBlockEntity m && m.looks().isPresent()),
			"the set-down miniature lost its build");
		server.runOnServer(s -> s.overworld().setBlockAndUpdate(landed, Blocks.AIR.defaultBlockState()));
	}

	/** How many of a miniature's drawn pieces (figures and its anchor) stand at this block. */
	private static int drawn(TestServerContext server, BlockPos at) {
		return server.computeOnServer(s -> s.overworld().getEntitiesOfClass(net.minecraft.world.entity.Display.BlockDisplay.class,
			new AABB(at).inflate(0.01), piece -> piece.entityTags().contains(MiniatureBlockEntity.ANCHOR_TAG)
				|| piece.entityTags().contains(MiniatureBlockEntity.FIGURE_TAG)).size());
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

	/** Placed as a player places it: aimed at the top of the stand from beside it and the use key pressed, so the client predicts it first. */
	private static BlockPos placeByHand(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, ItemStack item, BlockPos stand) {
		Vec3 was = server.computeOnServer(s -> connection.getServerPlayer().position());
		float[] looked = server.computeOnServer(s -> new float[] {connection.getServerPlayer().getYRot(), connection.getServerPlayer().getXRot()});
		server.runOnServer(s -> {
			s.overworld().setBlockAndUpdate(stand, Blocks.STONE.defaultBlockState());
			s.overworld().setBlockAndUpdate(stand.above(), Blocks.AIR.defaultBlockState());
			ServerPlayer player = connection.getServerPlayer();
			player.setItemInHand(InteractionHand.MAIN_HAND, item);
			Vec3 feet = Vec3.atBottomCenterOf(stand.south(2));
			Vec3 aim = Vec3.atCenterOf(stand).add(0, 0.5, 0).subtract(feet.add(0, player.getEyeHeight(), 0));
			float yaw = (float) Math.toDegrees(Math.atan2(-aim.x, aim.z));
			float pitch = (float) -Math.toDegrees(Math.atan2(aim.y, Math.sqrt(aim.x * aim.x + aim.z * aim.z)));
			player.teleportTo(s.overworld(), feet.x, feet.y, feet.z, java.util.Set.of(), yaw, pitch, true);
		});
		context.waitTicks(5);
		context.getInput().pressKey(options -> options.keyUse);
		context.waitTicks(5);
		BlockPos at = stand.above();
		check(server.computeOnServer(s -> s.overworld().getBlockState(at).is(Main.MINIATURE)), "the miniature was not placed by hand");
		server.runOnServer(s -> connection.getServerPlayer().teleportTo(s.overworld(), was.x, was.y, was.z, java.util.Set.of(), looked[0], looked[1], true));
		context.waitTicks(3);
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
