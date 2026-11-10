package bobbynooby.dev.graves;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.Stacks;
import bobbynooby.dev.core.Undo;
import bobbynooby.dev.core.Yolt;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Makes and removes graves. Its init wires the use-block, block-break and chunk hooks; the rest are mixins. */
public final class Graves {
    // A grave opens as a 6-row chest menu
    private static final int SLOTS_PER_GRAVE = GraveMenu.GRAVE_SLOTS;

    private Graves() {
    }

    // Grave event handlers register here. The death hook is a mixin, since no Fabric event runs before the drops,
    // and so are the protection hooks for explosions, pistons, fluids, the Wither and the dragon.
    public static void init() {
        UseBlockCallback.EVENT.register(GraveBlocks::onUseBlock);
        // Creative players included, so not even an op can break one by hand
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> !isGrave(level, pos));
        ServerChunkEvents.CHUNK_LOAD.register(GraveBlocks::onChunkLoad);
        GraveMenus.init();
        LastGround.init();
        GraveRecheck.init();
        GraveNotice.init();
    }

    /** True for the head block of a grave. Every protection hook asks this. */
    public static boolean isGrave(Level level, BlockPos pos) {
        // Not graves.enabled: switching graves off only stops new ones, and the graves already out there still hold
        // someone's items, so they stay protected
        if (!Settings.active() || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        // The block first: it is cheap, and the hooks run for many blocks that are never heads
        return level.getBlockState(pos).is(Blocks.PLAYER_HEAD)
                && GraveStore.get(serverLevel.getServer()).at(level.dimension(), pos)
                .filter(grave -> GraveBlocks.isOwnHead(level, pos, grave)).isPresent();
    }

    /**
     * Places a grave near the given spot for every 54 stacks, each next to the one before, and stores their records.
     * All or nothing: if one grave fails, the ones already made are taken back and the error is rethrown.
     */
    public static List<Grave> create(ServerLevel level, BlockPos near, ResolvableProfile profile, UUID owner,
                                     String ownerName, List<ItemStack> items) {
        return createUndoable(level, near, profile, owner, ownerName, items).graves();
    }

    /** The graves one create call made, and how to take them all back: records gone, replaced blocks put back. */
    public record Created(List<Grave> graves, Runnable undo) {
    }

    /** Same as create, for callers that can still fail after the graves exist and must then take them back. */
    public static Created createUndoable(ServerLevel level, BlockPos near, ResolvableProfile profile, UUID owner,
                                         String ownerName, List<ItemStack> items) {
        return createUndoable(level, near, GlobalPos.of(level.dimension(), near), profile, owner, ownerName, items);
    }

    /**
     * The same, for a death at died, whose graves go near the spot near in level. They differ only when the death's
     * own dimension isn't loaded, and the graves go to another one.
     */
    public static Created createUndoable(ServerLevel level, BlockPos near, GlobalPos died, ResolvableProfile profile,
                                         UUID owner, String ownerName, List<ItemStack> items) {
        GraveStore store = GraveStore.get(level.getServer());
        List<ItemStack> stacks = Stacks.nonEmpty(items);
        int rotation = RotationSegment.convertToSegment(ownerYaw(level, owner));
        long now = System.currentTimeMillis();
        // Only a death over the void uses it, and only the first grave can be over the void
        BlockPos lastGround = LastGround.in(owner, level.dimension()).orElse(null);

        List<Grave> created = new ArrayList<>();
        List<Runnable> undo = new ArrayList<>();
        // Each step on its own, so one that fails can't leave the other graves standing
        Runnable undoAll = () -> Undo.runAll(undo, "Could not take back one of {}'s graves", ownerName);
        try {
            BlockPos from = near;
            int start = 0;
            do {
                List<ItemStack> part = stacks.subList(start, Math.min(start + SLOTS_PER_GRAVE, stacks.size()));
                BlockPos pos = GravePlacement.find(level, from, lastGround, store);
                Grave grave = new Grave(UUID.randomUUID(), owner, ownerName, profile, level.dimension(), pos, part, now, died);
                BlockState previous = level.getBlockState(pos);
                undo.add(() -> {
                    // The record goes first, so a block that can't be put back still leaves no grave holding items
                    store.remove(grave.id());
                    Guard.run(() -> level.setBlock(pos, previous, Block.UPDATE_ALL),
                            "Could not put back the block {}'s taken-back grave replaced at {} {}",
                            ownerName, level.dimension().identifier(), pos);
                });
                GraveBlocks.placeHead(level, pos, grave, rotation);
                // Stored before the next search, so the next grave can't land on this one
                store.put(grave);
                created.add(grave);
                from = pos;
                start += SLOTS_PER_GRAVE;
            } while (start < stacks.size());
        } catch (RuntimeException e) {
            undoAll.run();
            throw e;
        }
        return new Created(List.copyOf(created), undoAll);
    }

    /** What an admin removal did: dropped the grave's stacks, or left the grave as it was and why. */
    public sealed interface Removal {
        record Dropped(int stacks) implements Removal {
        }

        record DimensionNotLoaded() implements Removal {
        }

        record NoSafeSpot() implements Removal {
        }
    }

    /**
     * Admin removal: drops everything at the grave, clears the head and deletes the record. When the spot would now
     * destroy dropped items, for example lava has flowed under it, they drop at the nearest safe spot instead. With no
     * such spot, or no loaded dimension, the grave is left as it was, and so it is if the world refuses a drop, which
     * then throws. The container leaves OPEN before any menu closes, so nothing can save the grave back or drop an item
     * twice.
     */
    public static Removal removeAndDrop(MinecraftServer server, Grave grave) {
        ServerLevel level = server.getLevel(grave.dimension());
        if (level == null) {
            // Nowhere to drop the items, so the record must stay until the dimension is back
            Yolt.LOGGER.error("Could not remove {}'s grave at {} {}: that dimension is not loaded",
                    grave.ownerName(), grave.dimension().identifier(), grave.pos());
            return new Removal.DimensionNotLoaded();
        }
        GraveStore store = GraveStore.get(server);
        BlockPos pos = grave.pos();
        Optional<BlockPos> dropAt = GravePlacement.safeForItems(level, pos, store)
                ? Optional.of(pos) : GravePlacement.nearestSafe(level, pos, store, true);
        if (dropAt.isEmpty()) {
            Yolt.LOGGER.error("Could not remove {}'s grave at {} {}: every spot near it would destroy its items",
                    grave.ownerName(), grave.dimension().identifier(), pos);
            return new Removal.NoSafeSpot();
        }
        if (!dropAt.get().equals(pos)) {
            Yolt.LOGGER.info("Dropping {}'s grave items at {} {} instead of at the grave, where they would be destroyed",
                    grave.ownerName(), grave.dimension().identifier(), dropAt.get());
        }
        SharedGraveContainer container = GraveMenus.OPEN.remove(grave.id());
        // The live container is newer than the record while anyone has the grave open
        List<ItemStack> source = container != null ? container.getItems() : store.byId(grave.id()).map(Grave::items).orElse(grave.items());
        List<ItemStack> items = Stacks.nonEmptyCopies(source);
        if (container != null) {
            // It already left OPEN, so emptying it can't write back; a menu still open on it now has nothing to give
            container.clearContent();
            container.closeMenus();
        }
        // The record still holds every item, so a drop that fails leaves the grave whole
        dropForGood(level, dropAt.get(), items);
        GraveBlocks.clearHead(level, grave);
        store.remove(grave.id());
        return new Removal.Dropped(items.size());
    }

    /**
     * Drops the stacks at the centre of spot, where a grave would have kept them: with no push, so each falls straight
     * down the column that was checked, and never to despawn. Every one or none: if the world refuses one, or adding it
     * throws, the ones dropped are taken back and this throws.
     */
    static void dropForGood(ServerLevel level, BlockPos spot, List<ItemStack> stacks) {
        // Entities added to a chunk that isn't loaded sit in limbo until it is
        level.getChunkAt(spot);
        Vec3 centre = Vec3.atCenterOf(spot);
        List<ItemEntity> added = new ArrayList<>();
        try {
            for (ItemStack stack : stacks) {
                ItemEntity item = new ItemEntity(level, centre.x, centre.y, centre.z, stack, 0.0, 0.0, 0.0);
                item.setUnlimitedLifetime();
                // Listed before the add, so it is still taken back if a load listener throws once it is in
                added.add(item);
                if (!level.addFreshEntity(item)) {
                    throw new IllegalStateException("Could not drop " + stack + " at " + level.dimension().identifier() + " " + spot);
                }
            }
        } catch (RuntimeException e) {
            added.forEach(Entity::discard);
            throw e;
        }
    }

    // Only an online owner has a facing, so graves for offline owners face the default way
    private static float ownerYaw(ServerLevel level, UUID owner) {
        Player player = level.getPlayerByUUID(owner);
        return player != null ? player.getYRot() : 0.0F;
    }
}
