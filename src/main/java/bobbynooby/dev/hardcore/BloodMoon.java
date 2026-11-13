package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.StandIn;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The blood moon: on a night that rolls, the overworld's players are warned before dark, then a boss bar drains over
 * the night while more mobs spawn, and those that do are marked for good (see MobBoosts). One runs at a time, in the
 * overworld only. Whether a night rolls comes from BloodMoonRoll alone; everything sent goes to each overworld player
 * on their own connection, the same for everyone.
 */
public final class BloodMoon {
    public static final String LAST_DAY = "lastBloodMoonDay";
    // So a restart later the same night doesn't bring back one that ended early, as a frozen clock or /yolt stop can
    public static final String ENDED_DAY = "lastBloodMoonEndedDay";
    public static final String WARNING_TITLE = "The moon will rise red tonight…";
    public static final String WARNING_LINE = "A blood moon rises tonight. Find somewhere safe before dark.";
    public static final String RISEN_LINE = "The blood moon has risen. Mobs are stronger and more numerous until dawn.";
    public static final String UP_LINE = "A blood moon is up. Mobs are stronger and more numerous until dawn.";
    public static final String SET_LINE = "The blood moon has set.";
    private static final int STATUS_ORDER = 90;
    private static final long TICKS_PER_DAY = 24000L;
    // The bar is a rough clock, so once a second is plenty
    private static final int BAR_EVERY_TICKS = 20;
    // Vanilla's chance a mob rolls armour is 0.15 times the local difficulty multiplier (Mob.populateDefaultEquipmentSlots)
    private static final float VANILLA_ARMOUR_CHANCE = 0.15F;
    private static final long NO_DAY = Long.MIN_VALUE;

    /**
     * The night running now. It ends when the clock passes endClock or after ticks server ticks from startTick, so a
     * frozen clock can't stretch it; startTick is back-dated for a night joined partway, so the bar shows what is left.
     */
    private record Night(long day, long startClock, long endClock, long startTick, long ticks, @Nullable ServerBossEvent bar) {}

    // Server thread only, as is everything below
    private static @Nullable Night night;
    private static double left;
    // The day the warning went out for, and whether it still stands
    private static long warnedDay = NO_DAY;
    private static boolean warned;
    private static boolean loaded;
    private static long lastDay = -1;
    private static long endedDay = NO_DAY;
    private static @Nullable LongSupplier testClock;
    // Which level, and which player's nearby cap, the spawner is asking about, since the cap itself is read per category
    private static @Nullable ServerLevel spawningLevel;
    private static @Nullable ServerPlayer spawningNear;

    private BloodMoon() {}

    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> Guard.run(() -> tick(server), "Could not run the blood moon"));
        ServerPlayerEvents.JOIN.register(player -> Guard.run(() -> arrived(player),
                "Could not show the blood moon to {}", player.nameAndId().name()));
        ServerPlayerEvents.LEAVE.register(player -> Guard.run(() -> loggedOut(player),
                "Could not take the blood moon bar off {}", player.nameAndId().name()));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> Guard.run(() -> respawned(oldPlayer, newPlayer),
                "Could not show the blood moon to {}", newPlayer.nameAndId().name()));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> Guard.run(
                () -> changedLevel(player, destination), "Could not show the blood moon to {}", player.nameAndId().name()));
        // A night belongs to this server, not to the next one started in the same process
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> forget());
        Status.register(STATUS_ORDER, BloodMoon::statusLine);
    }

    /** True while a blood moon is up. There is only ever one, in the overworld. */
    public static boolean active() {
        return night != null;
    }

    /** True while one is up in this level. */
    public static boolean activeIn(Level level) {
        return night != null && level.dimension() == Level.OVERWORLD;
    }

    /** True from the warning until the blood moon ends. */
    public static boolean tonight() {
        return night != null || warned;
    }

    /** True while the blood moon must stop sleepers from skipping the night. */
    public static boolean blocksSkip() {
        return night != null && Settings.on(c -> c.hardcore.bloodMoon.enabled && !c.hardcore.bloodMoon.canSleepThrough);
    }

    /** How much of the night is left, 1 to 0, while one is up. */
    public static OptionalDouble left() {
        return night == null ? OptionalDouble.empty() : OptionalDouble.of(left);
    }

    /** What the boss bar shows, while one is up with a bar. */
    public static OptionalDouble barProgress() {
        Night current = night;
        return current == null || current.bar == null ? OptionalDouble.empty() : OptionalDouble.of(current.bar.getProgress());
    }

    /** Starts one now, for a night's length, whatever tonight's roll says. */
    public static void start(MinecraftServer server) {
        if (night != null || !Settings.on(c -> c.hardcore.bloodMoon.enabled)) {
            return;
        }
        HardcoreConfig.BloodMoonSection config = config();
        long now = clock(server);
        long length = config.endTick - config.startTick;
        begin(server, Math.floorDiv(now, TICKS_PER_DAY), now, now + length, length, false);
    }

    /** Ends the one that is up, as dawn would. */
    public static void stop(MinecraftServer server) {
        if (night != null) {
            end(server, true);
        }
    }

    /** The monster cap for a level: times spawnCap while a blood moon is up in it. */
    public static int monsterCap(ServerLevel level, int vanilla) {
        if (!activeIn(level) || !Settings.on(c -> c.hardcore.bloodMoon.enabled)) {
            return vanilla;
        }
        // Kept to the int range, so a huge multiplier can't wrap round
        return (int) Math.min(Integer.MAX_VALUE, Math.round(vanilla * config().spawnCap));
    }

    /**
     * The monster cap for the chunks round one player. A player body counts as a player for spawning, so a bigger cap
     * there would swarm every body of a player who logged out; unless the config says otherwise, it keeps vanilla's.
     */
    public static int monsterCapNear(ServerPlayer player, int vanilla) {
        if (StandIn.is(player) && !Settings.on(c -> c.hardcore.bloodMoon.capBoostNearPlayerBodies)) {
            return vanilla;
        }
        return monsterCap(player.level(), vanilla);
    }

    /** A category's cap as the spawner reads it, with no level at hand: it uses the one spawning now. */
    public static int spawnCap(MobCategory category, int vanilla) {
        if (category != MobCategory.MONSTER || night == null) {
            return vanilla;
        }
        ServerPlayer near = spawningNear;
        if (near != null) {
            return monsterCapNear(near, vanilla);
        }
        ServerLevel level = spawningLevel;
        return level == null ? vanilla : monsterCap(level, vanilla);
    }

    /** Called by the chunk spawner with the level it spawns in, and null when it is done. */
    public static void spawning(@Nullable ServerLevel level) {
        spawningLevel = level;
    }

    /** Called by the local cap check with the player whose nearby cap it reads, and null when it is done. */
    public static void spawningNear(@Nullable ServerPlayer player) {
        spawningNear = player;
    }

    /** Vanilla's armour roll is 0.15 times this multiplier; while one is up, the chance is at least armourChance. */
    public static float armourMultiplier(Mob mob, float multiplier) {
        return gearMultiplier(mob, multiplier, VANILLA_ARMOUR_CHANCE, config().armourChance);
    }

    /** Vanilla's enchant roll is chance times this multiplier; while one is up, the chance is at least enchantChance. */
    public static float enchantMultiplier(Mob mob, float chance, float multiplier) {
        return gearMultiplier(mob, multiplier, chance, config().enchantChance);
    }

    /** A marked mob's XP, times xp. */
    public static int experience(LivingEntity entity, int xp) {
        if (!marked(entity)) {
            return xp;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.round(xp * config().xp));
    }

    /**
     * Rolls a marked mob's own loot table again, extraLootRolls more times, when a player killed it. Only that table:
     * its worn gear and what it picked up drop once, from vanilla's own path.
     */
    public static void extraLoot(LivingEntity entity, ServerLevel level, DamageSource source, boolean playerKilled,
                                 Optional<ResourceKey<LootTable>> lootTable) {
        if (!playerKilled || lootTable.isEmpty() || !marked(entity)) {
            return;
        }
        for (int i = 0; i < config().extraLootRolls; i++) {
            entity.dropFromLootTable(level, source, true, lootTable.get());
        }
    }

    /** Stands in for the overworld's clock, for a test, which runs the returned task to give the real one back. */
    public static Runnable useClockForTesting(LongSupplier clock) {
        testClock = clock;
        return () -> testClock = null;
    }

    /** Forgets everything but the saved days, as a server restart does; the next tick picks the night up again. */
    public static void restartForTesting() {
        Night current = night;
        night = null;
        if (current != null && current.bar != null) {
            current.bar.removeAllPlayers();
        }
        warned = false;
        warnedDay = NO_DAY;
        loaded = false;
    }

    private static void tick(MinecraftServer server) {
        if (!Settings.on(c -> c.hardcore.bloodMoon.enabled)) {
            // Off means off: it stops at once, with no line, and the saved days stay for when it comes back on
            if (night != null) {
                end(server, false);
            }
            warned = false;
            return;
        }
        HardcoreConfig.BloodMoonSection config = config();
        long now = clock(server);
        Night current = night;
        if (current != null) {
            carryOn(server, current, now);
            return;
        }
        long day = Math.floorDiv(now, TICKS_PER_DAY);
        long time = Math.floorMod(now, TICKS_PER_DAY);
        // The clock moved on to another day, or past the night, without one starting
        if (warned && (day != warnedDay || time >= config.endTick)) {
            warned = false;
        }
        load();
        if (endedDay == day) {
            return;
        }
        // Started already today means a restart inside the night, which carries on and announces once more
        boolean resumed = lastDay == day;
        if (!resumed && !BloodMoonRoll.rolls(server.overworld().getSeed(), day, lastDay, config.chance,
                config.minNightsBetween, config.graceDays, config.fullMoonsOnly)) {
            return;
        }
        if (time >= config.startTick && time < config.endTick) {
            long dayStart = day * TICKS_PER_DAY;
            begin(server, day, dayStart + config.startTick, dayStart + config.endTick, config.endTick - config.startTick, resumed);
        } else if (time >= config.warnTick && time < config.startTick && warnedDay != day) {
            warnedDay = day;
            warned = true;
            warn(server, config.warning);
        }
    }

    private static void carryOn(MinecraftServer server, Night current, long now) {
        int tick = server.getTickCount();
        // Set back to before it began, or past its end, or a night's worth of ticks gone with the clock stopped
        if (now < current.startClock || now >= current.endClock || tick - current.startTick >= current.ticks) {
            end(server, true);
            return;
        }
        long elapsed = Math.max(now - current.startClock, tick - current.startTick);
        left = Math.max(0.0, 1.0 - (double) elapsed / current.ticks);
        if (current.bar != null && tick % BAR_EVERY_TICKS == 0) {
            current.bar.setProgress((float) left);
            syncBar(server, current.bar);
        }
    }

    private static void begin(MinecraftServer server, long day, long startClock, long endClock, long ticks, boolean resumed) {
        if (!resumed) {
            lastDay = day;
            HardcoreStates.setWorld(LAST_DAY, day);
        }
        long now = clock(server);
        long length = Math.max(ticks, 1L);
        // Back-dated by what the clock says has passed, so a night joined partway ends on time with the clock stopped
        long startTick = server.getTickCount() - Math.max(0L, now - startClock);
        left = Math.max(0.0, 1.0 - (double) (server.getTickCount() - startTick) / length);
        ServerBossEvent bar = config().bossBar ? newBar((float) left) : null;
        night = new Night(day, startClock, endClock, startTick, length, bar);
        warned = false;
        Component line = Component.literal(resumed ? UP_LINE : RISEN_LINE);
        for (ServerPlayer player : overworldPlayers(server)) {
            if (bar != null) {
                bar.addPlayer(player);
            }
            player.sendSystemMessage(line);
        }
    }

    private static void end(MinecraftServer server, boolean announce) {
        Night ended = night;
        if (ended == null) {
            return;
        }
        night = null;
        if (ended.bar != null) {
            ended.bar.removeAllPlayers();
        }
        if (!announce) {
            return;
        }
        endedDay = ended.day;
        HardcoreStates.setWorld(ENDED_DAY, ended.day);
        Component line = Component.literal(SET_LINE);
        for (ServerPlayer player : overworldPlayers(server)) {
            player.sendSystemMessage(line);
        }
    }

    // Each part its own packet on the player's own connection: a sound played in the world would tell anyone near
    // where the player is
    private static void warn(MinecraftServer server, HardcoreConfig.WarningSection warning) {
        for (ServerPlayer player : overworldPlayers(server)) {
            if (warning.title) {
                player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
                player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(WARNING_TITLE)));
            }
            if (warning.chat) {
                player.sendSystemMessage(Component.literal(WARNING_LINE));
            }
            if (warning.sound) {
                player.connection.send(new ClientboundSoundPacket(SoundEvents.RAID_HORN, SoundSource.AMBIENT,
                        player.getX(), player.getY(), player.getZ(), 1.0F, 1.0F, player.getRandom().nextLong()));
            }
        }
    }

    private static ServerBossEvent newBar(float progress) {
        HardcoreConfig.BloodMoonSection config = config();
        ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("Blood Moon"), BossEvent.BossBarColor.RED,
                BossEvent.BossBarOverlay.PROGRESS);
        bar.setDarkenScreen(config.darkenSky);
        bar.setCreateWorldFog(config.fog);
        bar.setProgress(progress);
        return bar;
    }

    // Puts the bar on whoever is in the overworld and takes it off anyone who isn't, in case a hook was missed
    private static void syncBar(MinecraftServer server, ServerBossEvent bar) {
        for (ServerPlayer player : List.copyOf(bar.getPlayers())) {
            if (server.getPlayerList().getPlayer(player.getUUID()) != player || player.level().dimension() != Level.OVERWORLD) {
                bar.removePlayer(player);
            }
        }
        for (ServerPlayer player : overworldPlayers(server)) {
            bar.addPlayer(player);
        }
    }

    // Joined, or came into the overworld, during one
    private static void arrived(ServerPlayer player) {
        Night current = night;
        if (current == null || StandIn.is(player) || player.level().dimension() != Level.OVERWORLD) {
            return;
        }
        if (current.bar != null) {
            current.bar.addPlayer(player);
        }
        player.sendSystemMessage(Component.literal(UP_LINE));
    }

    private static void changedLevel(ServerPlayer player, ServerLevel destination) {
        Night current = night;
        if (current == null) {
            return;
        }
        if (destination.dimension() == Level.OVERWORLD) {
            arrived(player);
        } else if (current.bar != null) {
            current.bar.removePlayer(player);
        }
    }

    private static void loggedOut(ServerPlayer player) {
        Night current = night;
        if (current != null && current.bar != null) {
            current.bar.removePlayer(player);
        }
    }

    // A respawn makes a new player object on the same connection: the old one comes off, the new one goes on
    private static void respawned(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
        Night current = night;
        if (current == null) {
            return;
        }
        if (current.bar != null) {
            current.bar.removePlayer(oldPlayer);
        }
        if (oldPlayer.level().dimension() != Level.OVERWORLD) {
            arrived(newPlayer);
        } else if (current.bar != null && newPlayer.level().dimension() == Level.OVERWORLD) {
            current.bar.addPlayer(newPlayer);
        }
    }

    private static Optional<Component> statusLine(ServerPlayer player) {
        if (!Settings.on(c -> c.hardcore.bloodMoon.enabled) || !tonight() || StandIn.is(player)
                || player.level().dimension() != Level.OVERWORLD) {
            return Optional.empty();
        }
        return Optional.of(Status.line("Blood moon tonight",
                "Mobs that spawn tonight are tougher, better geared and more numerous, and drop more. It ends at dawn."));
    }

    // Only for a mob that would be marked as it loads: gear is rolled before the mob is added to the world
    private static float gearMultiplier(Mob mob, float multiplier, float vanillaChance, double floor) {
        if (vanillaChance <= 0.0F || !activeIn(mob.level()) || !Settings.on(c -> c.hardcore.bloodMoon.enabled)
                || !MobBoosts.eligible(mob)) {
            return multiplier;
        }
        return Math.max(multiplier, (float) (floor / vanillaChance));
    }

    private static boolean marked(LivingEntity entity) {
        return entity instanceof Mob && entity.entityTags().contains(MobBoosts.BLOOD_MOON_MARK)
                && Settings.on(c -> c.hardcore.bloodMoon.enabled);
    }

    // Who is sent the warning, the bar and the lines: every player in the overworld, and nothing else about them
    private static List<ServerPlayer> overworldPlayers(MinecraftServer server) {
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!StandIn.is(player) && player.level().dimension() == Level.OVERWORLD) {
                players.add(player);
            }
        }
        return players;
    }

    private static long clock(MinecraftServer server) {
        LongSupplier stand = testClock;
        return stand != null ? stand.getAsLong() : server.overworld().getOverworldClockTime();
    }

    private static void load() {
        if (loaded) {
            return;
        }
        lastDay = HardcoreStates.world(LAST_DAY).orElse(-1L);
        endedDay = HardcoreStates.world(ENDED_DAY).orElse(NO_DAY);
        loaded = true;
    }

    private static void forget() {
        restartForTesting();
        testClock = null;
        spawningLevel = null;
        spawningNear = null;
        lastDay = -1;
        endedDay = NO_DAY;
    }

    private static HardcoreConfig.BloodMoonSection config() {
        return Settings.get().hardcore.bloodMoon;
    }
}
