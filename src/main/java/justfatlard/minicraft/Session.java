package justfatlard.minicraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * A builder's time in the workshop, kept on the player: which plot, where they came from, and
 * what they were carrying.
 *
 * <p>On the player and not in the ledger, as pvp-dimensions keeps a visit: the player's own save
 * holds the stash and the inventory side by side, written together, so a crash can lose neither
 * and double neither. It survives a death too, and is how the pack comes back after one.
 */
public record Session(int plot, Home home, List<Slot> stash, String gameMode, boolean mayFly) {

	/** Where to put somebody back: exactly where they stood, not where the bench is. */
	public record Home(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch) {
		static final Codec<Home> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Home::dimension),
			Codec.DOUBLE.fieldOf("x").forGetter(Home::x),
			Codec.DOUBLE.fieldOf("y").forGetter(Home::y),
			Codec.DOUBLE.fieldOf("z").forGetter(Home::z),
			Codec.FLOAT.fieldOf("yaw").forGetter(Home::yaw),
			Codec.FLOAT.fieldOf("pitch").forGetter(Home::pitch)
		).apply(instance, Home::new));

		static Home of(ServerPlayer player) {
			return new Home(player.level().dimension(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
		}
	}

	public record Slot(int slot, ItemStack stack) {
		static final Codec<Slot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("slot").forGetter(Slot::slot),
			ItemStack.CODEC.fieldOf("item").forGetter(Slot::stack)
		).apply(instance, Slot::new));
	}

	public static final Codec<Session> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.INT.fieldOf("plot").forGetter(Session::plot),
		Home.CODEC.fieldOf("home").forGetter(Session::home),
		Slot.CODEC.listOf().fieldOf("stash").forGetter(Session::stash),
		Codec.STRING.fieldOf("game_mode").forGetter(Session::gameMode),
		Codec.BOOL.fieldOf("may_fly").forGetter(Session::mayFly)
	).apply(instance, Session::new));

	public static final AttachmentType<Session> ATTACHMENT = AttachmentRegistry.<Session>builder()
		.persistent(CODEC)
		.copyOnDeath()
		.buildAndRegister(Identifier.fromNamespaceAndPath(Main.MOD_ID, "session"));

	/**
	 * How many times a builder has been told about the handle on stepping in; set past the limit
	 * once they have marked one, as then they know. Kept on the player, outside any visit.
	 */
	public static final AttachmentType<Integer> HANDLE_HINTS = AttachmentRegistry.<Integer>builder()
		.persistent(Codec.INT)
		.copyOnDeath()
		.buildAndRegister(Identifier.fromNamespaceAndPath(Main.MOD_ID, "handle_hints"));

	/** Loads the class, which registers the attachments; it must happen at init. */
	static void init() {}

	public static Session of(ServerPlayer player) {
		return player.getAttached(ATTACHMENT);
	}

	static void set(ServerPlayer player, Session session) {
		if (session == null) player.removeAttached(ATTACHMENT);
		else player.setAttached(ATTACHMENT, session);
	}

	static List<Slot> stashOf(ServerPlayer player) {
		List<Slot> stash = new ArrayList<>();
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty()) stash.add(new Slot(slot, stack.copy()));
		}
		return stash;
	}
}
