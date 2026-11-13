package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Yolt;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.ToDoubleFunction;

/**
 * Every hardcore slowdown, summed into one hidden movement speed modifier. Nothing is cached per player: the modifier on
 * the player is what has been applied, so a respawn's new player object, which has none, simply gets it again.
 */
public final class Slowdowns {
    static final Identifier MODIFIER = Yolt.id("hardcore_slow");
    // Two stepped totals closer than this are the same step
    private static final double SAME = 1e-9;

    private record Part(String name, ToDoubleFunction<ServerPlayer> share) {}

    // Copy on write, since a test can take its part away while the tick reads the list
    private static final List<Part> PARTS = new CopyOnWriteArrayList<>();

    private Slowdowns() {}

    /** Adds a share of speed to take. Each feature registers once, at init, and its part returns 0 while it is off. */
    public static void register(String name, ToDoubleFunction<ServerPlayer> part) {
        PARTS.add(new Part(name, part));
    }

    /** The same, for a test, which runs the returned task to take the part away again. */
    public static Runnable registerForTesting(String name, ToDoubleFunction<ServerPlayer> part) {
        Part added = new Part(name, part);
        PARTS.add(added);
        return () -> PARTS.remove(added);
    }

    /** The stepped total now on the player, 0 when they have none. */
    public static double applied(ServerPlayer player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        AttributeModifier modifier = speed == null ? null : speed.getModifier(MODIFIER);
        return modifier == null ? 0.0 : -modifier.amount();
    }

    /** What the parts add up to before the cap and the steps. */
    public static double raw(ServerPlayer player) {
        double sum = 0.0;
        for (double share : shares(player)) {
            if (share > 0.0) {
                sum += share;
            }
        }
        return sum;
    }

    /** Works the total out again, and touches the modifier only when it moved to another step. */
    static void refresh(ServerPlayer player) {
        HardcoreConfig.SlowSection slow = Settings.get().hardcore.slow;
        double total = SlowBudget.total(slow.cap, slow.step, shares(player));
        if (Math.abs(total - applied(player)) < SAME) {
            return;
        }
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        if (total < SAME) {
            speed.removeModifier(MODIFIER);
        } else {
            speed.addOrUpdateTransientModifier(new AttributeModifier(MODIFIER, -total, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    /** Takes the modifier off, as for a player who no longer plays by the hardcore rules. */
    static void clear(ServerPlayer player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(MODIFIER);
        }
    }

    // The stream walks one snapshot of the list
    private static double[] shares(ServerPlayer player) {
        return PARTS.stream().mapToDouble(part -> part.share().applyAsDouble(player)).toArray();
    }
}
