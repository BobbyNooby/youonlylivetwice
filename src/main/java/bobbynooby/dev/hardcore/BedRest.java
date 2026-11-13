package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.StandIn;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.AbstractBedBlock;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Bed rest: lying in a bed heals the player at Regeneration's pace, with no effect and so no icon, and makes broken
 * legs heal faster. Where beds don't blow up, the player may lie down in the day too, but such a nap never counts
 * towards skipping the night.
 */
public final class BedRest {
    private static final int STATUS_ORDER = 70;

    // Ticks towards the next heal, by player object. Server thread only.
    private static final Map<ServerPlayer, Double> TIMERS = new WeakHashMap<>();

    private BedRest() {}

    static void init() {
        Hardcore.onTick(BedRest::tick);
        Legs.speedFactor(player -> resting(player) ? Settings.get().hardcore.bedRest.legHealSpeed : 1.0);
        Status.register(STATUS_ORDER, BedRest::statusLine);
    }

    /** True when the player may lie down here whatever the time: bed rest and its daytime switch on, and the bed rule here allows it. */
    public static boolean allowsDaytime(ServerPlayer player) {
        return allowsDaytime(ruleHere(player));
    }

    /**
     * True when this bed rule's time of day is lifted. Only a rule that lets you sleep in the dark is: a bed that blows
     * up, or never lets anyone sleep, stays as vanilla has it.
     */
    public static boolean allowsDaytime(BedRule rule) {
        return daytimeOn() && rule.canSleep() == BedRule.Rule.WHEN_DARK && !rule.destroyOnUse();
    }

    /** True while the player is asleep only because the daytime rule let them: vanilla's own rule would wake them now. */
    public static boolean napping(ServerPlayer player) {
        if (!player.isSleeping() || !daytimeOn()) {
            return false;
        }
        BedRule rule = ruleHere(player);
        return !rule.canSleep(player.level()) && allowsDaytime(rule);
    }

    /** How fast the bed heals this player: 1.0 is the set pace, slower while protein is low. */
    public static double healingSpeed(ServerPlayer player) {
        return Nutrients.healingSpeed(player);
    }

    /** The players without any napper, for the count that decides whether the night skips. The same list when none naps. */
    public static List<ServerPlayer> withoutNappers(List<ServerPlayer> players) {
        if (!daytimeOn()) {
            return players;
        }
        for (ServerPlayer player : players) {
            if (napping(player)) {
                return players.stream().filter(p -> !napping(p)).toList();
            }
        }
        return players;
    }

    private static boolean on() {
        return Settings.on(c -> c.hardcore.bedRest.enabled);
    }

    private static boolean daytimeOn() {
        return Settings.on(c -> c.hardcore.bedRest.enabled && c.hardcore.bedRest.allowDaytime);
    }

    private static boolean resting(ServerPlayer player) {
        return on() && player.isSleeping() && !Hardcore.exempt(player) && !StandIn.is(player);
    }

    // As vanilla's own wake check reads it: the bed the player lies in, at the player's position
    private static BedRule ruleHere(ServerPlayer player) {
        if (player.getInBlockState().getBlock() instanceof AbstractBedBlock bed) {
            return bed.getBedRule(player.level(), player.blockPosition());
        }
        return player.level().environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, player.blockPosition());
    }

    private static void tick(ServerPlayer player) {
        HardcoreConfig.BedRestSection cfg = Settings.get().hardcore.bedRest;
        if (!resting(player) || cfg.healEveryTicks <= 0) {
            // Getting up starts the count again
            TIMERS.remove(player);
            return;
        }
        double timer = TIMERS.getOrDefault(player, 0.0) + healingSpeed(player);
        if (timer < cfg.healEveryTicks) {
            TIMERS.put(player, timer);
            return;
        }
        TIMERS.put(player, Math.min(timer - cfg.healEveryTicks, cfg.healEveryTicks));
        if (player.getHealth() < player.getMaxHealth()) {
            player.heal(1.0F);
            // Straight onto the food data, so a hunger multiplier doesn't scale it a second time
            if (cfg.exhaustionPerHeal > 0.0) {
                player.getFoodData().addExhaustion((float) cfg.exhaustionPerHeal);
            }
        }
    }

    private static Optional<Component> statusLine(ServerPlayer player) {
        if (!resting(player)) {
            return Optional.empty();
        }
        return Optional.of(Status.line("Resting  healing (bed)", "Lying in a bed heals you slowly and makes broken legs heal"
                + " faster. Getting up or taking damage stops it."));
    }
}
