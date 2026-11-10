package bobbynooby.dev.graves;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Later;
import bobbynooby.dev.core.Yolt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Optional;
import java.util.UUID;

/** The grave head blocks: setting, clearing and restoring them, and what a click or a placement does. */
public final class GraveBlocks {
    private GraveBlocks() {
    }

    /**
     * True when the item about to be placed is a grave or revive head, which must stay an item. A placed head
     * drops back with only its profile, name and note sound, so its marker would be lost for good.
     */
    public static boolean refusePlacement(BlockPlaceContext context) {
        // The client's own prediction is left alone; the server's answer undoes it
        if (!Settings.active() || context.getLevel().isClientSide()) {
            return false;
        }
        ItemStack stack = context.getItemInHand();
        if (!GraveItems.isGraveHead(stack) && !GraveItems.isReviveHead(stack)) {
            return false;
        }
        if (context.getPlayer() instanceof ServerPlayer player) {
            resendInventory(player);
        }
        return true;
    }

    /**
     * Puts back the head of every grave in the chunk whose block went missing, for example to /setblock. A spot that
     * something else has taken since, another head included, is left alone, and the grave moves to the nearest free
     * spot instead.
     */
    public static void restoreChunk(ServerLevel level, LevelChunk chunk) {
        GraveStore store = GraveStore.get(level.getServer());
        for (Grave grave : store.inChunk(level.dimension(), chunk.getPos())) {
            if (isOwnHead(level, grave.pos(), grave)) {
                continue;
            }
            // Setting the head over a chest would spill it, over a shulker box would delete what it holds
            Optional<BlockPos> pos = GravePlacement.fits(level, grave.pos(), store)
                    ? Optional.of(grave.pos()) : GravePlacement.nearestSafe(level, grave.pos(), store);
            if (pos.isEmpty()) {
                Yolt.LOGGER.warn("Could not put back the head of {}'s grave at {} {}: its spot is taken or unsafe, and "
                        + "there is no free spot near it", grave.ownerName(), level.dimension().identifier(), grave.pos());
                continue;
            }
            BlockPos at = pos.get();
            boolean moved = !at.equals(grave.pos());
            if (moved) {
                // The moved record reaches the disk before the head goes down. Otherwise a crash could leave the
                // record at the old spot and a head at the new one that nothing protects.
                store.put(grave.withPos(at));
                try {
                    store.saveNow(level.getServer());
                } catch (RuntimeException e) {
                    store.put(grave);
                    Yolt.LOGGER.error("Could not save {}'s grave at its new spot {}, so it stays at {} {} without a head for now",
                            grave.ownerName(), at, level.dimension().identifier(), grave.pos(), e);
                    continue;
                }
            }
            // The record doesn't keep the facing, so a restored head faces the default way
            placeHead(level, at, grave, 0);
            if (moved) {
                Yolt.LOGGER.info("Moved {}'s grave in {} from {} to {}, since its old spot is taken or unsafe",
                        grave.ownerName(), level.dimension().identifier(), grave.pos(), at);
            } else {
                Yolt.LOGGER.info("Put back the missing head of {}'s grave at {} {}",
                        grave.ownerName(), level.dimension().identifier(), grave.pos());
            }
        }
    }

    static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!Settings.active() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        BlockPos pos = hit.getBlockPos();
        if (Settings.get().graves.enabled && level.getBlockState(pos).is(Blocks.PLAYER_HEAD)) {
            Optional<Grave> grave = GraveStore.get(serverPlayer.level().getServer()).at(level.dimension(), pos)
                    .filter(found -> isOwnHead(level, pos, found));
            if (grave.isPresent()) {
                // A vanilla client sees no use for a head, so it tries the off hand straight after; that must not reopen it
                if (hand == InteractionHand.MAIN_HAND) {
                    GraveMenus.open(serverPlayer, grave.get());
                    return InteractionResult.SUCCESS;
                }
                return InteractionResult.CONSUME;
            }
        }
        return InteractionResult.PASS;
    }

    // The client placed the head on its side and took it off the stack, so send it the real stack
    private static void resendInventory(ServerPlayer player) {
        player.inventoryMenu.sendAllDataToRemote();
    }

    // The event fires while the chunk is still being loaded, and setting a block in it then would wait on that
    // same load. So the restore waits until the current tick is done.
    static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean generated) {
        if (!Settings.on(c -> c.graves.enabled)) {
            return;
        }
        MinecraftServer server = level.getServer();
        ChunkPos pos = chunk.getPos();
        if (GraveStore.get(server).inChunk(level.dimension(), pos).isEmpty()) {
            return;
        }
        Later.afterTick(server, () -> {
            // Graves may have been switched off meanwhile. If the chunk unloaded, its next load tries again.
            if (Settings.on(c -> c.graves.enabled) && level.hasChunk(pos.x(), pos.z())) {
                restoreChunk(level, chunk);
            }
        });
    }

    // The skull reads its owner from components, the same way a placed head item hands it over. The grave's id is
    // kept with the skull's other components, so isOwnHead can tell this head from any other.
    static void placeHead(ServerLevel level, BlockPos pos, Grave grave, int rotation) {
        level.setBlock(pos, Blocks.PLAYER_HEAD.defaultBlockState().setValue(SkullBlock.ROTATION, rotation), Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof SkullBlockEntity skull) {
            skull.applyComponents(DataComponentMap.EMPTY, DataComponentPatch.builder()
                    .set(DataComponents.PROFILE, grave.profile())
                    .set(DataComponents.CUSTOM_DATA, GraveItems.graveMarker(grave.id()))
                    .build());
            skull.setChanged();
        }
    }

    /**
     * True when the block at pos is this grave's own head: a head carrying the grave's id, or, for a head placed before
     * heads carried one, showing the grave's owner. Any other head on the spot is someone's own block.
     */
    public static boolean isOwnHead(Level level, BlockPos pos, Grave grave) {
        if (!level.getBlockState(pos).is(Blocks.PLAYER_HEAD) || !(level.getBlockEntity(pos) instanceof SkullBlockEntity skull)) {
            return false;
        }
        Optional<UUID> id = GraveItems.graveId(skull.components().get(DataComponents.CUSTOM_DATA));
        return id.isPresent() ? id.get().equals(grave.id()) : grave.profile().equals(skull.getOwnerProfile());
    }

    // Only the grave's own head, so a block that has since replaced it, another head included, is left alone
    static void clearHead(ServerLevel level, Grave grave) {
        if (isOwnHead(level, grave.pos(), grave)) {
            level.setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
