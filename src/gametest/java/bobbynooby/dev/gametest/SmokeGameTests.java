package bobbynooby.dev.gametest;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringUtil;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class SmokeGameTests {
    @GameTest
    public void testPlayerIsSurvivalAndNamed(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Alex", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            helper.assertTrue(player.gameMode() == GameType.SURVIVAL, "test player is not in survival");
            helper.assertTrue(player.getGameProfile().name().startsWith("Alex"), "test player name does not start with Alex");
            helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(player.getUUID()) != null, "test player is not in the player list");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Survival is also a new player's default, so this checks the requested mode is really applied
    @GameTest
    public void testPlayerGetsRequestedGameMode(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Steve", GameType.CREATIVE, new BlockPos(1, 1, 1));
        try {
            helper.assertTrue(player.gameMode() == GameType.CREATIVE, "test player is not in creative");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A full run hands out over a thousand names and verify mode can hand out tens of thousands. Past 9,999 the count
    // needs a fifth digit, so the names from a long base and a short one are checked well beyond it. Each must also
    // survive the codec a revive paper's owner is stored with, which refuses an invalid name.
    @GameTest
    public void testPlayerNamesStayValidAndDistinct(GameTestHelper helper) {
        Set<String> names = new HashSet<>();
        for (String base : List.of("ThirtyCharactersOfBaseNameHere", "Short")) {
            AtomicInteger counter = new AtomicInteger();
            for (int i = 0; i < 12_000; i++) {
                String name = TestPlayers.uniqueName(base, counter);
                helper.assertTrue(StringUtil.isValidPlayerName(name), "not a valid player name: " + name);
                helper.assertTrue(names.add(name), "handed out twice: " + name);
                ResolvableProfile profile = ResolvableProfile.createResolved(new GameProfile(UUID.randomUUID(), name));
                helper.assertTrue(ResolvableProfile.CODEC.encodeStart(NbtOps.INSTANCE, profile).result()
                                .filter(tag -> tag instanceof CompoundTag compound && compound.contains("id"))
                                .isPresent(),
                        "a profile named " + name + " could not be stored with its id");
            }
        }
        helper.assertValueEqual(names.size(), 24_000, "distinct names");
        helper.succeed();
    }

    @GameTest
    public void modLoadsAndImmediateRespawnIsOn(GameTestHelper helper) {
        helper.assertTrue(FabricLoader.getInstance().isModLoaded("you-only-live-twice"), "mod not loaded");
        helper.assertTrue(helper.getLevel().getGameRules().get(GameRules.IMMEDIATE_RESPAWN), "immediate respawn is off");
        helper.succeed();
    }
}
