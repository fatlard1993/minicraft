package justfatlard.minicraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Every block a miniature can work as, and how: one small kind each, so another is one more entry.
 *
 * <ul>
 * <li><b>Menus</b> that keep nothing: crafting table, stonecutter, loom, smithing table, grindstone,
 * cartography table, enchanting table (powered by real bookshelves round the placed miniature, as
 * vanilla counts them), the three anvils (worn as an anvil wears, until it breaks and the
 * miniature works as nothing), and the ender chest (the player's own).</li>
 * <li><b>Storage</b>: any chest, a barrel, a shulker box. 27 slots, kept on the miniature. A trapped
 * chest gives off a signal while it is open.</li>
 * <li><b>Working</b>: the furnace, smoker, blast furnace and brewing stand, each a real vanilla
 * block entity held by the miniature and ticked by it, with vanilla's own menu; and the jukebox.</li>
 * </ul>
 *
 * <p>Everything else is refused in the bench's functional slot. Whatever keeps things will not
 * take a shulker box or another miniature that keeps things, as a shulker box will not take a
 * shulker box: no box inside a box.
 */
public final class Functions {
	private Functions() {}

	public static final int STORAGE_SLOTS = 27;

	/** How one kind of block is worked; makes the function a placed miniature holds. */
	private interface Kind {
		Function create(Block block, MiniatureBlockEntity host);

		/** Whether it keeps things in it, which the item carries. */
		boolean keepsThings();
	}

	private static final Map<Block, Identifier> MENU_STATS = Map.of(
		Blocks.CRAFTING_TABLE, Stats.INTERACT_WITH_CRAFTING_TABLE,
		Blocks.STONECUTTER, Stats.INTERACT_WITH_STONECUTTER,
		Blocks.LOOM, Stats.INTERACT_WITH_LOOM,
		Blocks.SMITHING_TABLE, Stats.INTERACT_WITH_SMITHING_TABLE,
		Blocks.GRINDSTONE, Stats.INTERACT_WITH_GRINDSTONE,
		Blocks.CARTOGRAPHY_TABLE, Stats.INTERACT_WITH_CARTOGRAPHY_TABLE);

	private static final Kind MENU = kind(false, Menu::new);
	private static final Kind ANVIL = kind(false, Anvil::new);
	private static final Kind ENCHANTING = kind(false, (block, host) -> new Enchanting(host));
	private static final Kind ENDER = kind(false, (block, host) -> new Ender(host));
	private static final Kind STORAGE = kind(true, Storage::new);
	private static final Kind COOKING = kind(true, (block, host) -> new Held(block, host, Held.Ticker.COOKING));
	private static final Kind BREWING = kind(true, (block, host) -> new Held(block, host, Held.Ticker.BREWING));
	private static final Kind JUKEBOX = kind(true, (block, host) -> new Held(block, host, Held.Ticker.JUKEBOX));

	private static Kind kind(boolean keepsThings, java.util.function.BiFunction<Block, MiniatureBlockEntity, Function> make) {
		return new Kind() {
			@Override
			public Function create(Block block, MiniatureBlockEntity host) {
				return make.apply(block, host);
			}

			@Override
			public boolean keepsThings() {
				return keepsThings;
			}
		};
	}

	private static Kind kindOf(Block block) {
		if (MENU_STATS.containsKey(block)) return MENU;
		if (block.defaultBlockState().is(BlockTags.ANVIL) && block instanceof AnvilBlock) return ANVIL;
		if (block == Blocks.ENCHANTING_TABLE) return ENCHANTING;
		if (block == Blocks.ENDER_CHEST) return ENDER;
		if (block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock) return STORAGE;
		if (block instanceof AbstractFurnaceBlock furnace && furnace.newBlockEntity(BlockPos.ZERO, furnace.defaultBlockState()) instanceof AbstractFurnaceBlockEntity) return COOKING;
		if (block instanceof BrewingStandBlock) return BREWING;
		if (block instanceof JukeboxBlock) return JUKEBOX;
		return null;
	}

	/** Whether a miniature can work as this block. */
	public static boolean supported(Block block) {
		return kindOf(block) != null;
	}

	public static boolean keepsThings(Block block) {
		Kind kind = kindOf(block);
		return kind != null && kind.keepsThings();
	}

	/** The function a miniature has as this block, or null if it cannot work as it. */
	static Function create(Block block, MiniatureBlockEntity host) {
		Kind kind = kindOf(block);
		return kind == null ? null : kind.create(block, host);
	}

	/** Why this cannot go in the functional slot, or null if it can. */
	public static Component refusal(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem item)) {
			return Component.translatableWithFallback("minicraft-justfatlard.function.not_a_block", "Only a block goes in the functional slot.");
		}
		if (!supported(item.getBlock())) {
			return Component.translatableWithFallback("minicraft-justfatlard.function.unsupported", "A miniature cannot work as %s.", item.getBlock().getName());
		}
		ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
		if (contents != null && contents.nonEmptyItemCopyStream().findAny().isPresent() || stack.has(DataComponents.BLOCK_ENTITY_DATA)) {
			return Component.translatableWithFallback("minicraft-justfatlard.edit.not_empty", "Empty it first.");
		}
		return null;
	}

	/**
	 * What a miniature that keeps things will take: anything but a box that travels with its
	 * contents, a shulker box or another miniature that keeps things.
	 */
	public static boolean fits(ItemStack stack) {
		if (stack.is(ItemTags.SHULKER_BOXES)) return false;
		return !stack.is(Main.MINIATURE_ITEM) || Looks.of(stack).map(looks -> !looks.keepsThings()).orElse(true);
	}

	/**
	 * Whether this container is one kept on a miniature, so its slots hold to {@link #fits}: the
	 * miniature's own storage, its window for hoppers, or a vanilla block entity a miniature holds.
	 */
	public static boolean guards(Container container) {
		if (container instanceof Storage) return true;
		if (container instanceof Window window) return window.guarded;
		return container instanceof BlockEntity entity && hostOf(entity) != null;
	}

	/** The placed miniature holding this block entity as its function, or null if none does. */
	public static MiniatureBlockEntity hostOf(BlockEntity entity) {
		Level level = entity.getLevel();
		if (level == null || entity instanceof MiniatureBlockEntity) return null;
		return level.getBlockEntity(entity.getBlockPos()) instanceof MiniatureBlockEntity host
			&& host.function() instanceof Held held && held.delegate == entity ? host : null;
	}

	/** Whether a player may still be working this placed miniature's function: it has one, and they are in reach. */
	public static boolean stillWorking(Level level, BlockPos pos, Player player) {
		return level.getBlockEntity(pos) instanceof MiniatureBlockEntity host && host.function() != null
			&& player.isWithinBlockInteractionRange(pos, 4.0);
	}

	/** What hoppers see of a miniature's function: its slots, holding to {@link #fits}. */
	static WorldlyContainer forHoppers(Container inner) {
		return new Window(inner, true, inner::stillValid);
	}

	// --- Menus ---

	/** A menu called by the miniature's own name, if it has one. */
	private static MenuProvider named(MenuProvider provider, MiniatureBlockEntity host) {
		if (host.name() == null) return provider;
		return new SimpleMenuProvider(provider::createMenu, host.name());
	}

	/** A block that opens a menu and keeps nothing: the block's own menu, at the miniature. */
	private record Menu(Block block, MiniatureBlockEntity host) implements Function {
		@Override
		public void use(ServerPlayer player, ServerLevel level, BlockPos pos) {
			MenuProvider provider = block.defaultBlockState().getMenuProvider(level, pos);
			if (provider == null) return;
			player.openMenu(named(provider, host));
			player.awardStat(MENU_STATS.get(block));
		}
	}

	/**
	 * An anvil, worn as vanilla wears one: one use in eight or so chips it, a chipped one is
	 * damaged, and a damaged one breaks, leaving the miniature working as nothing.
	 */
	private static final class Anvil implements Function {
		private final Block block;
		private final MiniatureBlockEntity host;

		Anvil(Block block, MiniatureBlockEntity host) {
			this.block = block;
			this.host = host;
		}

		@Override
		public void use(ServerPlayer player, ServerLevel level, BlockPos pos) {
			player.openMenu(new SimpleMenuProvider((id, inventory, opener) -> new Worn(id, inventory, ContainerLevelAccess.create(level, pos), this),
				host.title(Component.translatable("container.repair"))));
			player.awardStat(Stats.INTERACT_WITH_ANVIL);
		}

		/** Vanilla's own chance; vanilla's own wear does nothing here, as the block at the spot is not an anvil. */
		void wear(Player player) {
			if (player.hasInfiniteMaterials() || player.getRandom().nextFloat() >= 0.12F || host.function() != this) return;
			BlockState worn = AnvilBlock.damage(block.defaultBlockState());
			if (worn == null && host.getLevel() != null) host.getLevel().levelEvent(1029, host.getBlockPos(), 0);
			host.changeFunction(worn == null ? Optional.empty() : Optional.of(worn.getBlock()));
		}

		private static final class Worn extends AnvilMenu {
			private final Anvil anvil;

			Worn(int id, Inventory inventory, ContainerLevelAccess access, Anvil anvil) {
				super(id, inventory, access);
				this.anvil = anvil;
			}

			@Override
			protected void onTake(Player player, ItemStack carried) {
				super.onTake(player, carried);
				anvil.wear(player);
			}
		}
	}

	/** An enchanting table, counting the bookshelves round the miniature as vanilla counts round a table. */
	private record Enchanting(MiniatureBlockEntity host) implements Function {
		@Override
		public void use(ServerPlayer player, ServerLevel level, BlockPos pos) {
			player.openMenu(new SimpleMenuProvider((id, inventory, opener) -> new EnchantmentMenu(id, inventory, ContainerLevelAccess.create(level, pos)),
				host.title(Component.translatable("container.enchant"))));
		}
	}

	/** The player's own ender chest, which stays theirs: it is not kept on the miniature. */
	private record Ender(MiniatureBlockEntity host) implements Function {
		@Override
		public void use(ServerPlayer player, ServerLevel level, BlockPos pos) {
			Container chest = new Window(player.getEnderChestInventory(), false,
				who -> !host.isRemoved() && host.function() == this && who.isWithinBlockInteractionRange(pos, 4.0));
			player.openMenu(new SimpleMenuProvider((id, inventory, opener) -> ChestMenu.threeRows(id, inventory, chest),
				host.title(Component.translatable("container.enderchest"))));
			player.awardStat(Stats.OPEN_ENDERCHEST);
			level.playSound(null, pos, SoundEvents.ENDER_CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 1.2F);
		}
	}

	// --- Storage ---

	/** A chest, barrel or shulker box: 27 slots kept on the miniature, opened as a chest opens. */
	private static final class Storage implements Function, Container {
		private final Block block;
		private final MiniatureBlockEntity host;
		private final NonNullList<ItemStack> items = NonNullList.withSize(STORAGE_SLOTS, ItemStack.EMPTY);
		private int open;

		Storage(Block block, MiniatureBlockEntity host) {
			this.block = block;
			this.host = host;
		}

		@Override
		public void use(ServerPlayer player, ServerLevel level, BlockPos pos) {
			player.openMenu(new SimpleMenuProvider((id, inventory, opener) -> ChestMenu.threeRows(id, inventory, this), host.title(block.getName())));
			player.awardStat(block instanceof BarrelBlock ? Stats.OPEN_BARREL : block instanceof ShulkerBoxBlock ? Stats.OPEN_SHULKER_BOX
				: block instanceof TrappedChestBlock ? Stats.TRIGGER_TRAPPED_CHEST : Stats.OPEN_CHEST);
		}

		private SoundEvent sound(boolean opening) {
			if (block instanceof BarrelBlock) return opening ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE;
			if (block instanceof ShulkerBoxBlock) return opening ? SoundEvents.SHULKER_BOX_OPEN : SoundEvents.SHULKER_BOX_CLOSE;
			if (block instanceof ChestBlock chest) return opening ? chest.getOpenChestSound() : chest.getCloseChestSound();
			return opening ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE;
		}

		@Override
		public void startOpen(ContainerUser user) {
			if (open++ == 0) opened(true);
		}

		@Override
		public void stopOpen(ContainerUser user) {
			if (open > 0 && --open == 0) opened(false);
		}

		private void opened(boolean opening) {
			if (!(host.getLevel() instanceof ServerLevel level)) return;
			level.playSound(null, host.getBlockPos(), sound(opening), SoundSource.BLOCKS, 0.5F, 1.2F);
			if (block instanceof TrappedChestBlock) {
				level.updateNeighborsAt(host.getBlockPos(), Main.MINIATURE);
				level.updateNeighborsAt(host.getBlockPos().below(), Main.MINIATURE);
			}
		}

		@Override
		public int signal() {
			return block instanceof TrappedChestBlock ? Math.min(15, open) : 0;
		}

		@Override
		public Container container() {
			return this;
		}

		@Override
		public void save(ValueOutput output) {
			ContainerHelper.saveAllItems(output, items);
		}

		@Override
		public void load(ValueInput input) {
			items.clear();
			ContainerHelper.loadAllItems(input, items);
		}

		@Override
		public int getContainerSize() {
			return items.size();
		}

		@Override
		public boolean isEmpty() {
			return items.stream().allMatch(ItemStack::isEmpty);
		}

		@Override
		public ItemStack getItem(int slot) {
			return items.get(slot);
		}

		@Override
		public ItemStack removeItem(int slot, int count) {
			ItemStack taken = ContainerHelper.removeItem(items, slot, count);
			if (!taken.isEmpty()) setChanged();
			return taken;
		}

		@Override
		public ItemStack removeItemNoUpdate(int slot) {
			return ContainerHelper.takeItem(items, slot);
		}

		@Override
		public void setItem(int slot, ItemStack stack) {
			items.set(slot, stack);
			stack.limitSize(getMaxStackSize(stack));
			setChanged();
		}

		@Override
		public boolean canPlaceItem(int slot, ItemStack stack) {
			return fits(stack);
		}

		@Override
		public void setChanged() {
			host.setChanged();
		}

		@Override
		public boolean stillValid(Player player) {
			return !host.isRemoved() && host.function() == this && player.isWithinBlockInteractionRange(host.getBlockPos(), 4.0);
		}

		@Override
		public void clearContent() {
			items.clear();
		}
	}

	// --- Working ---

	/**
	 * A vanilla block entity held by the miniature, standing at its spot: the furnaces, the
	 * brewing stand, the jukebox. Ticked by the miniature, opened with vanilla's own menu, saved
	 * with the miniature. A furnace setting itself lit, which would put a furnace where the
	 * miniature stands, is caught by a mixin; the brewing stand is ticked with the miniature's own
	 * state, which it leaves alone.
	 */
	private static final class Held implements Function {
		enum Ticker { COOKING, BREWING, JUKEBOX }

		final BlockEntity delegate;
		private final MiniatureBlockEntity host;
		private final Block block;
		private final Ticker ticker;

		Held(Block block, MiniatureBlockEntity host, Ticker ticker) {
			this.block = block;
			this.host = host;
			this.ticker = ticker;
			this.delegate = ((EntityBlock) block).newBlockEntity(host.getBlockPos(), block.defaultBlockState());
			if (host.getLevel() != null) delegate.setLevel(host.getLevel());
		}

		@Override
		public void use(ServerPlayer player, ServerLevel level, BlockPos pos) {
			if (delegate instanceof JukeboxBlockEntity jukebox) {
				jukebox.popOutTheItem();
				return;
			}
			if (delegate instanceof MenuProvider menu) player.openMenu(named(menu, host));
			if (block == Blocks.FURNACE) player.awardStat(Stats.INTERACT_WITH_FURNACE);
			else if (block == Blocks.SMOKER) player.awardStat(Stats.INTERACT_WITH_SMOKER);
			else if (block == Blocks.BLAST_FURNACE) player.awardStat(Stats.INTERACT_WITH_BLAST_FURNACE);
			else if (ticker == Ticker.BREWING) player.awardStat(Stats.INTERACT_WITH_BREWINGSTAND);
		}

		/** A disc into the jukebox, as vanilla puts one in. */
		@Override
		public boolean useItem(ServerPlayer player, ServerLevel level, BlockPos pos, ItemStack stack) {
			if (!(delegate instanceof JukeboxBlockEntity jukebox) || !jukebox.getTheItem().isEmpty() || !stack.has(DataComponents.JUKEBOX_PLAYABLE)) return false;
			jukebox.setTheItem(stack.consumeAndReturn(1, player));
			player.awardStat(Stats.PLAY_RECORD);
			return true;
		}

		@Override
		public boolean ticks() {
			return true;
		}

		@Override
		public void tick(ServerLevel level, BlockPos pos, BlockState host) {
			switch (ticker) {
				case COOKING -> AbstractFurnaceBlockEntity.serverTick(level, pos, delegate.getBlockState(), (AbstractFurnaceBlockEntity) delegate);
				case BREWING -> BrewingStandBlockEntity.serverTick(level, pos, host, (BrewingStandBlockEntity) delegate);
				case JUKEBOX -> JukeboxBlockEntity.tick(level, pos, delegate.getBlockState(), (JukeboxBlockEntity) delegate);
			}
		}

		@Override
		public Container container() {
			return (Container) delegate;
		}

		@Override
		public int analogSignal() {
			return delegate instanceof JukeboxBlockEntity jukebox ? jukebox.getComparatorOutput() : Function.super.analogSignal();
		}

		@Override
		public void save(ValueOutput output) {
			delegate.saveCustomOnly(output);
		}

		@Override
		public void load(ValueInput input) {
			delegate.loadCustomOnly(input);
		}

		@Override
		public void setLevel(Level level) {
			delegate.setLevel(level);
		}

		@Override
		public void removed() {
			// The song stops with the block, as a jukebox's does.
			if (delegate instanceof JukeboxBlockEntity && delegate.getLevel() != null && !delegate.isRemoved()) delegate.setRemoved();
		}
	}

	/** Another container, seen through a window: for hoppers, held to {@link #fits}; for the ender chest, closed once out of reach. */
	private static final class Window implements WorldlyContainer {
		private final Container inner;
		final boolean guarded;
		private final Predicate<Player> valid;

		Window(Container inner, boolean guarded, Predicate<Player> valid) {
			this.inner = inner;
			this.guarded = guarded;
			this.valid = valid;
		}

		@Override
		public int[] getSlotsForFace(Direction direction) {
			if (inner instanceof WorldlyContainer worldly) return worldly.getSlotsForFace(direction);
			int[] all = new int[inner.getContainerSize()];
			for (int i = 0; i < all.length; i++) all[i] = i;
			return all;
		}

		@Override
		public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction direction) {
			return canPlaceItem(slot, stack) && (!(inner instanceof WorldlyContainer worldly) || worldly.canPlaceItemThroughFace(slot, stack, direction));
		}

		@Override
		public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
			return !(inner instanceof WorldlyContainer worldly) || worldly.canTakeItemThroughFace(slot, stack, direction);
		}

		@Override
		public boolean canPlaceItem(int slot, ItemStack stack) {
			return (!guarded || fits(stack)) && inner.canPlaceItem(slot, stack);
		}

		@Override
		public boolean canTakeItem(Container into, int slot, ItemStack stack) {
			return inner.canTakeItem(into, slot, stack);
		}

		@Override
		public int getContainerSize() {
			return inner.getContainerSize();
		}

		@Override
		public boolean isEmpty() {
			return inner.isEmpty();
		}

		@Override
		public ItemStack getItem(int slot) {
			return inner.getItem(slot);
		}

		@Override
		public ItemStack removeItem(int slot, int count) {
			return inner.removeItem(slot, count);
		}

		@Override
		public ItemStack removeItemNoUpdate(int slot) {
			return inner.removeItemNoUpdate(slot);
		}

		@Override
		public void setItem(int slot, ItemStack stack) {
			inner.setItem(slot, stack);
		}

		@Override
		public int getMaxStackSize() {
			return inner.getMaxStackSize();
		}

		@Override
		public int getMaxStackSize(ItemStack stack) {
			return inner.getMaxStackSize(stack);
		}

		@Override
		public void setChanged() {
			inner.setChanged();
		}

		@Override
		public boolean stillValid(Player player) {
			return valid.test(player);
		}

		@Override
		public void startOpen(ContainerUser user) {
			inner.startOpen(user);
		}

		@Override
		public void stopOpen(ContainerUser user) {
			inner.stopOpen(user);
		}

		@Override
		public void clearContent() {
			inner.clearContent();
		}
	}
}
