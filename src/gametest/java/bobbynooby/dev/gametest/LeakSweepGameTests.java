package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Offset;
import bobbynooby.dev.visibility.AdminView;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Every packet of a whole scene, read for a real X or Z, or a constant sent shifted. Each scene joins at one fixed real
// spot far out with the switch on, so every real position in it falls in a narrow band that no shifted one comes near,
// and every shifted constant in the band's mirror. Each scene also checks that the shifted spots it shows were sent, so
// a packet that was dropped can't pass for a clean one. The scenes share the spot and hold players across ticks, so each
// has a batch of its own. For the same reason they are not for a verify run, whose copies would break each other's
// blocks and switches; to repeat them, run the push job again.
public class LeakSweepGameTests {
    private static final String WORLD = "you-only-live-twice-gametest:leak_sweep_world";
    private static final String ITEMS = "you-only-live-twice-gametest:leak_sweep_items";
    private static final String TRAVEL = "you-only-live-twice-gametest:leak_sweep_travel";
    private static final String KILL = "you-only-live-twice-gametest:leak_sweep_kill";
    // The scene's real spot, and the offset of every frame anchored there, which puts the spot's chunk at 0, 0
    private static final Vec3 SPOT = new Vec3(12_296.5, 100.0, -12_280.5);
    private static final BlockPos SPOT_BLOCK = BlockPos.containing(SPOT);
    private static final Offset OFFSET = new Offset(-12_288, 12_288);
    // Real positions within 512 blocks of the spot's chunk. Shifted, the same positions sit within 512 of 0, 0. A constant
    // shifted on its way out, such as a zero the client ignores, sits within 512 of the offset itself.
    private static final int REACH = 512;
    private static final LeakScanner.Band BAND = LeakScanner.Band.around("real", -OFFSET.x(), -OFFSET.z(), REACH);
    private static final LeakScanner.Band OFFSET_BAND = LeakScanner.Band.around("offset", OFFSET.x(), OFFSET.z(), REACH);
    private static final List<LeakScanner.Band> BANDS = List.of(BAND, OFFSET_BAND);
    // A north compass's target Z, the same for everyone
    private static final int NORTH_Z = -28_999_984;
    // A compass's target and a gateway's exit, in the band, so a leak of either shows
    private static final BlockPos TARGET = SPOT_BLOCK.offset(30, -36, -40);
    private static final BlockPos EXIT = SPOT_BLOCK.offset(-50, 0, 60);
    // The longest the client may take to get the chunk it stands in, and how long a scene goes on after its last step
    private static final int WAIT = 80;
    private static final int AFTER = 20;
    // Between the travel scene's steps, so each place's chunks start to go out
    private static final int STEP = 8;
    // A border this wide, with its east wall 100 blocks east of the spot
    private static final double WALLED = 4_000.0;

    // The check of the check: with the switch off, a real spot is found, once, and so is one deep in an item, in a sound's
    // eighths and in chunks, a place with only one axis real, and a zero sent shifted. The same spot shifted is not. A
    // watched spot shows as seen only once a packet holds it, and a part that can't be read is named.
    @GameTest
    public void scannerFindsARealPosition(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper.getLevel().getServer(), "lsscanner", GameType.CREATIVE, SPOT_BLOCK);
        try {
            helper.assertTrue(CoordinateOffsets.offset(player).isZero(), "the player got an offset with the switch off");
            BlockState stone = Blocks.STONE.defaultBlockState();
            int mark = OffsetTestKit.mark(player);
            player.connection.send(new ClientboundBlockUpdatePacket(TARGET, stone));
            List<Packet<?>> sent = TestPlayers.sentPackets(player);
            List<String> found = scan(sent.subList(mark, sent.size()));
            helper.assertValueEqual(found.size(), 1, "findings in the block update sent: " + found);

            helper.assertValueEqual(findingsIn(new ClientboundBlockUpdatePacket(OFFSET.shift(TARGET), stone)), List.of(),
                    "findings in a block update at the shifted spot");
            helper.assertValueEqual(findingsIn(new ClientboundContainerSetContentPacket(0, 1, List.of(nestedCompass()), ItemStack.EMPTY)).size(),
                    1, "findings for a compass in a shulker box in a bundle");
            helper.assertValueEqual(findingsIn(new ClientboundSoundPacket(SoundEvents.NOTE_BLOCK_BELL, SoundSource.BLOCKS, SPOT.x, SPOT.y,
                    SPOT.z, 1.0F, 1.0F, 0L)).size(), 2, "findings in a sound, one each for x and z");
            ChunkPos chunk = ChunkPos.containing(SPOT_BLOCK);
            helper.assertValueEqual(findingsIn(new ClientboundSetChunkCacheCenterPacket(chunk.x(), chunk.z())).size(), 2,
                    "findings in a chunk centre, one each for x and z");
            // A north target whose X was left real, with the Z every player has
            helper.assertValueEqual(findingsIn(new ClientboundSetDefaultSpawnPositionPacket(LevelData.RespawnData.of(Level.OVERWORLD,
                    new BlockPos(TARGET.getX(), 64, NORTH_Z), 0.0F, 0.0F))).size(), 1, "findings for a north target with a real x");
            // The zero a portal sound is played at, sent shifted, which is the offset itself
            helper.assertValueEqual(findingsIn(new ClientboundLevelEventPacket(LevelEvent.SOUND_PORTAL_TRAVEL, OFFSET.shift(BlockPos.ZERO), 0,
                    false)).size(), 1, "findings for a portal sound at the shifted zero");

            LeakScanner.Scan watching = new LeakScanner.Scan(BANDS, List.of(OFFSET.shift(TARGET)));
            helper.assertValueEqual(watching.missing(), List.of(OFFSET.shift(TARGET)), "watched spots missing before any packet");
            watching.packet(new ClientboundBlockUpdatePacket(OFFSET.shift(TARGET), stone));
            helper.assertValueEqual(watching.missing(), List.of(), "watched spots missing after a block update there");
            LeakScanner.Scan locked = new LeakScanner.Scan(BANDS, List.of());
            locked.packet(new Unreadable(TARGET.getX()));
            helper.assertValueEqual(List.copyOf(locked.unread()), List.of(Unreadable.class.getName() + ".x"), "parts left unread");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest(environment = WORLD, maxTicks = 200)
    public void noRealCoordinateInTheWorld(GameTestHelper helper) {
        Sweep sweep = new Sweep(helper);
        sweep.guard(() -> {
            ServerPlayer player = sweep.join("lsworld", GameType.CREATIVE, SPOT_BLOCK, true);
            helper.assertValueEqual(CoordinateOffsets.offset(player), OFFSET, "offset at the spot");
            ServerPlayer sleeper = sweep.join("lssleeper", GameType.CREATIVE, SPOT_BLOCK.offset(-3, 0, 6), true);
            sweep.expect(TARGET);
            sweep.expect(EXIT);
            sweep.at(0, () -> worldScene(helper, sweep, player, sleeper));
            sweep.start(player);
        });
    }

    @GameTest(environment = ITEMS, maxTicks = 200)
    public void noRealCoordinateInItems(GameTestHelper helper) {
        Sweep sweep = new Sweep(helper);
        sweep.guard(() -> {
            ServerPlayer player = sweep.join("lsitems", GameType.CREATIVE, SPOT_BLOCK, true);
            helper.assertValueEqual(CoordinateOffsets.offset(player), OFFSET, "offset at the spot");
            ServerPlayer holder = sweep.join("lsholder", GameType.CREATIVE, SPOT_BLOCK.offset(-3, 0, 3), true);
            // Held from the start, so the player is sent it as soon as it sees the holder
            holder.setItemInHand(InteractionHand.MAIN_HAND, compass(TARGET));
            sweep.expect(TARGET);
            sweep.at(0, () -> itemsScene(helper, sweep, player));
            sweep.start(player);
        });
    }

    // Every respawn spot and destination is the spot itself, so each new frame has the same offset and one band does
    @GameTest(environment = TRAVEL, maxTicks = 200)
    public void noRealCoordinateAfterRespawnAndTravel(GameTestHelper helper) {
        Sweep sweep = new Sweep(helper);
        sweep.guard(() -> {
            ServerPlayer player = sweep.join("lstravel", GameType.CREATIVE, SPOT_BLOCK, true);
            helper.assertValueEqual(CoordinateOffsets.offset(player), OFFSET, "offset at the spot");
            // Where every teleport and respawn puts the client
            sweep.expect(SPOT_BLOCK);
            travelScene(helper, sweep, player.getUUID());
            sweep.start(player);
        });
    }

    // A killer with no offset holds a named compass, which the death message shows with its target
    @GameTest(environment = KILL, maxTicks = 200)
    public void noRealCoordinateInAKill(GameTestHelper helper) {
        Sweep sweep = new Sweep(helper);
        sweep.guard(() -> {
            ServerLevel level = helper.getLevel();
            MinecraftServer server = level.getServer();
            GameRules rules = level.getGameRules();
            YoltConfig config = Settings.get();
            boolean messagesBefore = rules.get(GameRules.SHOW_DEATH_MESSAGES);
            boolean pvpBefore = rules.get(GameRules.PVP);
            boolean lockoutBefore = config.deathLockout.enabled;
            boolean gravesBefore = config.graves.enabled;
            boolean hiddenBefore = config.hide.gameMessages;
            sweep.onCleanUp(() -> {
                rules.set(GameRules.SHOW_DEATH_MESSAGES, messagesBefore, server);
                rules.set(GameRules.PVP, pvpBefore, server);
                config.deathLockout.enabled = lockoutBefore;
                config.graves.enabled = gravesBefore;
                config.hide.gameMessages = hiddenBefore;
            });
            // Death messages for all, and no lockout or grave, so the death is only vanilla's
            rules.set(GameRules.SHOW_DEATH_MESSAGES, true, server);
            rules.set(GameRules.PVP, true, server);
            config.deathLockout.enabled = false;
            config.graves.enabled = false;
            config.hide.gameMessages = false;
            ServerPlayer victim = sweep.join("lsvictim", GameType.SURVIVAL, SPOT_BLOCK, true);
            helper.assertValueEqual(CoordinateOffsets.offset(victim), OFFSET, "offset at the spot");
            sweep.join("lsbystander", GameType.CREATIVE, SPOT_BLOCK.offset(-2, 0, 0), true);
            ServerPlayer killer = sweep.join("lskiller", GameType.SURVIVAL, SPOT_BLOCK.offset(2, 0, 0), false);
            ItemStack compass = compass(TARGET);
            compass.set(DataComponents.CUSTOM_NAME, Component.literal("Home"));
            killer.setItemInHand(InteractionHand.MAIN_HAND, compass);
            sweep.expect(TARGET);
            // A few ticks in, so both have seen the killer with the compass in hand
            sweep.at(5, () -> {
                TestPlayers.markLoaded(victim);
                victim.hurtServer(level, level.damageSources().playerAttack(killer), Float.MAX_VALUE);
                helper.assertTrue(victim.isDeadOrDying(), "the victim did not die");
            });
            sweep.start(victim);
        });
    }

    // Blocks the client places and breaks, block entities, entities, effects and a sleeper, all near the player
    private static void worldScene(GameTestHelper helper, Sweep sweep, ServerPlayer player, ServerPlayer sleeper) {
        ServerLevel level = helper.getLevel();
        OffsetTestKit.settle(helper, player);
        BlockPos base = sweep.place(SPOT_BLOCK.offset(2, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState());
        BlockPos client = OFFSET.shift(base);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
        BlockHitResult hit = new BlockHitResult(new Vec3(client.getX() + 0.5, client.getY() + 1.0, client.getZ() + 0.5), Direction.UP,
                client, false);
        TestPlayers.receive(player, new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 1));
        helper.assertTrue(level.getBlockState(base.above()).is(Blocks.STONE), "the client's stone was not placed");
        sweep.clearAtTheEnd(base.above());
        TestPlayers.receive(player, new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                client.above(), Direction.UP, 2));
        helper.assertTrue(level.getBlockState(base.above()).isAir(), "the client's stone was not broken");

        BlockPos signPos = sweep.place(SPOT_BLOCK.offset(-2, 0, 0), Blocks.OAK_SIGN.defaultBlockState());
        SignBlockEntity sign = (SignBlockEntity) level.getBlockEntity(signPos);
        sign.setAllowedPlayerEditor(player.getUUID());
        player.openTextEdit(sign, SignTextSlot.FRONT);

        campfireWithCompass(level, sweep, SPOT_BLOCK.offset(0, 0, -2));
        BlockPos gatewayPos = sweep.place(SPOT_BLOCK.offset(-3, 0, -3), Blocks.END_GATEWAY.defaultBlockState());
        ((TheEndGatewayBlockEntity) level.getBlockEntity(gatewayPos)).setExitPosition(EXIT, false);
        resend(level, gatewayPos);
        BlockPos dirt = sweep.place(SPOT_BLOCK.offset(3, 0, -3), Blocks.DIRT.defaultBlockState());
        sweep.place(dirt.above(), Blocks.BAMBOO.defaultBlockState());

        // Each on a floor of its own, so it stays in view
        BlockPos pigAt = sweep.place(SPOT_BLOCK.offset(4, -1, 2), Blocks.STONE.defaultBlockState()).above();
        Mob pig = EntityTypes.PIG.spawn(level, pigAt, EntitySpawnReason.TRIGGERED);
        helper.assertTrue(pig != null, "no pig was spawned");
        pig.setNoAi(true);
        sweep.removeAtTheEnd(pig);
        BlockPos frameWall = sweep.place(SPOT_BLOCK.offset(-4, 0, 2), Blocks.STONE.defaultBlockState());
        ItemFrame frame = new ItemFrame(level, frameWall.south(), Direction.SOUTH);
        frame.setItem(compass(TARGET), false);
        sweep.add(level, frame);
        BlockPos sandAt = sweep.place(SPOT_BLOCK.offset(5, -1, -1), Blocks.STONE.defaultBlockState()).above();
        sweep.clearAtTheEnd(sandAt);
        sweep.removeAtTheEnd(FallingBlockEntity.fall(level, sandAt, Blocks.SAND.defaultBlockState()));
        BlockPos cartAt = sweep.place(SPOT_BLOCK.offset(-5, -1, -1), Blocks.STONE.defaultBlockState()).above();
        Entity cart = EntityTypes.MINECART.create(level, EntitySpawnReason.TRIGGERED);
        helper.assertTrue(cart != null, "no minecart was made");
        cart.snapTo(Vec3.atBottomCenterOf(cartAt));
        sweep.add(level, cart);
        AreaEffectCloud cloud = new AreaEffectCloud(level, SPOT.x, SPOT.y, SPOT.z + 6.0);
        cloud.setCustomParticle(new TrailParticleOption(Vec3.atCenterOf(TARGET), 0x00FF00, 40));
        sweep.add(level, cloud);
        // Far enough off that it breaks and hurts nothing of the scene's
        PrimedTnt tnt = new PrimedTnt(level, SPOT.x + 8.0, SPOT.y, SPOT.z + 14.0, null);
        tnt.setFuse(0);
        sweep.add(level, tnt);

        level.playSound(null, SPOT.x + 1.0, SPOT.y + 1.0, SPOT.z + 1.0, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.sendParticles(new VibrationParticleOption(new BlockPositionSource(SPOT_BLOCK.offset(10, 0, 10)), 20), SPOT.x, SPOT.y + 2.0,
                SPOT.z, 1, 0.0, 0.0, 0.0, 0.0);
        level.levelEvent(null, LevelEvent.PARTICLES_DESTROY_BLOCK, SPOT_BLOCK.offset(2, 0, 2), Block.getId(Blocks.STONE.defaultBlockState()));
        level.destroyBlockProgress(4_242, base, 5);

        BlockPos bed = sweep.place(SPOT_BLOCK.offset(-3, 0, 7), Blocks.BED.pick(DyeColor.RED).defaultBlockState());
        helper.assertTrue(sleeper.startSleeping(bed), "the sleeper could not lie down");
        sweep.onCleanUp(sleeper::stopSleeping);
    }

    // Compasses in the inventory, nested in a bundle, on the cursor and in a trade, and an item picked with its block's data
    private static void itemsScene(GameTestHelper helper, Sweep sweep, ServerPlayer player) {
        player.getInventory().setItem(9, compass(TARGET));
        player.getInventory().setItem(10, nestedCompass());
        player.containerMenu.setCarried(compass(TARGET));
        player.inventoryMenu.sendAllDataToRemote();

        MerchantOffers offers = new MerchantOffers();
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 3), compass(TARGET), 12, 5, 0.05F));
        player.sendMerchantOffers(1, offers, 2, 70, true, false);

        // A gateway has no item to pick, so a campfire holding the compass stands in for a block with a position in its data
        BlockPos campfire = campfireWithCompass(helper.getLevel(), sweep, SPOT_BLOCK.offset(2, 0, 0));
        TestPlayers.receive(player, new ServerboundPickItemFromBlockPacket(OFFSET.shift(campfire), true));
        ItemStack picked = player.getInventory().getSelectedItem();
        helper.assertTrue(picked.is(Items.CAMPFIRE), "the pick gave " + picked);
        helper.assertTrue(picked.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItemCopyStream()
                .anyMatch(item -> ItemStack.isSameItemSameComponents(item, compass(TARGET))), "the picked campfire holds no compass: " + picked);
    }

    private static void travelScene(GameTestHelper helper, Sweep sweep, UUID id) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel overworld = helper.getLevel();
        ServerLevel nether = server.getLevel(Level.NETHER);
        ServerLevel end = server.getLevel(Level.END);
        helper.assertTrue(nether != null && end != null, "no nether or End to go to");
        WorldBorder border = overworld.getWorldBorder();
        double centreX = border.getCenterX();
        double centreZ = border.getCenterZ();
        double size = border.getSize();
        Runnable borderBack = () -> {
            border.setCenter(centreX, centreZ);
            border.setSize(size);
        };
        sweep.onCleanUp(borderBack);
        sweep.onCleanUp(() -> {
            ServerPlayer player = sweep.current(id);
            if (player != null && player.getAttachedOrElse(AdminView.ATTACHMENT, false)) {
                AdminView.toggle(player);
            }
            if (player != null) {
                TestPlayers.deop(player);
            }
        });
        // A death respawn back at the spot, in the same dimension, while the client holds its chunks
        sweep.at(0, () -> {
            ServerPlayer player = sweep.current(id);
            player.setHealth(0.0F);
            ServerPlayer back = OffsetTestKit.respawn(helper, player, SPOT_BLOCK, false);
            helper.assertValueEqual(CoordinateOffsets.offset(back), OFFSET, "offset after the respawn");
        });
        sweep.at(STEP, () -> travel(helper, sweep, id, nether));
        sweep.at(2 * STEP, () -> travel(helper, sweep, id, overworld));
        sweep.at(3 * STEP, () -> travel(helper, sweep, id, end));
        // The way back from the End, which is a respawn
        sweep.at(4 * STEP, () -> {
            ServerPlayer back = OffsetTestKit.respawn(helper, sweep.current(id), SPOT_BLOCK, true);
            helper.assertTrue(back.level() == overworld, "the way back from the End did not reach the overworld");
            helper.assertValueEqual(CoordinateOffsets.offset(back), OFFSET, "offset on the way back from the End");
        });
        sweep.at(5 * STEP, () -> {
            border.setCenter(SPOT.x + 100.0 - WALLED / 2.0, SPOT.z);
            border.setSize(WALLED);
        });
        sweep.at(6 * STEP, borderBack);
        // Admin view on and off, both within the frame, which keeps its offset until the next one starts
        sweep.at(7 * STEP, () -> {
            ServerPlayer player = sweep.current(id);
            TestPlayers.op(player);
            helper.assertTrue(AdminView.toggle(player), "admin view was on already");
        });
        sweep.at(8 * STEP, () -> {
            ServerPlayer player = sweep.current(id);
            AdminView.toggle(player);
            TestPlayers.deop(player);
            helper.assertValueEqual(CoordinateOffsets.offset(player), OFFSET, "offset after admin view went off");
        });
    }

    // With the sound a portal plays, sent at a zero the client ignores, which would show in the offset band sent shifted
    private static void travel(GameTestHelper helper, Sweep sweep, UUID id, ServerLevel to) {
        ServerPlayer player = sweep.current(id);
        OffsetTestKit.withCoordinates(() -> player.teleport(new TeleportTransition(to, SPOT, Vec3.ZERO, 0.0F, 0.0F,
                TeleportTransition.PLAY_PORTAL_SOUND)));
        helper.assertTrue(player.level() == to, "the player did not reach " + to.dimension().identifier());
        helper.assertValueEqual(CoordinateOffsets.offset(player), OFFSET, "offset in " + to.dimension().identifier());
        loadAround(to);
    }

    private static BlockPos campfireWithCompass(ServerLevel level, Sweep sweep, BlockPos at) {
        BlockPos pos = sweep.place(at, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        ((CampfireBlockEntity) level.getBlockEntity(pos)).getItems().set(0, compass(TARGET));
        resend(level, pos);
        return pos;
    }

    // A block entity changed after its block was placed goes to the clients again with its next update
    private static void resend(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
    }

    private static void loadAround(ServerLevel level) {
        ChunkPos at = ChunkPos.containing(SPOT_BLOCK);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.getChunk(at.x() + dx, at.z() + dz);
            }
        }
    }

    private static ItemStack compass(BlockPos target) {
        ItemStack compass = new ItemStack(Items.COMPASS);
        compass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(GlobalPos.of(Level.OVERWORLD, target)), true));
        return compass;
    }

    private static ItemStack nestedCompass() {
        NonNullList<ItemStack> slots = NonNullList.withSize(27, ItemStack.EMPTY);
        slots.set(3, compass(TARGET));
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots));
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(ItemStackTemplate.fromNonEmptyStack(box))));
        return bundle;
    }

    private static List<String> findingsIn(Object packet) {
        return LeakScanner.findings(packet, BAND, OFFSET_BAND);
    }

    private static List<String> scan(List<Packet<?>> packets) {
        List<String> found = new ArrayList<>();
        for (Packet<?> packet : packets) {
            found.addAll(findingsIn(packet));
        }
        return found;
    }

    // A part that throws as it is read, as a field locked by a later access rule would
    private record Unreadable(int x) {
        @Override
        public int x() {
            throw new IllegalStateException("locked");
        }
    }

    /**
     * One scene: its players, its steps, the real spots it shows shifted, and what it puts back. Once the main player's
     * client holds the chunk it stands in, the steps run on their ticks, and AFTER ticks past the last one every packet
     * each scanned player was sent is read. Everything is scheduled before the test's first tick runs, since the test
     * runner can't take new work while it runs a tick's. Players leave and the world goes back whatever happens.
     */
    private static final class Sweep {
        private final GameTestHelper helper;
        private final MinecraftServer server;
        // Every player the scene joined, by id, as a respawn makes a new one, and whether its packets are read
        private final Map<UUID, ServerPlayer> joined = new LinkedHashMap<>();
        private final List<UUID> scanned = new ArrayList<>();
        private final List<Step> steps = new ArrayList<>();
        private final List<BlockPos> expected = new ArrayList<>();
        private final List<Runnable> cleanUps = new ArrayList<>();
        private final List<BlockPos> blocks = new ArrayList<>();
        private final List<Entity> entities = new ArrayList<>();
        private boolean over;

        private record Step(int at, Runnable action) {}

        Sweep(GameTestHelper helper) {
            this.helper = helper;
            this.server = helper.getLevel().getServer();
        }

        // With the switch on and its packets read, or with it off and left unread, since that client is sent real ones
        ServerPlayer join(String name, GameType mode, BlockPos at, boolean offset) {
            ServerPlayer player = offset ? OffsetTestKit.joinNearTheCentre(helper, name, mode, at) : TestPlayers.join(server, name, mode, at);
            joined.put(player.getUUID(), player);
            if (offset) {
                scanned.add(player.getUUID());
            }
            return player;
        }

        @Nullable ServerPlayer current(UUID id) {
            return server.getPlayerList().getPlayer(id);
        }

        void at(int tick, Runnable action) {
            steps.add(new Step(tick, action));
        }

        // A real spot the scene shows, which some packet must hold shifted
        void expect(BlockPos real) {
            expected.add(real);
        }

        void onCleanUp(Runnable step) {
            cleanUps.add(step);
        }

        BlockPos place(BlockPos pos, BlockState state) {
            helper.getLevel().setBlockAndUpdate(pos, state);
            blocks.add(pos);
            return pos;
        }

        void clearAtTheEnd(BlockPos pos) {
            blocks.add(pos);
        }

        void add(ServerLevel level, Entity entity) {
            level.addFreshEntity(entity);
            entities.add(entity);
        }

        void removeAtTheEnd(Entity entity) {
            entities.add(entity);
        }

        void start(ServerPlayer main) {
            loadAround(main.level());
            UUID mainId = main.getUUID();
            int last = steps.stream().mapToInt(Step::at).max().orElse(0);
            int[] tick = {0};
            int[] sceneStart = {-1};
            helper.onEachTick(() -> guard(() -> {
                if (over) {
                    return;
                }
                int now = tick[0]++;
                for (UUID id : joined.keySet()) {
                    ServerPlayer player = current(id);
                    if (player != null) {
                        TestPlayers.driveTracking(player);
                    }
                }
                if (sceneStart[0] < 0) {
                    ServerPlayer player = current(mainId);
                    if (player == null || !CoordinateOffsets.holdsChunk(player, player.chunkPosition())) {
                        if (now >= WAIT) {
                            helper.fail("the client never got the chunk it stands in");
                        }
                        return;
                    }
                    sceneStart[0] = now;
                }
                int into = now - sceneStart[0];
                for (Step step : steps) {
                    if (step.at() == into) {
                        step.action().run();
                    }
                }
                if (into == last + AFTER) {
                    finish();
                }
            }));
            // A scene that runs out of time still leaves nothing behind
            helper.runBeforeTestEnd(this::cleanUp);
        }

        private void finish() {
            LeakScanner.Scan scan = new LeakScanner.Scan(BANDS, expected.stream().map(real -> OFFSET.shift(real)).toList());
            for (UUID id : scanned) {
                for (Packet<?> packet : TestPlayers.sentPackets(joined.get(id))) {
                    scan.packet(packet);
                }
            }
            cleanUp();
            List<String> found = scan.findings();
            helper.assertTrue(found.isEmpty(), found.size() + " real or shifted constant positions sent: "
                    + String.join("; ", found.subList(0, Math.min(found.size(), 25))));
            helper.assertValueEqual(scan.missing(), List.of(), "shifted spots the scene shows that no packet held");
            helper.assertValueEqual(scan.unreadMinecraft(), List.of(),
                    "Minecraft parts the scan couldn't read, of " + scan.unread().size() + " unread in all");
            helper.succeed();
        }

        void guard(Runnable step) {
            try {
                step.run();
            } catch (RuntimeException | Error e) {
                try {
                    cleanUp();
                } catch (RuntimeException cleanUpFailed) {
                    e.addSuppressed(cleanUpFailed);
                }
                throw e;
            }
        }

        // Each part even when one fails, so one broken part can't leave the rest behind
        private void cleanUp() {
            if (over) {
                return;
            }
            over = true;
            List<Runnable> parts = new ArrayList<>(cleanUps);
            parts.add(() -> entities.forEach(Entity::discard));
            parts.add(() -> {
                for (int i = blocks.size() - 1; i >= 0; i--) {
                    helper.getLevel().setBlockAndUpdate(blocks.get(i), Blocks.AIR.defaultBlockState());
                }
            });
            for (UUID id : joined.keySet()) {
                parts.add(() -> {
                    ServerPlayer player = current(id);
                    if (player != null) {
                        TestPlayers.leave(player);
                    }
                });
            }
            RuntimeException failed = null;
            for (Runnable part : parts) {
                try {
                    part.run();
                } catch (RuntimeException e) {
                    if (failed == null) {
                        failed = e;
                    } else {
                        failed.addSuppressed(e);
                    }
                }
            }
            if (failed != null) {
                throw failed;
            }
        }
    }
}
