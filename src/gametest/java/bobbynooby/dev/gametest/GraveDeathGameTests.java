package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.Kills;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveDeath;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveNotice;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.lockout.DeathLog;
import bobbynooby.dev.mixin.PlayerListInvoker;
import bobbynooby.dev.offline.OfflinePlayerData;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Prediction;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import org.apache.logging.log4j.core.LogEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Each kill runs inside one tick, so settings and game rules are restored before any other test runs
public class GraveDeathGameTests {
    // Holds graves.tellLocation across ticks, until a kick
    private static final String GRAVE_NOTICE = "you-only-live-twice-gametest:grave_notice";

    @GameTest
    public void deathCreatesGraveWithItemsAndHead(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Grave");
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            player.getInventory().add(new ItemStack(Items.IRON_SWORD));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            List<ItemStack> items = grave.items();
            helper.assertValueEqual(items.size(), 3, "grave item count");
            helper.assertTrue(GraveItems.isGraveHead(items.get(0)), "grave head is not the first item");
            helper.assertTrue(ItemStack.matches(items.get(1), new ItemStack(Items.DIAMOND)), "second item: " + items.get(1));
            helper.assertTrue(items.get(2).is(Items.IRON_SWORD), "third item is not the sword");
            helper.assertTrue(player.getInventory().isEmpty(), "player kept items");
            assertHeadBlock(helper, grave);
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    // The death kick saves the emptied player straight away, so the grave has to be in the file by then
    @GameTest
    public void deathWritesGraveToDisk(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Disk");
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            List<Grave> onDisk = GraveStoreGameTests.onDisk(helper.getLevel().getServer()).stream()
                    .filter(saved -> saved.id().equals(grave.id()))
                    .toList();
            helper.assertValueEqual(onDisk.size(), 1, "copies of the grave on disk");
            helper.assertValueEqual(GraveAsserts.countInGraves(onDisk, Items.DIAMOND), 1, "diamonds in the grave on disk");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    // A folder stands where the grave file goes, so the death's save of its grave fails. The storage counts the grave
    // store as saved anyway, so the store must be marked again: the next save then writes the grave. Without that, a
    // restart would find the emptied player and no grave. The file is written again in this tick.
    @GameTest
    public void graveTheDeathCouldNotWriteIsWrittenByTheNextSave(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        GraveStore store = GraveStore.get(server);
        Path file = GraveStore.TYPE.id().withSuffix(".dat").resolveAgainst(server.getWorldPath(LevelResource.DATA));
        ServerPlayer player = join(helper, "Unwritten");
        String name = player.getGameProfile().name();
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND, 2));
            Files.deleteIfExists(file);
            Files.createDirectories(file);
            List<String> log = OfflineGameTests.yoltLog(() -> kill(helper, player));
            Grave grave = onlyGrave(helper, player);
            helper.assertValueEqual(log.stream().filter(line -> line.startsWith("Could not write ")).toList(),
                    List.of("Could not write " + name + "'s grave to disk, it is kept in memory until the next save"),
                    "the failed write's log lines");
            helper.assertTrue(store.isDirty(), "the failed write left the grave store marked as saved");

            Files.delete(file);
            server.getDataStorage().saveAndJoin();
            List<Grave> onDisk = GraveStoreGameTests.onDisk(server).stream()
                    .filter(saved -> saved.id().equals(grave.id()))
                    .toList();
            helper.assertValueEqual(onDisk.size(), 1, "copies of the grave on disk after the next save");
            helper.assertValueEqual(GraveAsserts.countInGraves(onDisk, Items.DIAMOND), 2, "diamonds in the grave on disk");
        } finally {
            if (Files.isDirectory(file)) {
                Files.delete(file);
            }
            cleanUp(helper, player);
            store.saveNow(server);
        }
        helper.succeed();
    }

    @GameTest
    public void armourAndOffhandGoInTheGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Armour");
        Map<EquipmentSlot, Item> worn = Map.of(EquipmentSlot.HEAD, Items.IRON_HELMET, EquipmentSlot.CHEST, Items.IRON_CHESTPLATE,
                EquipmentSlot.LEGS, Items.IRON_LEGGINGS, EquipmentSlot.FEET, Items.IRON_BOOTS, EquipmentSlot.OFFHAND, Items.SHIELD);
        try {
            worn.forEach((slot, item) -> player.setItemSlot(slot, new ItemStack(item)));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            worn.forEach((slot, item) -> {
                helper.assertValueEqual(GraveAsserts.count(grave.items(), item), 1,
                        "grave's " + item + " from the " + slot + " slot");
                helper.assertTrue(player.getItemBySlot(slot).isEmpty(), slot + " slot still holds an item");
            });
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    // Curse of binding only keeps armour on a living player; at a death it goes in the grave like any armour, and the
    // respawn doesn't put it back on
    @GameTest
    public void curseOfBindingArmourGoesInTheGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Bound");
        try {
            ItemStack chestplate = new ItemStack(Items.DIAMOND_CHESTPLATE);
            chestplate.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.BINDING_CURSE), 1);
            ItemStack worn = chestplate.copy();
            player.setItemSlot(EquipmentSlot.CHEST, chestplate);
            BlockPos at = player.blockPosition();
            ServerPlayer back = dieAndRespawn(helper, player);

            Grave grave = onlyGrave(helper, player);
            List<ItemStack> chestplates = grave.items().stream().filter(stack -> stack.is(Items.DIAMOND_CHESTPLATE)).toList();
            helper.assertValueEqual(chestplates.size(), 1, "chestplates in the grave");
            helper.assertTrue(ItemStack.matches(chestplates.get(0), worn), "the grave's chestplate changed: " + chestplates.get(0));
            helper.assertTrue(back.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "the respawned player wears the bound chestplate");
            helper.assertValueEqual(back.getInventory().countItem(Items.DIAMOND_CHESTPLATE), 0, "chestplates the respawned player has");
            helper.assertValueEqual(itemsNear(helper, Items.DIAMOND_CHESTPLATE, at, 3.0), 0, "chestplates dropped");
        } finally {
            leaveAndRemoveGraves(helper, player.getUUID());
        }
        helper.succeed();
    }

    // A shulker box keeps its items inside it, in the grave and in the grave file, and none spill out of it
    @GameTest
    public void fullShulkerBoxGoesInTheGraveIntact(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Shulker");
        try {
            List<ItemStack> inside = new ArrayList<>();
            for (int i = 0; i < 26; i++) {
                inside.add(new ItemStack(Items.DIAMOND, 64));
            }
            ItemStack named = new ItemStack(Items.IRON_SWORD);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("Keepsake"));
            inside.add(named);
            ItemStack box = new ItemStack(Items.SHULKER_BOX);
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(inside));
            ItemStack packed = box.copy();
            player.getInventory().add(box);
            BlockPos at = player.blockPosition();
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            helper.assertValueEqual(grave.items().size(), 2, "stacks in the grave, the head and the box");
            ItemStack inGrave = grave.items().get(1);
            helper.assertTrue(ItemStack.matches(inGrave, packed), "the grave's shulker box changed: " + inGrave);
            assertBoxHolds(helper, inGrave, "in the grave");
            Grave onDisk = GraveStoreGameTests.onDisk(helper.getLevel().getServer()).stream()
                    .filter(saved -> saved.id().equals(grave.id())).findFirst().orElseThrow();
            assertBoxHolds(helper, onDisk.items().get(1), "in the grave file");
            helper.assertValueEqual(itemsNear(helper, Items.DIAMOND, at, 3.0) + itemsNear(helper, Items.SHULKER_BOX, at, 3.0), 0,
                    "diamonds and boxes dropped");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    // Two players on one block die in the same tick: two graves, one each, at different blocks, each with only its own
    // owner's items and head
    @GameTest
    public void twoPlayersDieOnSameBlockSameTick(GameTestHelper helper) {
        ServerPlayer first = join(helper, "SameBlockA");
        ServerPlayer second = join(helper, "SameBlockB");
        try {
            helper.assertValueEqual(first.blockPosition(), second.blockPosition(), "the two players' block");
            first.getInventory().add(new ItemStack(Items.DIAMOND, 5));
            second.getInventory().add(new ItemStack(Items.EMERALD, 7));
            kill(helper, first);
            kill(helper, second);

            Grave firstGrave = onlyGrave(helper, first);
            Grave secondGrave = onlyGrave(helper, second);
            helper.assertFalse(firstGrave.pos().equals(secondGrave.pos()), "both graves at " + firstGrave.pos());
            helper.assertValueEqual(GraveAsserts.count(firstGrave.items(), Items.DIAMOND), 5, "diamonds in the first grave");
            helper.assertValueEqual(GraveAsserts.count(firstGrave.items(), Items.EMERALD), 0, "emeralds in the first grave");
            helper.assertValueEqual(GraveAsserts.count(secondGrave.items(), Items.EMERALD), 7, "emeralds in the second grave");
            helper.assertValueEqual(GraveAsserts.count(secondGrave.items(), Items.DIAMOND), 0, "diamonds in the second grave");
            helper.assertValueEqual(GraveItems.ownerId(firstGrave.items().get(0)), Optional.of(first.getUUID()), "first grave's head");
            helper.assertValueEqual(GraveItems.ownerId(secondGrave.items().get(0)), Optional.of(second.getUUID()), "second grave's head");
            assertHeadBlock(helper, firstGrave);
            assertHeadBlock(helper, secondGrave);
        } finally {
            cleanUp(helper, first);
            cleanUp(helper, second);
        }
        helper.succeed();
    }

    // Standing on a block in the top layer, so the player is above the build limit. No head can go up there, but the
    // one-block platform's own layer is the nearest height a head fits, so the grave goes beside the platform, where items
    // dropped from it still fall to the floor.
    @GameTest(skyAccess = true)
    public void deathAboveBuildLimitGraveBesideThePlatform(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos top = helper.absolutePos(new BlockPos(3, 0, 3)).atY(level.getMaxY());
        ServerPlayer player = TestPlayers.join(level.getServer(), "BuildLimit", GameType.SURVIVAL, top.above());
        try {
            level.setBlock(top, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            BlockPos pos = grave.pos();
            helper.assertValueEqual(pos.getY(), level.getMaxY(), "grave height");
            helper.assertValueEqual(Math.max(Math.abs(pos.getX() - top.getX()), Math.abs(pos.getZ() - top.getZ())), 1,
                    "blocks from the platform to the grave " + pos);
            assertHeadBlock(helper, grave);
            BlockPos floor = pos.below();
            while (floor.getY() > level.getMinY() && level.getBlockState(floor).isAir()) {
                floor = floor.below();
            }
            helper.assertTrue(level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP),
                    "items dropped from the grave at " + pos + " would not land on a floor");
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.DIAMOND), 3, "diamonds in the grave");
        } finally {
            cleanUp(helper, player);
            level.setBlock(top, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        helper.succeed();
    }

    // Standing on a floor in the layer below the top, so the feet are in the top layer, which is inside the world: the
    // grave takes the death spot on the floor rather than a spot off its edge.
    @GameTest(skyAccess = true)
    public void deathInTopLayerGraveAtTheDeathSpot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos death = helper.absolutePos(new BlockPos(3, 0, 3)).atY(level.getMaxY());
        BlockPos floorCorner = death.offset(-3, -1, -3);
        BlockPos.betweenClosed(floorCorner, floorCorner.offset(6, 0, 6))
                .forEach(pos -> level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL));
        ServerPlayer player = TestPlayers.join(level.getServer(), "TopLayer", GameType.SURVIVAL, death);
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            helper.assertValueEqual(grave.pos(), death, "grave position");
            assertReachableAndItemSafe(helper, grave);
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.DIAMOND), 3, "diamonds in the grave");
        } finally {
            cleanUp(helper, player);
            BlockPos.betweenClosed(floorCorner, floorCorner.offset(6, 0, 6))
                    .forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
        }
        helper.succeed();
    }

    // At the bottom of a shaft one block wide and two deep: the grave takes the death spot, where the shaft's open top
    // still reaches it
    @GameTest
    public void deathInOneByOneHoleGraveAtTheBottom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos bottom = helper.absolutePos(new BlockPos(3, 0, 3));
        List<BlockPos> walls = new ArrayList<>();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0) {
                        walls.add(bottom.offset(dx, dy, dz));
                    }
                }
            }
        }
        ServerPlayer player = TestPlayers.join(level.getServer(), "Hole", GameType.SURVIVAL, bottom);
        try {
            walls.forEach(wall -> level.setBlock(wall, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL));
            player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            helper.assertValueEqual(grave.pos(), bottom, "grave position");
            assertReachableAndItemSafe(helper, grave);
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.DIAMOND), 3, "diamonds in the grave");
        } finally {
            cleanUp(helper, player);
            walls.forEach(wall -> level.setBlock(wall, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
        }
        helper.succeed();
    }

    // The border is one column wide and the death column is stone from the bottom of the world to the top, so no block
    // anywhere can take a grave and the placement throws. The death then drops the items where they last, each stack
    // once. Set and put back in this tick.
    @GameTest(skyAccess = true)
    public void noFreeBlockForAGraveDropsTheItemsInstead(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorldBorder border = level.getWorldBorder();
        double size = border.getSize();
        double centerX = border.getCenterX();
        double centerZ = border.getCenterZ();
        BlockPos death = helper.absolutePos(new BlockPos(3, 0, 3));
        Map<BlockPos, BlockState> before = new HashMap<>();
        AABB around = new AABB(death).inflate(2);
        ServerPlayer player = TestPlayers.join(level.getServer(), "Columnless", GameType.SURVIVAL, death);
        String name = player.getGameProfile().name();
        try {
            for (int y = level.getMinY(); y <= level.getMaxY(); y++) {
                VoidGraveGameTests.setRemembered(level, death.atY(y), Blocks.STONE, before);
            }
            player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
            player.getInventory().add(new ItemStack(Items.IRON_SWORD));
            border.setCenter(death.getX() + 0.5, death.getZ() + 0.5);
            border.setSize(1);
            List<LogEvent> log = OfflineGameTests.yoltEvents(() -> kill(helper, player));

            List<LogEvent> failed = log.stream()
                    .filter(event -> event.getMessage().getFormattedMessage().startsWith("Could not make a grave for "))
                    .toList();
            helper.assertValueEqual(failed.stream().map(event -> event.getMessage().getFormattedMessage()).toList(),
                    List.of("Could not make a grave for " + name + ", dropping their items instead"), "the failed grave's log lines");
            Throwable thrown = failed.get(0).getThrown();
            helper.assertTrue(thrown instanceof IllegalStateException, "the failed grave's error: " + thrown);
            helper.assertValueEqual(thrown.getMessage(),
                    "No free block for a grave in the column at " + level.dimension().identifier() + " " + death,
                    "the failed grave's error");
            helper.assertValueEqual(log.stream().map(event -> event.getMessage().getFormattedMessage())
                            .filter(line -> line.startsWith("Dropped ")).toList(),
                    List.of("Dropped " + name + "'s items at " + level.dimension().identifier() + " " + death
                            + ", since their grave could not be made"), "the drop's log lines");

            helper.assertTrue(gravesOf(helper, player.getUUID()).isEmpty(), "a grave was made");
            helper.assertTrue(level.getBlockState(death).is(Blocks.STONE), "the block at the death spot changed");
            helper.assertTrue(player.getInventory().isEmpty(), "the dead player still holds items");
            List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, around, Entity::isAlive);
            helper.assertValueEqual(drops.size(), 2, "stacks dropped");
            helper.assertValueEqual(itemsNear(helper, Items.DIAMOND, death, 2.0), 3, "diamonds dropped");
            helper.assertValueEqual(itemsNear(helper, Items.IRON_SWORD, death, 2.0), 1, "swords dropped");
            for (ItemEntity drop : drops) {
                helper.assertValueEqual(drop.blockPosition(), death, drop.getItem() + "'s spot");
                helper.assertValueEqual(drop.getAge(), (int) Short.MIN_VALUE, drop.getItem() + "'s age");
            }
        } finally {
            border.setSize(size);
            border.setCenter(centerX, centerZ);
            VoidGraveGameTests.putBack(level, before);
            level.getEntitiesOfClass(ItemEntity.class, around).forEach(Entity::discard);
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void emptyInventoryStillMakesGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Empty");
        try {
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            helper.assertValueEqual(grave.items().size(), 1, "grave item count");
            helper.assertTrue(GraveItems.isGraveHead(grave.items().get(0)), "the only item is not the grave head");
            assertHeadBlock(helper, grave);
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void keepInventoryLeavesItemsAndGraveHasOnlyHead(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameRules rules = server.getGameRules();
        ServerPlayer player = join(helper, "Keep");
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            boolean before = rules.get(GameRules.KEEP_INVENTORY);
            rules.set(GameRules.KEEP_INVENTORY, true, server);
            try {
                kill(helper, player);
            } finally {
                rules.set(GameRules.KEEP_INVENTORY, before, server);
            }

            helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND), 1, "player's diamonds");
            Grave grave = onlyGrave(helper, player);
            helper.assertValueEqual(grave.items().size(), 1, "grave item count");
            helper.assertTrue(GraveItems.isGraveHead(grave.items().get(0)), "the only item is not the grave head");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void curseOfVanishingItemsDoNotEnterGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Vanish");
        try {
            ItemStack helmet = new ItemStack(Items.IRON_HELMET);
            helmet.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.VANISHING_CURSE), 1);
            player.setItemSlot(EquipmentSlot.HEAD, helmet);
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.IRON_HELMET), 0,
                    "cursed helmets in the grave");
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.DIAMOND), 1, "diamonds in the grave");
            helper.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "cursed helmet stayed on the player");
            helper.assertValueEqual(itemsNear(helper, Items.IRON_HELMET, player.blockPosition(), 3.0), 0, "cursed helmets dropped");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    // A grave or revive head with curse of vanishing goes into the grave like any other item, while the cursed helmet
    // beside it still vanishes. The same for an offline death, which keeps the curse its own way.
    @GameTest
    public void cursedReviveHeadGoesToGrave(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Holder<Enchantment> vanishing = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.VANISHING_CURSE);
        ServerPlayer player = join(helper, "CursedHead");
        ServerPlayer away = join(helper, "CursedAway");
        NameAndId awayWho = away.nameAndId();
        try {
            carryCursedHeads(player, vanishing);
            kill(helper, player);
            assertHeadsKept(helper, onlyGrave(helper, player).items(), "online");

            carryCursedHeads(away, vanishing);
            TestPlayers.leave(away);
            helper.assertValueEqual(Kills.kill(server, awayWho), Kills.Result.KILLED_OFFLINE, "offline kill result");
            List<Grave> awayGraves = gravesOf(helper, awayWho.id());
            helper.assertValueEqual(awayGraves.size(), 1, "graves for the player killed offline");
            assertHeadsKept(helper, awayGraves.get(0).items(), "offline");
        } finally {
            cleanUp(helper, player);
            leaveAndRemoveGraves(helper, awayWho.id());
        }
        helper.succeed();
    }

    // A revive head and an older grave's head, both cursed, and a cursed helmet worn
    private static void carryCursedHeads(ServerPlayer player, Holder<Enchantment> vanishing) {
        ItemStack revive = ReviveGameTests.reviveHead(ResolvableProfile.createResolved(new GameProfile(UUID.randomUUID(), "Ghost")), 1);
        revive.enchant(vanishing, 1);
        ItemStack graveHead = GraveItems.graveHead(ResolvableProfile.createResolved(new GameProfile(UUID.randomUUID(), "Ghost")));
        graveHead.enchant(vanishing, 1);
        ItemStack helmet = new ItemStack(Items.IRON_HELMET);
        helmet.enchant(vanishing, 1);
        player.getInventory().add(revive);
        player.getInventory().add(graveHead);
        player.setItemSlot(EquipmentSlot.HEAD, helmet);
    }

    // The grave's own head, the carried grave head and the revive head are all there, each once, and the helmet isn't
    private static void assertHeadsKept(GameTestHelper helper, List<ItemStack> items, String death) {
        helper.assertValueEqual(items.stream().filter(GraveItems::isReviveHead).mapToInt(ItemStack::getCount).sum(), 1,
                "revive heads in the grave of the " + death + " death");
        helper.assertValueEqual(items.stream().filter(GraveItems::isGraveHead).mapToInt(ItemStack::getCount).sum(), 2,
                "grave heads in the grave of the " + death + " death");
        helper.assertValueEqual(GraveAsserts.count(items, Items.IRON_HELMET), 0, "cursed helmets in the grave of the " + death + " death");
    }

    @GameTest
    public void cursorAndCraftingGridItemsGoInTheGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Cursor");
        try {
            player.containerMenu.setCarried(new ItemStack(Items.GOLD_INGOT, 3));
            player.inventoryMenu.getSlot(InventoryMenu.CRAFT_SLOT_START).set(new ItemStack(Items.STICK, 2));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.GOLD_INGOT), 3,
                    "cursor gold ingots in the grave");
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.STICK), 2,
                    "crafting grid sticks in the grave");
            helper.assertTrue(player.containerMenu.getCarried().isEmpty(), "cursor still holds an item");
            assertNoItems(helper, player.inventoryMenu, InventoryMenu.CRAFT_SLOT_START, InventoryMenu.CRAFT_SLOT_END);
            BlockPos at = player.blockPosition();
            helper.assertValueEqual(itemsNear(helper, Items.GOLD_INGOT, at, 3.0), 0, "gold ingots dropped");
            helper.assertValueEqual(itemsNear(helper, Items.STICK, at, 3.0), 0, "sticks dropped");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    // The beacon payment slot is thrown out by the menu's own close code, not the cursor or crafting path
    @GameTest
    public void beaconMenuItemGoesInTheGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Beacon");
        try {
            player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new BeaconMenu(id, inventory), Component.empty()));
            AbstractContainerMenu menu = player.containerMenu;
            helper.assertTrue(menu instanceof BeaconMenu, "beacon menu did not open");
            menu.getSlot(0).set(new ItemStack(Items.IRON_INGOT));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            int ingots = grave.items().stream().filter(stack -> stack.is(Items.IRON_INGOT)).mapToInt(ItemStack::getCount).sum();
            helper.assertValueEqual(ingots, 1, "iron ingots in the grave");
            helper.assertValueEqual(itemsNear(helper, Items.IRON_INGOT, player.blockPosition(), 3.0), 0, "iron ingots dropped");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    // Any open menu is closed through its own code, so a crafting table stands in for the other kinds
    @GameTest
    public void openCraftingTableItemsGoInTheGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Table");
        try {
            BlockPos table = new BlockPos(2, 1, 1);
            helper.setBlock(table, Blocks.CRAFTING_TABLE);
            ContainerLevelAccess access = ContainerLevelAccess.create(helper.getLevel(), helper.absolutePos(table));
            player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new CraftingMenu(id, inventory, access), Component.empty()));
            AbstractContainerMenu menu = player.containerMenu;
            helper.assertTrue(menu instanceof CraftingMenu, "crafting table menu did not open");
            menu.setCarried(new ItemStack(Items.GOLD_INGOT, 3));
            menu.getSlot(1).set(new ItemStack(Items.FEATHER, 4));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.GOLD_INGOT), 3,
                    "cursor gold ingots in the grave");
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.FEATHER), 4,
                    "crafting table feathers in the grave");
            helper.assertTrue(player.containerMenu == player.inventoryMenu, "crafting table menu still open");
            helper.assertTrue(menu.getCarried().isEmpty(), "cursor still holds an item");
            assertNoItems(helper, menu, 1, 10);
            BlockPos at = player.blockPosition();
            helper.assertValueEqual(itemsNear(helper, Items.GOLD_INGOT, at, 3.0), 0, "gold ingots dropped");
            helper.assertValueEqual(itemsNear(helper, Items.FEATHER, at, 3.0), 0, "feathers dropped");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void enderChestKeep(GameTestHelper helper) {
        ServerPlayer player = join(helper, "EnderKeep");
        try {
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD));
            killWithEnderMode(helper, player, YoltConfig.EnderChestMode.KEEP);

            helper.assertValueEqual(player.getEnderChestInventory().countItem(Items.EMERALD), 1, "emeralds in the ender chest");
            helper.assertValueEqual(GraveAsserts.count(onlyGrave(helper, player).items(), Items.EMERALD), 0,
                    "emeralds in the grave");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void enderChestGrave(GameTestHelper helper) {
        ServerPlayer player = join(helper, "EnderGrave");
        try {
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 5));
            killWithEnderMode(helper, player, YoltConfig.EnderChestMode.GRAVE);

            helper.assertValueEqual(GraveAsserts.count(onlyGrave(helper, player).items(), Items.EMERALD), 5,
                    "emeralds in the grave");
            helper.assertTrue(player.getEnderChestInventory().isEmpty(), "ender chest still holds items");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void enderChestWipe(GameTestHelper helper) {
        ServerPlayer player = join(helper, "EnderWipe");
        try {
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD));
            killWithEnderMode(helper, player, YoltConfig.EnderChestMode.WIPE);

            helper.assertTrue(player.getEnderChestInventory().isEmpty(), "ender chest not wiped");
            helper.assertValueEqual(GraveAsserts.count(onlyGrave(helper, player).items(), Items.EMERALD), 0,
                    "wiped emeralds in the grave");
            // Wiped means gone, not dropped
            helper.assertValueEqual(itemsNear(helper, Items.EMERALD, player.blockPosition(), 3.0), 0, "emeralds dropped");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void gravesOffMeansVanillaDrops(GameTestHelper helper) {
        YoltConfig.GravesSection graves = Settings.get().graves;
        ServerPlayer player = join(helper, "NoGrave");
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            boolean before = graves.enabled;
            graves.enabled = false;
            try {
                kill(helper, player);
            } finally {
                graves.enabled = before;
            }

            // Every item entity in the test area, so a second drop beside vanilla's shows wherever it lands
            List<ItemEntity> drops = helper.getEntities(EntityTypes.ITEM);
            helper.assertValueEqual(drops.size(), 1, "item entities in the test area");
            helper.assertTrue(ItemStack.matches(drops.get(0).getItem(), new ItemStack(Items.DIAMOND)),
                    "dropped item: " + drops.get(0).getItem());
            helper.assertTrue(gravesOf(helper, player.getUUID()).isEmpty(), "grave made with graves off");
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    @GameTest
    public void moreThan54StacksMakesTwoGraves(GameTestHelper helper) {
        ServerPlayer player = join(helper, "Full");
        try {
            Inventory inventory = player.getInventory();
            for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
                inventory.setItem(i, new ItemStack(Items.COBBLESTONE));
            }
            PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
            for (int i = 0; i < enderChest.getContainerSize(); i++) {
                enderChest.setItem(i, new ItemStack(Items.EMERALD));
            }
            killWithEnderMode(helper, player, YoltConfig.EnderChestMode.GRAVE);

            // The store keeps graves in the order they were made
            List<Grave> found = gravesOf(helper, player.getUUID());
            helper.assertValueEqual(found.size(), 2, "graves for the player");
            Grave first = found.get(0);
            Grave second = found.get(1);
            helper.assertValueEqual(first.items().size() + second.items().size(), 64, "stacks across both graves");
            helper.assertValueEqual(first.items().size(), 54, "stacks in the first grave");
            helper.assertTrue(GraveItems.isGraveHead(first.items().get(0)), "first grave does not start with the head");
            helper.assertFalse(second.items().stream().anyMatch(GraveItems::isGraveHead), "second grave holds a grave head");
            helper.assertFalse(first.pos().equals(second.pos()), "both graves on one block");
            assertHeadBlock(helper, first);
            assertHeadBlock(helper, second);
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    // A put-back that throws drops what is left of the stack at the player: all of a stack it never took, and only the
    // rest of one it took part of before it threw
    @GameTest
    public void failedPutBackDropsTheStack(GameTestHelper helper) {
        ServerPlayer player = join(helper, "PutBack");
        BlockPos at = player.blockPosition();
        try {
            GraveDeath.putBackOrDrop(player, new ItemStack(Items.DIAMOND, 5), stack -> {
                throw new IllegalStateException("a put-back that fails, for this test");
            });
            GraveDeath.putBackOrDrop(player, new ItemStack(Items.EMERALD, 4), stack -> {
                player.getInventory().add(stack.split(1));
                throw new IllegalStateException("a put-back that fails halfway, for this test");
            });

            helper.assertValueEqual(itemsNear(helper, Items.DIAMOND, at, 3.0), 5, "diamonds dropped");
            helper.assertValueEqual(itemsNear(helper, Items.EMERALD, at, 3.0), 3, "emeralds dropped");
            helper.assertValueEqual(player.getInventory().countItem(Items.EMERALD), 1, "emeralds put back");
        } finally {
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(3.0)).forEach(ItemEntity::discard);
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    // With a full inventory, vanilla's put-back drops the very stack it was given. If it throws after that, as when
    // another mod's load listener throws for the new item entity, the stack is already in the world: dropping it again
    // would make the items exist twice, so the one dropped stack is all there is
    @GameTest
    public void failedPutBackAfterItsOwnDropDropsOnce(GameTestHelper helper) {
        ServerPlayer player = join(helper, "FullPutBack");
        BlockPos at = player.blockPosition();
        try {
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
            GraveDeath.putBackOrDrop(player, new ItemStack(Items.DIAMOND, 5), stack -> {
                player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
                throw new IllegalStateException("a put-back that fails after it dropped the stack, for this test");
            });

            helper.assertValueEqual(itemsNear(helper, Items.DIAMOND, at, 3.0), 5, "diamonds dropped");
            helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND), 0, "diamonds put back");
        } finally {
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(3.0)).forEach(ItemEntity::discard);
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    // A 3x3 lava pool with a stone rim, so the lava has nowhere to flow; the player dies in the middle of it.
    // No ceiling, which the search would take as a floor.
    @GameTest(skyAccess = true)
    public void lavaDeathPutsGraveOutOfLava(GameTestHelper helper) {
        List<BlockPos> lava = new ArrayList<>();
        List<BlockPos> rim = new ArrayList<>();
        ServerPlayer player = null;
        try {
            for (int x = 1; x <= 5; x++) {
                for (int z = 1; z <= 5; z++) {
                    BlockPos pos = new BlockPos(x, 0, z);
                    if (x == 1 || x == 5 || z == 1 || z == 5) {
                        rim.add(pos);
                        helper.setBlock(pos, Blocks.STONE);
                    } else {
                        lava.add(pos);
                        helper.setBlock(pos, Blocks.LAVA);
                    }
                }
            }
            player = TestPlayers.join(helper, "Lava", GameType.SURVIVAL, new BlockPos(3, 0, 3));
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            player.getInventory().add(new ItemStack(Items.IRON_SWORD));
            TestPlayers.markLoaded(player);
            ServerLevel level = helper.getLevel();
            player.hurtServer(level, level.damageSources().lava(), Float.MAX_VALUE);
            helper.assertTrue(player.isDeadOrDying(), "player did not die");

            Grave grave = onlyGrave(helper, player);
            List<BlockPos> pool = lava.stream().map(helper::absolutePos).toList();
            // The death spot is a pool block, so a grave left there, as before fix A1, fails the check after this one
            helper.assertTrue(pool.contains(player.blockPosition()), "the death spot " + player.blockPosition() + " is not in the pool");
            helper.assertFalse(pool.contains(grave.pos()), "grave placed in the lava pool at " + grave.pos());
            BlockPos below = grave.pos().below();
            helper.assertFalse(level.getFluidState(below).is(FluidTags.LAVA), "grave over lava at " + grave.pos());
            helper.assertFalse(level.getBlockState(below).getCollisionShape(level, below).isEmpty(),
                    "nothing solid under the grave at " + grave.pos());
            List<ItemStack> items = grave.items();
            helper.assertValueEqual(items.size(), 3, "grave item count");
            helper.assertTrue(GraveItems.isGraveHead(items.get(0)), "grave head is not the first item");
            helper.assertTrue(ItemStack.matches(items.get(1), new ItemStack(Items.DIAMOND)), "second item: " + items.get(1));
            helper.assertTrue(items.get(2).is(Items.IRON_SWORD), "third item is not the sword");
            assertHeadBlock(helper, grave);
        } finally {
            if (player != null) {
                cleanUp(helper, player);
            }
            for (BlockPos pos : lava) {
                helper.setBlock(pos, Blocks.AIR);
            }
            for (BlockPos pos : rim) {
                helper.setBlock(pos, Blocks.AIR);
            }
        }
        helper.succeed();
    }

    // Killed twenty blocks up, as when shot while gliding: the grave goes where the items would land, on the floor
    @GameTest(skyAccess = true)
    public void midAirDeathGraveLandsOnFloor(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Glider", GameType.SURVIVAL, new BlockPos(3, 20, 3));
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND, 2));
            kill(helper, player);

            Grave grave = onlyGrave(helper, player);
            helper.assertValueEqual(grave.pos(), helper.absolutePos(new BlockPos(3, 0, 3)), "grave position");
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.DIAMOND), 2, "diamonds in the grave");
            assertHeadBlock(helper, grave);
        } finally {
            cleanUp(helper, player);
        }
        helper.succeed();
    }

    // With no kick to save them, the emptied players are saved once the kick would have come: one dies with the lockout
    // off, one with a lockout of 0 days. Each save first holds the items, like an autosave before the death, so only a
    // save after the death can empty it before the next autosave. Each switch goes back in the same tick.
    @GameTest(maxTicks = 20)
    public void deathWithoutKickSavesEmptiedPlayer(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.DeathLockoutSection lockout = Settings.get().deathLockout;
        boolean enabledBefore = lockout.enabled;
        int daysBefore = lockout.cooldownDays;
        ServerPlayer lockoutOff = TestPlayers.join(helper, "SavedOff", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        ServerPlayer noDays = TestPlayers.join(helper, "SavedNoDays", GameType.SURVIVAL, new BlockPos(5, 1, 5));
        List<ServerPlayer> players = List.of(lockoutOff, noDays);
        Runnable cleanUp = () -> players.forEach(player -> cleanUp(helper, player));
        boolean waiting = false;
        try {
            for (ServerPlayer player : players) {
                player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
                ((PlayerListInvoker) server.getPlayerList()).yoltSave(player);
                helper.assertValueEqual(savedDiamonds(helper, player), 3, player.nameAndId().name() + "'s saved diamonds before the death");
            }
            lockout.enabled = false;
            try {
                kill(helper, lockoutOff);
            } finally {
                lockout.enabled = enabledBefore;
            }
            lockout.cooldownDays = 0;
            try {
                kill(helper, noDays);
            } finally {
                lockout.cooldownDays = daysBefore;
            }
            // The save is queued from a queued task, and a server running behind runs each within 4 ticks
            helper.runAfterDelay(10, () -> {
                try {
                    for (ServerPlayer player : players) {
                        String name = player.nameAndId().name();
                        helper.assertTrue(server.getPlayerList().getPlayer(player.getUUID()) == player, name + " was kicked");
                        assertSavedDead(helper, player, "after the tick");
                        helper.assertValueEqual(GraveAsserts.countInGraves(server, player.getUUID(), Items.DIAMOND), 3,
                                name + "'s diamonds in the grave");
                    }
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // Never says nothing even for a grave far below the death, and always tells even for one where the player died: in
    // chat after the respawn while the lockout is off, and in the kick while it is on. The setting is held until the
    // kick, so the test runs apart.
    @GameTest(environment = GRAVE_NOTICE, skyAccess = true, maxTicks = 30)
    public void tellLocationNeverAndAlways(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        YoltConfig config = Settings.get();
        YoltConfig.TellLocation modeBefore = config.graves.tellLocation;
        boolean lockoutBefore = config.deathLockout.enabled;
        ServerPlayer quiet = TestPlayers.join(helper, "Quiet", GameType.SURVIVAL, new BlockPos(3, 20, 3));
        ServerPlayer told = TestPlayers.join(helper, "Told", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        ServerPlayer kicked = TestPlayers.join(helper, "KickTold", GameType.SURVIVAL, new BlockPos(5, 1, 5));
        List<UUID> owners = List.of(quiet.getUUID(), told.getUUID(), kicked.getUUID());
        Runnable cleanUp = () -> {
            config.graves.tellLocation = modeBefore;
            config.deathLockout.enabled = lockoutBefore;
            for (UUID owner : owners) {
                ServerPlayer online = server.getPlayerList().getPlayer(owner);
                if (online != null) {
                    TestPlayers.leave(online);
                }
                removeGraves(helper, owner);
            }
        };
        boolean waiting = false;
        try {
            config.deathLockout.enabled = false;
            config.graves.tellLocation = YoltConfig.TellLocation.NEVER;
            ServerPlayer quietBack = dieAndRespawn(helper, quiet);
            Grave quietGrave = onlyGrave(helper, quiet);
            helper.assertValueEqual(quietGrave.pos(), helper.absolutePos(new BlockPos(3, 0, 3)), "quiet player's grave, 20 below the death");
            helper.assertValueEqual(graveLines(quietBack), List.of(), "chat with never");

            config.graves.tellLocation = YoltConfig.TellLocation.ALWAYS;
            ServerPlayer toldBack = dieAndRespawn(helper, told);
            Grave toldGrave = onlyGrave(helper, told);
            helper.assertValueEqual(toldGrave.pos(), helper.absolutePos(new BlockPos(1, 0, 1)), "told player's grave, just below the death");
            helper.assertValueEqual(graveLines(toldBack), List.of(graveLine(toldGrave)), "chat with always");

            // On again, so this death kicks; always is held until the kick
            config.deathLockout.enabled = lockoutBefore;
            kill(helper, kicked);
            Grave kickedGrave = onlyGrave(helper, kicked);
            // A server running behind still runs queued tasks within 4 ticks
            helper.runAfterDelay(5, () -> {
                try {
                    helper.assertTrue(server.getPlayerList().getPlayer(kicked.getUUID()) == null, "the dead player was not kicked");
                    String reason = LockoutGameTests.disconnectReason(kicked);
                    helper.assertTrue(reason.startsWith("You died: ") && reason.endsWith("\n" + graveLine(kickedGrave)),
                            "kick reason: " + reason);
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // The lockout is on, but a revive in the same tick leaves nothing to kick for. So the player is saved once the kick
    // would have come, like any death that doesn't kick, and told in chat at the respawn. A death 20 up puts the grave
    // far enough below for the default to tell. Only the death's own row changes, so nothing is held across ticks.
    @GameTest(skyAccess = true, maxTicks = 20)
    public void sameTickReviveSavesAndTellsInChat(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Revived", GameType.SURVIVAL, new BlockPos(3, 20, 3));
        Runnable cleanUp = () -> leaveAndRemoveGraves(helper, player.getUUID());
        boolean waiting = false;
        try {
            assertDefaultTelling(helper);
            helper.assertTrue(Settings.get().deathLockout.enabled && Settings.get().deathLockout.cooldownDays > 0,
                    "the lockout is not on with days to wait");
            player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
            ((PlayerListInvoker) server.getPlayerList()).yoltSave(player);
            helper.assertValueEqual(savedDiamonds(helper, player), 3, "saved diamonds before the death");
            respawnBesideTest(helper, player);
            kill(helper, player);
            helper.assertValueEqual(DeathLockout.revive(player.getUUID(), player.nameAndId().name(), "test"),
                    DeathLockout.ReviveResult.REVIVED, "the revive");
            Grave grave = onlyGrave(helper, player);
            helper.assertValueEqual(grave.pos(), helper.absolutePos(new BlockPos(3, 0, 3)), "grave, 20 below the death");
            // The save is queued from a queued task, and a server running behind runs each within 4 ticks
            helper.runAfterDelay(10, () -> {
                try {
                    helper.assertTrue(server.getPlayerList().getPlayer(player.getUUID()) == player, "the revived player was kicked");
                    assertSavedDead(helper, player, "after the tick");
                    ServerPlayer back = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
                    helper.assertValueEqual(graveLines(back), List.of(graveLine(grave)), "chat at the respawn");
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // A death that makes no grave says nothing, though the player has an older grave far from its own death: not in
    // chat at the respawn, and not in the lockout's refusal for a death recorded now. The older grave is a record from
    // an hour ago, in a chunk nothing loads. Every switch goes back in the same tick.
    @GameTest
    public void deathWithNoGraveNamesNoOlderGrave(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        boolean gravesBefore = config.graves.enabled;
        boolean lockoutBefore = config.deathLockout.enabled;
        ServerPlayer player = TestPlayers.join(helper, "Graveless", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String name = player.nameAndId().name();
        GraveStore store = GraveStore.get(server);
        BlockPos far = new BlockPos(30000, 70, 30000);
        Grave older = new Grave(UUID.randomUUID(), player.getUUID(), name, ResolvableProfile.createUnresolved(name), Level.OVERWORLD,
                far, List.of(new ItemStack(Items.DIAMOND)), System.currentTimeMillis() - 3_600_000L,
                GlobalPos.of(Level.OVERWORLD, far.east(100)));
        try {
            assertDefaultTelling(helper);
            store.put(older);
            respawnBesideTest(helper, player);
            // Both stay off through the respawn, as on a server that runs without them
            config.graves.enabled = false;
            config.deathLockout.enabled = false;
            ServerPlayer back;
            try {
                kill(helper, player);
                back = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            } finally {
                config.graves.enabled = gravesBefore;
                config.deathLockout.enabled = lockoutBefore;
            }
            helper.assertValueEqual(graveLines(back), List.of(), "chat at the respawn");

            DeathLockout.recordDeath(player.getUUID(), name);
            helper.assertValueEqual(refusalGraveLine(helper, server, player.getUUID(), name), Optional.empty(),
                    "grave line of the refusal");
        } finally {
            store.remove(older.id());
            leaveAndRemoveGraves(helper, player.getUUID());
        }
        helper.succeed();
    }

    // The default tells in chat only for a grave that went far: a death 20 up has its grave on the floor, one just above
    // the floor has it where the player died. The lockout is off for the deaths only.
    @GameTest(skyAccess = true)
    public void whenMovedTellsInChatOnlyForAFarGrave(GameTestHelper helper) {
        YoltConfig.DeathLockoutSection lockout = Settings.get().deathLockout;
        boolean lockoutBefore = lockout.enabled;
        ServerPlayer far = TestPlayers.join(helper, "MovedFar", GameType.SURVIVAL, new BlockPos(3, 20, 3));
        ServerPlayer near = TestPlayers.join(helper, "MovedNear", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            assertDefaultTelling(helper);
            ServerPlayer farBack;
            ServerPlayer nearBack;
            lockout.enabled = false;
            try {
                farBack = dieAndRespawn(helper, far);
                nearBack = dieAndRespawn(helper, near);
            } finally {
                lockout.enabled = lockoutBefore;
            }
            Grave farGrave = onlyGrave(helper, far);
            helper.assertValueEqual(farGrave.pos(), helper.absolutePos(new BlockPos(3, 0, 3)), "far grave, 20 below the death");
            helper.assertValueEqual(graveLines(farBack), List.of(graveLine(farGrave)), "chat for the far grave");
            helper.assertValueEqual(onlyGrave(helper, near).pos(), helper.absolutePos(new BlockPos(1, 0, 1)), "near grave, just below the death");
            helper.assertValueEqual(graveLines(nearBack), List.of(), "chat for the near grave");
        } finally {
            leaveAndRemoveGraves(helper, far.getUUID());
            leaveAndRemoveGraves(helper, near.getUUID());
        }
        helper.succeed();
    }

    // The kick and the login refusal name the death's grave by the death's time. They tell for a grave in another
    // dimension than the death, even at the same spot; by the distance as set; and name a dimension that isn't one of
    // the three by its id. A grave made after the death is a later death's, so it isn't named. The graves are records
    // made at the death's own time, as a death's graves are made just before it is recorded. The test server has no
    // dimension of its own, so that grave is a record in a dimension with no level. The distance goes back in the same
    // tick.
    @GameTest
    public void refusalTellsByDimensionAndDistance(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.GravesSection graves = Settings.get().graves;
        int distanceBefore = graves.tellLocationDistance;
        UUID owner = UUID.randomUUID();
        String name = "Elsewhere";
        GraveStore store = GraveStore.get(server);
        BlockPos at = new BlockPos(30000, 70, 30000);
        String overworldLine = "Your grave is at 30000, 70, 30000 in the Overworld.";
        List<Grave> made = new ArrayList<>();
        try {
            assertDefaultTelling(helper);
            DeathLockout.recordDeath(owner, name);
            DeathLog death = DeathLockout.newestDeath(owner);
            helper.assertTrue(death != null, "the death was not recorded");
            long died = death.deathTimeMillis();

            Grave nether = putRecord(store, made, owner, name, Level.OVERWORLD, at, GlobalPos.of(Level.NETHER, at), died);
            helper.assertValueEqual(refusalGraveLine(helper, server, owner, name), Optional.of(overworldLine),
                    "grave line for a death in the Nether at the same spot");
            store.remove(nether.id());

            putRecord(store, made, owner, name, Level.OVERWORLD, at, GlobalPos.of(Level.OVERWORLD, at.east(20)), died);
            graves.tellLocationDistance = 30;
            helper.assertValueEqual(refusalGraveLine(helper, server, owner, name), Optional.empty(),
                    "grave line for a grave 20 from the death, told past 30");
            graves.tellLocationDistance = 10;
            helper.assertValueEqual(refusalGraveLine(helper, server, owner, name), Optional.of(overworldLine),
                    "grave line for a grave 20 from the death, told past 10");
            graves.tellLocationDistance = distanceBefore;
            made.forEach(grave -> store.remove(grave.id()));

            ResourceKey<Level> modded = ResourceKey.create(Registries.DIMENSION,
                    Identifier.fromNamespaceAndPath("you-only-live-twice-gametest", "elsewhere"));
            putRecord(store, made, owner, name, modded, at, GlobalPos.of(Level.OVERWORLD, at), died);
            helper.assertValueEqual(refusalGraveLine(helper, server, owner, name),
                    Optional.of("Your grave is at 30000, 70, 30000 in you-only-live-twice-gametest:elsewhere."),
                    "grave line for a grave in a dimension of a mod's");
            made.forEach(grave -> store.remove(grave.id()));

            putRecord(store, made, owner, name, Level.OVERWORLD, at, GlobalPos.of(Level.NETHER, at), died + 1);
            helper.assertValueEqual(refusalGraveLine(helper, server, owner, name), Optional.empty(),
                    "grave line for a grave made after the death");
        } finally {
            graves.tellLocationDistance = distanceBefore;
            made.forEach(grave -> store.remove(grave.id()));
        }
        helper.succeed();
    }

    // An owner killed while away is told in chat at the respawn once they are back, as after an online death: here a
    // body killed with the lockout off. Always tells, since the grave is where the body stood. The whole test runs in
    // one tick, and both switches go back in it.
    @GameTest
    public void offlineDeathTellsInChatAtTheRespawn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        YoltConfig.TellLocation modeBefore = config.graves.tellLocation;
        boolean lockoutBefore = config.deathLockout.enabled;
        ServerPlayer player = TestPlayers.join(helper, "AwayTold", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection bodiesBefore = BodyTestKit.useDefaultBodies();
        try {
            config.graves.tellLocation = YoltConfig.TellLocation.ALWAYS;
            config.deathLockout.enabled = false;
            Mannequin body = BodyTestKit.leaveBody(helper, player);
            body.kill(helper.getLevel());
            Grave grave = onlyGrave(helper, player);
            ServerPlayer back = TestPlayers.rejoin(helper, player.getGameProfile(), GameType.SURVIVAL, new BlockPos(1, 1, 1));
            helper.assertTrue(back.isDeadOrDying(), "the owner did not come back dead");
            respawnBesideTest(helper, back);
            ServerPlayer respawned = server.getPlayerList().respawn(back, false, Entity.RemovalReason.KILLED);
            helper.assertValueEqual(graveLines(respawned), List.of(graveLine(grave)), "chat at the respawn");
        } finally {
            config.graves.tellLocation = modeBefore;
            config.deathLockout.enabled = lockoutBefore;
            BodyTestKit.cleanUp(helper, owner, bodiesBefore);
        }
        helper.succeed();
    }

    // The kick tells a locked-out player where their grave is, so the respawn after the lockout doesn't tell them again.
    // The notice is set the way a death sets it, for a grave record far from its death spot, so the default would tell;
    // then the lockout's own record of the death kicks, and a revive lets the player back.
    @GameTest(maxTicks = 20)
    public void kickedPlayerIsNotToldAgainAtTheRespawn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "KickedOnce", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        String name = player.nameAndId().name();
        GraveStore store = GraveStore.get(server);
        List<Grave> made = new ArrayList<>();
        Runnable cleanUp = () -> {
            made.forEach(grave -> store.remove(grave.id()));
            leaveAndRemoveGraves(helper, owner);
        };
        boolean waiting = false;
        try {
            assertDefaultTelling(helper);
            // Held across ticks, so at a spot of its own: other tests expect 30000, 70, 30000 to be free
            BlockPos at = new BlockPos(-30448, 70, 30448);
            Grave grave = putRecord(store, made, owner, name, Level.OVERWORLD, at, GlobalPos.of(Level.OVERWORLD, at.east(100)),
                    System.currentTimeMillis());
            GraveNotice.tellAtRespawn(owner, grave.id());
            DeathLockout.recordDeath(player);
            // A server running behind still runs queued tasks within 4 ticks
            helper.runAfterDelay(5, () -> {
                try {
                    helper.assertTrue(server.getPlayerList().getPlayer(owner) == null, "the player was not kicked");
                    String reason = LockoutGameTests.disconnectReason(player);
                    helper.assertTrue(reason.endsWith("\n" + graveLine(grave)), "kick reason: " + reason);
                    helper.assertValueEqual(DeathLockout.revive(owner, name, "test"), DeathLockout.ReviveResult.REVIVED, "the revive");
                    ServerPlayer back = TestPlayers.rejoin(helper, player.getGameProfile(), GameType.SURVIVAL, new BlockPos(1, 1, 1));
                    respawnBesideTest(helper, back);
                    ServerPlayer respawned = server.getPlayerList().respawn(back, false, Entity.RemovalReason.KILLED);
                    helper.assertValueEqual(graveLines(respawned), List.of(), "chat at the respawn after the lockout");
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // A way back from the End respawns a living player, not a dead one, so it tells nothing. A dead player can't take
    // one, so the test makes it: a death owed a line, then the End's respawn, then the death's own. The lockout is off
    // for the death only.
    @GameTest(skyAccess = true)
    public void wayBackFromTheEndTellsNothing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.DeathLockoutSection lockout = Settings.get().deathLockout;
        boolean lockoutBefore = lockout.enabled;
        ServerPlayer player = TestPlayers.join(helper, "FromTheEnd", GameType.SURVIVAL, new BlockPos(3, 20, 3));
        try {
            assertDefaultTelling(helper);
            respawnBesideTest(helper, player);
            lockout.enabled = false;
            try {
                kill(helper, player);
            } finally {
                lockout.enabled = lockoutBefore;
            }
            Grave grave = onlyGrave(helper, player);
            helper.assertValueEqual(grave.pos(), helper.absolutePos(new BlockPos(3, 0, 3)), "grave, 20 below the death");
            ServerPlayer fromEnd = server.getPlayerList().respawn(player, true, Entity.RemovalReason.CHANGED_DIMENSION);
            helper.assertValueEqual(graveLines(fromEnd), List.of(), "chat after the way back from the End");
            ServerPlayer back = server.getPlayerList().respawn(fromEnd, false, Entity.RemovalReason.KILLED);
            helper.assertValueEqual(graveLines(back), List.of(graveLine(grave)), "chat at the death's respawn");
        } finally {
            leaveAndRemoveGraves(helper, player.getUUID());
        }
        helper.succeed();
    }

    // Killed, then back through the death screen's respawn at a point set beside the test, so no random spot is searched
    private static ServerPlayer dieAndRespawn(GameTestHelper helper, ServerPlayer player) {
        respawnBesideTest(helper, player);
        kill(helper, player);
        return helper.getLevel().getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
    }

    static void respawnBesideTest(GameTestHelper helper, ServerPlayer player) {
        player.setRespawnPosition(new ServerPlayer.RespawnConfig(
                LevelData.RespawnData.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(7, 1, 7)), 0.0F, 0.0F), true), false);
    }

    // The tests that rely on the defaults: tell only for a grave more than 16 blocks from the death
    private static void assertDefaultTelling(GameTestHelper helper) {
        helper.assertValueEqual(Settings.get().graves.tellLocation, YoltConfig.TellLocation.WHEN_MOVED, "tellLocation");
        helper.assertValueEqual(Settings.get().graves.tellLocationDistance, 16, "tellLocationDistance");
    }

    // A grave record only, with no head in the world
    private static Grave putRecord(GraveStore store, List<Grave> made, UUID owner, String name, ResourceKey<Level> dimension,
                                   BlockPos pos, GlobalPos died, long createdAt) {
        Grave grave = new Grave(UUID.randomUUID(), owner, name, ResolvableProfile.createUnresolved(name), dimension, pos,
                List.of(new ItemStack(Items.DIAMOND)), createdAt, died);
        store.put(grave);
        made.add(grave);
        return grave;
    }

    // The grave line of the refusal the owner gets now, the kick's text too
    private static Optional<String> refusalGraveLine(GameTestHelper helper, MinecraftServer server, UUID owner, String name) {
        Component refusal = DeathLockout.refusal(server, owner, name);
        helper.assertTrue(refusal != null, name + " is not locked out");
        String[] lines = refusal.getString().split("\n", -1);
        helper.assertTrue(lines.length == 2 || lines.length == 3, "refusal: " + refusal.getString());
        return lines.length == 3 ? Optional.of(lines[2]) : Optional.empty();
    }

    // Whichever player object is online for the owner now leaves, so a queued kick finds nobody
    private static void leaveAndRemoveGraves(GameTestHelper helper, UUID owner) {
        ServerPlayer online = helper.getLevel().getServer().getPlayerList().getPlayer(owner);
        if (online != null) {
            TestPlayers.leave(online);
        }
        removeGraves(helper, owner);
    }

    // The grave lines a player was sent in chat, oldest first
    private static List<String> graveLines(ServerPlayer player) {
        return TestPlayers.sentPackets(player).stream()
                .filter(packet -> packet instanceof ClientboundSystemChatPacket)
                .map(packet -> ((ClientboundSystemChatPacket) packet).content().getString())
                .filter(text -> text.startsWith("Your grave is at "))
                .toList();
    }

    private static String graveLine(Grave grave) {
        BlockPos pos = grave.pos();
        return "Your grave is at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " in the Overworld.";
    }

    private static int savedDiamonds(GameTestHelper helper, ServerPlayer player) {
        MinecraftServer server = helper.getLevel().getServer();
        CompoundTag saved = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
        return GraveAsserts.count(OfflinePlayerData.takeInventory(saved, server.registryAccess(), player.nameAndId().name()), Items.DIAMOND);
    }

    // The save on disk is a dead player's with nothing left on them
    private static void assertSavedDead(GameTestHelper helper, ServerPlayer player, String when) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = player.nameAndId().name();
        CompoundTag saved = OfflinePlayerData.load(server, player.nameAndId()).orElseThrow();
        helper.assertTrue(OfflinePlayerData.takeInventory(saved, server.registryAccess(), name).isEmpty(),
                name + "'s save still has items " + when);
        helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 0.0F, name + "'s saved health " + when);
    }

    // Counts the item across the item entities near an absolute position. Absolute, since vanilla's relativePos turns
    // an unrotated test's position half round.
    static int itemsNear(GameTestHelper helper, Item item, BlockPos absolutePos, double distance) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(absolutePos).inflate(distance), Entity::isAlive).stream()
                .map(ItemEntity::getItem)
                .filter(stack -> stack.is(item))
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    private static ServerPlayer join(GameTestHelper helper, String name) {
        return TestPlayers.join(helper, name, GameType.SURVIVAL, new BlockPos(1, 1, 1));
    }

    private static void kill(GameTestHelper helper, ServerPlayer player) {
        TestPlayers.markLoaded(player);
        player.kill(player.level());
        helper.assertTrue(player.isDeadOrDying(), "player did not die");
    }

    private static void killWithEnderMode(GameTestHelper helper, ServerPlayer player, YoltConfig.EnderChestMode mode) {
        YoltConfig.GravesSection graves = Settings.get().graves;
        YoltConfig.EnderChestMode before = graves.enderChestOnDeath;
        graves.enderChestOnDeath = mode;
        try {
            kill(helper, player);
        } finally {
            graves.enderChestOnDeath = before;
        }
    }

    private static List<Grave> gravesOf(GameTestHelper helper, UUID owner) {
        return GraveStore.get(helper.getLevel().getServer()).all().stream()
                .filter(grave -> grave.owner().equals(owner))
                .toList();
    }

    private static Grave onlyGrave(GameTestHelper helper, ServerPlayer player) {
        List<Grave> graves = gravesOf(helper, player.getUUID());
        helper.assertValueEqual(graves.size(), 1, "graves for the player");
        return graves.get(0);
    }

    private static void assertNoItems(GameTestHelper helper, AbstractContainerMenu menu, int fromSlot, int toSlot) {
        for (int i = fromSlot; i < toSlot; i++) {
            helper.assertFalse(menu.getSlot(i).hasItem(), "menu slot " + i + " still holds an item");
        }
    }

    // The grave's head stands inside the world with open air above it, so a player can get at it, on a solid floor that
    // its items would rest on if it were removed
    private static void assertReachableAndItemSafe(GameTestHelper helper, Grave grave) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = grave.pos();
        assertHeadBlock(helper, grave);
        helper.assertTrue(level.isInsideBuildHeight(pos), "grave outside the build height at " + pos);
        helper.assertTrue(level.getBlockState(pos.above()).isAir(), "no open air above the grave at " + pos);
        helper.assertTrue(level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP),
                "no solid floor under the grave at " + pos);
    }

    // 26 stacks of 64 diamonds and the named sword, as the box was packed
    private static void assertBoxHolds(GameTestHelper helper, ItemStack box, String where) {
        helper.assertTrue(box.is(Items.SHULKER_BOX), "not a shulker box " + where + ": " + box);
        List<ItemStack> held = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItemCopyStream().toList();
        helper.assertValueEqual(held.size(), 27, "stacks in the shulker box " + where);
        helper.assertValueEqual(GraveAsserts.count(held, Items.DIAMOND), 26 * 64, "diamonds in the shulker box " + where);
        helper.assertValueEqual(held.stream().filter(stack -> stack.is(Items.IRON_SWORD)
                && stack.getHoverName().getString().equals("Keepsake")).count(), 1L, "named swords in the shulker box " + where);
    }

    private static void assertHeadBlock(GameTestHelper helper, Grave grave) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(level.getBlockState(grave.pos()).is(Blocks.PLAYER_HEAD), "no player head at " + grave.pos());
        helper.assertTrue(level.getBlockEntity(grave.pos()) instanceof SkullBlockEntity skull
                && grave.profile().equals(skull.getOwnerProfile()), "head at " + grave.pos() + " lacks the owner's profile");
    }

    // Leaving first means the queued death kick finds nobody; the graves go too, so no test sees another's
    private static void cleanUp(GameTestHelper helper, ServerPlayer player) {
        TestPlayers.leave(player);
        removeGraves(helper, player.getUUID());
    }

    private static void removeGraves(GameTestHelper helper, UUID owner) {
        GraveStore store = GraveStore.get(helper.getLevel().getServer());
        for (Grave grave : gravesOf(helper, owner)) {
            store.remove(grave.id());
            helper.getLevel().setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
