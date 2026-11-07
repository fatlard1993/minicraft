package justfatlard.minicraft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The miniature bench. Right-click it with blocks to load them, with a miniature to work on it
 * again, or with an empty hand to open it.
 */
public class BenchBlock extends Block implements EntityBlock {
	public BenchBlock(Properties properties) {
		super(properties.pushReaction(PushReaction.IMMOVEABLE));
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new BenchBlockEntity(pos, state);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (level instanceof ServerLevel server && player instanceof ServerPlayer user) BenchScreen.open(user, server, pos);
		return InteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		boolean miniature = stack.is(Main.MINIATURE_ITEM);
		if (!miniature && !Bench.loadable(stack)) return InteractionResult.TRY_WITH_EMPTY_HAND;
		if (level instanceof ServerLevel server && player instanceof ServerPlayer user) {
			if (miniature) Bench.edit(user, server, pos, stack);
			else Bench.load(user, server, pos, stack);
		}
		return InteractionResult.SUCCESS;
	}
}
