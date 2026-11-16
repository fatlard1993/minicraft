package justfatlard.minicraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.attribute.EnvironmentAttributeReader;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The world as it would be with a build standing in it, for asking whether a block could stay
 * where it lands before anything is placed: the build's blocks where they would go, the
 * miniature's spot as the build's corner, and the real world everywhere else.
 */
final class Overlay implements LevelReader {
	private final ServerLevel level;
	private final Map<BlockPos, BlockState> build = new HashMap<>();

	Overlay(ServerLevel level, BlockPos at, Miniature cells) {
		this.level = level;
		int n = cells.n();
		build.put(at, Blocks.AIR.defaultBlockState());
		for (int y = 0; y < cells.height(); y++) for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
			BlockState state = cells.at(x, y, z);
			if (!state.isAir()) build.put(Constructing.target(at, n, x, y, z), state);
		}
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		BlockState state = build.get(pos);
		return state != null ? state : level.getBlockState(pos);
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getBlockState(pos).getFluidState();
	}

	@Override
	public BlockEntity getBlockEntity(BlockPos pos) {
		return build.containsKey(pos) ? null : level.getBlockEntity(pos);
	}

	@Override
	public ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus status, boolean load) {
		return level.getChunk(chunkX, chunkZ, status, load);
	}

	@Override
	public boolean hasChunk(int chunkX, int chunkZ) {
		return level.hasChunk(chunkX, chunkZ);
	}

	@Override
	public int getHeight(Heightmap.Types type, int x, int z) {
		return level.getHeight(type, x, z);
	}

	@Override
	public int getSkyDarken() {
		return level.getSkyDarken();
	}

	@Override
	public BiomeManager getBiomeManager() {
		return level.getBiomeManager();
	}

	@Override
	public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
		return level.getUncachedNoiseBiome(x, y, z);
	}

	@Override
	public boolean isClientSide() {
		return false;
	}

	@Override
	public int getSeaLevel() {
		return level.getSeaLevel();
	}

	@Override
	public DimensionType dimensionType() {
		return level.dimensionType();
	}

	@Override
	public RegistryAccess registryAccess() {
		return level.registryAccess();
	}

	@Override
	public FeatureFlagSet enabledFeatures() {
		return level.enabledFeatures();
	}

	@Override
	public EnvironmentAttributeReader environmentAttributes() {
		return level.environmentAttributes();
	}

	@Override
	public LevelLightEngine getLightEngine() {
		return level.getLightEngine();
	}

	@Override
	public WorldBorder getWorldBorder() {
		return level.getWorldBorder();
	}

	@Override
	public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
		return this;
	}

	@Override
	public List<VoxelShape> getEntityCollisions(Entity source, AABB area) {
		return level.getEntityCollisions(source, area);
	}

	@Override
	public int getHeight() {
		return level.getHeight();
	}

	@Override
	public int getMinY() {
		return level.getMinY();
	}
}
