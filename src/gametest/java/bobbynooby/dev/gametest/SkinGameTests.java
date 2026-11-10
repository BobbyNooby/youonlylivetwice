package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.bodies.Kills;
import bobbynooby.dev.chat.CommandRestriction;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.skins.CachedSkin;
import bobbynooby.dev.skins.SkinStore;
import bobbynooby.dev.skins.SkinTextures;
import bobbynooby.dev.skins.Skins;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

// The fake lookup stands in for Mojang (TestServerSetup puts it in before the server starts), and each test sets up
// names of its own. Logins run in LOGIN_CHECK, where no other test's player is online: the GameTest server holds one
// player, and vanilla refuses a login to a full server. Every test puts back what it changes in the same tick.
public class SkinGameTests {
    private static final FakeSkinLookup MOJANG = FakeSkinLookup.INSTANCE;

    // The skin goes into the profile the player is made from, so the player has it, and so does the body they leave
    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void offlineJoinGetsCachedSkin(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Skin");
        SkinTextures skin = FakeSkinLookup.texturesOf(name);
        MOJANG.answer(name);
        YoltConfig.BodiesSection bodies = Settings.get().bodies;
        boolean bodiesBefore = bodies.enabled;
        ServerPlayer player = null;
        try (TestLogins.Login login = TestLogins.start(server, TestLogins.offline(name))) {
            login.tick();
            Optional<GameProfile> told = login.finished();
            helper.assertTrue(told.isPresent(), "the login did not go through");
            assertEqual(helper, textures(told.get()), skin, "textures the client was told it has");
            assertEqual(helper, textures(login.profile()), skin, "textures in the profile the player is made from");
            CachedSkin cached = SkinStore.get(server).skin(name);
            helper.assertTrue(cached != null && skin.equals(cached.textures()), "the skin was not cached: " + cached);

            player = TestPlayers.join(helper, login.profile(), GameType.SURVIVAL, new BlockPos(1, 1, 1));
            assertEqual(helper, textures(player.getGameProfile()), skin, "the joined player's textures");
            bodies.enabled = true;
            TestPlayers.leave(player);
            BodyRecord record = BodyStore.get(server).byOwner(player.getUUID()).orElse(null);
            helper.assertTrue(record != null, "no body at logout");
            Entity body = helper.getLevel().getEntity(record.bodyEntity());
            helper.assertTrue(body instanceof Mannequin, "the body is not a mannequin: " + body);
            assertEqual(helper, textures(((Mannequin) body).getProfile().partialProfile()), skin, "the body's textures");
        } finally {
            bodies.enabled = bodiesBefore;
            MOJANG.forget(name);
            if (player != null) {
                removeBody(helper, player);
            }
        }
        helper.succeed();
    }

    // Online mode, a proxy or another mod gave the skin: Mojang isn't asked, and the skin stays as it came
    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void onlineProfileIsLeftAlone(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Online");
        SkinTextures own = new SkinTextures("own textures", "own signature");
        // What the lookup would give, if it were asked
        MOJANG.answer(name);
        GameProfile online = Skins.withTextures(new GameProfile(UUID.randomUUID(), name), own);
        try (TestLogins.Login login = TestLogins.start(server, online)) {
            login.tick();
            helper.assertTrue(login.finished().isPresent(), "the login did not go through");
            helper.assertValueEqual(MOJANG.timesAsked(name), 0, "times Mojang was asked");
            assertEqual(helper, textures(login.profile()), own, "textures in the profile the player is made from");
            // Kept for heads built later from the name alone
            assertEqual(helper, SkinStore.get(server).applied(online.id()), own, "skin kept as the one they joined with");
        } finally {
            MOJANG.forget(name);
        }
        helper.succeed();
    }

    // Mojang never answers: the login waits out skins.lookupTimeoutSeconds, a tick at a time, then goes on without a skin
    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void lookupTimeoutStillLogsIn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Slow");
        MOJANG.neverAnswer(name);
        int ticks = Settings.get().skins.lookupTimeoutSeconds * SharedConstants.TICKS_PER_SECOND;
        try (TestLogins.Login login = TestLogins.start(server, TestLogins.offline(name))) {
            for (int tick = 0; tick < ticks; tick++) {
                login.tick();
            }
            helper.assertTrue(login.finished().isEmpty(), "the login went on before the timeout");
            login.tick();
            Optional<GameProfile> told = login.finished();
            helper.assertTrue(told.isPresent(), "the login did not go through after the timeout");
            assertEqual(helper, textures(told.get()), null, "textures after the timeout");
            helper.assertValueEqual(MOJANG.timesAsked(name), 1, "times Mojang was asked");
        } finally {
            MOJANG.forget(name);
        }
        helper.succeed();
    }

    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void modeOffNeverLooksUp(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Off");
        MOJANG.answer(name);
        YoltConfig.SkinsSection skins = Settings.get().skins;
        YoltConfig.SkinMode before = skins.mode;
        skins.mode = YoltConfig.SkinMode.OFF;
        try (TestLogins.Login login = TestLogins.start(server, TestLogins.offline(name))) {
            login.tick();
            Optional<GameProfile> told = login.finished();
            helper.assertTrue(told.isPresent(), "the login did not go through");
            helper.assertValueEqual(MOJANG.timesAsked(name), 0, "times Mojang was asked");
            assertEqual(helper, textures(told.get()), null, "textures with skins off");
            // Refused before the player is even looked up
            helper.assertValueEqual(console(server, "yolt skin set " + name + " " + name), List.of("Skins are turned off."),
                    "reply to a pick with skins off");
            helper.assertValueEqual(MOJANG.timesAsked(name), 0, "times Mojang was asked after the pick");
        } finally {
            skins.mode = before;
            MOJANG.forget(name);
        }
        helper.succeed();
    }

    // SkinsRestorer or SkinRestorer gives skins at login itself, so YOLT does nothing
    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void skinModPresentStepsAside(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Mod");
        MOJANG.answer(name);
        BooleanSupplier before = Skins.useSkinModCheckForTesting(() -> true);
        try (TestLogins.Login login = TestLogins.start(server, TestLogins.offline(name))) {
            login.tick();
            Optional<GameProfile> told = login.finished();
            helper.assertTrue(told.isPresent(), "the login did not go through");
            helper.assertValueEqual(MOJANG.timesAsked(name), 0, "times Mojang was asked");
            assertEqual(helper, textures(told.get()), null, "textures with a skin mod loaded");
        } finally {
            Skins.useSkinModCheckForTesting(before);
            MOJANG.forget(name);
        }
        helper.succeed();
    }

    // /yolt kill of an offline player with no body builds the grave head from the name and id alone, so the skin
    // comes from what YOLT saved for that name
    @GameTest
    public void nameOnlyHeadUsesCache(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TestPlayers.join(helper, "Head", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        NameAndId who = player.nameAndId();
        // Something to carry, so the death leaves a grave
        player.getInventory().add(new ItemStack(Items.DIRT));
        // Leaving saves the player, so the kill is an offline one
        TestPlayers.leave(player);
        SkinTextures skin = FakeSkinLookup.texturesOf(who.name());
        SkinStore.get(server).putSkin(CachedSkin.found(who.name(), skin, System.currentTimeMillis()));
        try {
            helper.assertValueEqual(Kills.kill(server, who), Kills.Result.KILLED_OFFLINE, "kill result");
            List<Grave> graves = gravesOf(server, who.id());
            helper.assertFalse(graves.isEmpty(), "the kill left no grave");
            for (Grave grave : graves) {
                assertEqual(helper, textures(grave.profile().partialProfile()), skin, "the grave head's textures");
            }
            ItemStack head = graves.getFirst().items().stream().filter(GraveItems::isGraveHead).findFirst().orElse(ItemStack.EMPTY);
            ResolvableProfile headProfile = head.get(DataComponents.PROFILE);
            helper.assertTrue(headProfile != null, "the grave holds no grave head item");
            assertEqual(helper, textures(headProfile.partialProfile()), skin, "the grave head item's textures");
        } finally {
            for (Grave grave : gravesOf(server, who.id())) {
                GraveStore.get(server).remove(grave.id());
                helper.getLevel().setBlock(grave.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            DeathLockout.revive(who.id(), who.name(), "console");
        }
        helper.succeed();
    }

    private static List<Grave> gravesOf(MinecraftServer server, UUID owner) {
        return GraveStore.get(server).all().stream().filter(grave -> grave.owner().equals(owner)).toList();
    }

    // The account a player picked wins over the one with their name, which Mojang isn't even asked about
    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void skinChoiceOverridesUsernameLookup(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Pick");
        String account = uniqueName("Acct");
        MOJANG.answer(name);
        MOJANG.answer(account);
        GameProfile profile = TestLogins.offline(name);
        try {
            // The console names the player, which an offline server turns into the same id their login gets
            helper.assertValueEqual(console(server, "yolt skin set " + name + " " + account),
                    List.of(name + "'s skin is now " + account + "'s. It shows once they rejoin."), "command output");
            assertEqual(helper, SkinStore.get(server).choice(profile.id()), account, "the picked account");
            try (TestLogins.Login login = TestLogins.start(server, profile)) {
                login.tick();
                Optional<GameProfile> told = login.finished();
                helper.assertTrue(told.isPresent(), "the login did not go through");
                assertEqual(helper, textures(login.profile()), FakeSkinLookup.texturesOf(account), "textures after the pick");
            }
            helper.assertValueEqual(MOJANG.timesAsked(name), 0, "times Mojang was asked about the player's own name");
        } finally {
            SkinStore.get(server).clearChoice(profile.id());
            MOJANG.forget(name);
            MOJANG.forget(account);
        }
        helper.succeed();
    }

    // Even a skin the player came with, as on an online-mode server, gives way to the account they picked
    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void skinChoiceOverridesOnlineSkin(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Online");
        String account = uniqueName("Acct");
        MOJANG.answer(account);
        GameProfile online = Skins.withTextures(new GameProfile(UUID.randomUUID(), name), new SkinTextures("own textures", "own signature"));
        // Straight into the store: on this offline test server a command would give the name its offline id
        SkinStore.get(server).choose(online.id(), account);
        try (TestLogins.Login login = TestLogins.start(server, online)) {
            login.tick();
            helper.assertTrue(login.finished().isPresent(), "the login did not go through");
            assertEqual(helper, textures(login.profile()), FakeSkinLookup.texturesOf(account), "textures after the pick");
            helper.assertValueEqual(MOJANG.timesAsked(account), 1, "times Mojang was asked about the picked account");
        } finally {
            SkinStore.get(server).clearChoice(online.id());
            MOJANG.forget(account);
        }
        helper.succeed();
    }

    // A picked account that no longer exists gives way to the player's own name at login, as it does for heads built
    // from the name alone, so the player and a later head agree
    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void goneAccountPickFallsBackToUsername(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Gone");
        // No test sets this one up, so the fake says no account has it, as after the account was renamed
        String account = uniqueName("Acct");
        MOJANG.answer(name);
        GameProfile profile = TestLogins.offline(name);
        SkinStore store = SkinStore.get(server);
        // Straight into the store, as a pick made while the account still existed: a command now would refuse it
        store.choose(profile.id(), account);
        try (TestLogins.Login login = TestLogins.start(server, profile)) {
            login.tick();
            helper.assertTrue(login.finished().isPresent(), "the login did not go through");
            assertEqual(helper, textures(login.profile()), FakeSkinLookup.texturesOf(name), "textures with the picked account gone");
            helper.assertValueEqual(MOJANG.timesAsked(account), 1, "times Mojang was asked about the picked account");
            helper.assertValueEqual(MOJANG.timesAsked(name), 1, "times Mojang was asked about the player's own name");
            assertEqual(helper, textures(Skins.known(server, new NameAndId(profile))), FakeSkinLookup.texturesOf(name),
                    "textures of a head built from the name");
            // Kept, so the pick comes back if someone takes the name again
            assertEqual(helper, store.choice(profile.id()), account, "the pick after its account was found gone");
        } finally {
            store.clearChoice(profile.id());
            MOJANG.forget(name);
            MOJANG.forget(account);
        }
        helper.succeed();
    }

    @GameTest
    public void unknownMojangNameChangesNothing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean choiceBefore = config.skins.allowPlayerChoice;
        String account = uniqueName("Acct");
        // The fake has no account for a name no test set up
        String unknown = uniqueName("None");
        MOJANG.answer(account);
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Unknown", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        SkinStore store = SkinStore.get(server);
        try {
            config.restrictCommands = true;
            config.skins.allowPlayerChoice = true;
            type(player, "yolt skin set " + account);
            assertEqual(helper, store.choice(player.getUUID()), account, "the first pick");

            player.messages.clear();
            type(player, "yolt skin set " + unknown);
            helper.assertValueEqual(texts(player), List.of("No Minecraft account is called " + unknown + "."), "reply to an unknown name");
            assertEqual(helper, store.choice(player.getUUID()), account, "the pick after an unknown name");
            helper.assertValueEqual(MOJANG.timesAsked(unknown), 1, "times Mojang was asked about the unknown name");

            // No account can have a dot in its name, so Mojang isn't asked
            player.messages.clear();
            type(player, "yolt skin set no.such");
            helper.assertValueEqual(texts(player), List.of("No Minecraft account is called no.such."), "reply to an impossible name");
            helper.assertValueEqual(MOJANG.timesAsked("no.such"), 0, "times Mojang was asked about an impossible name");
            assertEqual(helper, store.choice(player.getUUID()), account, "the pick after an impossible name");
        } finally {
            config.restrictCommands = restrictBefore;
            config.skins.allowPlayerChoice = choiceBefore;
            store.clearChoice(player.getUUID());
            TestPlayers.leave(player);
            MOJANG.forget(account);
            MOJANG.forget(unknown);
        }
        helper.succeed();
    }

    // Typed by a non-op, read against the tree their client has: /yolt skin for their own skin, and nothing more
    @GameTest
    public void nonOpCanOnlySetOwnSkin(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean choiceBefore = config.skins.allowPlayerChoice;
        String account = uniqueName("Acct");
        MOJANG.answer(account);
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Picker", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        ServerPlayer other = TestPlayers.join(helper, "Picked", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String otherName = other.nameAndId().name();
        SkinStore store = SkinStore.get(server);
        try {
            config.restrictCommands = true;
            config.skins.allowPlayerChoice = true;
            helper.assertTrue(CommandRestriction.restricted(player), "the test player is not held to the non-op tree");

            type(player, "yolt skin set " + otherName + " " + account);
            assertEqual(helper, store.choice(other.getUUID()), null, "a non-op picked another player's skin");

            type(player, "yolt skin set " + account);
            assertEqual(helper, store.choice(player.getUUID()), account, "the non-op's own pick");
            helper.assertTrue(texts(player).contains("Your skin is now " + account + "'s. Rejoin to see it."),
                    "no reply to the pick: " + texts(player));

            store.choose(other.getUUID(), account);
            type(player, "yolt skin clear " + otherName);
            assertEqual(helper, store.choice(other.getUUID()), account, "a non-op cleared another player's pick");

            player.messages.clear();
            type(player, "yolt skin clear");
            assertEqual(helper, store.choice(player.getUUID()), null, "the non-op's own pick after clearing");
            helper.assertValueEqual(texts(player), List.of("Your skin is back to the default. Rejoin to see it."), "reply to clearing");

            // With the switch off the tree has no /yolt, so nothing runs
            config.skins.allowPlayerChoice = false;
            type(player, "yolt skin set " + account);
            assertEqual(helper, store.choice(player.getUUID()), null, "a pick with player choice off");
        } finally {
            config.restrictCommands = restrictBefore;
            config.skins.allowPlayerChoice = choiceBefore;
            store.clearChoice(player.getUUID());
            store.clearChoice(other.getUUID());
            TestPlayers.leave(player);
            TestPlayers.leave(other);
            MOJANG.forget(account);
        }
        helper.succeed();
    }

    // With the command filter off a non-op gets vanilla's tree, cut down to the nodes they may use: of /yolt, only
    // their own skin. Typed, their commands are read against the full tree, where the requirements stop the rest.
    @GameTest
    public void nonOpPicksOwnSkinWithFilterOff(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        YoltConfig config = Settings.get();
        boolean restrictBefore = config.restrictCommands;
        boolean choiceBefore = config.skins.allowPlayerChoice;
        int diameterBefore = config.worldBorder.diameter;
        String account = uniqueName("Acct");
        MOJANG.answer(account);
        TestPlayers.Recording player = TestPlayers.joinRecording(helper, "Unfiltered", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        ServerPlayer other = TestPlayers.join(helper, "Unpicked", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        String otherName = other.nameAndId().name();
        SkinStore store = SkinStore.get(server);
        try {
            config.restrictCommands = false;
            config.skins.allowPlayerChoice = true;
            helper.assertFalse(CommandRestriction.restricted(player), "the test player is held to the non-op tree");
            server.getCommands().sendCommands(player);
            helper.assertValueEqual(yoltPaths(CommandRestrictionGameTests.lastTree(helper, player)),
                    List.of("yolt", "yolt skin", "yolt skin set", "yolt skin set <mojangName>", "yolt skin clear"),
                    "the /yolt a non-op is sent");

            type(player, "yolt skin set " + account);
            assertEqual(helper, store.choice(player.getUUID()), account, "the non-op's own pick");
            helper.assertTrue(texts(player).contains("Your skin is now " + account + "'s. Rejoin to see it."),
                    "no reply to the pick: " + texts(player));

            type(player, "yolt skin set " + otherName + " " + account);
            assertEqual(helper, store.choice(other.getUUID()), null, "a non-op picked another player's skin");
            store.choose(other.getUUID(), account);
            type(player, "yolt skin clear " + otherName);
            assertEqual(helper, store.choice(other.getUUID()), account, "a non-op cleared another player's pick");
            type(player, "yolt border 5000");
            helper.assertValueEqual(config.worldBorder.diameter, diameterBefore, "the border diameter after a non-op's /yolt border");

            player.messages.clear();
            type(player, "yolt skin clear");
            assertEqual(helper, store.choice(player.getUUID()), null, "the non-op's own pick after clearing");
            helper.assertValueEqual(texts(player), List.of("Your skin is back to the default. Rejoin to see it."), "reply to clearing");

            // With player choice off a non-op is sent no /yolt, and can't run it
            config.skins.allowPlayerChoice = false;
            server.getCommands().sendCommands(player);
            helper.assertValueEqual(yoltPaths(CommandRestrictionGameTests.lastTree(helper, player)), List.of(),
                    "the /yolt a non-op is sent with player choice off");
            type(player, "yolt skin set " + account);
            assertEqual(helper, store.choice(player.getUUID()), null, "a pick with player choice off");
        } finally {
            config.restrictCommands = restrictBefore;
            config.skins.allowPlayerChoice = choiceBefore;
            store.clearChoice(player.getUUID());
            store.clearChoice(other.getUUID());
            TestPlayers.leave(player);
            TestPlayers.leave(other);
            MOJANG.forget(account);
        }
        helper.succeed();
    }

    // Every path under /yolt in a tree, as the dump writes them
    private static List<String> yoltPaths(RootCommandNode<CommandSourceStack> root) {
        List<String> paths = new ArrayList<>();
        CommandNode<CommandSourceStack> yolt = root.getChild("yolt");
        if (yolt != null) {
            addPaths(yolt, "", paths);
        }
        return paths;
    }

    private static void addPaths(CommandNode<CommandSourceStack> node, String parent, List<String> paths) {
        String path = parent + (node instanceof ArgumentCommandNode<?, ?> ? "<" + node.getName() + ">" : node.getName());
        paths.add(path);
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            addPaths(child, path + " ", paths);
        }
    }

    // Clearing goes back to the account with the player's own name
    @GameTest(environment = LockoutGameTests.LOGIN_CHECK)
    public void skinClearRestoresDefault(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        String name = uniqueName("Clear");
        String account = uniqueName("Acct");
        MOJANG.answer(name);
        MOJANG.answer(account);
        GameProfile profile = TestLogins.offline(name);
        try {
            console(server, "yolt skin set " + name + " " + account);
            assertEqual(helper, SkinStore.get(server).choice(profile.id()), account, "the pick");
            // On disk at once, so a crash can't lose what the player was told
            assertEqual(helper, onDisk(server).choice(profile.id()), account, "the pick in the skin file");
            helper.assertValueEqual(console(server, "yolt skin clear " + name),
                    List.of(name + "'s skin is back to the default. It shows once they rejoin."), "command output");
            assertEqual(helper, SkinStore.get(server).choice(profile.id()), null, "the pick after clearing");
            assertEqual(helper, onDisk(server).choice(profile.id()), null, "the pick in the skin file after clearing");
            helper.assertValueEqual(console(server, "yolt skin clear " + name), List.of(name + " hasn't picked a skin."),
                    "output of a second clear");
            try (TestLogins.Login login = TestLogins.start(server, profile)) {
                login.tick();
                helper.assertTrue(login.finished().isPresent(), "the login did not go through");
                assertEqual(helper, textures(login.profile()), FakeSkinLookup.texturesOf(name), "textures after clearing");
            }
        } finally {
            SkinStore.get(server).clearChoice(profile.id());
            MOJANG.forget(name);
            MOJANG.forget(account);
        }
        helper.succeed();
    }

    // What the skin file on disk holds right now
    private static SkinStore onDisk(MinecraftServer server) throws IOException {
        Path file = SkinStore.TYPE.id().withSuffix(".dat").resolveAgainst(server.getWorldPath(LevelResource.DATA));
        CompoundTag saved = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        return SkinStore.TYPE.codec().parse(NbtOps.INSTANCE, saved.get("data")).getOrThrow();
    }

    // As the player's client sends a typed command, so it is read against the tree that player has
    private static void type(ServerPlayer player, String command) {
        player.connection.handleChatCommand(new ServerboundChatCommandPacket(command));
    }

    private static List<String> texts(TestPlayers.Recording player) {
        return player.messages.stream().map(Component::getString).toList();
    }

    // Runs the command as the console and returns every line it sent back
    private static List<String> console(MinecraftServer server, String command) {
        List<String> output = new ArrayList<>();
        CommandSource capture = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                output.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(capture), command);
        return output;
    }

    // A current file loads as it was saved, and one from a newer YOLT still gives what this one can read
    @GameTest
    public void skinStoreReadsWhatItSaves(GameTestHelper helper) {
        UUID player = UUID.randomUUID();
        CompoundTag data = new CompoundTag();
        data.putInt("format", SkinStore.FORMAT);
        ListTag skins = new ListTag();
        skins.add(skinTag("Notch", new SkinTextures("notch value", "notch signature"), 123L));
        skins.add(skinTag("Nobody", null, 456L));
        data.put("skins", skins);
        ListTag applied = new ListTag();
        CompoundTag entry = new CompoundTag();
        entry.put("player", UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, player).getOrThrow());
        entry.put("textures", texturesTag(new SkinTextures("applied value", null)));
        applied.add(entry);
        data.put("applied", applied);
        ListTag choices = new ListTag();
        CompoundTag choice = new CompoundTag();
        choice.put("player", UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, player).getOrThrow());
        choice.putString("name", "Notch");
        choices.add(choice);
        data.put("choices", choices);

        SkinStore loaded = SkinStore.TYPE.codec().parse(NbtOps.INSTANCE, data).getOrThrow();
        assertStore(helper, loaded, player, "loaded");
        helper.assertFalse(loaded.fromNewer(), "a current file read as newer");

        Tag saved = SkinStore.TYPE.codec().encodeStart(NbtOps.INSTANCE, loaded).getOrThrow();
        helper.assertTrue(saved instanceof CompoundTag tag && tag.getIntOr("format", 0) == SkinStore.FORMAT,
                "the saved file has no format: " + saved);
        assertStore(helper, SkinStore.TYPE.codec().parse(NbtOps.INSTANCE, saved).getOrThrow(), player, "saved and loaded again");

        data.putInt("format", SkinStore.FORMAT + 1);
        SkinStore newer = SkinStore.TYPE.codec().parse(NbtOps.INSTANCE, data).getOrThrow();
        helper.assertTrue(newer.fromNewer(), "a newer file did not read as newer");
        assertStore(helper, newer, player, "from a newer file");
        helper.succeed();
    }

    private static void assertStore(GameTestHelper helper, SkinStore store, UUID player, String what) {
        assertEqual(helper, store.skin("NOTCH"), CachedSkin.found("Notch", new SkinTextures("notch value", "notch signature"), 123L),
                "Notch's skin, " + what);
        assertEqual(helper, store.skin("nobody"), CachedSkin.noAccount("Nobody", 456L), "the no-account answer, " + what);
        assertEqual(helper, store.applied(player), new SkinTextures("applied value", null), "the applied skin, " + what);
        assertEqual(helper, store.choice(player), "Notch", "the picked account, " + what);
    }

    private static CompoundTag skinTag(String name, @Nullable SkinTextures textures, long fetchedAt) {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        if (textures != null) {
            tag.put("textures", texturesTag(textures));
        }
        tag.putLong("fetched_at", fetchedAt);
        return tag;
    }

    private static CompoundTag texturesTag(SkinTextures textures) {
        CompoundTag tag = new CompoundTag();
        tag.putString("value", textures.value());
        if (textures.signature() != null) {
            tag.putString("signature", textures.signature());
        }
        return tag;
    }

    // Like assertValueEqual, which can't take a null value
    static void assertEqual(GameTestHelper helper, @Nullable Object value, @Nullable Object expected, String what) {
        helper.assertTrue(Objects.equals(value, expected), what + ": expected " + expected + ", got " + value);
    }

    static @Nullable SkinTextures textures(GameProfile profile) {
        return profile.properties().get("textures").stream().findFirst()
                .map(property -> new SkinTextures(property.value(), property.signature()))
                .orElse(null);
    }

    // Short enough for a player name, and never one another test uses
    static String uniqueName(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 6);
    }

    // Bodies off first, so a player still online leaves without making another one
    private static void removeBody(GameTestHelper helper, ServerPlayer player) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer online = server.getPlayerList().getPlayer(player.getUUID());
        if (online != null) {
            TestPlayers.leave(online);
        }
        BodyStore store = BodyStore.get(server);
        store.byOwner(player.getUUID()).ifPresent(record -> {
            Entity body = helper.getLevel().getEntity(record.bodyEntity());
            if (body != null) {
                body.discard();
            }
            store.remove(record.owner());
        });
    }
}
