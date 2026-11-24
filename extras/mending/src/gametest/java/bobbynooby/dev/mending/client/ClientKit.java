package bobbynooby.dev.mending.client;

import bobbynooby.dev.mending.ClientBridge;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * What the mod's client tests share: a real dedicated server in the client's process with the client joined to it, the
 * XP pickups both tests make with a Mending pickaxe in hand, and screenshots. Nothing here comes from You Only Live
 * Twice or YOLT Hardcore, neither of which is loaded.
 */
final class ClientKit {
    static final int LONG_TIMEOUT = 200;
    // The name the mod logs under, which TinyMod gives it
    private static final String MOD_LOGGER = "YOLT Mending";
    // Longer than a pickup's slot and XP updates take to reach the client, for a check that nothing more changes
    private static final int HOLD_TICKS = 20;
    private static final double CLOSE = 1.0E-9;
    private static final int WORN = 100;
    // A slot is 16 pixels across, from its x and y
    private static final int SLOT_MIDDLE = 8;

    // Not the mod's own logger, whose lines a test reads
    static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("YOLT Mending client tests");

    private ClientKit() {}

    /**
     * The server runs inside the client process, but it is a real dedicated server, with this mod and nothing of YOLT's.
     * The client joins once the server is up.
     */
    static void withServer(ClientGameTestContext ctx, BiConsumer<TestDedicatedServerContext, TestDedicatedServerConnection> body) {
        try (TestDedicatedServerContext server = ctx.worldBuilder().createServer()) {
            try (TestDedicatedServerConnection connection = server.connect()) {
                connection.waitForChunksRender();
                ctx.runOnClient(mc -> mc.gui.toastManager().clear());
                body.accept(server, connection);
            }
        }
    }

    /**
     * The pickups both tests make, at the rate as shipped, 0.4 durability a point, with a Mending pickaxe the only thing
     * the player holds and no XP before. Each orb appears on the player on the server and the player's own touch picks
     * it up; then the client holds the pickaxe's damage and the XP total the server sent it, and keeps them:
     * <ul>
     * <li>5 XP repair a pickaxe 100 worn by 2 points, where vanilla's 2 a point would repair 10, and none reach the bar;</li>
     * <li>1 XP repairs nothing and doesn't reach the bar either: its 0.4 is kept for the next pickup;</li>
     * <li>2 XP then repair 1 point, their 0.8 on top of the kept 0.4, where 0.8 alone would repair nothing; 0.2 is kept;</li>
     * <li>5 XP on a pickaxe 1 worn use 2 of their points to mend it, with the kept 0.2, and the other 3 reach the bar,
     * where vanilla would use 1 and give 4, and without the kept part 3 would be used.</li>
     * </ul>
     * The shots, named with the prefix, are of the pickaxe's durability after the first and of the XP bar after the last.
     */
    static void checkPickups(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection,
                             String shots) {
        check(server.computeOnServer(s -> connection.getServerPlayer().totalExperience) == 0,
                () -> "the player has XP before the first pickup");
        check(server.computeOnServer(s -> ClientBridge.carried(connection.getServerPlayer())) == 0.0,
                () -> "the mod keeps part of a point for the player before the first pickup");
        server.runOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
            pickaxe.enchant(s.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.MENDING), 1);
            pickaxe.setDamageValue(WORN);
            player.setItemSlot(EquipmentSlot.MAINHAND, pickaxe);
        });
        waitForGear(ctx, WORN, 0, "before the first pickup");

        pickUp(ctx, server, connection, 5, 98, 0, 0.0, "5 XP");
        screenshotPickaxe(ctx, shots + "-rate");
        pickUp(ctx, server, connection, 1, 98, 0, 0.4, "1 XP");
        pickUp(ctx, server, connection, 2, 97, 0, 0.2, "2 XP on top of the kept 0.4");

        server.runOnServer(s -> connection.getServerPlayer().getMainHandItem().setDamageValue(1));
        waitForGear(ctx, 1, 0, "with the pickaxe 1 worn");
        pickUp(ctx, server, connection, 5, 0, 3, 0.0, "5 XP on a pickaxe 1 worn");
        // The XP bar, which a tooltip would cover
        screenshot(ctx, shots + "-bar");
    }

    /** What checkPickups gave the player taken off again: the pickaxe and the XP. Called on the server thread. */
    static void takeBack(ServerPlayer player) {
        player.getInventory().clearContent();
        player.setExperienceLevels(0);
        player.setExperiencePoints(0);
        player.totalExperience = 0;
    }

    // An orb of this much XP on the player, picked up by their touch, and what the server and then the client hold after
    private static void pickUp(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection,
                               int xp, int damage, int total, double carried, String what) {
        ExperienceOrb orb = server.computeOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            ExperienceOrb made = new ExperienceOrb(player.level(), player.getX(), player.getY(), player.getZ(), xp);
            player.level().addFreshEntity(made);
            return made;
        });
        waitOnServer(server, LONG_TIMEOUT, s -> orb.isRemoved(), s -> "the player never picked up the orb of " + what);
        server.runOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            check(player.getMainHandItem().getDamageValue() == damage && player.totalExperience == total,
                    () -> "after " + what + " the server's pickaxe has " + player.getMainHandItem().getDamageValue()
                            + " damage and the player " + player.totalExperience + " XP, not " + damage + " and " + total);
            double kept = ClientBridge.carried(player);
            check(Math.abs(kept - carried) < CLOSE,
                    () -> "after " + what + " the mod keeps " + kept + " of a point, not " + carried);
        });
        waitForGear(ctx, damage, total, "after " + what);
    }

    // The client holds the pickaxe with this damage and this much XP, and still does a second later
    private static void waitForGear(ClientGameTestContext ctx, int damage, int total, String when) {
        Predicate<Minecraft> holds = mc -> mc.player.getMainHandItem().is(Items.DIAMOND_PICKAXE)
                && mc.player.getMainHandItem().getDamageValue() == damage && mc.player.totalExperience == total;
        Function<Minecraft, String> failure = mc -> "the client holds " + mc.player.getMainHandItem() + " with "
                + mc.player.getMainHandItem().getDamageValue() + " damage and " + mc.player.totalExperience + " XP "
                + when + ", not " + damage + " and " + total;
        waitOnClient(ctx, LONG_TIMEOUT, holds, failure);
        for (int tick = 0; tick < HOLD_TICKS; tick++) {
            ctx.waitTick();
            check(ctx.computeOnClient(holds::test), () -> ctx.computeOnClient(failure::apply));
        }
    }

    /**
     * The inventory open, as a player opens it, with the cursor on the held pickaxe and advanced tooltips on, so the
     * shot shows the durability the client holds in numbers. The option is put back without being saved.
     */
    private static void screenshotPickaxe(ClientGameTestContext ctx, String name) {
        boolean advancedWas = ctx.computeOnClient(mc -> mc.options.advancedItemTooltips);
        ctx.getInput().pressKey(options -> options.keyInventory);
        ctx.waitForScreen(InventoryScreen.class);
        try {
            ctx.runOnClient(mc -> mc.options.advancedItemTooltips = true);
            double[] cursor = ctx.computeOnClient(ClientKit::heldSlotOnWindow);
            ctx.getInput().setCursorPos(cursor[0], cursor[1]);
            waitOnClient(ctx, LONG_TIMEOUT, mc -> mc.gui.screen() instanceof InventoryScreen screen
                            && screen.getMenu().getCarried().isEmpty() && hovered(screen) != null
                            && hovered(screen).getItem().is(Items.DIAMOND_PICKAXE),
                    mc -> "the cursor isn't on the held pickaxe in the inventory");
            screenshot(ctx, name);
        } finally {
            ctx.runOnClient(mc -> mc.options.advancedItemTooltips = advancedWas);
            ctx.setScreen(() -> null);
        }
    }

    // The middle of the selected hotbar slot on the open inventory, in the window's pixels, which the cursor moves in
    private static double[] heldSlotOnWindow(Minecraft mc) {
        InventoryScreen screen = (InventoryScreen) mc.gui.screen();
        int selected = mc.player.getInventory().getSelectedSlot();
        Slot slot = screen.getMenu().slots.stream()
                .filter(candidate -> candidate.container == mc.player.getInventory() && candidate.getContainerSlot() == selected)
                .findFirst().orElseThrow(() -> new AssertionError("the inventory screen has no slot for the held item"));
        double scale = (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
        return new double[] {(screenField(screen, "leftPos") + slot.x + SLOT_MIDDLE) * scale,
                (screenField(screen, "topPos") + slot.y + SLOT_MIDDLE) * scale};
    }

    // Where the screen put its menu, which vanilla keeps to itself
    private static int screenField(AbstractContainerScreen<?> screen, String name) {
        try {
            Field field = AbstractContainerScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(screen);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("can't read the inventory screen's " + name, e);
        }
    }

    private static Slot hovered(AbstractContainerScreen<?> screen) {
        try {
            Field field = AbstractContainerScreen.class.getDeclaredField("hoveredSlot");
            field.setAccessible(true);
            return (Slot) field.get(screen);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("can't read the inventory screen's hovered slot", e);
        }
    }

    static void screenshot(ClientGameTestContext ctx, String name) {
        ctx.runOnClient(mc -> mc.gui.toastManager().clear());
        // No counter prefix, so each file keeps its plain name from run to run
        ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
    }

    /** Each line the mod's own logger logs while the task runs, which on a world's start is the server thread's. */
    static List<String> logged(Runnable task) {
        LoggerConfig config = ((Logger) LogManager.getLogger(MOD_LOGGER)).get();
        List<String> lines = new CopyOnWriteArrayList<>();
        // The appender joins the config the logger already uses, which other loggers may use too, so it keeps the
        // mod's lines only
        AbstractAppender appender = new AbstractAppender("yolt-mending-client-test", null, null, false, Property.EMPTY_ARRAY) {
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
