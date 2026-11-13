package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.StandIn;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.equipment.Equippable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Carried weight: everything a player carries weighs something, and a load over the free amount slows them and makes
 * each jump cost extra hunger. The load is kept per player and worked out again every few ticks, only while weight is
 * on, and never for a player outside the rules or a logout body.
 */
public final class Weight {
    private static final int STATUS_ORDER = 10;
    // Soon enough that putting things down feels immediate, rarely enough that a full inventory costs nothing
    private static final int RECOMPUTE_TICKS = 10;
    private static final int HEAVIEST_SHOWN = 3;
    private static final List<TagKey<Item>> TOOLS = List.of(ItemTags.SWORDS, ItemTags.AXES, ItemTags.PICKAXES, ItemTags.SHOVELS,
            ItemTags.HOES, ItemTags.SPEARS);
    // Armour and the off-hand; the hotbar and the main inventory are the inventory's own list
    private static final List<EquipmentSlot> WORN = List.of(EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST,
            EquipmentSlot.HEAD, EquipmentSlot.OFFHAND);
    private static final String SLOWED = "Your load is slowing you down.";

    // Everything of one kind the player carries, added up
    private record Heavy(String name, int count, double weight) {
        Heavy plus(Heavy other) {
            return new Heavy(name, count + other.count, weight + other.weight);
        }
    }

    private record Tally(double load, List<Heavy> heaviest) {}

    // told: the player has heard they are slowed and still is, so they aren't told again until it stops and starts again
    private record Carried(Tally tally, int at, boolean told) {}

    // By player object, so a respawn's new object starts afresh and one that is gone drops out. Server thread only.
    private static final Map<ServerPlayer, Carried> CARRIED = new WeakHashMap<>();
    // Group names read once each; a name that isn't an id matches nothing
    private static final Map<String, Optional<TagKey<Item>>> TAGS = new ConcurrentHashMap<>();

    private Weight() {}

    static void init() {
        Slowdowns.register("weight", Weight::slowShare);
        Status.register(STATUS_ORDER, Weight::loadLine);
        Status.register(STATUS_ORDER, Weight::heaviestLine);
        Hardcore.onTick(Weight::tick);
    }

    /** The player's load, at most a few ticks old; 0 while weight is off and for anyone it doesn't apply to. */
    public static double load(ServerPlayer player) {
        return carried(player).map(carried -> carried.tally().load()).orElse(0.0);
    }

    /** 0 at or under the free load, 1 at or over the full one; 0 while weight is off. */
    public static double loadShare(ServerPlayer player) {
        HardcoreConfig.WeightSection cfg = Settings.get().hardcore.weight;
        return carried(player).map(carried -> WeightTable.loadShare(carried.tally().load(), cfg.freeLoad, cfg.fullLoad)).orElse(0.0);
    }

    /** What the player carries right now under these settings, worked out afresh. Not gated by the switch: for tests. */
    public static double carriedWeight(ServerPlayer player, HardcoreConfig.WeightSection cfg) {
        return tally(player, cfg).load();
    }

    /** One stack, with what is inside it when it is a container. depth is how many containers it sits in. */
    public static double weigh(ItemStack stack, HardcoreConfig.WeightSection cfg, int depth) {
        if (stack.isEmpty()) {
            return 0.0;
        }
        double weight = each(stack, cfg) * stack.getCount();
        if (cfg.countContainers && depth < cfg.containerDepth) {
            double contents = 0.0;
            for (ItemStack inside : contents(stack)) {
                contents += weigh(inside, cfg, depth + 1);
            }
            weight += contents * cfg.containerContents;
        }
        return weight;
    }

    /** A heavy jump costs extra hunger, through the same call as vanilla's own, so anything that scales hunger scales it. */
    public static void onJump(ServerPlayer player) {
        Guard.run(() -> {
            double extra = Settings.get().hardcore.weight.heavyJumpExhaustion * loadShare(player);
            if (extra > 0.0) {
                player.causeFoodExhaustion((float) extra);
            }
        }, "Could not add the heavy jump's hunger for {}", player.nameAndId().name());
    }

    private static boolean on() {
        return Settings.on(c -> c.hardcore.weight.enabled);
    }

    private static void tick(ServerPlayer player) {
        if (!on()) {
            // Nothing kept from before, so switching it back on starts from what the player carries then
            if (!CARRIED.isEmpty()) {
                CARRIED.clear();
            }
            return;
        }
        carried(player).ifPresent(carried -> {
            boolean slowed = slow(carried.tally().load()) > 0.0;
            if (slowed && !carried.told()) {
                Hardcore.actionBar(player, SLOWED);
            }
            if (slowed != carried.told()) {
                CARRIED.put(player, new Carried(carried.tally(), carried.at(), slowed));
            }
        });
    }

    // The kept load, or a fresh one when it is RECOMPUTE_TICKS old
    private static Optional<Carried> carried(ServerPlayer player) {
        if (!on() || Hardcore.exempt(player) || StandIn.is(player)) {
            return Optional.empty();
        }
        int now = player.level().getServer().getTickCount();
        Carried last = CARRIED.get(player);
        if (last != null && now - last.at() < RECOMPUTE_TICKS) {
            return Optional.of(last);
        }
        Carried next = new Carried(tally(player, Settings.get().hardcore.weight), now, last != null && last.told());
        CARRIED.put(player, next);
        return Optional.of(next);
    }

    private static double slowShare(ServerPlayer player) {
        return carried(player).map(carried -> slow(carried.tally().load())).orElse(0.0);
    }

    // Read live, so a changed number applies at once without waiting for the next weighing
    private static double slow(double load) {
        HardcoreConfig.WeightSection cfg = Settings.get().hardcore.weight;
        return WeightTable.slow(load, cfg.freeLoad, cfg.fullLoad, cfg.maxSlow);
    }

    private static Tally tally(ServerPlayer player, HardcoreConfig.WeightSection cfg) {
        Map<Item, Heavy> byKind = new LinkedHashMap<>();
        double load = 0.0;
        for (ItemStack stack : carriedStacks(player, cfg)) {
            if (stack.isEmpty()) {
                continue;
            }
            double weight = weigh(stack, cfg, 0);
            load += weight;
            // Named by the item alone: a stack's own components can name a player, as a grave head or a revive paper does
            String kind = stack.getItem().getDefaultInstance().getItemName().getString();
            byKind.merge(stack.getItem(), new Heavy(kind, stack.getCount(), weight), Heavy::plus);
        }
        List<Heavy> heaviest = byKind.values().stream()
                .sorted(Comparator.comparingDouble(Heavy::weight).reversed())
                .limit(HEAVIEST_SHOWN)
                .toList();
        return new Tally(load, heaviest);
    }

    // The ender chest stays out: it isn't carried
    private static List<ItemStack> carriedStacks(ServerPlayer player, HardcoreConfig.WeightSection cfg) {
        List<ItemStack> stacks = new ArrayList<>(player.getInventory().getNonEquipmentItems());
        for (EquipmentSlot slot : WORN) {
            stacks.add(player.getItemBySlot(slot));
        }
        if (cfg.countCursorAndGrid) {
            // The cursor belongs to whatever screen is open, the 2x2 grid always to the inventory's own
            stacks.add(player.containerMenu.getCarried());
            Container grid = player.inventoryMenu.getCraftSlots();
            for (int i = 0; i < grid.getContainerSize(); i++) {
                stacks.add(grid.getItem(i));
            }
        }
        return stacks;
    }

    // One item's weight. An id beats armour, armour beats tools, tools beat the groups.
    private static double each(ItemStack stack, HardcoreConfig.WeightSection cfg) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        int max = stack.getMaxStackSize();
        Double each = cfg.itemsEach.get(id);
        if (each != null) {
            return each;
        }
        Double stackWeight = cfg.items.get(id);
        if (stackWeight != null) {
            return WeightTable.each(stackWeight, max);
        }
        Optional<Double> armour = armour(stack, cfg);
        if (armour.isPresent()) {
            return armour.get();
        }
        for (TagKey<Item> tool : TOOLS) {
            if (stack.is(tool)) {
                return cfg.toolWeight;
            }
        }
        for (Map.Entry<String, Double> group : cfg.groups.entrySet()) {
            Optional<TagKey<Item>> tag = tag(group.getKey());
            if (tag.isPresent() && stack.is(tag.get())) {
                return WeightTable.each(group.getValue(), max);
            }
        }
        return max == 1 ? cfg.unstackableWeight : WeightTable.each(cfg.defaultWeight, max);
    }

    // Worn armour with armour points, by the material its look comes from. Horse and wolf armour go on an animal's body
    // slot and an elytra has no points, so neither is armour here.
    private static Optional<Double> armour(ItemStack stack, HardcoreConfig.WeightSection cfg) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null || equippable.slot().getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
            return Optional.empty();
        }
        ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        int points = (int) Math.round(modifiers.compute(Attributes.ARMOR, 0.0, equippable.slot()));
        if (points <= 0) {
            return Optional.empty();
        }
        Optional<Double> factor = equippable.assetId().map(asset -> cfg.armourFactors.get(asset.identifier().getPath()));
        return Optional.of(factor.isPresent()
                ? WeightTable.armour(points, factor.get())
                : WeightTable.each(cfg.defaultWeight, stack.getMaxStackSize()));
    }

    private static List<ItemStack> contents(ItemStack stack) {
        List<ItemStack> inside = new ArrayList<>();
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            container.nonEmptyItemCopyStream().forEach(inside::add);
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            bundle.itemCopies().forEach(inside::add);
        }
        return inside;
    }

    private static Optional<TagKey<Item>> tag(String name) {
        return TAGS.computeIfAbsent(name, key -> Optional.ofNullable(Identifier.tryParse(key)).map(id -> TagKey.create(Registries.ITEM, id)));
    }

    private static Optional<Component> loadLine(ServerPlayer player) {
        return carried(player).map(carried -> {
            HardcoreConfig.WeightSection cfg = Settings.get().hardcore.weight;
            long percent = Math.round(slow(carried.tally().load()) * 100.0);
            String text = "Load " + brief(carried.tally().load()) + " / " + brief(cfg.fullLoad) + " (free up to " + brief(cfg.freeLoad) + ")"
                    + (percent > 0 ? " → " + percent + "% slower" : "");
            return Status.line(text, "Everything you carry weighs something. Over the free load you walk slower, and each jump"
                    + " costs extra hunger. Your ender chest doesn't count.");
        });
    }

    private static Optional<Component> heaviestLine(ServerPlayer player) {
        return carried(player).filter(carried -> !carried.tally().heaviest().isEmpty()).map(carried -> {
            String heaviest = carried.tally().heaviest().stream()
                    .map(heavy -> heavy.name() + (heavy.count() > 1 ? " ×" + heavy.count() : "") + " (" + oneDecimal(heavy.weight()) + ")")
                    .collect(Collectors.joining(", "));
            return Status.line("  heaviest: " + heaviest, "The heaviest things you carry, with whatever is inside them.");
        });
    }

    // 31.5, or 56 for a whole number
    private static String brief(double value) {
        String text = oneDecimal(value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
