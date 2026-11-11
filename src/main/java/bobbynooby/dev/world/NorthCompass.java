package bobbynooby.dev.world;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.visibility.AdminView;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.Mth;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelData;

import java.util.List;

/**
 * Decides which spawn each player's client is told, which is all a plain compass points at. With hide.compassSpawn on,
 * that is a spot far to the player's north in the dimension they are in, so the compass shows north everywhere.
 * Recovery, lodestone and grave compasses have targets of their own. The server's real world spawn is untouched.
 */
public final class NorthCompass {
    // A million blocks inside the world's edge, where the border a client starts each level with has its walls. That
    // leaves a coordinate offset room to move the target and keep it inside.
    private static final int EDGE = MinecraftServer.ABSOLUTE_MAX_WORLD_SIZE - 1_000_000;
    // The sideways drift, as a share of the distance to the target, at which the needle is half a degree off
    private static final double MAX_DRIFT = Math.tan(Math.toRadians(0.5));
    // The target last sent to the player, for the drift check. Not saved: every join and respawn sends a new one.
    private static final AttachmentType<GlobalPos> SENT = AttachmentRegistry.create(Yolt.id("compass_target"));

    private NorthCompass() {}

    public static void init() {
        // Once a second is plenty: the target is so far off that only a near-maximum border lets anyone drift that much
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % SharedConstants.TICKS_PER_SECOND == 0) {
                recheckAll(server);
            }
        });
    }

    /** True when the player's compass points north rather than at the world spawn. Admin view gives an op the real one. */
    public static boolean pointsNorth(ServerPlayer player) {
        return Settings.on(c -> c.hide.compassSpawn) && !AdminView.isOn(player) && !StandIn.is(player);
    }

    /**
     * What a packet vanilla sends the player becomes: the north target for a spawn packet while it applies, otherwise the
     * packet itself. level is the one the player is in once the send's respawn or dimension change is done.
     */
    public static Packet<?> replace(ServerPlayer player, ServerLevel level, Packet<?> packet) {
        if (packet instanceof ClientboundSetDefaultSpawnPositionPacket && pointsNorth(player)) {
            return northFor(player, level);
        }
        return packet;
    }

    /**
     * Sends each player their own version of the spawn vanilla broadcasts when the world spawn moves. False when the switch
     * is off, which leaves vanilla's own broadcast to go out as it is.
     */
    public static boolean sendEach(PlayerList playerList, Packet<?> packet) {
        if (!Settings.on(c -> c.hide.compassSpawn)) {
            return false;
        }
        for (ServerPlayer player : playerList.getPlayers()) {
            player.connection.send(replace(player, player.level(), packet));
        }
        return true;
    }

    /** Sends the player's client the spawn it should have now. */
    public static void apply(ServerPlayer player) {
        if (!Settings.active() || StandIn.is(player)) {
            return;
        }
        ServerLevel level = player.level();
        // What vanilla sends as a player joins or changes dimension
        player.connection.send(replace(player, level, new ClientboundSetDefaultSpawnPositionPacket(level.getRespawnData())));
    }

    /** The same for every online player, after the switch changed. */
    public static void applyAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            apply(player);
        }
    }

    /** Sends a new target to each player who has walked far enough to the side of theirs to put the needle off. */
    public static void recheckAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            GlobalPos sent = player.getAttached(SENT);
            if (sent == null || !pointsNorth(player)) {
                continue;
            }
            ServerLevel level = player.level();
            // Past the edge, or north of the target, the player would get the same target again, which changes nothing
            boolean same = sent.dimension() == level.dimension();
            if (same && (!drifted(player, sent.pos()) || sent.pos().equals(targetFor(player, level)))) {
                continue;
            }
            player.connection.send(northFor(player, level));
        }
    }

    /** Where the player's compass points in level: straight north of them, far off. */
    public static BlockPos targetFor(ServerPlayer player, ServerLevel level) {
        return new BlockPos(Mth.clamp(player.getBlockX(), -EDGE, EDGE), level.getSeaLevel(), -EDGE);
    }

    // A client keeps a spawn only inside its own border, and moves one outside it to the border's centre. So the target
    // goes out between the border every client starts a level with, which reaches the world's edge, and the player's
    // real one. A bundle is handled all at once, so nothing on the client sees the first border. The real one carries a
    // moving border's sizes, time left and warning settings, so it goes on as it was.
    private static Packet<?> northFor(ServerPlayer player, ServerLevel level) {
        BlockPos target = targetFor(player, level);
        player.setAttached(SENT, GlobalPos.of(level.dimension(), target));
        return new ClientboundBundlePacket(List.<Packet<? super ClientGamePacketListener>>of(
                new ClientboundInitializeBorderPacket(new WorldBorder()),
                new ClientboundSetDefaultSpawnPositionPacket(LevelData.RespawnData.of(level.dimension(), target, 0.0F, 0.0F)),
                new ClientboundInitializeBorderPacket(level.getWorldBorder())));
    }

    // The needle aims at the target block's centre. North of the target it points the wrong way altogether.
    private static boolean drifted(ServerPlayer player, BlockPos target) {
        double sideways = player.getX() - (target.getX() + 0.5);
        double south = player.getZ() - (target.getZ() + 0.5);
        return south <= 0.0 || Math.abs(sideways) > south * MAX_DRIFT;
    }
}
