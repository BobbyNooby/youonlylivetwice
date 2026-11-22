package bobbynooby.dev.hardcore.client;

import bobbynooby.dev.hardcore.YoltHardcore;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * What the mod's client tests share: a real dedicated server in the client's process with the client joined to it,
 * commands typed into the chat as a player types them, the lines the client was shown, the death screen's buttons,
 * and screenshots. Nothing here comes from You Only Live Twice, which isn't loaded.
 */
final class ClientKit {
    static final int LONG_TIMEOUT = 200;
    // Long enough for a boss bar's fade and the field of view to settle before a shot
    static final int SETTLE_TICKS = 40;
    // A line over the hotbar shows for three seconds and fades in its last one, so its shot is taken well before the
    // fade, and it is gone after the three
    static final int HOTBAR_LINE_TICKS = 10;
    static final int HOTBAR_LINE_GONE_TICKS = 60;
    // A death screen's buttons wake 20 ticks after it opens, and a confirm screen's after its own delay
    private static final int BUTTON_TIMEOUT = 100;
    static final String RESPAWN = "deathScreen.respawn";
    // The death screen's way out, and the yes of the confirm screen it opens
    static final String TITLE_SCREEN = "deathScreen.titleScreen";
    // What the curse tells a player who comes back from a death that took one heart
    static final String LOST_A_HEART = "Curse of the Undead: −1 max heart.";
    // What /status reports for a player with nothing on them, with the settings as shipped
    static final List<String> STATUS_REPORT = List.of("Your body", "Server rules", "  Mending 0.4/XP (vanilla 2)",
            "  anvil: rename 1, repair 5, enchant 6");
    // What /yolt-hardcore and the start line say with the settings as shipped
    static final String FEATURES = "Hardcore features on: curse, mending, anvil.";
    static final float FULL_HEALTH = 20.0F;
    // One heart lost to the curse leaves nine
    static final float CURSED_HEALTH = 18.0F;

    // Not the mod's own logger, whose lines a test reads
    static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("YOLT Hardcore client tests");

    private record Shown(String text, boolean overlay) {}

    // Every system line the client was shown, in its chat or over its hotbar. A listener can't be taken off an event, so
    // it is added once for the run, and a check reads only what came after its mark.
    private static final List<Shown> SHOWN = new CopyOnWriteArrayList<>();

    static {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> SHOWN.add(new Shown(message.getString(), overlay)));
    }

    private ClientKit() {}

    /**
     * The server runs inside the client process, but it is a real dedicated server, with this mod and the tiny mods
     * inside its jar and nothing else of YOLT's. The client joins once the server is up.
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

    /** Where the lines the client has been shown end now, so a check reads only what comes after. */
    static int mark() {
        return SHOWN.size();
    }

    /** The chat lines the client was shown from the mark on. */
    static List<String> chat(int from) {
        return shown(from, false);
    }

    /** The lines put over the client's hotbar from the mark on. */
    static List<String> overlay(int from) {
        return shown(from, true);
    }

    private static List<String> shown(int from, boolean overlay) {
        List<Shown> all = List.copyOf(SHOWN);
        return all.subList(Math.min(from, all.size()), all.size()).stream()
                .filter(line -> line.overlay() == overlay).map(Shown::text).toList();
    }

    /** Waits until the client was shown this chat line after the mark. */
    static void waitForChat(ClientGameTestContext ctx, int from, String line) {
        waitOnClient(ctx, LONG_TIMEOUT, mc -> chat(from).contains(line),
                mc -> "the client's chat never showed \"" + line + "\", only " + chat(from));
    }

    /** Waits until the client was shown this line over its hotbar after the mark. */
    static void waitForOverlay(ClientGameTestContext ctx, int from, String line) {
        waitOnClient(ctx, LONG_TIMEOUT, mc -> overlay(from).contains(line),
                mc -> "the client's hotbar never showed \"" + line + "\", only " + overlay(from));
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

    /** Empties the chat on screen, so a shot of it shows only what comes next. The log of what was shown stays. */
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
        // A death leaves a puff of smoke on the spot, which is where the player respawns, right in front of the camera
        ctx.runOnClient(mc -> mc.particleEngine.clearParticles());
        // No counter prefix, so each file keeps its plain name from run to run
        ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
    }

    // Join toasts, such as the offline chat warning and the tutorial, would cover the top right of every shot
    private static void clearToasts(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.gui.toastManager().clear());
    }

    /**
     * Clicks the button a player would, once it can be clicked: Fabric's click presses a button whether or not it is
     * active, and a death screen's are asleep for its first second.
     */
    static void click(ClientGameTestContext ctx, String translationKey) {
        String label = Component.translatable(translationKey).getString();
        waitOnClient(ctx, BUTTON_TIMEOUT, mc -> button(mc, label).map(Button::isActive).orElse(false),
                mc -> button(mc, label).isPresent() ? "the \"" + label + "\" button on " + screenName(mc) + " never woke"
                        : "there is no \"" + label + "\" button on " + screenName(mc));
        ctx.clickScreenButton(translationKey);
    }

    /** Killed on the server, as any death is, and the client on its death screen. */
    static void die(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection) {
        server.runOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            player.kill(player.level());
            check(player.isDeadOrDying(), () -> "the client's player did not die");
        });
        waitOnClient(ctx, LONG_TIMEOUT, mc -> mc.gui.screen() instanceof DeathScreen,
                mc -> "the client is on " + screenName(mc) + ", not the death screen");
    }

    /** The death screen's Respawn, clicked once it wakes, and the client back in the world alive. */
    static void respawn(ClientGameTestContext ctx) {
        click(ctx, RESPAWN);
        waitOnClient(ctx, LONG_TIMEOUT, mc -> mc.gui.screen() == null && mc.player != null && !mc.player.isDeadOrDying(),
                mc -> "the client is on " + screenName(mc) + " after Respawn"
                        + (mc.player == null ? ", with no player" : ", with " + mc.player.getHealth() + " health"));
    }

    private static Optional<Button> button(Minecraft mc, String label) {
        Screen screen = mc.gui.screen();
        if (screen == null) {
            return Optional.empty();
        }
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals(label)).findFirst();
    }

    static String screenName(Minecraft mc) {
        Screen screen = mc.gui.screen();
        return screen == null ? "no screen" : screen.getClass().getSimpleName();
    }

    /** What vanilla answers a command a player may not use, as one that doesn't exist, in the client's language. */
    static String unknownCommand() {
        return Component.translatable("command.unknown.command").getString();
    }

    /** True when the client's own command tree, which the server sends it, has this command. */
    static boolean clientHasCommand(Minecraft mc, String name) {
        return mc.getConnection().getCommands().getRoot().getChild(name) != null;
    }

    /** Each line the mod's own logger logs while the task runs, which on a world's start is the server thread's. */
    static List<String> logged(Runnable task) {
        String name = YoltHardcore.LOGGER.getName();
        LoggerConfig config = ((Logger) LogManager.getLogger(name)).get();
        List<String> lines = new CopyOnWriteArrayList<>();
        // The appender joins the config the logger already uses, which other loggers may use too, so it keeps the
        // mod's lines only
        AbstractAppender appender = new AbstractAppender("yolt-hardcore-client-test", null, null, false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (name.equals(event.getLoggerName())) {
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

    // The same, while the condition has to hold on every one of the ticks
    static void holdOnClient(ClientGameTestContext ctx, int ticks, Predicate<Minecraft> condition,
                             Function<Minecraft, String> failure) {
        for (int tick = 0; tick <= ticks; tick++) {
            if (!ctx.computeOnClient(condition::test)) {
                int held = tick;
                throw explained(new AssertionError("held for " + held + " of " + ticks + " ticks"),
                        () -> ctx.computeOnClient(failure::apply));
            }
            if (tick < ticks) {
                ctx.waitTick();
            }
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
