package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.visibility.AdminView;
import bobbynooby.dev.visibility.PlayerVisibility;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSigningContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import net.minecraft.util.TaskChainer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.player.ProfilePublicKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CreakingHeartBlockEntity;
import org.jspecify.annotations.Nullable;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

// Other tests share the server, so these check who is in a set, never the whole set.
// Test players have no client, so the waiting tests drive chunk tracking themselves (TestPlayers.driveTracking).
public class VisibilityGameTests {
    @GameTest
    public void joinerDoesNotKnowFarPlayers(GameTestHelper helper) {
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            hide.distantPlayers = true;
            // Online before the joiner, so vanilla's join packet would carry it
            ServerPlayer far = join(helper, joined, "visfar", new BlockPos(1, 0, 0));
            far.teleportTo(far.getX() + 1000, far.getY(), far.getZ());
            ServerPlayer p = join(helper, joined, "visjoin", BlockPos.ZERO);

            helper.assertTrue(PlayerVisibility.known(p).contains(p.getUUID()), "joiner does not know itself");
            helper.assertFalse(PlayerVisibility.known(p).contains(far.getUUID()), "joiner knows a far player");
            helper.assertFalse(PlayerVisibility.known(far).contains(p.getUUID()), "far player knows the joiner");
            helper.assertTrue(addedEntry(p, far) == null, "joiner's client got the far player's entry");
            helper.assertTrue(addedEntry(far, p) == null, "far player's client got the joiner's entry");

            ClientboundPlayerInfoUpdatePacket.Entry self = addedEntry(p, p);
            helper.assertTrue(self != null, "joiner's client did not get its own entry");
            helper.assertFalse(self.listed(), "own entry is listed while the tab list is hidden");
        } finally {
            hide.distantPlayers = distantBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void nearPlayersBecomeKnown(GameTestHelper helper) {
        ServerPlayer a = TestPlayers.join(helper, "visneara", GameType.SURVIVAL, BlockPos.ZERO);
        ServerPlayer b = TestPlayers.join(helper, "visnearb", GameType.SURVIVAL, new BlockPos(5, 0, 0));
        boolean waiting = false;
        try {
            // A real client's entry carries its chat session; without it the other client can't verify its signed chat
            UUID session = giveChatSession(b);
            helper.onEachTick(() -> {
                TestPlayers.driveTracking(a);
                TestPlayers.driveTracking(b);
            });
            helper.runAfterDelay(5, () -> {
                try {
                    helper.assertTrue(PlayerVisibility.known(a).contains(b.getUUID()), "a does not know b");
                    helper.assertTrue(PlayerVisibility.known(b).contains(a.getUUID()), "b does not know a");
                    assertEntryBeforeSpawn(helper, a, b);
                    assertEntryBeforeSpawn(helper, b, a);

                    ClientboundPlayerInfoUpdatePacket.Entry entry = addedEntry(a, b);
                    helper.assertTrue(entry.chatSession() != null && entry.chatSession().sessionId().equals(session),
                            "b's entry is missing its chat session");
                    helper.assertFalse(entry.listed(), "b's entry is listed while the tab list is hidden");
                } finally {
                    TestPlayers.leave(a);
                    TestPlayers.leave(b);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                TestPlayers.leave(a);
                TestPlayers.leave(b);
            }
        }
    }

    @GameTest(maxTicks = 60)
    public void farPlayersAreForgotten(GameTestHelper helper) {
        ServerPlayer a = TestPlayers.join(helper, "visgonea", GameType.SURVIVAL, BlockPos.ZERO);
        ServerPlayer b = TestPlayers.join(helper, "visgoneb", GameType.SURVIVAL, new BlockPos(5, 0, 0));
        boolean waiting = false;
        try {
            // Leaving b undriven after the jump keeps the server from loading chunks out there
            AtomicBoolean jumped = new AtomicBoolean();
            helper.onEachTick(() -> {
                TestPlayers.driveTracking(a);
                if (!jumped.get()) {
                    TestPlayers.driveTracking(b);
                }
            });
            helper.runAfterDelay(5, () -> {
                jumped.set(true);
                b.teleportTo(b.getX() + 1000, b.getY(), b.getZ());
            });
            helper.runAfterDelay(45, () -> {
                try {
                    // The entries arriving show they did track each other before the jump
                    helper.assertTrue(addedEntry(a, b) != null, "a never got b's entry");
                    helper.assertTrue(addedEntry(b, a) != null, "b never got a's entry");
                    helper.assertFalse(PlayerVisibility.known(a).contains(b.getUUID()), "a still knows b");
                    helper.assertFalse(PlayerVisibility.known(b).contains(a.getUUID()), "b still knows a");
                    helper.assertTrue(gotRemove(a, b), "a's client was not told to drop b");
                    helper.assertTrue(gotRemove(b, a), "b's client was not told to drop a");
                } finally {
                    TestPlayers.leave(a);
                    TestPlayers.leave(b);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                TestPlayers.leave(a);
                TestPlayers.leave(b);
            }
        }
    }

    @GameTest
    public void adminKnowsEveryone(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        boolean tabListBefore = hide.tabList;
        List<ServerPlayer> joined = new ArrayList<>();
        ServerPlayer admin = null;
        try {
            hide.distantPlayers = true;
            hide.tabList = true;
            admin = join(helper, joined, "visadmin", BlockPos.ZERO);
            ServerPlayer far = join(helper, joined, "visadminfar", new BlockPos(1, 0, 0));
            far.teleportTo(far.getX() + 1000, far.getY(), far.getZ());
            helper.assertFalse(PlayerVisibility.known(admin).contains(far.getUUID()), "far player known before admin view");

            TestPlayers.op(admin);
            AdminView.set(admin, true);
            PlayerVisibility.refresh(admin);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                helper.assertTrue(PlayerVisibility.known(admin).contains(player.getUUID()),
                        "admin view does not know " + player.getPlainTextName());
            }
            ClientboundPlayerInfoUpdatePacket.Entry farEntry = addedEntry(admin, far);
            helper.assertTrue(farEntry != null && farEntry.listed(), "admin view did not get the far player listed");
            ServerPlayer late = join(helper, joined, "visadminlate", new BlockPos(2, 0, 0));
            helper.assertTrue(PlayerVisibility.known(admin).contains(late.getUUID()), "admin view misses a later joiner");

            AdminView.set(admin, false);
            PlayerVisibility.refresh(admin);
            helper.assertFalse(PlayerVisibility.known(admin).contains(far.getUUID()), "far player still known after admin view");
            helper.assertTrue(PlayerVisibility.known(admin).contains(admin.getUUID()), "admin no longer knows itself");
            helper.assertTrue(gotRemove(admin, far), "admin's client was not told to drop the far player");
        } finally {
            hide.distantPlayers = distantBefore;
            hide.tabList = tabListBefore;
            if (admin != null) {
                AdminView.set(admin, false);
                TestPlayers.deop(admin);
            }
            leaveAll(joined);
        }
        helper.succeed();
    }

    @GameTest
    public void switchOffSendsEveryone(GameTestHelper helper) {
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        boolean tabListBefore = hide.tabList;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            hide.distantPlayers = false;
            hide.tabList = true;
            ServerPlayer far = join(helper, joined, "visofffar", new BlockPos(1, 0, 0));
            far.teleportTo(far.getX() + 1000, far.getY(), far.getZ());
            ServerPlayer p = join(helper, joined, "visoff", BlockPos.ZERO);

            helper.assertTrue(PlayerVisibility.known(p).contains(far.getUUID()), "switch off: joiner does not know the far player");
            helper.assertTrue(PlayerVisibility.known(far).contains(p.getUUID()), "switch off: far player does not know the joiner");
            ClientboundPlayerInfoUpdatePacket.Entry entry = addedEntry(p, far);
            helper.assertTrue(entry != null && !entry.listed(), "switch off: far player's entry is missing or listed with the tab list hidden");
        } finally {
            hide.distantPlayers = distantBefore;
            hide.tabList = tabListBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    @GameTest
    public void unknownSenderChatArrivesAsText(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.ChatSection chat = Settings.get().chat;
        YoltConfig.HideSection hide = Settings.get().hide;
        YoltConfig.ChatMode modeBefore = chat.mode;
        boolean distantBefore = hide.distantPlayers;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            chat.mode = YoltConfig.ChatMode.GLOBAL;
            hide.distantPlayers = true;
            TestPlayers.Recording sender = TestPlayers.joinRecording(helper, "vissay", GameType.SURVIVAL, BlockPos.ZERO);
            joined.add(sender);
            TestPlayers.Recording far = TestPlayers.joinRecording(helper, "vishear", GameType.SURVIVAL, new BlockPos(1, 0, 0));
            joined.add(far);
            far.teleportTo(far.getX() + 1000, far.getY(), far.getZ());
            helper.assertFalse(PlayerVisibility.known(far).contains(sender.getUUID()), "far player knows the sender");

            server.getPlayerList().broadcastChatMessage(PlayerChatMessage.unsigned(sender.getUUID(), "hello there"),
                    sender, ChatType.bind(ChatType.CHAT, sender));

            String name = sender.getDisplayName().getString();
            helper.assertTrue(far.messages.stream().anyMatch(m -> isChatLine(m, "chat.type.text", name, "hello there")), "far player did not get the chat as text");
            helper.assertFalse(chatFrom(far, sender), "far player got player chat from a sender it doesn't know");
            // The sender always knows itself, so it takes the normal signed path
            helper.assertTrue(chatFrom(sender, sender), "sender did not get its own chat as player chat");
            helper.assertFalse(sender.messages.stream().anyMatch(m -> isChatLine(m, "chat.type.text", name, "hello there")), "sender got its own chat as text");
        } finally {
            chat.mode = modeBefore;
            hide.distantPlayers = distantBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    // Vanilla's /msg sends to the target directly, outside ChatDelivery
    @GameTest
    public void opMsgToUnknownRecipientFallsBack(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        List<ServerPlayer> joined = new ArrayList<>();
        TestPlayers.Recording op = null;
        try {
            hide.distantPlayers = true;
            op = TestPlayers.joinRecording(helper, "visop", GameType.SURVIVAL, BlockPos.ZERO);
            joined.add(op);
            TestPlayers.Recording far = TestPlayers.joinRecording(helper, "visopfar", GameType.SURVIVAL, new BlockPos(1, 0, 0));
            joined.add(far);
            far.teleportTo(far.getX() + 1000, far.getY(), far.getZ());
            helper.assertFalse(PlayerVisibility.known(far).contains(op.getUUID()), "far player knows the op");
            // A gamemaster op, so restrictCommands lets vanilla /msg run; the test server's plain op is level 0
            TestPlayers.op(op);

            // A real client signs the message argument; this puts the command on that same signed path
            PlayerChatMessage signed = PlayerChatMessage.unsigned(op.getUUID(), "psst");
            CommandSourceStack source = op.createCommandSourceStack().withSigningContext(
                    new CommandSigningContext.SignedArguments(Map.of("message", signed)), TaskChainer.immediate(Runnable::run));
            server.getCommands().performPrefixedCommand(source, "msg " + far.getGameProfile().name() + " psst");

            String name = op.getDisplayName().getString();
            helper.assertTrue(far.messages.stream().anyMatch(m -> isChatLine(m, "commands.message.display.incoming", name, "psst")),
                    "far player did not get the message as text");
            helper.assertFalse(chatFrom(far, op), "far player got player chat from an op it doesn't know");
            // The op's own copy takes the normal signed path
            helper.assertTrue(chatFrom(op, op), "op did not get its own message as player chat");
        } finally {
            hide.distantPlayers = distantBefore;
            if (op != null) {
                server.getPlayerList().deop(op.nameAndId());
            }
            leaveAll(joined);
        }
        helper.succeed();
    }

    @GameTest
    public void adminSeesListedWithSwitchOff(GameTestHelper helper) {
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        boolean tabListBefore = hide.tabList;
        List<ServerPlayer> joined = new ArrayList<>();
        ServerPlayer admin = null;
        try {
            hide.distantPlayers = false;
            hide.tabList = true;
            admin = join(helper, joined, "visoffadmin", BlockPos.ZERO);
            ServerPlayer other = join(helper, joined, "visoffother", new BlockPos(1, 0, 0));
            helper.assertFalse(listedFor(admin, other), "other is listed before admin view");

            TestPlayers.op(admin);
            AdminView.set(admin, true);
            PlayerVisibility.refresh(admin);
            helper.assertTrue(listedFor(admin, other), "admin view did not list a known player with the switch off");
            ServerPlayer late = join(helper, joined, "visofflate", new BlockPos(2, 0, 0));
            helper.assertTrue(listedFor(admin, late), "admin view did not list a later joiner with the switch off");
            helper.assertFalse(listedFor(other, late), "a normal player got the later joiner listed");
        } finally {
            hide.distantPlayers = distantBefore;
            hide.tabList = tabListBefore;
            if (admin != null) {
                AdminView.set(admin, false);
                TestPlayers.deop(admin);
            }
            leaveAll(joined);
        }
        helper.succeed();
    }

    // Plan 04's bodies are players outside the player list, and pairing with them runs the same way.
    // A player who has left stands in for one, with the pairing calls made directly.
    @GameTest
    public void unlistedPlayerIsDroppedEvenForAdmins(GameTestHelper helper) {
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        boolean tabListBefore = hide.tabList;
        List<ServerPlayer> joined = new ArrayList<>();
        ServerPlayer admin = null;
        try {
            hide.distantPlayers = true;
            hide.tabList = true;
            admin = join(helper, joined, "visbodyadmin", BlockPos.ZERO);
            ServerPlayer body = TestPlayers.join(helper, "visbody", GameType.SURVIVAL, new BlockPos(1, 0, 0));
            TestPlayers.leave(body);
            TestPlayers.op(admin);
            AdminView.set(admin, true);

            PlayerVisibility.onPairingStart(admin, body);
            helper.assertTrue(PlayerVisibility.known(admin).contains(body.getUUID()), "admin did not get the body's entry");
            ClientboundPlayerInfoUpdatePacket.Entry entry = addedEntry(admin, body);
            helper.assertTrue(entry != null && !entry.listed(), "the body's entry is missing or listed");

            PlayerVisibility.onPairingStop(admin, body);
            helper.assertFalse(PlayerVisibility.known(admin).contains(body.getUUID()), "admin view kept the body's entry");
            helper.assertTrue(gotRemove(admin, body), "admin's client was not told to drop the body");
        } finally {
            hide.distantPlayers = distantBefore;
            hide.tabList = tabListBefore;
            if (admin != null) {
                AdminView.set(admin, false);
                TestPlayers.deop(admin);
            }
            leaveAll(joined);
        }
        helper.succeed();
    }

    // Hitting a creaking makes its heart draw a trail to it, which vanilla forces out to 512 blocks with exact positions
    @GameTest
    public void creakingTrailIsNotForcedFar(GameTestHelper helper) {
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        List<ServerPlayer> joined = new ArrayList<>();
        BlockPos heartPos = new BlockPos(1, 1, 1);
        Creaking creaking = null;
        try {
            hide.distantPlayers = true;
            ServerPlayer near = join(helper, joined, "visheartnear", new BlockPos(3, 1, 1));
            ServerPlayer far = join(helper, joined, "visheartfar", new BlockPos(3, 1, 1));
            far.teleportTo(far.getX() + 100, far.getY(), far.getZ());
            helper.setBlock(heartPos, Blocks.CREAKING_HEART);
            CreakingHeartBlockEntity heart = helper.getBlockEntity(heartPos, CreakingHeartBlockEntity.class);
            creaking = helper.spawn(EntityTypes.CREAKING, new BlockPos(1, 1, 3));
            heart.setCreakingInfo(creaking);
            int nearMark = TestPlayers.sentPackets(near).size();
            int farMark = TestPlayers.sentPackets(far).size();

            heart.creakingHurt();
            List<ClientboundLevelParticlesPacket> nearTrail = trail(near, nearMark);
            helper.assertFalse(nearTrail.isEmpty(), "no trail sent to a player next to the heart");
            helper.assertFalse(nearTrail.getFirst().overrideLimiter(), "the trail is still forced out to 512 blocks");
            helper.assertTrue(trail(far, farMark).isEmpty(), "trail sent to a player 100 blocks away");
        } finally {
            hide.distantPlayers = distantBefore;
            // Gone first, so removing the heart doesn't tear it down with a burst of particles
            if (creaking != null) {
                creaking.discard();
            }
            helper.setBlock(heartPos, Blocks.AIR);
            leaveAll(joined);
        }
        helper.succeed();
    }

    // The trail particles this player's client was sent, from its packet number from on
    private static List<ClientboundLevelParticlesPacket> trail(ServerPlayer player, int from) {
        List<Packet<?>> packets = TestPlayers.sentPackets(player);
        List<ClientboundLevelParticlesPacket> trail = new ArrayList<>();
        for (Packet<?> packet : packets.subList(from, packets.size())) {
            if (packet instanceof ClientboundLevelParticlesPacket particles && particles.particle() instanceof TrailParticleOption) {
                trail.add(particles);
            }
        }
        return trail;
    }

    // The last listed flag this viewer's client was sent for seen
    static boolean listedFor(ServerPlayer viewer, ServerPlayer seen) {
        boolean listed = false;
        for (Packet<?> packet : TestPlayers.sentPackets(viewer)) {
            if (packet instanceof ClientboundPlayerInfoUpdatePacket info
                    && info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED)) {
                for (ClientboundPlayerInfoUpdatePacket.Entry entry : info.entries()) {
                    if (entry.profileId().equals(seen.getUUID())) {
                        listed = entry.listed();
                    }
                }
            }
        }
        return listed;
    }

    // The client drops a player spawn it has no entry for, so the entry has to come first
    private static void assertEntryBeforeSpawn(GameTestHelper helper, ServerPlayer viewer, ServerPlayer seen) {
        List<Packet<?>> packets = TestPlayers.sentPackets(viewer);
        int entry = -1;
        int spawn = -1;
        for (int i = 0; i < packets.size(); i++) {
            if (entry < 0 && addedEntry(packets.get(i), seen) != null) {
                entry = i;
            }
            if (spawn < 0 && spawns(packets.get(i), seen)) {
                spawn = i;
            }
        }
        String who = viewer.getPlainTextName() + " seeing " + seen.getPlainTextName();
        helper.assertTrue(entry >= 0, who + ": no entry sent");
        helper.assertTrue(spawn >= 0, who + ": no spawn sent");
        helper.assertTrue(entry < spawn, who + ": spawn sent before the entry");
    }

    // The first entry for seen that viewer's client was told to add, in vanilla's join packet layout
    private static ClientboundPlayerInfoUpdatePacket.@Nullable Entry addedEntry(ServerPlayer viewer, ServerPlayer seen) {
        for (Packet<?> packet : TestPlayers.sentPackets(viewer)) {
            ClientboundPlayerInfoUpdatePacket.Entry entry = addedEntry(packet, seen);
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    static ClientboundPlayerInfoUpdatePacket.@Nullable Entry addedEntry(Packet<?> packet, ServerPlayer seen) {
        if (packet instanceof ClientboundPlayerInfoUpdatePacket info
                && info.actions().equals(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of()).actions())) {
            for (ClientboundPlayerInfoUpdatePacket.Entry entry : info.entries()) {
                if (entry.profileId().equals(seen.getUUID())) {
                    return entry;
                }
            }
        }
        return null;
    }

    static boolean spawns(Packet<?> packet, ServerPlayer seen) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> part : bundle.subPackets()) {
                if (part instanceof ClientboundAddEntityPacket add && add.getUUID().equals(seen.getUUID())) {
                    return true;
                }
            }
        }
        return packet instanceof ClientboundAddEntityPacket add && add.getUUID().equals(seen.getUUID());
    }

    private static boolean gotRemove(ServerPlayer viewer, ServerPlayer seen) {
        return TestPlayers.sentPackets(viewer).stream()
                .anyMatch(p -> p instanceof ClientboundPlayerInfoRemovePacket remove && remove.profileIds().contains(seen.getUUID()));
    }

    private static boolean chatFrom(ServerPlayer viewer, ServerPlayer sender) {
        return TestPlayers.sentPackets(viewer).stream()
                .anyMatch(p -> p instanceof ClientboundPlayerChatPacket chat && chat.sender().equals(sender.getUUID()));
    }

    private static boolean isChatLine(Component message, String key, String name, String text) {
        return message.getContents() instanceof TranslatableContents line
                && line.getKey().equals(key)
                && line.getArgs().length == 2
                && line.getArgs()[0] instanceof Component sender && sender.getString().equals(name)
                && line.getArgs()[1] instanceof Component content && content.getString().equals(text);
    }

    // Test players have no chat session; any unexpired key is enough for it to be sent along
    private static UUID giveChatSession(ServerPlayer player) {
        try {
            KeyPair keys = Crypt.generateKeyPair();
            ProfilePublicKey key = new ProfilePublicKey(
                    new ProfilePublicKey.Data(Instant.now().plus(Duration.ofHours(1)), keys.getPublic(), new byte[0]));
            UUID id = UUID.randomUUID();
            player.setChatSession(new RemoteChatSession(id, key));
            return id;
        } catch (CryptException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ServerPlayer join(GameTestHelper helper, List<ServerPlayer> joined, String name, BlockPos pos) {
        ServerPlayer player = TestPlayers.join(helper, name, GameType.SURVIVAL, pos);
        joined.add(player);
        return player;
    }

    private static void leaveAll(List<ServerPlayer> joined) {
        for (ServerPlayer player : joined) {
            TestPlayers.leave(player);
        }
    }
}
