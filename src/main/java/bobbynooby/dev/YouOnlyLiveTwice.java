package bobbynooby.dev;

import bobbynooby.dev.bodies.Bodies;
import bobbynooby.dev.chat.ChatDelivery;
import bobbynooby.dev.chat.Whispers;
import bobbynooby.dev.commands.YoltCommands;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.core.Yolt;
import bobbynooby.dev.graves.GraveCompass;
import bobbynooby.dev.graves.GraveNotice;
import bobbynooby.dev.graves.Graves;
import bobbynooby.dev.lockout.DeathLockout;
import bobbynooby.dev.revive.Revive;
import bobbynooby.dev.skins.Skins;
import bobbynooby.dev.visibility.AdminView;
import bobbynooby.dev.visibility.DebugPrivacy;
import bobbynooby.dev.visibility.NameTagTeam;
import bobbynooby.dev.visibility.NorthCompass;
import bobbynooby.dev.visibility.TabHeaders;
import bobbynooby.dev.world.LiveSettings;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

// Loads in any environment so client tests can host a dedicated server in the client process;
// every feature checks Settings.active(), so single-player stays vanilla.
public final class YouOnlyLiveTwice implements ModInitializer {
    // Fabric runs each event's listeners in the order they were registered, so this order is load-bearing
    @Override
    public void onInitialize() {
        // Settings: every hook below reads the config, and SERVER_STARTING decides whether YOLT is active at all
        Settings.load();
        ServerLifecycleEvents.SERVER_STARTING.register(Settings::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPED.register(Settings::onServerStopped);
        // The config's settings and border: the first SERVER_STARTED hook, so the nametag team exists before the bodies
        // come back and join it
        ServerLifecycleEvents.SERVER_STARTED.register(YouOnlyLiveTwice::onServerStarted);
        // AdminView: registers its saved flag, which must happen before any player save is read
        AdminView.init();
        // Commands: built on each command registration, and their one hook, a LEAVE that forgets a skin pick's wait,
        // touches nothing another hook reads, so their place here doesn't matter
        YoltCommands.register();
        // DeathLockout: its JOIN, which kicks a player who died while joining, must run before the body handover in Bodies.
        // It gets the graves' notice from here, so its kick and login refusal can say where the grave is.
        DeathLockout.init(GraveNotice::line, GraveNotice::forget);
        // Graves: before Revive, since the first use-block hook to act wins the click
        Graves.init();
        // Revive: after Graves, so the grave's use-block hook opens a clicked grave before a revive head in hand is tried
        Revive.init();
        // Visibility, the compass and chat: their hooks don't share an event with a hook that cares about order
        TabHeaders.init();
        NameTagTeam.init();
        DebugPrivacy.init();
        // The compass keeps its target inside the world's edge as the player's client sees it, shifted by their offset
        NorthCompass.init(player -> CoordinateOffsets.offset(player).x());
        // Coordinate offset: its hooks are on the connection, not on an event, apart from the border recheck at the end of
        // a tick, which reads nothing another hook writes. Admin view is handed in, so an admin's next frame can be real,
        // and so is the north compass's target, whose Z is the same for everyone.
        CoordinateOffsets.init(AdminView::isOn, NorthCompass::isNorthTarget);
        ChatDelivery.init();
        Whispers.init();
        // Skins: its hooks make the Mojang lookup when a server starts and note who joins, which no other hook reads
        Skins.init();
        // Bodies: late, so the handover follows the lockout check, and the bodies come back once the team is there
        Bodies.init();
        // Grave compass: after Bodies, so a body taken over at the join has set the player's gear before the compass goes in
        GraveCompass.init();

        Yolt.LOGGER.info("You Only Live Twice started");
    }

    /**
     * Applies the config to a server that has started. The border check comes first, so a saved border that would strand
     * the world spawn, the End platform, a body or a grave is held rather than applied. Nobody is online yet, so the
     * visibility refresh misses no one.
     */
    public static void onServerStarted(MinecraftServer server) {
        YoltCommands.holdStrandingBorder(server);
        LiveSettings.applyAll(server);
    }
}
