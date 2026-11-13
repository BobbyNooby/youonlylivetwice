package bobbynooby.dev.hardcore;

import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodyRules;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.StandIn;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.ToDoubleFunction;

/**
 * Broken legs: a long fall breaks them, and they slow the player until they heal, which takes food the way health
 * regen does, and only once health is full. A logout body's fall breaks its owner's legs; a body never heals. The
 * state lives in the hardcore store, so it outlasts a relog and changes for a player who isn't online.
 */
public final class Legs {
    private static final int STATUS_ORDER = 20;
    private static final String BROKE = "Your legs broke.";
    private static final String HEALED = "Your legs have healed.";

    // Ticks towards the next heal point, by player object, so a respawn starts afresh. Server thread only.
    private static final Map<ServerPlayer, Double> TIMERS = new WeakHashMap<>();
    // Multiplied together into the heal timer's speed each tick. Copy on write, as the other registries are.
    private static final List<ToDoubleFunction<ServerPlayer>> SPEED_FACTORS = new CopyOnWriteArrayList<>();

    private Legs() {}

    static void init() {
        Slowdowns.register("legs", Legs::slowShare);
        Status.register(STATUS_ORDER, Legs::statusLine);
        // The one call a tick; anything that speeds healing up or slows it down goes in as a factor
        Hardcore.onTick(player -> heal(player, speed(player)));
        // A new life, whatever the game mode was
        Hardcore.onDeath((uuid, mode) -> {
            if (on()) {
                set(uuid, false);
            }
        });
    }

    /** True while the player's stored legs are broken, whatever the switch says. */
    public static boolean broken(ServerPlayer player) {
        return HardcoreStates.of(player).legsBroken;
    }

    /** Called with a hit's damage after armour and protection enchantments, before absorption, for every living entity. */
    public static void onFallDamage(LivingEntity entity, DamageSource source, float afterProtection) {
        if (!on() || !(afterProtection > 0.0F) || !breaksLegs(source)) {
            return;
        }
        Guard.run(() -> fall(entity, afterProtection), "Could not check a fall for broken legs on {}", entity.getStringUUID());
    }

    /**
     * One tick of the heal timer at this speed: 1.0 is the normal pace, 2.0 twice as fast. Call it at most once a tick
     * for a player, since each call counts as a tick.
     */
    public static void heal(ServerPlayer player, double speed) {
        if (!on() || !(speed > 0.0) || Hardcore.exempt(player) || StandIn.is(player)) {
            return;
        }
        HardcoreState state = HardcoreStates.of(player);
        if (!state.legsBroken) {
            TIMERS.remove(player);
            return;
        }
        HardcoreConfig.LegsSection cfg = Settings.get().hardcore.legs;
        if (state.legHealPoints >= cfg.healPoints) {
            healed(player);
            return;
        }
        if (cfg.healthFirst && player.getHealth() < player.getMaxHealth()) {
            TIMERS.remove(player);
            return;
        }
        FoodData food = player.getFoodData();
        MobEffectInstance regeneration = cfg.regenEffectHeals ? player.getEffect(MobEffects.REGENERATION) : null;
        boolean regenRule = player.level().getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION);
        int pace = regeneration != null
                ? LegHealing.regenerationTicksPerPoint(regeneration.getAmplifier())
                : LegHealing.ticksPerPoint(food.getFoodLevel(), food.getSaturationLevel(), regenRule, cfg);
        if (pace <= 0) {
            // As vanilla's regen timer, it starts again from nothing once healing can go on
            TIMERS.remove(player);
            return;
        }
        double timer = TIMERS.getOrDefault(player, 0.0) + speed;
        if (timer < pace) {
            TIMERS.put(player, timer);
            return;
        }
        // One point a call; what is left over is kept, so a fractional speed loses nothing
        TIMERS.put(player, Math.min(timer - pace, pace));
        // Straight onto the food data, so a hunger multiplier doesn't scale it a second time. Regeneration and the
        // rule-off pace cost nothing.
        if (regeneration == null && regenRule) {
            food.addExhaustion((float) cfg.exhaustionPerPoint);
        }
        HardcoreStates.update(player.getUUID(), stored -> stored.legHealPoints += 1.0);
        if (HardcoreStates.of(player).legHealPoints >= cfg.healPoints) {
            healed(player);
        }
    }

    /**
     * Adds a factor to the heal timer's speed: 1.0 leaves it alone, 2.0 heals twice as fast, 0.5 half as fast. Each
     * feature registers once, at init, and its factor returns 1.0 while it is off.
     */
    public static void speedFactor(ToDoubleFunction<ServerPlayer> factor) {
        SPEED_FACTORS.add(factor);
    }

    /** Sets the stored legs, online or not, with no heal progress. A death clears them with false. */
    public static void set(UUID uuid, boolean broken) {
        HardcoreStates.update(uuid, state -> {
            state.legsBroken = broken;
            state.legHealPoints = 0.0;
        });
        TIMERS.keySet().removeIf(player -> player.getUUID().equals(uuid));
    }

    private static boolean on() {
        return Settings.on(c -> c.hardcore.legs.enabled);
    }

    private static double speed(ServerPlayer player) {
        double speed = 1.0;
        for (ToDoubleFunction<ServerPlayer> factor : SPEED_FACTORS) {
            speed *= factor.applyAsDouble(player);
        }
        return speed;
    }

    // Ender pearls count as falls in vanilla's tag, but never break legs
    private static boolean breaksLegs(DamageSource source) {
        return source.is(DamageTypes.FALL) || source.is(DamageTypes.STALAGMITE)
                || Settings.get().hardcore.legs.includeElytraCrash && source.is(DamageTypes.FLY_INTO_WALL);
    }

    private static void fall(LivingEntity entity, float afterProtection) {
        HardcoreConfig.LegsSection cfg = Settings.get().hardcore.legs;
        if (entity instanceof ServerPlayer player && !StandIn.is(player)) {
            if (Hardcore.exempt(player)) {
                return;
            }
            double breakAt = LegHealing.breakAt(cfg.breakAtDamage, cfg.breakAtDamageFullLoad, cfg.heavyLandings,
                    Weight.loadShare(player));
            if (afterProtection >= breakAt) {
                // Breaking them again starts the healing over
                set(player.getUUID(), true);
                Hardcore.actionBar(player, BROKE);
            }
            return;
        }
        // A body carries no load, so it breaks at the plain value
        if (cfg.bodiesCanBreakLegs && afterProtection >= cfg.breakAtDamage) {
            ownerOfBody(entity).ifPresent(owner -> set(owner, true));
        }
    }

    // Mannequin and player bodies alike are found by their entity in the body store
    private static Optional<UUID> ownerOfBody(LivingEntity entity) {
        if (!BodyRules.isBody(entity) || !(entity.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        return BodyStore.get(level.getServer()).byEntity(entity.getUUID()).map(BodyRecord::owner);
    }

    private static void healed(ServerPlayer player) {
        set(player.getUUID(), false);
        Hardcore.actionBar(player, HEALED);
    }

    private static double slowShare(ServerPlayer player) {
        if (!on() || Hardcore.exempt(player) || StandIn.is(player)) {
            return 0.0;
        }
        return broken(player) ? Settings.get().hardcore.legs.slow : 0.0;
    }

    private static Optional<Component> statusLine(ServerPlayer player) {
        if (!on() || Hardcore.exempt(player) || !broken(player)) {
            return Optional.empty();
        }
        HardcoreConfig.LegsSection cfg = Settings.get().hardcore.legs;
        HardcoreState state = HardcoreStates.of(player);
        long percent = Math.round(cfg.slow * 100.0);
        String text = "Legs   broken → " + percent + "% slower · healing " + (long) state.legHealPoints + "/" + cfg.healPoints
                + (cfg.healthFirst ? " (uses food once your health is full)" : " (uses food)");
        return Optional.of(Status.line(text, "A long fall broke your legs. They heal over time while you are well fed,"
                + " using food the way health regen does, and Regeneration heals them too. Dying clears them."));
    }
}
