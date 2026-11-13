package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The hardcore layer's hub: one tick for every player who plays by its rules, and one hook that puts each feature's
 * modifiers back after a join, a respawn or a dimension change. Creative and spectator players are left out.
 */
public final class Hardcore {
    private static final List<Consumer<ServerPlayer>> TICKS = new ArrayList<>();
    private static final List<Consumer<ServerPlayer>> REFRESHES = new ArrayList<>();

    private Hardcore() {}

    public static void init() {
        HardcoreStates.init();
        Weight.init();
        ServerTickEvents.END_SERVER_TICK.register(Hardcore::tick);
        ServerPlayerEvents.JOIN.register(Hardcore::refresh);
        // A respawn makes a new player object without the old one's transient modifiers, the End exit's included
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> refresh(newPlayer));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> refresh(player));
    }

    /** True for a player outside the hardcore rules: anyone not in survival or adventure. */
    public static boolean exempt(ServerPlayer player) {
        return !player.gameMode().isSurvival();
    }

    /** True while any hardcore feature is switched on. */
    public static boolean anyOn() {
        return Settings.on(c -> c.hardcore.weight.enabled);
    }

    /** Runs at the end of every server tick for each online player who isn't exempt. */
    public static void onTick(Consumer<ServerPlayer> perPlayer) {
        TICKS.add(perPlayer);
    }

    /** Runs after a join, a respawn and a dimension change, so a feature can put its modifiers back. */
    public static void onRefresh(Consumer<ServerPlayer> reapply) {
        REFRESHES.add(reapply);
    }

    /** A line over the hotbar for this player alone. */
    public static void actionBar(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text), true);
    }

    private static void tick(MinecraftServer server) {
        if (!Settings.active()) {
            return;
        }
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            Guard.run(() -> tickPlayer(player), "Could not run the hardcore tick for {}", player.nameAndId().name());
        }
    }

    private static void tickPlayer(ServerPlayer player) {
        if (exempt(player)) {
            Slowdowns.clear(player);
            return;
        }
        for (Consumer<ServerPlayer> perPlayer : TICKS) {
            Guard.run(() -> perPlayer.accept(player), "A hardcore tick failed for {}", player.nameAndId().name());
        }
        // Last, so it sees what this tick's features changed
        Slowdowns.refresh(player);
    }

    private static void refresh(ServerPlayer player) {
        if (!Settings.active()) {
            return;
        }
        Guard.run(() -> refreshPlayer(player), "Could not put the hardcore modifiers back on {}", player.nameAndId().name());
    }

    private static void refreshPlayer(ServerPlayer player) {
        if (exempt(player)) {
            Slowdowns.clear(player);
            return;
        }
        for (Consumer<ServerPlayer> reapply : REFRESHES) {
            Guard.run(() -> reapply.accept(player), "A hardcore refresh failed for {}", player.nameAndId().name());
        }
        Slowdowns.refresh(player);
    }
}
