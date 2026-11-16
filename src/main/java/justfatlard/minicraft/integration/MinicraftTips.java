package justfatlard.minicraft.integration;

import justfatlard.block_tip.api.BlockTipApi;
import justfatlard.minicraft.Bench;
import justfatlard.minicraft.BenchBlockEntity;
import justfatlard.minicraft.Ledger;
import justfatlard.minicraft.Looks;
import justfatlard.minicraft.MiniatureBlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * The card for a miniature says its name, how finely it is cut, who made it, and what it does,
 * since none of that shows; the card for a bench says what it is working on.
 *
 * <p>Compiled against block-tip's API and reached only from a mod-loaded check, so a server
 * without block-tip never loads this class.
 */
public final class MinicraftTips {
	private MinicraftTips() {}

	public static void register() {
		BlockTipApi.describe((level, pos, state, player) -> {
			if (level.getBlockEntity(pos) instanceof MiniatureBlockEntity miniature) return describe(miniature);
			if (level.getBlockEntity(pos) instanceof BenchBlockEntity) return describe(Ledger.get(level.getServer()).project(Bench.plotOf(level, pos)));
			return null;
		});
	}

	private static String describe(MiniatureBlockEntity miniature) {
		Looks looks = miniature.looks().orElse(null);
		if (looks == null) return null;
		List<String> parts = new ArrayList<>();
		if (miniature.name() != null) parts.add("\"" + miniature.name().getString() + "\"");
		parts.add("Cut " + looks.off().n() + " to a side" + (looks.isSlab() ? ", a slab" : "") + looks.maker().map(name -> ", by " + name).orElse(""));
		if (looks.respondsToRedstone()) parts.add("responds to redstone");
		looks.function().ifPresent(block -> parts.add("works as " + block.getName().getString().toLowerCase(java.util.Locale.ROOT)));
		if (looks.hasHandle()) parts.add("right-click to work its handle");
		return String.join(" · ", parts);
	}

	private static String describe(Ledger.Project project) {
		if (project == null) return null;
		if (project.n() == 0) return "Choose a size to begin";
		int materials = project.materials().size();
		String held = materials == 0 ? "nothing loaded" : materials == 1 ? "1 material" : materials + " materials";
		String function = project.function().isEmpty() ? "" : " · works as " + project.function().getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
		int inside = project.occupants().size();
		String use = project.capturing() ? " · trying a build out" : inside == 1 ? " · 1 building" : inside > 1 ? " · " + inside + " building" : "";
		return "Cut " + project.n() + " to a side" + (project.slab() ? ", a slab" : "") + " · " + held + function + use;
	}
}
