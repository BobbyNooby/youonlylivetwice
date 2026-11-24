package bobbynooby.dev.anvils.client;

import bobbynooby.dev.anvils.YoltAnvils;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;

import java.util.List;

import static bobbynooby.dev.anvils.client.ClientKit.check;

/**
 * The mod in a client's own single-player world, as a player who put the jar in their mods folder meets it. The world's
 * start reads the client's config/yolt-anvils.json, which the run's first server start wrote with the defaults, Loom
 * having emptied the run folder before the run. In a survival world with cheats on, the anvil screen shows the flat
 * price for an enchant and a rename, where vanilla would show 1 and 11, and /yolt-anvils answers the world's owner.
 */
public class SingleplayerAloneClientTest implements FabricClientGameTest {
    private static final String START_LINE = "YOLT Anvils on: rename 1, repair 5, enchant 6, break chance 0.12";
    private static final int LEVELS = 30;
    private static final int PRIOR_WORK = 10;
    private static final int ENCHANT = 6;
    private static final int RENAME = 1;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        TestSingleplayerContext[] opened = new TestSingleplayerContext[1];
        List<String> logged = ClientKit.logged(() -> opened[0] = ctx.worldBuilder().adjustSettings(world -> {
            world.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
            world.setAllowCommands(true);
        }).create());
        try (TestSingleplayerContext world = opened[0]) {
            TestServerContext server = world.getServer();
            TestServerConnection connection = world.getConnection();
            try {
                check(logged.contains(START_LINE), () -> "the world's start logged " + logged + ", not \"" + START_LINE + "\"");
                check(!YoltAnvils.hosted(), () -> "YOLT Anvils is hosted, but nothing here hosts it");
                check(server.computeOnServer(s -> connection.getServerPlayer().gameMode()) == GameType.SURVIVAL,
                        () -> "the owner isn't in survival, where the flat price counts");
                showFlatPrices(ctx, server, connection);
                showCommand(ctx, connection);
            } finally {
                ClientKit.runAll(List.of(
                        () -> ClientKit.closeAnvil(ctx, connection),
                        () -> ClientKit.clearChat(ctx)));
            }
        }
    }

    private static void showFlatPrices(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection) {
        server.runOnServer(s -> connection.getServerPlayer().setExperienceLevels(LEVELS));
        // The world is new and goes with the test, so the anvil is left standing
        ClientKit.openAnvil(ctx, server, connection);
        ItemStack book = server.computeOnServer(s -> EnchantmentHelper.createBook(new EnchantmentInstance(
                s.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 1)));
        ClientKit.put(ctx, server, connection, new ItemStack(Items.DIAMOND_SWORD), book);
        ClientKit.waitForPrice(ctx, connection, ENCHANT, "a sword enchanted from a book in single-player");

        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.REPAIR_COST, PRIOR_WORK);
        ClientKit.put(ctx, server, connection, sword, ItemStack.EMPTY);
        ClientKit.typeName(ctx, connection, "Frostbite");
        ClientKit.waitForPrice(ctx, connection, RENAME, "a rename of a sword with prior work " + PRIOR_WORK + " in single-player");
        ctx.getInput().setCursorPos(0, 0);
        ctx.waitTick();
        ClientKit.screenshot(ctx, "anvils-alone-sp-rename");
        ClientKit.closeAnvil(ctx, connection);
    }

    // The owner of a world with cheats on may use every command
    private static void showCommand(ClientGameTestContext ctx, TestServerConnection connection) {
        check(ctx.computeOnClient(mc -> ClientKit.clientHasCommand(mc, "yolt-anvils")),
                () -> "the owner's command tree has no /yolt-anvils with cheats on");
        ClientKit.clearChat(ctx);
        int mark = ClientKit.mark();
        ClientKit.type(ctx, connection, "/yolt-anvils");
        ClientKit.waitForChat(ctx, mark, ClientKit.PRICES);
        ClientKit.screenshotWithChat(ctx, "anvils-alone-sp-command");
    }
}
