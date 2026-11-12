package bobbynooby.dev.bodies;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Stacks;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.lockout.DeathLockout;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Leaves a body behind when a player logs out. */
public final class BodyLogout {
    private BodyLogout() {}

    /** The new body, or empty when this logout gets none. */
    public static Optional<Entity> onLogout(ServerPlayer player) {
        YoltConfig.BodiesSection cfg = Settings.get().bodies;
        // Dead players get none: the lockout's kick after a death comes through here too
        if (!Settings.active() || player.isDeadOrDying()
                || DeathLockout.refusal(player.getUUID(), player.nameAndId().name()) != null) {
            return Optional.empty();
        }
        MinecraftServer server = player.level().getServer();
        BodyStore store = BodyStore.get(server);
        // Out again before the old body was taken back. The player has been online since, so the old body no longer
        // stands for them: it goes, and there is never a second one.
        Optional<BodyRecord> old = store.byOwner(player.getUUID());
        // None with bodies switched off, and none in creative or spectator, where the body could be killed while its
        // owner can't be. The old body goes all the same: a login would take it over and clear gear put on since.
        if (!cfg.enabled || player.isCreative() || player.isSpectator()) {
            old.ifPresent(record -> {
                store.remove(record.owner());
                retire(server, player, record);
            });
            return Optional.empty();
        }

        LivingEntity body = cfg.type == YoltConfig.BodyType.PLAYER
                ? PlayerBody.spawn(player, cfg)
                : MannequinBody.spawn(player, cfg);
        // A player body comes back from its record after a restart, so the record keeps its skin
        PropertyMap skin = body instanceof PlayerBody playerBody
                ? playerBody.getGameProfile().properties()
                : PropertyMap.EMPTY;
        Set<EquipmentSlot> empty = EnumSet.noneOf(EquipmentSlot.class);
        for (EquipmentSlot slot : GearSync.SLOTS) {
            if (body.getItemBySlot(slot).isEmpty()) {
                empty.add(slot);
            }
        }
        // The body was built from this same section, so the record says whether it took the effects
        store.put(new BodyRecord(player.getUUID(), player.nameAndId().name(), body.getUUID(), cfg.type,
                System.currentTimeMillis(), false, BodySnapshot.of(body), skin, empty, cfg.copyEffects));
        // Only once the new body stands, so a spawn that fails leaves the old one
        old.ifPresent(record -> retire(server, player, record));
        return Optional.of(body);
    }

    // A pending record's body was taken over already, its gear with it. One that isn't pending is stale: its login
    // takeover failed, so the owner has the real items of everything it copied, but not what a dispenser added since.
    private static void retire(MinecraftServer server, ServerPlayer player, BodyRecord old) {
        if (!old.pendingRemoval()) {
            keepAddedGear(server, player, old);
        }
        discard(server, old);
    }

    // What fills a slot that was empty at its logout is real, so it goes into the owner's inventory, or failing room
    // there, drops for good where the body stands
    private static void keepAddedGear(MinecraftServer server, ServerPlayer player, BodyRecord stale) {
        Entity entity = Bodies.entityOf(server, stale);
        BodySnapshot now = entity instanceof LivingEntity body ? BodySnapshot.of(body) : stale.snapshot();
        Inventory inventory = player.getInventory();
        List<ItemStack> noRoom = new ArrayList<>();
        for (EquipmentSlot slot : stale.emptyAtLogout()) {
            ItemStack item = now.gear().getOrDefault(slot, ItemStack.EMPTY).copy();
            if (item.isEmpty()) {
                continue;
            }
            int free = freeSlot(inventory);
            String saved = Stacks.saved(item, server.registryAccess());
            if (free >= 0) {
                inventory.setItem(free, item);
                Yolt.LOGGER.warn("{}'s old body got an item in slot {} after they logged out, so it goes into their "
                        + "inventory slot {}: {}", stale.ownerName(), slot.getName(), free, saved);
            } else {
                noRoom.add(item);
                Yolt.LOGGER.warn("{}'s old body got an item in slot {} after they logged out, and their inventory is "
                        + "full, so it drops where the body stands: {}", stale.ownerName(), slot.getName(), saved);
            }
        }
        Bodies.dropForGood(server, now.dimension(), now.pos(), noRoom);
    }

    // Never the hand: the new body copies the hand as it is now, so an item put there after would look used up on
    // the body, and the next takeover would clear it
    private static int freeSlot(Inventory inventory) {
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (slot != inventory.getSelectedSlot() && inventory.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    // Any dimension, in case an admin moved it. One in an unloaded chunk isn't found, but once the record
    // is replaced or removed it no longer counts as a body.
    private static void discard(MinecraftServer server, BodyRecord old) {
        Entity entity = Bodies.entityOf(server, old);
        if (entity != null) {
            entity.discard();
        }
    }
}
