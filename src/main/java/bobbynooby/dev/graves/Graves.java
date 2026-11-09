package bobbynooby.dev.graves;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.Stacks;
import bobbynooby.dev.core.Undo;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/** Makes and removes graves. Every grave hook is wired up in init. */
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
    }

    /** True for the head block of a grave while graves are on. Every protection hook asks this. */
    public static boolean isGrave(Level level, BlockPos pos) {
        if (!Settings.on(c -> c.graves.enabled) || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        // The block first: it is cheap, and the hooks run for many blocks that are never heads
        return level.getBlockState(pos).is(Blocks.PLAYER_HEAD)
                && GraveStore.get(serverLevel.getServer()).at(level.dimension(), pos).isPresent();
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
        GraveStore store = GraveStore.get(level.getServer());
        List<ItemStack> stacks = Stacks.nonEmpty(items);
        int rotation = RotationSegment.convertToSegment(ownerYaw(level, owner));
        long now = System.currentTimeMillis();

        List<Grave> created = new ArrayList<>();
        List<Runnable> undo = new ArrayList<>();
        // Each step on its own, so one that fails can't leave the other graves standing
        Runnable undoAll = () -> Undo.runAll(undo, "Could not take back one of {}'s graves", ownerName);
        try {
            BlockPos from = near;
            int start = 0;
            do {
                List<ItemStack> part = stacks.subList(start, Math.min(start + SLOTS_PER_GRAVE, stacks.size()));
                BlockPos pos = GravePlacement.find(level, from, store);
                Grave grave = new Grave(UUID.randomUUID(), owner, ownerName, profile, level.dimension(), pos, part, now);
                BlockState previous = level.getBlockState(pos);
                undo.add(() -> {
                    // The record goes first, so a block that can't be put back still leaves no grave holding items
                    store.remove(grave.id());
                    Guard.run(() -> level.setBlock(pos, previous, Block.UPDATE_ALL),
                            "Could not put back the block {}'s taken-back grave replaced at {} {}",
                            ownerName, level.dimension().identifier(), pos);
                });
                GraveBlocks.placeHead(level, pos, profile, rotation);
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

    /**
     * Admin removal: drops everything at the grave, clears the head and deletes the record. Returns the stacks dropped,
     * or nothing when the grave's dimension isn't loaded, in which case the grave is left as it was.
     * The container leaves OPEN before any menu closes, so nothing can save the grave back or drop an item twice.
     */
    public static OptionalInt removeAndDrop(MinecraftServer server, Grave grave) {
        ServerLevel level = server.getLevel(grave.dimension());
        if (level == null) {
            // Nowhere to drop the items, so the record must stay until the dimension is back
            YouOnlyLiveTwice.LOGGER.error("Could not remove {}'s grave at {} {}: that dimension is not loaded",
                    grave.ownerName(), grave.dimension().identifier(), grave.pos());
            return OptionalInt.empty();
        }
        GraveStore store = GraveStore.get(server);
        SharedGraveContainer container = GraveMenus.OPEN.remove(grave.id());
        // The live container is newer than the record while anyone has the grave open
        List<ItemStack> source = container != null ? container.getItems() : store.byId(grave.id()).map(Grave::items).orElse(grave.items());
        List<ItemStack> items = Stacks.nonEmptyCopies(source);
        if (container != null) {
            // It already left OPEN, so emptying it can't write back; a menu still open on it now has nothing to give
            container.clearContent();
            container.closeMenus();
        }
        BlockPos pos = grave.pos();
        for (ItemStack stack : items) {
            level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack));
        }
        GraveBlocks.clearHead(level, pos);
        store.remove(grave.id());
        return OptionalInt.of(items.size());
    }

    // Only an online owner has a facing, so graves for offline owners face the default way
    private static float ownerYaw(ServerLevel level, UUID owner) {
        Player player = level.getPlayerByUUID(owner);
        return player != null ? player.getYRot() : 0.0F;
    }
}
