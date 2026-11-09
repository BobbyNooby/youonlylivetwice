package bobbynooby.dev.graves;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.mixin.PlayerInvoker;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Moves a dead player's items into graves and lets players loot them. */
public final class Graves {
    // A grave opens as a 6-row chest menu
    private static final int SLOTS_PER_GRAVE = GraveMenu.GRAVE_SLOTS;

    // One container per grave while anyone has it open, keyed by grave id. Server thread only.
    private static final Map<UUID, SharedContainer> OPEN = new HashMap<>();

    // Non-null only while a dying player's menus close. Server thread only.
    private static ServerPlayer collectingFor;
    private static final List<ItemStack> collected = new ArrayList<>();

    private Graves() {
    }

    // Grave event handlers register here. The death hook is a mixin, since no Fabric event runs before the drops.
    public static void init() {
        UseBlockCallback.EVENT.register(Graves::onUseBlock);
        // A container must never outlive its server and save into the next one started in the same game
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> OPEN.clear());
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
        List<ItemStack> fromMenus = List.of();
        if (!keepInventory) {
            ((PlayerInvoker) player).invokeDestroyVanishingCursedItems();
            addStacks(items, inventory);
            fromMenus = takeMenuItems(player);
            items.addAll(fromMenus);
        }
        if (enderMode == YoltConfig.EnderChestMode.GRAVE) {
            addStacks(items, enderChest);
        }

        String name = player.nameAndId().name();
        try {
            create(level, player.blockPosition(), profile, player.getUUID(), name, items);
        } catch (RuntimeException e) {
            // Only the menu items have left the player; put them back so vanilla drops them with the rest
            YouOnlyLiveTwice.LOGGER.error("Could not make a grave for {}, dropping their items instead", name, e);
            fromMenus.forEach(stack -> inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY));
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

    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!Settings.active() || !Settings.get().graves.enabled) {
            return InteractionResult.PASS;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || !level.getBlockState(hit.getBlockPos()).is(Blocks.PLAYER_HEAD)) {
            return InteractionResult.PASS;
        }
        Optional<Grave> grave = GraveStore.get(serverPlayer.level().getServer()).at(level.dimension(), hit.getBlockPos());
        if (grave.isEmpty()) {
            return InteractionResult.PASS;
        }
        // A vanilla client sees no use for a head, so it tries the off hand straight after; that must not reopen it
        if (hand == InteractionHand.MAIN_HAND) {
            open(serverPlayer, grave.get());
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }

    private static void addStacks(List<ItemStack> into, Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                into.add(stack);
            }
        }
    }

    // Closes the menus with their own vanilla code, so any menu hands back its cursor and input items (crafting
    // grids, anvil, loom, trades...). The hooks catch each item on its way to the inventory or the ground.
    private static List<ItemStack> takeMenuItems(ServerPlayer player) {
        collectingFor = player;
        try {
            if (player.containerMenu != player.inventoryMenu) {
                player.closeContainer();
            }
            // The inventory menu is never closed, but this returns its cursor and 2x2 grid the same way
            player.inventoryMenu.removed(player);
            return List.copyOf(collected);
        } finally {
            collectingFor = null;
            collected.clear();
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
            for (ItemStack stack : items) {
                if (!stack.isEmpty()) {
                    // A copy, so taking from the menu never changes the record behind its back. Put in the list
                    // directly because setItem would cut an oversized stack down to the slot limit.
                    getItems().set(slot++, stack.copy());
                }
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
                List<ItemStack> left = new ArrayList<>();
                for (ItemStack stack : getItems()) {
                    if (!stack.isEmpty()) {
                        left.add(stack.copy());
                    }
                }
                store.put(grave.withItems(left));
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
