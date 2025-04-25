package bobbynooby.dev.features;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;

import static bobbynooby.dev.YouOnlyLiveTwice.TEAM_NAME;
import static net.minecraft.scoreboard.AbstractTeam.VisibilityRule.NEVER;

public class NameTagHider {


    public static void registerInitTeams() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var team = server.getScoreboard().getTeam(TEAM_NAME);
            if (team == null || team.shouldShowFriendlyInvisibles()) {
                team = server.getScoreboard().addTeam(TEAM_NAME);
                team.setShowFriendlyInvisibles(false);
                team.setNameTagVisibilityRule(NEVER);
            }
        });
    }

    public static void registerAddPlayerToTeamOnJoin() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();

            if (player.getScoreboardTeam() == null || !player.getScoreboardTeam().getName().equals(TEAM_NAME))
                server.getScoreboard().addScoreHolderToTeam(player.getNameForScoreboard(), server.getScoreboard().getTeam(TEAM_NAME));
        });

        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((handler, sender, server) -> {
            System.out.println("Hello " + handler.getName());
            if (handler.getScoreboardTeam() == null || !handler.getScoreboardTeam().getName().equals(TEAM_NAME))
                server.getScoreboard().addScoreHolderToTeam(handler.getNameForScoreboard(), server.getScoreboard().getTeam(TEAM_NAME));
        });
    }
}
