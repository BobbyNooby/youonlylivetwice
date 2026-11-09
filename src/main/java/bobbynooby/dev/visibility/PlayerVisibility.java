package bobbynooby.dev.visibility;

import bobbynooby.dev.bodies.PlayerBody;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.features.NameTagHider;
import bobbynooby.dev.mixin.PlayerInfoUpdatePacketAccessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Decides which players each client has a player-list entry for, so a client only learns the names of players near it. */
public final class PlayerVisibility {
    // Keyed by connection: it lives exactly as long as the client, and a respawn keeps it
    private static final Map<ServerGamePacketListenerImpl, Viewer> VIEWERS = new WeakHashMap<>();

    private PlayerVisibility() {}

    private static final class Viewer {
        // Entries this client has
        final Set<UUID> known = new HashSet<>();
        // Players whose entity this client is tracking
        final Set<UUID> tracked = new HashSet<>();
    }

    /** The players whose entries this viewer's client has, as a live read-only view. */
    public static Set<UUID> known(ServerPlayer viewer) {
        Viewer state = viewer.connection == null ? null : VIEWERS.get(viewer.connection);
        return state == null ? Set.of() : Collections.unmodifiableSet(state.known);
    }

    /** Vanilla sends a joiner everyone already online; this keeps only the ones the joiner may know about. */
    public static ClientboundPlayerInfoUpdatePacket joinPacket(ServerPlayer joiner, Collection<ServerPlayer> online) {
        // Everyone else reaches this client when tracking starts
        List<ServerPlayer> told = seesEveryone(joiner) ? List.copyOf(online) : List.of();
        Viewer state = state(joiner);
        for (ServerPlayer player : told) {
            state.known.add(player.getUUID());
        }
        return addPacket(joiner, told);
    }

    /** Sends the entry for a player whose entity the viewer starts tracking. Runs before the spawn packet. */
    public static void onPairingStart(ServerPlayer viewer, ServerPlayer seen) {
        // A player body tracks entities like a player but has no client to tell, so it never gets any state here
        if (viewer instanceof PlayerBody) {
            return;
        }
        Viewer state = state(viewer);
        state.tracked.add(seen.getUUID());
        if (state.known.add(seen.getUUID())) {
            viewer.connection.send(addPacket(viewer, List.of(seen)));
        }
        // Also for viewers who already knew them, since the nametag only matters once the player is in sight
        NameTagHider.onKnown(viewer, seen);
    }

    /** Removes the entry once the viewer stops tracking that player's entity. */
    public static void onPairingStop(ServerPlayer viewer, ServerPlayer seen) {
        if (viewer instanceof PlayerBody) {
            return;
        }
        Viewer state = state(viewer);
        state.tracked.remove(seen.getUUID());
        // A player outside the player list (a plan 04 body) would otherwise stay on as an online name.
        // A leaving or respawning player is still in it here, so viewers who see everyone keep them.
        boolean online = seen.level().getServer().getPlayerList().getPlayer(seen.getUUID()) != null;
        if ((!seesEveryone(viewer) || !online) && state.known.remove(seen.getUUID())) {
            viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(seen.getUUID())));
            NameTagHider.onForgotten(viewer, seen);
        }
    }

    /** Brings the viewer's entries in line with the current rules, after admin view or the switches change. */
    public static void refresh(ServerPlayer viewer) {
        if (!Settings.active()) {
            return;
        }
        Viewer state = state(viewer);
        Map<UUID, ServerPlayer> wanted = new LinkedHashMap<>();
        wanted.put(viewer.getUUID(), viewer);
        if (seesEveryone(viewer)) {
            for (ServerPlayer player : viewer.level().getServer().getPlayerList().getPlayers()) {
                wanted.put(player.getUUID(), player);
            }
        }
        // Tracked players share the viewer's level, which also finds players outside the player list
        for (UUID id : state.tracked) {
            if (viewer.level().getEntity(id) instanceof ServerPlayer player) {
                wanted.put(id, player);
            }
        }

        List<UUID> gone = new ArrayList<>();
        for (UUID id : state.known) {
            if (!wanted.containsKey(id)) {
                gone.add(id);
            }
        }
        if (!gone.isEmpty()) {
            gone.forEach(state.known::remove);
            viewer.connection.send(new ClientboundPlayerInfoRemovePacket(gone));
        }

        List<ServerPlayer> added = new ArrayList<>();
        List<ServerPlayer> kept = new ArrayList<>();
        for (ServerPlayer player : wanted.values()) {
            if (state.known.add(player.getUUID())) {
                added.add(player);
            } else {
                kept.add(player);
            }
        }
        if (!added.isEmpty()) {
            viewer.connection.send(addPacket(viewer, added));
        }
        // Whether entries show in the tab list may have changed too
        if (!kept.isEmpty()) {
            viewer.connection.send(withListed(viewer, new ClientboundPlayerInfoUpdatePacket(
                    EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED), kept)));
        }
        // The nametag team lists names too, so it follows the entries
        NameTagHider.refresh(viewer);
    }

    /**
     * Sends a broadcast player-info packet to each player, cut down to the entries that player's client may have.
     * Returns false, sending nothing, for any other packet.
     */
    public static boolean broadcast(List<ServerPlayer> players, Packet<?> packet) {
        if (packet instanceof ClientboundPlayerInfoUpdatePacket update) {
            for (ServerPlayer viewer : players) {
                sendUpdate(viewer, update);
            }
            return true;
        }
        if (packet instanceof ClientboundPlayerInfoRemovePacket remove) {
            for (ServerPlayer viewer : players) {
                sendRemove(viewer, remove);
            }
            return true;
        }
        return false;
    }

    private static void sendUpdate(ServerPlayer viewer, ClientboundPlayerInfoUpdatePacket packet) {
        Viewer state = state(viewer);
        // Vanilla only broadcasts new entries for a joining player
        boolean adding = packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER);
        boolean everyone = seesEveryone(viewer);
        List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new ArrayList<>();
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
            UUID id = entry.profileId();
            boolean newForViewer = adding && (everyone || id.equals(viewer.getUUID()));
            if (newForViewer) {
                state.known.add(id);
            }
            if (newForViewer || state.known.contains(id)) {
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            return;
        }
        ClientboundPlayerInfoUpdatePacket sent = entries.size() == packet.entries().size() ? packet : withEntries(packet, entries);
        if (packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED)) {
            sent = withListed(viewer, sent);
        }
        viewer.connection.send(sent);
    }

    private static void sendRemove(ServerPlayer viewer, ClientboundPlayerInfoRemovePacket packet) {
        Viewer state = state(viewer);
        List<UUID> gone = new ArrayList<>();
        for (UUID id : packet.profileIds()) {
            if (state.known.remove(id)) {
                gone.add(id);
            }
        }
        if (!gone.isEmpty()) {
            viewer.connection.send(gone.size() == packet.profileIds().size() ? packet : new ClientboundPlayerInfoRemovePacket(gone));
        }
    }

    private static Viewer state(ServerPlayer viewer) {
        return VIEWERS.computeIfAbsent(viewer.connection, connection -> new Viewer());
    }

    // With the switch off every client gets every entry, as vanilla does
    private static boolean seesEveryone(ServerPlayer viewer) {
        return !Settings.get().hide.distantPlayers || AdminView.isOn(viewer);
    }

    // Vanilla's join packet, so the client reads the entry and the player's chat session exactly as vanilla sends them
    private static ClientboundPlayerInfoUpdatePacket addPacket(ServerPlayer viewer, Collection<ServerPlayer> players) {
        return withListed(viewer, ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(players));
    }

    private static ClientboundPlayerInfoUpdatePacket withListed(ServerPlayer viewer, ClientboundPlayerInfoUpdatePacket packet) {
        // Admin view's full tab list doesn't depend on hide.distantPlayers
        boolean listed = !Settings.get().hide.tabList || AdminView.isOn(viewer);
        PlayerList playerList = viewer.level().getServer().getPlayerList();
        List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new ArrayList<>(packet.entries().size());
        for (ClientboundPlayerInfoUpdatePacket.Entry e : packet.entries()) {
            // Plan 04's bodies are players outside the player list, and never show in the tab list
            boolean show = listed && playerList.getPlayer(e.profileId()) != null;
            entries.add(e.listed() == show ? e : new ClientboundPlayerInfoUpdatePacket.Entry(e.profileId(), e.profile(), show,
                    e.latency(), e.gameMode(), e.displayName(), e.showHat(), e.listOrder(), e.chatSession()));
        }
        return withEntries(packet, entries);
    }

    // The packet has no constructor that takes entries
    private static ClientboundPlayerInfoUpdatePacket withEntries(ClientboundPlayerInfoUpdatePacket packet,
                                                                 List<ClientboundPlayerInfoUpdatePacket.Entry> entries) {
        ClientboundPlayerInfoUpdatePacket copy = new ClientboundPlayerInfoUpdatePacket(packet.actions(), List.of());
        ((PlayerInfoUpdatePacketAccessor) copy).setEntries(entries);
        return copy;
    }
}
