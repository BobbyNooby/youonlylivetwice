package bobbynooby.dev.config;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/** Mod settings. Field names are the JSON keys; @Applies marks a key whose change doesn't take effect at once. */
public class YoltConfig {
    // A fresh file is written at the version this build reads
    public int configVersion = ConfigLoader.CURRENT_VERSION;
    public WorldBorderSection worldBorder = new WorldBorderSection();
    public DeathLockoutSection deathLockout = new DeathLockoutSection();
    public GravesSection graves = new GravesSection();
    public ReviveSection revive = new ReviveSection();
    public ChatSection chat = new ChatSection();
    public HideSection hide = new HideSection();
    public boolean restrictCommands = true;
    public boolean netherScaleOneToOne = true;
    public boolean randomSpawn = true;
    public SleepSection sleep = new SleepSection();
    public boolean immediateRespawn = true;
    public BodiesSection bodies = new BodiesSection();
    public ServerListSection serverList = new ServerListSection();
    public SkinsSection skins = new SkinsSection();

    public static class WorldBorderSection {
        public boolean enabled = true;
        public int diameter = 10000;
        public double endScale = 2.0;
    }

    public static class DeathLockoutSection {
        public boolean enabled = true;
        @Applies(Applies.When.NEW_DEATHS) public int cooldownDays = 7;
        // Off: a death in creative or spectator makes no lockout, grave or death record, and the player keeps their items
        @Applies(Applies.When.NEW_DEATHS) public boolean countCreativeAndSpectatorDeaths = false;
    }

    public static class GravesSection {
        // Graves already there stay either way
        @Applies(Applies.When.NEW_DEATHS) public boolean enabled = true;
        @Applies(Applies.When.NEW_DEATHS) public EnderChestMode enderChestOnDeath = EnderChestMode.KEEP;
        // How often graves in loaded chunks get a missing head put back; 0 turns that off
        public int recheckSeconds = 5;
        // Read each time the dead player is told, so a change reaches earlier deaths too
        public TellLocation tellLocation = TellLocation.WHEN_MOVED;
        // How far from the death spot, in blocks, a grave must be for whenMoved to tell the dead player
        public int tellLocationDistance = 16;
        // A compass to the death's grave, given when the owner is next back in and alive. A death while it was on is
        // still owed one after it is turned off.
        @Applies(Applies.When.NEW_DEATHS) public boolean compass = false;
        // How far out, in blocks, a new grave looks for safe ground when nothing close to the death is safe, such as in
        // the middle of a lava lake. Graves already placed stay where they are.
        public int searchRadius = 16;
        // How far out, in blocks, a death over the void looks for land when the owner has no last ground there, before
        // the grave goes to a fixed spot such as the world spawn
        public int voidSearchRadius = 16;
    }

    public static class ReviveSection {
        public boolean enabled = true;
    }

    public static class ChatSection {
        public ChatMode mode = ChatMode.PROXIMITY;
        public int radius = 48;
        public boolean allowWhispers = false;
        // How close, in blocks, a whisper's target must be; 0 uses radius. Global chat has no range for either.
        public int whisperRadius = 0;
    }

    public static class HideSection {
        public boolean tabList = true;
        public boolean nametags = true;
        public boolean serverPlayerCount = true;
        public boolean gameMessages = true;
        public boolean tabHeader = true;
        // Each player's tab-list header is one of these, picked at random, under a row of dashes; § colour codes work.
        // A reload gives a player whose line is gone a new one. An empty list shows no header.
        public List<String> tabHeaderLines = new ArrayList<>(List.of(
                "\u00a79The cold atmosphere of a new land... it fills you with determination.",
                "\u00a7cYou scream out for help, but no one answers.",
                "\u00a78You are surrounded by darkness. You cannot see anything.",
                "\u00a7bThe horizon stretches far ahead, and you wonder what awaits.",
                "\u00a7aThe map is incomplete. Where will you go next?",
                "\u00a7eThe path ahead is unclear, but your journey must continue.",
                "\u00a7dEvery step into the unknown feels like a new discovery.",
                "\u00a76A new land lies before you. What secrets will it reveal?",
                "\u00a7cThe world is vast and uncharted. Adventure calls.",
                "\u00a77Who knows what lies beyond the next ridge?",
                "\u00a7fWith every sunrise, the unknown becomes a little clearer.",
                "\u00a72You're not sure what you'll find, but you're ready to explore.",
                "\u00a73The world is filled with possibilities... and dangers."));
        public boolean distantPlayers = true;
        public boolean locatorBar = true;
        public boolean globalSounds = true;
        public boolean mapPlayers = true;
        // Vanilla's reduced F3 for every player: no coordinates, biome, light or targeted block, though the position inside
        // the current 16-block section still shows. Admin view gives an op the full screen back.
        public boolean debugScreen = true;
        // F3 names the server "vanilla" rather than "fabric". The brand is sent as a player logs in. Fabric's channel
        // registration and ping at login still show a client that logs packets it is Fabric.
        @Applies(Applies.When.NEXT_JOIN) public boolean serverBrand = false;
        // A plain compass points north in every dimension, not at the world spawn. Recovery, lodestone and grave compasses
        // keep their own targets. Admin view gives an op the real spawn.
        public boolean compassSpawn = true;
    }

    public static class SleepSection {
        public boolean hideSleepers = true;
        public boolean noNightSkip = true;
        public boolean noBedRespawn = true;
    }

    public static class BodiesSection {
        // Bodies already standing stay either way
        @Applies(Applies.When.NEW_BODIES) public boolean enabled = true;
        @Applies(Applies.When.NEW_BODIES) public BodyType type = BodyType.MANNEQUIN;
        public BodyDuration duration = BodyDuration.OFFLINE;
        @Applies(Applies.When.NEW_BODIES) public BodyPose pose = BodyPose.PRONE;
        public boolean mobsAttack = true;
        @Applies(Applies.When.NEW_BODIES) public boolean movable = true;
        @Applies(Applies.When.NEW_BODIES) public boolean totems = true;
        public boolean armourWears = true;
        public boolean copyEffects = true;
        // Vanilla /kill leaves bodies standing; /yolt kill <player> is the way to kill one on purpose
        public boolean ignoreVanillaKill = true;
    }

    public static class ServerListSection {
        // One line per hover entry; § colour codes work. An empty list keeps vanilla's sample
        public List<String> hoverLines = new ArrayList<>(List.of("You scream out for help, but no one answers."));
        // The slot count the server list shows while hide.serverPlayerCount hides the real ones. The online count stays 0.
        public int shownMaxPlayers = 1;
    }

    public static class SkinsSection {
        // A player gets their skin as they log in, so one already online keeps theirs until they rejoin
        @Applies(Applies.When.NEXT_JOIN) public SkinMode mode = SkinMode.AUTO;
        public int lookupTimeoutSeconds = 5;
        public int refreshHours = 24;
        public boolean allowPlayerChoice = true;
        // A non-op can't pick the skin of a player who has joined this server, or one another player picked. A pick of a
        // skin whose owner joined later goes at its holder's next login.
        public boolean refuseKnownPlayers = true;
        // A non-op picks once; after that only an op changes it, with /yolt skin set <player> <name>
        public boolean lockAfterFirstChoice = false;
    }

    public enum EnderChestMode {
        @SerializedName("keep") KEEP,
        @SerializedName("grave") GRAVE,
        @SerializedName("wipe") WIPE
    }

    // Whether the dead player is told where their grave is
    public enum TellLocation {
        @SerializedName("never") NEVER,
        @SerializedName("whenMoved") WHEN_MOVED,
        @SerializedName("always") ALWAYS
    }

    public enum ChatMode {
        @SerializedName("proximity") PROXIMITY,
        @SerializedName("self_only") SELF_ONLY,
        @SerializedName("global") GLOBAL
    }

    public enum BodyType {
        @SerializedName("mannequin") MANNEQUIN,
        @SerializedName("player") PLAYER
    }

    public enum BodyPose {
        @SerializedName("prone") PRONE,
        @SerializedName("sleeping") SLEEPING,
        @SerializedName("standing") STANDING
    }

    public enum SkinMode {
        @SerializedName("auto") AUTO,
        @SerializedName("off") OFF
    }
}
