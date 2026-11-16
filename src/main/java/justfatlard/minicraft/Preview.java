package justfatlard.minicraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where a miniature would go at full size, drawn for the one player who asked, so they can clear
 * the ground first: the box it fills in white motes along its twelve edges, as the workshop box's
 * edges are drawn; its footprint, the outline of the columns it stands on, in green at its base;
 * and every spot something is in the way at in red, looked at afresh each time it is drawn.
 *
 * <p>Particles sent to that player alone, twice a second. It stops when they toggle it off, walk
 * more than {@link #RANGE} blocks away or change dimension, when the miniature is broken or built,
 * or after {@link #LASTS} ticks.
 */
public final class Preview {
	private Preview() {}

	/** Two minutes. */
	static final int LASTS = 20 * 120;
	static final double RANGE = 32.0;
	private static final int EVERY = 10;
	/** Past this many spots in the way, only this many are marked each time; the dialog has the count. */
	private static final int MOST_MARKED = 512;

	private static final ParticleOptions EDGE = new DustParticleOptions(0xFFFFFF, 0.8F);
	private static final int EDGE_STEPS = 4;
	private static final ParticleOptions FOOTPRINT = new DustParticleOptions(0x33EE55, 0.9F);
	private static final ParticleOptions BLOCKED = new DustParticleOptions(0xFF2020, 1.6F);

	private record Shown(ServerLevel level, BlockPos at, MiniatureBlockEntity miniature, int until) {}

	private static final Map<UUID, Shown> shown = new HashMap<>();

	/** Whether this player has the preview of the miniature at this spot up. */
	public static boolean showing(ServerPlayer player, BlockPos at) {
		Shown preview = shown.get(player.getUUID());
		return preview != null && preview.at.equals(at) && preview.level == player.level();
	}

	/** On if it was off, off if it was on; on for this miniature in place of any other. Whether it is on now. */
	public static boolean toggle(ServerPlayer player, ServerLevel level, BlockPos at) {
		if (showing(player, at)) {
			stop(player, false);
			return false;
		}
		if (!(level.getBlockEntity(at) instanceof MiniatureBlockEntity miniature) || miniature.looks().isEmpty()) return false;
		shown.put(player.getUUID(), new Shown(level, at.immutable(), miniature, level.getServer().getTickCount() + LASTS));
		draw(player, shown.get(player.getUUID()));
		return true;
	}

	/** Take it down, saying so if it ended by itself. */
	public static void stop(ServerPlayer player, boolean tell) {
		if (shown.remove(player.getUUID()) != null && tell) {
			player.sendOverlayMessage(Component.translatableWithFallback("minicraft-justfatlard.construct.preview_ended", "The preview has ended."));
		}
	}

	public static void forget(UUID player) {
		shown.remove(player);
	}

	public static void tick(MinecraftServer server) {
		if (shown.isEmpty() || server.getTickCount() % EVERY != 0) return;
		for (Map.Entry<UUID, Shown> entry : List.copyOf(shown.entrySet())) {
			Shown preview = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				shown.remove(entry.getKey());
				continue;
			}
			boolean over = player.level() != preview.level
				|| player.position().distanceTo(Vec3.atCenterOf(preview.at)) > RANGE
				|| preview.level.getBlockEntity(preview.at) != preview.miniature || preview.miniature.isRemoved()
				|| server.getTickCount() >= preview.until;
			if (over) stop(player, true);
			else draw(player, preview);
		}
	}

	private static void draw(ServerPlayer player, Shown preview) {
		ServerLevel level = preview.level;
		Constructing.Survey survey = Constructing.survey(player, level, preview.at);
		if (survey == null) return;
		// As it will stand: turned, mirrored and flipped as the dialog has it.
		Miniature build = survey.build();
		int n = build.n();
		int height = build.height();
		BlockPos corner = Constructing.corner(preview.at, n);
		double x0 = corner.getX(), y0 = corner.getY(), z0 = corner.getZ();

		// The box: its twelve edges, four motes a block, so each reads as a line.
		for (int q = 0; q <= n * EDGE_STEPS; q++) {
			double i = q / (double) EDGE_STEPS;
			for (int a = 0; a <= 1; a++) for (int b = 0; b <= 1; b++) {
				mote(level, player, EDGE, x0 + i, y0 + a * height, z0 + b * n);
				if (i <= height) mote(level, player, EDGE, x0 + a * n, y0 + i, z0 + b * n);
				mote(level, player, EDGE, x0 + a * n, y0 + b * height, z0 + i);
			}
		}

		// The footprint: every column anything stands in, outlined where it meets one nothing does.
		boolean[] column = new boolean[n * n];
		for (int y = 0; y < height; y++) for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
			if (!build.at(x, y, z).isAir()) column[z * n + x] = true;
		}
		double base = y0 + 0.08;
		for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
			if (!column[z * n + x]) continue;
			if (z == 0 || !column[(z - 1) * n + x]) side(level, player, x0 + x, base, z0 + z, 1, 0);
			if (z == n - 1 || !column[(z + 1) * n + x]) side(level, player, x0 + x, base, z0 + z + 1, 1, 0);
			if (x == 0 || !column[z * n + x - 1]) side(level, player, x0 + x, base, z0 + z, 0, 1);
			if (x == n - 1 || !column[z * n + x + 1]) side(level, player, x0 + x + 1, base, z0 + z, 0, 1);
		}

		// What is in the way, looked at again now.
		List<BlockPos> blocked = survey.blocked();
		for (int i = 0; i < Math.min(blocked.size(), MOST_MARKED); i++) {
			BlockPos spot = blocked.get(i);
			level.sendParticles(player, BLOCKED, true, true, spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5, 3, 0.22, 0.22, 0.22, 0);
		}
	}

	/** One block's length of footprint edge, from this point along x or z: five motes. */
	private static void side(ServerLevel level, ServerPlayer player, double x, double y, double z, int dx, int dz) {
		for (int i = 0; i <= 4; i++) mote(level, player, FOOTPRINT, x + dx * i / 4.0, y, z + dz * i / 4.0);
	}

	/**
	 * Sent as a count of none, which a client reads as one particle at exactly this point with
	 * exactly this (no) motion; a count of one lets the client scatter it.
	 */
	private static void mote(ServerLevel level, ServerPlayer player, ParticleOptions particle, double x, double y, double z) {
		level.sendParticles(player, particle, true, true, x, y, z, 0, 0, 0, 0, 0);
	}
}
