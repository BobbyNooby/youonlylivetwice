package bobbynooby.dev.hardcore;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * For a test that waits across ticks. The hardcore tick reads the settings in use at the end of every server tick, so
 * such a test keeps its own settings in use from its start to its end, in a batch of its own, where no other test runs
 * under them. What it changed goes back once, newest first: when its last step ends, when a step fails, or on the tick
 * before it would time out, which fails it, whichever comes first. The settings go back last, after the players have
 * left.
 */
final class Held {
    private final GameTestHelper helper;
    private final List<Runnable> undo = new ArrayList<>();
    private final AtomicBoolean done = new AtomicBoolean();

    Held(GameTestHelper helper, HardcoreFile.Loaded settings) {
        this.helper = helper;
        HardcoreFile.Loaded before = YoltHardcore.swapForTesting(settings);
        undo.add(() -> YoltHardcore.swapForTesting(before));
        helper.runBeforeTestEnd(this::ranOutOfTime);
    }

    /** Held with the defaults and a change of the test's. */
    Held(GameTestHelper helper, Consumer<HardcoreConfig.HardcoreSection> change) {
        this(helper, HardcoreCommandGameTests.with(change));
    }

    /** Runs at the clean-up, before whatever was added before it. */
    void onCleanUp(Runnable task) {
        undo.add(task);
    }

    /** A player who leaves at the clean-up, by id, so one a respawn made leaves too. Loaded, so they can be hurt. */
    ServerPlayer join(String base, GameType mode, BlockPos relativePos) {
        ServerPlayer player = TestPlayers.join(helper, base, mode, relativePos);
        MinecraftServer server = helper.getLevel().getServer();
        UUID id = player.getUUID();
        onCleanUp(() -> TestPlayers.leave(server, id));
        TestPlayers.markLoaded(player);
        return player;
    }

    /**
     * A step that cleans up before its failure ends the test, since the steps that would have cleaned up never come. A
     * step after the clean-up does nothing: the test has failed already, but a sequence still runs, in the same tick,
     * the steps that follow a failed one, and those would reach for players who have left.
     */
    Runnable step(Runnable check) {
        return () -> {
            if (done.get()) {
                return;
            }
            try {
                check.run();
            } catch (RuntimeException | Error e) {
                cleanUpAfter(e);
                throw e;
            }
        };
    }

    /** The last step: its checks, the clean-up whether they pass or not, then the test passes. */
    Runnable last(Runnable check) {
        return () -> {
            if (done.get()) {
                return;
            }
            try {
                check.run();
            } catch (RuntimeException | Error e) {
                cleanUpAfter(e);
                throw e;
            }
            cleanUp();
            helper.succeed();
        };
    }

    /**
     * The clean-up after a failure, which the caller then throws: a clean-up that throws as well is added to the
     * failure, so the failure's own message is what the test reports.
     */
    void cleanUpAfter(Throwable failure) {
        try {
            cleanUp();
        } catch (RuntimeException | Error e) {
            failure.addSuppressed(e);
        }
    }

    // The tick before the timeout, with the last step still to come. The clean-up makes every step after it do nothing,
    // and a wait that held the test up may pass once the clean-up has run, as one for a blood moon to end does when the
    // clean-up stops it, so a sequence could reach its thenSucceed in this same tick and pass. Thrown from a task the
    // test scheduled, the failure fails the test, and a succeed after it changes nothing.
    private void ranOutOfTime() {
        RuntimeException timedOut = helper.assertionException("ran out of time before its last step");
        cleanUpAfter(timedOut);
        throw timedOut;
    }

    /**
     * Runs what was changed backwards, once. Every task runs even when one before it throws; the first to throw is
     * thrown at the end, with any later one added to it.
     */
    void cleanUp() {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        Throwable first = null;
        for (int i = undo.size() - 1; i >= 0; i--) {
            try {
                undo.get(i).run();
            } catch (RuntimeException | Error e) {
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
