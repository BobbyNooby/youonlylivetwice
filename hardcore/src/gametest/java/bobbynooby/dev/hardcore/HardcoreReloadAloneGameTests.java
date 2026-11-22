package bobbynooby.dev.hardcore;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static bobbynooby.dev.hardcore.HardcoreTestKit.assertClose;
import static bobbynooby.dev.hardcore.HardcoreTestKit.assertSlow;

/**
 * /yolt-hardcore reload with no YOLT on the server, when the file it reads switches features off that a player feels
 * now. The hardcore tick reads the settings in use at the end of every server tick, so the test holds its own in a
 * batch of its own, where no other test runs under them or reads the file it writes.
 */
public class HardcoreReloadAloneGameTests {
    private static final String RELOAD = "yolt-hardcore-gametest:reload_alone";
    private static final BlockPos AT = new BlockPos(1, 1, 1);
    // Weight weighs again every 10 ticks, plus 2 for the slowdown to follow
    private static final int SETTLE = 12;
    // 12 stacks of cobblestone weigh 36, which the slowdown's steps make 0.1
    private static final int LOAD_STACKS = 12;
    // Under the 0.25 below which a group is low
    private static final double LOW_VITAMINS = 0.1;

    // The weight slowdown, the curse's lost heart and low vitamins' slower mining are each on the player, then a reload
    // of a file that switches the three off takes each off within a tick or two. The curse's heart stays stored, so
    // switching it on again would put it back.
    @GameTest(environment = RELOAD, maxTicks = 60)
    public void aReloadThatSwitchesFeaturesOffTakesThemOffAPlayer(GameTestHelper helper) {
        Path file = YoltHardcore.configFile();
        @Nullable String original = HardcoreCommandGameTests.read(file);
        Held held = new Held(helper, c -> {
            c.weight.enabled = true;
            c.nutrients.enabled = true;
        });
        try {
            // The file as it was, or gone if it wasn't there; Held puts the settings in use back after it
            held.onCleanUp(() -> {
                if (original == null) {
                    delete(file);
                } else {
                    HardcoreCommandGameTests.write(file, original);
                }
            });
            ServerPlayer player = held.join("Reloaded", GameType.SURVIVAL, AT);
            UUID id = player.getUUID();
            held.onCleanUp(() -> {
                Curse.set(id, 0);
                player.getInventory().clearContent();
            });
            helper.assertTrue(Host.settings().curse.enabled, "the curse is off before the reload");
            for (int i = 0; i < LOAD_STACKS; i++) {
                player.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
            }
            Curse.set(id, 1);
            HardcoreStates.update(id, state -> {
                state.protein = 1.0;
                state.vitamins = LOW_VITAMINS;
                state.sugar = 1.0;
            });
            helper.runAfterDelay(SETTLE, held.step(() -> {
                assertSlow(helper, player, 0.1, "with the load on");
                helper.assertValueEqual(player.getMaxHealth(), 18.0F, "the max health with a heart lost");
                AttributeModifier mining = miningModifier(player);
                helper.assertTrue(mining != null, "no slower mining with low vitamins");
                assertClose(helper, mining.amount(), -0.15, "the slower mining");

                HardcoreCommandGameTests.write(file, """
                        {"hardcore": {"weight": {"enabled": false}, "curse": {"enabled": false},
                         "nutrients": {"enabled": false}}}""");
                helper.assertValueEqual(TestPlayers.console(helper, "yolt-hardcore reload", false, 1),
                        List.of("Reloaded yolt-hardcore.json."), "the reload's reply");
                HardcoreConfig.HardcoreSection now = Host.settings();
                helper.assertFalse(now.weight.enabled || now.curse.enabled || now.nutrients.enabled,
                        "the reload left weight, the curse or nutrients on");
            }));
            helper.runAfterDelay(SETTLE + 2, held.last(() -> {
                assertSlow(helper, player, 0.0, "after a reload switched weight off");
                helper.assertValueEqual(player.getMaxHealth(), 20.0F, "the max health after a reload switched the curse off");
                helper.assertTrue(miningModifier(player) == null, "slower mining after a reload switched nutrients off");
                helper.assertValueEqual(HardcoreStates.read(id).curseHeartsLost, 1, "the hearts lost, as stored");
            }));
        } catch (RuntimeException | Error e) {
            held.cleanUpAfter(e);
            throw e;
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static @Nullable AttributeModifier miningModifier(ServerPlayer player) {
        AttributeInstance mining = Objects.requireNonNull(player.getAttribute(Attributes.BLOCK_BREAK_SPEED),
                "a player without a mining speed");
        return mining.getModifier(Nutrients.MODIFIER);
    }
}
