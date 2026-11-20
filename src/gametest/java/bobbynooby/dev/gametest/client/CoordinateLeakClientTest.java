package bobbynooby.dev.gametest.client;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Offset;
import bobbynooby.dev.gametest.LeakScanner;
import bobbynooby.dev.gametest.PacketRecorder;
import bobbynooby.dev.world.RandomSpawn;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * No real coordinate reaches a real client with the offset on. Every packet the client decodes in play goes through the
 * leak scanner (LeakScanner), the one the server's own sweep uses, while a scene at a real spot far out sends it blocks,
 * block entities with positions in their data, entities, items, sounds, particles, an explosion, a death's respawn and
 * grave notice, and a trip to the nether and back. The server's sweep (LeakSweepGameTests) reads what the server meant to
 * send a fake player; this reads what a vanilla client actually got and kept. The texts it was sent are read too, which
 * the scanner skips, so a coordinate written into a chat line, title or kick shows as well.
 */
public class CoordinateLeakClientTest implements FabricClientGameTest {
    // In the chunk at 768, -768. Every frame anchored in that chunk gets the offset that puts it at 0, 0, so each place
    // the client is sent sits near 0, 0, and a real one in a narrow band far from it.
    private static final int SPOT_X = 12_296;
    private static final int SPOT_Z = -12_280;
    private static final Offset OFFSET = new Offset(-12_288, 12_288);
    private static final int REACH = 512;
    private static final LeakScanner.Band REAL = LeakScanner.Band.around("real", -OFFSET.x(), -OFFSET.z(), REACH);
    // A constant the client ignores, such as a zero, shifted on its way out, lands near the offset itself
    private static final LeakScanner.Band CONSTANT = LeakScanner.Band.around("a constant shifted", OFFSET.x(), OFFSET.z(), REACH);
    // A real place shifted the wrong way lands near twice the spot
    private static final LeakScanner.Band WRONG_SIGN = LeakScanner.Band.around("shifted the wrong way", -2 * OFFSET.x(),
            -2 * OFFSET.z(), REACH);
    private static final List<LeakScanner.Band> BANDS = List.of(REAL, CONSTANT, WRONG_SIGN);
    // Where the scene's compasses, crystal beam and trail point, and the gateway's exit: in the real band
    private static final int TARGET_DX = 30;
    private static final int TARGET_DZ = -40;
    private static final int NETHER_Y = 100;
    // Long enough for what the scene sends to be tracked, sent and handled
    private static final int SETTLE_TICKS = 40;
    private static final String GRAVE_LINE = "Your grave is at";
    // Packets of these phases hold registries, tags and the handshake: no place, and big, so they are skipped
    private static final List<String> SKIPPED_PACKAGES = List.of("net.minecraft.network.protocol.configuration.",
            "net.minecraft.network.protocol.login.", "net.minecraft.network.protocol.status.",
            "net.minecraft.network.protocol.handshake.");
    // What the scene has to send, by the end of each packet type's id
    private static final List<String> SCENE_PACKETS = List.of("block_entity_data", "add_entity", "set_entity_data", "sound",
            "level_particles", "level_event", "block_destruction", "explode", "container_set_content", "respawn",
            "level_chunk_with_light");
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,9}");

    // What the client is sent, read on its network thread; read back here only under the same lock
    private static final class Reading {
        private final LeakScanner.Scan scan;
        private final List<String> texts = new ArrayList<>();
        private final List<String> graveLines = new ArrayList<>();
        private int scanned;

        private Reading(Set<BlockPos> watched) {
            scan = new LeakScanner.Scan(BANDS, watched);
        }

        private synchronized void packet(Packet<?> packet) {
            String name = packet.getClass().getName();
            if (SKIPPED_PACKAGES.stream().anyMatch(name::startsWith)) {
                return;
            }
            scanned++;
            scan.packet(packet);
            readTexts(packet, packet.type().toString(), 0);
        }

        // The packet's own texts, one level into lists and optionals, and each bundled packet's
        private void readTexts(Object value, String where, int depth) {
            if (value instanceof BundlePacket<?> bundle) {
                for (Object part : bundle.subPackets()) {
                    if (part instanceof Packet<?> packet) {
                        readTexts(packet, packet.type().toString(), 0);
                    }
                }
                return;
            }
            if (value instanceof Component text) {
                checkText(text.getString(), where);
                return;
            }
            if (value instanceof Optional<?> optional) {
                optional.ifPresent(inner -> readTexts(inner, where, depth));
                return;
            }
            if (value instanceof Collection<?> collection) {
                collection.forEach(inner -> readTexts(inner, where, depth));
                return;
            }
            if (depth > 0 || !(value instanceof Packet<?>)) {
                return;
            }
            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        readTexts(field.get(value), where + "." + field.getName(), depth + 1);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        texts.add(where + "." + field.getName() + " couldn't be read: " + e);
                    }
                }
            }
        }

        private void checkText(String text, String where) {
            if (text.contains(GRAVE_LINE)) {
                graveLines.add(text);
            }
            Matcher number = NUMBER.matcher(text);
            while (number.find()) {
                long n = Long.parseLong(number.group());
                for (LeakScanner.Band band : List.of(REAL, WRONG_SIGN)) {
                    if (band.inX(n) || band.inZ(n)) {
                        texts.add(band.name() + " number " + n + " in " + where + ": \"" + text + "\"");
                    }
                }
            }
        }
    }

    private record Scene(BlockPos spot, Reading reading, PacketRecorder.Session session) {
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!ClientTests.selected(getClass())) {
            return;
        }
        // The config outlives each test's server, and between servers nothing else reads it. A random spawn would put the
        // client away from the spot. The offset is turned on once the server is up: the test setup turns it off at every
        // server start.
        YoltConfig config = Settings.get();
        boolean coordinatesWas = config.hide.coordinates;
        boolean randomSpawnWas = config.randomSpawn;
        config.randomSpawn = false;
        Scene[] scene = new Scene[1];
        try {
            ClientTests.withServer(ctx, server -> scene[0] = server.computeOnServer(CoordinateLeakClientTest::setUp),
                    (server, connection) -> run(ctx, server, connection, scene[0]));
        } finally {
            PacketRecorder.disarm();
            config.hide.coordinates = coordinatesWas;
            config.randomSpawn = randomSpawnWas;
        }
    }

    // Before the client connects, so the recording starts with its first byte and its join is anchored at the spot
    private static Scene setUp(MinecraftServer s) {
        Settings.get().hide.coordinates = true;
        ServerLevel overworld = s.overworld();
        BlockPos spot = standingSpot(overworld, SPOT_X, SPOT_Z);
        // The world is new for each test, so its spawn needs no setting back
        s.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, spot, 0.0F, 0.0F));
        // The control: places the client must get, shifted. A scan that never saw them read nothing worth trusting.
        Set<BlockPos> watched = Set.of(OFFSET.shift(gold(spot)), OFFSET.shift(sign(spot)), OFFSET.shift(campfire(spot)));
        Reading reading = new Reading(watched);
        PacketRecorder.Session session = PacketRecorder.arm(new PacketRecorder.Session(Map.of(), reading::packet));
        return new Scene(spot, reading, session);
    }

    private static void run(ClientGameTestContext ctx, TestDedicatedServerContext server, TestDedicatedServerConnection connection,
                            Scene scene) {
        checkOffset(server, connection, "the join");
        server.runOnServer(s -> build(s, connection.getServerPlayer(), scene.spot()));
        BlockPos shiftedGold = OFFSET.shift(gold(scene.spot()));
        ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> mc.level.getBlockState(shiftedGold).is(Blocks.GOLD_BLOCK),
                mc -> "the client never got the gold block at its shifted spot " + shiftedGold.toShortString());
        ctx.waitTicks(SETTLE_TICKS);
        connection.waitForClientboundPackets();

        dieAndRespawn(ctx, server, connection);
        travel(ctx, server, connection, Level.NETHER, new BlockPos(scene.spot().getX(), NETHER_Y, scene.spot().getZ()));
        travel(ctx, server, connection, Level.OVERWORLD, scene.spot());
        ctx.waitTicks(SETTLE_TICKS);
        connection.waitForClientboundPackets();

        check(scene);
    }

    private static void check(Scene scene) {
        PacketRecorder.Session session = scene.session();
        Reading reading = scene.reading();
        List<String> findings;
        List<String> unread;
        List<BlockPos> missing;
        List<String> texts;
        List<String> graveLines;
        int scanned;
        synchronized (reading) {
            findings = List.copyOf(reading.scan.findings());
            unread = List.copyOf(reading.scan.unreadThatFails());
            missing = List.copyOf(reading.scan.missing());
            texts = List.copyOf(reading.texts);
            graveLines = List.copyOf(reading.graveLines);
            scanned = reading.scanned;
        }
        if (session.connections() != 1 || !session.problems().isEmpty()) {
            throw new AssertionError("the recording didn't hook the client's one connection whole: " + session.summary());
        }
        if (!findings.isEmpty()) {
            throw new AssertionError(findings.size() + " places in a band in what the client got, of " + scanned + " packets: "
                    + findings.subList(0, Math.min(findings.size(), 20)));
        }
        if (!texts.isEmpty()) {
            throw new AssertionError("coordinates in the texts the client got: " + texts);
        }
        if (!unread.isEmpty()) {
            throw new AssertionError("parts of what the client got the scanner couldn't read: " + unread);
        }
        if (!missing.isEmpty()) {
            throw new AssertionError("the client was never sent these shifted places, so the scan can't be trusted: " + missing
                    + "; packet types " + session.types());
        }
        // The control for the rest of the scene: each of these was sent, so a clean scan read them
        List<String> absent = SCENE_PACKETS.stream()
                .filter(type -> session.types().keySet().stream().noneMatch(seen -> seen.endsWith(":" + type))).toList();
        if (!absent.isEmpty()) {
            throw new AssertionError("the client was never sent " + absent + ", so the scan read less than the scene; it got "
                    + session.types().keySet());
        }
        if (graveLines.isEmpty()) {
            throw new AssertionError("the client never got the grave line, so the text check read nothing worth trusting");
        }
    }

    private static void checkOffset(TestDedicatedServerContext server, TestDedicatedServerConnection connection, String when) {
        Offset offset = server.computeOnServer(s -> CoordinateOffsets.offset(connection.getServerPlayer()));
        if (!offset.equals(OFFSET)) {
            throw new AssertionError("the frame after " + when + " has the offset " + offset + ", not " + OFFSET
                    + ", so the bands are in the wrong place");
        }
    }

    // Everything the server sweep's world and item scenes send, around the client
    private static void build(MinecraftServer s, ServerPlayer player, BlockPos spot) {
        ServerLevel level = s.overworld();
        BlockPos target = spot.offset(TARGET_DX, 0, TARGET_DZ);
        level.setBlockAndUpdate(gold(spot), Blocks.GOLD_BLOCK.defaultBlockState());

        // A sign whose text shows a lodestone compass on hover: the compass's target is a place in the sign's data
        level.setBlockAndUpdate(sign(spot), Blocks.OAK_SIGN.defaultBlockState());
        SignBlockEntity sign = (SignBlockEntity) level.getBlockEntity(sign(spot));
        Component line = Component.literal("look").withStyle(style -> style.withHoverEvent(
                new HoverEvent.ShowItem(ItemStackTemplate.fromNonEmptyStack(compass(target)))));
        List<Component> lines = List.of(line, Component.empty(), Component.empty(), Component.empty());
        sign.setText(new SignText(lines, lines, DyeColor.BLACK, false), SignTextSlot.FRONT);
        resend(level, sign(spot));

        level.setBlockAndUpdate(campfire(spot), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        ((CampfireBlockEntity) level.getBlockEntity(campfire(spot))).getItems().set(0, compass(target));
        resend(level, campfire(spot));
        BlockPos gateway = spot.offset(-3, 0, -3);
        level.setBlockAndUpdate(gateway, Blocks.END_GATEWAY.defaultBlockState());
        ((TheEndGatewayBlockEntity) level.getBlockEntity(gateway)).setExitPosition(target, false);
        resend(level, gateway);

        Mob pig = EntityTypes.PIG.spawn(level, spot.offset(4, 0, 2), EntitySpawnReason.TRIGGERED);
        if (pig == null) {
            throw new AssertionError("no pig was spawned");
        }
        pig.setNoAi(true);
        BlockPos wall = spot.offset(-4, 0, 2);
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        ItemFrame frame = new ItemFrame(level, wall.south(), Direction.SOUTH);
        frame.setItem(compass(target), false);
        level.addFreshEntity(frame);
        EndCrystal crystal = EntityTypes.END_CRYSTAL.create(level, EntitySpawnReason.TRIGGERED);
        if (crystal == null) {
            throw new AssertionError("no end crystal was made");
        }
        crystal.snapTo(Vec3.atBottomCenterOf(spot.offset(-5, 0, -1)));
        crystal.setBeamTarget(target);
        crystal.setShowBottom(false);
        level.addFreshEntity(crystal);
        ItemEntity dropped = new ItemEntity(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 4.5, compass(target));
        dropped.setNeverPickUp();
        level.addFreshEntity(dropped);
        AreaEffectCloud cloud = new AreaEffectCloud(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 6.5);
        cloud.setCustomParticle(new TrailParticleOption(Vec3.atCenterOf(target), 0x00FF00, 40));
        level.addFreshEntity(cloud);

        level.playSound(null, spot.getX() + 1.0, spot.getY() + 1.0, spot.getZ() + 1.0, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.sendParticles(new VibrationParticleOption(new BlockPositionSource(spot.offset(10, 0, 10)), 20), spot.getX() + 0.5,
                spot.getY() + 2.0, spot.getZ() + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
        level.levelEvent(null, LevelEvent.PARTICLES_DESTROY_BLOCK, spot.offset(2, 0, 2), Block.getId(Blocks.STONE.defaultBlockState()));
        level.destroyBlockProgress(4_242, gold(spot), 5);
        // Too weak and far to hurt the client or break the scene, but it sends its centre
        level.explode(null, spot.getX() + 8.5, spot.getY(), spot.getZ() - 8.5, 0.5F, Level.ExplosionInteraction.NONE);

        player.getInventory().setItem(9, compass(target));
        player.getInventory().setItem(10, nestedCompass(target));
        player.inventoryMenu.sendAllDataToRemote();
    }

    // A death respawns the client at the world spawn, the spot, in a new frame anchored there, and the grave line goes out
    // in it. The lockout is off for it, so the death doesn't kick the client.
    private static void dieAndRespawn(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                      TestDedicatedServerConnection connection) {
        LocalPlayer before = ctx.computeOnClient(mc -> mc.player);
        int mark = ChatStoryClientTest.chatLogEnd(ctx);
        boolean lockoutWas = server.computeOnServer(s -> Settings.get().deathLockout.enabled);
        YoltConfig.TellLocation tellWas = server.computeOnServer(s -> Settings.get().graves.tellLocation);
        try {
            server.runOnServer(s -> {
                Settings.get().deathLockout.enabled = false;
                Settings.get().graves.tellLocation = YoltConfig.TellLocation.ALWAYS;
                ServerPlayer player = connection.getServerPlayer();
                player.kill(player.level());
            });
            // Immediate respawn is on, so the client respawns by itself
            ctx.waitFor(mc -> mc.player != before && mc.gui.screen() == null, TestServerConnection.DEFAULT_CHUNK_LOAD_TIMEOUT);
            ClientTests.waitOnClient(ctx, ClientGameTestContext.DEFAULT_TIMEOUT, mc -> ChatStoryClientTest.chatLogHas(mc, mark, GRAVE_LINE),
                    mc -> "the client never got the grave line after its respawn");
            checkOffset(server, connection, "the respawn");
        } finally {
            server.runOnServer(s -> {
                Settings.get().deathLockout.enabled = lockoutWas;
                Settings.get().graves.tellLocation = tellWas;
            });
        }
    }

    // A dimension change starts a frame at the arrival, on a platform made there with room above it
    private static void travel(ClientGameTestContext ctx, TestDedicatedServerContext server, TestDedicatedServerConnection connection,
                               ResourceKey<Level> dimension, BlockPos at) {
        server.runOnServer(s -> {
            ServerLevel level = s.getLevel(dimension);
            if (level == null) {
                throw new AssertionError("no " + dimension.identifier() + " to go to");
            }
            for (BlockPos pos : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 2, 1))) {
                level.setBlockAndUpdate(pos, pos.getY() < at.getY() ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
            ServerPlayer player = connection.getServerPlayer();
            player.teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
        });
        ctx.waitFor(mc -> mc.level != null && mc.level.dimension() == dimension && mc.gui.screen() == null,
                TestServerConnection.DEFAULT_CHUNK_LOAD_TIMEOUT);
        checkOffset(server, connection, "the trip to " + dimension.identifier());
    }

    private static BlockPos gold(BlockPos spot) {
        return spot.offset(2, 0, 0);
    }

    private static BlockPos sign(BlockPos spot) {
        return spot.offset(-2, 0, 0);
    }

    private static BlockPos campfire(BlockPos spot) {
        return spot.offset(0, 0, -2);
    }

    // A block entity changed after its block was placed goes to the clients again with its next update
    private static void resend(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
    }

    private static ItemStack compass(BlockPos target) {
        ItemStack compass = new ItemStack(Items.COMPASS);
        compass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(GlobalPos.of(Level.OVERWORLD, target)), true));
        return compass;
    }

    // A compass in a shulker box in a bundle
    private static ItemStack nestedCompass(BlockPos target) {
        NonNullList<ItemStack> slots = NonNullList.withSize(27, ItemStack.EMPTY);
        slots.set(3, compass(target));
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots));
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(ItemStackTemplate.fromNonEmptyStack(box))));
        return bundle;
    }

    // The block above the ground in that column, where a player stands
    private static BlockPos standingSpot(ServerLevel level, int x, int z) {
        BlockPos spot = RandomSpawn.spotAt(level, x, z);
        if (spot == null) {
            throw new AssertionError("nowhere to stand at " + x + ", " + z);
        }
        return spot;
    }
}
