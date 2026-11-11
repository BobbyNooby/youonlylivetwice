package bobbynooby.dev.graves;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.lockout.DeathLockout;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.LodestoneTracker;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A compass to the grave of the owner's latest death, given once they are back in and alive. It shows no coordinates,
 * and spins once the grave is gone. Bound to a lodestone by the player, it is an ordinary lodestone compass.
 */
public final class GraveCompass {
    private static final String ROOT = "yolt";
    private static final String GRAVE_COMPASS = "grave_compass";
    private static final String NAME_END = "'s grave";

    private GraveCompass() {
    }

    public static void init() {
        ServerPlayerEvents.JOIN.register(GraveCompass::offerSafely);
        // Not only after a death: an End exit is a respawn too, and offer gives nothing when nothing is owed
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> offerSafely(newPlayer));
    }

    // A throw must not escape into the join or the respawn
    private static void offerSafely(ServerPlayer player) {
        Guard.run(() -> offer(player), "Could not give {} the compass to their grave", player.nameAndId().name());
    }

    /**
     * Gives the player the compass a grave of theirs owes, once they are alive and not locked out, and the grave owes it
     * no more. The switch isn't read here: a death while it was on is owed its compass either way. True when given.
     */
    public static boolean offer(ServerPlayer player) {
        if (!Settings.active() || player.isDeadOrDying()) {
            return false;
        }
        UUID owner = player.getUUID();
        GraveStore store = GraveStore.get(player.level().getServer());
        List<Grave> owed = store.all().stream().filter(grave -> grave.owner().equals(owner) && grave.compassPending()).toList();
        // The lockout reads the death store, so it waits until a compass is owed at all
        if (owed.isEmpty() || DeathLockout.refusal(owner, player.nameAndId().name()) != null) {
            return false;
        }
        owed.forEach(grave -> store.put(grave.withCompassPending(false)));
        // Only one grave owes a compass at a time; should more ever, the store keeps them oldest first. Dropped at the
        // player when no slot takes it: Inventory.add would report a full creative inventory as taking it, and lose it.
        player.getInventory().placeItemBackInInventory(compassFor(owed.getLast()), Prediction.SERVER_ONLY);
        return true;
    }

    /** A compass that points at the grave, named for its owner. */
    public static ItemStack compassFor(Grave grave) {
        ItemStack compass = new ItemStack(Items.COMPASS);
        compass.set(DataComponents.LODESTONE_TRACKER, trackerFor(grave));
        // A compass with a tracker calls itself a lodestone compass whatever its item name, so only a custom name shows
        compass.set(DataComponents.CUSTOM_NAME, nameFor(grave.ownerName()));
        CompoundTag marker = new CompoundTag();
        marker.store(GRAVE_COMPASS, UUIDUtil.STRING_CODEC, grave.id());
        CompoundTag root = new CompoundTag();
        root.put(ROOT, marker);
        compass.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        return compass;
    }

    /**
     * Runs as a compass ticks in an inventory or a hand. A grave compass follows its grave if the grave has moved, and
     * once the grave is gone it loses its target, so it spins, and stops being a grave compass. One the player has
     * bound to a lodestone stops being one too, and keeps that lodestone. With the switch off too, since the switch
     * only stops new compasses.
     */
    public static void tick(ItemStack stack, ServerLevel level) {
        // Every compass in every inventory comes here each tick, so one without custom data costs a lookup and no more
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !Settings.active()) {
            return;
        }
        Optional<UUID> graveId = data.copyTag().getCompound(ROOT)
                .flatMap(marker -> marker.read(GRAVE_COMPASS, UUIDUtil.STRING_CODEC));
        if (graveId.isEmpty()) {
            return;
        }
        LodestoneTracker tracker = stack.get(DataComponents.LODESTONE_TRACKER);
        // Only the player's use on a lodestone makes a tracked target; the grave's never is. The copy that use makes in
        // creative, or from a stack of two, carries the marker too, and ends up the same.
        if (tracker != null && tracker.tracked()) {
            removeMarker(stack);
            // A name the player gave it in an anvil stays
            if (isGraveName(stack.get(DataComponents.CUSTOM_NAME))) {
                stack.remove(DataComponents.CUSTOM_NAME);
            }
            return;
        }
        Optional<Grave> grave = GraveStore.get(level.getServer()).byId(graveId.get());
        if (grave.isEmpty()) {
            stack.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.empty(), false));
            removeMarker(stack);
            return;
        }
        LodestoneTracker target = trackerFor(grave.get());
        if (!target.equals(tracker)) {
            stack.set(DataComponents.LODESTONE_TRACKER, target);
        }
    }

    // From then on it is an ordinary compass, which tick leaves alone
    private static void removeMarker(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.getCompound(ROOT).ifPresent(marker -> {
            marker.remove(GRAVE_COMPASS);
            if (marker.isEmpty()) {
                tag.remove(ROOT);
            }
        }));
    }

    private static Component nameFor(String ownerName) {
        return Component.literal(ownerName + NAME_END).withStyle(style -> style.withItalic(false));
    }

    // The name compassFor gives, whoever the owner. An anvil's name has no style, so a name typed there never matches.
    private static boolean isGraveName(@Nullable Component name) {
        if (name == null) {
            return false;
        }
        String text = name.getString();
        return text.endsWith(NAME_END) && name.equals(nameFor(text.substring(0, text.length() - NAME_END.length())));
    }

    // Not tracked: vanilla clears a tracked target that has no lodestone, and a grave has none
    private static LodestoneTracker trackerFor(Grave grave) {
        return new LodestoneTracker(Optional.of(GlobalPos.of(grave.dimension(), grave.pos())), false);
    }
}
