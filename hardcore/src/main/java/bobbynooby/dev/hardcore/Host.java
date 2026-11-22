package bobbynooby.dev.hardcore;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * What the layer reads from its host, through one place: the hosting mod's values when a mod hosts this one, and the
 * mod's own otherwise. Each value is asked for at every use, never kept, since a host's config is live: under YOLT a
 * reload or /yolt config change applies at once.
 */
final class Host {
    private static volatile HardcoreHost current = YoltHardcore.OWN;

    private Host() {}

    // Once, from YoltHardcore.hostedBy, before any hook is registered
    static void use(HardcoreHost host) {
        current = host;
    }

    /** The name of the mod whose settings these are, for the log. */
    static String name() {
        return current.name();
    }

    /** True while the host's rules apply on this server. */
    static boolean active() {
        return current.active();
    }

    /**
     * True when the rules apply and this switch is on, read from the settings in use at the moment of the call. Off
     * while they don't apply, whatever the switch says, as in a single-player world under YOLT.
     */
    static boolean on(Predicate<HardcoreConfig.HardcoreSection> switchOn) {
        HardcoreHost host = current;
        return host.active() && switchOn.test(host.settings());
    }

    /** The settings in use now. */
    static HardcoreConfig.HardcoreSection settings() {
        return current.settings();
    }

    static BloodMoonTexts texts() {
        return current.texts();
    }

    /** True for a player entity with no client, such as a player body. */
    static boolean isStandIn(Entity entity) {
        return current.isStandIn(entity);
    }

    /** True for any logout body. */
    static boolean isBody(Entity entity) {
        return current.isBody(entity);
    }

    /** The player a logout body stands for. */
    static Optional<UUID> bodyOwner(LivingEntity entity) {
        return current.bodyOwner(entity);
    }
}
