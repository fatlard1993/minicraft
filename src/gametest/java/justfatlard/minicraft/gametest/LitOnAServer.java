package justfatlard.minicraft.gametest;

import justfatlard.minicraft.Looks;
import justfatlard.minicraft.Main;
import justfatlard.minicraft.Miniature;
import justfatlard.minicraft.MiniatureBlock;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * A glowing miniature placed by hand on a dedicated server lights the world at once, for the
 * player who placed it, without their leaving and coming back.
 */
public final class LitOnAServer implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		// Not the usual port: a real server may already be on it.
		Properties properties = new Properties();
		properties.setProperty("server-port", "25597");
		try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties);
				TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			server.runCommand("gamemode survival @a");
			server.runCommand("time set midnight");
			BlockPos origin = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());
			BlockPos stand = origin.north(3);
			byte[] cells = new byte[8];
			Arrays.fill(cells, (byte) 1);
			Looks looks = Looks.plain(new Miniature(2, List.of(Blocks.SHROOMLIGHT.defaultBlockState()), cells));

			server.runOnServer(s -> {
				s.overworld().setBlockAndUpdate(stand, Blocks.STONE.defaultBlockState());
				s.overworld().setBlockAndUpdate(stand.above(), Blocks.AIR.defaultBlockState());
				ServerPlayer player = connection.getServerPlayer();
				player.setItemInHand(InteractionHand.MAIN_HAND, looks.toItem());
				Vec3 feet = Vec3.atBottomCenterOf(stand.south(2));
				Vec3 aim = Vec3.atCenterOf(stand).add(0, 0.5, 0).subtract(feet.add(0, player.getEyeHeight(), 0));
				float yaw = (float) Math.toDegrees(Math.atan2(-aim.x, aim.z));
				float pitch = (float) -Math.toDegrees(Math.atan2(aim.y, Math.sqrt(aim.x * aim.x + aim.z * aim.z)));
				player.teleportTo(s.overworld(), feet.x, feet.y, feet.z, Set.of(), yaw, pitch, true);
			});
			context.waitTicks(10);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitTicks(20);
			BlockPos at = stand.above();
			check(server.computeOnServer(s -> s.overworld().getBlockState(at).is(Main.MINIATURE)), "the miniature was not placed by hand");
			int stateLight = server.computeOnServer(s -> s.overworld().getBlockState(at).getValue(MiniatureBlock.LIGHT));
			int serverLight = server.computeOnServer(s -> s.overworld().getBrightness(LightLayer.BLOCK, at.above()));
			int clientEmission = context.computeOnClient(client -> client.level.getBlockState(at).getLightEmission());
			int clientLight = context.computeOnClient(client -> client.level.getBrightness(LightLayer.BLOCK, at.above()));
			String clientState = context.computeOnClient(client -> client.level.getBlockState(at).toString());
			System.out.println("[minicraft-test] lit on a server: state " + stateLight + ", server " + serverLight + ", client emission "
				+ clientEmission + ", client " + clientLight + ", client sees " + clientState);
			check(stateLight == 15 && serverLight == 14, "the server does not light it: state " + stateLight + ", light " + serverLight);
			check(clientEmission == 15, "the client takes it for a block giving " + clientEmission + " light: " + clientState);
			check(clientLight == 14, "the client does not light the air above it: " + clientLight);
			System.out.println("[minicraft-test] lit on a server passed");
		}
	}

	private static void check(boolean holds, String otherwise) {
		if (!holds) throw new AssertionError(otherwise);
	}
}
