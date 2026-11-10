package bobbynooby.dev.bodies;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.StandIn;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Vanilla /kill leaves offline players' bodies standing, so a lag clear such as /kill @e[type=!player] can't kill every
 * player whose body is loaded. Only the command: damage and the void still kill a body.
 */
public final class VanillaKill {
    private VanillaKill() {
    }

    /**
     * Runs vanilla's kill on the victims that aren't bodies, so its message counts only those, then tells the source
     * how many bodies were left. With bodies switched off too, since a body left from before still stands for its owner.
     */
    public static int kill(CommandSourceStack source, Collection<? extends Entity> victims,
                           ToIntFunction<Collection<? extends Entity>> vanilla) {
        if (!Settings.on(c -> c.bodies.ignoreVanillaKill)) {
            return vanilla.applyAsInt(victims);
        }
        List<Entity> others = new ArrayList<>();
        for (Entity victim : victims) {
            if (!isBody(victim)) {
                others.add(victim);
            }
        }
        int skipped = victims.size() - others.size();
        if (skipped == 0) {
            return vanilla.applyAsInt(victims);
        }
        int killed = vanilla.applyAsInt(others);
        String hint = "Skipped " + skipped + " offline player " + (skipped == 1 ? "body" : "bodies")
                + ". Use /yolt kill <player> to kill one.";
        source.sendSuccess(() -> Component.literal(hint), false);
        return killed;
    }

    // A mannequin body carries the tag, and a player-type body is a stand-in as well
    private static boolean isBody(Entity entity) {
        return StandIn.is(entity) || entity.entityTags().contains(Bodies.TAG);
    }
}
