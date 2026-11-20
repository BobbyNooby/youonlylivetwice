package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.mixin.ItemContainerContentsInvoker;
import com.google.common.collect.MapMaker;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.minecraft.core.component.DataComponentMap;
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
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.equipment.Equippable;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

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

    // Everything of one kind the player carries, added up. Named only when /status shows it.
    private record Heavy(Item item, int count, double weight) {
        Heavy plus(Heavy other) {
            return new Heavy(item, count + other.count, weight + other.weight);
        }
    }

    private record Tally(double load, List<Heavy> heaviest) {}

    // told: the player has heard they are slowed and still is, so they aren't told again until it stops and starts again
    private record Carried(Tally tally, int at, boolean told) {}

    // By player object, so a respawn's new object starts afresh and one that is gone drops out. Server thread only.
    private static final Map<ServerPlayer, Carried> CARRIED = new WeakHashMap<>();
    // Group names read once each; a name that isn't an id matches nothing
    private static final Map<String, Optional<TagKey<Item>>> TAGS = new ConcurrentHashMap<>();
    // The weights worked out so far under the settings they were worked out with. Server thread only.
    private static @Nullable Known known;
    // How many times a container's contents have been walked through, rather than found in Known. For a test.
    private static long walks;

    private Weight() {}

    static void init() {
        Slowdowns.register("weight", Weight::slowShare);
        Status.register(STATUS_ORDER, Weight::statusLines);
        Hardcore.onTick(Weight::tick);
        // The groups and tools are item tags, which a datapack reload can change. The client's own tags don't count.
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
            if (!client) {
                known = null;
            }
        });
    }

    /** How many times a container's contents have been walked through, rather than found already weighed. For a test. */
    public static long contentWalks() {
        return walks;
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
        return stack.isEmpty() ? 0.0 : known(cfg).weigh(stack, depth);
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
            // After the steps, so the player is told only once the load really slows them
            HardcoreConfig.SlowSection steps = Settings.get().hardcore.slow;
            boolean slowed = SlowBudget.total(steps.cap, steps.step, slow(carried.tally().load())) > 0.0;
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
        Known weights = known(cfg);
        Map<Item, Heavy> byKind = new LinkedHashMap<>();
        double load = 0.0;
        for (ItemStack stack : carriedStacks(player, cfg)) {
            if (stack.isEmpty()) {
                continue;
            }
            double weight = weights.weigh(stack, 0);
            load += weight;
            byKind.merge(stack.getItem(), new Heavy(stack.getItem(), stack.getCount(), weight), Heavy::plus);
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
            // A crafting table's grid too: what lies there goes back to the player when the screen closes
            if (player.containerMenu != player.inventoryMenu && player.containerMenu instanceof AbstractCraftingMenu crafting) {
                for (Slot slot : crafting.getInputGridSlots()) {
                    stacks.add(slot.getItem());
                }
            }
        }
        return stacks;
    }

    // One item's weight. An id beats armour, armour beats tools, tools beat the groups.
    private static double each(ItemInstance item, HardcoreConfig.WeightSection cfg) {
        String id = BuiltInRegistries.ITEM.getKey(item.typeHolder().value()).toString();
        int max = item.getMaxStackSize();
        Double each = cfg.itemsEach.get(id);
        if (each != null) {
            return each;
        }
        Double stackWeight = cfg.items.get(id);
        if (stackWeight != null) {
            return WeightTable.each(stackWeight, max);
        }
        Optional<Double> armour = armour(item, cfg);
        if (armour.isPresent()) {
            return armour.get();
        }
        for (TagKey<Item> tool : TOOLS) {
            if (item.is(tool)) {
                return cfg.toolWeight;
            }
        }
        for (Map.Entry<String, Double> group : cfg.groups.entrySet()) {
            Optional<TagKey<Item>> tag = tag(group.getKey());
            if (tag.isPresent() && item.is(tag.get())) {
                return WeightTable.each(group.getValue(), max);
            }
        }
        return max == 1 ? cfg.unstackableWeight : WeightTable.each(cfg.defaultWeight, max);
    }

    // Worn armour with armour points, by the material its look comes from. Horse and wolf armour go on an animal's body
    // slot and an elytra has no points, so neither is armour here.
    private static Optional<Double> armour(ItemInstance item, HardcoreConfig.WeightSection cfg) {
        Equippable equippable = item.get(DataComponents.EQUIPPABLE);
        if (equippable == null || equippable.slot().getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
            return Optional.empty();
        }
        ItemAttributeModifiers modifiers = item.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        int points = (int) Math.round(modifiers.compute(Attributes.ARMOR, 0.0, equippable.slot()));
        if (points <= 0) {
            return Optional.empty();
        }
        Optional<Double> factor = equippable.assetId().map(asset -> cfg.armourFactors.get(asset.identifier().getPath()));
        return Optional.of(factor.isPresent()
                ? WeightTable.armour(points, factor.get())
                : WeightTable.each(cfg.defaultWeight, item.getMaxStackSize()));
    }

    // The only components each reads that a stack can set for itself. Where all three are the item's own, the item's
    // weight is the same for every stack of it.
    private static boolean ownWeight(ItemInstance item) {
        DataComponentMap prototype = item.typeHolder().components();
        return item.get(DataComponents.MAX_STACK_SIZE) != prototype.get(DataComponents.MAX_STACK_SIZE)
                || item.get(DataComponents.EQUIPPABLE) != prototype.get(DataComponents.EQUIPPABLE)
                || item.get(DataComponents.ATTRIBUTE_MODIFIERS) != prototype.get(DataComponents.ATTRIBUTE_MODIFIERS);
    }

    /**
     * True when ItemStackTemplate.create would make an empty stack of this, as it does of one ItemStack.validateStrict
     * refuses. The weighing reads a container's templates without making stacks of them, and an empty stack weighs
     * nothing. Public so a test can check it against create itself.
     */
    public static boolean refused(ItemStackTemplate item) {
        int max = item.getMaxStackSize();
        if (item.count() <= 0 || item.count() > max || item.get(DataComponents.MAX_DAMAGE) != null && max > 1) {
            return true;
        }
        ItemContainerContents container = item.get(DataComponents.CONTAINER);
        if (container != null && oversized(container.nonEmptyItems())) {
            return true;
        }
        BundleContents bundle = item.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null && (oversized(bundle.items()) || bundle.weight().isError())) {
            return true;
        }
        ChargedProjectiles charged = item.get(DataComponents.CHARGED_PROJECTILES);
        return charged != null && oversized(charged.items());
    }

    private static boolean oversized(Iterable<? extends ItemInstance> items) {
        for (ItemInstance item : items) {
            if (item.count() > item.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    // Kept while every setting the weights depend on stays as it was, in this object or a reloaded one
    private static Known known(HardcoreConfig.WeightSection cfg) {
        Known now = known;
        if (now == null || !now.holds(cfg)) {
            now = new Known(cfg);
            known = now;
        }
        return now;
    }

    /**
     * The weights worked out under one set of settings: each item's own, for stacks that don't change it with their own
     * components, and each container's contents, by the container or bundle component itself. Components never change,
     * so an unchanged shulker box costs one lookup however much is inside it. The keys are weak and compared by identity,
     * so a component no stack holds any more drops out.
     */
    private static final class Known {
        // A copy of what the weights depend on, which nothing else can change. Groups are tried in order, so the copy
        // keeps it, and only the same order holds.
        private final HardcoreConfig.WeightSection cfg = new HardcoreConfig.WeightSection();
        private final Map<Item, Double> each = new IdentityHashMap<>();
        // The contents' weight before containerContents scales it, by how many containers the container sits in
        private final Map<Object, double[]> contents = new MapMaker().weakKeys().makeMap();

        Known(HardcoreConfig.WeightSection from) {
            cfg.defaultWeight = from.defaultWeight;
            cfg.unstackableWeight = from.unstackableWeight;
            cfg.toolWeight = from.toolWeight;
            cfg.groups = new LinkedHashMap<>(from.groups);
            cfg.items = new HashMap<>(from.items);
            cfg.itemsEach = new HashMap<>(from.itemsEach);
            cfg.armourFactors = new HashMap<>(from.armourFactors);
            cfg.countContainers = from.countContainers;
            cfg.containerContents = from.containerContents;
            cfg.containerDepth = from.containerDepth;
        }

        boolean holds(HardcoreConfig.WeightSection now) {
            return Double.compare(now.defaultWeight, cfg.defaultWeight) == 0
                    && Double.compare(now.unstackableWeight, cfg.unstackableWeight) == 0
                    && Double.compare(now.toolWeight, cfg.toolWeight) == 0
                    && now.countContainers == cfg.countContainers
                    && Double.compare(now.containerContents, cfg.containerContents) == 0
                    && now.containerDepth == cfg.containerDepth
                    && now.items.equals(cfg.items) && now.itemsEach.equals(cfg.itemsEach)
                    && now.armourFactors.equals(cfg.armourFactors)
                    && List.copyOf(now.groups.entrySet()).equals(List.copyOf(cfg.groups.entrySet()));
        }

        double weigh(ItemInstance item, int depth) {
            double weight = each(item) * item.count();
            if (cfg.countContainers && depth < cfg.containerDepth) {
                weight += contents(item, depth) * cfg.containerContents;
            }
            return weight;
        }

        private double each(ItemInstance item) {
            if (ownWeight(item)) {
                return Weight.each(item, cfg);
            }
            return each.computeIfAbsent(item.typeHolder().value(), kind -> Weight.each(item, cfg));
        }

        // Added up in the order the old copies came in: the container's slots, then the bundle's
        private double contents(ItemInstance item, int depth) {
            ItemContainerContents container = item.get(DataComponents.CONTAINER);
            BundleContents bundle = item.get(DataComponents.BUNDLE_CONTENTS);
            if (container == null && bundle == null) {
                return 0.0;
            }
            // Both at once only comes from a command, and the two sums can't be kept apart without changing the total
            if (container != null && bundle != null) {
                return addUp(addUp(0.0, container, depth), bundle, depth);
            }
            Object component = container != null ? container : bundle;
            double[] byDepth = contents.get(component);
            if (byDepth != null && depth < byDepth.length && !Double.isNaN(byDepth[depth])) {
                return byDepth[depth];
            }
            double sum = container != null ? addUp(0.0, container, depth) : addUp(0.0, bundle, depth);
            // A NaN sum, from NaN settings, is worked out again each time, which gives the same NaN
            if (byDepth == null || depth >= byDepth.length) {
                int from = byDepth == null ? 0 : byDepth.length;
                byDepth = byDepth == null ? new double[depth + 1] : Arrays.copyOf(byDepth, depth + 1);
                Arrays.fill(byDepth, from, byDepth.length, Double.NaN);
                contents.put(component, byDepth);
            }
            byDepth[depth] = sum;
            return sum;
        }

        private double addUp(double sum, ItemContainerContents container, int depth) {
            walks++;
            for (Optional<ItemStackTemplate> slot : ((ItemContainerContentsInvoker) (Object) container).yoltItems()) {
                if (slot.isPresent()) {
                    sum += inside(slot.get(), depth);
                }
            }
            return sum;
        }

        private double addUp(double sum, BundleContents bundle, int depth) {
            walks++;
            for (ItemStackTemplate item : bundle.items()) {
                sum += inside(item, depth);
            }
            return sum;
        }

        private double inside(ItemStackTemplate item, int depth) {
            return refused(item) ? 0.0 : weigh(item, depth + 1);
        }
    }

    private static Optional<TagKey<Item>> tag(String name) {
        return TAGS.computeIfAbsent(name, key -> Optional.ofNullable(Identifier.tryParse(key)).map(id -> TagKey.create(Registries.ITEM, id)));
    }

    // The load, then the heaviest things one to a line
    private static List<Component> statusLines(ServerPlayer player) {
        return carried(player).map(carried -> {
            HardcoreConfig.WeightSection cfg = Settings.get().hardcore.weight;
            double load = carried.tally().load();
            long percent = Math.round(slow(load) * 100.0);
            String hover = "Everything you carry weighs something. Up to " + brief(cfg.freeLoad) + " is free; over it you walk"
                    + " slower, and each jump costs extra hunger. Your ender chest doesn't count. Under this line are the"
                    + " heaviest things you carry, with whatever is inside them.";
            List<Component> lines = new ArrayList<>();
            lines.add(percent > 0
                    ? Status.line(hover, Status.label("Load"), Status.value(brief(load) + " / " + brief(cfg.fullLoad)),
                            Status.separator(), Status.bad(percent + "% slower"))
                    : Status.line(hover, Status.label("Load"), Status.value(brief(load) + " / " + brief(cfg.fullLoad))));
            for (Heavy heavy : carried.tally().heaviest()) {
                lines.add(Status.detail(hover, heavyText(heavy)));
            }
            return lines;
        }).orElse(List.of());
    }

    // A long name is cut rather than the count and weight, so the line still fits the chat
    private static String heavyText(Heavy heavy) {
        String rest = (heavy.count() > 1 ? " ×" + heavy.count() : "") + " (" + oneDecimal(heavy.weight()) + ")";
        // Named by the item alone: a stack's own components can name a player, as a grave head or a revive paper does
        String name = heavy.item().getDefaultInstance().getItemName().getString();
        return Status.fit(name, Status.WIDTH - 2 - rest.length()) + rest;
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
