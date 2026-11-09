package bobbynooby.dev.offline;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Undo;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.graves.Graves;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.lockout.DeathMessages;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Turns an offline player's save into a dead player's. Every offline death goes through here, and only here. */
public final class OfflineDeath {
    private OfflineDeath() {}

    /**
     * Turns the save into a dead player's, so the next login goes through vanilla's respawn.
     * All or nothing: if any step fails, the world and the save file stay as they were and the error is rethrown.
     * The tag object passed in is left wiped either way. The profile is the grave head's, so a caller that has the
     * player's skin can keep it. The body's death message, when it was a body that died, goes into the death log.
     */
    public static void applyOfflineDeath(MinecraftServer server, NameAndId who, CompoundTag tag, GlobalPos where,
                                         ResolvableProfile profile, @Nullable String bodyDeathMessage) {
        // Prepare: wipe the tag and write it to a temp file before anything in the world changes
        ServerLevel level = levelFor(server, where);
        // Same as an online death: a spectator drops nothing and gets everything back at respawn, and with
        // keepInventory the inventory and XP stay. The ender chest setting still applies with keepInventory, but
        // only while graves are on, since an online death with graves off never reaches it.
        boolean spectator = OfflinePlayerData.gameType(tag) == GameType.SPECTATOR;
        boolean graves = Settings.on(c -> c.graves.enabled);
        boolean dropsLoot = !spectator && !level.getGameRules().get(GameRules.KEEP_INVENTORY);
        List<ItemStack> items = new ArrayList<>();
        int experience = 0;
        if (dropsLoot) {
            // Vanilla destroys curse of vanishing items on death, but only those in the inventory
            for (ItemStack item : OfflinePlayerData.takeInventory(tag, server.registryAccess())) {
                if (!EnchantmentHelper.has(item, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
                    items.add(item);
                }
            }
            // Vanilla's Player.getBaseExperienceReward, read from the save instead of a live player
            experience = Math.min(OfflinePlayerData.takeExperience(tag) * 7, 100);
        }
        if (!spectator && graves) {
            switch (Settings.get().graves.enderChestOnDeath) {
                case GRAVE -> items.addAll(OfflinePlayerData.takeEnderChest(tag, server.registryAccess()));
                case WIPE -> OfflinePlayerData.takeEnderChest(tag, server.registryAccess());
                case KEEP -> {
                }
            }
        }
        OfflinePlayerData.markDead(tag, where);
        Path prepared = OfflinePlayerData.prepareSave(server, who.id(), tag);

        // Deposit, then commit. A failed commit takes the deposit back, so the items never exist twice.
        // Graves are already on disk when deposit returns, so a crash before the commit can duplicate, never lose.
        List<Runnable> undo = new ArrayList<>();
        try {
            // An online spectator's death never reaches the grave hook either
            if (!spectator) {
                undo.add(deposit(server, who, items, where, profile));
            }
            undo.add(dropExperience(level, where, experience));
            OfflinePlayerData.commitSave(server, who.id(), prepared);
        } catch (RuntimeException e) {
            // Every step is tried, since one failing must not leave another's items in the world
            Undo.runAll(undo, "Could not take back part of {}'s offline death", who.name());
            OfflinePlayerData.discardSave(prepared);
            throw e;
        }

        DeathLockout.recordDeath(who.id(), who.name(), DeathMessages.whileAway(bodyDeathMessage));
    }

    /**
     * Puts the items in the world at where, and returns how to take them back out. With graves on they go into
     * graves with a grave head first, as in an online death, and the graves are on disk before this returns.
     */
    private static Runnable deposit(MinecraftServer server, NameAndId who, List<ItemStack> items, GlobalPos where,
                                    ResolvableProfile profile) {
        ServerLevel level = levelFor(server, where);
        if (Settings.on(c -> c.graves.enabled)) {
            return depositInGraves(server, level, who, items, where, profile);
        }
        Vec3 pos = Vec3.atBottomCenterOf(where.pos());
        List<Entity> drops = new ArrayList<>();
        for (ItemStack item : items) {
            drops.add(new ItemEntity(level, pos.x, pos.y, pos.z, item));
        }
        return spawnAll(level, where, drops);
    }

    // The undo takes back every grave, including the extra ones past 54 stacks
    private static Runnable depositInGraves(MinecraftServer server, ServerLevel level, NameAndId who, List<ItemStack> items,
                                            GlobalPos where, ResolvableProfile profile) {
        List<ItemStack> stacks = new ArrayList<>();
        stacks.add(GraveItems.graveHead(profile));
        stacks.addAll(items);
        Graves.Created created = Graves.createUndoable(level, where.pos(), profile, who.id(), who.name(), stacks);
        try {
            // The wiped save is only committed after this, so a crash in between can't lose the items
            GraveStore.get(server).saveNow(server);
        } catch (RuntimeException e) {
            created.undo().run();
            throw e;
        }
        return created.undo();
    }

    // Split like ExperienceOrb.award, but never merged into orbs already there, so undo removes exactly these
    private static Runnable dropExperience(ServerLevel level, GlobalPos where, int amount) {
        Vec3 pos = Vec3.atBottomCenterOf(where.pos());
        List<Entity> orbs = new ArrayList<>();
        while (amount > 0) {
            int value = ExperienceOrb.getExperienceValue(amount);
            amount -= value;
            orbs.add(new ExperienceOrb(level, pos.x, pos.y, pos.z, value));
        }
        return spawnAll(level, where, orbs);
    }

    // Adds every entity or none. The chunk is loaded first, since entities added to an unloaded chunk sit in limbo.
    private static Runnable spawnAll(ServerLevel level, GlobalPos where, List<Entity> entities) {
        level.getChunkAt(where.pos());
        List<Entity> added = new ArrayList<>();
        Runnable undo = () -> added.forEach(Entity::discard);
        try {
            for (Entity entity : entities) {
                if (!level.addFreshEntity(entity)) {
                    throw new IllegalStateException("Could not add " + entity + " to the world");
                }
                added.add(entity);
            }
        } catch (RuntimeException e) {
            undo.run();
            throw e;
        }
        return undo;
    }

    private static ServerLevel levelFor(MinecraftServer server, GlobalPos where) {
        ServerLevel level = server.getLevel(where.dimension());
        return level != null ? level : server.overworld();
    }
}
