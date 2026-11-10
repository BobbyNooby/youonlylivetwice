package bobbynooby.dev.visibility;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.mixin.SetPlayerTeamPacketInvoker;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Hides nametags by keeping every player in one server-side team. With hide.distantPlayers on, each client is only
 * told about the members whose entry it has, since the member list is a list of names. Server thread only.
 */
public final class NameTagTeam {
    private NameTagTeam() {}

    public static final String TEAM_NAME = "invisible_players";

    // ClientboundSetPlayerTeamPacket's method codes, which it keeps private
    private static final int CREATE = 0;
    private static final int CHANGE = 2;
    private static final int JOIN = 3;
    private static final int LEAVE = 4;

    // The names each client has in our team, keyed by connection like PlayerVisibility.
    // The client drops the connection when told to remove a name it doesn't have, so this must stay exact.
    private static final Map<ServerGamePacketListenerImpl, Set<String>> MEMBERS = new WeakHashMap<>();

    // Players stay in the team either way, so a reload can flip the rule for everyone at once
    public static void apply(MinecraftServer server) {
        if (!Settings.active()) {
            return;
        }
        PlayerTeam team = server.getScoreboard().getPlayerTeam(TEAM_NAME);
        if (team == null) {
            team = server.getScoreboard().addPlayerTeam(TEAM_NAME);
        }
        // Everyone shares this team, so seeing invisible teammates would show every invisible player
        team.setSeeFriendlyInvisibles(false);
        team.setNameTagVisibility(Settings.get().hide.nametags ? Team.Visibility.NEVER : Team.Visibility.ALWAYS);
        // Any other rule sends death messages straight to team members, past the game-message hide
        team.setDeathMessageVisibility(Team.Visibility.ALWAYS);
        // Joining is the only other way in, so a team removed with /team remove is refilled here
        for (ServerPlayer player : StandIn.without(server.getPlayerList().getPlayers())) {
            if (player.getTeam() != team) {
                server.getScoreboard().addPlayerToTeam(player.getScoreboardName(), team);
            }
        }
    }

    public static void init() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> addToTeam(server, handler.getPlayer()));

        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> addToTeam(destination.getServer(), player));
    }

    /** Player bodies never join, so they are put in the team here too and their nametags hide the same way. */
    public static void addToTeam(MinecraftServer server, ServerPlayer player) {
        if (!Settings.active()) {
            return;
        }
        PlayerTeam team = server.getScoreboard().getPlayerTeam(TEAM_NAME);
        if (team != null && player.getTeam() != team) {
            server.getScoreboard().addPlayerToTeam(player.getScoreboardName(), team);
        }
    }

    public static boolean isOurs(PlayerTeam team) {
        return team.getName().equals(TEAM_NAME);
    }

    /** Our team as a joining client first gets it. Fabric's join event has already put the joiner in it. */
    public static ClientboundSetPlayerTeamPacket joinPacket(ServerPlayer joiner, PlayerTeam team) {
        Set<String> members = members(joiner, team.getPlayers());
        reset(joiner, members);
        return create(joiner, new ClientboundSetPlayerTeamPacket.Parameters(team), members);
    }

    /** Adds a player the viewer's client just got the entry for. Works for players outside the player list too. */
    public static void onKnown(ServerPlayer viewer, ServerPlayer seen) {
        if (!Settings.active() || !perViewer()) {
            return;
        }
        PlayerTeam team = team(viewer);
        String name = seen.getScoreboardName();
        // Only the server's members, so this client's teams never disagree with the server's broadcasts
        if (team != null && team.getPlayers().contains(name) && state(viewer).add(name)) {
            viewer.connection.send(players(JOIN, List.of(name)));
        }
    }

    /** Removes a player whose entry the viewer's client just dropped. */
    public static void onForgotten(ServerPlayer viewer, ServerPlayer seen) {
        if (!Settings.active() || !perViewer()) {
            return;
        }
        String name = seen.getScoreboardName();
        // A player body has its owner's name, so the name stays while the client still knows either of them
        if (!knownNames(viewer).contains(name) && state(viewer).remove(name)) {
            viewer.connection.send(players(LEAVE, List.of(name)));
        }
    }

    /** Sends the viewer our whole team again, after admin view or the switches change. */
    public static void refresh(ServerPlayer viewer) {
        if (!Settings.active()) {
            return;
        }
        PlayerTeam team = team(viewer);
        if (team == null) {
            return;
        }
        Set<String> members = members(viewer, team.getPlayers());
        reset(viewer, members);
        // Starting over drops whatever names this client had before
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createRemovePacket(team));
        viewer.connection.send(create(viewer, new ClientboundSetPlayerTeamPacket.Parameters(team), members));
    }

    /**
     * Sends a broadcast packet about our team to each player, cut down to the names that player's client may have.
     * Returns false, sending nothing, for any other packet.
     */
    public static boolean broadcast(List<ServerPlayer> players, Packet<?> packet) {
        if (!(packet instanceof ClientboundSetPlayerTeamPacket team) || !team.getName().equals(TEAM_NAME)) {
            return false;
        }
        // In both modes, so each client's names stay exact and each viewer keeps its own nametag rule
        for (ServerPlayer viewer : players) {
            send(viewer, team);
        }
        return true;
    }

    private static void send(ServerPlayer viewer, ClientboundSetPlayerTeamPacket packet) {
        Set<String> state = state(viewer);
        if (packet.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
            state.clear();
            viewer.connection.send(packet);
            return;
        }
        if (packet.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD) {
            Set<String> members = members(viewer, packet.getPlayers());
            reset(viewer, members);
            viewer.connection.send(create(viewer, packet.getParameters().orElseThrow(), members));
            return;
        }
        if (packet.getParameters().isPresent()) {
            viewer.connection.send(SetPlayerTeamPacketInvoker.create(TEAM_NAME, CHANGE,
                    Optional.of(forViewer(viewer, packet.getParameters().get())), List.of()));
            return;
        }
        boolean joining = packet.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD;
        List<String> names = new ArrayList<>();
        for (String name : joining ? members(viewer, packet.getPlayers()) : packet.getPlayers()) {
            if (joining ? state.add(name) : state.remove(name)) {
                names.add(name);
            }
        }
        if (!names.isEmpty()) {
            viewer.connection.send(players(joining ? JOIN : LEAVE, names));
        }
    }

    // With the switch off every client knows everyone, so the team stays vanilla's shared one
    private static boolean perViewer() {
        return Settings.get().hide.distantPlayers;
    }

    // The names out of these that this viewer's client may have: all of them when shared, otherwise the ones it knows
    private static Set<String> members(ServerPlayer viewer, Collection<String> names) {
        Set<String> members = new LinkedHashSet<>(names);
        if (perViewer()) {
            members.retainAll(knownNames(viewer));
        }
        return members;
    }

    // The viewer's own name and the name of each player whose entry its client has
    private static Set<String> knownNames(ServerPlayer viewer) {
        Set<String> names = new HashSet<>();
        names.add(viewer.getScoreboardName());
        PlayerList playerList = viewer.level().getServer().getPlayerList();
        for (UUID id : PlayerVisibility.known(viewer)) {
            ServerPlayer player = playerList.getPlayer(id);
            // Player bodies are players outside the player list, in the viewer's level when it knows them
            if (player == null && viewer.level().getEntity(id) instanceof ServerPlayer body) {
                player = body;
            }
            if (player != null) {
                names.add(player.getScoreboardName());
            }
        }
        return names;
    }

    // Admin view shows every nametag
    private static ClientboundSetPlayerTeamPacket.Parameters forViewer(ServerPlayer viewer, ClientboundSetPlayerTeamPacket.Parameters server) {
        Team.Visibility rule = AdminView.isOn(viewer) ? Team.Visibility.ALWAYS : server.nameTagVisibility();
        return new ClientboundSetPlayerTeamPacket.Parameters(server.displayName(), server.playerPrefix(), server.playerSuffix(), rule,
                server.collisionRule(), server.color(), server.options());
    }

    private static ClientboundSetPlayerTeamPacket create(ServerPlayer viewer, ClientboundSetPlayerTeamPacket.Parameters server,
                                                         Collection<String> members) {
        return SetPlayerTeamPacketInvoker.create(TEAM_NAME, CREATE, Optional.of(forViewer(viewer, server)), members);
    }

    private static ClientboundSetPlayerTeamPacket players(int method, Collection<String> names) {
        return SetPlayerTeamPacketInvoker.create(TEAM_NAME, method, Optional.empty(), names);
    }

    private static @Nullable PlayerTeam team(ServerPlayer viewer) {
        return viewer.level().getServer().getScoreboard().getPlayerTeam(TEAM_NAME);
    }

    private static Set<String> state(ServerPlayer viewer) {
        return MEMBERS.computeIfAbsent(viewer.connection, connection -> new HashSet<>());
    }

    private static void reset(ServerPlayer viewer, Set<String> members) {
        Set<String> state = state(viewer);
        state.clear();
        state.addAll(members);
    }
}
