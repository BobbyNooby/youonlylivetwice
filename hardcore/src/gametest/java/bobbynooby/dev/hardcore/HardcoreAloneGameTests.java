package bobbynooby.dev.hardcore;

import bobbynooby.dev.anvils.AnvilConfig;
import bobbynooby.dev.anvils.AnvilSettings;
import bobbynooby.dev.anvils.YoltAnvils;
import bobbynooby.dev.elytra.ElytraConfig;
import bobbynooby.dev.elytra.ElytraSettings;
import bobbynooby.dev.elytra.YoltElytra;
import bobbynooby.dev.mending.MendingConfig;
import bobbynooby.dev.mending.MendingSettings;
import bobbynooby.dev.mending.YoltMending;
import com.mojang.brigadier.tree.RootCommandNode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** The mod on its own, with You Only Live Twice nowhere on the server. */
public class HardcoreAloneGameTests {
    @GameTest
    public void theModRunsWithoutYolt(GameTestHelper helper) {
        FabricLoader loader = FabricLoader.getInstance();
        helper.assertTrue(loader.isModLoaded("yolt-hardcore"), "yolt-hardcore isn't loaded");
        helper.assertFalse(loader.isModLoaded("you-only-live-twice"), "you-only-live-twice is loaded");
        // Shipped inside this mod's jar, so a server with only it has them too
        for (String tinyMod : List.of("yolt-anvils", "yolt-mending", "yolt-elytra")) {
            helper.assertTrue(loader.isModLoaded(tinyMod), tinyMod + " isn't loaded");
        }
        helper.succeed();
    }

    // The tiny mods take their settings from this one, as they do from YOLT, so none has a command of its own, and a
    // reload of one names this mod
    @GameTest
    public void itHostsTheTinyMods(GameTestHelper helper) {
        helper.assertFalse(YoltHardcore.hosted(), "yolt-hardcore is hosted");
        helper.assertTrue(YoltAnvils.hosted(), "yolt-anvils isn't hosted");
        helper.assertTrue(YoltMending.hosted(), "yolt-mending isn't hosted");
        helper.assertTrue(YoltElytra.hosted(), "yolt-elytra isn't hosted");
        RootCommandNode<CommandSourceStack> root = helper.getLevel().getServer().getCommands().getDispatcher().getRoot();
        for (String id : List.of(YoltAnvils.MOD_ID, YoltMending.MOD_ID, YoltElytra.MOD_ID)) {
            helper.assertTrue(root.getChild(id) == null, "/" + id + " is there while hosted");
        }
        List<String> fromHere = List.of("the settings come from YOLT Hardcore");
        helper.assertValueEqual(YoltAnvils.reload().notes(), fromHere, "what a reload of yolt-anvils says");
        helper.assertValueEqual(YoltMending.reload().notes(), fromHere, "what a reload of yolt-mending says");
        helper.assertValueEqual(YoltElytra.reload().notes(), fromHere, "what a reload of yolt-elytra says");
        helper.succeed();
    }

    // The config folder outlives a run, so a file there may be a leftover of an older one: only a file changed since
    // this server's JVM started is this start's doing. A leftover is removed, then the mod is reloaded, which a tiny
    // mod on its own answers by writing the missing file, and hosted by naming this mod.
    @GameTest
    public void theTinyModsWriteNoFileOfTheirOwn(GameTestHelper helper) throws IOException {
        Map<String, Supplier<List<String>>> reloads = new LinkedHashMap<>();
        reloads.put(AnvilConfig.FILE, () -> YoltAnvils.reload().notes());
        reloads.put(MendingConfig.FILE, () -> YoltMending.reload().notes());
        reloads.put(ElytraConfig.FILE, () -> YoltElytra.reload().notes());
        long started = ManagementFactory.getRuntimeMXBean().getStartTime();
        for (Map.Entry<String, Supplier<List<String>>> mod : reloads.entrySet()) {
            Path file = FabricLoader.getInstance().getConfigDir().resolve(mod.getKey());
            if (Files.exists(file)) {
                helper.assertTrue(Files.getLastModifiedTime(file).toMillis() < started, "this server start wrote " + file);
                Files.delete(file);
            }
            helper.assertValueEqual(mod.getValue().get(), List.of("the settings come from YOLT Hardcore"),
                    "what a reload for " + mod.getKey() + " says");
            helper.assertFalse(Files.exists(file), "a reload wrote " + file);
        }
        helper.succeed();
    }

    // Each reads this mod's settings in use at every use: the flat anvil price and the Mending nerf ship on and the
    // elytra nerf off, as in the hardcore block, a change to that block reaches them, and the players every hardcore
    // feature leaves out keep vanilla's anvil, Mending and elytra
    @GameTest
    public void theTinyModsTakeTheirSettingsFromThisMod(GameTestHelper helper) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreFile.Loaded.defaults());
        ServerPlayer survival = null;
        ServerPlayer creative = null;
        try {
            helper.assertValueEqual(YoltAnvils.settings(), new AnvilSettings(true, 6, 5, 1, 0.12), "the anvil settings");
            helper.assertValueEqual(YoltMending.settings(), new MendingSettings(true, 0.4), "the Mending settings");
            helper.assertFalse(YoltElytra.settings().enabled(), "the elytra nerf is on by default");

            YoltHardcore.swapForTesting(HardcoreCommandGameTests.with(c -> {
                c.anvil.enchantCost = 9;
                c.mending.durabilityPerXp = 0.7;
                c.elytra.enabled = true;
                c.elytra.glideGravityMultiplier = 0.5;
            }));
            helper.assertValueEqual(YoltAnvils.settings().enchantCost(), 9, "the anvil's enchant price after a change");
            helper.assertValueEqual(YoltMending.settings().durabilityPerXp(), 0.7, "the Mending rate after a change");
            helper.assertValueEqual(YoltElytra.settings(), new ElytraSettings(true, 0.5, 20.0), "the elytra settings after a change");

            survival = TestPlayers.join(helper, "TinySurvival", GameType.SURVIVAL, new BlockPos(1, 1, 1));
            creative = TestPlayers.join(helper, "TinyCreative", GameType.CREATIVE, new BlockPos(1, 1, 1));
            List<String> status = TestPlayers.status(helper, survival);
            helper.assertTrue(status.contains("Server rules") && status.contains("  Mending 0.7/XP (vanilla 2)")
                    && status.contains("  anvil: rename 1, repair 5, enchant 9"), "/status: " + status);
            for (ServerPlayer player : List.of(survival, creative)) {
                boolean out = player == creative;
                String who = out ? "a creative player" : "a survival player";
                helper.assertValueEqual(YoltAnvils.exempt(player), out, "whether yolt-anvils leaves out " + who);
                helper.assertValueEqual(YoltMending.exempt(player), out, "whether yolt-mending leaves out " + who);
                helper.assertValueEqual(YoltElytra.exempt(player), out, "whether yolt-elytra leaves out " + who);
            }
        } finally {
            try {
                TestPlayers.leave(survival);
                TestPlayers.leave(creative);
            } finally {
                YoltHardcore.swapForTesting(before);
            }
        }
        helper.succeed();
    }

    // The start wrote the file with every key, so it reads back with nothing to say
    @GameTest
    public void theStartWroteTheFile(GameTestHelper helper) {
        Path file = YoltHardcore.configFile();
        helper.assertTrue(Files.exists(file), "the start wrote no " + file);
        HardcoreFile.Result read = HardcoreFile.reload(file, HardcoreFile.Loaded.defaults());
        helper.assertFalse(read.failed(), "the file couldn't be read: " + read.notes());
        helper.assertValueEqual(read.notes(), List.of(), "what reading the file said");
        RootCommandNode<CommandSourceStack> root = helper.getLevel().getServer().getCommands().getDispatcher().getRoot();
        helper.assertTrue(root.getChild(YoltHardcore.MOD_ID) != null, "/yolt-hardcore isn't there on its own");
        helper.assertTrue(root.getChild("status") != null, "/status isn't there on its own");
        helper.succeed();
    }

    // With no host, the mod's own entity-load hook boosts a mob, as YOLT's does under YOLT. Made the way a natural spawn
    // makes it, then added to the world, which runs the load hook. A zombie's 20 health times 1.5.
    @GameTest
    public void aMobIsBoostedAsItLoads(GameTestHelper helper) {
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(HardcoreCommandGameTests.with(c -> c.tougherMobs.enabled = true));
        Zombie zombie = null;
        try {
            zombie = HardcoreTestKit.naturalZombie(helper, new BlockPos(1, 1, 1));
            AttributeInstance maxHealth = zombie.getAttribute(Attributes.MAX_HEALTH);
            helper.assertTrue(maxHealth != null && maxHealth.getModifier(MobBoosts.TOUGHER) != null,
                    "the zombie has no " + MobBoosts.TOUGHER + " modifier");
            helper.assertValueEqual(zombie.getMaxHealth(), 30.0F, "the zombie's max health");
            helper.assertTrue(zombie.entityTags().contains(MobBoosts.TOUGH), "the zombie is not tagged " + MobBoosts.TOUGH);
        } finally {
            try {
                if (zombie != null) {
                    zombie.discard();
                }
            } finally {
                YoltHardcore.swapForTesting(before);
            }
        }
        helper.succeed();
    }
}
