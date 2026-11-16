package justfatlard.minicraft;

import justfatlard.minicraft.mixin.ServerPlayerGameModeAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Breaking a miniature takes as long as breaking what it is mostly made of, on the server and on
 * the breaker's client alike.
 *
 * <p>The server knows the build; the client takes the miniature for a plain block of its own
 * hardness and times the break by that. Left alone, a miniature of obsidian would vanish on the
 * client long before the server let it go, and come back: a flicker. So while a player is
 * breaking one, their own break speed, which their client is sent, is set to the pace that brings
 * the plain block's time to the build's, and taken off again when they stop.
 */
public final class Breaking {
	private Breaking() {}

	private static final Identifier PACE = Identifier.fromNamespaceAndPath(Main.MOD_ID, "miniature_pace");
	private static final Set<UUID> paced = new HashSet<>();

	/** The pace set on this player now; one when none is. */
	public static float pace(Player player) {
		AttributeInstance speed = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
		AttributeModifier modifier = speed == null ? null : speed.getModifier(PACE);
		return modifier == null ? 1.0F : (float) (1.0 + modifier.amount());
	}

	/** Every tick: the pace kept right for everyone breaking a miniature, and taken off everyone who has stopped. */
	public static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			BlockPos at = breaking(player);
			if (at != null) {
				set(player, wanted(player, at));
				paced.add(player.getUUID());
			} else if (paced.remove(player.getUUID())) {
				set(player, 1.0F);
			}
		}
		paced.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
	}

	/** The miniature this player is breaking, or null. */
	private static BlockPos breaking(ServerPlayer player) {
		ServerPlayerGameModeAccessor mode = (ServerPlayerGameModeAccessor) player.gameMode;
		BlockPos at = mode.minicraft$destroying() ? mode.minicraft$destroyPos() : mode.minicraft$delayed() ? mode.minicraft$delayedPos() : null;
		return at != null && player.level().getBlockState(at).is(Main.MINIATURE) ? at : null;
	}

	/** The build's time over the plain block's, both measured without any pace. */
	private static float wanted(ServerPlayer player, BlockPos at) {
		BlockState state = player.level().getBlockState(at);
		// The build's progress already has the pace taken out; the plain block's is paced like any block's.
		float build = state.getDestroyProgress(player, player.level(), at);
		float plain = Main.MINIATURE.plainProgress(state, player, player.level(), at) / pace(player);
		return plain <= 0 || build <= 0 ? 1.0F : build / plain;
	}

	private static void set(ServerPlayer player, float factor) {
		AttributeInstance speed = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
		if (speed == null) return;
		AttributeModifier now = speed.getModifier(PACE);
		if (factor == 1.0F) {
			if (now != null) speed.removeModifier(PACE);
			return;
		}
		double amount = factor - 1.0;
		if (now != null && Math.abs(now.amount() - amount) < 1.0E-4) return;
		speed.removeModifier(PACE);
		speed.addTransientModifier(new AttributeModifier(PACE, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	}
}
