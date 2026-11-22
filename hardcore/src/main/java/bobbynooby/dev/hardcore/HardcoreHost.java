package bobbynooby.dev.hardcore;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Optional;
import java.util.UUID;

/**
 * What a mod that hosts YOLT Hardcore hands it, each read at every use, so a host can hand out its live config. A host
 * says so in its fabric.mod.json, with the custom value {@code "yolt-hardcore:host": true}, and calls
 * {@link YoltHardcore#hostedBy} from its own onInitialize.
 * <p>
 * While hosted the mod registers no entity-load hook, so a host calls {@link MobBoosts#onLoad} for each mob as it
 * loads, from an entity-load hook of its own, in the order it needs: YOLT boosts a mob before it gives it the goal that
 * hunts bodies.
 */
public interface HardcoreHost {
    /** The host's name, for the log, such as "You Only Live Twice". */
    String name();

    /** False in a world the host's rules are off in, which keeps every feature off there. */
    boolean active();

    /** The hardcore settings in use now. */
    HardcoreConfig.HardcoreSection settings();

    /** What the blood moon tells players. */
    BloodMoonTexts texts();

    /** True for a player entity with no client, such as a player body. */
    boolean isStandIn(Entity entity);

    /** True for any logout body. */
    boolean isBody(Entity entity);

    /** The player a logout body stands for, or empty for anything else. */
    Optional<UUID> bodyOwner(LivingEntity entity);
}
