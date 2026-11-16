package justfatlard.minicraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;

/**
 * A finished miniature: how it looks as built, and how it looks when something has happened to
 * it, each captured from the full-size build at the bench.
 *
 * <p>As built is the only look a plain miniature has. One that responds to redstone also has a
 * powered look; one with a handle has a look with the handle worked, and with both, a look for
 * both at once. Four at most, which keeps a capture a fixed amount of work. The handle is the
 * cell that was worked, kept so the miniature can go back on a bench and keep it. The function is
 * the block from the bench's functional slot that a placed miniature works as (a furnace, a
 * crafting table); see {@link Functions}. The maker is whoever finished it, by name, for the card.
 *
 * <p>A miniature made before the functional slot had its storage from a chest or barrel built
 * into it, kept as a slot count; read back, that is a chest or barrel function.
 */
public record Looks(Miniature off, Optional<Miniature> powered, Optional<Miniature> handled,
		Optional<Miniature> handledPowered, Optional<BlockPos> handle, Optional<Block> function, Optional<String> maker) {

	private record Extras(Optional<Miniature> powered, Optional<Miniature> handled, Optional<Miniature> handledPowered,
			Optional<BlockPos> handle, Optional<Block> function, int storage, Optional<String> maker) {
		static final Codec<Extras> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Miniature.CODEC.optionalFieldOf("powered").forGetter(Extras::powered),
			Miniature.CODEC.optionalFieldOf("handled").forGetter(Extras::handled),
			Miniature.CODEC.optionalFieldOf("handled_powered").forGetter(Extras::handledPowered),
			BlockPos.CODEC.optionalFieldOf("handle").forGetter(Extras::handle),
			BuiltInRegistries.BLOCK.byNameCodec().optionalFieldOf("function").forGetter(Extras::function),
			// Only ever read: how a miniature from before the functional slot said it had storage.
			Codec.INT.optionalFieldOf("storage", 0).forGetter(Extras::storage),
			Codec.STRING.optionalFieldOf("maker").forGetter(Extras::maker)
		).apply(instance, Extras::new));
	}

	public static Looks plain(Miniature off) {
		return new Looks(off, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
	}

	/** The same looks working as this block, or as nothing. */
	public Looks withFunction(Optional<Block> block) {
		return new Looks(off, powered, handled, handledPowered, handle, block, maker);
	}

	public boolean respondsToRedstone() {
		return powered.isPresent();
	}

	/**
	 * Whether it has a handle to work. A miniature has a handle or a function, never both; one made
	 * with both before that was settled keeps its function and what it holds, and its handle's
	 * looks stay in its data unused.
	 */
	public boolean hasHandle() {
		return handled.isPresent() && function.isEmpty();
	}

	public boolean hasFunction() {
		return function.isPresent();
	}

	/** Whether what it works as keeps things in it: a chest, a furnace, a jukebox. */
	public boolean keepsThings() {
		return function.map(Functions::keepsThings).orElse(false);
	}

	public boolean isSlab() {
		return off.isSlab();
	}

	/** The look for this moment: the nearest one captured, falling back to as built. */
	public Miniature look(boolean isPowered, boolean isHandled) {
		if (isHandled && isPowered && hasHandle() && handledPowered.isPresent()) return handledPowered.get();
		if (isHandled && hasHandle()) return handled.get();
		if (isPowered && powered.isPresent()) return powered.get();
		return off;
	}

	/** Which of the four this is, for keeping one shape per look. */
	public static int index(boolean isPowered, boolean isHandled) {
		return (isPowered ? 1 : 0) | (isHandled ? 2 : 0);
	}

	// --- On an item ---

	public static Optional<Looks> of(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? Optional.empty() : of(data);
	}

	public static Optional<Looks> of(CustomData data) {
		CompoundTag root = data.copyTag();
		Optional<Miniature> off = Miniature.of(data);
		if (off.isEmpty()) return Optional.empty();
		Optional<Extras> read = Extras.CODEC.parse(NbtOps.INSTANCE, root).result();
		if (read.isEmpty()) return Optional.of(plain(off.get()));
		Extras extras = read.get();
		Optional<Block> function = extras.function();
		if (function.isEmpty() && extras.storage() > 0) {
			function = Optional.of(off.get().palette().stream().anyMatch(state -> state.is(Blocks.BARREL))
				&& off.get().palette().stream().noneMatch(state -> state.is(Blocks.CHEST)) ? Blocks.BARREL : Blocks.CHEST);
		}
		return Optional.of(new Looks(off.get(), extras.powered(), extras.handled(), extras.handledPowered(), extras.handle(), function, extras.maker()));
	}

	public CustomData toData() {
		CompoundTag root = off.toData().copyTag();
		Tag extras = Extras.CODEC.encodeStart(NbtOps.INSTANCE, new Extras(powered, handled, handledPowered, handle, function, 0, maker)).getOrThrow();
		if (extras instanceof CompoundTag more) root.merge(more);
		return CustomData.of(root);
	}

	public ItemStack toItem() {
		ItemStack stack = new ItemStack(Main.MINIATURE_ITEM);
		stack.set(DataComponents.CUSTOM_DATA, toData());
		return stack;
	}
}
