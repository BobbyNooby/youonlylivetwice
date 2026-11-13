package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.StandIn;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The hunger tax: one multiplier on the hunger that actions cost, such as sprinting, jumping, attacking, mining and
 * taking damage. Healing puts its cost straight on the food data, so it is never taxed. At 1.0 it is vanilla.
 */
public final class HungerTax {
    private HungerTax() {}

    static void init() {
        Status.rule(HungerTax::statusPart);
    }

    /** An action's hunger for this player: scaled for anyone playing by the rules, as it is for everyone else. */
    public static float scale(Player player, float amount) {
        if (!Settings.active() || !(player instanceof ServerPlayer serverPlayer) || StandIn.is(serverPlayer)
                || Hardcore.exempt(serverPlayer)) {
            return amount;
        }
        double multiplier = multiplier();
        return multiplier == 1.0 ? amount : (float) (amount * multiplier);
    }

    private static double multiplier() {
        return Settings.get().hardcore.hungerTax.multiplier;
    }

    private static Optional<String> statusPart(ServerPlayer player) {
        double multiplier = multiplier();
        if (multiplier == 1.0) {
            return Optional.empty();
        }
        // 2 rather than 2.0, and 1.5 as it is
        return Optional.of("hunger ×" + BigDecimal.valueOf(multiplier).stripTrailingZeros().toPlainString());
    }
}
