package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.core.Yolt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Cold: out in the open where it is cold enough to snow, a hidden chill rises, and heat, shelter and leather bring it
 * down. It shows through vanilla's own frost, slows the player inside the shared budget and, once full, freezes them.
 * The chill lives in the hardcore store, so a relog keeps it; a death clears it.
 */
public final class Cold {
    static final TagKey<Item> HELD_HEAT = TagKey.create(Registries.ITEM, Yolt.id("held_heat"));
    static final TagKey<Block> HEAT_SOURCES = TagKey.create(Registries.BLOCK, Yolt.id("heat_sources"));
    private static final int STATUS_ORDER = 60;
    private static final int TICKS_PER_SECOND = 20;
    private static final float FREEZE_HIT = 1.0F;
    // Freeze damage stops here unless it may kill: half a heart, as starvation stops on Normal
    private static final float FLOOR = 1.0F;

    private record Surroundings(boolean coldHere, boolean sheltered, boolean heldHeat, boolean nearHeat, boolean wet,
                                boolean inWater, int leatherPieces) {}

    // Ticks since the last once-a-second step, then seconds spent fully frozen, by player object, so a respawn starts
    // afresh. Server thread only.
    private static final Map<ServerPlayer, int[]> TIMERS = new WeakHashMap<>();

    private Cold() {}

    static void init() {
        Slowdowns.register("cold", Cold::slowShare);
        Status.register(STATUS_ORDER, Cold::statusLine);
        Hardcore.onTick(Cold::tick);
        // A new player object after a respawn or a change shows its chill at once
        Hardcore.onRefresh(Cold::showChill);
        Hardcore.onExempt(TIMERS::remove);
        // A new life, whatever the game mode was
        Hardcore.onDeath((uuid, mode) -> {
            if (on()) {
                HardcoreStates.update(uuid, state -> state.chill = 0.0);
            }
        });
    }

    /** True in the overworld where the biome is cold enough to snow at this height. */
    public static boolean coldSpot(ServerLevel level, BlockPos pos) {
        return level.dimension() == Level.OVERWORLD && level.getBiome(pos).value().coldEnoughToSnow(pos, level.getSeaLevel());
    }

    /** True for a player whose freezing comes from our chill, not from something stronger such as powder snow. */
    public static boolean chills(LivingEntity entity) {
        if (!(entity instanceof ServerPlayer player) || !counts(player)) {
            return false;
        }
        double chill = HardcoreStates.of(player).chill;
        return chill > 0.0 && ColdMaths.frozenTicks(chill) >= player.getTicksFrozen();
    }

    /** True where a random spawn must not land while cold is on. */
    public static boolean refusesSpawn(ServerLevel level, BlockPos pos) {
        return on() && coldSpot(level, pos);
    }

    /**
     * Vanilla's own freeze hit on a chilled player: none with freeze damage off, and with canKill off only what keeps
     * them at half a heart. Powder snow's own freezing keeps vanilla's hit.
     */
    public static float freezeHit(LivingEntity entity, float amount) {
        if (!(entity instanceof ServerPlayer player) || !chills(player)) {
            return amount;
        }
        if (!cfg().freezeDamage) {
            return 0.0F;
        }
        return floored(player, amount);
    }

    /**
     * Raises the player's frozen ticks to what their chill shows. Runs every tick after vanilla takes its own off, so
     * whichever is stronger, the chill or powder snow, is what the player sees.
     */
    public static void showChill(LivingEntity entity) {
        // Fire clears freezing, as in vanilla, until it goes out
        if (!(entity instanceof ServerPlayer player) || !counts(player) || player.isOnFire()) {
            return;
        }
        int ticks = ColdMaths.frozenTicks(HardcoreStates.of(player).chill);
        if (player.getTicksFrozen() < ticks) {
            player.setTicksFrozen(ticks);
        }
    }

    private static boolean on() {
        return Settings.on(c -> c.hardcore.cold.enabled);
    }

    private static HardcoreConfig.ColdSection cfg() {
        return Settings.get().hardcore.cold;
    }

    // Players only: a logout body or a grave never gets cold
    private static boolean counts(ServerPlayer player) {
        return on() && !Hardcore.exempt(player) && !StandIn.is(player);
    }

    private static void tick(ServerPlayer player) {
        if (!counts(player) || player.isDeadOrDying()) {
            // Switching it back on starts the second over, from the chill the store kept
            TIMERS.remove(player);
            return;
        }
        // Players a client doesn't tick still show it; for the rest the living tick already did
        showChill(player);
        int[] timer = TIMERS.computeIfAbsent(player, p -> new int[2]);
        if (++timer[0] < TICKS_PER_SECOND) {
            return;
        }
        timer[0] = 0;
        second(player, timer);
    }

    private static void second(ServerPlayer player, int[] timer) {
        HardcoreConfig.ColdSection cfg = cfg();
        double chill = ColdMaths.next(HardcoreStates.of(player).chill, perSecond(surroundings(player, cfg, false), cfg));
        if (chill != HardcoreStates.of(player).chill) {
            HardcoreStates.update(player.getUUID(), state -> state.chill = chill);
        }
        // Through the player, so the hunger tax counts it too
        double shiver = cfg.shiverExhaustionPerSecond * chill;
        if (shiver > 0.0) {
            player.causeFoodExhaustion((float) shiver);
        }
        // Any leather piece stops vanilla's own freeze hit, so the full chill hits here instead
        if (chill >= 1.0 && cfg.freezeDamage && !player.canFreeze()) {
            if (++timer[1] >= cfg.damageEverySeconds) {
                timer[1] = 0;
                float hit = floored(player, FREEZE_HIT);
                if (hit > 0.0F) {
                    player.hurtServer(player.level(), player.damageSources().freeze(), hit);
                }
            }
        } else {
            timer[1] = 0;
        }
    }

    // Absorption counts, since it soaks the hit before health does
    private static float floored(ServerPlayer player, float amount) {
        if (cfg().canKill) {
            return amount;
        }
        float above = player.getHealth() + player.getAbsorptionAmount() - FLOOR;
        return Math.max(0.0F, Math.min(amount, above));
    }

    private static double perSecond(Surroundings here, HardcoreConfig.ColdSection cfg) {
        return ColdMaths.perSecond(here.coldHere(), here.sheltered(), here.heldHeat(), here.nearHeat(), here.wet(),
                here.inWater(), here.leatherPieces(), cfg.chillPerSecond, cfg.warmPerSecond, cfg.wetMultiplier,
                cfg.waterMultiplier, cfg.leatherPerPiece);
    }

    // The heat scan is skipped when nothing could chill anyway, unless the status line wants to name the warmth
    private static Surroundings surroundings(ServerPlayer player, HardcoreConfig.ColdSection cfg, boolean forStatus) {
        ServerLevel level = player.level();
        BlockPos feet = player.blockPosition();
        BlockPos eyes = BlockPos.containing(player.getEyePosition());
        boolean coldHere = coldSpot(level, feet);
        // The sky light layer itself, which stays 15 under open sky at night too
        boolean sheltered = level.getBrightness(LightLayer.SKY, eyes) < cfg.shelteredBelowSkyLight;
        boolean heldHeat = player.getMainHandItem().is(HELD_HEAT) || player.getOffhandItem().is(HELD_HEAT);
        boolean scan = forStatus ? !heldHeat : coldHere && !sheltered && !heldHeat;
        boolean nearHeat = player.isOnFire() || scan && heatNear(level, feet, cfg.heatRadius);
        // Where it snows the rain check is false, so this asks whether anything falls here at all
        boolean wet = level.precipitationAt(feet) != Biome.Precipitation.NONE
                || level.precipitationAt(eyes) != Biome.Precipitation.NONE;
        return new Surroundings(coldHere, sheltered, heldHeat, nearHeat, wet, player.isInWater(), leatherPieces(player));
    }

    // From the feet up to head height; a block that can be lit counts only while it is
    private static boolean heatNear(ServerLevel level, BlockPos feet, int radius) {
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-radius, -radius, -radius), feet.offset(radius, radius + 1, radius))) {
            BlockState state = level.getBlockState(pos);
            if (state.is(HEAT_SOURCES)
                    && (!state.hasProperty(BlockStateProperties.LIT) || state.getValue(BlockStateProperties.LIT))) {
                return true;
            }
        }
        return false;
    }

    // Vanilla's own leather list, the one that stops its freezing
    private static int leatherPieces(ServerPlayer player) {
        int pieces = 0;
        for (EquipmentSlot slot : EquipmentSlotGroup.ARMOR) {
            if (player.getItemBySlot(slot).is(ItemTags.FREEZE_IMMUNE_WEARABLES)) {
                pieces++;
            }
        }
        return pieces;
    }

    private static double slowShare(ServerPlayer player) {
        if (!counts(player)) {
            return 0.0;
        }
        return ColdMaths.slow(HardcoreStates.of(player).chill, cfg().maxSlow);
    }

    // Says how cold the player is and why it moves, never where they are
    private static Optional<Component> statusLine(ServerPlayer player) {
        if (!counts(player)) {
            return Optional.empty();
        }
        double chill = HardcoreStates.of(player).chill;
        if (!(chill > 0.0)) {
            return Optional.empty();
        }
        HardcoreConfig.ColdSection cfg = cfg();
        String text = "Cold   " + word(chill) + " " + percent(chill) + "% · " + reason(surroundings(player, cfg, true), cfg);
        return Optional.of(Status.line(text, "Out in the open where it is cold enough to snow, a chill builds, faster when"
                + " wet and faster still in water. Holding a torch or lantern, standing near a fire, going indoors or"
                + " leaving the cold warms you, and each piece of leather armour slows the chill. Dying clears it."));
    }

    private static String word(double chill) {
        if (chill >= 1.0) {
            return "frozen";
        }
        return chill < 0.5 ? "chilly" : "cold";
    }

    // Never 0% while there is some chill, nor 100% before it is full
    private static long percent(double chill) {
        if (chill >= 1.0) {
            return 100;
        }
        return Math.max(1, Math.min(99, Math.round(chill * 100.0)));
    }

    private static String reason(Surroundings here, HardcoreConfig.ColdSection cfg) {
        if (perSecond(here, cfg) > 0.0) {
            return "getting colder";
        }
        if (here.heldHeat()) {
            return "warming (torch)";
        }
        if (here.nearHeat()) {
            return "warming (fire)";
        }
        if (!here.coldHere()) {
            return "warming";
        }
        if (here.sheltered()) {
            return "warming (shelter)";
        }
        return here.leatherPieces() > 0 ? "warming (leather)" : "warming";
    }
}
