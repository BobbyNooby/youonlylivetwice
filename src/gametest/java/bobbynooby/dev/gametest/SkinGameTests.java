package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.bodies.Kills;
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
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
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
import org.jspecify.annotations.Nullable;

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
