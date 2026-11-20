package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.Yolt;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Every player's hardcore state, online or not. An online player's is cached and written back when they leave and every
 * few minutes; an offline player's is read and written straight away. The store's file is only made on the first write,
 * so a server that never changes a state never gets one. If it can't be opened, states live in memory until the server
 * stops.
 */
public final class HardcoreStates {
    // Five minutes, so a crash loses little
    static final int FLUSH_TICKS = 6000;

    private static final class Cached {
        final HardcoreState state;
        // What the store holds for it; the state is dirty when it encodes to something else
        String saved;
        // True for a state standing in for one the store couldn't give. It is never saved, since that would put it
        // over the player's real one, and each use tries the read again.
        final boolean unread;

        Cached(HardcoreState state, String saved, boolean unread) {
            this.state = state;
            this.saved = saved;
            this.unread = unread;
        }
    }

    // A state as far as it is known. Unread when the store is there but couldn't give the player's row.
    private record Loaded(HardcoreState state, boolean unread) {
    }

    private static @Nullable Path file;
    private static @Nullable HardcoreStore store;
    // Set once opening failed, so the error is logged once and the rest of the session runs from memory
    private static boolean failed;
    private static final Map<UUID, Cached> ONLINE = new HashMap<>();
    // Writes the store couldn't take, newest first over whatever the store holds
    private static final Map<UUID, String> UNSAVED_PLAYERS = new HashMap<>();
    // Changes made to a player whose row couldn't be read, in order. They are made again on the real row once it can
    // be, and until then nothing is written for the player: a fresh state with these changes would wipe the rest.
    private static final Map<UUID, List<Consumer<HardcoreState>>> UNREAD_CHANGES = new HashMap<>();
    private static final Map<String, Long> UNSAVED_WORLD = new HashMap<>();

    private HardcoreStates() {}

    static void init() {
        // Before the worlds load, so bodies made again at start-up read their owner's state. Settings decides whether
        // YOLT is active in an earlier listener of the same event.
        ServerLifecycleEvents.SERVER_STARTING.register(started -> {
            if (!Settings.active()) {
                return;
            }
            useFile(started.getWorldPath(LevelResource.ROOT).resolve("database/" + Yolt.MOD_ID + "-hardcore.db"));
        });
        ServerPlayerEvents.LEAVE.register(player -> Guard.run(() -> leave(player),
                "Could not save the hardcore state of {}", player.nameAndId().name()));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % FLUSH_TICKS == 0) {
                Guard.run(HardcoreStates::flushAll, "Could not save the hardcore states");
            }
        });
        // Not SERVER_STOPPING: players are still logged out after that
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> Guard.run(() -> useFile(null),
                "Could not close the hardcore store"));
    }

    /** The player's live state, loaded on first use. Changes go through update, which marks it to be saved. */
    public static synchronized HardcoreState of(ServerPlayer player) {
        return cached(player.getUUID()).state;
    }

    /** Changes a player's state: an online one's in the cache, an offline one's in the store at once. */
    public static synchronized void update(UUID uuid, Consumer<HardcoreState> change) {
        Cached online = ONLINE.get(uuid);
        if (online != null) {
            change.accept(online.state);
            if (online.unread) {
                UNREAD_CHANGES.computeIfAbsent(uuid, id -> new ArrayList<>()).add(change);
            }
            return;
        }
        Loaded loaded = load(uuid);
        change.accept(loaded.state());
        if (loaded.unread()) {
            UNREAD_CHANGES.computeIfAbsent(uuid, id -> new ArrayList<>()).add(change);
            UNSAVED_PLAYERS.put(uuid, loaded.state().encode());
            return;
        }
        savePlayer(uuid, loaded.state().encode());
    }

    /** A copy of the player's state, whether or not they are online. */
    public static synchronized HardcoreState read(UUID uuid) {
        Cached online = ONLINE.get(uuid);
        return online != null ? online.state.copy() : load(uuid).state();
    }

    static synchronized void flush(ServerPlayer player) {
        flush(player.getUUID());
    }

    /** Writes an online player's state now if it changed, rather than at their leave or the next flush. */
    static synchronized void flush(UUID uuid) {
        Cached online = ONLINE.get(uuid);
        if (online != null) {
            save(uuid, online);
        }
    }

    public static synchronized OptionalLong world(String key) {
        Long unsaved = UNSAVED_WORLD.get(key);
        if (unsaved != null) {
            return OptionalLong.of(unsaved);
        }
        HardcoreStore open = readableStore();
        if (open == null) {
            return OptionalLong.empty();
        }
        try {
            return open.world(key).map(value -> OptionalLong.of(Long.parseLong(value))).orElse(OptionalLong.empty());
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not read the hardcore world value {}", key, e);
            return OptionalLong.empty();
        }
    }

    public static synchronized void setWorld(String key, long value) {
        HardcoreStore open = writableStore();
        if (open != null) {
            try {
                open.saveWorld(key, Long.toString(value));
                UNSAVED_WORLD.remove(key);
                return;
            } catch (RuntimeException e) {
                Yolt.LOGGER.error("Could not save the hardcore world value {}, keeping it in memory", key, e);
            }
        }
        UNSAVED_WORLD.put(key, value);
    }

    /** Uses this store in place of the world's file; null goes back to the file. It is closed with the next reset or stop. */
    public static synchronized void useStoreForTesting(@Nullable HardcoreStore replacement) {
        store = replacement;
        failed = false;
    }

    /** Forgets every state and points the store at this file, as a server start does; null leaves it with none. */
    static synchronized void useFileForTesting(@Nullable Path dbFile) {
        useFile(dbFile);
    }

    private static synchronized void useFile(@Nullable Path dbFile) {
        flushAll();
        HardcoreStore open = store;
        store = null;
        failed = false;
        ONLINE.clear();
        if (!UNREAD_CHANGES.isEmpty()) {
            Yolt.LOGGER.error("The hardcore states of {} could never be read, so the changes made to them are lost",
                    UNREAD_CHANGES.keySet());
        }
        UNREAD_CHANGES.clear();
        UNSAVED_PLAYERS.clear();
        UNSAVED_WORLD.clear();
        file = dbFile;
        if (open != null) {
            try {
                open.close();
                Yolt.LOGGER.info("Closed hardcore store");
            } catch (RuntimeException e) {
                Yolt.LOGGER.error("Could not close the hardcore store", e);
            }
        }
    }

    private static synchronized void leave(ServerPlayer player) {
        flush(player);
        ONLINE.remove(player.getUUID());
    }

    /**
     * Writes every changed online state, and the writes that failed before, such as on a file a backup tool held, in one
     * transaction. A failure stops at the first write and keeps everything for the next flush, so a locked file costs the
     * server thread one wait, not one per player.
     */
    static synchronized void flushAll() {
        // Online states over older failed writes of the same player, since they are newer
        Map<UUID, String> players = new LinkedHashMap<>();
        for (UUID uuid : List.copyOf(UNSAVED_PLAYERS.keySet())) {
            // One whose row couldn't be read is read again first, and waits if it still can't be
            if (!UNREAD_CHANGES.containsKey(uuid) || !load(uuid).unread()) {
                players.put(uuid, UNSAVED_PLAYERS.get(uuid));
            }
        }
        ONLINE.forEach((uuid, online) -> {
            String now = online.state.encode();
            if (!online.unread && !now.equals(online.saved)) {
                players.put(uuid, now);
            }
        });
        Map<String, String> world = new LinkedHashMap<>();
        UNSAVED_WORLD.forEach((key, value) -> world.put(key, Long.toString(value)));
        if (players.isEmpty() && world.isEmpty()) {
            return;
        }
        HardcoreStore open = writableStore();
        if (open == null) {
            return;
        }
        try {
            open.saveAll(players, world);
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not save {} hardcore states and {} world values, keeping them for the next save",
                    players.size(), world.size(), e);
            return;
        }
        players.forEach((uuid, data) -> {
            UNSAVED_PLAYERS.remove(uuid);
            Cached online = ONLINE.get(uuid);
            // The store now holds this, so a live state that has since gone back to the old saved one still gets written
            if (online != null) {
                online.saved = data;
            }
        });
        world.keySet().forEach(UNSAVED_WORLD::remove);
    }

    private static void save(UUID uuid, Cached online) {
        if (online.unread) {
            return;
        }
        String now = online.state.encode();
        // Left unsaved on a failed write, so the next flush tries again
        if (!now.equals(online.saved) && savePlayer(uuid, now)) {
            online.saved = now;
        }
    }

    private static Cached cached(UUID uuid) {
        Cached online = ONLINE.get(uuid);
        if (online == null || online.unread) {
            Loaded loaded = load(uuid);
            if (!loaded.unread() || online == null) {
                if (online != null) {
                    Yolt.LOGGER.warn("The hardcore state of {} could be read again; what changed meanwhile is lost, but for"
                            + " deaths and totems", uuid);
                }
                // Saved as it was loaded, so a state nothing changed is never written
                online = new Cached(loaded.state(), loaded.state().encode(), loaded.unread());
                ONLINE.put(uuid, online);
            }
        }
        return online;
    }

    private static Loaded load(UUID uuid) {
        List<Consumer<HardcoreState>> changes = UNREAD_CHANGES.get(uuid);
        String unsaved = UNSAVED_PLAYERS.get(uuid);
        if (changes == null && unsaved != null) {
            return new Loaded(HardcoreState.decode(unsaved), false);
        }
        HardcoreStore open = readableStore();
        if (open == null) {
            // No store at all: nothing is stored that a save could wipe
            return new Loaded(unsaved != null ? HardcoreState.decode(unsaved) : new HardcoreState(), false);
        }
        HardcoreState state;
        try {
            state = open.player(uuid).map(HardcoreState::decode).orElseGet(HardcoreState::new);
        } catch (RuntimeException e) {
            Yolt.LOGGER.error("Could not read the hardcore state of {}; nothing is saved over it until it can be read", uuid, e);
            return new Loaded(unsaved != null ? HardcoreState.decode(unsaved) : new HardcoreState(), true);
        }
        if (changes != null) {
            // The row is back: the changes made without it are made on it, and it waits for the next save
            changes.forEach(change -> change.accept(state));
            UNREAD_CHANGES.remove(uuid);
            UNSAVED_PLAYERS.put(uuid, state.encode());
        }
        return new Loaded(state, false);
    }

    // True when the store took it; otherwise it is kept in memory
    private static boolean savePlayer(UUID uuid, String data) {
        HardcoreStore open = writableStore();
        if (open != null) {
            try {
                open.savePlayer(uuid, data);
                UNSAVED_PLAYERS.remove(uuid);
                return true;
            } catch (RuntimeException e) {
                Yolt.LOGGER.error("Could not save the hardcore state of {}, keeping it in memory", uuid, e);
            }
        }
        UNSAVED_PLAYERS.put(uuid, data);
        return false;
    }

    // A read never makes the file: with none yet, there is nothing stored
    private static @Nullable HardcoreStore readableStore() {
        if (store == null && file != null && Files.exists(file)) {
            return writableStore();
        }
        return store;
    }

    private static @Nullable HardcoreStore writableStore() {
        if (store != null || failed || file == null) {
            return store;
        }
        try {
            store = HardcoreStore.open(file);
            Yolt.LOGGER.info("Opened hardcore store");
        } catch (RuntimeException e) {
            failed = true;
            Yolt.LOGGER.error("Could not open the hardcore store, hardcore states will last only until the server stops", e);
        }
        return store;
    }
}
