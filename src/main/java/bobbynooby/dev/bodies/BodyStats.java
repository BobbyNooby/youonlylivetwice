package bobbynooby.dev.bodies;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.level.storage.LevelResource;

/** Stats and advancements for a player body that are never stored or saved, so its owner's stay untouched. */
public final class BodyStats {
    private BodyStats() {}

    /** The body's own stats, never stored in the player list, so nothing is kept or written for the body's id. */
    public static ServerStatsCounter detachedStats(PlayerBody body) {
        MinecraftServer server = body.level().getServer();
        return new ServerStatsCounter(server, server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(body.getUUID() + ".json")) {
            @Override
            public void save() {
            }
        };
    }

    /** The body's own advancements, kept the same way. It never makes one: the chat would announce its owner's name. */
    public static PlayerAdvancements detachedAdvancements(PlayerBody body) {
        MinecraftServer server = body.level().getServer();
        PlayerAdvancements advancements = new PlayerAdvancements(server.getFixerUpper(), server.getPlayerList(), server.getAdvancements(),
                server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(body.getUUID() + ".json"), body) {
            @Override
            public boolean award(AdvancementHolder holder, String criterion) {
                return false;
            }

            @Override
            public void save() {
            }
        };
        // Nothing to check its criteria against on every tick and move
        advancements.clearTriggers();
        return advancements;
    }
}
