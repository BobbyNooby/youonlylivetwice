package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.Kills;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveBlocks;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.graves.Graves;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.offline.OfflinePlayerData;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

// Every grave a test makes is removed again in finally, record and block, so no other test runs into it
public class GraveProtectionGameTests {
    @GameTest
    public void playerCannotBreakGrave(GameTestHelper helper) {
        Grave grave = makeGrave(helper, new BlockPos(3, 1, 3));
        ServerPlayer player = TestPlayers.join(helper, "Breaker", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            helper.assertFalse(player.gameMode.destroyBlock(grave.pos()), "survival player broke the grave");
            assertGraveIntact(helper, grave);
            player.setGameMode(GameType.CREATIVE);
            helper.assertFalse(player.gameMode.destroyBlock(grave.pos()), "creative player broke the grave");
            assertGraveIntact(helper, grave);
        } finally {
            TestPlayers.leave(player);
            removeGraves(helper, List.of(grave));
        }
        helper.succeed();
    }

    @GameTest
    public void explosionLeavesGrave(GameTestHelper helper) {
        Grave grave = makeGrave(helper, new BlockPos(3, 1, 3));
        try {
            Vec3 above = Vec3.atBottomCenterOf(grave.pos().above());
            helper.getLevel().explode(null, above.x, above.y, above.z, 4.0F, Level.ExplosionInteraction.TNT);
            assertGraveIntact(helper, grave);
        } finally {
            removeGraves(helper, List.of(grave));
        }
        helper.succeed();
    }

    // Player buckets, dispensers and fish buckets all empty through this one method, and a head gives way to any fluid
    @GameTest
    public void bucketCannotReplaceGrave(GameTestHelper helper) {
        Grave grave = makeGrave(helper, new BlockPos(3, 1, 3));
        try {
            ServerLevel level = helper.getLevel();
            helper.assertFalse(((BucketItem) Items.WATER_BUCKET).emptyContents(null, level, grave.pos(), null),
                    "water bucket emptied into the grave");
            assertGraveIntact(helper, grave);
            helper.assertFalse(((BucketItem) Items.LAVA_BUCKET).emptyContents(null, level, grave.pos(), null),
                    "lava bucket emptied into the grave");
            assertGraveIntact(helper, grave);
        } finally {
            removeGraves(helper, List.of(grave));
        }
        helper.succeed();
    }

    // A piston pops a head that sits right in front of it, so this is the case that must be blocked
    @GameTest(maxTicks = 20)
    public void pistonCannotPushGrave(GameTestHelper helper) {
        Grave grave = makeGrave(helper, new BlockPos(3, 1, 3));
        boolean waiting = false;
        try {
            helper.setBlock(new BlockPos(2, 1, 3), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
            helper.setBlock(new BlockPos(1, 1, 3), Blocks.REDSTONE_BLOCK);
            // Pistons move in block events at the end of the tick, so check a few ticks later
            helper.runAfterDelay(5, () -> {
                try {
                    assertGraveIntact(helper, grave);
                } finally {
                    removeGraves(helper, List.of(grave));
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                removeGraves(helper, List.of(grave));
            }
        }
    }

    // A stone channel, so the water's only way out is into the head
    @GameTest(maxTicks = 60)
    public void waterFlowLeavesGrave(GameTestHelper helper) {
        for (BlockPos stone : List.of(new BlockPos(2, 0, 3), new BlockPos(3, 0, 3), new BlockPos(1, 1, 3), new BlockPos(2, 1, 2),
                new BlockPos(2, 1, 4), new BlockPos(3, 1, 2), new BlockPos(3, 1, 4), new BlockPos(4, 1, 3))) {
            helper.setBlock(stone, Blocks.STONE);
        }
        Grave grave = makeGrave(helper, new BlockPos(3, 1, 3));
        boolean waiting = false;
        try {
            helper.setBlock(new BlockPos(2, 1, 3), Blocks.WATER);
            helper.runAfterDelay(40, () -> {
                try {
                    assertGraveIntact(helper, grave);
                } finally {
                    removeGraves(helper, List.of(grave));
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                removeGraves(helper, List.of(grave));
            }
        }
    }

    @GameTest
    public void missingHeadRestoredOnChunkLoad(GameTestHelper helper) {
        Grave grave = makeGrave(helper, new BlockPos(3, 1, 3));
        try {
            ServerLevel level = helper.getLevel();
            level.setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            GraveBlocks.restoreChunk(level, level.getChunkAt(grave.pos()));
            assertGraveIntact(helper, grave);
        } finally {
            removeGraves(helper, List.of(grave));
        }
        helper.succeed();
    }

    @GameTest
    public void offlineKillMakesGraveWithSavedItems(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "OffGrave", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        BlockPos last = helper.relativePos(player.blockPosition());
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            player.giveExperienceLevels(30);
        } finally {
            TestPlayers.leave(player);
        }

        try {
            helper.assertValueEqual(Kills.kill(server, who), Kills.Result.KILLED_OFFLINE, "kill result");
            Grave grave = onlyGrave(helper, who.id());
            List<ItemStack> items = grave.items();
            helper.assertValueEqual(items.size(), 3, "grave item count");
            helper.assertTrue(GraveItems.isGraveHead(items.get(0)), "first item is not the grave head");
            helper.assertTrue(items.get(1).is(Items.DIAMOND), "second item is not the diamond");
            helper.assertTrue(items.get(2).is(Items.IRON_HELMET), "third item is not the iron helmet");
            assertGraveIntact(helper, grave);
            helper.assertTrue(helper.getEntities(EntityTypes.ITEM, last, 3.0).isEmpty(), "items were dropped as well");
            // XP still drops as orbs, as in an online death
            helper.assertEntityPresent(EntityTypes.EXPERIENCE_ORB, last, 2.0);

            CompoundTag saved = OfflinePlayerData.load(server, who).orElseThrow();
            helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 0.0F, "saved health");
            helper.assertTrue(OfflinePlayerData.takeInventory(saved, server.registryAccess()).isEmpty(), "save still has items");
            helper.assertValueEqual(saved.getIntOr("XpLevel", -1), 0, "saved XP level");
            helper.assertTrue(DeathLockout.refusal(who.id()) != null, "offline kill did not start the lockout");
        } finally {
            removeGraves(helper, gravesOf(helper, who.id()));
        }
        helper.succeed();
    }

    // Over 54 stacks makes two graves; a failed save swap must take both back
    @GameTest
    public void offlineKillFailureRemovesEveryGrave(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Undo", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        BlockPos last = helper.relativePos(player.blockPosition());
        try {
            Inventory inventory = player.getInventory();
            for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
                inventory.setItem(i, new ItemStack(Items.COBBLESTONE));
            }
            PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
            for (int i = 0; i < enderChest.getContainerSize(); i++) {
                enderChest.setItem(i, new ItemStack(Items.EMERALD));
            }
        } finally {
            TestPlayers.leave(player);
        }

        // A non-empty folder where the old save gets moved can't be deleted, so the swap of the new save fails
        Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        Path blocker = dir.resolve(who.id() + ".dat_old");
        Path blockerContent = blocker.resolve("blocker");
        Files.createDirectories(blocker);
        Files.writeString(blockerContent, "");
        YoltConfig.GravesSection graves = Settings.get().graves;
        YoltConfig.EnderChestMode mode = graves.enderChestOnDeath;
        graves.enderChestOnDeath = YoltConfig.EnderChestMode.GRAVE;
        boolean failed = false;
        List<Grave> onDisk = List.of();
        List<Grave> left;
        try {
            Kills.kill(server, who);
        } catch (RuntimeException expected) {
            failed = true;
            // Both graves reached the file before the save swap was tried; the undo leaves it for the next save
            onDisk = GraveStoreGameTests.onDisk(server).stream().filter(grave -> grave.owner().equals(who.id())).toList();
        } finally {
            graves.enderChestOnDeath = mode;
            Files.delete(blockerContent);
            Files.delete(blocker);
            left = gravesOf(helper, who.id());
            removeGraves(helper, left);
        }

        helper.assertTrue(failed, "kill did not report the failed save");
        helper.assertValueEqual(onDisk.size(), 2, "graves on disk when the save swap failed");
        helper.assertTrue(left.isEmpty(), "graves left after the failed kill: " + left.size());
        assertNoHeadsNear(helper, last);
        helper.assertTrue(helper.getEntities(EntityTypes.ITEM, last, 3.0).isEmpty(), "items were dropped");
        CompoundTag saved = OfflinePlayerData.load(server, who).orElseThrow();
        helper.assertValueEqual(OfflinePlayerData.takeInventory(saved, server.registryAccess()).size(), Inventory.INVENTORY_SIZE,
                "stacks left in the saved inventory");
        helper.assertValueEqual(OfflinePlayerData.takeEnderChest(saved, server.registryAccess()).size(), 27,
                "stacks left in the saved ender chest");
        helper.assertTrue(DeathLockout.refusal(who.id()) == null, "failed kill started the lockout");
        helper.succeed();
    }

    @GameTest
    public void offlineKillKeepInventoryKeepsItems(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "OffKeep", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        BlockPos last = helper.relativePos(player.blockPosition());
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            player.giveExperienceLevels(30);
        } finally {
            TestPlayers.leave(player);
        }

        GameRules rules = server.getGameRules();
        try {
            boolean before = rules.get(GameRules.KEEP_INVENTORY);
            rules.set(GameRules.KEEP_INVENTORY, true, server);
            try {
                helper.assertValueEqual(Kills.kill(server, who), Kills.Result.KILLED_OFFLINE, "kill result");
            } finally {
                rules.set(GameRules.KEEP_INVENTORY, before, server);
            }

            CompoundTag saved = OfflinePlayerData.load(server, who).orElseThrow();
            helper.assertTrue(contains(OfflinePlayerData.takeInventory(saved, server.registryAccess()), Items.DIAMOND),
                    "save lost the diamond");
            helper.assertValueEqual(saved.getIntOr("XpLevel", -1), 30, "saved XP level");
            helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 0.0F, "saved health");
            Grave grave = onlyGrave(helper, who.id());
            helper.assertValueEqual(grave.items().size(), 1, "grave item count");
            helper.assertTrue(GraveItems.isGraveHead(grave.items().get(0)), "the only item is not the grave head");
            helper.assertTrue(helper.getEntities(EntityTypes.ITEM, last, 3.0).isEmpty(), "items were dropped");
            helper.assertTrue(helper.getEntities(EntityTypes.EXPERIENCE_ORB, last, 3.0).isEmpty(), "XP was dropped");
        } finally {
            removeGraves(helper, gravesOf(helper, who.id()));
        }
        helper.succeed();
    }

    @GameTest
    public void offlineKillDestroysVanishingItems(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "OffVanish", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        BlockPos last = helper.relativePos(player.blockPosition());
        try {
            ItemStack helmet = new ItemStack(Items.IRON_HELMET);
            helmet.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.VANISHING_CURSE), 1);
            player.setItemSlot(EquipmentSlot.HEAD, helmet);
            player.getInventory().add(new ItemStack(Items.DIAMOND));
        } finally {
            TestPlayers.leave(player);
        }

        try {
            helper.assertValueEqual(Kills.kill(server, who), Kills.Result.KILLED_OFFLINE, "kill result");
            Grave grave = onlyGrave(helper, who.id());
            helper.assertFalse(contains(grave.items(), Items.IRON_HELMET), "cursed helmet went into the grave");
            helper.assertTrue(contains(grave.items(), Items.DIAMOND), "diamond missing from the grave");
            helper.assertItemEntityNotPresent(Items.IRON_HELMET, last, 3.0);
            CompoundTag saved = OfflinePlayerData.load(server, who).orElseThrow();
            helper.assertTrue(OfflinePlayerData.takeInventory(saved, server.registryAccess()).isEmpty(), "save still has items");
        } finally {
            removeGraves(helper, gravesOf(helper, who.id()));
        }
        helper.succeed();
    }

    // Vanilla drops nothing for a dead spectator and gives everything back at respawn, so no grave either
    @GameTest
    public void offlineKillLeavesSpectatorsAlone(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "OffSpec", GameType.SPECTATOR, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        BlockPos last = helper.relativePos(player.blockPosition());
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD));
            player.giveExperienceLevels(30);
        } finally {
            TestPlayers.leave(player);
        }

        YoltConfig.GravesSection graves = Settings.get().graves;
        YoltConfig.EnderChestMode mode = graves.enderChestOnDeath;
        try {
            graves.enderChestOnDeath = YoltConfig.EnderChestMode.GRAVE;
            try {
                helper.assertValueEqual(Kills.kill(server, who), Kills.Result.KILLED_OFFLINE, "kill result");
            } finally {
                graves.enderChestOnDeath = mode;
            }

            CompoundTag saved = OfflinePlayerData.load(server, who).orElseThrow();
            helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 0.0F, "saved health");
            helper.assertValueEqual(saved.getIntOr("XpLevel", -1), 30, "saved XP level");
            helper.assertTrue(contains(OfflinePlayerData.takeInventory(saved, server.registryAccess()), Items.DIAMOND),
                    "save lost the diamond");
            helper.assertTrue(contains(OfflinePlayerData.takeEnderChest(saved, server.registryAccess()), Items.EMERALD),
                    "save lost the ender chest emerald");
            helper.assertTrue(gravesOf(helper, who.id()).isEmpty(), "a spectator got a grave");
            helper.assertTrue(helper.getEntities(EntityTypes.ITEM, last, 3.0).isEmpty(), "items were dropped");
            helper.assertTrue(helper.getEntities(EntityTypes.EXPERIENCE_ORB, last, 3.0).isEmpty(), "XP was dropped");
            helper.assertTrue(DeathLockout.refusal(who.id()) != null, "offline kill did not start the lockout");
        } finally {
            removeGraves(helper, gravesOf(helper, who.id()));
        }
        helper.succeed();
    }

    // A static profile, so the head never looks anything up
    private static Grave makeGrave(GameTestHelper helper, BlockPos relativePos) {
        UUID owner = UUID.randomUUID();
        ResolvableProfile profile = ResolvableProfile.createResolved(new GameProfile(owner, "Ghost"));
        BlockPos pos = helper.absolutePos(relativePos);
        List<Grave> made = Graves.create(helper.getLevel(), pos, profile, owner, "Ghost",
                List.of(GraveItems.graveHead(profile), new ItemStack(Items.DIAMOND)));
        helper.assertValueEqual(made.size(), 1, "graves made");
        helper.assertValueEqual(made.get(0).pos(), pos, "grave position");
        return made.get(0);
    }

    private static void assertGraveIntact(GameTestHelper helper, Grave grave) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(level.getBlockState(grave.pos()).is(Blocks.PLAYER_HEAD), "no player head at " + grave.pos());
        helper.assertTrue(level.getBlockEntity(grave.pos()) instanceof SkullBlockEntity skull
                && grave.profile().equals(skull.getOwnerProfile()), "head at " + grave.pos() + " lacks the owner's profile");
        helper.assertTrue(GraveStore.get(level.getServer()).byId(grave.id()).isPresent(), "grave record is gone");
    }

    // Graves for one death sit on or near the death spot
    private static void assertNoHeadsNear(GameTestHelper helper, BlockPos relativePos) {
        BlockPos center = helper.absolutePos(relativePos);
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-4, -1, -4), center.offset(4, 10, 4))) {
            helper.assertFalse(helper.getLevel().getBlockState(pos).is(Blocks.PLAYER_HEAD), "player head left at " + pos);
        }
    }

    private static List<Grave> gravesOf(GameTestHelper helper, UUID owner) {
        return GraveStore.get(helper.getLevel().getServer()).all().stream()
                .filter(grave -> grave.owner().equals(owner))
                .toList();
    }

    private static Grave onlyGrave(GameTestHelper helper, UUID owner) {
        List<Grave> graves = gravesOf(helper, owner);
        helper.assertValueEqual(graves.size(), 1, "graves for the player");
        return graves.get(0);
    }

    private static boolean contains(List<ItemStack> items, Item item) {
        return items.stream().anyMatch(stack -> stack.is(item));
    }

    private static void removeGraves(GameTestHelper helper, List<Grave> graves) {
        GraveStore store = GraveStore.get(helper.getLevel().getServer());
        for (Grave grave : graves) {
            store.remove(grave.id());
            helper.getLevel().setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
