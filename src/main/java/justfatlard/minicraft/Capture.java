package justfatlard.minicraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Finishing a miniature that does something: its looks captured from the full-size build, over a
 * few ticks, because a circuit takes ticks to settle.
 *
 * <p>As built first. Then, if it responds to redstone, the floor under the box becomes redstone
 * blocks, everything wired to it answers, and that is the powered look; the floor goes back. Then,
 * if it has a handle, the handle is worked as a player would work it, and that is the handled
 * look, powered as well if both; and the handle is worked back. A look holding more than a
 * miniature can stops the capture, leaving the build as it was.
 */
public final class Capture {
	private Capture() {}

	/** Ticks for a circuit to settle: a repeater at its longest, and a lamp going out. */
	static final int SETTLE = 6;

	private record Step(Consumer<Job> action, int settle) {}

	private static final class Job {
		final int plot;
		final int n;
		final ServerLevel benchLevel;
		final BlockPos bench;
		final UUID player;
		final String maker;
		final int height;
		final BlockPos handle;
		final Deque<Step> steps = new ArrayDeque<>();
		int wait;
		Miniature off, powered, handled, handledPowered;
		boolean failed;

		Job(int plot, int n, int height, ServerLevel benchLevel, BlockPos bench, UUID player, String maker, BlockPos handle) {
			this.maker = maker;
			this.plot = plot;
			this.n = n;
			this.height = height;
			this.benchLevel = benchLevel;
			this.bench = bench;
			this.player = player;
			this.handle = handle;
		}
	}

	private static final Map<Integer, Job> jobs = new HashMap<>();

	/** Begin capturing the plot's build; the miniature is handed over when it is done. */
	static void start(ServerPlayer player, ServerLevel level, BlockPos bench, int plot, Ledger.Project project, BlockPos handle) {
		Job job = new Job(plot, project.n(), project.height(), level, bench.immutable(), player.getUUID(), player.getName().getString(), handle);
		// The box's chunk kept ticking while it is tried out, or nothing scheduled in it (a
		// repeater, a button, a piston) would ever happen with nobody standing there. A box fits in
		// one chunk: plots start on chunk corners, and no box is more than sixteen across.
		forceBox(job, true);
		job.steps.add(new Step(j -> {}, SETTLE * 2));
		job.steps.add(new Step(j -> j.off = take(j), 0));
		if (project.redstone()) {
			job.steps.add(new Step(j -> power(j, true), SETTLE));
			job.steps.add(new Step(j -> j.powered = take(j), 0));
			job.steps.add(new Step(j -> power(j, false), SETTLE));
		}
		if (handle != null) {
			job.steps.add(new Step(Capture::work, SETTLE));
			job.steps.add(new Step(j -> j.handled = take(j), 0));
			if (project.redstone()) {
				job.steps.add(new Step(j -> power(j, true), SETTLE));
				job.steps.add(new Step(j -> j.handledPowered = take(j), 0));
				job.steps.add(new Step(j -> power(j, false), SETTLE));
			}
			job.steps.add(new Step(Capture::work, SETTLE));
		}
		job.steps.add(new Step(Capture::hand, 0));
		Ledger.get(level.getServer()).setCapturing(plot, true);
		jobs.put(plot, job);
	}

	/** Every tick: each running capture takes its next step once its circuit has had time to settle. */
	public static void tick(MinecraftServer server) {
		if (jobs.isEmpty()) return;
		for (Iterator<Job> it = jobs.values().iterator(); it.hasNext(); ) {
			Job job = it.next();
			if (job.wait > 0) {
				job.wait--;
				continue;
			}
			while (job.wait == 0 && !job.steps.isEmpty() && !job.failed) {
				Step step = job.steps.poll();
				step.action().accept(job);
				job.wait = step.settle();
			}
			if (job.failed || job.steps.isEmpty() && job.wait == 0) {
				if (job.failed) power(job, false);
				forceBox(job, false);
				Ledger.get(server).setCapturing(job.plot, false);
				it.remove();
			}
		}
	}

	private static void forceBox(Job job, boolean on) {
		BlockPos min = Workshop.origin(job.plot);
		workshop(job).setChunkForced(min.getX() >> 4, min.getZ() >> 4, on);
	}

	private static ServerLevel workshop(Job job) {
		return Workshop.level(job.benchLevel.getServer());
	}

	private static Miniature take(Job job) {
		Optional<Miniature> look = Miniature.capture(workshop(job), Workshop.origin(job.plot), job.n, job.height);
		if (look.isEmpty()) {
			job.failed = true;
			tell(job, "minicraft-justfatlard.finish.too_many", "Too many different blocks to fit in one miniature.");
			return null;
		}
		return look.get();
	}

	/** The floor under the box as redstone blocks, or back to plain floor. */
	private static void power(Job job, boolean on) {
		ServerLevel level = workshop(job);
		BlockPos min = Workshop.origin(job.plot);
		BlockState floor = on ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Workshop.FOOTPRINT;
		for (int x = 0; x < job.n; x++) for (int z = 0; z < job.n; z++) level.setBlock(min.offset(x, -1, z), floor, Block.UPDATE_ALL);
	}

	/** Work the handle as a player would: a lever thrown, a button pressed, a door opened. */
	private static void work(Job job) {
		ServerLevel level = workshop(job);
		BlockPos at = Workshop.origin(job.plot).offset(job.handle);
		BlockState state = level.getBlockState(at);
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(job.player);
		if (state.isAir() || player == null) return;
		state.useWithoutItem(level, player, new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
	}

	private static void hand(Job job) {
		ServerLevel level = workshop(job);
		Looks looks = new Looks(job.off, Optional.ofNullable(job.powered), Optional.ofNullable(job.handled),
			Optional.ofNullable(job.handledPowered), Optional.ofNullable(job.handle), Optional.empty(), Optional.of(job.maker));
		Workshop.clear(level, job.plot, job.n);
		Bench.handOver(job.benchLevel, job.bench, job.plot, job.player, looks);
	}

	private static void tell(Job job, String key, String fallback) {
		ServerPlayer player = job.benchLevel.getServer().getPlayerList().getPlayer(job.player);
		if (player != null) player.sendOverlayMessage(Component.translatableWithFallback(key, fallback));
	}

	static boolean running(int plot) {
		return jobs.containsKey(plot);
	}

	static void playDone(ServerLevel level, BlockPos bench) {
		level.playSound(null, bench, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 1.0F, 1.0F);
	}
}
