package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.Kills;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveBlocks;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveMenu;
import bobbynooby.dev.graves.GraveMenus;
import bobbynooby.dev.graves.GraveRecheck;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.graves.Graves;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.offline.OfflinePlayerData;
import bobbynooby.dev.world.LiveSettings;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Every grave a test makes is removed again in finally, record and block, so no other test runs into it
public class GraveProtectionGameTests {
    // Tests that turn graves off and on again run apart from the others
    private static final String GRAVES_SWITCH = "you-only-live-twice-gametest:graves_switch";
    // The test server keeps the re-check off, since tests leave graves without a head on purpose; this one turns it on
    private static final String GRAVE_RECHECK = "you-only-live-twice-gametest:grave_recheck";
    // Copies of a test in a flake hunt that hold the same switch across ticks
    private static final SharedHold RECHECK_HOLD = new SharedHold();
    private static final SharedHold GRAVES_OFF_HOLD = new SharedHold();

    @GameTest
    public void playerCannotBreakGrave(GameTestHelper helper) {
        Grave grave = makeGrave(helper, new BlockPos(3, 0, 3));
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

    // An op in creative clicks the grave through the start-break packet's whole path. Creative breaks at once, as the
    // stone beside the grave shows; only /yolt grave remove takes a grave away.
    @GameTest
    public void creativeOpCannotBreakGraveByHand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Grave grave = makeGrave(helper, new BlockPos(3, 0, 3));
        BlockPos stone = helper.absolutePos(new BlockPos(4, 0, 3));
        ServerPlayer op = TestPlayers.join(helper, "OpBreaker", GameType.CREATIVE, new BlockPos(1, 1, 1));
        try {
            TestPlayers.op(op);
            level.setBlock(stone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            startBreaking(op, stone);
            helper.assertTrue(level.getBlockState(stone).isAir(), "the op's click did not break the stone beside the grave");

            startBreaking(op, grave.pos());
            assertGraveIntact(helper, grave);
            List<ItemStack> items = GraveStore.get(level.getServer()).byId(grave.id()).orElseThrow().items();
            helper.assertValueEqual(items.size(), 2, "stacks in the grave");
            helper.assertValueEqual(GraveAsserts.count(items, Items.DIAMOND), 1, "diamonds in the grave");
        } finally {
            TestPlayers.deop(op);
            TestPlayers.leave(op);
            removeGraves(helper, List.of(grave));
            level.setBlock(stone, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        helper.succeed();
    }

    // A refused break leaves the breaker's client with a blank head, since the client took the head for broken and the
    // block-change ack only puts the block back. The head's own data goes to the breaker after the refusal, at the end
    // of the tick, once that ack is out (a test player's ack never goes, since its connection doesn't tick).
    @GameTest(maxTicks = 20)
    public void refusedBreakerIsSentTheHeadAgain(GameTestHelper helper) {
        Grave grave = makeGrave(helper, new BlockPos(3, 0, 3));
        ServerPlayer breaker = TestPlayers.join(helper, "Resent", GameType.CREATIVE, new BlockPos(1, 1, 1));
        boolean waiting = false;
        try {
            // The server reads no break from a client that hasn't said its world loaded
            TestPlayers.markLoaded(breaker);
            int before = headUpdates(breaker, grave.pos());
            TestPlayers.receive(breaker, new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    grave.pos(), Direction.UP, 1));
            assertGraveIntact(helper, grave);
            helper.assertValueEqual(headUpdates(breaker, grave.pos()), before, "head updates sent inside the refused break");
            helper.runAfterDelay(2, () -> {
                try {
                    helper.assertTrue(headUpdates(breaker, grave.pos()) > before, "the breaker was not sent the head again");
                } finally {
                    TestPlayers.leave(breaker);
                    removeGraves(helper, List.of(grave));
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                TestPlayers.leave(breaker);
                removeGraves(helper, List.of(grave));
            }
        }
    }

    private static int headUpdates(ServerPlayer player, BlockPos pos) {
        return (int) TestPlayers.sentPackets(player).stream()
                .filter(packet -> packet instanceof ClientboundBlockEntityDataPacket data && data.getPos().equals(pos))
                .count();
    }

    // A hopper pulls from a container or from loose items above it, and a grave head is neither. The hopper under the
    // chest beside it shows the hoppers did run. No YOLT code runs here, so this passes with YOLT off: it is a guard
    // against a change that makes the grave a container.
    @GameTest(maxTicks = 20)
    public void hopperUnderGravePullsNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos under = helper.absolutePos(new BlockPos(3, 0, 3));
        BlockPos chest = helper.absolutePos(new BlockPos(5, 1, 5));
        BlockPos chestHopper = chest.below();
        level.setBlock(under, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        // On stone, since a new grave keeps off block entities; the hopper goes in under it after
        Grave grave = makeGrave(helper, new BlockPos(3, 1, 3));
        Runnable cleanUp = BodyTestKit.once(() -> {
            removeGraves(helper, List.of(grave));
            // Emptied first, or taking the blocks away would spill what they hold
            for (BlockPos pos : List.of(under, chestHopper, chest)) {
                if (level.getBlockEntity(pos) instanceof Container container) {
                    container.clearContent();
                }
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        });
        boolean waiting = false;
        try {
            level.setBlock(under, Blocks.HOPPER.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(chestHopper, Blocks.HOPPER.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            ((Container) level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.EMERALD));
            // A hopper tries every tick until it moves something, then every 8
            helper.runAfterDelay(10, () -> {
                try {
                    helper.assertValueEqual(((Container) level.getBlockEntity(chestHopper)).countItem(Items.EMERALD), 1,
                            "emeralds the chest's hopper pulled");
                    Container hopper = (Container) level.getBlockEntity(under);
                    helper.assertTrue(hopper.isEmpty(), "the hopper under the grave pulled " + hopper.getItem(0));
                    assertGraveIntact(helper, grave);
                    List<ItemStack> items = GraveStore.get(level.getServer()).byId(grave.id()).orElseThrow().items();
                    helper.assertValueEqual(items.size(), 2, "stacks in the grave");
                    helper.assertValueEqual(GraveAsserts.count(items, Items.DIAMOND), 1, "diamonds in the grave");
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

    @GameTest
    public void explosionLeavesGrave(GameTestHelper helper) {
        Grave grave = makeGrave(helper, new BlockPos(3, 0, 3));
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
        Grave grave = makeGrave(helper, new BlockPos(3, 0, 3));
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
        Grave grave = makeGrave(helper, new BlockPos(3, 0, 3));
        boolean waiting = false;
        try {
            helper.setBlock(new BlockPos(2, 0, 3), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
            helper.setBlock(new BlockPos(1, 0, 3), Blocks.REDSTONE_BLOCK);
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

    @GameTest(maxTicks = 60)
    public void waterFlowLeavesGrave(GameTestHelper helper) {
        fluidFlowLeavesGrave(helper, Blocks.WATER, 40);
    }

    // Lava in the overworld spreads every 30 ticks, so this waits for two spreads
    @GameTest(maxTicks = 100)
    public void lavaFlowLeavesGrave(GameTestHelper helper) {
        fluidFlowLeavesGrave(helper, Blocks.LAVA, 70);
    }

    // A source with the grave on one side and an open cell on the other, walled in with stone on a stone floor. With no
    // drop anywhere the fluid spreads into every cell it may, so reaching the open cell shows it did flow while the
    // grave stayed.
    private static void fluidFlowLeavesGrave(GameTestHelper helper, Block fluid, int waitTicks) {
        BlockPos source = new BlockPos(2, 1, 3);
        BlockPos open = new BlockPos(2, 1, 4);
        for (BlockPos stone : List.of(new BlockPos(2, 0, 3), new BlockPos(3, 0, 3), new BlockPos(2, 0, 4),
                new BlockPos(1, 1, 3), new BlockPos(2, 1, 2), new BlockPos(3, 1, 2), new BlockPos(3, 1, 4), new BlockPos(4, 1, 3),
                new BlockPos(1, 1, 4), new BlockPos(2, 1, 5))) {
            helper.setBlock(stone, Blocks.STONE);
        }
        Grave grave = makeGrave(helper, new BlockPos(3, 1, 3));
        Fluid flows = fluid.defaultBlockState().getFluidState().getType();
        // Lava left in the open would set fire to what is round it
        Runnable cleanUp = () -> {
            helper.setBlock(source, Blocks.AIR);
            helper.setBlock(open, Blocks.AIR);
            removeGraves(helper, List.of(grave));
        };
        boolean waiting = false;
        try {
            helper.setBlock(source, fluid);
            helper.runAfterDelay(waitTicks, () -> {
                try {
                    helper.assertTrue(helper.getLevel().getFluidState(helper.absolutePos(open)).getType().isSame(flows),
                            fluid.getName().getString() + " never flowed into the open cell beside its source");
                    assertGraveIntact(helper, grave);
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

    // Vanilla never pulls a head itself (heads pop rather than move), but a pulled slime block drags the block on top
    // of it along, and that block crushes whatever head is in its way. The grave sits above one piston's arm, where the
    // stone on the slime would land; the twin piston beside it has no grave and shows the pull does happen. Both start
    // out extended, so taking their power away makes them pull at once.
    @GameTest(maxTicks = 20)
    public void stickyPistonCannotPullBlockOntoGrave(GameTestHelper helper) {
        int withGrave = 3;
        int twin = 6;
        stickyRig(helper, withGrave);
        stickyRig(helper, twin);
        Grave grave = makeGrave(helper, new BlockPos(2, 2, withGrave));
        boolean waiting = false;
        try {
            helper.setBlock(new BlockPos(0, 1, withGrave), Blocks.AIR);
            helper.setBlock(new BlockPos(0, 1, twin), Blocks.AIR);
            // Pistons move in block events at the end of the tick, and a moved block settles two ticks later
            helper.runAfterDelay(5, () -> {
                try {
                    for (int z : List.of(withGrave, twin)) {
                        BlockState base = helper.getBlockState(new BlockPos(1, 1, z));
                        helper.assertTrue(base.is(Blocks.STICKY_PISTON) && !base.getValue(PistonBaseBlock.EXTENDED),
                                "the piston at z " + z + " did not pull back: " + base);
                    }
                    helper.assertBlockPresent(Blocks.SLIME_BLOCK, new BlockPos(2, 1, twin));
                    helper.assertBlockPresent(Blocks.STONE, new BlockPos(2, 2, twin));
                    helper.assertBlockPresent(Blocks.AIR, new BlockPos(3, 1, twin));
                    helper.assertBlockPresent(Blocks.AIR, new BlockPos(2, 1, withGrave));
                    helper.assertBlockPresent(Blocks.SLIME_BLOCK, new BlockPos(3, 1, withGrave));
                    helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 2, withGrave));
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

    // An extended sticky piston at x 1 facing east, powered from x 0, holding a slime block at x 3 with a stone on top
    private static void stickyRig(GameTestHelper helper, int z) {
        helper.setBlock(new BlockPos(0, 1, z), Blocks.REDSTONE_BLOCK);
        helper.setBlock(new BlockPos(1, 1, z), Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.EAST).setValue(PistonBaseBlock.EXTENDED, true));
        helper.setBlock(new BlockPos(2, 1, z), Blocks.PISTON_HEAD.defaultBlockState()
                .setValue(PistonHeadBlock.FACING, Direction.EAST).setValue(PistonHeadBlock.TYPE, PistonType.STICKY));
        helper.setBlock(new BlockPos(3, 1, z), Blocks.SLIME_BLOCK);
        helper.setBlock(new BlockPos(3, 2, z), Blocks.STONE);
    }

    // The dragon removes every block its body flies through itself, without an explosion. It only does that while
    // flying its End fight, so the hooked method is called directly, over the grave and the stone beside it.
    @GameTest
    public void dragonCannotBreakGrave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(level.getGameRules().get(GameRules.MOB_GRIEFING), "mob griefing is off, so the dragon breaks nothing");
        Grave grave = makeGrave(helper, new BlockPos(3, 0, 3));
        BlockPos stone = grave.pos().east();
        try {
            level.setBlock(stone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            EnderDragon dragon = EntityTypes.ENDER_DRAGON.create(level, EntitySpawnReason.COMMAND);
            helper.assertTrue(dragon != null, "no dragon was made");
            // Inside both blocks and no others, since the dragon takes every block the box's corners fall in
            AABB box = new AABB(grave.pos().getX() + 0.1, grave.pos().getY() + 0.1, grave.pos().getZ() + 0.1,
                    stone.getX() + 0.9, stone.getY() + 0.9, stone.getZ() + 0.9);
            callHooked(EnderDragon.class, "checkWalls", new Class<?>[]{ServerLevel.class, AABB.class}, dragon, level, box);
            helper.assertTrue(level.getBlockState(stone).isAir(), "the dragon did not break the stone beside the grave");
            assertGraveIntact(helper, grave);
        } finally {
            level.setBlock(stone, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            removeGraves(helper, List.of(grave));
        }
        helper.succeed();
    }

    // A hurt Wither breaks the blocks round itself, without an explosion: a hit sets destroyBlocksTick, and the server
    // step it counts down to zero in does the breaking. The Wither isn't added to the level, so it can't fly off or
    // fight. The grave and the stone are either side of it.
    @GameTest
    public void witherCannotBreakGrave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(level.getGameRules().get(GameRules.MOB_GRIEFING), "mob griefing is off, so the Wither breaks nothing");
        Grave grave = makeGrave(helper, new BlockPos(3, 0, 4));
        BlockPos stone = grave.pos().east(2);
        try {
            level.setBlock(stone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            WitherBoss wither = EntityTypes.WITHER.create(level, EntitySpawnReason.COMMAND);
            helper.assertTrue(wither != null, "no Wither was made");
            wither.snapTo(Vec3.atBottomCenterOf(grave.pos().east()), 0.0F, 0.0F);
            setField(WitherBoss.class, "destroyBlocksTick", wither, 1);
            callHooked(WitherBoss.class, "customServerAiStep", new Class<?>[]{ServerLevel.class}, wither, level);
            helper.assertTrue(level.getBlockState(stone).isAir(), "the Wither did not break the stone beside it");
            assertGraveIntact(helper, grave);
        } finally {
            // The broken stone dropped as cobblestone
            near(helper, EntityTypes.ITEM, stone, 2.0).forEach(Entity::discard);
            level.setBlock(stone, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            removeGraves(helper, List.of(grave));
        }
        helper.succeed();
    }

    @GameTest
    public void missingHeadRestoredOnChunkLoad(GameTestHelper helper) {
        Grave grave = makeGrave(helper, new BlockPos(3, 0, 3));
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

    // Gone as to /setblock while its chunk stays loaded, so no chunk load puts it back: the re-check does, within its
    // interval, here a second set the way a reload sets it
    @GameTest(environment = GRAVE_RECHECK, maxTicks = 60)
    public void missingHeadComesBackWhileChunkStaysLoaded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Grave grave = makeGrave(helper, new BlockPos(3, 0, 3));
        ChunkPos chunk = ChunkPos.containing(grave.pos());
        YoltConfig.GravesSection graves = Settings.get().graves;
        RECHECK_HOLD.take(() -> {
            int seconds = graves.recheckSeconds;
            return () -> graves.recheckSeconds = seconds;
        });
        Runnable cleanUp = BodyTestKit.once(() -> {
            RECHECK_HOLD.release();
            LiveSettings.applyAll(server);
            removeGraves(helper, List.of(grave));
        });
        boolean waiting = false;
        try {
            graves.recheckSeconds = 1;
            LiveSettings.applyAll(server);
            level.setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.runAfterDelay(30, () -> {
                try {
                    helper.assertTrue(level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null, "the grave's chunk unloaded");
                    assertGraveIntact(helper, grave);
                    helper.assertValueEqual(GraveStore.get(server).byId(grave.id()).map(Grave::pos), Optional.of(grave.pos()),
                            "grave's spot");
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

    // A grave the re-check can't put back: its spot is taken, and the border, moved far off in this tick, leaves no
    // loaded spot to move it to. The failure is logged once. The grave then waits for its chunk's next load, even once
    // it could be put back; after that load the re-check tries it again, and a second failure of the same kind logs
    // nothing. Everything goes back in this tick.
    @GameTest
    public void recheckLogsAFailureOnceAndWaitsForTheChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GraveStore store = GraveStore.get(level.getServer());
        WorldBorder border = level.getWorldBorder();
        double size = border.getSize();
        double centerX = border.getCenterX();
        double centerZ = border.getCenterZ();
        Grave grave = makeGrave(helper, new BlockPos(3, 0, 3));
        BlockPos spot = grave.pos();
        BlockPos above = spot.above();
        Runnable borderFar = () -> {
            border.setCenter(spot.getX() + 100_000, spot.getZ());
            border.setSize(16);
        };
        Runnable recheck = () -> GraveRecheck.recheck(level, store, store.byId(grave.id()).orElseThrow());
        String failure = "Could not put back the head of Ghost's grave";
        try {
            level.setBlock(spot, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            borderFar.run();
            helper.assertValueEqual(failures(recheck, failure), 1L, "failure lines from the first re-check");
            helper.assertValueEqual(failures(recheck, failure), 0L, "failure lines from the second re-check");

            border.setSize(size);
            border.setCenter(centerX, centerZ);
            recheck.run();
            helper.assertValueEqual(store.byId(grave.id()).map(Grave::pos), Optional.of(spot), "grave's spot before its chunk loads");
            helper.assertTrue(level.getBlockState(above).isAir(), "the re-check put the grave back before its chunk loaded");

            GraveBlocks.restoreChunk(level, level.getChunkAt(spot));
            Grave moved = store.byId(grave.id()).orElseThrow();
            helper.assertValueEqual(moved.pos(), above, "grave's spot after its chunk loaded");
            helper.assertTrue(GraveBlocks.isOwnHead(level, above, moved), "no head after the chunk loaded");
            level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            recheck.run();
            helper.assertTrue(GraveBlocks.isOwnHead(level, above, moved), "the re-check left the grave alone after its chunk loaded");

            level.setBlock(above, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            borderFar.run();
            helper.assertValueEqual(failures(recheck, failure), 0L, "failure lines from a later failure of the same kind");
        } finally {
            border.setSize(size);
            border.setCenter(centerX, centerZ);
            store.remove(grave.id());
            level.setBlock(spot, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        helper.succeed();
    }

    // The re-check finds the grave's spot taken and would move the grave up a block, but a folder stands where the
    // grave file goes, so the moved record can't reach the disk first. The grave stays where it was, with no head
    // anywhere for now, and says so. The file is written again in this tick.
    @GameTest
    public void movedGraveThatCannotBeSavedStaysPut(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        GraveStore store = GraveStore.get(server);
        Grave grave = makeGrave(helper, new BlockPos(3, 0, 3));
        BlockPos spot = grave.pos();
        BlockPos above = spot.above();
        Path file = GraveStore.TYPE.id().withSuffix(".dat").resolveAgainst(server.getWorldPath(LevelResource.DATA));
        try {
            level.setBlock(spot, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            Files.deleteIfExists(file);
            Files.createDirectories(file);
            List<String> log = OfflineGameTests.yoltLog(() -> GraveRecheck.recheck(level, store, grave));

            String line = "Could not save Ghost's grave at its new spot " + above + ", so it stays at "
                    + level.dimension().identifier() + " " + spot + " without a head for now";
            helper.assertValueEqual(log.stream().filter(entry -> entry.startsWith("Could not save Ghost's grave")).toList(),
                    List.of(line), "the failed move's log lines");
            helper.assertValueEqual(store.byId(grave.id()).map(Grave::pos), Optional.of(spot), "grave's spot");
            helper.assertTrue(level.getBlockState(above).isAir(), "a head went down at the spot the grave could not move to");
            helper.assertTrue(level.getBlockState(spot).is(Blocks.STONE), "the block on the grave's spot changed");
            // So the next save writes the grave where it still is
            helper.assertTrue(store.isDirty(), "the grave store is not marked to be saved again");
        } finally {
            if (Files.isDirectory(file)) {
                Files.delete(file);
            }
            store.remove(grave.id());
            level.setBlock(spot, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            store.saveNow(server);
        }
        helper.succeed();
    }

    // How many lines YOLT logged with that text while the action ran
    private static long failures(Runnable action, String text) {
        return OfflineGameTests.yoltLog(action).stream().filter(line -> line.contains(text)).count();
    }

    @GameTest(environment = GRAVES_SWITCH)
    public void restoreDoesNotOverwriteChest(GameTestHelper helper) {
        restoreLeavesContainer(helper, Blocks.CHEST);
    }

    @GameTest(environment = GRAVES_SWITCH)
    public void restoreDoesNotOverwriteShulker(GameTestHelper helper) {
        restoreLeavesContainer(helper, Blocks.SHULKER_BOX);
    }

    // A block set where a grave's head was, as /setblock can, is never overwritten by the head: the chunk's next load
    // moves the grave instead
    private static void restoreLeavesContainer(GameTestHelper helper, Block block) {
        ServerLevel level = helper.getLevel();
        GraveStore store = GraveStore.get(level.getServer());
        Grave grave = makeGrave(helper, new BlockPos(3, 0, 3));
        BlockPos spot = grave.pos();
        try {
            level.setBlock(spot, block.defaultBlockState(), Block.UPDATE_ALL);
            ((Container) level.getBlockEntity(spot)).setItem(0, new ItemStack(Items.DIAMOND, 3));
            GraveBlocks.restoreChunk(level, level.getChunkAt(spot));

            String name = block.getName().getString();
            helper.assertTrue(level.getBlockState(spot).is(block), "the " + name + " was replaced");
            helper.assertTrue(level.getBlockEntity(spot) instanceof Container container && container.countItem(Items.DIAMOND) == 3,
                    "the " + name + " does not hold exactly 3 diamonds");
            helper.assertValueEqual(GraveDeathGameTests.itemsNear(helper, Items.DIAMOND, spot, 3.0), 0, "diamonds spilled from the " + name);
            Grave moved = store.byId(grave.id()).orElse(null);
            helper.assertTrue(moved != null, "grave record is gone");
            helper.assertFalse(moved.pos().equals(spot), "grave record still points at the taken spot");
            // A head right on top would keep a shulker box's lid shut
            helper.assertFalse(moved.pos().equals(spot.above()), "grave moved right on top of the " + name);
            assertGraveIntact(helper, moved);
            helper.assertTrue(GraveAsserts.sameStacks(moved.items(), grave.items()), "grave items changed: " + moved.items());
        } finally {
            // Emptied first, so removing the chest doesn't spill it
            if (level.getBlockEntity(spot) instanceof Container container) {
                container.clearContent();
            }
            level.setBlock(spot, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            removeGraves(helper, store.byId(grave.id()).stream().toList());
        }
        helper.succeed();
    }

    // Graves switched off stop only new ones. An old grave still can't be broken or blown up, and still opens. A missing
    // head still comes back when its chunk loads, here a far one loaded for the test, and on the re-check. Graves stay off
    // across ticks, so the test runs apart.
    @GameTest(environment = GRAVES_SWITCH, maxTicks = 20)
    public void gravesOffKeepsOldGravesProtected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GraveStore store = GraveStore.get(level.getServer());
        ChunkPos chunk = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO).offset(4000, 0, 4000));
        helper.assertTrue(level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null, "the far chunk is loaded already");
        Grave grave = makeGrave(helper, new BlockPos(3, 0, 3));
        // Only the record, with nothing loaded where it is: its head goes down when the chunk loads
        UUID farOwner = UUID.randomUUID();
        ResolvableProfile farProfile = ResolvableProfile.createResolved(new GameProfile(farOwner, "Faraway"));
        Grave far = new Grave(UUID.randomUUID(), farOwner, "Faraway", farProfile, level.dimension(),
                chunk.getMiddleBlockPosition(100), List.of(GraveItems.graveHead(farProfile), new ItemStack(Items.DIAMOND)), 1L);
        store.put(far);
        ServerPlayer player = TestPlayers.join(helper, "OffOpener", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        YoltConfig.GravesSection graves = Settings.get().graves;
        GRAVES_OFF_HOLD.take(() -> {
            boolean enabled = graves.enabled;
            return () -> graves.enabled = enabled;
        });
        Runnable cleanUp = BodyTestKit.once(() -> {
            GRAVES_OFF_HOLD.release();
            TestPlayers.leave(player);
            removeGraves(helper, List.of(grave, far));
            level.setChunkForced(chunk.x(), chunk.z(), false);
        });
        boolean waiting = false;
        try {
            graves.enabled = false;
            helper.assertFalse(player.gameMode.destroyBlock(grave.pos()), "a player broke the grave with graves off");
            assertGraveIntact(helper, grave);
            Vec3 above = Vec3.atBottomCenterOf(grave.pos().above());
            level.explode(null, above.x, above.y, above.z, 4.0F, Level.ExplosionInteraction.TNT);
            assertGraveIntact(helper, grave);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(grave.pos()), Direction.UP, grave.pos(), false);
            player.gameMode.useItemOn(player, level, player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(player.containerMenu instanceof GraveMenu, "the grave did not open with graves off");
            player.closeContainer();

            // Loaded now and kept loaded; the restore waits for the end of the tick
            level.setChunkForced(chunk.x(), chunk.z(), true);
            helper.assertTrue(level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null, "the far chunk did not load");
            helper.runAfterDelay(3, () -> {
                try {
                    helper.assertTrue(GraveBlocks.isOwnHead(level, far.pos(), far), "the chunk's load left the far grave without a head");
                    level.setBlock(far.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    GraveRecheck.recheck(level, store, far);
                    helper.assertTrue(GraveBlocks.isOwnHead(level, far.pos(), far), "the re-check left the far grave without a head");
                    helper.assertValueEqual(store.byId(far.id()).map(Grave::pos), Optional.of(far.pos()), "far grave's spot");
                    assertGraveIntact(helper, grave);
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

    // Another player's head set on a grave's spot, for example with /setblock, stays theirs: the grave neither takes it
    // for its own nor deletes it
    @GameTest
    public void foreignHeadOnGraveSpotIsKept(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        GraveStore store = GraveStore.get(server);
        ResolvableProfile stranger = ResolvableProfile.createResolved(new GameProfile(UUID.randomUUID(), "Stranger"));
        Grave restored = makeGrave(helper, new BlockPos(3, 0, 3));
        Grave removed = makeGrave(helper, new BlockPos(5, 0, 5));
        BlockPos spot = restored.pos();
        try {
            setHead(level, spot, stranger);
            helper.assertFalse(Graves.isGrave(level, spot), "the other head counts as the grave");
            GraveBlocks.restoreChunk(level, level.getChunkAt(spot));
            assertHeadOf(helper, spot, stranger);
            Grave moved = store.byId(restored.id()).orElse(null);
            helper.assertTrue(moved != null, "grave record is gone");
            helper.assertFalse(moved.pos().equals(spot), "grave still claims the other head's spot");
            assertGraveIntact(helper, moved);

            // Removal drops the grave's two stacks, each once, and clears only its own head
            setHead(level, removed.pos(), stranger);
            helper.assertValueEqual(Graves.removeAndDrop(server, removed), new Graves.Removal.Dropped(2), "removal");
            assertHeadOf(helper, removed.pos(), stranger);
            List<ItemEntity> drops = near(helper, EntityTypes.ITEM, removed.pos(), 3.0);
            helper.assertValueEqual(drops.size(), 2, "item entities dropped");
            helper.assertValueEqual(drops.stream().filter(drop -> GraveItems.isGraveHead(drop.getItem())).count(), 1L,
                    "grave heads dropped");
            helper.assertValueEqual(drops.stream().filter(drop -> drop.getItem().is(Items.DIAMOND))
                    .mapToInt(drop -> drop.getItem().getCount()).sum(), 1, "diamonds dropped");
        } finally {
            near(helper, EntityTypes.ITEM, removed.pos(), 3.0).forEach(Entity::discard);
            level.setBlock(spot, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(removed.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            removeGraves(helper, store.all().stream()
                    .filter(grave -> grave.id().equals(restored.id()) || grave.id().equals(removed.id())).toList());
        }
        helper.succeed();
    }

    // Another player's head set on the spot while a looter has the grave open: emptying it removes the grave, and the
    // head stays theirs
    @GameTest
    public void foreignHeadKeptWhenGraveIsEmptied(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GraveStore store = GraveStore.get(level.getServer());
        ResolvableProfile stranger = ResolvableProfile.createResolved(new GameProfile(UUID.randomUUID(), "Stranger"));
        Grave grave = makeGrave(helper, new BlockPos(3, 0, 3));
        ServerPlayer looter = TestPlayers.join(helper, "Emptier", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            GraveMenus.open(looter, grave);
            AbstractContainerMenu menu = looter.containerMenu;
            helper.assertTrue(menu instanceof GraveMenu, "the grave did not open");
            setHead(level, grave.pos(), stranger);
            menu.clicked(0, 0, ContainerInput.QUICK_MOVE, looter);
            menu.clicked(1, 0, ContainerInput.QUICK_MOVE, looter);

            helper.assertTrue(store.byId(grave.id()).isEmpty(), "the emptied grave's record is still there");
            helper.assertTrue(looter.containerMenu == looter.inventoryMenu, "the grave's menu is still open");
            assertHeadOf(helper, grave.pos(), stranger);
            helper.assertValueEqual(looter.getInventory().countItem(Items.DIAMOND), 1, "diamonds the looter has");
            helper.assertValueEqual(looter.getInventory().countItem(Items.PLAYER_HEAD), 1, "heads the looter has");
        } finally {
            TestPlayers.leave(looter);
            level.setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            store.remove(grave.id());
        }
        helper.succeed();
    }

    // A click on another player's head that sits on a grave's spot is a click on that head: the grave doesn't open
    @GameTest
    public void foreignHeadOnGraveSpotDoesNotOpenIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GraveStore store = GraveStore.get(level.getServer());
        ResolvableProfile stranger = ResolvableProfile.createResolved(new GameProfile(UUID.randomUUID(), "Stranger"));
        Grave grave = makeGrave(helper, new BlockPos(3, 0, 3));
        ServerPlayer player = TestPlayers.join(helper, "Clicker", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            setHead(level, grave.pos(), stranger);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(grave.pos()), Direction.UP, grave.pos(), false);
            player.gameMode.useItemOn(player, level, player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(player.containerMenu == player.inventoryMenu, "a menu opened: " + player.containerMenu);
            helper.assertTrue(GraveMenus.containerFor(grave) == null, "the grave's container is open");
            helper.assertTrue(store.byId(grave.id()).isPresent(), "the grave record is gone");
            assertHeadOf(helper, grave.pos(), stranger);
        } finally {
            TestPlayers.leave(player);
            level.setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            store.remove(grave.id());
        }
        helper.succeed();
    }

    // A fresh head block with only an owner, the way /setblock or a placed head item makes one
    private static void setHead(ServerLevel level, BlockPos pos, ResolvableProfile owner) {
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, Blocks.PLAYER_HEAD.defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof SkullBlockEntity skull) {
            skull.applyComponents(DataComponentMap.EMPTY, DataComponentPatch.builder().set(DataComponents.PROFILE, owner).build());
        }
    }

    private static void assertHeadOf(GameTestHelper helper, BlockPos pos, ResolvableProfile owner) {
        helper.assertTrue(helper.getLevel().getBlockEntity(pos) instanceof SkullBlockEntity skull && owner.equals(skull.getOwnerProfile()),
                "the other player's head at " + pos + " is gone or changed");
    }

    @GameTest
    public void offlineKillMakesGraveWithSavedItems(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "OffGrave", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        BlockPos last = player.blockPosition();
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
            helper.assertTrue(ItemStack.matches(items.get(1), new ItemStack(Items.DIAMOND)), "second item: " + items.get(1));
            helper.assertTrue(items.get(2).is(Items.IRON_HELMET), "third item is not the iron helmet");
            assertGraveIntact(helper, grave);
            helper.assertTrue(near(helper, EntityTypes.ITEM, last, 3.0).isEmpty(), "items were dropped as well");
            // XP still drops as orbs, as in an online death
            helper.assertFalse(near(helper, EntityTypes.EXPERIENCE_ORB, last, 2.0).isEmpty(), "no XP dropped");

            CompoundTag saved = OfflinePlayerData.load(server, who).orElseThrow();
            helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 0.0F, "saved health");
            helper.assertTrue(OfflinePlayerData.takeInventory(saved, server.registryAccess(), who.name()).isEmpty(), "save still has items");
            helper.assertValueEqual(saved.getIntOr("XpLevel", -1), 0, "saved XP level");
            helper.assertTrue(DeathLockout.refusal(who.id(), "Test") != null, "offline kill did not start the lockout");
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
        BlockPos last = player.blockPosition();
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
        helper.assertTrue(near(helper, EntityTypes.ITEM, last, 3.0).isEmpty(), "items were dropped");
        CompoundTag saved = OfflinePlayerData.load(server, who).orElseThrow();
        helper.assertValueEqual(OfflinePlayerData.takeInventory(saved, server.registryAccess(), who.name()).size(), Inventory.INVENTORY_SIZE,
                "stacks left in the saved inventory");
        helper.assertValueEqual(OfflinePlayerData.takeEnderChest(saved, server.registryAccess(), who.name()).size(), 27,
                "stacks left in the saved ender chest");
        helper.assertTrue(DeathLockout.refusal(who.id(), "Test") == null, "failed kill started the lockout");
        helper.succeed();
    }

    @GameTest
    public void offlineKillKeepInventoryKeepsItems(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "OffKeep", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        BlockPos last = player.blockPosition();
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
            helper.assertValueEqual(GraveAsserts.count(OfflinePlayerData.takeInventory(saved, server.registryAccess(), who.name()),
                    Items.DIAMOND), 1, "diamonds in the save");
            helper.assertValueEqual(saved.getIntOr("XpLevel", -1), 30, "saved XP level");
            helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 0.0F, "saved health");
            Grave grave = onlyGrave(helper, who.id());
            helper.assertValueEqual(grave.items().size(), 1, "grave item count");
            helper.assertTrue(GraveItems.isGraveHead(grave.items().get(0)), "the only item is not the grave head");
            helper.assertTrue(near(helper, EntityTypes.ITEM, last, 3.0).isEmpty(), "items were dropped");
            helper.assertTrue(near(helper, EntityTypes.EXPERIENCE_ORB, last, 3.0).isEmpty(), "XP was dropped");
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
        BlockPos last = player.blockPosition();
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
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.IRON_HELMET), 0, "cursed helmets in the grave");
            helper.assertValueEqual(GraveAsserts.count(grave.items(), Items.DIAMOND), 1, "diamonds in the grave");
            helper.assertValueEqual(GraveDeathGameTests.itemsNear(helper, Items.IRON_HELMET, last, 3.0), 0, "cursed helmets dropped");
            CompoundTag saved = OfflinePlayerData.load(server, who).orElseThrow();
            helper.assertTrue(OfflinePlayerData.takeInventory(saved, server.registryAccess(), who.name()).isEmpty(), "save still has items");
        } finally {
            removeGraves(helper, gravesOf(helper, who.id()));
        }
        helper.succeed();
    }

    // Vanilla drops nothing for a dead spectator and gives everything back at respawn, so no grave either. A death in
    // spectator doesn't count, so no lockout.
    @GameTest
    public void offlineKillLeavesSpectatorsAlone(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "OffSpec", GameType.SPECTATOR, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        BlockPos last = player.blockPosition();
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
                helper.assertValueEqual(Kills.kill(server, who), Kills.Result.KILLED_UNCOUNTED, "kill result");
            } finally {
                graves.enderChestOnDeath = mode;
            }

            CompoundTag saved = OfflinePlayerData.load(server, who).orElseThrow();
            helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 0.0F, "saved health");
            helper.assertValueEqual(saved.getIntOr("XpLevel", -1), 30, "saved XP level");
            helper.assertValueEqual(GraveAsserts.count(OfflinePlayerData.takeInventory(saved, server.registryAccess(), who.name()),
                    Items.DIAMOND), 1, "diamonds in the save");
            helper.assertValueEqual(GraveAsserts.count(OfflinePlayerData.takeEnderChest(saved, server.registryAccess(), who.name()),
                    Items.EMERALD), 1, "emeralds in the saved ender chest");
            helper.assertTrue(gravesOf(helper, who.id()).isEmpty(), "a spectator got a grave");
            helper.assertTrue(near(helper, EntityTypes.ITEM, last, 3.0).isEmpty(), "items were dropped");
            helper.assertTrue(near(helper, EntityTypes.EXPERIENCE_ORB, last, 3.0).isEmpty(), "XP was dropped");
            helper.assertTrue(DeathLockout.newestDeath(who.id()) == null, "a spectator's offline death was recorded");
        } finally {
            removeGraves(helper, gravesOf(helper, who.id()));
        }
        helper.succeed();
    }

    // With the count switch on, a spectator's offline death counts: the lockout starts, and the grave holds only the
    // revive head, since the save keeps everything else for the respawn, the ender chest too. The switches go back in
    // this tick.
    @GameTest
    public void offlineCountedSpectatorKillLeavesOnlyTheHead(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "OffSpecCount", GameType.SPECTATOR, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        try {
            player.getInventory().add(new ItemStack(Items.DIAMOND));
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD));
        } finally {
            TestPlayers.leave(player);
        }

        YoltConfig config = Settings.get();
        boolean countBefore = config.deathLockout.countCreativeAndSpectatorDeaths;
        YoltConfig.EnderChestMode mode = config.graves.enderChestOnDeath;
        try {
            config.deathLockout.countCreativeAndSpectatorDeaths = true;
            config.graves.enderChestOnDeath = YoltConfig.EnderChestMode.GRAVE;
            try {
                helper.assertValueEqual(Kills.kill(server, who), Kills.Result.KILLED_OFFLINE, "kill result");
            } finally {
                config.deathLockout.countCreativeAndSpectatorDeaths = countBefore;
                config.graves.enderChestOnDeath = mode;
            }

            List<Grave> graves = gravesOf(helper, who.id());
            helper.assertValueEqual(graves.size(), 1, "graves");
            List<ItemStack> items = graves.get(0).items();
            helper.assertValueEqual(items.size(), 1, "stacks in the grave");
            helper.assertTrue(GraveItems.isGraveHead(items.get(0)), "the grave's only stack is not the head");
            helper.assertValueEqual(items.get(0).getCount(), 1, "heads in the grave");
            CompoundTag saved = OfflinePlayerData.load(server, who).orElseThrow();
            helper.assertValueEqual(saved.getFloatOr("Health", -1.0F), 0.0F, "saved health");
            helper.assertValueEqual(GraveAsserts.count(OfflinePlayerData.takeInventory(saved, server.registryAccess(), who.name()),
                    Items.DIAMOND), 1, "diamonds in the save");
            helper.assertValueEqual(GraveAsserts.count(OfflinePlayerData.takeEnderChest(saved, server.registryAccess(), who.name()),
                    Items.EMERALD), 1, "emeralds in the saved ender chest");
            helper.assertTrue(LockoutGameTests.refusedForLockout(DeathLockout.refusal(who.id(), "Test")),
                    "the counted death did not lock them out");
        } finally {
            removeGraves(helper, gravesOf(helper, who.id()));
        }
        helper.succeed();
    }

    // A static profile, so the head never looks anything up. The spot must be on something: in open air the grave
    // would drop to the floor.
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
    private static void assertNoHeadsNear(GameTestHelper helper, BlockPos center) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-4, -1, -4), center.offset(4, 10, 4))) {
            helper.assertFalse(helper.getLevel().getBlockState(pos).is(Blocks.PLAYER_HEAD), "player head left at " + pos);
        }
    }

    // Entities near an absolute position. Absolute, since vanilla's relativePos turns an unrotated test's position
    // half round.
    private static <T extends Entity> List<T> near(GameTestHelper helper, EntityType<T> type, BlockPos absolutePos, double distance) {
        return helper.getLevel().getEntities(type, new AABB(absolutePos).inflate(distance), Entity::isAlive);
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

    // 26.3 keeps Mojang's names at runtime, so a private vanilla method our mixin hooks can be called by name
    private static void callHooked(Class<?> owner, String name, Class<?>[] parameters, Object target, Object... arguments) {
        try {
            Method method = owner.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            method.invoke(target, arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not call " + owner.getSimpleName() + "." + name, e);
        }
    }

    private static void setField(Class<?> owner, String name, Object target, int value) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not set " + owner.getSimpleName() + "." + name, e);
        }
    }

    // What a client's start-break packet runs
    private static void startBreaking(ServerPlayer player, BlockPos pos) {
        player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, Direction.UP,
                player.level().getMaxY(), 0);
    }

    private static void removeGraves(GameTestHelper helper, List<Grave> graves) {
        GraveStore store = GraveStore.get(helper.getLevel().getServer());
        for (Grave grave : graves) {
            store.remove(grave.id());
            helper.getLevel().setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
