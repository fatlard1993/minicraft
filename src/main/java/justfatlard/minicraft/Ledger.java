package justfatlard.minicraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.TreeSet;

/**
 * Every bench's project: its plot, how finely it cuts, how much of each material it holds, and
 * who is building in its box.
 *
 * <p>Kept here, on the server, rather than in the bench, because a builder spends material in the
 * workshop while the bench sits in a chunk that may well unload behind them. The bench only
 * remembers which plot is its own.
 *
 * <p>Material is counted in cells. A block loaded is N³ of them, a block placed in the box one,
 * a block taken out whole again N³; so no amount of loading, building and withdrawing comes out
 * ahead.
 */
public final class Ledger extends SavedData {
	private static final String STORAGE_KEY = "minicraft_ledger";

	/** One bench's project. N is zero until a size is chosen. */
	public static final class Project {
		int n;
		final LinkedHashMap<Identifier, Long> budget = new LinkedHashMap<>();
		UUID occupant;
		/** The behaviours chosen on the bench; see {@link Capture}. */
		boolean redstone;
		BlockPos handle;
		/** Being captured right now: nobody steps in or changes anything until it is done. Not saved. */
		boolean capturing;

		public boolean redstone() {
			return redstone;
		}

		/** The handle's cell in the box, or null. */
		public BlockPos handle() {
			return handle;
		}

		public boolean capturing() {
			return capturing;
		}

		/** Busy to anyone else: somebody building, or a capture running. */
		public boolean busy() {
			return occupant != null || capturing;
		}

		public int n() {
			return n;
		}

		public long cellsPerBlock() {
			return (long) n * n * n;
		}

		public long cells(Item item) {
			return budget.getOrDefault(BuiltInRegistries.ITEM.getKey(item), 0L);
		}

		/** The materials held, in the order they were first loaded. */
		public List<Item> materials() {
			List<Item> items = new ArrayList<>();
			for (Map.Entry<Identifier, Long> entry : budget.entrySet()) {
				if (entry.getValue() > 0) BuiltInRegistries.ITEM.getOptional(entry.getKey()).ifPresent(items::add);
			}
			return items;
		}

		public boolean hasMaterial() {
			for (long cells : budget.values()) if (cells > 0) return true;
			return false;
		}

		/** Whether nothing is held but crumbs: less than a block of anything. */
		public boolean onlyCrumbs() {
			for (long cells : budget.values()) if (cells >= cellsPerBlock()) return false;
			return true;
		}

		public UUID occupant() {
			return occupant;
		}
	}

	private record Entry(int plot, int n, Map<String, Long> budget, Optional<UUID> occupant, boolean redstone, Optional<BlockPos> handle) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("plot").forGetter(Entry::plot),
			Codec.INT.fieldOf("n").forGetter(Entry::n),
			Codec.unboundedMap(Codec.STRING, Codec.LONG).fieldOf("budget").forGetter(Entry::budget),
			UUIDUtil.CODEC.optionalFieldOf("occupant").forGetter(Entry::occupant),
			Codec.BOOL.optionalFieldOf("redstone", false).forGetter(Entry::redstone),
			BlockPos.CODEC.optionalFieldOf("handle").forGetter(Entry::handle)
		).apply(instance, Entry::new));
	}

	private record Stored(List<Entry> projects, int nextPlot, List<Integer> free) {
		static final Codec<Stored> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Entry.CODEC.listOf().fieldOf("projects").forGetter(Stored::projects),
			Codec.INT.fieldOf("next_plot").forGetter(Stored::nextPlot),
			Codec.INT.listOf().fieldOf("free").forGetter(Stored::free)
		).apply(instance, Stored::new));
	}

	public static final Codec<Ledger> CODEC = Stored.CODEC.xmap(Ledger::fromStored, Ledger::toStored);

	private static final SavedDataType<Ledger> TYPE = new SavedDataType<>(
		Identifier.parse(STORAGE_KEY), Ledger::new, CODEC, DataFixTypes.LEVEL);

	private final Map<Integer, Project> projects = new HashMap<>();
	/** Plots given back by broken benches, handed out again before new ones, lowest first. */
	private final TreeSet<Integer> free = new TreeSet<>();
	private int nextPlot = 0;

	public static Ledger get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	private static Ledger fromStored(Stored stored) {
		Ledger ledger = new Ledger();
		for (Entry entry : stored.projects()) {
			Project project = new Project();
			project.n = entry.n();
			entry.budget().forEach((id, cells) -> project.budget.put(Identifier.parse(id), cells));
			project.occupant = entry.occupant().orElse(null);
			project.redstone = entry.redstone();
			project.handle = entry.handle().orElse(null);
			ledger.projects.put(entry.plot(), project);
		}
		ledger.nextPlot = stored.nextPlot();
		ledger.free.addAll(stored.free());
		return ledger;
	}

	private Stored toStored() {
		List<Entry> entries = new ArrayList<>();
		projects.forEach((plot, project) -> {
			Map<String, Long> budget = new LinkedHashMap<>();
			project.budget.forEach((id, cells) -> { if (cells > 0) budget.put(id.toString(), cells); });
			entries.add(new Entry(plot, project.n, budget, Optional.ofNullable(project.occupant), project.redstone, Optional.ofNullable(project.handle)));
		});
		return new Stored(entries, nextPlot, new ArrayList<>(free));
	}

	/** A new project on a plot nobody else has. */
	public int open() {
		Integer reused = free.pollFirst();
		int plot = reused != null ? reused : nextPlot++;
		projects.put(plot, new Project());
		setDirty();
		return plot;
	}

	public Project project(int plot) {
		return projects.get(plot);
	}

	/** Give a plot back: its project is over and its box emptied. */
	public void close(int plot) {
		if (projects.remove(plot) != null) {
			free.add(plot);
			setDirty();
		}
	}

	/** A new size starts a new project: the behaviours chosen for the old one go with it. */
	public void setSize(int plot, int n) {
		Project project = projects.get(plot);
		project.n = n;
		if (n == 0) {
			project.redstone = false;
			project.handle = null;
		}
		setDirty();
	}

	public void setRedstone(int plot, boolean redstone) {
		Project project = projects.get(plot);
		if (project == null) return;
		project.redstone = redstone;
		setDirty();
	}

	/** @param handle the cell in the box, or null for none */
	public void setHandle(int plot, BlockPos handle) {
		Project project = projects.get(plot);
		if (project == null) return;
		project.handle = handle == null ? null : handle.immutable();
		setDirty();
	}

	public void setCapturing(int plot, boolean capturing) {
		Project project = projects.get(plot);
		if (project != null) project.capturing = capturing;
	}

	/** Load whole blocks of a material: N³ cells each. */
	public void load(int plot, BlockItem item, int blocks) {
		Project project = projects.get(plot);
		project.budget.merge(BuiltInRegistries.ITEM.getKey(item), blocks * project.cellsPerBlock(), Long::sum);
		setDirty();
	}

	/** Add or take cells; never below nothing. */
	public void change(int plot, Item item, long cells) {
		Project project = projects.get(plot);
		if (project == null) return;
		Identifier id = BuiltInRegistries.ITEM.getKey(item);
		long now = Math.max(0, project.budget.getOrDefault(id, 0L) + cells);
		if (now == 0) project.budget.remove(id);
		else project.budget.put(id, now);
		setDirty();
	}

	/** Take out every whole block left, and leave the crumbs. What was taken, by material. */
	public Map<Item, Long> withdraw(int plot) {
		Project project = projects.get(plot);
		Map<Item, Long> taken = new LinkedHashMap<>();
		for (Item item : project.materials()) {
			long blocks = project.cells(item) / project.cellsPerBlock();
			if (blocks <= 0) continue;
			change(plot, item, -blocks * project.cellsPerBlock());
			taken.put(item, blocks);
		}
		return taken;
	}

	/** Throw away the crumbs, so the size can be chosen again. */
	public void sweep(int plot) {
		Project project = projects.get(plot);
		project.budget.clear();
		project.n = 0;
		project.redstone = false;
		project.handle = null;
		setDirty();
	}

	public void occupy(int plot, UUID player) {
		Project project = projects.get(plot);
		if (project == null) return;
		project.occupant = player;
		setDirty();
	}
}
