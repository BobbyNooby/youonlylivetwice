package bobbynooby.dev.config;

import com.google.gson.annotations.SerializedName;

/** Mod settings. Field names are the JSON keys. */
public class YoltConfig {
    public int configVersion = 1;
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

    public static class WorldBorderSection {
        public boolean enabled = true;
        public int diameter = 10000;
        public double endScale = 2.0;
    }

    public static class DeathLockoutSection {
        public boolean enabled = true;
        public int cooldownDays = 7;
    }

    public static class GravesSection {
        public boolean enabled = true;
        public EnderChestMode enderChestOnDeath = EnderChestMode.KEEP;
    }

    public static class ReviveSection {
        public boolean enabled = true;
    }

    public static class ChatSection {
        public ChatMode mode = ChatMode.PROXIMITY;
        public int radius = 48;
        public boolean allowWhispers = false;
    }

    public static class HideSection {
        public boolean tabList = true;
        public boolean nametags = true;
        public boolean serverPlayerCount = true;
        public boolean gameMessages = true;
        public boolean tabHeader = true;
        public boolean distantPlayers = true;
        public boolean locatorBar = true;
    }

    public static class SleepSection {
        public boolean hideSleepers = true;
        public boolean noNightSkip = true;
        public boolean noBedRespawn = true;
    }

    public static class BodiesSection {
        public boolean enabled = true;
        public BodyType type = BodyType.MANNEQUIN;
        public BodyDuration duration = BodyDuration.OFFLINE;
        public BodyPose pose = BodyPose.PRONE;
        public boolean mobsAttack = true;
        public boolean movable = true;
        public boolean totems = true;
        public boolean armourWears = true;
        public boolean copyEffects = true;
    }

    public enum EnderChestMode {
        @SerializedName("keep") KEEP,
        @SerializedName("grave") GRAVE,
        @SerializedName("wipe") WIPE
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
}
