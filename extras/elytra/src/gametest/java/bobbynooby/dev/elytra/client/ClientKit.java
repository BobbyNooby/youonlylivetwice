package bobbynooby.dev.elytra.client;

import bobbynooby.dev.elytra.ClientBridge;
import bobbynooby.dev.elytra.ElytraSettings;
import bobbynooby.dev.elytra.YoltElytra;
import bobbynooby.dev.elytra.mixin.FireworkRocketInvoker;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * What the mod's client tests share: a real dedicated server in the client's process with the client joined to it, the
 * glides both tests make, and screenshots. Nothing here comes from You Only Live Twice or YOLT Hardcore, neither of
 * which is loaded.
 * <p>
 * A glide starts as a player starts one: the client is put in the air with an elytra on, on the server, and its own
 * jump key is pressed there, so the client starts gliding and tells the server, which starts the glide on its side.
 * The client then flies itself, as a real client always does.
 */
final class ClientKit {
    static final int LONG_TIMEOUT = 200;
    // The name the mod logs under, which TinyMod gives it
    private static final String MOD_LOGGER = "YOLT Elytra";
    private static final double CLOSE = 1.0E-9;
    private static final double MULTIPLIER = ElytraSettings.DEFAULTS.glideGravityMultiplier();
    private static final double CUTOFF = ElytraSettings.DEFAULTS.rocketCutoffSpeed();
    private static final double TICKS_PER_SECOND = 20.0;
    // Blocks above the ground: a glide nose down from the low height lands within seconds, and one level from the high
    // height has room for the rockets
    private static final int LOW = 10;
    private static final int HIGH = 40;
    private static final float NORTH = 180.0F;
    private static final float LEVEL = 0.0F;
    private static final float NOSE_DOWN = 30.0F;
    private static final int LANDING_TIMEOUT = 600;
    private static final int ROCKETS = 5;
    // The shipped glide gravity with no rocket cut-off, for the vanilla rocket the others are measured against
    private static final ElytraSettings NO_CUTOFF = new ElytraSettings(true, MULTIPLIER, 0.0);
    // A plain rocket lasts at least 20 ticks on the server, 10 for each of its 1 + 1 flight steps, before it explodes. The
    // client has it from the tick after it is fired to the tick after it ends, which can put it a tick either way.
    private static final int ROCKET_LEAST_LIFE = 20;
    // The cut-off ends a rocket fired from a slow glide 4 ticks after it reaches the client: its second push takes the
    // client past 20 m/s, the server sees that move a packet later and ends the rocket, and the client sees it go a tick
    // after. Twice that leaves room for a tick of lag and still fails a cut-off that lets a rocket push for long.
    private static final int ROCKET_ENDS_WITHIN = 8;
    private static final int FOLLOW_TICKS = 60;
    // Longer than a use takes to reach the server and its rocket, if any, to reach the client
    private static final int HOLD_TICKS = 20;
    // An attached rocket sits on the player, who may have moved since its last tick
    private static final double ROCKET_REACH = 8.0;

    // Not the mod's own logger, whose lines a test reads
    static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("YOLT Elytra client tests");

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
     * A glide nose down from 10 blocks up, with the settings as shipped: the gravity the client's own flight reads is 0.6
     * of its base, with the mod's modifier on it, from just after the glide starts until it lands, and its base again
     * once it has landed. Before the glide it is the base.
     */
    static void checkGlide(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection, Vec3 home,
                           String shot) {
        server.runOnServer(s -> connection.getServerPlayer().setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA)));
        waitOnClient(ctx, LONG_TIMEOUT, mc -> mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA),
                mc -> "the client wears " + mc.player.getItemBySlot(EquipmentSlot.CHEST) + ", not the elytra");
        check(ctx.computeOnClient(mc -> gravityIs(mc, false)),
                () -> ctx.computeOnClient(ClientKit::gravity) + " before the glide");

        startGlide(ctx, server, connection, home, LOW, NOSE_DOWN);
        // A glide this low lands before the wait is over, so a failure tells of the gravity it landed with
        waitOnClient(ctx, LONG_TIMEOUT, mc -> gravityIs(mc, true),
                mc -> gravity(mc) + ", never the glide's in the " + LONG_TIMEOUT + " ticks from the glide's start");
        server.runOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            double expected = player.getAttributeBaseValue(Attributes.GRAVITY) * MULTIPLIER;
            double gravity = player.getAttributeValue(Attributes.GRAVITY);
            check(Math.abs(gravity - expected) < CLOSE,
                    () -> "the server's glider has the gravity " + gravity + ", not " + expected);
        });
        thirdPersonShot(ctx, shot);

        // Every tick until the server's word that the glide is over reaches the client, the glide's gravity holds. The
        // gravity is read on the same tick as the glide, so a gravity that went first can't pass.
        int ticks = 0;
        while (true) {
            int tick = ticks;
            String broken = ctx.computeOnClient(mc -> mc.player.isFallFlying() && !gravityIs(mc, true) ? gravity(mc) : "");
            check(broken.isEmpty(), () -> broken + " " + tick + " ticks into the glide");
            if (!ctx.computeOnClient(mc -> mc.player.isFallFlying())) {
                break;
            }
            check(++ticks < LANDING_TIMEOUT, () -> "the client still glides after " + LANDING_TIMEOUT + " ticks");
            ctx.waitTick();
        }
        int glided = ticks;
        check(ctx.computeOnClient(mc -> mc.player.onGround()),
                () -> "the client stopped gliding off the ground, at " + ctx.computeOnClient(mc -> mc.player.position()));
        check(!server.computeOnServer(s -> connection.getServerPlayer().isFallFlying()), () -> "the server's player still glides");
        waitOnClient(ctx, LONG_TIMEOUT, mc -> gravityIs(mc, false), mc -> gravity(mc) + " after it landed");
        LOGGER.info("The client glided {} ticks with the glide's gravity, landed at {} with {} health", glided,
                ctx.computeOnClient(mc -> mc.player.position()), ctx.computeOnClient(mc -> mc.player.getHealth()));
    }

    /**
     * Rockets in level glides from 40 blocks up. First the vanilla rocket, with the cut-off off: fired from a slow glide
     * it pushes the client for a plain rocket's life, past the cut-off's speed. With the cut-off as shipped swapped back
     * in and the client past it, the next rocket isn't fired: none is used up, no rocket reaches the client, and the
     * client, whose own copy of a rocket is what pushes it, goes no faster. Then, in a new slow glide with the cut-off as
     * shipped, a rocket is fired and used up and the server ends it within ROCKET_ENDS_WITHIN ticks. How fast the client
     * got by then is logged but not checked: the rule promises the rocket ends, and a mod that held the speed down would
     * keep to it too.
     */
    static void checkRockets(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection,
                             Vec3 home, String shot) {
        server.runOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.FIREWORK_ROCKET, ROCKETS));
        });
        waitForRockets(ctx, ROCKETS, "before the glide");

        Runnable shipped = server.computeOnServer(s -> ClientBridge.use(NO_CUTOFF));
        try {
            startGlide(ctx, server, connection, home, HIGH, LEVEL);
            waitOnClient(ctx, LONG_TIMEOUT, mc -> gravityIs(mc, true), mc -> gravity(mc) + " while it glides");
            Push vanilla = fireFromSlowGlide(ctx, server, connection, "with no cut-off");
            check(vanilla.life() >= ROCKET_LEAST_LIFE - 1, () -> "with no cut-off the rocket pushed the client for "
                    + vanilla.life() + " ticks, not a plain rocket's life: " + vanilla.samples());
            check(vanilla.top() > CUTOFF, () -> "with no cut-off the rocket took the client to " + vanilla.top()
                    + " m/s, not past the cut-off's " + CUTOFF + ": " + vanilla.samples());
            waitForRockets(ctx, ROCKETS - 1, "after the rocket with no cut-off");
        } finally {
            server.runOnServer(s -> shipped.run());
        }

        checkRefused(ctx, server, connection);

        // A new glide, slow again, so the rocket below starts under the cut-off as the vanilla one did
        land(ctx, server, connection, home);
        startGlide(ctx, server, connection, home, HIGH, LEVEL);
        waitOnClient(ctx, LONG_TIMEOUT, mc -> gravityIs(mc, true), mc -> gravity(mc) + " while it glides");
        Push cut = fireFromSlowGlide(ctx, server, connection, "with the cut-off as shipped");
        check(cut.life() <= ROCKET_ENDS_WITHIN, () -> "with the cut-off as shipped the rocket pushed the client for "
                + cut.life() + " ticks, not " + ROCKET_ENDS_WITHIN + " or fewer: " + cut.samples());
        waitForRockets(ctx, ROCKETS - 2, "after the rocket with the cut-off as shipped");
        thirdPersonShot(ctx, shot);
    }

    // How long a rocket was on the client, from the tick it arrived to the tick it was gone, the fastest the client went
    // meanwhile, and its speed each tick
    private record Push(int life, double top, List<String> samples) {}

    // The use key with rockets in hand, the client gliding slower than the cut-off, and the client followed tick by tick
    // until the rocket that reached it has gone: its speed, and whether a rocket is on it. The samples are logged, so a
    // run's numbers can be read whether or not it passes.
    private static Push fireFromSlowGlide(ClientGameTestContext ctx, TestServerContext server,
                                         TestServerConnection connection, String when) {
        // The server judges the speed from the client's last moves, which a new glide takes a tick or two to send
        waitOnServer(server, LONG_TIMEOUT, s -> !YoltElytra.pastCutoff(connection.getServerPlayer()),
                s -> "the server sees the client past the cut-off before the rocket " + when);
        double before = ctx.computeOnClient(ClientKit::speed);
        check(before < CUTOFF, () -> "the client glides at " + before + " m/s before the rocket " + when);
        ctx.getInput().pressKey(options -> options.keyUse);
        List<String> samples = new ArrayList<>();
        int arrived = -1;
        int gone = -1;
        double top = before;
        for (int tick = 1; tick <= FOLLOW_TICKS && gone < 0; tick++) {
            ctx.waitTick();
            boolean pushed = ctx.computeOnClient(ClientKit::rocketOn);
            double speed = ctx.computeOnClient(ClientKit::speed);
            samples.add(String.format(Locale.ROOT, "%d:%.2f%s", tick, speed, pushed ? "R" : ""));
            top = Math.max(top, speed);
            if (pushed && arrived < 0) {
                arrived = tick;
            } else if (!pushed && arrived >= 0) {
                gone = tick;
            }
        }
        LOGGER.info("The client's speed in m/s each tick after the rocket {}, from {}, R while a rocket is on it: {}",
                when, before, samples);
        int came = arrived;
        int went = gone;
        check(came >= 0, () -> "no rocket reached the client after its use key " + when + ": " + samples);
        check(went >= 0, () -> "the rocket " + when + " still pushed the client " + FOLLOW_TICKS + " ticks after its use key: "
                + samples);
        return new Push(went - came, top, List.copyOf(samples));
    }

    // With the cut-off as shipped and the client past it, the use key fires nothing: no rocket reaches the client, the
    // client goes no faster, and none is used up, on the client or the server
    private static void checkRefused(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection) {
        // The state the refusal depends on, not the refusal itself, which the use key below tries
        check(server.computeOnServer(s -> YoltElytra.pastCutoff(connection.getServerPlayer())),
                () -> "the server doesn't see the client past the cut-off at " + ctx.computeOnClient(ClientKit::speed)
                        + " m/s");
        double at = ctx.computeOnClient(ClientKit::speed);
        ctx.getInput().pressKey(options -> options.keyUse);
        List<String> after = new ArrayList<>();
        for (int tick = 1; tick <= HOLD_TICKS; tick++) {
            ctx.waitTick();
            int now = tick;
            double speed = ctx.computeOnClient(ClientKit::speed);
            after.add(String.format(Locale.ROOT, "%d:%.2f", tick, speed));
            check(!ctx.computeOnClient(ClientKit::rocketOn), () -> "a rocket reached the client " + now
                    + " ticks after its use key past the cut-off: " + after);
            check(speed <= at, () -> "the client sped up from " + at + " to " + speed + " m/s after its use key past the "
                    + "cut-off: " + after);
        }
        LOGGER.info("The client's speed in m/s each tick after its use key past the cut-off, from {}: {}", at, after);
        waitForRockets(ctx, ROCKETS - 1, "after the use key past the cut-off");
        server.runOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            check(player.getMainHandItem().getCount() == ROCKETS - 1, () -> "the server's player has "
                    + player.getMainHandItem().getCount() + " rockets after the use key past the cut-off, not " + (ROCKETS - 1));
            check(!rocketOn(player.level(), player), () -> "the server has a rocket on the player after the use key past "
                    + "the cut-off");
        });
    }

    // Back on the ground at home, which ends the glide, rockets and elytra kept
    private static void land(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection,
                             Vec3 home) {
        server.runOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            player.teleportTo(player.level(), home.x, home.y, home.z, Set.of(), NORTH, LEVEL, true);
        });
        waitOnServer(server, LONG_TIMEOUT, s -> !connection.getServerPlayer().isFallFlying(),
                s -> "the server's player still glides after it was put on the ground");
        waitOnClient(ctx, LONG_TIMEOUT, mc -> !mc.player.isFallFlying() && mc.player.onGround(),
                mc -> "the client still glides after it was put on the ground, at " + mc.player.position());
    }

    /**
     * What the glides gave the player taken off again, back on the ground at home with nothing on, and the client no
     * longer gliding.
     */
    static void takeBack(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection, Vec3 home) {
        server.runOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            player.getInventory().clearContent();
            player.teleportTo(player.level(), home.x, home.y, home.z, Set.of(), NORTH, LEVEL, true);
        });
        waitOnServer(server, LONG_TIMEOUT, s -> !connection.getServerPlayer().isFallFlying(),
                s -> "the server's player still glides after the clean-up");
        waitOnClient(ctx, LONG_TIMEOUT, mc -> !mc.player.isFallFlying() && gravityIs(mc, false),
                mc -> gravity(mc) + " after the clean-up");
    }

    // The player put this many blocks above home, facing north at this pitch, and the jump key held over a tick in the
    // air with the elytra on, as a player starts a glide. The client reads the key as it ticks and starts on the tick it
    // goes down.
    private static void startGlide(ClientGameTestContext ctx, TestServerContext server, TestServerConnection connection,
                                   Vec3 home, int height, float pitch) {
        server.runOnServer(s -> {
            ServerPlayer player = connection.getServerPlayer();
            player.teleportTo(player.level(), home.x, home.y + height, home.z, Set.of(), NORTH, pitch, true);
        });
        waitOnClient(ctx, LONG_TIMEOUT, mc -> mc.player.getY() > home.y + height / 2.0 && !mc.player.onGround(),
                mc -> "the client is at " + mc.player.position() + ", not in the air " + height + " blocks over " + home);
        ctx.getInput().holdKey(options -> options.keyJump);
        try {
            ctx.waitTicks(2);
        } finally {
            ctx.getInput().releaseKey(options -> options.keyJump);
        }
        waitOnClient(ctx, LONG_TIMEOUT, mc -> mc.player.isFallFlying(),
                mc -> "the client doesn't glide after its jump key, at " + mc.player.position());
        waitOnServer(server, LONG_TIMEOUT, s -> connection.getServerPlayer().isFallFlying(),
                s -> "the server's player doesn't glide after the client's jump key");
    }

    // The gravity the client's own flight reads, and the mod's modifier among its attribute's: on, at the multiplier as
    // shipped, while it glides, and off with the gravity at its base when not
    private static boolean gravityIs(Minecraft mc, boolean gliding) {
        AttributeInstance gravity = mc.player.getAttribute(Attributes.GRAVITY);
        AttributeModifier modifier = gravity.getModifier(YoltElytra.GRAVITY_MODIFIER);
        double base = gravity.getBaseValue();
        if (!gliding) {
            return modifier == null && Math.abs(mc.player.getGravity() - base) < CLOSE;
        }
        return mc.player.isFallFlying() && modifier != null && Math.abs(modifier.amount() - (MULTIPLIER - 1.0)) < CLOSE
                && modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
                && Math.abs(mc.player.getGravity() - base * MULTIPLIER) < CLOSE;
    }

    private static String gravity(Minecraft mc) {
        AttributeInstance gravity = mc.player.getAttribute(Attributes.GRAVITY);
        return "the client " + (mc.player.isFallFlying() ? "glides" : "doesn't glide") + " with the gravity "
                + mc.player.getGravity() + " of a base " + gravity.getBaseValue() + ", modifier "
                + gravity.getModifier(YoltElytra.GRAVITY_MODIFIER);
    }

    // Horizontal metres per second, as the client moves itself
    private static double speed(Minecraft mc) {
        return mc.player.getDeltaMovement().horizontalDistance() * TICKS_PER_SECOND;
    }

    private static boolean rocketOn(Minecraft mc) {
        return rocketOn(mc.level, mc.player);
    }

    // A rocket stuck to this player, which boosts them each tick it is there
    private static boolean rocketOn(Level level, Player player) {
        return !level.getEntitiesOfClass(FireworkRocketEntity.class, player.getBoundingBox().inflate(ROCKET_REACH),
                rocket -> !rocket.isRemoved() && ((FireworkRocketInvoker) rocket).yoltElytraIsAttachedToEntity()).isEmpty();
    }

    private static void waitForRockets(ClientGameTestContext ctx, int count, String when) {
        waitOnClient(ctx, LONG_TIMEOUT, mc -> mc.player.getMainHandItem().is(Items.FIREWORK_ROCKET)
                        && mc.player.getMainHandItem().getCount() == count,
                mc -> "the client holds " + mc.player.getMainHandItem() + " " + when + ", not " + count + " rockets");
    }

    // From behind, so the shot shows the player and the elytra; the camera is put back as it was
    private static void thirdPersonShot(ClientGameTestContext ctx, String name) {
        CameraType was = ctx.computeOnClient(mc -> mc.options.getCameraType());
        ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
        try {
            ctx.waitTick();
            screenshot(ctx, name);
        } finally {
            ctx.runOnClient(mc -> mc.options.setCameraType(was));
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
        AbstractAppender appender = new AbstractAppender("yolt-elytra-client-test", null, null, false, Property.EMPTY_ARRAY) {
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
