package bobbynooby.dev.hardcore;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
    // The namespace of every id the layer saves or reads: YOLT's mod id, which it had while the layer was part of YOLT.
    // The modifiers are saved on mobs and the tags may be overridden by a data pack, so it stays.
    static final String NAMESPACE = "you-only-live-twice";

    private static final List<Consumer<ServerPlayer>> TICKS = new ArrayList<>();
    private static final List<Consumer<ServerPlayer>> REFRESHES = new ArrayList<>();
    private static final List<Consumer<ServerPlayer>> EXEMPTS = new ArrayList<>();
    // Copy on write, so a listener added while a death is passed on can't upset the loop
    private static final List<BiConsumer<UUID, GameType>> DEATHS = new CopyOnWriteArrayList<>();

    private Hardcore() {}

    // Once, from YoltHardcore: inside the host's call when a mod hosts this one, so each hook lands where it wants
    static void init() {
        HardcoreStates.init();
        Weight.init();
        Legs.init();
        Limp.init();
        BedRest.init();
        Curse.init();
        Nutrients.init();
        HungerTax.init();
        Mending.init();
        Elytra.init();
        Anvils.init();
        BloodMoon.init();
        Cold.init();
        ServerTickEvents.END_SERVER_TICK.register(Hardcore::tick);
        ServerPlayerEvents.JOIN.register(Hardcore::refresh);
        // A respawn makes a new player object without the old one's transient modifiers, the End exit's included
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> refresh(newPlayer));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> refresh(player));
        // A player body's death reaches its owner through the offline death instead
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player && !Host.isStandIn(player)) {
                died(player.getUUID(), player.gameMode());
            }
        });
    }

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }

    /** True for a player outside the hardcore rules: anyone not in survival or adventure. */
    public static boolean exempt(ServerPlayer player) {
        return !player.gameMode().isSurvival();
    }

    /** True while any hardcore feature is switched on. */
    public static boolean anyOn() {
        return Host.on(c -> c.weight.enabled || c.legs.enabled || c.limp.enabled
                || c.bedRest.enabled || c.curse.enabled
                || c.nutrients.enabled || c.hungerTax.multiplier != 1.0
                || c.mending.enabled || c.elytra.enabled || c.anvil.enabled
                || c.tougherMobs.enabled || c.relentlessMobs.enabled
                || c.bloodMoon.enabled || c.cold.enabled);
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
     * Runs after every player death: an online one as it happens, and an offline one a host passes on through died
     * (under YOLT, a body killed, or /yolt kill of someone away, once the dead save is written). It gets the player's
     * game mode at death, creative and spectator included, and decides for itself whether that death counts. It runs on
     * the server thread, whether or not any feature is on, and a failure in it is logged and skipped.
     */
    public static void onDeath(BiConsumer<UUID, GameType> listener) {
        DEATHS.add(listener);
    }

    /** Passes a death on to every death listener: a host's offline death, or an online one from the death event. */
    public static void died(UUID uuid, GameType mode) {
        if (!Host.active()) {
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
        if (!Host.active()) {
            return;
        }
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            guarded(Hardcore::tickPlayer, player, "Could not run the hardcore tick for {}");
        }
    }

    private static void tickPlayer(ServerPlayer player) {
        if (exempt(player)) {
            leaveRules(player);
            return;
        }
        for (Consumer<ServerPlayer> perPlayer : TICKS) {
            guarded(perPlayer, player, "A hardcore tick failed for {}");
        }
        // Last, so it sees what this tick's features changed
        Slowdowns.refresh(player);
    }

    private static void refresh(ServerPlayer player) {
        if (!Host.active()) {
            return;
        }
        guarded(Hardcore::refreshPlayer, player, "Could not put the hardcore modifiers back on {}");
    }

    private static void refreshPlayer(ServerPlayer player) {
        if (exempt(player)) {
            leaveRules(player);
            return;
        }
        for (Consumer<ServerPlayer> reapply : REFRESHES) {
            guarded(reapply, player, "A hardcore refresh failed for {}");
        }
        Slowdowns.refresh(player);
    }

    private static void leaveRules(ServerPlayer player) {
        Slowdowns.clear(player);
        for (Consumer<ServerPlayer> remove : EXEMPTS) {
            guarded(remove, player, "A hardcore exempt hook failed for {}");
        }
    }

    // What Guard.run does, but the player's name for the message is only looked up once a hook has thrown. These run for
    // every player and every hook each tick, where making the name and a lambda each time cost more than most hooks do.
    private static void guarded(Consumer<ServerPlayer> hook, ServerPlayer player, String message) {
        try {
            hook.accept(player);
        } catch (RuntimeException e) {
            // SLF4J takes a trailing Throwable as the exception, not as an argument
            YoltHardcore.LOGGER.error(message, player.nameAndId().name(), e);
        }
    }
}
