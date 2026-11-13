package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.mixin.FireworkRocketInvoker;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;

/**
 * The elytra nerf: lighter gravity while gliding, which makes the glide slower and floatier over the same distance, and
 * a horizontal speed past which rockets stop pushing. The client flies with the synced gravity itself, and a rocket that
 * ends early just looks like a shorter one, so the server never fights the client's own speed.
 */
public final class Elytra {
    static final Identifier MODIFIER = Yolt.id("elytra_gravity");
    private static final double TICKS_PER_SECOND = 20.0;
    // An attached rocket sits on the player at its own tick, and the player may have moved since
    private static final double ROCKET_REACH = 8.0;
    private static final double SAME = 1e-9;

    private Elytra() {}

    static void init() {
        Hardcore.onTick(Elytra::tick);
        Hardcore.onExempt(Elytra::clear);
    }

    /** True while the player glides faster than the rocket cut-off. */
    public static boolean pastCutoff(ServerPlayer player) {
        if (!Settings.on(c -> c.hardcore.elytra.enabled) || StandIn.is(player) || Hardcore.exempt(player)
                || !player.isFallFlying()) {
            return false;
        }
        double cutoff = Settings.get().hardcore.elytra.rocketCutoffSpeed;
        return cutoff > 0 && speed(player) > cutoff;
    }

    /** True when a rocket fired now would only push for a tick before it is cut, so it isn't fired at all. */
    public static boolean refusesRocket(ServerPlayer player) {
        return pastCutoff(player);
    }

    // Horizontal metres per second. A player moves itself, so the server's own delta movement isn't their speed; the
    // move packet handler keeps what the client last moved in one tick instead.
    static double speed(ServerPlayer player) {
        return player.getKnownMovement().horizontalDistance() * TICKS_PER_SECOND;
    }

    private static void tick(ServerPlayer player) {
        HardcoreConfig.ElytraSection elytra = Settings.get().hardcore.elytra;
        if (!elytra.enabled || !player.isFallFlying()) {
            clear(player);
            return;
        }
        applyGravity(player, elytra.glideGravityMultiplier - 1.0);
        if (pastCutoff(player)) {
            endRockets(player);
        }
    }

    private static void applyGravity(ServerPlayer player, double amount) {
        AttributeInstance gravity = player.getAttribute(Attributes.GRAVITY);
        if (gravity == null) {
            return;
        }
        AttributeModifier current = gravity.getModifier(MODIFIER);
        if (current != null && Math.abs(current.amount() - amount) < SAME) {
            return;
        }
        gravity.addOrUpdateTransientModifier(new AttributeModifier(MODIFIER, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    private static void clear(ServerPlayer player) {
        AttributeInstance gravity = player.getAttribute(Attributes.GRAVITY);
        if (gravity != null && gravity.getModifier(MODIFIER) != null) {
            gravity.removeModifier(MODIFIER);
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
        return ((FireworkRocketInvoker) rocket).yoltIsAttachedToEntity() && rocket.getOwner() == player;
    }
}
