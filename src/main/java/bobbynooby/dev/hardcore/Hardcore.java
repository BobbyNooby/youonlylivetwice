package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.StandIn;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The hardcore layer's hub: one tick for every player who plays by its rules, and one hook that puts each feature's
 * modifiers back after a join, a respawn or a dimension change. Creative and spectator players are left out of those.
 * It also passes on every player death, online or offline, so features can reset what a new life resets.
 */
public final class Hardcore {
    private static final List<Consumer<ServerPlayer>> TICKS = new ArrayList<>();
    private static final List<Consumer<ServerPlayer>> REFRESHES = new ArrayList<>();
    private static final List<Consumer<ServerPlayer>> EXEMPTS = new ArrayList<>();
    // Copy on write, so a listener added while a death is passed on can't upset the loop
    private static final List<BiConsumer<UUID, GameType>> DEATHS = new CopyOnWriteArrayList<>();

    private Hardcore() {}

    public static void init() {
        HardcoreStates.init();
        Weight.init();
        Legs.init();
        Limp.init();
        BedRest.init();
        Curse.init();
        Nutrients.init();
        HungerTax.init();
        Mending.init();
        ServerTickEvents.END_SERVER_TICK.register(Hardcore::tick);
        ServerPlayerEvents.JOIN.register(Hardcore::refresh);
        // A respawn makes a new player object without the old one's transient modifiers, the End exit's included
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> refresh(newPlayer));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> refresh(player));
        // A player body's death reaches its owner through the offline death instead
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player && !StandIn.is(player)) {
                died(player.getUUID(), player.gameMode());
            }
        });
    }

    /** True for a player outside the hardcore rules: anyone not in survival or adventure. */
    public static boolean exempt(ServerPlayer player) {
        return !player.gameMode().isSurvival();
    }

    /** True while any hardcore feature is switched on. */
    public static boolean anyOn() {
        return Settings.on(c -> c.hardcore.weight.enabled || c.hardcore.legs.enabled || c.hardcore.limp.enabled
                || c.hardcore.bedRest.enabled || c.hardcore.curse.enabled
                || c.hardcore.nutrients.enabled || c.hardcore.hungerTax.multiplier != 1.0
                || c.hardcore.mending.enabled);
    }

    /** Runs at the end of every server tick for each online player who isn't exempt. */
    public static void onTick(Consumer<ServerPlayer> perPlayer) {
        TICKS.add(perPlayer);
    }

    /** Runs after a join, a respawn and a dimension change, so a feature can put its modifiers back. */
    public static void onRefresh(Consumer<ServerPlayer> reapply) {
        REFRESHES.add(reapply);
    }

    /**
     * Runs in place of the tick and the refresh for a player outside the rules, so a feature can take its modifiers
     * off. It runs every tick they stay exempt, so it must cost nothing when there is nothing to take off.
     */
    public static void onExempt(Consumer<ServerPlayer> remove) {
        EXEMPTS.add(remove);
    }

    /**
     * Runs after every player death: an online one as it happens, and an offline one (a body killed, or /yolt kill of
     * someone away) once the dead save is written. It gets the player's game mode at death, creative and spectator
     * included, and decides for itself whether that death counts. It runs on the server thread, whether or not any
     * feature is on, and a failure in it is logged and skipped.
     */
    public static void onDeath(BiConsumer<UUID, GameType> listener) {
        DEATHS.add(listener);
    }

    /** Passes a death on to every death listener. Called by the offline death; an online one comes from the death event. */
    public static void died(UUID uuid, GameType mode) {
        if (!Settings.active()) {
            return;
        }
        for (BiConsumer<UUID, GameType> listener : DEATHS) {
            Guard.run(() -> listener.accept(uuid, mode), "A hardcore death hook failed for {}", uuid);
        }
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
            leaveRules(player);
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
            leaveRules(player);
            return;
        }
        for (Consumer<ServerPlayer> reapply : REFRESHES) {
            Guard.run(() -> reapply.accept(player), "A hardcore refresh failed for {}", player.nameAndId().name());
        }
        Slowdowns.refresh(player);
    }

    private static void leaveRules(ServerPlayer player) {
        Slowdowns.clear(player);
        for (Consumer<ServerPlayer> remove : EXEMPTS) {
            Guard.run(() -> remove.accept(player), "A hardcore exempt hook failed for {}", player.nameAndId().name());
        }
    }
}
