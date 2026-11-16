package justfatlard.minicraft;

import justfatlard.pandorical.api.Capabilities;
import justfatlard.pandorical.api.PandoricalApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.WorldlyContainerHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
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

import java.util.Optional;

/**
 * A finished miniature, placed. The block itself draws nothing: what is seen is the build, at
 * 1/N scale, turned to face whoever placed it. Breaking it gives the miniature back, whole.
 *
 * <p>Which look it wears is in its state: powered by a neighbour, its handle worked, and how
 * bright its look is, so the world's light and redstone treat it like any block that changes. So
 * is which half a slab miniature fills: double for a block, bottom or top for a slab.
 *
 * <p>It sounds and breaks like whatever fills most of its build, through its block entity: the
 * time to break it, quicker with the tool that block wants, and the particles and sound as it goes.
 * It always comes away whole, whatever it is broken with.
 */
public class MiniatureBlock extends Block implements EntityBlock, WorldlyContainerHolder {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
	public static final BooleanProperty POWERED = BooleanProperty.create("powered");
	public static final BooleanProperty HANDLED = BooleanProperty.create("handled");
	public static final IntegerProperty LIGHT = IntegerProperty.create("light", 0, 15);
	public static final EnumProperty<SlabType> TYPE = BlockStateProperties.SLAB_TYPE;

	private static final VoxelShape BOTTOM = Block.box(0, 0, 0, 16, 8, 16);
	private static final VoxelShape TOP = Block.box(0, 8, 0, 16, 16, 16);

	public MiniatureBlock(Properties properties) {
		super(properties.noOcclusion().dynamicShape().pushReaction(PushReaction.POPPED).lightLevel(state -> state.getValue(LIGHT)));
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH)
			.setValue(POWERED, false).setValue(HANDLED, false).setValue(LIGHT, 0).setValue(TYPE, SlabType.DOUBLE));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, POWERED, HANDLED, LIGHT, TYPE);
	}

	/**
	 * As it was built: the workshop's north is the world's north, whichever way the placer faces.
	 * A slab goes on the half clicked, as a vanilla slab goes.
	 *
	 * <p>Facing stays in the state for miniatures placed before, which were turned to face their
	 * placer and keep that turn, and for a structure turning one; a new one is always placed facing
	 * south, which is no turn at all.
	 */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		// A miniature item with no build on it (picked from a creative tab, or given bare) has nothing to be.
		Optional<Looks> looks = Looks.of(context.getItemInHand());
		if (looks.isEmpty()) return null;
		BlockState state = defaultBlockState();
		if (!looks.get().isSlab()) return state;
		Direction face = context.getClickedFace();
		boolean top = face == Direction.DOWN || face != Direction.UP && context.getClickLocation().y - context.getClickedPos().getY() > 0.5;
		return state.setValue(TYPE, top ? SlabType.TOP : SlabType.BOTTOM);
	}

	/** The shape when the build's own is not known: the whole block, or the half a slab fills. */
	static VoxelShape fallbackShape(BlockState state) {
		return switch (state.getValue(TYPE)) {
			case BOTTOM -> BOTTOM;
			case TOP -> TOP;
			case DOUBLE -> Shapes.block();
		};
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
		return shapeFor(state, level, pos, context);
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shapeFor(state, level, pos, context);
	}

	/**
	 * The build's own shape, except to a player whose client cannot know it: a plain block (or
	 * the half a slab fills) for them, which is what their client takes it for, so the two sides
	 * never disagree under anybody.
	 */
	private static VoxelShape shapeFor(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		if (context instanceof EntityCollisionContext entity && entity.getEntity() instanceof ServerPlayer player
				&& !PandoricalApi.hasCapability(player, Capabilities.BLOCK_SHAPES)) {
			return fallbackShape(state);
		}
		return level.getBlockEntity(pos) instanceof MiniatureBlockEntity miniature ? miniature.shape() : fallbackShape(state);
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

	/** A worked handle is a lever thrown: a signal on every side while it is on. A trapped chest gives one while it is open. */
	@Override
	protected boolean isSignalSource(BlockState state) {
		return state.getValue(HANDLED);
	}

	@Override
	protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		if (state.getValue(HANDLED)) return 15;
		return level.getBlockEntity(pos) instanceof MiniatureBlockEntity miniature && miniature.function() != null ? miniature.function().signal() : 0;
	}

	/** A comparator reads what its function holds, as it would read the block's own. */
	@Override
	protected boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
		return level.getBlockEntity(pos) instanceof MiniatureBlockEntity miniature && miniature.function() != null ? miniature.function().analogSignal() : 0;
	}

	/** What hoppers reach: what its function holds, if it holds anything. */
	@Override
	public WorldlyContainer getContainer(BlockState state, LevelAccessor level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof MiniatureBlockEntity miniature) || miniature.function() == null) return null;
		Container container = miniature.function().container();
		return container == null ? null : Functions.forHoppers(container);
	}

	// --- Using it ---

	/**
	 * A sneaking right-click with both hands empty, on any miniature, opens the dialog for building
	 * it full size ({@link ConstructScreen}). Otherwise a right-click works its handle, if it has
	 * one, or its function, if it has one: opens the crafting table, the furnace, the chest it
	 * works as. A miniature has one or the other, never both. Sneaking with a block in hand places
	 * the block against it, as vanilla does; sneaking with anything else is caught by
	 * {@link #sneakWithItem}, as vanilla would hand that click straight to the item.
	 */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!(level.getBlockEntity(pos) instanceof MiniatureBlockEntity miniature)) return InteractionResult.PASS;
		Looks looks = miniature.looks().orElse(null);
		if (looks == null) return InteractionResult.PASS;
		if (player.isSecondaryUseActive() && player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty()) {
			if (level instanceof ServerLevel server && player instanceof ServerPlayer user) ConstructScreen.open(user, server, pos);
			return InteractionResult.SUCCESS;
		}
		if (!looks.hasHandle() && miniature.function() == null) return InteractionResult.PASS;
		if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer user)) return InteractionResult.SUCCESS;
		if (looks.hasHandle()) miniature.workHandle(server);
		else miniature.function().use(user, server, pos);
		return InteractionResult.SUCCESS;
	}

	/**
	 * Holding something: a handle is worked whatever is held; a function takes what it takes (a
	 * disc for a jukebox), and anything else is an empty-handed click.
	 */
	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		// Empty-handed, it comes here too: on to useWithoutItem, which opens the dialog for a sneak.
		if (!stack.isEmpty() && level instanceof ServerLevel server && player instanceof ServerPlayer user && level.getBlockEntity(pos) instanceof MiniatureBlockEntity miniature) {
			if (miniature.looks().map(Looks::hasHandle).orElse(false)) {
				miniature.workHandle(server);
				MiniatureBlockEntity.workedWithItem(user);
				return InteractionResult.SUCCESS;
			}
			if (!player.isSecondaryUseActive() && miniature.function() != null && miniature.function().useItem(user, server, pos, stack)) {
				return InteractionResult.SUCCESS;
			}
		}
		return InteractionResult.TRY_WITH_EMPTY_HAND;
	}

	/**
	 * A sneaking right-click holding something that is not a block, as Fabric's use event sees it
	 * before vanilla passes a sneaking click with something in hand straight to the item: on a
	 * miniature with a handle, it works the handle, as every click but the two above does.
	 */
	static InteractionResult sneakWithItem(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!player.isSecondaryUseActive() || player.isSpectator()) return InteractionResult.PASS;
		ItemStack held = player.getItemInHand(hand);
		if (held.isEmpty() || held.getItem() instanceof net.minecraft.world.item.BlockItem) return InteractionResult.PASS;
		if (!(level instanceof ServerLevel server) || !(level.getBlockEntity(hit.getBlockPos()) instanceof MiniatureBlockEntity miniature)
				|| !miniature.looks().map(Looks::hasHandle).orElse(false)) {
			return InteractionResult.PASS;
		}
		miniature.workHandle(server);
		if (player instanceof ServerPlayer user) MiniatureBlockEntity.workedWithItem(user);
		return InteractionResult.SUCCESS;
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (level.isClientSide() || type != Main.MINIATURE_BLOCK_ENTITY) return null;
		return (at, pos, now, entity) -> ((MiniatureBlockEntity) entity).tick((ServerLevel) at, pos, now);
	}

	// --- Breaking it like what it is made of ---

	/**
	 * As long to break as its majority block, and as quick with the right tool. Divided by the
	 * pace {@link Breaking} sets on the player's own break speed, which is there only so their
	 * client, which takes this for a plain block, reaches the end at the same moment.
	 */
	@Override
	protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof MiniatureBlockEntity miniature && miniature.looks().isPresent()) {
			BlockState most = miniature.majority();
			if (most.getDestroySpeed(level, pos) >= 0) return most.getDestroyProgress(player, level, pos) / Breaking.pace(player);
		}
		return super.getDestroyProgress(state, player, level, pos) / Breaking.pace(player);
	}

	/** What a plain miniature takes to break, which is what a client predicts: the pace is measured against it. */
	float plainProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
		return super.getDestroyProgress(state, player, level, pos);
	}

	/**
	 * Breaking particles and sound are its majority block's, for everyone but the breaker, whose
	 * client plays its own from the material Pandorical tells it. Sent as the majority block rather
	 * than left to the material so a client without one hears the build too.
	 */
	@Override
	public void spawnDestroyByEntityParticles(Level level, Entity entity, BlockPos pos, BlockState state) {
		if (level.getBlockEntity(pos) instanceof MiniatureBlockEntity miniature && miniature.looks().isPresent()) {
			level.levelEvent(entity, 2001, pos, getId(miniature.majority()));
			return;
		}
		super.spawnDestroyByEntityParticles(level, entity, pos, state);
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
