package justfatlard.minicraft;

import justfatlard.pandorical.api.BlockRegistration;
import justfatlard.pandorical.api.ItemRegistration;
import justfatlard.pandorical.api.PandoricalApi;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import java.util.Set;

/**
 * Minicraft: a bench that turns a full-size build into one block.
 *
 * <p>Choose how finely a block is cut, load materials, step into a box in the workshop and build
 * at full size with exactly what was loaded, and come out with a block that is that build at
 * 1/N scale.
 */
public class Main implements ModInitializer {
	public static final String MOD_ID = "minicraft-justfatlard";

	public static final Identifier BENCH_ID = Identifier.fromNamespaceAndPath(MOD_ID, "bench");
	public static final Identifier MINIATURE_ID = Identifier.fromNamespaceAndPath(MOD_ID, "miniature");

	private static final float BENCH_STRENGTH = 2.5F;
	private static final float MINIATURE_STRENGTH = 0.8F;

	public static final BenchBlock BENCH = new BenchBlock(BlockBehaviour.Properties.of()
		.mapColor(MapColor.WOOD).strength(BENCH_STRENGTH).sound(SoundType.WOOD)
		.setId(ResourceKey.create(Registries.BLOCK, BENCH_ID)));
	public static final BlockEntityType<BenchBlockEntity> BENCH_BLOCK_ENTITY = new BlockEntityType<>(BenchBlockEntity::new, Set.of(BENCH));
	public static final Item BENCH_ITEM = new BlockItem(BENCH, new Item.Properties()
		.setId(ResourceKey.create(Registries.ITEM, BENCH_ID)).useBlockDescriptionPrefix());

	public static final MiniatureBlock MINIATURE = new MiniatureBlock(BlockBehaviour.Properties.of()
		.strength(MINIATURE_STRENGTH).sound(SoundType.STONE)
		.setId(ResourceKey.create(Registries.BLOCK, MINIATURE_ID)));
	public static final BlockEntityType<MiniatureBlockEntity> MINIATURE_BLOCK_ENTITY = new BlockEntityType<>(MiniatureBlockEntity::new, Set.of(MINIATURE));
	public static final Item MINIATURE_ITEM = new BlockItem(MINIATURE, new Item.Properties()
		.setId(ResourceKey.create(Registries.ITEM, MINIATURE_ID)).useBlockDescriptionPrefix().stacksTo(1));

	@Override
	public void onInitialize() {
		PandoricalApi.content().registerBlock(BENCH_ID.toString(), new BlockRegistration()
			.baseBlock("minecraft:oak_planks")
			.interactive()
			.strength(BENCH_STRENGTH)
			.model(MOD_ID + ":block/bench"));
		// Glass for the stand-in: a full block, no properties of its own, and nothing drawn but
		// what this block's model draws, which is nothing. The build is drawn by displays.
		PandoricalApi.content().registerBlock(MINIATURE_ID.toString(), new BlockRegistration()
			.baseBlock("minecraft:glass")
			// A handle is worked and a function opened with a right-click: the client must not predict a placement.
			.interactive()
			.strength(MINIATURE_STRENGTH)
			.requiresCorrectTool(false)
			.model(MOD_ID + ":block/miniature"));
		PandoricalApi.content().registerItem(BENCH_ID.toString(), new ItemRegistration().model(MOD_ID + ":item/bench"));
		PandoricalApi.content().registerItem(MINIATURE_ID.toString(), new ItemRegistration().model(MOD_ID + ":item/miniature"));
		PandoricalApi.content().registerModAssets(MOD_ID);

		Registry.register(BuiltInRegistries.BLOCK, BENCH_ID, BENCH);
		Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, BENCH_ID, BENCH_BLOCK_ENTITY);
		Registry.register(BuiltInRegistries.ITEM, BENCH_ID, BENCH_ITEM);
		Registry.register(BuiltInRegistries.BLOCK, MINIATURE_ID, MINIATURE);
		Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, MINIATURE_ID, MINIATURE_BLOCK_ENTITY);
		Registry.register(BuiltInRegistries.ITEM, MINIATURE_ID, MINIATURE_ITEM);

		Session.init();
		BenchScreen.register();
		ConstructScreen.register();

		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (blockEntity instanceof BenchBlockEntity && Bench.occupied(level, pos)) {
				if (player instanceof ServerPlayer told) Bench.tellBusy(told, level, pos);
				return false;
			}
			if (!(level instanceof ServerLevel server) || !Workshop.isWorkshop(server)) return true;
			// In the workshop a builder breaks only what is in their own box; nobody else breaks anything but in creative.
			if (!(player instanceof ServerPlayer builder)) return false;
			Session session = Session.of(builder);
			if (session == null) return builder.isCreative();
			Ledger.Project project = Ledger.get(server.getServer()).project(session.plot());
			return project != null && Workshop.inBox(pos, session.plot(), project.n(), project.height());
		});
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (level instanceof ServerLevel server && Workshop.isWorkshop(server) && player instanceof ServerPlayer builder) {
				Visits.broke(builder, pos, state);
			}
		});

		// A handle worked with something in hand: the client, not knowing, uses the item in the air next.
		UseItemCallback.EVENT.register((player, level, hand) ->
			player instanceof ServerPlayer user && MiniatureBlockEntity.justWorkedWithItem(user) ? InteractionResult.FAIL : InteractionResult.PASS);
		// A sneaking click with something that is not a block works a miniature's handle; vanilla would hand it to the item.
		UseBlockCallback.EVENT.register(MiniatureBlock::sneakWithItem);
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!Visits.isLeave(player.getItemInHand(hand))) return InteractionResult.PASS;
			if (player instanceof ServerPlayer builder) Visits.leave(builder);
			return InteractionResult.SUCCESS;
		});

		// In the workshop the stick marks the handle, and anything that holds things stays shut:
		// none can be loaded, but material put away in one would be material that never came back.
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!(level instanceof ServerLevel server) || !Workshop.isWorkshop(server) || !(player instanceof ServerPlayer builder)) return InteractionResult.PASS;
			if (Session.of(builder) == null) return InteractionResult.PASS;
			if (Visits.isMarker(player.getItemInHand(hand))) {
				Visits.markHandle(builder, hit.getBlockPos());
				return InteractionResult.SUCCESS;
			}
			if (server.getBlockEntity(hit.getBlockPos()) instanceof net.minecraft.world.Container && !player.isSecondaryUseActive()) return InteractionResult.FAIL;
			return InteractionResult.PASS;
		});
		ServerTickEvents.END_SERVER_TICK.register(Visits::watch);
		ServerTickEvents.END_SERVER_TICK.register(Capture::tick);
		ServerTickEvents.END_SERVER_TICK.register(Breaking::tick);
		ServerTickEvents.END_SERVER_TICK.register(BenchScreen::tick);
		ServerTickEvents.END_SERVER_TICK.register(ConstructScreen::tick);
		ServerTickEvents.END_SERVER_TICK.register(Preview::tick);
		ServerTickEvents.END_SERVER_TICK.register(MiniatureBlockEntity::upkeep);
		PandoricalApi.structures().drawings().register(MiniatureDrawing::draw);
		// Pandorical keeps structures in memory only: draw each miniature again as its anchor loads, and let it go as it unloads.
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Display.BlockDisplay anchor && anchor.entityTags().contains(MiniatureBlockEntity.ANCHOR_TAG)
					&& level.getBlockEntity(anchor.blockPosition()) instanceof MiniatureBlockEntity miniature) {
				miniature.build(anchor);
			}
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof Display.BlockDisplay anchor && anchor.entityTags().contains(MiniatureBlockEntity.ANCHOR_TAG)) {
				PandoricalApi.structures().despawn(MiniatureBlockEntity.structureId(anchor));
				PandoricalApi.blockShapes().clearShape(level, anchor.blockPosition());
				PandoricalApi.blockMaterials().clearMaterial(level, anchor.blockPosition());
			}
		});
		// A builder who died in the workshop wakes up outside it: put their pack back there.
		ServerPlayerEvents.AFTER_RESPAWN.register((before, after, alive) -> {
			Session session = Session.of(after);
			if (session != null && !Workshop.isWorkshop(after.level())) Visits.finish(after, session);
		});
		// Isolated in its own class and only reached from here: it names block-tip's types directly.
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("block-tip")) {
			justfatlard.minicraft.integration.MinicraftTips.register();
		}
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			BenchScreen.forget(handler.getPlayer().getUUID());
			ConstructScreen.forget(handler.getPlayer().getUUID());
			Preview.forget(handler.getPlayer().getUUID());
		});
	}
}
