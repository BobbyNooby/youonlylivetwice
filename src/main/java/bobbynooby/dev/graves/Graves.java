package bobbynooby.dev.graves;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.Later;
import bobbynooby.dev.core.Stacks;
import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.core.Undo;
import bobbynooby.dev.mixin.PlayerInvoker;
import bobbynooby.dev.revive.Revive;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/** Moves a dead player's items into graves and lets players loot them. */
public final class Graves {
    // A grave opens as a 6-row chest menu
    private static final int SLOTS_PER_GRAVE = GraveMenu.GRAVE_SLOTS;

    // One container per grave while anyone has it open, keyed by grave id. Server thread only.
    private static final Map<UUID, SharedContainer> OPEN = new HashMap<>();

    // Non-null only while a dying player's menus close. Server thread only.
    private static ServerPlayer collectingFor;
    private static List<ItemStack> collected = new ArrayList<>();

    private Graves() {
    }

    // Grave event handlers register here. The death hook is a mixin, since no Fabric event runs before the drops,
    // and so are the protection hooks for explosions, pistons, fluids, the Wither and the dragon.
    public static void init() {
        UseBlockCallback.EVENT.register(Graves::onUseBlock);
        // Creative players included, so not even an op can break one by hand
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> !isGrave(level, pos));
        ServerChunkEvents.CHUNK_LOAD.register(Graves::onChunkLoad);
        // A container must never outlive its server and save into the next one started in the same game
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> OPEN.clear());
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

    /** Puts back the head of every grave in the chunk whose block went missing, for example to /setblock. */
    public static void restoreChunk(ServerLevel level, LevelChunk chunk) {
        for (Grave grave : GraveStore.get(level.getServer()).inChunk(level.dimension(), chunk.getPos())) {
            if (!level.getBlockState(grave.pos()).is(Blocks.PLAYER_HEAD)) {
                // The record doesn't keep the facing, so a restored head faces the default way
                placeHead(level, grave.pos(), grave.profile(), 0);
                YouOnlyLiveTwice.LOGGER.info("Put back the missing head of {}'s grave at {} {}",
                        grave.ownerName(), level.dimension().identifier(), grave.pos());
            }
        }
    }

    /** True when the items went into a grave, so vanilla must not drop them as well. */
    public static boolean onPlayerDeathDrops(ServerPlayer player, ServerLevel level) {
        // Before every switch: a player body only wears copies of its owner's saved gear, and the owner's real
        // grave comes from the body's death. Dropping the copies would make the items exist twice.
        if (StandIn.is(player)) {
            player.getInventory().clearContent();
            return true;
        }
        if (!Settings.on(c -> c.graves.enabled)) {
            return false;
        }
        boolean keepInventory = level.getGameRules().get(GameRules.KEEP_INVENTORY);
        YoltConfig.EnderChestMode enderMode = Settings.get().graves.enderChestOnDeath;
        Inventory inventory = player.getInventory();
        PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
        String name = player.nameAndId().name();

        // Until the graves exist, any failure falls back to vanilla dropping everything
        List<ItemStack> fromMenus = List.of();
        try {
            ResolvableProfile profile = GraveItems.profileOf(player);
            // The head comes first and is always there: it is the revive token
            List<ItemStack> items = new ArrayList<>();
            items.add(GraveItems.graveHead(profile));
            if (!keepInventory) {
                // Menus close first, so a menu that hands items back into the inventory is covered by the scan
                fromMenus = takeMenuItems(player);
                ((PlayerInvoker) player).yoltDestroyVanishingCursedItems();
                addStacks(items, inventory);
                items.addAll(fromMenus);
            }
            if (enderMode == YoltConfig.EnderChestMode.GRAVE) {
                addStacks(items, enderChest);
            }
            create(level, player.blockPosition(), profile, player.getUUID(), name, items);
        } catch (RuntimeException e) {
            // Only the menu items have left the player; put them back so vanilla drops them with the rest
            YouOnlyLiveTwice.LOGGER.error("Could not make a grave for {}, dropping their items instead", name, e);
            handBack(player, fromMenus);
            return false;
        }

        // The lockout kick right after a death saves the emptied player at once, so the graves must reach the
        // disk first. This runs inside the death, so a failed write only logs; the next save tries again.
        try {
            GraveStore.get(level.getServer()).saveNow(level.getServer());
        } catch (RuntimeException e) {
            YouOnlyLiveTwice.LOGGER.error("Could not write {}'s grave to disk, it is kept in memory until the next save", name, e);
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
                placeHead(level, pos, profile, rotation);
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

    /** Called by the menu and drop hooks. True when the stack was taken for a dying player's grave instead. */
    public static boolean takeReturnedItem(Player player, ItemStack stack) {
        if (collectingFor == null || player != collectingFor) {
            return false;
        }
        if (!stack.isEmpty()) {
            collected.add(stack);
        }
        return true;
    }

    /** Opens the grave's take-only menu. Everyone looking at one grave shares the same items. */
    public static void open(ServerPlayer player, Grave grave) {
        if (GraveStore.get(player.level().getServer()).byId(grave.id()).isEmpty()) {
            return;
        }
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new GraveMenu(id, inventory, grave),
                Component.literal(grave.ownerName() + "'s Grave")));
    }

    /** The container every open menu on this grave shares, or null when nobody has the grave open. */
    public static Container containerFor(Grave grave) {
        return OPEN.get(grave.id());
    }

    // Filled from the stored record, never the caller's copy, which may still list items someone has taken
    static Container sharedContainer(MinecraftServer server, Grave grave) {
        return OPEN.computeIfAbsent(grave.id(), id -> new SharedContainer(server, id,
                GraveStore.get(server).byId(id).map(Grave::items).orElse(List.of())));
    }

    /** Once the stored record is empty: the head goes, then the record, then every menu still showing it. */
    static void removeIfEmpty(MinecraftServer server, Grave grave) {
        GraveStore store = GraveStore.get(server);
        if (store.byId(grave.id()).filter(stored -> stored.items().isEmpty()).isEmpty()) {
            return;
        }
        ServerLevel level = server.getLevel(grave.dimension());
        if (level != null && level.getBlockState(grave.pos()).is(Blocks.PLAYER_HEAD)) {
            level.setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        store.remove(grave.id());
        SharedContainer container = OPEN.remove(grave.id());
        if (container != null) {
            container.closeMenus();
        }
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
        SharedContainer container = OPEN.remove(grave.id());
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
        if (level.getBlockState(pos).is(Blocks.PLAYER_HEAD)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        store.remove(grave.id());
        return OptionalInt.of(items.size());
    }

    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!Settings.active() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (Settings.get().graves.enabled && level.getBlockState(hit.getBlockPos()).is(Blocks.PLAYER_HEAD)) {
            Optional<Grave> grave = GraveStore.get(serverPlayer.level().getServer()).at(level.dimension(), hit.getBlockPos());
            if (grave.isPresent()) {
                // A vanilla client sees no use for a head, so it tries the off hand straight after; that must not reopen it
                if (hand == InteractionHand.MAIN_HAND) {
                    open(serverPlayer, grave.get());
                    return InteractionResult.SUCCESS;
                }
                return InteractionResult.CONSUME;
            }
        }
        return useReviveHead(serverPlayer, hand);
    }

    // Right-clicking with a revive head always means trying to revive, so the clicked block never reacts. A grave
    // head passes: the block reacts as usual, and placing it is refused in refusePlacement.
    private static InteractionResult useReviveHead(ServerPlayer player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isSpectator() || !GraveItems.isReviveHead(stack)) {
            return InteractionResult.PASS;
        }
        Revive.tryUse(player, stack);
        resendInventory(player);
        return InteractionResult.FAIL;
    }

    // The client placed the head on its side and took it off the stack, so send it the real stack
    private static void resendInventory(ServerPlayer player) {
        player.inventoryMenu.sendAllDataToRemote();
    }

    // The event fires while the chunk is still being loaded, and setting a block in it then would wait on that
    // same load. So the restore waits until the current tick is done.
    private static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean generated) {
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

    private static void addStacks(List<ItemStack> into, Container container) {
        List<ItemStack> all = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            all.add(container.getItem(i));
        }
        into.addAll(Stacks.nonEmpty(all));
    }

    // Closes the menus with their own vanilla code, so any menu hands back its cursor and input items (crafting
    // grids, anvil, loom, trades...). The hooks catch each item on its way to the inventory or the ground.
    private static List<ItemStack> takeMenuItems(ServerPlayer player) {
        List<ItemStack> taken = new ArrayList<>();
        try {
            closeMenusInto(player, taken);
        } catch (RuntimeException e) {
            // The items caught so far would go down with the grave, so they go back for vanilla to drop
            handBack(player, taken);
            throw e;
        }
        return taken;
    }

    // A menu's close code could kill another player and start their collection, so each call keeps its own list
    // and restores the one before
    private static void closeMenusInto(ServerPlayer player, List<ItemStack> into) {
        ServerPlayer previousFor = collectingFor;
        List<ItemStack> previous = collected;
        collectingFor = player;
        collected = into;
        try {
            if (player.containerMenu != player.inventoryMenu) {
                // No close packet: the client already shows the death screen, and a close would swap it for one
                // without the death message
                player.doCloseContainer();
            }
            // The inventory menu is never closed, but this returns its cursor and 2x2 grid the same way
            player.inventoryMenu.removed(player);
        } finally {
            collectingFor = previousFor;
            collected = previous;
        }
    }

    // Each stack on its own, so one that can't go back doesn't keep the rest from being dropped
    private static void handBack(ServerPlayer player, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            Guard.run(() -> player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY),
                    "Could not hand {} back to {}", stack, player.nameAndId().name());
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

    // Every viewer of a grave clicks into this one container, so two looters can never both take a stack
    private static final class SharedContainer extends SimpleContainer {
        private final MinecraftServer server;
        private final UUID graveId;
        private final List<ContainerUser> viewers = new ArrayList<>();

        SharedContainer(MinecraftServer server, UUID graveId, List<ItemStack> items) {
            // Room for every stored stack, so saving back can never drop one the menu has no slot for
            super(Math.max(SLOTS_PER_GRAVE, items.size()));
            this.server = server;
            this.graveId = graveId;
            int slot = 0;
            // Copies, so taking from the menu never changes the record behind its back. Put in the list
            // directly because setItem would cut an oversized stack down to the slot limit.
            for (ItemStack stack : Stacks.nonEmptyCopies(items)) {
                getItems().set(slot++, stack);
            }
        }

        // Every change goes straight into the record, so a save or restart never brings back a taken item.
        // Only the live container writes: one let go of could otherwise overwrite newer contents.
        @Override
        public void setChanged() {
            if (OPEN.get(graveId) != this) {
                return;
            }
            GraveStore store = GraveStore.get(server);
            store.byId(graveId).ifPresent(grave -> {
                store.put(grave.withItems(Stacks.nonEmptyCopies(getItems())));
            });
        }

        @Override
        public void startOpen(ContainerUser user) {
            viewers.add(user);
        }

        @Override
        public void stopOpen(ContainerUser user) {
            viewers.remove(user);
            if (viewers.isEmpty()) {
                OPEN.remove(graveId, this);
            }
        }

        void closeMenus() {
            for (ContainerUser viewer : List.copyOf(viewers)) {
                if (viewer instanceof ServerPlayer player && player.containerMenu instanceof GraveMenu menu
                        && menu.graveId().equals(graveId)) {
                    player.closeContainer();
                }
            }
        }
    }
}
