package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.core.Yolt;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Nutrients: foods carry hidden protein, vitamins and sugar, using food drains all three, and a group that runs low
 * costs the player something: slower healing, slower sprinting or slower mining. The levels live in the hardcore store,
 * so a death while away still starts the next life afresh.
 */
public final class Nutrients {
    static final Identifier MODIFIER = Yolt.id("nutrients");
    private static final int STATUS_ORDER = 50;
    private static final int CAKE_SLICE = 2;
    private static final String CAKE = "minecraft:cake";
    private static final double THIRD = 1.0 / 3.0;

    private enum Group {
        PROTEIN(NutrientMaths.PROTEIN), VITAMINS(NutrientMaths.VITAMINS), SUGAR(NutrientMaths.SUGAR);

        final String label;

        Group(String label) {
            this.label = label;
        }

        double level(HardcoreState s) {
            return switch (this) {
                case PROTEIN -> s.protein;
                case VITAMINS -> s.vitamins;
                case SUGAR -> s.sugar;
            };
        }
    }

    // The groups last told as low, by player object, so each change is told once. Server thread only.
    private static final Map<ServerPlayer, Set<Group>> TOLD_LOW = new WeakHashMap<>();

    private Nutrients() {}

    static void init() {
        Hardcore.onTick(Nutrients::tick);
        Hardcore.onRefresh(Nutrients::refresh);
        Hardcore.onExempt(Nutrients::clear);
        Hardcore.onDeath(Nutrients::onDeath);
        Slowdowns.register("nutrients", Nutrients::sprintShare);
        Legs.speedFactor(Nutrients::healingSpeed);
        Status.register(STATUS_ORDER, Nutrients::statusLine);
    }

    /** A food item eaten, read before the stack shrinks: its own nutrition, shared out as its entry in the table says. */
    public static void onEat(ServerPlayer player, ItemStack eaten) {
        if (!counts(player)) {
            return;
        }
        FoodProperties food = eaten.get(DataComponents.FOOD);
        if (food == null) {
            return;
        }
        Guard.run(() -> eat(player, food.nutrition(), shares(eaten)), "Could not count what {} ate", player.nameAndId().name());
    }

    /** A slice of cake, which is eaten from the block rather than as an item. */
    public static void onEatCake(ServerPlayer player) {
        if (!counts(player)) {
            return;
        }
        Map<String, Double> shares = cfg().foods.get(CAKE);
        Guard.run(() -> eat(player, CAKE_SLICE, shares != null ? shares : unknownShares()),
                "Could not count the cake {} ate", player.nameAndId().name());
    }

    /** The food bar went down by one. Saturation going down never gets here. */
    public static void onFoodPointBurned(ServerPlayer player) {
        if (!counts(player)) {
            return;
        }
        double decay = cfg().decayPerPoint;
        started(player);
        HardcoreStates.update(player.getUUID(), s -> NutrientMaths.burnFoodPoint(s, decay));
    }

    /** Vanilla's ticks between two regen heals, stretched while protein is low. */
    public static int regenTicks(ServerPlayer player, int vanillaTicks) {
        double speed = healingSpeed(player);
        if (speed == 1.0) {
            return vanillaTicks;
        }
        if (!(speed > 0.0)) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, Math.round(vanillaTicks / speed)));
    }

    /** How fast this player heals: the protein penalty's multiplier while protein is low, else 1.0. */
    public static double healingSpeed(ServerPlayer player) {
        if (!counts(player) || !low(player, Group.PROTEIN)) {
            return 1.0;
        }
        return cfg().penalties.protein.healingMultiplier;
    }

    private static boolean on() {
        return Settings.on(c -> c.hardcore.nutrients.enabled);
    }

    private static HardcoreConfig.NutrientsSection cfg() {
        return Settings.get().hardcore.nutrients;
    }

    // Bodies carry no nutrients, and a player outside the rules gains and loses nothing
    private static boolean counts(ServerPlayer player) {
        return on() && !Hardcore.exempt(player) && !StandIn.is(player);
    }

    private static void eat(ServerPlayer player, int nutrition, Map<String, Double> shares) {
        double gain = cfg().gainPerPoint;
        started(player);
        HardcoreStates.update(player.getUUID(), s -> NutrientMaths.eat(s, nutrition, shares, gain));
    }

    // An id entry first, then the first tag entry the item is in, then what unknown foods give
    private static Map<String, Double> shares(ItemStack stack) {
        Map<String, Map<String, Double>> foods = cfg().foods;
        Map<String, Double> byId = foods.get(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        if (byId != null) {
            return byId;
        }
        for (Map.Entry<String, Map<String, Double>> entry : foods.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("#")) {
                continue;
            }
            Identifier tag = Identifier.tryParse(key.substring(1));
            if (tag != null && stack.is(TagKey.create(Registries.ITEM, tag))) {
                return entry.getValue();
            }
        }
        return unknownShares();
    }

    private static Map<String, Double> unknownShares() {
        return switch (cfg().unknownFood) {
            case NONE -> Map.of();
            case PROTEIN -> Map.of(NutrientMaths.PROTEIN, 1.0);
            case VITAMINS -> Map.of(NutrientMaths.VITAMINS, 1.0);
            case SUGAR -> Map.of(NutrientMaths.SUGAR, 1.0);
            case EVEN -> Map.of(NutrientMaths.PROTEIN, THIRD, NutrientMaths.VITAMINS, THIRD, NutrientMaths.SUGAR, THIRD);
        };
    }

    // A player seen for the first time starts at the start level
    private static HardcoreState started(ServerPlayer player) {
        if (NutrientMaths.unset(HardcoreStates.of(player))) {
            double start = cfg().startLevel;
            HardcoreStates.update(player.getUUID(), s -> NutrientMaths.start(s, start));
        }
        return HardcoreStates.of(player);
    }

    private static boolean low(ServerPlayer player, Group group) {
        return NutrientMaths.low(group.level(started(player)), cfg().deficientBelow);
    }

    // A new life starts afresh, online or not; only a death in survival or adventure is one
    private static void onDeath(UUID uuid, GameType modeAtDeath) {
        if (!on() || !modeAtDeath.isSurvival()) {
            return;
        }
        double start = cfg().startLevel;
        HardcoreStates.update(uuid, s -> NutrientMaths.start(s, start));
    }

    private static void tick(ServerPlayer player) {
        if (StandIn.is(player)) {
            return;
        }
        if (!on()) {
            clear(player);
            TOLD_LOW.remove(player);
            return;
        }
        apply(player);
        // A death has just started the levels again, which is no news to a player on the death screen
        if (player.isDeadOrDying()) {
            TOLD_LOW.remove(player);
            return;
        }
        tellChanges(player);
    }

    private static void refresh(ServerPlayer player) {
        if (StandIn.is(player)) {
            return;
        }
        if (!on()) {
            clear(player);
            return;
        }
        apply(player);
    }

    // Only touches the attribute when the amount changes, since every change is sent to the client
    private static void apply(ServerPlayer player) {
        AttributeInstance mining = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
        if (mining == null) {
            return;
        }
        double amount = low(player, Group.VITAMINS) ? -cfg().penalties.vitamins.miningSlow : 0.0;
        if (amount == 0.0) {
            mining.removeModifier(MODIFIER);
            return;
        }
        AttributeModifier current = mining.getModifier(MODIFIER);
        if (current != null && current.amount() == amount) {
            return;
        }
        mining.addOrUpdateTransientModifier(new AttributeModifier(MODIFIER, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    private static void clear(ServerPlayer player) {
        if (StandIn.is(player)) {
            return;
        }
        AttributeInstance mining = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
        if (mining != null) {
            mining.removeModifier(MODIFIER);
        }
    }

    // The first look at a player only notes what is low, so a join or a respawn tells nothing
    private static void tellChanges(ServerPlayer player) {
        Set<Group> now = EnumSet.noneOf(Group.class);
        for (Group group : Group.values()) {
            if (low(player, group)) {
                now.add(group);
            }
        }
        Set<Group> before = TOLD_LOW.put(player, now);
        if (before == null) {
            return;
        }
        for (Group group : Group.values()) {
            if (now.contains(group) && !before.contains(group)) {
                Hardcore.actionBar(player, "You're low on " + group.label + ".");
            } else if (!now.contains(group) && before.contains(group)) {
                Hardcore.actionBar(player, "Your " + group.label + " " + (group == Group.VITAMINS ? "are" : "is") + " back up.");
            }
        }
    }

    private static double sprintShare(ServerPlayer player) {
        if (!counts(player) || !player.isSprinting() || !low(player, Group.SUGAR)) {
            return 0.0;
        }
        return cfg().penalties.sugar.sprintSlow;
    }

    private static Optional<Component> statusLine(ServerPlayer player) {
        if (!counts(player)) {
            return Optional.empty();
        }
        HardcoreConfig.NutrientsSection cfg = cfg();
        HardcoreState state = started(player);
        List<String> parts = new ArrayList<>();
        for (Group group : Group.values()) {
            String part = group.label + " " + percent(group.level(state)) + "%";
            if (NutrientMaths.low(group.level(state), cfg.deficientBelow)) {
                part += " (low: " + cost(group, cfg.penalties) + ")";
            }
            parts.add(part);
        }
        return Optional.of(Status.line("Nutrients  " + String.join(" · ", parts), "Foods carry hidden protein, vitamins"
                + " and sugar, and using food drains all three. Below " + percent(cfg.deficientBelow) + "% you are short"
                + " on one: low protein slows healing, low sugar slows sprinting and low vitamins slow mining. Eat a mix."));
    }

    private static String cost(Group group, HardcoreConfig.PenaltiesSection penalties) {
        return switch (group) {
            case PROTEIN -> "healing " + slower(1.0 - penalties.protein.healingMultiplier);
            case SUGAR -> "sprinting " + slower(penalties.sugar.sprintSlow);
            case VITAMINS -> "mining " + slower(penalties.vitamins.miningSlow);
        };
    }

    // A multiplier over 1 heals faster, which reads as a negative slowdown
    private static String slower(double share) {
        return share < 0.0 ? percent(-share) + "% faster" : percent(share) + "% slower";
    }

    private static long percent(double share) {
        return Math.round(share * 100.0);
    }
}
