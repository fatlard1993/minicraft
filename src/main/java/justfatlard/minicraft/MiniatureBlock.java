package justfatlard.minicraft;

import justfatlard.pandorical.api.Capabilities;
import justfatlard.pandorical.api.PandoricalApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A finished miniature, placed. The block itself draws nothing: what is seen is the build, at
 * 1/N scale, turned to face whoever placed it. Breaking it gives the miniature back, whole.
 *
 * <p>Which look it wears is in its state: powered by a neighbour, its handle worked, and how
 * bright its look is, so the world's light and redstone treat it like any block that changes.
 */
public class MiniatureBlock extends Block implements EntityBlock {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
	public static final BooleanProperty POWERED = BooleanProperty.create("powered");
	public static final BooleanProperty HANDLED = BooleanProperty.create("handled");
	public static final IntegerProperty LIGHT = IntegerProperty.create("light", 0, 15);

	public MiniatureBlock(Properties properties) {
		super(properties.noOcclusion().dynamicShape().pushReaction(PushReaction.POPPED).lightLevel(state -> state.getValue(LIGHT)));
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH)
			.setValue(POWERED, false).setValue(HANDLED, false).setValue(LIGHT, 0));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, POWERED, HANDLED, LIGHT);
	}

	/** Its front, the side that faced the builder as they arrived in the workshop, turned to the player placing it. */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		// A miniature item with no build on it (picked from a creative tab, or given bare) has nothing to be.
		if (Looks.of(context.getItemInHand()).isEmpty()) return null;
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shapeFor(level, pos, context);
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shapeFor(level, pos, context);
	}

	/**
	 * The build's own shape, except to a player whose client cannot know it: a plain block for
	 * them, which is what their client takes it for, so the two sides never disagree under anybody.
	 */
	private static VoxelShape shapeFor(BlockGetter level, BlockPos pos, CollisionContext context) {
		if (context instanceof EntityCollisionContext entity && entity.getEntity() instanceof ServerPlayer player
				&& !PandoricalApi.hasCapability(player, Capabilities.BLOCK_SHAPES)) {
			return Shapes.block();
		}
		return level.getBlockEntity(pos) instanceof MiniatureBlockEntity miniature ? miniature.shape() : Shapes.block();
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.INVISIBLE;
	}

	// --- Redstone ---

	/** A neighbour's signal changed: one that responds to redstone puts on its powered look, or takes it off. */
	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, Orientation orientation, boolean movedByPiston) {
		if (!(level instanceof ServerLevel server) || !(level.getBlockEntity(pos) instanceof MiniatureBlockEntity miniature)) return;
		if (!miniature.looks().map(Looks::respondsToRedstone).orElse(false)) return;
		boolean powered = level.hasNeighborSignal(pos);
		if (powered != state.getValue(POWERED)) miniature.wear(server, powered, state.getValue(HANDLED));
	}

	/** A worked handle is a lever thrown: a signal on every side while it is on. */
	@Override
	protected boolean isSignalSource(BlockState state) {
		return state.getValue(HANDLED);
	}

	@Override
	protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return state.getValue(HANDLED) ? 15 : 0;
	}

	// --- Using it ---

	/**
	 * A right-click opens its storage, or works its handle if it has no storage; with both, the
	 * handle is worked with a sneak.
	 */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!(level.getBlockEntity(pos) instanceof MiniatureBlockEntity miniature)) return InteractionResult.PASS;
		Looks looks = miniature.looks().orElse(null);
		if (looks == null || !looks.hasStorage() && !looks.hasHandle()) return InteractionResult.PASS;
		if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer user)) return InteractionResult.SUCCESS;
		boolean handle = looks.hasHandle() && (!looks.hasStorage() || player.isSecondaryUseActive());
		if (handle) miniature.wear(server, state.getValue(POWERED), !state.getValue(HANDLED));
		else miniature.openStorage(user);
		return InteractionResult.SUCCESS;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new MiniatureBlockEntity(pos, state);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof MiniatureBlockEntity miniature) {
			miniature.placed(server);
		}
	}
}
