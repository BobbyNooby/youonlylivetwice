package bobbynooby.dev.offline;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.lockout.DeathLockout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Kills a player whether they are online or not, so both end in the same state. */
public final class OfflineKill {
    public enum KillResult { KILLED_ONLINE, KILLED_OFFLINE, NOT_FOUND }

    private OfflineKill() {}

    public static KillResult kill(MinecraftServer server, NameAndId who) {
        ServerPlayer online = server.getPlayerList().getPlayer(who.id());
        if (online != null) {
            // Same as vanilla /kill: the normal death runs, and its death event starts the lockout
            online.kill(online.level());
            return KillResult.KILLED_ONLINE;
        }
        // No save file means they never played here
        Optional<CompoundTag> tag = OfflinePlayerData.load(server, who);
        if (tag.isEmpty()) {
            return KillResult.NOT_FOUND;
        }
        applyOfflineDeath(server, who, tag.get(), OfflinePlayerData.lastPosition(tag.get()));
        return KillResult.KILLED_OFFLINE;
    }

    /** Turns the save into a dead player's, so the next login goes through vanilla's respawn. */
    public static void applyOfflineDeath(MinecraftServer server, NameAndId who, CompoundTag tag, GlobalPos where) {
        List<ItemStack> items = new ArrayList<>(OfflinePlayerData.takeInventory(tag, server.registryAccess()));
        switch (Settings.get().graves.enderChestOnDeath) {
            case GRAVE -> items.addAll(OfflinePlayerData.takeEnderChest(tag, server.registryAccess()));
            case WIPE -> OfflinePlayerData.takeEnderChest(tag, server.registryAccess());
            case KEEP -> {
            }
        }

        ServerLevel level = loadedLevel(server, where);
        ExperienceOrb.award(level, Vec3.atBottomCenterOf(where.pos()), experienceReward(level, tag));
        tag.putInt("XpLevel", 0);
        tag.putInt("XpTotal", 0);
        tag.putFloat("XpP", 0.0F);

        tag.remove("active_effects");
        tag.putFloat("Health", 0.0F);
        tag.store("LastDeathLocation", GlobalPos.CODEC, where);

        // Items go out before the save is written: a failed save then duplicates them instead of losing them
        deposit(server, who, items, where);
        OfflinePlayerData.save(server, who.id(), tag);
        if (Settings.get().deathLockout.enabled) {
            DeathLockout.recordDeath(who.id(), who.name());
        }
    }

    public static void deposit(MinecraftServer server, NameAndId who, List<ItemStack> items, GlobalPos where) {
        ServerLevel level = loadedLevel(server, where);
        BlockPos pos = where.pos();
        for (ItemStack item : items) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), item);
        }
    }

    // Vanilla's Player.getBaseExperienceReward, read from the save instead of a live player
    private static int experienceReward(ServerLevel level, CompoundTag tag) {
        boolean spectator = GameType.byId(tag.getIntOr("playerGameType", 0)) == GameType.SPECTATOR;
        if (level.getGameRules().get(GameRules.KEEP_INVENTORY) || spectator) {
            return 0;
        }
        return Math.min(tag.getIntOr("XpLevel", 0) * 7, 100);
    }

    // Entities added to an unloaded chunk sit in limbo, so load it first
    private static ServerLevel loadedLevel(MinecraftServer server, GlobalPos where) {
        ServerLevel level = server.getLevel(where.dimension());
        if (level == null) {
            level = server.overworld();
        }
        level.getChunkAt(where.pos());
        return level;
    }
}
