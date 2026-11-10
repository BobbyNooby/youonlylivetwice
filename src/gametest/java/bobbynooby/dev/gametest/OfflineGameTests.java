package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.Kills;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.offline.OfflinePlayerData;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

public class OfflineGameTests {
    @GameTest(maxTicks = 40)
    public void offlineKillDropsSavedItemsAndLocksOut(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Off", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        BlockPos last = helper.relativePos(player.blockPosition());
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            player.giveExperienceLevels(30);
        } finally {
            // Leaving saves the player, like a real logout
            TestPlayers.leave(player);
        }

        CompoundTag before = OfflinePlayerData.load(server, who).orElseThrow();
        helper.assertFalse(before.getListOrEmpty("Inventory").isEmpty(), "inventory was not saved");
        helper.assertFalse(before.getCompoundOrEmpty("equipment").isEmpty(), "equipment was not saved");

        // With graves off the items are dropped, which is the path this test covers
        YoltConfig.GravesSection graves = Settings.get().graves;
        boolean gravesBefore = graves.enabled;
        graves.enabled = false;
        try {
            helper.assertValueEqual(Kills.kill(server, who), Kills.Result.KILLED_OFFLINE, "kill result");
        } finally {
            graves.enabled = gravesBefore;
        }
        helper.assertValueEqual(itemEntities(helper, Items.DIAMOND, last), 1, "diamond item entities");
        helper.assertItemEntityCountIs(Items.DIAMOND, last, 2.0, 1);
        helper.assertValueEqual(itemEntities(helper, Items.IRON_HELMET, last), 1, "iron helmet item entities");
        helper.assertEntityPresent(EntityTypes.EXPERIENCE_ORB, last, 2.0);

        CompoundTag saved = OfflinePlayerData.load(server, who).orElseThrow();
        helper.assertTrue(saved.getListOrEmpty("Inventory").isEmpty(), "saved inventory still has items");
        helper.assertTrue(saved.getCompoundOrEmpty("equipment").isEmpty(), "saved equipment still has items");
        helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 0.0F, "saved health");
        helper.assertValueEqual(saved.getIntOr("XpLevel", -1), 0, "saved XP level");
        helper.assertValueEqual(saved.getIntOr("XpTotal", -1), 0, "saved total XP");
        String refusal = LockoutGameTests.refusalText(who.id());
        helper.assertTrue(refusal.startsWith("You died: Killed while away\nTry again in "), "offline kill's lockout: " + refusal);
        helper.succeed();
    }

    // A non-empty folder where the old save gets moved can't be deleted, so the swap of the new save fails.
    // Graves are off so this covers taking dropped items back; GraveProtectionGameTests covers graves.
    @GameTest
    public void offlineKillFailureLeavesNoDuplicate(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Fail", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        BlockPos last = helper.relativePos(player.blockPosition());
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            player.giveExperienceLevels(30);
        } finally {
            TestPlayers.leave(player);
        }

        Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        Path blocker = dir.resolve(who.id() + ".dat_old");
        Path blockerContent = blocker.resolve("blocker");
        Files.createDirectories(blocker);
        Files.writeString(blockerContent, "");
        YoltConfig.GravesSection graves = Settings.get().graves;
        boolean gravesBefore = graves.enabled;
        graves.enabled = false;
        boolean failed = false;
        try {
            Kills.kill(server, who);
        } catch (RuntimeException expected) {
            failed = true;
        } finally {
            graves.enabled = gravesBefore;
            Files.delete(blockerContent);
            Files.delete(blocker);
        }

        helper.assertTrue(failed, "kill did not report the failed save");
        helper.assertValueEqual(itemEntities(helper, Items.DIAMOND, last), 0, "diamond item entities");
        helper.assertTrue(helper.getEntities(EntityTypes.EXPERIENCE_ORB, last, 2.0).isEmpty(), "XP orbs left behind");
        CompoundTag saved = OfflinePlayerData.load(server, who).orElseThrow();
        helper.assertTrue(OfflinePlayerData.takeInventory(saved, server.registryAccess(), who.name()).stream().anyMatch(item -> item.is(Items.DIAMOND)),
                "save lost the diamond");
        helper.assertValueEqual(saved.getIntOr("XpLevel", -1), 30, "saved XP level");
        helper.assertTrue(DeathLockout.refusal(who.id(), "Test") == null, "failed kill started the lockout");
        try (Stream<Path> files = Files.list(dir)) {
            helper.assertTrue(files.noneMatch(file -> file.getFileName().toString().startsWith(who.id() + "-")), "temp save file left behind");
        }
        helper.succeed();
    }

    // A second kill must not drop anything, write the save, or restart the lockout
    @GameTest
    public void offlineKillOfDeadPlayerChangesNothing(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Twice", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        BlockPos last = helper.relativePos(player.blockPosition());
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            player.giveExperienceLevels(30);
        } finally {
            TestPlayers.leave(player);
        }
        try {
            helper.assertValueEqual(Kills.kill(server, who), Kills.Result.KILLED_OFFLINE, "first kill result");

            // Clear the first death's drops and lockout, so anything found afterwards came from the second kill
            helper.getEntities(EntityTypes.ITEM, last, 2.0).forEach(Entity::discard);
            helper.getEntities(EntityTypes.EXPERIENCE_ORB, last, 2.0).forEach(Entity::discard);
            helper.assertValueEqual(DeathLockout.revive(who.id(), "Test", "console"), DeathLockout.ReviveResult.REVIVED, "revive after the first kill");
            byte[][] filesBefore = saveFiles(server, who);
            int gravesBefore = gravesOf(helper, who.id()).size();

            helper.assertValueEqual(Kills.kill(server, who), Kills.Result.ALREADY_DEAD, "second kill result");
            helper.assertTrue(helper.getEntities(EntityTypes.ITEM, last, 2.0).isEmpty(), "second kill dropped items");
            helper.assertTrue(helper.getEntities(EntityTypes.EXPERIENCE_ORB, last, 2.0).isEmpty(), "second kill dropped XP");
            helper.assertValueEqual(gravesOf(helper, who.id()).size(), gravesBefore, "graves after the second kill");
            helper.assertTrue(Arrays.deepEquals(saveFiles(server, who), filesBefore), "second kill wrote the save");
            helper.assertTrue(DeathLockout.refusal(who.id(), "Test") == null, "second kill restarted the lockout");
        } finally {
            removeGraves(helper, who.id());
        }
        helper.succeed();
    }

    @GameTest
    public void offlineKillAppliesEnderChestMode(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Ender", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        BlockPos last = helper.relativePos(player.blockPosition());
        try {
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD));
        } finally {
            TestPlayers.leave(player);
        }
        helper.assertFalse(enderChest(server, who).isEmpty(), "ender chest was not saved");

        YoltConfig.GravesSection graves = Settings.get().graves;
        YoltConfig.EnderChestMode before = graves.enderChestOnDeath;
        try {
            graves.enderChestOnDeath = YoltConfig.EnderChestMode.WIPE;
            try {
                helper.assertValueEqual(Kills.kill(server, who), Kills.Result.KILLED_OFFLINE, "kill result");
            } finally {
                graves.enderChestOnDeath = before;
            }
            helper.assertTrue(enderChest(server, who).isEmpty(), "ender chest not wiped");
            // Wiped means gone, not dropped or kept in a grave
            helper.assertItemEntityNotPresent(Items.EMERALD, last, 2.0);
            helper.assertFalse(gravesOf(helper, who.id()).stream()
                    .anyMatch(grave -> grave.items().stream().anyMatch(item -> item.is(Items.EMERALD))), "grave holds the wiped emerald");
        } finally {
            removeGraves(helper, who.id());
        }
        helper.succeed();
    }

    // An online death with graves off never reaches the ender chest setting, so an offline one mustn't either
    @GameTest
    public void offlineKillWithGravesOffKeepsEnderChest(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "EnderOff", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        BlockPos last = helper.relativePos(player.blockPosition());
        try {
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD));
        } finally {
            TestPlayers.leave(player);
        }

        YoltConfig.GravesSection graves = Settings.get().graves;
        boolean enabledBefore = graves.enabled;
        YoltConfig.EnderChestMode modeBefore = graves.enderChestOnDeath;
        graves.enabled = false;
        graves.enderChestOnDeath = YoltConfig.EnderChestMode.WIPE;
        try {
            helper.assertValueEqual(Kills.kill(server, who), Kills.Result.KILLED_OFFLINE, "kill result");
        } finally {
            graves.enabled = enabledBefore;
            graves.enderChestOnDeath = modeBefore;
        }
        helper.assertTrue(enderChest(server, who).stream().anyMatch(item -> item.is(Items.EMERALD)), "ender chest lost the emerald");
        helper.assertItemEntityNotPresent(Items.EMERALD, last, 2.0);
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void onlineKillRunsNormalDeath(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Online", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        UUID uuid = player.getUUID();
        boolean waitingForKick = false;
        try {
            TestPlayers.markLoaded(player);
            helper.assertValueEqual(Kills.kill(server, player.nameAndId()), Kills.Result.KILLED_ONLINE, "kill result");
            helper.assertTrue(player.isDeadOrDying(), "player is still alive");
            // The death kick waits for the end of the tick, so the death code still sees a connected player
            helper.assertTrue(server.getPlayerList().getPlayer(uuid) != null, "player was kicked inside the death call");
            helper.assertTrue(DeathLockout.refusal(uuid, "Test") != null, "death did not start the lockout");
            helper.assertValueEqual(Kills.kill(server, player.nameAndId()), Kills.Result.ALREADY_DEAD, "second kill result");

            // A server running behind still runs queued tasks within 4 ticks
            helper.runAfterDelay(5, () -> {
                boolean kicked = server.getPlayerList().getPlayer(uuid) == null;
                if (!kicked) {
                    TestPlayers.leave(player);
                }
                removeGraves(helper, uuid);
                helper.assertTrue(kicked, "player was not kicked after dying");
                helper.succeed();
            });
            waitingForKick = true;
        } finally {
            if (!waitingForKick) {
                TestPlayers.leave(player);
                removeGraves(helper, uuid);
            }
        }
    }

    // Vanilla ignores damage to a player whose client hasn't finished loading the world
    @GameTest
    public void onlineKillOfLoadingPlayerIsReported(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Loading", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            helper.assertValueEqual(Kills.kill(server, player.nameAndId()), Kills.Result.NOT_KILLED, "kill result");
            helper.assertFalse(player.isDeadOrDying(), "loading player died");
            helper.assertTrue(DeathLockout.refusal(player.getUUID(), "Test") == null, "a death was recorded");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // One worn item reads back and one doesn't, like an item from a mod or datapack that was removed since
    @GameTest
    public void unreadableWornItemStaysInSave(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Worn", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        try {
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        } finally {
            TestPlayers.leave(player);
        }
        CompoundTag tag = OfflinePlayerData.load(server, who).orElseThrow();
        tag.getCompound("equipment").orElseThrow().put("chest", unreadableItem());
        OfflinePlayerData.save(server, who.id(), tag);

        try {
            helper.assertValueEqual(Kills.kill(server, who), Kills.Result.KILLED_OFFLINE, "kill result");
            List<ItemStack> items = onlyGrave(helper, who.id()).items();
            helper.assertValueEqual(items.size(), 2, "grave item count");
            helper.assertTrue(items.get(1).is(Items.IRON_HELMET), "second item is not the iron helmet");
            CompoundTag expected = new CompoundTag();
            expected.put("chest", unreadableItem());
            helper.assertValueEqual(OfflinePlayerData.load(server, who).orElseThrow().getCompoundOrEmpty("equipment"), expected,
                    "saved equipment");
        } finally {
            removeGraves(helper, who.id());
        }
        helper.succeed();
    }

    @GameTest
    public void unreadableInventoryEntryStaysInSave(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Inv", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
        } finally {
            TestPlayers.leave(player);
        }
        CompoundTag unreadable = unreadableItem();
        unreadable.putByte("Slot", (byte) 5);
        CompoundTag tag = OfflinePlayerData.load(server, who).orElseThrow();
        tag.getList("Inventory").orElseThrow().add(unreadable.copy());
        OfflinePlayerData.save(server, who.id(), tag);

        try {
            helper.assertValueEqual(Kills.kill(server, who), Kills.Result.KILLED_OFFLINE, "kill result");
            List<ItemStack> items = onlyGrave(helper, who.id()).items();
            helper.assertValueEqual(items.size(), 2, "grave item count");
            helper.assertTrue(items.get(1).is(Items.DIAMOND), "second item is not the diamond");
            ListTag expected = new ListTag();
            expected.add(unreadable);
            helper.assertValueEqual(OfflinePlayerData.load(server, who).orElseThrow().getListOrEmpty("Inventory"), expected,
                    "saved inventory");
        } finally {
            removeGraves(helper, who.id());
        }
        helper.succeed();
    }

    // Like an item that carries a component from a mod removed since: the item reads back, only that component doesn't
    @GameTest
    public void partlyReadableItemGoesToGrave(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Partly", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        TestPlayers.leave(player);
        CompoundTag inInventory = partlyReadableItem("minecraft:diamond");
        inInventory.putByte("Slot", (byte) 5);
        CompoundTag inOffhand = partlyReadableItem("minecraft:emerald");
        CompoundTag tag = OfflinePlayerData.load(server, who).orElseThrow();
        ListTag inventory = tag.getListOrEmpty("Inventory");
        inventory.add(inInventory.copy());
        tag.put("Inventory", inventory);
        CompoundTag equipment = tag.getCompoundOrEmpty("equipment");
        equipment.put("offhand", inOffhand.copy());
        tag.put("equipment", equipment);
        OfflinePlayerData.save(server, who.id(), tag);

        try {
            List<String> log = yoltLog(() ->
                    helper.assertValueEqual(Kills.kill(server, who), Kills.Result.KILLED_OFFLINE, "kill result"));
            List<ItemStack> items = onlyGrave(helper, who.id()).items();
            helper.assertValueEqual(items.size(), 3, "grave item count");
            helper.assertTrue(ItemStack.matches(items.get(1), new ItemStack(Items.DIAMOND)), "inventory item in the grave: " + items.get(1));
            helper.assertTrue(ItemStack.matches(items.get(2), new ItemStack(Items.EMERALD)), "offhand item in the grave: " + items.get(2));
            for (CompoundTag raw : List.of(inInventory, inOffhand)) {
                helper.assertTrue(log.stream().anyMatch(line -> line.startsWith("Taking ") && line.contains(raw.toString())),
                        "no warning holds " + raw + ": " + log);
            }
            CompoundTag saved = OfflinePlayerData.load(server, who).orElseThrow();
            helper.assertTrue(saved.getListOrEmpty("Inventory").isEmpty(), "save still holds " + saved.get("Inventory"));
            helper.assertTrue(saved.getCompoundOrEmpty("equipment").isEmpty(), "save still holds " + saved.get("equipment"));
        } finally {
            removeGraves(helper, who.id());
        }
        helper.succeed();
    }

    // A real item with one component no registry knows
    private static CompoundTag partlyReadableItem(String id) {
        CompoundTag components = new CompoundTag();
        components.putInt("you-only-live-twice-gametest:no_such_component", 1);
        CompoundTag item = new CompoundTag();
        item.putString("id", id);
        item.putInt("count", 1);
        item.put("components", components);
        return item;
    }

    // Every message YOLT logs while the action runs. The appender joins the config YOLT's logger already uses, and
    // leaves it after: Logger.addAppender makes a config of its own that stays and cuts YOLT off the console. Other
    // test classes use it too.
    static List<String> yoltLog(Runnable action) {
        LoggerConfig config = ((Logger) LogManager.getLogger(Yolt.MOD_ID)).get();
        List<String> lines = new CopyOnWriteArrayList<>();
        AbstractAppender appender = new AbstractAppender("yolt-gametest", null, null, false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (Yolt.MOD_ID.equals(event.getLoggerName())) {
                    lines.add(event.getMessage().getFormattedMessage());
                }
            }
        };
        appender.start();
        config.addAppender(appender, null, null);
        try {
            action.run();
        } finally {
            config.removeAppender(appender.getName());
            appender.stop();
        }
        return lines;
    }

    // No item has this id, so the entry can't be read back
    static CompoundTag unreadableItem() {
        CompoundTag item = new CompoundTag();
        item.putString("id", "you-only-live-twice-gametest:no_such_item");
        item.putInt("count", 1);
        return item;
    }

    // A main inventory with every slot taken, by one dirt each
    static ListTag fullInventory(GameTestHelper helper) {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        ListTag inventory = new ListTag();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            inventory.add(ItemStackWithSlot.CODEC.encodeStart(ops, new ItemStackWithSlot(slot, new ItemStack(Items.DIRT))).getOrThrow());
        }
        return inventory;
    }

    // The save's main inventory entries that read back, by slot
    static Map<Integer, ItemStack> savedInventory(GameTestHelper helper, CompoundTag save) {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Map<Integer, ItemStack> items = new HashMap<>();
        for (Tag entry : save.getListOrEmpty("Inventory")) {
            ItemStackWithSlot.CODEC.parse(ops, entry).result().ifPresent(read -> items.put(read.slot(), read.stack()));
        }
        return items;
    }

    private static Grave onlyGrave(GameTestHelper helper, UUID owner) {
        List<Grave> graves = gravesOf(helper, owner);
        helper.assertValueEqual(graves.size(), 1, "graves for the player");
        return graves.get(0);
    }

    // Entities, not items: checked in the same tick as the drop, before anything could merge
    private static int itemEntities(GameTestHelper helper, Item item, BlockPos relativePos) {
        AABB area = new AABB(helper.absolutePos(relativePos)).inflate(2.0);
        return helper.getLevel().getEntities(EntityTypes.ITEM, area, entity -> entity.isAlive() && entity.getItem().is(item)).size();
    }

    // A write moves the current save to .dat_old, so both files staying the same means nothing was written
    private static byte[][] saveFiles(MinecraftServer server, NameAndId who) throws IOException {
        Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        return new byte[][] {Files.readAllBytes(dir.resolve(who.id() + ".dat")), Files.readAllBytes(dir.resolve(who.id() + ".dat_old"))};
    }

    private static List<ItemStack> enderChest(MinecraftServer server, NameAndId who) {
        return OfflinePlayerData.takeEnderChest(OfflinePlayerData.load(server, who).orElseThrow(), server.registryAccess(), who.name());
    }

    private static List<Grave> gravesOf(GameTestHelper helper, UUID owner) {
        return GraveStore.get(helper.getLevel().getServer()).all().stream()
                .filter(grave -> grave.owner().equals(owner))
                .toList();
    }

    // Kills make graves while graves are on, and a grave left behind would sit in another test's way
    private static void removeGraves(GameTestHelper helper, UUID owner) {
        GraveStore store = GraveStore.get(helper.getLevel().getServer());
        for (Grave grave : gravesOf(helper, owner)) {
            store.remove(grave.id());
            helper.getLevel().setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
