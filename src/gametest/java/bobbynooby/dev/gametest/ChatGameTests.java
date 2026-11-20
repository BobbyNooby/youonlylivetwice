package bobbynooby.dev.gametest;

import bobbynooby.dev.chat.ChatDelivery;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.visibility.AdminView;
import bobbynooby.dev.visibility.PlayerVisibility;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FilterMask;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

// Other tests share the server, so these check who is in a set, never the whole set.
// Switches are restored in a finally block in the same tick.
public class ChatGameTests {
    // Proximity chat with distant players hidden, held for a batch whose test runs over several ticks
    private static final String CHAT_SPECTATOR = "you-only-live-twice-gametest:chat_spectator";

    @GameTest
    public void proximityRecipientsUseLivePositions(GameTestHelper helper) {
        YoltConfig.ChatSection chat = Settings.get().chat;
        YoltConfig.ChatMode modeBefore = chat.mode;
        int radiusBefore = chat.radius;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            chat.mode = YoltConfig.ChatMode.PROXIMITY;
            chat.radius = 48;
            ServerPlayer a = join(helper, joined, "chata", BlockPos.ZERO);
            ServerPlayer b = join(helper, joined, "chatb", new BlockPos(10, 0, 0));
            ServerPlayer c = join(helper, joined, "chatc", new BlockPos(5, 0, 0));
            helper.assertTrue(ChatDelivery.chatRecipients(a).contains(c), "c not heard before moving away");

            c.teleportTo(c.getX() + 200, c.getY(), c.getZ());
            Set<ServerPlayer> heard = ChatDelivery.chatRecipients(a);
            helper.assertTrue(heard.contains(a), "sender not in recipients");
            helper.assertTrue(heard.contains(b), "player 10 blocks away not in recipients");
            helper.assertTrue(!heard.contains(c), "player 200 blocks away still in recipients");
        } finally {
            chat.mode = modeBefore;
            chat.radius = radiusBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    @GameTest
    public void globalModeIsVanilla(GameTestHelper helper) {
        YoltConfig.ChatSection chat = Settings.get().chat;
        YoltConfig.ChatMode modeBefore = chat.mode;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            chat.mode = YoltConfig.ChatMode.GLOBAL;
            ServerPlayer a = join(helper, joined, "globala", BlockPos.ZERO);
            ServerPlayer b = join(helper, joined, "globalb", new BlockPos(10, 0, 0));
            ServerPlayer c = join(helper, joined, "globalc", BlockPos.ZERO);
            c.teleportTo(c.getX() + 200, c.getY(), c.getZ());

            Set<ServerPlayer> heard = ChatDelivery.chatRecipients(a);
            helper.assertTrue(heard.contains(a), "sender not in recipients");
            helper.assertTrue(heard.contains(b), "near player not in recipients");
            helper.assertTrue(heard.contains(c), "far player not in recipients in global mode");
        } finally {
            chat.mode = modeBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    @GameTest
    public void hiddenGameMessageGoesToAdminsOnly(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean hiddenBefore = hide.gameMessages;
        List<ServerPlayer> joined = new ArrayList<>();
        ServerPlayer admin = null;
        try {
            admin = join(helper, joined, "msgadmin", BlockPos.ZERO);
            ServerPlayer other = join(helper, joined, "msgother", new BlockPos(1, 0, 0));
            TestPlayers.op(admin);
            AdminView.set(admin, true);

            hide.gameMessages = true;
            Set<ServerPlayer> hidden = ChatDelivery.gameMessageRecipients(server);
            helper.assertTrue(hidden.contains(admin), "admin view player misses hidden game messages");
            helper.assertTrue(!hidden.contains(other), "normal player gets hidden game messages");

            hide.gameMessages = false;
            Set<ServerPlayer> shown = ChatDelivery.gameMessageRecipients(server);
            helper.assertTrue(shown.contains(admin), "admin misses game messages with hiding off");
            helper.assertTrue(shown.contains(other), "normal player misses game messages with hiding off");
        } finally {
            hide.gameMessages = hiddenBefore;
            if (admin != null) {
                AdminView.set(admin, false);
                TestPlayers.deop(admin);
            }
            leaveAll(joined);
        }
        helper.succeed();
    }

    // A real death with game messages hidden: its death message reaches no player near or far, only the admin in admin
    // view, which shows it was sent at all. The dying player leaves before the death's kick.
    @GameTest
    public void deathMessageReachesNoNormalPlayer(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean hiddenBefore = hide.gameMessages;
        List<ServerPlayer> joined = new ArrayList<>();
        ServerPlayer dying = join(helper, joined, "deathmsg", BlockPos.ZERO);
        TestPlayers.Recording admin = null;
        try {
            hide.gameMessages = true;
            TestPlayers.Recording near = joinRecording(helper, joined, "deathnear", new BlockPos(2, 0, 0));
            TestPlayers.Recording far = joinRecording(helper, joined, "deathfar", BlockPos.ZERO);
            far.teleportTo(far.getX() + 200, far.getY(), far.getZ());
            admin = joinRecording(helper, joined, "deathadmin", new BlockPos(1, 0, 0));
            TestPlayers.op(admin);
            AdminView.set(admin, true);
            String name = dying.getGameProfile().name();

            TestPlayers.markLoaded(dying);
            dying.kill(dying.level());
            helper.assertTrue(dying.isDeadOrDying(), "the player did not die");
            helper.assertValueEqual(deathLines(admin, name), 1, "death messages the admin in admin view got");
            helper.assertValueEqual(deathLines(near, name), 0, "death messages the near player got");
            helper.assertValueEqual(deathLines(far, name), 0, "death messages the far player got");
        } finally {
            hide.gameMessages = hiddenBefore;
            if (admin != null) {
                AdminView.set(admin, false);
                TestPlayers.deop(admin);
            }
            leaveAll(joined);
            BodyTestKit.removeGraves(helper, dying.getUUID());
        }
        helper.succeed();
    }

    // Vanilla sends a pet's death message straight to its owner, wherever they are, and it names the killer
    @GameTest
    public void petDeathMessageDoesNotNameTheKillerToTheOwner(GameTestHelper helper) {
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean hiddenBefore = hide.gameMessages;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            TestPlayers.Recording owner = joinRecording(helper, joined, "petowner", BlockPos.ZERO);
            owner.teleportTo(owner.getX() + 200, owner.getY(), owner.getZ());
            ServerPlayer killer = join(helper, joined, "petkiller", new BlockPos(1, 0, 0));
            String killerName = killer.getGameProfile().name();

            // The control: with game messages shown, the owner is told, so the kill below does send the line
            hide.gameMessages = false;
            killPet(helper, owner, killer);
            helper.assertValueEqual(linesNaming(owner, killerName), 1, "pet death messages the owner got with game messages shown");

            owner.messages.clear();
            hide.gameMessages = true;
            killPet(helper, owner, killer);
            helper.assertValueEqual(linesNaming(owner, killerName), 0, "pet death messages the owner got with game messages hidden");

            owner.messages.clear();
            TestPlayers.op(owner);
            AdminView.set(owner, true);
            killPet(helper, owner, killer);
            helper.assertValueEqual(linesNaming(owner, killerName), 1, "pet death messages an owner in admin view got");
            AdminView.set(owner, false);
            TestPlayers.deop(owner);
        } finally {
            hide.gameMessages = hiddenBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    private static void killPet(GameTestHelper helper, ServerPlayer owner, ServerPlayer killer) {
        Wolf wolf = EntityTypes.WOLF.spawn(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)), EntitySpawnReason.TRIGGERED);
        helper.assertTrue(wolf != null, "no wolf was spawned");
        wolf.tame(owner);
        wolf.hurtServer(helper.getLevel(), helper.getLevel().damageSources().playerAttack(killer), 1_000.0F);
        helper.assertTrue(wolf.isDeadOrDying(), "the wolf did not die");
    }

    private static int linesNaming(TestPlayers.Recording player, String name) {
        return (int) player.messages.stream().filter(message -> message.getString().contains(name)).count();
    }

    // With restrictCommands off anyone may /me, and only a gamemaster's is an announcement
    @GameTest
    public void nonOpEmoteIsChat(GameTestHelper helper) {
        YoltConfig.ChatSection chat = Settings.get().chat;
        YoltConfig.HideSection hide = Settings.get().hide;
        YoltConfig.ChatMode modeBefore = chat.mode;
        int radiusBefore = chat.radius;
        boolean distantBefore = hide.distantPlayers;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            chat.mode = YoltConfig.ChatMode.PROXIMITY;
            chat.radius = 48;
            TestPlayers.Recording sender = joinRecording(helper, joined, "emotefrom", BlockPos.ZERO);
            TestPlayers.Recording near = joinRecording(helper, joined, "emotenear", new BlockPos(10, 0, 0));
            TestPlayers.Recording far = joinRecording(helper, joined, "emotefar", BlockPos.ZERO);
            far.teleportTo(far.getX() + 200, far.getY(), far.getZ());

            for (boolean distant : List.of(true, false)) {
                hide.distantPlayers = distant;
                String text = "waves " + distant;
                broadcast(helper, PlayerChatMessage.unsigned(sender.getUUID(), text), sender.createCommandSourceStack(), ChatType.EMOTE_COMMAND);
                String mode = "hide.distantPlayers " + distant + ": ";
                helper.assertTrue(heard(near, text), mode + "a near player missed a non-op's /me");
                helper.assertFalse(heard(far, text), mode + "a far player heard a non-op's /me");
            }
        } finally {
            chat.mode = modeBefore;
            chat.radius = radiusBefore;
            hide.distantPlayers = distantBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    @GameTest
    public void announcementsReachEveryone(GameTestHelper helper) {
        YoltConfig.ChatSection chat = Settings.get().chat;
        YoltConfig.ChatMode modeBefore = chat.mode;
        int radiusBefore = chat.radius;
        List<ServerPlayer> joined = new ArrayList<>();
        ServerPlayer op = null;
        try {
            chat.mode = YoltConfig.ChatMode.PROXIMITY;
            chat.radius = 48;
            op = joinRecording(helper, joined, "announceop", BlockPos.ZERO);
            ServerPlayer nonOp = joinRecording(helper, joined, "announceas", new BlockPos(1, 0, 0));
            TestPlayers.Recording far = joinRecording(helper, joined, "announcefar", BlockPos.ZERO);
            far.teleportTo(far.getX() + 200, far.getY(), far.getZ());
            TestPlayers.op(op);
            CommandSourceStack opSource = op.createCommandSourceStack();

            broadcast(helper, PlayerChatMessage.unsigned(op.getUUID(), "op waves"), opSource, ChatType.EMOTE_COMMAND);
            helper.assertTrue(heard(far, "op waves"), "a far player missed an op's /me");
            // execute as runs with the op's permissions
            broadcast(helper, PlayerChatMessage.unsigned(nonOp.getUUID(), "waves for the op"), opSource.withEntity(nonOp), ChatType.EMOTE_COMMAND);
            helper.assertTrue(heard(far, "waves for the op"), "a far player missed an op's execute as /me");
            broadcast(helper, PlayerChatMessage.unsigned(op.getUUID(), "op says"), opSource, ChatType.SAY_COMMAND);
            // The far client has no entry for the op, so it would drop the signed message
            helper.assertTrue(asText(far, "op says"), "a far player did not get an op's /say as text");
            CommandSourceStack console = helper.getLevel().getServer().createCommandSourceStack();
            broadcast(helper, PlayerChatMessage.system("console says"), console, ChatType.SAY_COMMAND);
            helper.assertTrue(heard(far, "console says"), "a far player missed the console's /say");
        } finally {
            chat.mode = modeBefore;
            chat.radius = radiusBefore;
            if (op != null) {
                TestPlayers.deop(op);
            }
            leaveAll(joined);
        }
        helper.succeed();
    }

    // Vanilla never shows a spectator to a player who isn't one, so the listener's client has no entry for the spectator
    // 10 blocks off, and would throw its player chat away: it gets the line as text instead. A player in survival beside
    // them, whom the listener does come to know, is heard as player chat, which shows the tracking ran. A batch of its
    // own, with the switches held, since the players are driven over several ticks to start tracking.
    @GameTest(environment = CHAT_SPECTATOR, maxTicks = 20)
    public void spectatorChatReachesAnUnknownNearPlayerAsText(GameTestHelper helper) {
        List<ServerPlayer> joined = new ArrayList<>();
        TestPlayers.Recording listener = joinRecording(helper, joined, "spechear", BlockPos.ZERO);
        TestPlayers.Recording walker = joinRecording(helper, joined, "specwalk", new BlockPos(5, 0, 0));
        ServerPlayer spectator = TestPlayers.join(helper, "specsay", GameType.SPECTATOR, new BlockPos(10, 0, 0));
        joined.add(spectator);
        boolean waiting = false;
        try {
            helper.onEachTick(() -> joined.forEach(TestPlayers::driveTracking));
            helper.runAfterDelay(5, () -> {
                try {
                    helper.assertTrue(PlayerVisibility.known(listener).contains(walker.getUUID()), "the listener never came to know the walker");
                    helper.assertFalse(PlayerVisibility.known(listener).contains(spectator.getUUID()), "the listener knows the spectator");
                    String walked = "walking by " + CommandGameTests.uniqueName("x");
                    chatFrom(helper, walker, PlayerChatMessage.unsigned(walker.getUUID(), walked));
                    helper.assertTrue(TestPlayers.sentPackets(listener).stream().anyMatch(packet ->
                            packet instanceof ClientboundPlayerChatPacket chat && chat.body().content().equals(walked)),
                            "the listener did not get the walker's line as player chat");

                    String watched = "just watching " + CommandGameTests.uniqueName("x");
                    chatFrom(helper, spectator, PlayerChatMessage.unsigned(spectator.getUUID(), watched));
                    helper.assertTrue(asText(listener, watched), "the listener did not get the spectator's line as text");
                    helper.assertValueEqual(timesHeard(listener, watched), 1, "times the listener got the spectator's line");
                } finally {
                    leaveAll(joined);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            if (!waiting) {
                leaveAll(joined);
            }
        }
    }

    // A plain chat line, as a client's chat packet hands it to the player list: once to each player in range and to the
    // sender, and nothing at all to a player out of range, neither as player chat nor as the text a client that doesn't
    // know the sender gets
    @GameTest
    public void plainChatReachesOnlyPlayersInRadius(GameTestHelper helper) {
        YoltConfig.ChatSection chat = Settings.get().chat;
        YoltConfig.ChatMode modeBefore = chat.mode;
        int radiusBefore = chat.radius;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            chat.mode = YoltConfig.ChatMode.PROXIMITY;
            chat.radius = 48;
            TestPlayers.Recording sender = joinRecording(helper, joined, "plainfrom", BlockPos.ZERO);
            TestPlayers.Recording near = joinRecording(helper, joined, "plainnear", new BlockPos(10, 0, 0));
            TestPlayers.Recording far = joinRecording(helper, joined, "plainfar", BlockPos.ZERO);
            far.teleportTo(far.getX() + 200, far.getY(), far.getZ());

            String text = "anyone near " + CommandGameTests.uniqueName("x");
            chatFrom(helper, sender, PlayerChatMessage.unsigned(sender.getUUID(), text));
            helper.assertValueEqual(timesHeard(sender, text), 1, "times the sender got their own line");
            helper.assertValueEqual(timesHeard(near, text), 1, "times the player 10 blocks away got the line");
            helper.assertValueEqual(timesHeard(far, text), 0, "times the player 200 blocks away got the line");
        } finally {
            chat.mode = modeBefore;
            chat.radius = radiusBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    // Self-only chat reaches nobody but the sender, however close, and the admins in admin view, however far
    @GameTest
    public void selfOnlyChatReachesOnlySenderAndAdmins(GameTestHelper helper) {
        YoltConfig.ChatSection chat = Settings.get().chat;
        YoltConfig.ChatMode modeBefore = chat.mode;
        List<ServerPlayer> joined = new ArrayList<>();
        TestPlayers.Recording admin = null;
        try {
            chat.mode = YoltConfig.ChatMode.SELF_ONLY;
            TestPlayers.Recording sender = joinRecording(helper, joined, "selffrom", BlockPos.ZERO);
            TestPlayers.Recording near = joinRecording(helper, joined, "selfnear", new BlockPos(2, 0, 0));
            admin = joinRecording(helper, joined, "selfadmin", BlockPos.ZERO);
            admin.teleportTo(admin.getX() + 200, admin.getY(), admin.getZ());
            TestPlayers.op(admin);
            AdminView.set(admin, true);

            String text = "talking to myself " + CommandGameTests.uniqueName("x");
            chatFrom(helper, sender, PlayerChatMessage.unsigned(sender.getUUID(), text));
            helper.assertValueEqual(timesHeard(sender, text), 1, "times the sender got their own line");
            helper.assertValueEqual(timesHeard(admin, text), 1, "times the admin in admin view got the line");
            helper.assertValueEqual(timesHeard(near, text), 0, "times the player 2 blocks away got the line");
        } finally {
            chat.mode = modeBefore;
            if (admin != null) {
                AdminView.set(admin, false);
                TestPlayers.deop(admin);
            }
            leaveAll(joined);
        }
        helper.succeed();
    }

    // Hidden game messages are the chat ones. An action-bar line, such as a bed's, still goes to everyone it is for.
    @GameTest
    public void overlayGameMessagesStillReachEveryone(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean hiddenBefore = hide.gameMessages;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            hide.gameMessages = true;
            TestPlayers.Recording a = joinRecording(helper, joined, "overlaya", BlockPos.ZERO);
            TestPlayers.Recording b = joinRecording(helper, joined, "overlayb", new BlockPos(1, 0, 0));
            b.teleportTo(b.getX() + 200, b.getY(), b.getZ());

            String overlay = "on the action bar " + CommandGameTests.uniqueName("x");
            String line = "in the chat " + CommandGameTests.uniqueName("x");
            server.getPlayerList().broadcastSystemMessage(Component.literal(overlay), true);
            server.getPlayerList().broadcastSystemMessage(Component.literal(line), false);
            for (TestPlayers.Recording player : List.of(a, b)) {
                helper.assertTrue(texts(player).contains(overlay), player.getPlainTextName() + " missed the action-bar line");
                helper.assertFalse(texts(player).contains(line), player.getPlainTextName() + " got the hidden chat line");
            }
        } finally {
            hide.gameMessages = hiddenBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    // A client set to show only system messages, or none, throws player chat away, so the text sent in its place for a
    // sender it doesn't know isn't sent to it either. A client showing everything is the check that the text goes out.
    @GameTest
    public void fallbackTextRespectsRecipientChatVisibility(GameTestHelper helper) {
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        List<ServerPlayer> joined = new ArrayList<>();
        ServerPlayer op = null;
        try {
            hide.distantPlayers = true;
            op = joinRecording(helper, joined, "visibleop", BlockPos.ZERO);
            TestPlayers.op(op);
            TestPlayers.Recording full = joinRecording(helper, joined, "visiblefull", BlockPos.ZERO);
            full.teleportTo(full.getX() + 200, full.getY(), full.getZ());
            TestPlayers.Recording systemOnly = joinRecording(helper, joined, "visiblesystem", BlockPos.ZERO);
            systemOnly.teleportTo(systemOnly.getX() - 200, systemOnly.getY(), systemOnly.getZ());
            ClientInformation shown = ClientInformation.createDefault();
            systemOnly.updateOptions(new ClientInformation(shown.language(), shown.viewDistance(), ChatVisiblity.SYSTEM, shown.chatColors(),
                    shown.modelCustomisation(), shown.mainHand(), shown.textFilteringEnabled(), shown.allowsListing(), shown.particleStatus()));
            helper.assertFalse(PlayerVisibility.known(full).contains(op.getUUID()) || PlayerVisibility.known(systemOnly).contains(op.getUUID()),
                    "a far player knows the op");

            String text = "hear ye " + CommandGameTests.uniqueName("x");
            broadcast(helper, PlayerChatMessage.unsigned(op.getUUID(), text), op.createCommandSourceStack(), ChatType.SAY_COMMAND);
            helper.assertTrue(asText(full, text), "the player showing all chat did not get the op's /say as text");
            helper.assertValueEqual(timesHeard(systemOnly, text), 0, "times the player showing system messages only got the /say");
        } finally {
            hide.distantPlayers = distantBefore;
            if (op != null) {
                TestPlayers.deop(op);
            }
            leaveAll(joined);
        }
        helper.succeed();
    }

    // The text sent for an unknown sender blanks the words the server's filter took out, as the client would have shown
    // the player chat it replaces, and only when that recipient's copy is filtered
    @GameTest
    public void fallbackTextBlanksFilteredWords(GameTestHelper helper) {
        YoltConfig.HideSection hide = Settings.get().hide;
        boolean distantBefore = hide.distantPlayers;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            hide.distantPlayers = true;
            TestPlayers.Recording sender = joinRecording(helper, joined, "filterfrom", BlockPos.ZERO);
            TestPlayers.Recording far = joinRecording(helper, joined, "filterfar", BlockPos.ZERO);
            far.teleportTo(far.getX() + 1000, far.getY(), far.getZ());
            helper.assertFalse(PlayerVisibility.known(far).contains(sender.getUUID()), "the far player knows the sender");

            String text = "meet at the secret base";
            FilterMask mask = new FilterMask(text.length());
            int secret = text.indexOf("secret");
            for (int i = secret; i < secret + "secret".length(); i++) {
                mask.setFiltered(i);
            }
            OutgoingChatMessage message = OutgoingChatMessage.create(PlayerChatMessage.unsigned(sender.getUUID(), text).filter(mask));
            ChatType.Bound type = ChatType.bind(ChatType.CHAT, sender);
            far.sendChatMessage(message, true, type);
            helper.assertTrue(asText(far, "meet at the ###### base"), "the filtered copy's text: " + texts(far));
            far.sendChatMessage(message, false, type);
            helper.assertTrue(asText(far, text), "the unfiltered copy's text: " + texts(far));
        } finally {
            hide.distantPlayers = distantBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    // A chat bridge, such as a Discord one, listens for chat that went out to everyone. Proximity chat went out to some,
    // so the bridge never hears it; global chat it hears once, which shows the listener works.
    @GameTest
    public void chatBridgeEventsDoNotSeeProximityChat(GameTestHelper helper) {
        listenAsABridge();
        YoltConfig.ChatSection chat = Settings.get().chat;
        YoltConfig.ChatMode modeBefore = chat.mode;
        List<ServerPlayer> joined = new ArrayList<>();
        try {
            TestPlayers.Recording sender = joinRecording(helper, joined, "bridgefrom", BlockPos.ZERO);
            chat.mode = YoltConfig.ChatMode.PROXIMITY;
            String near = "just nearby " + CommandGameTests.uniqueName("x");
            chatFrom(helper, sender, PlayerChatMessage.unsigned(sender.getUUID(), near));
            helper.assertValueEqual(timesHeard(sender, near), 1, "times the sender got their proximity line");
            helper.assertFalse(BRIDGED.contains(near), "the bridge heard proximity chat");

            chat.mode = YoltConfig.ChatMode.GLOBAL;
            String everyone = "to everyone " + CommandGameTests.uniqueName("x");
            chatFrom(helper, sender, PlayerChatMessage.unsigned(sender.getUUID(), everyone));
            helper.assertValueEqual(BRIDGED.stream().filter(everyone::equals).count(), 1L, "times the bridge heard global chat");
        } finally {
            chat.mode = modeBefore;
            leaveAll(joined);
        }
        helper.succeed();
    }

    // What a bridge's listener has heard. Fabric's events can't drop a listener, so it is added once and stays.
    private static final List<String> BRIDGED = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean BRIDGE_LISTENING = new AtomicBoolean();

    private static void listenAsABridge() {
        if (BRIDGE_LISTENING.compareAndSet(false, true)) {
            ServerMessageEvents.CHAT_MESSAGE.register((message, sender, type) -> BRIDGED.add(message.signedContent()));
        }
    }

    // How a client's chat line reaches the player list
    private static void chatFrom(GameTestHelper helper, ServerPlayer sender, PlayerChatMessage message) {
        helper.getLevel().getServer().getPlayerList().broadcastChatMessage(message, sender, ChatType.bind(ChatType.CHAT, sender));
    }

    // How many times this player was sent the line, as player chat or as the text in its place
    private static int timesHeard(TestPlayers.Recording player, String text) {
        int times = 0;
        for (Packet<?> packet : TestPlayers.sentPackets(player)) {
            if (packet instanceof ClientboundPlayerChatPacket signed && signed.body().content().equals(text)
                    || packet instanceof ClientboundDisguisedChatPacket disguised && disguised.message().getString().equals(text)) {
                times++;
            }
        }
        for (Component message : player.messages) {
            if (message.getContents() instanceof TranslatableContents line && Arrays.stream(line.getArgs())
                    .anyMatch(arg -> arg instanceof Component part && part.getString().equals(text))) {
                times++;
            }
        }
        return times;
    }

    private static List<String> texts(TestPlayers.Recording player) {
        return player.messages.stream().map(Component::getString).toList();
    }

    // How /say and /me hand their message to the player list
    private static void broadcast(GameTestHelper helper, PlayerChatMessage message, CommandSourceStack source, ResourceKey<ChatType> type) {
        helper.getLevel().getServer().getPlayerList().broadcastChatMessage(message, source, ChatType.bind(type, source));
    }

    // Player chat from a sender the client knows, or the same line as text, or a console line with no sender
    private static boolean heard(TestPlayers.Recording player, String text) {
        if (asText(player, text)) {
            return true;
        }
        for (Packet<?> packet : TestPlayers.sentPackets(player)) {
            if (packet instanceof ClientboundPlayerChatPacket signed && signed.body().content().equals(text)
                    || packet instanceof ClientboundDisguisedChatPacket disguised && disguised.message().getString().equals(text)) {
                return true;
            }
        }
        return false;
    }

    // The line a client gets as text in place of player chat from a sender it doesn't know
    private static boolean asText(TestPlayers.Recording player, String text) {
        for (Component message : player.messages) {
            if (message.getContents() instanceof TranslatableContents line) {
                for (Object arg : line.getArgs()) {
                    if (arg instanceof Component part && part.getString().equals(text)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // How many death messages naming that player this one was sent; every vanilla one names the dead first
    private static int deathLines(TestPlayers.Recording player, String name) {
        int lines = 0;
        for (Component message : player.messages) {
            if (message.getContents() instanceof TranslatableContents line && line.getKey().startsWith("death.")
                    && line.getArgs().length > 0 && line.getArgs()[0] instanceof Component dead && dead.getString().equals(name)) {
                lines++;
            }
        }
        return lines;
    }

    private static TestPlayers.Recording joinRecording(GameTestHelper helper, List<ServerPlayer> joined, String name, BlockPos pos) {
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, name, GameType.SURVIVAL, pos);
        joined.add(player);
        return player;
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
