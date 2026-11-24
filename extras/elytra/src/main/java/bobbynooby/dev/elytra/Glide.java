package bobbynooby.dev.elytra;

import bobbynooby.dev.elytra.mixin.FireworkRocketInvoker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The elytra nerf: lighter gravity while gliding, which makes the glide slower and floatier over the same distance, and
 * a horizontal speed past which a rocket boosting the glider is ended early. The server learns that speed a moment after
 * the client flies it, so a rocket can still take a glider well past it. The client flies with the synced gravity
 * itself, and a rocket that ends early just looks like a shorter one, so the server never fights the client's own speed.
 * A player {@link YoltElytra#exempt} keeps vanilla's elytra.
 */
final class Glide {
    private static final double TICKS_PER_SECOND = 20.0;
    // An attached rocket sits on the player at its own tick, and the player may have moved since
    private static final double ROCKET_REACH = 8.0;
    private static final double SAME = 1e-9;

    // The name TinyMod logs this mod's own lines under
    private static final Logger LOGGER = LoggerFactory.getLogger("YOLT Elytra");

    private Glide() {}

    // Every player, one tick. A player whose tick throws is logged and skipped, so the others still glide by the rules
    // and the throw never reaches the server's own tick.
    static void tickAll(Iterable<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            try {
                tick(player);
            } catch (RuntimeException e) {
                // SLF4J takes a trailing Throwable as the exception, not as an argument
                LOGGER.error("Could not run the elytra tick for {}", player.nameAndId().name(), e);
            }
        }
    }

    /**
     * One player, one tick: the gravity modifier comes off when the mod is off, the player is left out or isn't gliding,
     * and otherwise is set, and a rocket boosting a glider past the cut-off is ended early.
     */
    static void tick(ServerPlayer player) {
        ElytraSettings settings = YoltElytra.settings();
        if (!settings.enabled() || YoltElytra.exempt(player) || !player.isFallFlying()) {
            clear(player);
            return;
        }
        applyGravity(player, settings.glideGravityMultiplier() - 1.0);
        if (pastCutoff(player)) {
            endRockets(player);
        }
    }

    // True while the player glides faster than the rocket cut-off. A cut-off of 0 never cuts.
    static boolean pastCutoff(ServerPlayer player) {
        ElytraSettings settings = YoltElytra.settings();
        if (!settings.enabled() || YoltElytra.exempt(player) || !player.isFallFlying()) {
            return false;
        }
        double cutoff = settings.rocketCutoffSpeed();
        return cutoff > 0 && speed(player) > cutoff;
    }

    // Horizontal metres per second. A player moves itself, so the server's own delta movement isn't their speed; the
    // move packet handler keeps what the client last moved in one tick instead.
    private static double speed(ServerPlayer player) {
        return player.getKnownMovement().horizontalDistance() * TICKS_PER_SECOND;
    }

    private static void applyGravity(ServerPlayer player, double amount) {
        AttributeInstance gravity = player.getAttribute(Attributes.GRAVITY);
        if (gravity == null) {
            return;
        }
        AttributeModifier current = gravity.getModifier(YoltElytra.GRAVITY_MODIFIER);
        if (current != null && Math.abs(current.amount() - amount) < SAME) {
            return;
        }
        gravity.addOrUpdateTransientModifier(new AttributeModifier(YoltElytra.GRAVITY_MODIFIER, amount,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    // Runs every tick for every player who isn't gliding, so it costs nothing when there is nothing to take off
    private static void clear(ServerPlayer player) {
        AttributeInstance gravity = player.getAttribute(Attributes.GRAVITY);
        if (gravity != null && gravity.getModifier(YoltElytra.GRAVITY_MODIFIER) != null) {
            gravity.removeModifier(YoltElytra.GRAVITY_MODIFIER);
        }
    }

    // Removed rather than exploded, so the rocket ends without a blast or any damage
    private static void endRockets(ServerPlayer player) {
        for (FireworkRocketEntity rocket : player.level().getEntitiesOfClass(FireworkRocketEntity.class,
                player.getBoundingBox().inflate(ROCKET_REACH), candidate -> boosts(candidate, player))) {
            rocket.discard();
        }
    }

    // An attached rocket's owner is the entity it is stuck to
    private static boolean boosts(FireworkRocketEntity rocket, ServerPlayer player) {
        return ((FireworkRocketInvoker) rocket).yoltElytraIsAttachedToEntity() && rocket.getOwner() == player;
    }
}
