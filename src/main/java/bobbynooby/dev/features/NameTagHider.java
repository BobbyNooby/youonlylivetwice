package bobbynooby.dev.features;

import bobbynooby.dev.config.Settings;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

import static bobbynooby.dev.YouOnlyLiveTwice.TEAM_NAME;

public class NameTagHider {


    public static void registerInitTeams() {
        ServerLifecycleEvents.SERVER_STARTED.register(NameTagHider::apply);
    }

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
    }

    public static void registerAddPlayerToTeamOnJoin() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> addToTeam(server, handler.getPlayer()));

        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> addToTeam(destination.getServer(), player));
    }

    private static void addToTeam(MinecraftServer server, ServerPlayer player) {
        if (!Settings.active()) {
            return;
        }
        PlayerTeam team = server.getScoreboard().getPlayerTeam(TEAM_NAME);
        if (team != null && player.getTeam() != team) {
            server.getScoreboard().addPlayerToTeam(player.getScoreboardName(), team);
        }
    }
}
