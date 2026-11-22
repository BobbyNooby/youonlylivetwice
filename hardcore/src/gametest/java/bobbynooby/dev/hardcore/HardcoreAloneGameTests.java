package bobbynooby.dev.hardcore;

import bobbynooby.dev.anvils.YoltAnvils;
import bobbynooby.dev.elytra.YoltElytra;
import bobbynooby.dev.mending.YoltMending;
import com.mojang.brigadier.tree.RootCommandNode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
            ServerLevel level = helper.getLevel();
            zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.NATURAL);
            helper.assertTrue(zombie != null, "no zombie made");
            Vec3 at = helper.absoluteVec(Vec3.atBottomCenterOf(new BlockPos(1, 1, 1)));
            zombie.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
            zombie.setPersistenceRequired();
            helper.assertTrue(level.addFreshEntity(zombie), "the zombie was not added");
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
