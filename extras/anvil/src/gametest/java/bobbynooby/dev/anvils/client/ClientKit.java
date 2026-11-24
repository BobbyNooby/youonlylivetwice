package bobbynooby.dev.anvils.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * What the mod's client tests share: a real dedicated server in the client's process with the client joined to it,
 * commands typed into the chat as a player types them and the lines the client was shown, an anvil opened with a real
 * right-click and the price its screen shows, and screenshots. Nothing here comes from You Only Live Twice or YOLT
 * Hardcore, neither of which is loaded.
 */
final class ClientKit {
    static final int LONG_TIMEOUT = 200;
    // What /yolt-anvils shows an op with the settings as shipped
    static final String PRICES = "Anvil prices: rename 1, repair 5, enchant 6; break chance 0.12";
    // The name the mod logs under, which TinyMod gives it
    private static final String MOD_LOGGER = "YOLT Anvils";
    private static final int USE_DISTANCE = 2;
    private static final float NORTH = 180.0F;
    private static final float LEVEL = 0.0F;
    private static final double AT_HOME = 0.01;

    // Not the mod's own logger, whose lines a test reads
    static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("YOLT Anvils client tests");

    // Every system line the client was shown in its chat. A listener can't be taken off an event, so it is added once
    // for the run, and a check reads only what came after its mark.
    private static final List<String> CHAT = new CopyOnWriteArrayList<>();

    static {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                CHAT.add(message.getString());
            }
        });
    }

    private ClientKit() {}

    /**
     * The server runs inside the client process, but it is a real dedicated server, with this mod and nothing of YOLT's.
     * The client joins once the server is up.
     */
    static void withServer(ClientGameTestContext ctx, BiConsumer<TestDedicatedServerContext, TestDedicatedServerConnection> body) {
        try (TestDedicatedServerContext server = ctx.worldBuilder().createServer()) {
            try (TestDedicatedServerConnection connection = server.connect()) {
                connection.waitForChunksRender();
                clearToasts(ctx);
                body.accept(server, connection);
            }
        }
    }

    /**
     * Puts an anvil two blocks north of the player, turns them to face it and opens it with the use key, as a player
     * right-clicks one. Returns where the anvil stands.
     */
    static BlockPos openAnvil(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection) {
        Vec3 home = server.computeOnServer(s -> connection.getServerPlayer().position());
        BlockPos anvil = server.computeOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            player.teleportTo(player.level(), home.x, home.y, home.z, Set.of(), NORTH, LEVEL, true);
            BlockPos at = player.blockPosition().relative(Direction.NORTH, USE_DISTANCE);
            player.level().setBlock(at, Blocks.ANVIL.defaultBlockState(), Block.UPDATE_ALL);
            return at;
        });
        waitOnClient(ctx, LONG_TIMEOUT, mc -> mc.player.position().distanceTo(home) < AT_HOME
                        && mc.level.getBlockState(anvil).getBlock() instanceof AnvilBlock,
                mc -> "the client is at " + mc.player.position() + " with " + mc.level.getBlockState(anvil)
                        + " at " + anvil.toShortString());
        ctx.getInput().lookAt(anvil);
        waitOnClient(ctx, LONG_TIMEOUT, mc -> mc.hitResult instanceof BlockHitResult hit
                        && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(anvil),
                mc -> "the client looks at " + mc.hitResult + ", not the anvil");
        ctx.getInput().pressKey(options -> options.keyUse);
        ctx.waitForScreen(AnvilScreen.class);
        waitOnServer(server, LONG_TIMEOUT, s -> connection.getServerPlayer().containerMenu instanceof AnvilMenu,
                s -> "the server has no anvil menu open for the client");
        return anvil;
    }

    /**
     * These two items in the open anvil's slots, put there on the server, which works the result out at once, and the
     * client's anvil showing the left one. The left item carries no enchantment, whose holders on the client aren't the
     * server's, so the client's copy compares equal to it.
     */
    static void put(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection, ItemStack left,
                    ItemStack right) {
        server.runOnServer(s -> {
            if (!(connection.getServerPlayer().containerMenu instanceof AnvilMenu menu)) {
                throw new AssertionError("the server has no anvil menu open for the client");
            }
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(left.copy());
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(right.copy());
        });
        waitOnClient(ctx, LONG_TIMEOUT, mc -> mc.player.containerMenu instanceof AnvilMenu menu
                        && ItemStack.isSameItemSameComponents(menu.getSlot(AnvilMenu.INPUT_SLOT).getItem(), left),
                mc -> "the client's anvil never got " + left);
    }

    /**
     * The anvil's name box emptied of the item's own name, as a player clears it, and this typed in its place. Each key
     * sends the server the new name.
     */
    static void typeName(ClientGameTestContext ctx, TestServerConnection connection, String text) {
        int length = ctx.computeOnClient(mc -> mc.player.containerMenu.getSlot(AnvilMenu.INPUT_SLOT).getItem()
                .getHoverName().getString().length());
        for (int i = 0; i < length; i++) {
            ctx.getInput().pressKey(InputConstants.KEY_BACKSPACE);
        }
        ctx.getInput().typeChars(text);
        connection.waitForServerboundPackets();
        connection.waitForClientboundPackets();
    }

    /**
     * The price the server worked out, as the client's own menu holds it and its screen draws it, with a result to take.
     * Checked again once every packet either way has arrived, so a price the server was still changing can't pass.
     */
    static void waitForPrice(ClientGameTestContext ctx, TestServerConnection connection, int price, String what) {
        Predicate<Minecraft> shown = mc -> mc.gui.screen() instanceof AnvilScreen
                && mc.player.containerMenu instanceof AnvilMenu menu && menu.getSlot(AnvilMenu.RESULT_SLOT).hasItem()
                && menu.getCost() == price;
        Function<Minecraft, String> failure = mc -> describe(mc) + " for " + what + ", not " + price;
        waitOnClient(ctx, LONG_TIMEOUT, shown, failure);
        connection.waitForServerboundPackets();
        connection.waitForClientboundPackets();
        check(ctx.computeOnClient(shown::test), () -> ctx.computeOnClient(failure::apply) + ", once every packet arrived");
    }

    /**
     * The click the anvil screen sends for a shift-click on the result, as a player takes it into their inventory, and
     * the server's answer back.
     */
    static void takeResult(ClientGameTestContext ctx, TestServerConnection connection) {
        ctx.runOnClient(mc -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, AnvilMenu.RESULT_SLOT,
                0, ContainerInput.QUICK_MOVE, mc.player));
        connection.waitForServerboundPackets();
        connection.waitForClientboundPackets();
    }

    /** Escape, if the anvil is still open, as a player shuts it. Closing hands the items in it back. */
    static void closeAnvil(ClientGameTestContext ctx, TestServerConnection connection) {
        if (ctx.computeOnClient(mc -> mc.gui.screen() instanceof AnvilScreen)) {
            ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
            ctx.waitForScreen(null);
            connection.waitForServerboundPackets();
        }
    }

    private static String describe(Minecraft mc) {
        if (!(mc.player.containerMenu instanceof AnvilMenu menu)) {
            return "the client's anvil menu closed";
        }
        return "the client's anvil shows " + menu.getSlot(AnvilMenu.RESULT_SLOT).getItem() + " at a price of " + menu.getCost();
    }

    /** Where the chat lines the client has been shown end now, so a check reads only what comes after. */
    static int mark() {
        return CHAT.size();
    }

    /** The chat lines the client was shown from the mark on. */
    static List<String> chat(int from) {
        List<String> all = List.copyOf(CHAT);
        return all.subList(Math.min(from, all.size()), all.size());
    }

    /** Waits until the client was shown this chat line after the mark. */
    static void waitForChat(ClientGameTestContext ctx, int from, String line) {
        waitOnClient(ctx, LONG_TIMEOUT, mc -> chat(from).contains(line),
                mc -> "the client's chat never showed \"" + line + "\", only " + chat(from));
    }

    /** Typed into the chat box and sent with Enter, as a player would, and the server's answer back. */
    static void type(ClientGameTestContext ctx, TestServerConnection connection, String line) {
        ctx.getInput().pressKey(options -> options.keyChat);
        ctx.waitForScreen(ChatScreen.class);
        ctx.getInput().typeChars(line);
        ctx.getInput().pressKey(InputConstants.KEY_RETURN);
        ctx.waitForScreen(null);
        connection.waitForServerboundPackets();
        connection.waitForClientboundPackets();
    }

    /** Empties the chat on screen, so a shot of it shows only what comes next. The record of what was shown stays. */
    static void clearChat(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
    }

    /** A shot with the chat open, as a player looks back over it. */
    static void screenshotWithChat(ClientGameTestContext ctx, String name) {
        ctx.getInput().pressKey(options -> options.keyChat);
        ctx.waitForScreen(ChatScreen.class);
        try {
            screenshot(ctx, name);
        } finally {
            ctx.setScreen(() -> null);
        }
    }

    static void screenshot(ClientGameTestContext ctx, String name) {
        clearToasts(ctx);
        // No counter prefix, so each file keeps its plain name from run to run
        ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
    }

    // Join toasts, such as the offline chat warning and the tutorial, would cover the top right of every shot
    private static void clearToasts(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.gui.toastManager().clear());
    }

    /** What vanilla answers a command a player may not use, as one that doesn't exist, in the client's language. */
    static String unknownCommand() {
        return Component.translatable("command.unknown.command").getString();
    }

    /** True when the client's own command tree, which the server sends it, has this command. */
    static boolean clientHasCommand(Minecraft mc, String name) {
        return mc.getConnection().getCommands().getRoot().getChild(name) != null;
    }

    /** True when the server's own dispatcher has this command, for anyone allowed it. Called on the server thread. */
    static boolean serverHasCommand(MinecraftServer server, String name) {
        return server.getCommands().getDispatcher().getRoot().getChild(name) != null;
    }

    /** Each line the mod's own logger logs while the task runs, which on a world's start is the server thread's. */
    static List<String> logged(Runnable task) {
        LoggerConfig config = ((Logger) LogManager.getLogger(MOD_LOGGER)).get();
        List<String> lines = new CopyOnWriteArrayList<>();
        // The appender joins the config the logger already uses, which other loggers may use too, so it keeps the
        // mod's lines only
        AbstractAppender appender = new AbstractAppender("yolt-anvils-client-test", null, null, false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (MOD_LOGGER.equals(event.getLoggerName())) {
                    lines.add(event.getMessage().getFormattedMessage());
                }
            }
        };
        appender.start();
        config.addAppender(appender, null, null);
        try {
            task.run();
        } finally {
            config.removeAppender(appender.getName());
            appender.stop();
        }
        return List.copyOf(lines);
    }

    static void check(boolean condition, Supplier<String> failure) {
        if (!condition) {
            throw new AssertionError(failure.get());
        }
    }

    // Like waitFor, but a timeout says what was wrong at the end, read on the server
    static void waitOnServer(TestServerContext server, int timeout, Predicate<MinecraftServer> condition,
                             Function<MinecraftServer, String> failure) {
        try {
            server.waitFor(condition, timeout);
        } catch (AssertionError e) {
            throw explained(e, () -> server.computeOnServer(failure::apply));
        }
    }

    static void waitOnClient(ClientGameTestContext ctx, int timeout, Predicate<Minecraft> condition,
                             Function<Minecraft, String> failure) {
        try {
            ctx.waitFor(condition, timeout);
        } catch (AssertionError e) {
            throw explained(e, () -> ctx.computeOnClient(failure::apply));
        }
    }

    // Reading what was wrong can fail too, say when the player it reads has gone. The wait's own failure is kept.
    private static AssertionError explained(AssertionError failed, Supplier<String> message) {
        try {
            return new AssertionError(message.get(), failed);
        } catch (RuntimeException | Error e) {
            failed.addSuppressed(e);
            return failed;
        }
    }

    /**
     * Runs every step even when one fails, so one broken clean-up step can't leave the rest undone for the tests after
     * it in this JVM. The first failure is thrown at the end, with any later ones attached.
     */
    static void runAll(List<Runnable> steps) {
        Throwable first = null;
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (RuntimeException | Error e) {
                LOGGER.error("A client test step failed", e);
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first instanceof RuntimeException e) {
            throw e;
        }
        if (first instanceof Error e) {
            throw e;
        }
    }
}
