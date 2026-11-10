package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.BodyExpiry;
import bobbynooby.dev.bodies.Kills;
import bobbynooby.dev.config.BodyDuration;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveBlocks;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GravePlacement;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.graves.Graves;
import bobbynooby.dev.graves.LastGround;
import bobbynooby.dev.offline.OfflinePlayerData;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

// Deaths over the void. The test world is flat and solid to the bottom in every dimension, so each test digs its own
// hole down through the world and puts every block back in finally. Positions are absolute.
public class VoidGraveGameTests {
    // The world spawn is moved to the Nether, so the test runs apart from the others
    private static final String VOID_SPAWN = "you-only-live-twice-gametest:void_spawn";

    // A shaft through the floor; the death is below the world in it
    @GameTest(skyAccess = true)
    public void overworldVoidDeathUsesNeighbourSurface(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos shaft = helper.absolutePos(new BlockPos(3, 0, 3));
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        try {
            dig(level, shaft, before);
            BlockPos result = GravePlacement.find(level, belowWorld(level, shaft), store(level));
            // Straight beside the shaft is the nearest land; a corner of it is further
            helper.assertValueEqual(flatDistanceSqr(result, shaft), 1, "grave's distance from the shaft, squared");
            helper.assertValueEqual(result.getY(), shaft.getY(), "grave height");
            assertOnSolid(helper, level, result);
        } finally {
            putBack(level, before);
        }
        helper.succeed();
    }

    // A 3 by 3 hole through the End's floor, with the death under its middle. The test's structure is millions out, so
    // a box check that squares in ints would take every spot there and leave only the last resort.
    @GameTest(dimension = "minecraft:the_end", skyAccess = true)
    public void endVoidDeathNearLandLandsOnLand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos middle = helper.absolutePos(new BlockPos(3, 0, 3));
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        try {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    dig(level, middle.offset(dx, 0, dz), before);
                }
            }
            BlockPos result = withoutLastResort(helper, () -> GravePlacement.find(level, belowWorld(level, middle), store(level)));
            helper.assertTrue(Math.abs(result.getX() - middle.getX()) > 1 || Math.abs(result.getZ() - middle.getZ()) > 1,
                    "grave in the hole at " + result);
            // One of the four floor blocks straight out from the middle; the hole's corners are further
            helper.assertValueEqual(flatDistanceSqr(result, middle), 4, "grave's distance from the hole's middle, squared");
            helper.assertValueEqual(result.getY(), middle.getY(), "grave height");
            assertOnSolid(helper, level, result);
        } finally {
            putBack(level, before);
        }
        helper.succeed();
    }

    // Tracked on a ledge across the test, then dead below the world down a shaft whose side is nearer land
    @GameTest(skyAccess = true)
    public void voidDeathUsesLastGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos shaft = helper.absolutePos(new BlockPos(1, 0, 1));
        BlockPos onLedge = helper.absolutePos(new BlockPos(6, 1, 6));
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        ServerPlayer player = null;
        try {
            dig(level, shaft, before);
            setRemembered(level, onLedge.below(), Blocks.STONE, before);
            player = TestPlayers.join(helper, "Ledge", GameType.SURVIVAL, new BlockPos(6, 1, 6));
            player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
            standOnGround(player);
            helper.assertValueEqual(LastGround.in(player.getUUID(), level.dimension()), Optional.of(onLedge), "last ground");

            dieBelowWorld(helper, level, player, shaft);
            Grave grave = onlyGrave(helper, level, player.getUUID());
            helper.assertValueEqual(grave.pos(), onLedge, "grave position");
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.DIAMOND), 3, "diamonds in the grave");
            helper.assertTrue(GraveBlocks.isOwnHead(level, grave.pos(), grave), "no grave head at " + grave.pos());
        } finally {
            if (player != null) {
                cleanUp(level, player);
            }
            putBack(level, before);
        }
        helper.succeed();
    }

    // The Nether's floor dug out at the death, and a bedrock roof at the roof's height over it. The heightmap tops
    // there are the roof, which nobody can reach.
    @GameTest(dimension = "minecraft:the_nether", skyAccess = true)
    public void netherVoidDeathStaysUnderRoof(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int roofY = level.getMinY() + level.getLogicalHeight() - 1;
        BlockPos shaft = helper.absolutePos(new BlockPos(3, 0, 3));
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        try {
            dig(level, shaft, before);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    setRemembered(level, shaft.offset(dx, 0, dz).atY(roofY), Blocks.BEDROCK, before);
                }
            }
            BlockPos result = GravePlacement.find(level, belowWorld(level, shaft), store(level));
            helper.assertTrue(result.getY() < roofY, "grave on or in the roof at " + result);
            helper.assertValueEqual(result.getY(), shaft.getY(), "grave height");
            helper.assertValueEqual(flatDistanceSqr(result, shaft), 1, "grave's distance from the shaft, squared");
            assertOnSolid(helper, level, result);
        } finally {
            putBack(level, before);
        }
        helper.succeed();
    }

    // Far out over the End's void, in a chunk loaded for this test alone and dug out wherever the search reads, so no
    // land is near. The grave goes beside the exit portal, then the first kill's egg goes where vanilla puts it.
    @GameTest
    public void endVoidFallbackIsNotTheEggSpot(GameTestHelper helper) {
        ServerLevel end = level(helper, Level.END);
        ChunkPos chunk = new ChunkPos(312, 312);
        BlockPos death = new BlockPos(chunk.getMiddleBlockX(), end.getMinY() - 70, chunk.getMiddleBlockZ());
        BlockPos podium = EnderDragonFight.getPodiumLocation(BlockPos.ZERO);
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        Grave grave = null;
        BlockPos egg = null;
        try {
            emptyLoneChunk(helper, end, chunk, before);
            grave = graveFor(end, death);
            int fromPodium = flatDistanceSqr(grave.pos(), podium);
            helper.assertTrue(fromPodium >= 13, "grave inside the exit portal's ring at " + grave.pos());
            helper.assertTrue(fromPodium <= 49, "grave not beside the exit portal: " + grave.pos());
            assertOnSolid(helper, end, grave.pos());

            egg = end.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, podium);
            end.setBlockAndUpdate(egg, Blocks.DRAGON_EGG.defaultBlockState());
            helper.assertTrue(GraveBlocks.isOwnHead(end, grave.pos(), grave), "the egg took the grave's head at " + grave.pos());
        } finally {
            if (egg != null && end.getBlockState(egg).is(Blocks.DRAGON_EGG)) {
                end.setBlock(egg, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            if (grave != null) {
                removeGrave(end, grave);
            }
            putBack(end, before);
        }
        helper.succeed();
    }

    // The same in the Nether, with the fallback column at 0, 0 filled up to a bedrock roof, so it has no room under the
    // roof. The columns beside it have, so the grave goes there and not up on the roof.
    @GameTest
    public void netherVoidFallbackStaysUnderRoof(GameTestHelper helper) {
        ServerLevel nether = level(helper, Level.NETHER);
        int roofY = nether.getMinY() + nether.getLogicalHeight() - 1;
        ChunkPos chunk = new ChunkPos(312, 312);
        BlockPos death = new BlockPos(chunk.getMiddleBlockX(), nether.getMinY() - 70, chunk.getMiddleBlockZ());
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        try {
            emptyLoneChunk(helper, nether, chunk, before);
            // A chunk that isn't loaded reads as the bottom of the world in the heightmap
            nether.getChunk(BlockPos.ZERO);
            int floorY = nether.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    setRemembered(nether, new BlockPos(x, roofY, z), Blocks.BEDROCK, before);
                }
            }
            for (int y = floorY; y < roofY; y++) {
                setRemembered(nether, new BlockPos(0, y, 0), Blocks.STONE, before);
            }
            BlockPos result = withoutLastResort(helper, () -> GravePlacement.find(nether, death, store(nether)));
            helper.assertTrue(result.getY() < roofY, "grave on or in the roof at " + result);
            helper.assertValueEqual(flatDistanceSqr(result, BlockPos.ZERO), 1, "grave's distance from 0, 0, squared");
            helper.assertValueEqual(result.getY(), floorY, "grave height");
            assertOnSolid(helper, nether, result);
        } finally {
            putBack(nether, before);
        }
        helper.succeed();
    }

    // The same, in the overworld, with the world spawn set in the Nether: those x and z mean nothing here. Set and put
    // back in this tick.
    @GameTest(environment = VOID_SPAWN)
    public void overworldVoidFallbackIgnoresSpawnInOtherDimension(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ChunkPos chunk = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO).offset(-4000, 0, -4000));
        BlockPos death = new BlockPos(chunk.getMiddleBlockX(), level.getMinY() - 70, chunk.getMiddleBlockZ());
        BlockPos netherSpawn = new BlockPos(death.getX() + 200, 64, death.getZ() + 200);
        LevelData.RespawnData spawnBefore = server.getWorldData().overworldData().getRespawnData();
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        try {
            emptyLoneChunk(helper, level, chunk, before);
            server.setRespawnData(LevelData.RespawnData.of(Level.NETHER, netherSpawn, 0.0F, 0.0F));
            helper.assertValueEqual(level.getRespawnData().dimension(), Level.NETHER, "spawn's dimension");

            BlockPos result = GravePlacement.find(level, death, store(level));
            helper.assertTrue(flatDistanceSqr(result, netherSpawn) > 16 * 16, "grave at the Nether spawn's x and z: " + result);
            helper.assertTrue(flatDistanceSqr(result, BlockPos.ZERO) <= 3 * 3 * 2, "grave not at the overworld's 0, 0: " + result);
            assertOnSolid(helper, level, result);
        } finally {
            server.setRespawnData(spawnBefore);
            putBack(level, before);
        }
        helper.succeed();
    }

    // The void kills through a totem, so the totem goes into the grave with the rest
    @GameTest(skyAccess = true)
    public void voidDeathTotemGoesToGrave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos shaft = helper.absolutePos(new BlockPos(3, 0, 3));
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        ServerPlayer player = null;
        try {
            dig(level, shaft, before);
            player = TestPlayers.join(helper, "Totem", GameType.SURVIVAL, new BlockPos(1, 1, 1));
            String name = player.getGameProfile().name();
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            player.getInventory().add(new ItemStack(Items.DIAMOND, 3));

            dieBelowWorld(helper, level, player, shaft);
            Grave grave = onlyGrave(helper, level, player.getUUID());
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.TOTEM_OF_UNDYING), 1, "totems in the grave");
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.DIAMOND), 3, "diamonds in the grave");
            helper.assertFalse(sameColumn(grave.pos(), shaft), "grave in the shaft at " + grave.pos());
            assertOnSolid(helper, level, grave.pos());
            String refusal = LockoutGameTests.refusalText(player.getUUID());
            helper.assertTrue(refusal.startsWith("You died: " + name + " fell out of the world\nTry again in "),
                    "lockout: " + refusal);
        } finally {
            if (player != null) {
                cleanUp(level, player);
            }
            putBack(level, before);
        }
        helper.succeed();
    }

    // Tracked on a ledge, then saved over the void. The test server keeps bodies off, so the logout leaves no body and
    // forgets the ledge, and the kill starts from the save.
    @GameTest(skyAccess = true)
    public void offlineKillInVoidGoesToLand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos shaft = helper.absolutePos(new BlockPos(3, 0, 3));
        BlockPos onLedge = helper.absolutePos(new BlockPos(6, 1, 6));
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        UUID owner = null;
        try {
            dig(level, shaft, before);
            setRemembered(level, onLedge.below(), Blocks.STONE, before);
            ServerPlayer player = TestPlayers.join(helper, "VoidSave", GameType.SURVIVAL, new BlockPos(6, 1, 6));
            NameAndId who = player.nameAndId();
            owner = who.id();
            try {
                player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
                standOnGround(player);
                player.setOnGround(false);
                player.snapTo(shaft.getX() + 0.5, belowWorld(level, shaft).getY(), shaft.getZ() + 0.5);
            } finally {
                TestPlayers.leave(player);
            }
            helper.assertTrue(LastGround.in(owner, level.dimension()).isEmpty(), "the logout kept the last ground");

            helper.assertValueEqual(Kills.kill(server, who), Kills.Result.KILLED_OFFLINE, "kill result");
            Grave grave = onlyGrave(helper, level, owner);
            helper.assertValueEqual(flatDistanceSqr(grave.pos(), shaft), 1, "grave's distance from the shaft, squared");
            helper.assertValueEqual(grave.pos().getY(), shaft.getY(), "grave height");
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.DIAMOND), 3, "diamonds in the grave");
            CompoundTag saved = OfflinePlayerData.load(server, who).orElseThrow();
            helper.assertTrue(OfflinePlayerData.takeInventory(saved, server.registryAccess(), who.name()).isEmpty(),
                    "save still has items");
            helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 0.0F, "saved health");
        } finally {
            if (owner != null) {
                removeGraves(level, owner);
            }
            putBack(level, before);
        }
        helper.succeed();
    }

    // Ender chest into the grave: 41 stacks on the player and 27 in the chest, so two graves, the second on the first
    @GameTest(skyAccess = true)
    public void voidDeathTwoGravesStackOnLand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos shaft = helper.absolutePos(new BlockPos(3, 0, 3));
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        Map<EquipmentSlot, Item> worn = Map.of(EquipmentSlot.HEAD, Items.IRON_HELMET, EquipmentSlot.CHEST, Items.IRON_CHESTPLATE,
                EquipmentSlot.LEGS, Items.IRON_LEGGINGS, EquipmentSlot.FEET, Items.IRON_BOOTS, EquipmentSlot.OFFHAND, Items.SHIELD);
        ServerPlayer player = null;
        YoltConfig.GravesSection graves = Settings.get().graves;
        YoltConfig.EnderChestMode modeBefore = graves.enderChestOnDeath;
        try {
            dig(level, shaft, before);
            player = TestPlayers.join(helper, "Stacks", GameType.SURVIVAL, new BlockPos(1, 1, 1));
            Inventory inventory = player.getInventory();
            for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
                inventory.setItem(i, new ItemStack(Items.COBBLESTONE));
            }
            for (Map.Entry<EquipmentSlot, Item> slot : worn.entrySet()) {
                player.setItemSlot(slot.getKey(), new ItemStack(slot.getValue()));
            }
            PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
            for (int i = 0; i < enderChest.getContainerSize(); i++) {
                enderChest.setItem(i, new ItemStack(Items.EMERALD));
            }
            graves.enderChestOnDeath = YoltConfig.EnderChestMode.GRAVE;
            try {
                dieBelowWorld(helper, level, player, shaft);
            } finally {
                graves.enderChestOnDeath = modeBefore;
            }

            List<Grave> made = gravesOf(level, player.getUUID());
            helper.assertValueEqual(made.size(), 2, "graves for the player");
            helper.assertValueEqual(GraveAsserts.countInGraves(made, Items.COBBLESTONE), Inventory.INVENTORY_SIZE, "cobblestone");
            helper.assertValueEqual(GraveAsserts.countInGraves(made, Items.EMERALD), enderChest.getContainerSize(), "emeralds");
            for (Item item : worn.values()) {
                helper.assertValueEqual(GraveAsserts.countInGraves(made, item), 1, item + " in the graves");
            }
            helper.assertValueEqual(made.get(0).items().size() + made.get(1).items().size(), 69, "stacks with the grave head");
            for (Grave grave : made) {
                helper.assertFalse(sameColumn(grave.pos(), shaft), "grave in the shaft at " + grave.pos());
                helper.assertTrue(GraveBlocks.isOwnHead(level, grave.pos(), grave), "no grave head at " + grave.pos());
            }
            assertOnSolid(helper, level, made.get(0).pos());
            helper.assertValueEqual(made.get(1).pos(), made.get(0).pos().above(), "second grave's position");
        } finally {
            graves.enderChestOnDeath = modeBefore;
            if (player != null) {
                cleanUp(level, player);
            }
            putBack(level, before);
        }
        helper.succeed();
    }

    // The border's east edge runs through a hole 3 wide, and the death is just past it. Beside the hole the nearest land
    // is past the border too, so only the border check keeps the grave in. Set and put back in this tick.
    @GameTest(skyAccess = true)
    public void voidDeathAtBorderStaysInside(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorldBorder border = level.getWorldBorder();
        double size = border.getSize();
        double centerX = border.getCenterX();
        double centerZ = border.getCenterZ();
        BlockPos edge = helper.absolutePos(new BlockPos(6, 0, 3));
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        try {
            for (int x = 3; x <= 5; x++) {
                for (int z = 2; z <= 4; z++) {
                    dig(level, helper.absolutePos(new BlockPos(x, 0, z)), before);
                }
            }
            // Inside reaches up to, not into, the edge's column
            border.setCenter(edge.getX() - 20, edge.getZ());
            border.setSize(40);
            BlockPos death = belowWorld(level, edge);
            helper.assertFalse(border.isWithinBounds(death), "death spot inside the border");
            helper.assertTrue(border.isWithinBounds(edge.west()), "the hole's east column is outside the border");

            BlockPos result = GravePlacement.find(level, death, store(level));
            helper.assertTrue(border.isWithinBounds(result), "grave outside the border at " + result);
            helper.assertTrue(result.getX() < edge.getX() - 3 || result.getX() >= edge.getX()
                    || Math.abs(result.getZ() - edge.getZ()) > 1, "grave in the hole at " + result);
            // Two out from the hole's inside edge, where the death was moved in to
            helper.assertValueEqual(flatDistanceSqr(result, edge.west()), 4, "grave's distance from the hole's edge, squared");
            helper.assertValueEqual(result.getY(), edge.getY(), "grave height");
            assertOnSolid(helper, level, result);
        } finally {
            border.setSize(size);
            border.setCenter(centerX, centerZ);
            putBack(level, before);
        }
        helper.succeed();
    }

    // A body tracked on a ledge, pushed off down a shaft whose side is nearer land, and killed by the void below
    @GameTest(skyAccess = true, maxTicks = 40)
    public void bodyPushedIntoVoidGraveOnLand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos shaft = helper.absolutePos(new BlockPos(1, 0, 1));
        BlockPos onLedge = helper.absolutePos(new BlockPos(6, 1, 6));
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        ServerPlayer player = TestPlayers.join(helper, "Pushed", GameType.SURVIVAL, new BlockPos(6, 1, 6));
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection bodiesBefore = BodyTestKit.useDefaultBodies();
        Runnable cleanUp = () -> {
            BodyTestKit.cleanUp(helper, owner, bodiesBefore);
            LastGround.forget(owner);
            putBack(level, before);
        };
        boolean waiting = false;
        try {
            dig(level, shaft, before);
            setRemembered(level, onLedge.below(), Blocks.STONE, before);
            player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
            Mannequin body = BodyTestKit.leaveBody(helper, player);
            // The switches only matter while the body spawns
            Settings.get().bodies = bodiesBefore;
            // The body ticks on the ledge, and the server tick notes where it stands
            helper.runAfterDelay(5, () -> {
                try {
                    helper.assertValueEqual(LastGround.in(owner, level.dimension()), Optional.of(onLedge), "body's last ground");
                    body.snapTo(shaft.getX() + 0.5, belowWorld(level, shaft).getY(), shaft.getZ() + 0.5);
                    body.hurtServer(level, level.damageSources().fellOutOfWorld(), Float.MAX_VALUE);
                    helper.assertTrue(body.isDeadOrDying(), "body did not die");

                    List<Grave> made = BodyTestKit.gravesOf(helper, owner);
                    helper.assertValueEqual(made.size(), 1, "graves for the owner");
                    helper.assertValueEqual(made.get(0).pos(), onLedge, "grave position");
                    helper.assertValueEqual(GraveAsserts.countInGraves(made, Items.DIAMOND), 3, "diamonds in the grave");
                    helper.assertTrue(BodyTestKit.store(helper).byOwner(owner).isEmpty(), "body record kept");
                } finally {
                    cleanUp.run();
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            Settings.get().bodies = bodiesBefore;
            if (!waiting) {
                cleanUp.run();
            }
        }
    }

    // Tracked on a ledge, then out in mid-air over a shaft: the body keeps the ledge though it never stands anywhere, so
    // its death in the void below puts the grave there. The ledge goes with the body.
    @GameTest(skyAccess = true)
    public void logoutMidFallHandsLastGroundToBody(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos shaft = helper.absolutePos(new BlockPos(1, 0, 1));
        BlockPos onLedge = helper.absolutePos(new BlockPos(6, 1, 6));
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        ServerPlayer player = TestPlayers.join(helper, "Falling", GameType.SURVIVAL, new BlockPos(6, 1, 6));
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection bodiesBefore = BodyTestKit.useDefaultBodies();
        try {
            dig(level, shaft, before);
            setRemembered(level, onLedge.below(), Blocks.STONE, before);
            player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
            standOnGround(player);
            player.setOnGround(false);
            player.snapTo(shaft.getX() + 0.5, shaft.getY() + 3, shaft.getZ() + 0.5);
            Mannequin body = BodyTestKit.leaveBody(helper, player);
            Settings.get().bodies = bodiesBefore;
            helper.assertValueEqual(LastGround.in(owner, level.dimension()), Optional.of(onLedge), "body's last ground");

            body.snapTo(shaft.getX() + 0.5, belowWorld(level, shaft).getY(), shaft.getZ() + 0.5);
            body.hurtServer(level, level.damageSources().fellOutOfWorld(), Float.MAX_VALUE);
            helper.assertTrue(body.isDeadOrDying(), "body did not die");
            List<Grave> made = BodyTestKit.gravesOf(helper, owner);
            helper.assertValueEqual(made.size(), 1, "graves for the owner");
            helper.assertValueEqual(made.get(0).pos(), onLedge, "grave position");
            helper.assertValueEqual(GraveAsserts.countInGraves(made, Items.DIAMOND), 3, "diamonds in the grave");
            helper.assertTrue(LastGround.in(owner, level.dimension()).isEmpty(), "the body's death kept its last ground");
        } finally {
            BodyTestKit.cleanUp(helper, owner, bodiesBefore);
            LastGround.forget(owner);
            putBack(level, before);
        }
        helper.succeed();
    }

    // The body keeps its owner's ledge until the owner comes back and takes it over
    @GameTest(skyAccess = true)
    public void ownerLoginForgetsBodyLastGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos onLedge = helper.absolutePos(new BlockPos(6, 1, 6));
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        ServerPlayer player = TestPlayers.join(helper, "Back", GameType.SURVIVAL, new BlockPos(6, 1, 6));
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection bodiesBefore = BodyTestKit.useDefaultBodies();
        try {
            setRemembered(level, onLedge.below(), Blocks.STONE, before);
            standOnGround(player);
            BodyTestKit.leaveBody(helper, player);
            helper.assertValueEqual(LastGround.in(owner, level.dimension()), Optional.of(onLedge), "body's last ground");

            TestPlayers.rejoin(helper, player.getGameProfile(), GameType.SURVIVAL, new BlockPos(6, 1, 6));
            helper.assertTrue(BodyTestKit.store(helper).byOwner(owner).isEmpty(), "body record kept");
            helper.assertTrue(LastGround.in(owner, level.dimension()).isEmpty(), "the login kept the body's last ground");
        } finally {
            BodyTestKit.cleanUp(helper, owner, bodiesBefore);
            LastGround.forget(owner);
            putBack(level, before);
        }
        helper.succeed();
    }

    // The same, with the body's time up instead. The expiry runs over every body, so the test runs apart.
    @GameTest(environment = PlayerBodyGameTests.BODY_EXPIRY, skyAccess = true)
    public void bodyExpiryForgetsLastGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos onLedge = helper.absolutePos(new BlockPos(6, 1, 6));
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        ServerPlayer player = TestPlayers.join(helper, "Expired", GameType.SURVIVAL, new BlockPos(6, 1, 6));
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection bodiesBefore = BodyTestKit.useDefaultBodies();
        try {
            Settings.get().bodies.duration = new BodyDuration(1);
            setRemembered(level, onLedge.below(), Blocks.STONE, before);
            standOnGround(player);
            Mannequin body = BodyTestKit.leaveBody(helper, player);
            helper.assertValueEqual(LastGround.in(owner, level.dimension()), Optional.of(onLedge), "body's last ground");
            BodyBehaviourGameTests.expiredLogout(helper, owner);

            BodyExpiry.tick(level.getServer());
            helper.assertTrue(body.isRemoved(), "body still in the world");
            helper.assertTrue(LastGround.in(owner, level.dimension()).isEmpty(), "the expiry kept the body's last ground");
        } finally {
            BodyTestKit.cleanUp(helper, owner, bodiesBefore);
            LastGround.forget(owner);
            putBack(level, before);
        }
        helper.succeed();
    }

    // Notes each block before it goes, so putBack can set back exactly what was there
    static void setRemembered(ServerLevel level, BlockPos pos, Block block, Map<BlockPos, BlockState> before) {
        before.putIfAbsent(pos.immutable(), level.getBlockState(pos));
        level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    // Every block of the column under the given open block, down to the bottom of the world. Without neighbour updates,
    // so nothing beside it reacts before putBack.
    static void dig(ServerLevel level, BlockPos open, Map<BlockPos, BlockState> before) {
        for (int y = level.getMinY(); y < open.getY(); y++) {
            setRemembered(level, open.atY(y), Blocks.AIR, before);
        }
    }

    static void putBack(ServerLevel level, Map<BlockPos, BlockState> before) {
        before.forEach((pos, state) -> {
            if (!level.getBlockState(pos).equals(state)) {
                level.setBlock(pos, state, Block.UPDATE_CLIENTS);
            }
        });
    }

    // A chunk far from every test, loaded on its own, with every column the void search could read in it dug out: those
    // whose search reaches no further than the chunk itself
    private static void emptyLoneChunk(GameTestHelper helper, ServerLevel level, ChunkPos chunk, Map<BlockPos, BlockState> before) {
        level.getChunk(chunk.x(), chunk.z());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    helper.assertTrue(level.getChunkSource().getChunkNow(chunk.x() + dx, chunk.z() + dz) == null,
                            "the chunk beside " + chunk + " is loaded");
                }
            }
        }
        for (int x = 4; x <= 11; x++) {
            for (int z = 4; z <= 11; z++) {
                BlockPos column = chunk.getBlockAt(x, 0, z);
                dig(level, column.atY(level.getHeight(Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ())), before);
            }
        }
    }

    // Down the shaft to below the world, where the void's own damage kills, totems or not
    private static void dieBelowWorld(GameTestHelper helper, ServerLevel level, ServerPlayer player, BlockPos shaft) {
        player.setOnGround(false);
        player.snapTo(shaft.getX() + 0.5, belowWorld(level, shaft).getY(), shaft.getZ() + 0.5);
        TestPlayers.markLoaded(player);
        player.hurtServer(level, level.damageSources().fellOutOfWorld(), Float.MAX_VALUE);
        helper.assertTrue(player.isDeadOrDying(), "player did not die");
    }

    // Test players get no living tick, so this stands in for the one that would note where they stand
    private static void standOnGround(ServerPlayer player) {
        player.setOnGround(true);
        LastGround.update(player.getUUID(), player);
    }

    private static BlockPos belowWorld(ServerLevel level, BlockPos column) {
        return column.atY(level.getMinY() - 70);
    }

    // A grave for a death at pos, made the way a death makes one. A static profile, so the head never looks anything up.
    private static Grave graveFor(ServerLevel level, BlockPos death) {
        UUID owner = UUID.randomUUID();
        ResolvableProfile profile = ResolvableProfile.createResolved(new GameProfile(owner, "Ghost"));
        List<Grave> made = Graves.create(level, death, profile, owner, "Ghost",
                List.of(GraveItems.graveHead(profile), new ItemStack(Items.DIAMOND)));
        return made.getFirst();
    }

    // Throws past what an int holds, so a grave thrown far off fails the test rather than wrapping round to near
    static int flatDistanceSqr(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return Math.toIntExact(dx * dx + dz * dz);
    }

    // The placement, failing the test if it fell back on the last resort, which logs why
    static <T> T withoutLastResort(GameTestHelper helper, Supplier<T> placement) {
        List<T> result = new ArrayList<>(1);
        List<String> log = OfflineGameTests.yoltLog(() -> result.add(placement.get()));
        List<String> lastResort = log.stream().filter(line -> line.startsWith("No spot near")).toList();
        helper.assertTrue(lastResort.isEmpty(), "the grave came from the last resort: " + lastResort);
        return result.getFirst();
    }

    private static boolean sameColumn(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    static void assertOnSolid(GameTestHelper helper, ServerLevel level, BlockPos grave) {
        BlockPos below = grave.below();
        helper.assertFalse(level.getBlockState(below).getCollisionShape(level, below).isEmpty(),
                "nothing solid under the grave at " + grave);
    }

    private static GraveStore store(ServerLevel level) {
        return GraveStore.get(level.getServer());
    }

    private static ServerLevel level(GameTestHelper helper, ResourceKey<Level> dimension) {
        ServerLevel level = helper.getLevel().getServer().getLevel(dimension);
        helper.assertTrue(level != null, "test server has no level " + dimension);
        return level;
    }

    private static List<Grave> gravesOf(ServerLevel level, UUID owner) {
        return store(level).all().stream().filter(grave -> grave.owner().equals(owner)).toList();
    }

    private static Grave onlyGrave(GameTestHelper helper, ServerLevel level, UUID owner) {
        List<Grave> found = gravesOf(level, owner);
        helper.assertValueEqual(found.size(), 1, "graves for the player");
        return found.getFirst();
    }

    // In the grave's own dimension, so a grave in the End is cleared from a test in the overworld
    static void removeGrave(ServerLevel anyLevel, Grave grave) {
        store(anyLevel).remove(grave.id());
        ServerLevel level = anyLevel.getServer().getLevel(grave.dimension());
        if (level != null && GraveBlocks.isOwnHead(level, grave.pos(), grave)) {
            level.setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static void removeGraves(ServerLevel level, UUID owner) {
        gravesOf(level, owner).forEach(grave -> removeGrave(level, grave));
    }

    // Leaving first means the queued death kick finds nobody; the graves and the last ground go too
    private static void cleanUp(ServerLevel level, ServerPlayer player) {
        TestPlayers.leave(player);
        LastGround.forget(player.getUUID());
        removeGraves(level, player.getUUID());
    }
}
