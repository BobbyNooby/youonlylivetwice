package bobbynooby.dev.features;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Team;

import static bobbynooby.dev.YouOnlyLiveTwice.TEAM_NAME;

public class NameTagHider {


    public static void registerInitTeams() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var team = server.getScoreboard().getPlayerTeam(TEAM_NAME);
            if (team == null || team.canSeeFriendlyInvisibles()) {
                team = server.getScoreboard().addPlayerTeam(TEAM_NAME);
                team.setSeeFriendlyInvisibles(false);
                team.setNameTagVisibility(Team.Visibility.NEVER);
            }
        });
    }

    public static void registerAddPlayerToTeamOnJoin() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();

            if (player.getTeam() == null || !player.getTeam().getName().equals(TEAM_NAME))
                server.getScoreboard().addPlayerToTeam(player.getScoreboardName(), server.getScoreboard().getPlayerTeam(TEAM_NAME));
        });

        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
            if (player.getTeam() == null || !player.getTeam().getName().equals(TEAM_NAME))
                destination.getScoreboard().addPlayerToTeam(player.getScoreboardName(), destination.getScoreboard().getPlayerTeam(TEAM_NAME));
        });
    }
}
