package bobbynooby.dev.world;

import bobbynooby.dev.config.Settings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRules;

/** The game rules the config forces on, applied at startup and by /yolt reload. */
public final class GameRuleSwitches {
    private GameRuleSwitches() {}

    // Also run by /yolt reload
    public static void applyImmediateRespawn(MinecraftServer server) {
        if (!Settings.active()) {
            return;
        }
        // The rule is saved with the world, so with the switch off we leave whatever the admin set
        if (Settings.get().immediateRespawn) {
            server.getGameRules().set(GameRules.IMMEDIATE_RESPAWN, true, server);
        }
    }

    // Also run by /yolt reload. The locator bar points at players far beyond sight, so it tells who is online.
    public static void applyLocatorBar(MinecraftServer server) {
        if (!Settings.active()) {
            return;
        }
        // Saved with the world too, so with the switch off we leave whatever the admin set
        if (Settings.get().hide.locatorBar) {
            // Passing the server drops the waypoints clients already have
            server.getGameRules().set(GameRules.LOCATOR_BAR, false, server);
        }
    }

    // Also run by /yolt reload. A wither spawning, the dragon dying or an End portal opening plays for every player,
    // from the event's direction, so it tells who is active and where.
    public static void applyGlobalSounds(MinecraftServer server) {
        if (!Settings.active()) {
            return;
        }
        // Saved with the world too, so with the switch off we leave whatever the admin set
        if (Settings.get().hide.globalSounds) {
            server.getGameRules().set(GameRules.GLOBAL_SOUND_EVENTS, false, server);
        }
    }
}
