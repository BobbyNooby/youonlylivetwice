package bobbynooby.dev.lockout;

import bobbynooby.dev.config.Settings;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/** How a player died, as the text their own death screen shows, for the death log. */
public final class DeathMessages {
    static final String KILLED_WHILE_AWAY = "Killed while away";

    // ServerPlayer.die clears the combat tracker before the death event runs, so a player's message is kept from the
    // start of die. Weak, so one whose death event never comes goes with its entity. Server thread only.
    private static final Map<LivingEntity, Component> KEPT = new WeakHashMap<>();

    private DeathMessages() {}

    /** Called as a player starts to die, while the combat tracker still knows how. */
    public static void keep(ServerPlayer player) {
        if (Settings.on(c -> c.deathLockout.enabled)) {
            KEPT.put(player, player.getCombatTracker().getDeathMessage());
        }
    }

    /** The death message of an entity whose death event is running. */
    public static String of(LivingEntity entity) {
        return message(entity).getString();
    }

    /** A body's death message told as its owner's, since a mannequin's own names the mannequin. */
    public static String ofBody(LivingEntity body, String owner) {
        return withVictim(message(body), owner).getString();
    }

    /** For a player who died while offline, with how their body died if it did. */
    public static String whileAway(@Nullable String bodyDeathMessage) {
        return bodyDeathMessage == null ? KILLED_WHILE_AWAY : KILLED_WHILE_AWAY + " (" + bodyDeathMessage + ")";
    }

    private static Component message(LivingEntity entity) {
        Component kept = KEPT.remove(entity);
        return kept != null ? kept : entity.getCombatTracker().getDeathMessage();
    }

    // Every vanilla death message names the one who died as its first argument
    private static Component withVictim(Component message, String name) {
        if (message.getContents() instanceof TranslatableContents translatable && translatable.getArgs().length > 0) {
            Object[] args = translatable.getArgs().clone();
            args[0] = Component.literal(name);
            return Component.translatableWithFallback(translatable.getKey(), translatable.getFallback(), args);
        }
        return message;
    }
}
