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
import net.minecraft.world.item.ItemStack;
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
 * who is building in its box: any number of builders at once, all spending the one budget.
 *
 * <p>Kept here, on the server, rather than in the bench, because a builder spends material in the
 * workshop while the bench sits in a chunk that may well unload behind them. The bench only
 * remembers which plot is its own.
 *
 * <p>Material is counted in cells. A block loaded is N³ of them, a block placed in the box one,
 * a block taken out whole again N³; so no amount of loading, building and withdrawing comes out
 * ahead.
 *
 * <p>The size can change with material loaded, and everything held is cut again to the new size
 * without losing any: what the new size cannot cut, less than one of its cells, is kept as a
 * sliver, an exact fraction of a block, never spent or taken out, and folded back in by a later
 * size that can cut it. So 4 to 5 and back to 4 gives back the very cells there were.
 */
public final class Ledger extends SavedData {
	private static final String STORAGE_KEY = "minicraft_ledger";

	/** One bench's project. N is zero until a size is chosen; a slab is chosen with it. */
	public static final class Project {
		int n;
		boolean slab;
		/** The block in the functional slot, which the miniature will work as; empty for none. */
		ItemStack function = ItemStack.EMPTY;
		/** The name the miniature will be given; empty for none. */
		String name = "";
		final LinkedHashMap<Identifier, Long> budget = new LinkedHashMap<>();
		/** Per material, the part of a block too fine for this size, as an exact fraction of a block; see {@link #resize}. */
		final LinkedHashMap<Identifier, Sliver> slivers = new LinkedHashMap<>();
		/** Everyone in the box now, in the order they stepped in, with the name each had then. */
		final LinkedHashMap<UUID, String> occupants = new LinkedHashMap<>();
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

		/** Busy: somebody building, or a capture running. Finishing and the like wait for it; stepping in waits only for a capture. */
		public boolean busy() {
			return !occupants.isEmpty() || capturing;
		}

		public int n() {
			return n;
		}

		/** Whether it makes a slab, half a block high, rather than a block. */
		public boolean slab() {
			return slab;
		}

		/** How many cells high the box and the miniature are: N, or half of it for a slab. */
		public int height() {
			return slab ? Miniature.slabHeight(n) : n;
		}

		public ItemStack function() {
			return function;
		}

		public String name() {
			return name;
		}

		public long cellsPerBlock() {
			return (long) n * n * n;
		}

		public long cells(Item item) {
			return budget.getOrDefault(BuiltInRegistries.ITEM.getKey(item), 0L);
		}

		/** The part of a block of this material kept aside as too fine for this size to cut; zero for none. */
		public Sliver sliver(Item item) {
			return slivers.getOrDefault(BuiltInRegistries.ITEM.getKey(item), Sliver.NONE);
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

		/** Who is in the box, as they were named when they stepped in, in the order they did. */
		public Map<UUID, String> occupants() {
			return java.util.Collections.unmodifiableMap(occupants);
		}

		public boolean inside(UUID player) {
			return occupants.containsKey(player);
		}
	}

	/** A fraction of a block, exact: what a size could not cut. Always less than one of that size's cells, never negative. */
	public record Sliver(java.math.BigInteger num, java.math.BigInteger den) {
		public static final Sliver NONE = new Sliver(java.math.BigInteger.ZERO, java.math.BigInteger.ONE);

		static Sliver of(java.math.BigInteger num, java.math.BigInteger den) {
			if (num.signum() == 0) return NONE;
			java.math.BigInteger gcd = num.gcd(den);
			return new Sliver(num.divide(gcd), den.divide(gcd));
		}

		public boolean isNone() {
			return num.signum() == 0;
		}

		static final Codec<Sliver> CODEC = Codec.STRING.comapFlatMap(text -> {
			try {
				int slash = text.indexOf('/');
				java.math.BigInteger num = new java.math.BigInteger(text.substring(0, slash));
				java.math.BigInteger den = new java.math.BigInteger(text.substring(slash + 1));
				if (num.signum() < 0 || den.signum() <= 0 || num.compareTo(den) >= 0) return com.mojang.serialization.DataResult.error(() -> "not a sliver: " + text);
				return com.mojang.serialization.DataResult.success(of(num, den));
			} catch (RuntimeException e) {
				return com.mojang.serialization.DataResult.error(() -> "not a sliver: " + text);
			}
		}, sliver -> sliver.num() + "/" + sliver.den());
	}

	/** One builder in the box, kept by name too, so the bench can say who even while they are away. */
	private record Occupant(UUID id, String name) {
		static final Codec<Occupant> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(Occupant::id),
			Codec.STRING.optionalFieldOf("name", "").forGetter(Occupant::name)
		).apply(instance, Occupant::new));
	}

	/**
	 * @param occupant the one builder a project had before any number could build at once; read,
	 *     never written, so a world saved then still knows who is inside
	 */
	private record Entry(int plot, int n, Map<String, Long> budget, Optional<UUID> occupant, boolean redstone, Optional<BlockPos> handle,
			boolean slab, ItemStack function, String name, List<Occupant> occupants, Map<String, Sliver> slivers) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("plot").forGetter(Entry::plot),
			Codec.INT.fieldOf("n").forGetter(Entry::n),
			Codec.unboundedMap(Codec.STRING, Codec.LONG).fieldOf("budget").forGetter(Entry::budget),
			UUIDUtil.CODEC.optionalFieldOf("occupant").forGetter(Entry::occupant),
			Codec.BOOL.optionalFieldOf("redstone", false).forGetter(Entry::redstone),
			BlockPos.CODEC.optionalFieldOf("handle").forGetter(Entry::handle),
			Codec.BOOL.optionalFieldOf("slab", false).forGetter(Entry::slab),
			ItemStack.OPTIONAL_CODEC.optionalFieldOf("function", ItemStack.EMPTY).forGetter(Entry::function),
			Codec.STRING.optionalFieldOf("name", "").forGetter(Entry::name),
			Occupant.CODEC.listOf().optionalFieldOf("occupants", List.of()).forGetter(Entry::occupants),
			Codec.unboundedMap(Codec.STRING, Sliver.CODEC).optionalFieldOf("slivers", Map.of()).forGetter(Entry::slivers)
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
			entry.occupant().ifPresent(id -> project.occupants.put(id, ""));
			for (Occupant occupant : entry.occupants()) project.occupants.put(occupant.id(), occupant.name());
			entry.slivers().forEach((id, sliver) -> { if (!sliver.isNone()) project.slivers.put(Identifier.parse(id), sliver); });
			project.redstone = entry.redstone();
			project.handle = entry.handle().orElse(null);
			project.slab = entry.slab();
			project.function = entry.function();
			project.name = entry.name();
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
			List<Occupant> occupants = new ArrayList<>();
			project.occupants.forEach((id, name) -> occupants.add(new Occupant(id, name)));
			Map<String, Sliver> slivers = new LinkedHashMap<>();
			project.slivers.forEach((id, sliver) -> slivers.put(id.toString(), sliver));
			entries.add(new Entry(plot, project.n, budget, Optional.empty(), project.redstone, Optional.ofNullable(project.handle),
				project.slab, project.function, project.name, occupants, slivers));
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

	/**
	 * A new size starts a new project: the behaviours chosen for the old one go with it. Choosing a
	 * size for a bench with none folds in any slivers kept from before.
	 */
	public void setSize(int plot, int n) {
		Project project = projects.get(plot);
		if (project.n == 0 && n > 0 && !project.slivers.isEmpty()) {
			project.n = n;
			resize(plot, n);
			return;
		}
		project.n = n;
		if (n == 0) {
			project.redstone = false;
			project.handle = null;
			project.slab = false;
			project.name = "";
		}
		setDirty();
	}

	/** Block or slab: chosen with the size, before it is set. */
	public void setSlab(int plot, boolean slab) {
		Project project = projects.get(plot);
		if (project == null) return;
		project.slab = slab;
		setDirty();
	}

	/** What goes in the functional slot; empty for nothing. */
	public void setFunction(int plot, ItemStack stack) {
		Project project = projects.get(plot);
		if (project == null) return;
		project.function = stack;
		setDirty();
	}

	public void setName(int plot, String name) {
		Project project = projects.get(plot);
		if (project == null) return;
		project.name = name;
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

	/**
	 * Cut everything held again to a new size, losing nothing. For each material the exact amount
	 * held, in blocks, is its cells over the old N³ plus its sliver; the new cells are that times the
	 * new N³, rounded down, and what rounding down left, less than one new cell, is the new sliver.
	 * Whole blocks always come through exactly. Nothing else about the project changes here.
	 *
	 * @return the materials that now have a sliver kept aside, which a later size may fold back in
	 */
	public List<Item> resize(int plot, int newN) {
		Project project = projects.get(plot);
		List<Item> slivered = new ArrayList<>();
		if (project == null || newN <= 0) return slivered;
		java.math.BigInteger oldCube = java.math.BigInteger.valueOf((long) project.n * project.n * project.n);
		java.math.BigInteger newCube = java.math.BigInteger.valueOf((long) newN * newN * newN);
		java.util.LinkedHashSet<Identifier> ids = new java.util.LinkedHashSet<>(project.budget.keySet());
		ids.addAll(project.slivers.keySet());
		LinkedHashMap<Identifier, Long> budget = new LinkedHashMap<>();
		LinkedHashMap<Identifier, Sliver> slivers = new LinkedHashMap<>();
		for (Identifier id : ids) {
			Sliver sliver = project.slivers.getOrDefault(id, Sliver.NONE);
			long cells = project.n == 0 ? 0 : project.budget.getOrDefault(id, 0L);
			// held = cells / oldCube + sliver.num / sliver.den, as num / den
			java.math.BigInteger num, den;
			if (project.n == 0) {
				num = sliver.num();
				den = sliver.den();
			} else {
				num = java.math.BigInteger.valueOf(cells).multiply(sliver.den()).add(sliver.num().multiply(oldCube));
				den = oldCube.multiply(sliver.den());
			}
			java.math.BigInteger[] split = num.multiply(newCube).divideAndRemainder(den);
			long now = split[0].longValueExact();
			Sliver left = Sliver.of(split[1], den.multiply(newCube));
			if (now > 0) budget.put(id, now);
			if (!left.isNone()) {
				slivers.put(id, left);
				BuiltInRegistries.ITEM.getOptional(id).ifPresent(slivered::add);
			}
		}
		project.budget.clear();
		project.budget.putAll(budget);
		project.slivers.clear();
		project.slivers.putAll(slivers);
		project.n = newN;
		setDirty();
		return slivered;
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
		project.slivers.clear();
		project.n = 0;
		project.slab = false;
		project.redstone = false;
		project.handle = null;
		project.name = "";
		setDirty();
	}

	/** A builder in the box; any number may be. */
	public void arrive(int plot, UUID player, String name) {
		Project project = projects.get(plot);
		if (project == null) return;
		if (!name.equals(project.occupants.put(player, name))) setDirty();
	}

	/** A builder out of the box. */
	public void depart(int plot, UUID player) {
		Project project = projects.get(plot);
		if (project != null && project.occupants.remove(player) != null) setDirty();
	}

	/** Every plot somebody is in, for keeping the ledger in step with where builders really are. */
	public List<Integer> occupiedPlots() {
		List<Integer> plots = new ArrayList<>();
		projects.forEach((plot, project) -> { if (!project.occupants.isEmpty()) plots.add(plot); });
		return plots;
	}
}
