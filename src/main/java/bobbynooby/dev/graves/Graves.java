package bobbynooby.dev.graves;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.mixin.PlayerInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Moves a dead player's items into graves. */
public final class Graves {
    // A grave opens as a 6-row chest menu
    private static final int SLOTS_PER_GRAVE = 54;

    private Graves() {
    }

    // Grave event handlers register here. The death hook is a mixin, since no Fabric event runs before the drops.
    public static void init() {
    }

    /** True when the items went into a grave, so vanilla must not drop them as well. */
    public static boolean onPlayerDeathDrops(ServerPlayer player, ServerLevel level) {
        if (!Settings.active() || !Settings.get().graves.enabled) {
            return false;
        }
        boolean keepInventory = level.getGameRules().get(GameRules.KEEP_INVENTORY);
        YoltConfig.EnderChestMode enderMode = Settings.get().graves.enderChestOnDeath;
        Inventory inventory = player.getInventory();
        PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
        ResolvableProfile profile = GraveItems.profileOf(player);

        // The head comes first and is always there: it is the revive token
        List<ItemStack> items = new ArrayList<>();
        items.add(GraveItems.graveHead(profile));
        if (!keepInventory) {
            ((PlayerInvoker) player).invokeDestroyVanishingCursedItems();
            addStacks(items, inventory);
        }
        if (enderMode == YoltConfig.EnderChestMode.GRAVE) {
            addStacks(items, enderChest);
        }

        String name = player.nameAndId().name();
        try {
            create(level, player.blockPosition(), profile, player.getUUID(), name, items);
        } catch (RuntimeException e) {
            // Nothing has been taken from the player yet, so vanilla can still drop it all
            YouOnlyLiveTwice.LOGGER.error("Could not make a grave for {}, dropping their items instead", name, e);
            return false;
        }

        // Emptied only once the graves hold the items, so a failed grave can't lose them
        if (!keepInventory) {
            inventory.clearContent();
        }
        if (enderMode == YoltConfig.EnderChestMode.GRAVE || enderMode == YoltConfig.EnderChestMode.WIPE) {
            enderChest.clearContent();
        }
        return true;
    }

    /**
     * Places a grave near the given spot for every 54 stacks, each next to the one before, and stores their records.
     * All or nothing: if one grave fails, the ones already made are taken back and the error is rethrown.
     */
    public static List<Grave> create(ServerLevel level, BlockPos near, ResolvableProfile profile, UUID owner,
                                     String ownerName, List<ItemStack> items) {
        GraveStore store = GraveStore.get(level.getServer());
        List<ItemStack> stacks = items.stream().filter(stack -> !stack.isEmpty()).toList();
        int rotation = RotationSegment.convertToSegment(ownerYaw(level, owner));
        long now = System.currentTimeMillis();

        List<Grave> created = new ArrayList<>();
        List<Runnable> undo = new ArrayList<>();
        try {
            BlockPos from = near;
            int start = 0;
            do {
                List<ItemStack> part = stacks.subList(start, Math.min(start + SLOTS_PER_GRAVE, stacks.size()));
                BlockPos pos = GravePlacement.find(level, from, store);
                Grave grave = new Grave(UUID.randomUUID(), owner, ownerName, profile, level.dimension(), pos, part, now);
                BlockState previous = level.getBlockState(pos);
                undo.add(() -> {
                    store.remove(grave.id());
                    level.setBlock(pos, previous, Block.UPDATE_ALL);
                });
                placeHead(level, pos, profile, rotation);
                // Stored before the next search, so the next grave can't land on this one
                store.put(grave);
                created.add(grave);
                from = pos;
                start += SLOTS_PER_GRAVE;
            } while (start < stacks.size());
        } catch (RuntimeException e) {
            undo.forEach(Runnable::run);
            throw e;
        }
        return created;
    }

    private static void addStacks(List<ItemStack> into, Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                into.add(stack);
            }
        }
    }

    // Only an online owner has a facing, so graves for offline owners face the default way
    private static float ownerYaw(ServerLevel level, UUID owner) {
        Player player = level.getPlayerByUUID(owner);
        return player != null ? player.getYRot() : 0.0F;
    }

    // The skull reads its owner from components, the same way a placed head item hands it over
    private static void placeHead(ServerLevel level, BlockPos pos, ResolvableProfile profile, int rotation) {
        level.setBlock(pos, Blocks.PLAYER_HEAD.defaultBlockState().setValue(SkullBlock.ROTATION, rotation), Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof SkullBlockEntity skull) {
            skull.applyComponents(DataComponentMap.EMPTY, DataComponentPatch.builder().set(DataComponents.PROFILE, profile).build());
            skull.setChanged();
        }
    }
}
