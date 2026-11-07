package justfatlard.minicraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;

/**
 * A finished miniature: how it looks as built, and how it looks when something has happened to
 * it, each captured from the full-size build at the bench.
 *
 * <p>As built is the only look a plain miniature has. One that responds to redstone also has a
 * powered look; one with a handle has a look with the handle worked, and with both, a look for
 * both at once. Four at most, which keeps a capture a fixed amount of work. The handle is the
 * cell that was worked, kept so the miniature can go back on a bench and keep it. Storage is the
 * number of slots the one chest or barrel in the build gives it; zero for none. The maker is
 * whoever finished it, by name, for the card.
 */
public record Looks(Miniature off, Optional<Miniature> powered, Optional<Miniature> handled,
		Optional<Miniature> handledPowered, Optional<BlockPos> handle, int storage, Optional<String> maker) {

	static final Codec<Looks> EXTRAS = RecordCodecBuilder.create(instance -> instance.group(
		Miniature.CODEC.optionalFieldOf("powered").forGetter(Looks::powered),
		Miniature.CODEC.optionalFieldOf("handled").forGetter(Looks::handled),
		Miniature.CODEC.optionalFieldOf("handled_powered").forGetter(Looks::handledPowered),
		BlockPos.CODEC.optionalFieldOf("handle").forGetter(Looks::handle),
		Codec.INT.optionalFieldOf("storage", 0).forGetter(Looks::storage),
		Codec.STRING.optionalFieldOf("maker").forGetter(Looks::maker)
	).apply(instance, (powered, handled, handledPowered, handle, storage, maker) ->
		new Looks(null, powered, handled, handledPowered, handle, storage, maker)));

	public static Looks plain(Miniature off) {
		return new Looks(off, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, Optional.empty());
	}

	public boolean respondsToRedstone() {
		return powered.isPresent();
	}

	public boolean hasHandle() {
		return handled.isPresent();
	}

	public boolean hasStorage() {
		return storage > 0;
	}

	/** The look for this moment: the nearest one captured, falling back to as built. */
	public Miniature look(boolean isPowered, boolean isHandled) {
		if (isHandled && isPowered && handledPowered.isPresent()) return handledPowered.get();
		if (isHandled && handled.isPresent()) return handled.get();
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
		Looks extras = EXTRAS.parse(NbtOps.INSTANCE, root).result().orElse(plain(off.get()));
		return Optional.of(new Looks(off.get(), extras.powered(), extras.handled(), extras.handledPowered(), extras.handle(), extras.storage(), extras.maker()));
	}

	public CustomData toData() {
		CompoundTag root = off.toData().copyTag();
		Tag extras = EXTRAS.encodeStart(NbtOps.INSTANCE, this).getOrThrow();
		if (extras instanceof CompoundTag more) root.merge(more);
		return CustomData.of(root);
	}

	public ItemStack toItem() {
		ItemStack stack = new ItemStack(Main.MINIATURE_ITEM);
		stack.set(DataComponents.CUSTOM_DATA, toData());
		return stack;
	}
}
