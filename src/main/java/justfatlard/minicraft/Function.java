package justfatlard.minicraft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * What a placed miniature does as the block in its functional slot: one to a miniature, made by
 * its kind in {@link Functions}. Most hold nothing (a crafting table opens its menu and is done);
 * the rest keep what is in them on the miniature, through being broken and placed again, the way
 * a shulker box keeps its contents.
 */
public interface Function {
	/** Right-clicked: open it, as its block opens. */
	void use(ServerPlayer player, ServerLevel level, BlockPos pos);

	/** Right-clicked holding something: whether it took it. Most take nothing, and the click goes on empty-handed. */
	default boolean useItem(ServerPlayer player, ServerLevel level, BlockPos pos, ItemStack stack) {
		return false;
	}

	/** Whether it works over time, and so is ticked. */
	default boolean ticks() {
		return false;
	}

	/** Once a tick on the server, for those that work over time. */
	default void tick(ServerLevel level, BlockPos pos, BlockState host) {}

	/** What is kept in it, as hoppers, comparators and the item see it; null if it keeps nothing. */
	default Container container() {
		return null;
	}

	/** Whether anything at all is in it. */
	default boolean holdsAnything() {
		Container container = container();
		return container != null && !container.isEmpty();
	}

	/** What a comparator reads from it. */
	default int analogSignal() {
		Container container = container();
		return container == null ? 0 : AbstractContainerMenu.getRedstoneSignalFromContainer(container);
	}

	/** Redstone it gives off on every side: a trapped chest's while it is open. */
	default int signal() {
		return 0;
	}

	default void save(ValueOutput output) {}

	default void load(ValueInput input) {}

	/** The miniature is in a world now. */
	default void setLevel(Level level) {}

	/** The miniature is gone from the world, broken or unloaded. */
	default void removed() {}
}
