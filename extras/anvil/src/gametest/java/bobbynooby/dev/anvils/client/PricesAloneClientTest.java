package bobbynooby.dev.anvils.client;

import bobbynooby.dev.anvils.AnvilSettings;
import bobbynooby.dev.anvils.ClientBridge;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

import static bobbynooby.dev.anvils.client.ClientKit.check;

/**
 * A real client's anvil screen on a server with only YOLT Anvils, with the prices as shipped: an enchant shows 6, a
 * repair 5 and a rename 1, where vanilla would show 1, 1 and 11 for the same items, and taking the enchant's result costs
 * the client 6 levels. The same player in creative is shown vanilla's 1 for the repair.
 */
public class PricesAloneClientTest implements FabricClientGameTest {
    private static final int LEVELS = 30;
    // Vanilla prices a rename at 1 plus the item's prior work; the flat price is the rename cost alone
    private static final int PRIOR_WORK = 10;
    private static final int VANILLA_REPAIR = 1;
    private static final String NEW_NAME = "Frostbite";
    // The prices as shipped, with no chance the anvil wears down between the uses, so one anvil serves them all
    private static final AnvilSettings SHIPPED_PRICES = new AnvilSettings(true, AnvilSettings.DEFAULTS.enchantCost(),
            AnvilSettings.DEFAULTS.repairCost(), AnvilSettings.DEFAULTS.renameCost(), 0.0);

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ClientKit.withServer(ctx, (server, connection) -> {
            GameType modeWas = server.computeOnServer(s -> connection.getServerPlayer().gameMode());
            int levelsWas = server.computeOnServer(s -> connection.getServerPlayer().experienceLevel);
            BlockPos[] anvil = new BlockPos[1];
            // Last before the try, so nothing can fail between the swap and what puts it back
            Runnable putBack = server.computeOnServer(s -> ClientBridge.use(SHIPPED_PRICES));
            try {
                check(modeWas == GameType.SURVIVAL, () -> "the client's player is in " + modeWas + ", not survival");
                server.runOnServer(s -> connection.getServerPlayer().setExperienceLevels(LEVELS));
                ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> mc.player.experienceLevel == LEVELS,
                        mc -> "the client has " + mc.player.experienceLevel + " levels, not " + LEVELS);
                anvil[0] = ClientKit.openAnvil(ctx, server, connection);

                ItemStack book = server.computeOnServer(PricesAloneClientTest::sharpnessBook);
                ClientKit.put(ctx, server, connection, new ItemStack(Items.DIAMOND_SWORD), book);
                ClientKit.waitForPrice(ctx, connection, SHIPPED_PRICES.enchantCost(), "a sword enchanted from a book");
                shot(ctx, "anvils-alone-enchant");
                ClientKit.takeResult(ctx, connection);
                int left = LEVELS - SHIPPED_PRICES.enchantCost();
                ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> mc.player.experienceLevel == left,
                        mc -> "the client has " + mc.player.experienceLevel + " levels after taking the enchant, not " + left);
                int serverLevels = server.computeOnServer(s -> connection.getServerPlayer().experienceLevel);
                check(serverLevels == left,
                        () -> "the server's player has " + serverLevels + " levels after the enchant, not " + left);

                ClientKit.put(ctx, server, connection, wornPickaxe(), new ItemStack(Items.DIAMOND));
                ClientKit.waitForPrice(ctx, connection, SHIPPED_PRICES.repairCost(), "a pickaxe repaired with a diamond");
                shot(ctx, "anvils-alone-repair");

                ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                sword.set(DataComponents.REPAIR_COST, PRIOR_WORK);
                ClientKit.put(ctx, server, connection, sword, ItemStack.EMPTY);
                ClientKit.typeName(ctx, connection, NEW_NAME);
                ClientKit.waitForPrice(ctx, connection, SHIPPED_PRICES.renameCost(),
                        "a rename of a sword with prior work " + PRIOR_WORK);
                shot(ctx, "anvils-alone-rename");

                // Creative keeps vanilla's anvil: the same repair as above at vanilla's price
                server.runOnServer(s -> connection.getServerPlayer().setGameMode(GameType.CREATIVE));
                ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> mc.player.isCreative(),
                        mc -> "the client isn't in creative");
                ClientKit.put(ctx, server, connection, wornPickaxe(), new ItemStack(Items.DIAMOND));
                ClientKit.waitForPrice(ctx, connection, VANILLA_REPAIR, "a creative player's repair");
                shot(ctx, "anvils-alone-creative");
            } finally {
                ClientKit.runAll(List.of(
                        () -> ClientKit.closeAnvil(ctx, connection),
                        () -> server.runOnServer(s -> {
                            ServerPlayer player = connection.getServerPlayer();
                            player.closeContainer();
                            // Closing the anvil hands its items back
                            player.getInventory().clearContent();
                            player.setGameMode(modeWas);
                            player.setExperienceLevels(levelsWas);
                            if (anvil[0] != null) {
                                player.level().setBlock(anvil[0], Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                            }
                        }),
                        () -> server.runOnServer(s -> putBack.run())));
            }
        });
    }

    // Off the menu, so no slot is highlighted
    private static void shot(ClientGameTestContext ctx, String name) {
        ctx.getInput().setCursorPos(0, 0);
        ctx.waitTick();
        ClientKit.screenshot(ctx, name);
    }

    // Made on the server, whose registries hold the enchantment
    private static ItemStack sharpnessBook(MinecraftServer server) {
        return EnchantmentHelper.createBook(new EnchantmentInstance(
                server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 1));
    }

    // A diamond pickaxe a hundred points worn, which one diamond repairs in full
    private static ItemStack wornPickaxe() {
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        pickaxe.setDamageValue(100);
        return pickaxe;
    }
}
