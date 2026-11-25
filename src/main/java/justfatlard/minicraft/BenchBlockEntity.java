package justfatlard.minicraft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** A bench remembers one thing, which plot is its own; the ledger has everything else. */
public class BenchBlockEntity extends BlockEntity {
	private int plot = -1;

	public BenchBlockEntity(BlockPos pos, BlockState state) {
		super(Main.BENCH_BLOCK_ENTITY, pos, state);
	}

	/** The bench's plot, opened the first time it is asked for. */
	public int plot() {
		if (plot < 0 && level instanceof ServerLevel server) {
			plot = Ledger.get(server.getServer()).open();
			setChanged();
		}
		return plot;
	}

	/** Gone with its world rather than broken: its plot given back with nothing handed out, and never this bench's again. */
	public void forget() {
		if (plot < 0 || !(level instanceof ServerLevel server)) return;
		Bench.forget(server.getServer(), plot);
		plot = -1;
		setChanged();
	}

	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (plot >= 0 && level instanceof ServerLevel server) Bench.broken(server, pos, plot);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putInt("plot", plot);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		plot = input.getIntOr("plot", -1);
	}
}
