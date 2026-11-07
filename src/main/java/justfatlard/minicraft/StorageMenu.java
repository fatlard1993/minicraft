package justfatlard.minicraft;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A miniature's storage, opened: laid out exactly as a chest's, so every client draws it as one,
 * but its slots will not take a shulker box or another miniature with storage, as a shulker box's
 * slots will not take a shulker box.
 */
public class StorageMenu extends AbstractContainerMenu {
	private static final int ROWS = 3;
	private final Container storage;

	public StorageMenu(int id, Inventory inventory, Container storage) {
		super(MenuType.GENERIC_9x3, id);
		this.storage = storage;
		storage.startOpen(inventory.player);
		for (int row = 0; row < ROWS; row++) for (int column = 0; column < 9; column++) {
			addSlot(new Slot(storage, column + row * 9, 8 + column * 18, 18 + row * 18) {
				@Override
				public boolean mayPlace(ItemStack stack) {
					return fits(stack);
				}
			});
		}
		addStandardInventorySlots(inventory, 8, 18 + ROWS * 18 + 13);
	}

	/** What a miniature's storage will hold: anything but a container that travels with its contents. */
	public static boolean fits(ItemStack stack) {
		if (stack.is(ItemTags.SHULKER_BOXES)) return false;
		return !stack.is(Main.MINIATURE_ITEM) || Looks.of(stack).map(looks -> !looks.hasStorage()).orElse(true);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);
		if (!slot.hasItem()) return ItemStack.EMPTY;
		ItemStack stack = slot.getItem();
		ItemStack before = stack.copy();
		int size = ROWS * 9;
		if (index < size) {
			if (!moveItemStackTo(stack, size, slots.size(), true)) return ItemStack.EMPTY;
		} else if (!fits(stack) || !moveItemStackTo(stack, 0, size, false)) {
			return ItemStack.EMPTY;
		}
		if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
		else slot.setChanged();
		return before;
	}

	@Override
	public boolean stillValid(Player player) {
		return storage.stillValid(player);
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		storage.stopOpen(player);
	}
}
