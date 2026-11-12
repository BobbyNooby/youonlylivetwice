package bobbynooby.dev.coords;

import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.gameevent.BlockPositionSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Positions inside particles: where a vibration heads for a block, where a trail ends, and the item an item particle
 * shows. Particles come in their own packet, in an explosion and in entity data.
 */
public final class ParticleRule {
    // Filled at mod start, before any server thread runs, and only read after
    private static final Map<ParticleType<?>, Coverage> PARTICLES = new HashMap<>();

    private ParticleRule() {}

    static void register() {
        classifyParticles();
    }

    /** Every particle type: TRANSLATED when its options can hold a position, which this rule shifts. */
    public static Map<ParticleType<?>, Coverage> particleCoverage() {
        return Collections.unmodifiableMap(PARTICLES);
    }

    /** The particle as the client is sent it: the very particle when nothing in it moves. */
    public static ParticleOptions shift(ParticleOptions particle, Offset offset) {
        if (offset.isZero()) {
            return particle;
        }
        return switch (particle) {
            case VibrationParticleOption vibration -> vibration(vibration, offset);
            case TrailParticleOption trail -> new TrailParticleOption(offset.shift(trail.target()), trail.color(), trail.duration());
            case ItemParticleOption item -> item(item, offset);
            default -> particle;
        };
    }

    // A sculk sensor's vibration heads for a block. One heading for an entity names it by id, which the client looks up.
    private static ParticleOptions vibration(VibrationParticleOption vibration, Offset offset) {
        if (!(vibration.getDestination() instanceof BlockPositionSource block)) {
            return vibration;
        }
        return new VibrationParticleOption(new BlockPositionSource(offset.shift(block.pos())), vibration.getArrivalInTicks());
    }

    private static ParticleOptions item(ItemParticleOption item, Offset offset) {
        ItemStackTemplate shifted = ItemRule.shift(item.getItem(), offset);
        return shifted == item.getItem() ? item : new ItemParticleOption(item.getType(), shifted);
    }

    // From each type's options in 26.3. The others hold block states, colours, sizes and delays.
    private static void classifyParticles() {
        PARTICLES.put(ParticleTypes.VIBRATION, Coverage.TRANSLATED);
        PARTICLES.put(ParticleTypes.TRAIL, Coverage.TRANSLATED);
        PARTICLES.put(ParticleTypes.ITEM, Coverage.TRANSLATED);
        noPositions(
                ParticleTypes.ANGRY_VILLAGER,
                ParticleTypes.BLOCK,
                ParticleTypes.BLOCK_MARKER,
                ParticleTypes.BUBBLE,
                ParticleTypes.SULFUR_BUBBLES,
                ParticleTypes.NOXIOUS_GAS,
                ParticleTypes.NOXIOUS_GAS_CLOUD,
                ParticleTypes.GEYSER,
                ParticleTypes.GEYSER_BASE,
                ParticleTypes.GEYSER_POOF,
                ParticleTypes.GEYSER_PLUME,
                ParticleTypes.CLOUD,
                ParticleTypes.COPPER_FIRE_FLAME,
                ParticleTypes.CRIT,
                ParticleTypes.DAMAGE_INDICATOR,
                ParticleTypes.DRAGON_BREATH,
                ParticleTypes.DRIPPING_LAVA,
                ParticleTypes.FALLING_LAVA,
                ParticleTypes.LANDING_LAVA,
                ParticleTypes.DRIPPING_WATER,
                ParticleTypes.FALLING_WATER,
                ParticleTypes.DUST,
                ParticleTypes.DUST_COLOR_TRANSITION,
                ParticleTypes.EFFECT,
                ParticleTypes.ELDER_GUARDIAN,
                ParticleTypes.ENCHANTED_HIT,
                ParticleTypes.ENCHANT,
                ParticleTypes.END_ROD,
                ParticleTypes.ENTITY_EFFECT,
                ParticleTypes.EXPLOSION_EMITTER,
                ParticleTypes.EXPLOSION,
                ParticleTypes.GUST,
                ParticleTypes.SMALL_GUST,
                ParticleTypes.GUST_EMITTER_LARGE,
                ParticleTypes.GUST_EMITTER_SMALL,
                ParticleTypes.SONIC_BOOM,
                ParticleTypes.FALLING_DUST,
                ParticleTypes.FIREWORK,
                ParticleTypes.FISHING,
                ParticleTypes.FLAME,
                ParticleTypes.INFESTED,
                ParticleTypes.CHERRY_LEAVES,
                ParticleTypes.PALE_OAK_LEAVES,
                ParticleTypes.RED_POPLAR_LEAVES,
                ParticleTypes.ORANGE_POPLAR_LEAVES,
                ParticleTypes.YELLOW_POPLAR_LEAVES,
                ParticleTypes.TINTED_LEAVES,
                ParticleTypes.SCULK_SOUL,
                ParticleTypes.SCULK_CHARGE,
                ParticleTypes.SCULK_CHARGE_POP,
                ParticleTypes.SOUL_FIRE_FLAME,
                ParticleTypes.SOUL,
                ParticleTypes.FLASH,
                ParticleTypes.HAPPY_VILLAGER,
                ParticleTypes.COMPOSTER,
                ParticleTypes.HEART,
                ParticleTypes.INSTANT_EFFECT,
                ParticleTypes.PAUSE_MOB_GROWTH,
                ParticleTypes.RESET_MOB_GROWTH,
                ParticleTypes.ITEM_SLIME,
                ParticleTypes.ITEM_COBWEB,
                ParticleTypes.ITEM_SNOWBALL,
                ParticleTypes.LARGE_SMOKE,
                ParticleTypes.LAVA,
                ParticleTypes.MYCELIUM,
                ParticleTypes.NOTE,
                ParticleTypes.POOF,
                ParticleTypes.PORTAL,
                ParticleTypes.RAIN,
                ParticleTypes.SMOKE,
                ParticleTypes.WHITE_SMOKE,
                ParticleTypes.SNEEZE,
                ParticleTypes.SPIT,
                ParticleTypes.SQUID_INK,
                ParticleTypes.SWEEP_ATTACK,
                ParticleTypes.TOTEM_OF_UNDYING,
                ParticleTypes.UNDERWATER,
                ParticleTypes.SPLASH,
                ParticleTypes.WITCH,
                ParticleTypes.BUBBLE_POP,
                ParticleTypes.CURRENT_DOWN,
                ParticleTypes.BUBBLE_COLUMN_UP,
                ParticleTypes.NAUTILUS,
                ParticleTypes.DOLPHIN,
                ParticleTypes.CAMPFIRE_COSY_SMOKE,
                ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
                ParticleTypes.DRIPPING_HONEY,
                ParticleTypes.FALLING_HONEY,
                ParticleTypes.LANDING_HONEY,
                ParticleTypes.FALLING_NECTAR,
                ParticleTypes.FALLING_SPORE_BLOSSOM,
                ParticleTypes.ASH,
                ParticleTypes.CRIMSON_SPORE,
                ParticleTypes.WARPED_SPORE,
                ParticleTypes.SPORE_BLOSSOM_AIR,
                ParticleTypes.DRIPPING_OBSIDIAN_TEAR,
                ParticleTypes.FALLING_OBSIDIAN_TEAR,
                ParticleTypes.LANDING_OBSIDIAN_TEAR,
                ParticleTypes.REVERSE_PORTAL,
                ParticleTypes.WHITE_ASH,
                ParticleTypes.SMALL_FLAME,
                ParticleTypes.SNOWFLAKE,
                ParticleTypes.DRIPPING_DRIPSTONE_LAVA,
                ParticleTypes.FALLING_DRIPSTONE_LAVA,
                ParticleTypes.DRIPPING_DRIPSTONE_WATER,
                ParticleTypes.FALLING_DRIPSTONE_WATER,
                ParticleTypes.GLOW_SQUID_INK,
                ParticleTypes.GLOW,
                ParticleTypes.WAX_ON,
                ParticleTypes.WAX_OFF,
                ParticleTypes.ELECTRIC_SPARK,
                ParticleTypes.SCRAPE,
                ParticleTypes.SHRIEK,
                ParticleTypes.EGG_CRACK,
                ParticleTypes.DUST_PLUME,
                ParticleTypes.TRIAL_SPAWNER_DETECTED_PLAYER,
                ParticleTypes.TRIAL_SPAWNER_DETECTED_PLAYER_OMINOUS,
                ParticleTypes.VAULT_CONNECTION,
                ParticleTypes.DUST_PILLAR,
                ParticleTypes.OMINOUS_SPAWNING,
                ParticleTypes.RAID_OMEN,
                ParticleTypes.TRIAL_OMEN,
                ParticleTypes.BLOCK_CRUMBLE,
                ParticleTypes.FIREFLY,
                ParticleTypes.SULFUR_CUBE_GOO);
    }

    private static void noPositions(ParticleType<?>... types) {
        for (ParticleType<?> type : types) {
            PARTICLES.put(type, Coverage.NO_POSITIONS);
        }
    }
}
