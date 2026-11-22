package bobbynooby.dev.hardcore.client;

import bobbynooby.dev.hardcore.ClientBridge;
import bobbynooby.dev.hardcore.HardcoreStates;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static bobbynooby.dev.hardcore.client.ClientKit.check;

/**
 * The mod in a client's own single-player worlds, as a player who put the jar in their mods folder meets it. The first
 * world, with cheats on, starts the mod from the client's config/yolt-hardcore.json, which the run's first server start
 * wrote with the defaults, Loom having emptied the run folder before the run: /status answers, the owner may use
 * /yolt-hardcore, a death takes a heart, and the state file is in that world's folder. A second new world in the same
 * client, with cheats off, starts clean, has a state file of its own, and a death takes a heart there too, while
 * /yolt-hardcore is refused. Both worlds stay in the run folder's saves, to be looked into after a run.
 */
public class SingleplayerAloneClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        UUID self = ctx.computeOnClient(mc -> mc.getGameProfile().id());
        Path first = firstWorld(ctx, self);
        secondWorld(ctx, self, first);
    }

    private static Path firstWorld(ClientGameTestContext ctx, UUID self) {
        TestSingleplayerContext[] opened = new TestSingleplayerContext[1];
        List<String> logged = ClientKit.logged(() -> opened[0] = ctx.worldBuilder().adjustSettings(world -> {
            world.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
            world.setAllowCommands(true);
        }).create());
        try (TestSingleplayerContext world = opened[0]) {
            Path folder = world.getWorldSave().getSaveDirectory();
            TestServerContext server = world.getServer();
            check(logged.contains(ClientKit.FEATURES),
                    () -> "the world's start logged " + logged + ", not \"" + ClientKit.FEATURES + "\"");
            check(server.computeOnServer(s -> ClientBridge.active()), () -> "YOLT Hardcore isn't active in single-player");
            Path store = checkStoreIn(server, folder);
            check(!Files.exists(store), () -> store + " is there before anything was stored");
            checkClean(ctx, server, self, "the first world");

            ClientKit.clearChat(ctx);
            int mark = ClientKit.mark();
            ClientKit.type(ctx, world.getConnection(), "/status");
            ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT,
                    mc -> ClientKit.chat(mark).size() >= ClientKit.STATUS_REPORT.size(),
                    mc -> "the client's chat showed " + ClientKit.chat(mark) + " after /status");
            check(ClientKit.chat(mark).equals(ClientKit.STATUS_REPORT),
                    () -> "the client's chat showed " + ClientKit.chat(mark) + " for /status, not " + ClientKit.STATUS_REPORT);

            // The owner of a world with cheats on may use every command
            check(ctx.computeOnClient(mc -> ClientKit.clientHasCommand(mc, "yolt-hardcore")),
                    () -> "the owner's command tree has no /yolt-hardcore with cheats on");
            ClientKit.type(ctx, world.getConnection(), "/yolt-hardcore");
            ClientKit.waitForChat(ctx, mark, ClientKit.FEATURES);
            ClientKit.screenshotWithChat(ctx, "hardcore-alone-sp-commands");

            ClientKit.clearChat(ctx);
            int died = ClientKit.mark();
            ClientKit.die(ctx, server, world.getConnection());
            checkHeartTaken(ctx, server, self, died, store, "the first world");
            ClientKit.screenshot(ctx, "hardcore-alone-sp-first");
            // The client keeps a hotbar line through a change of world, so this one is let fade before the next opens
            ctx.waitTicks(ClientKit.HOTBAR_LINE_GONE_TICKS);
            ClientKit.clearChat(ctx);
            return store;
        }
    }

    private static void secondWorld(ClientGameTestContext ctx, UUID self, Path firstStore) {
        int opened = ClientKit.mark();
        try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(settings -> {
            settings.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
            settings.setAllowCommands(false);
        }).create()) {
            Path folder = world.getWorldSave().getSaveDirectory();
            TestServerContext server = world.getServer();
            check(!folder.equals(firstStore.getParent().getParent()), () -> "the second world opened in the first one's folder");
            check(server.computeOnServer(s -> ClientBridge.active()), () -> "YOLT Hardcore isn't active in the second world");
            Path store = checkStoreIn(server, folder);
            check(!store.equals(firstStore), () -> "the second world keeps its states in the first one's " + firstStore);
            check(!Files.exists(store), () -> store + " is there before anything was stored in the second world");
            check(Files.isRegularFile(firstStore), () -> "the first world's " + firstStore + " is gone");
            checkClean(ctx, server, self, "the second world");

            // The owner of a world with cheats off is no op
            check(!ctx.computeOnClient(mc -> ClientKit.clientHasCommand(mc, "yolt-hardcore")),
                    () -> "the owner's command tree has /yolt-hardcore with cheats off");
            ClientKit.clearChat(ctx);
            int mark = ClientKit.mark();
            ClientKit.type(ctx, world.getConnection(), "/yolt-hardcore");
            ClientKit.waitForChat(ctx, mark, ClientKit.unknownCommand());
            check(!ClientKit.chat(mark).contains(ClientKit.FEATURES),
                    () -> "the owner with cheats off was told " + ClientKit.chat(mark));
            ClientKit.type(ctx, world.getConnection(), "/status");
            ClientKit.waitForChat(ctx, mark, ClientKit.STATUS_REPORT.getLast());
            ClientKit.screenshotWithChat(ctx, "hardcore-alone-sp-cheats-off");
            // Nothing told of a heart here before a death here
            check(!ClientKit.overlay(opened).contains(ClientKit.LOST_A_HEART),
                    () -> "the second world told of a lost heart before any death there: " + ClientKit.overlay(opened));

            ClientKit.clearChat(ctx);
            int died = ClientKit.mark();
            ClientKit.die(ctx, server, world.getConnection());
            checkHeartTaken(ctx, server, self, died, store, "the second world");
            ClientKit.screenshot(ctx, "hardcore-alone-sp-second");
            ClientKit.clearChat(ctx);
        }
    }

    // The state file the mod uses is the one inside this world's own folder
    private static Path checkStoreIn(TestServerContext server, Path folder) {
        Path store = server.computeOnServer(ClientBridge::storeFile).toAbsolutePath().normalize();
        Path expected = folder.resolve("database/you-only-live-twice-hardcore.db").toAbsolutePath().normalize();
        check(store.equals(expected), () -> "the mod keeps this world's states in " + store + ", not " + expected);
        return store;
    }

    // A new world's player has lost nothing to the curse, whatever another world took
    private static void checkClean(ClientGameTestContext ctx, TestServerContext server, UUID self, String where) {
        check(server.computeOnServer(s -> HardcoreStates.read(self).curseHeartsLost) == 0,
                () -> "the curse has taken " + server.computeOnServer(s -> HardcoreStates.read(self).curseHeartsLost)
                        + " hearts in " + where + " before any death there");
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> mc.player.getMaxHealth() == ClientKit.FULL_HEALTH,
                mc -> "the client's maximum health is " + mc.player.getMaxHealth() + " in " + where + " before any death");
    }

    // On the death screen: the heart is stored in this world's file, and the respawn shows it and says so
    private static void checkHeartTaken(ClientGameTestContext ctx, TestServerContext server, UUID self, int died,
                                        Path store, String where) {
        check(server.computeOnServer(s -> HardcoreStates.read(self).curseHeartsLost) == 1,
                () -> "the death in " + where + " took " + server.computeOnServer(s -> HardcoreStates.read(self).curseHeartsLost)
                        + " hearts, not 1");
        check(Files.isRegularFile(store), () -> "no " + store + " after the death in " + where);
        ClientKit.respawn(ctx);
        ClientKit.waitOnClient(ctx, ClientKit.LONG_TIMEOUT, mc -> mc.player.getMaxHealth() == ClientKit.CURSED_HEALTH,
                mc -> "the client's maximum health is " + mc.player.getMaxHealth() + " after the respawn in " + where);
        ClientKit.waitForOverlay(ctx, died, ClientKit.LOST_A_HEART);
        ctx.waitTicks(ClientKit.HOTBAR_LINE_TICKS);
    }
}
