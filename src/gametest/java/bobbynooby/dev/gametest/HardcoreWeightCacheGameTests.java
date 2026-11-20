package bobbynooby.dev.gametest;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.hardcore.Weight;
import bobbynooby.dev.hardcore.WeightTable;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

// The weighing keeps what it worked out: each item's own weight, and each container's contents by the component itself.
// Every weight here must come out exactly, to the last bit, as the weighing before that did. That weighing is kept below
// as it was, copies and all, to check against.
public class HardcoreWeightCacheGameTests {
    // A batch of its own, so no other test's weighing walks containers while this one counts the walks
    private static final String WALKS = "you-only-live-twice-gametest:hardcore_weight_walks";
    private static final BlockPos AT = new BlockPos(1, 1, 1);
    // Stackable things from each kind of rule: ids, stack weights, groups, the default
    private static final List<Item> LOOSE = List.of(Items.COBBLESTONE, Items.IRON_INGOT, Items.DIRT, Items.PAPER, Items.FEATHER,
            Items.STRING, Items.RAW_IRON, Items.OAK_LOG, Items.COAL, Items.GOLD_INGOT, Items.IRON_BLOCK, Items.END_STONE,
            Items.DIAMOND, Items.REDSTONE, Items.STONE_BRICKS, Items.COPPER_BLOCK.waxed().weathered());

    // The known abuse: 27 shulker boxes, each of 27 bundles of 64 single items, weighed under several settings
    @GameTest(maxTicks = 200)
    public void shulkersOfBundlesWeighAsBefore(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Hoarder", GameType.SURVIVAL, AT);
        try {
            fillWithShulkersOfBundles(player);
            putOddStacks(player);
            for (HardcoreConfig.WeightSection cfg : settings()) {
                for (int slot = 0; slot < player.getInventory().getNonEquipmentItems().size(); slot++) {
                    ItemStack stack = player.getInventory().getNonEquipmentItems().get(slot);
                    assertSame(helper, Weight.weigh(stack, cfg, 0), Before.weigh(stack, cfg, 0), "slot " + slot + " " + stack);
                }
                assertSame(helper, Weight.carriedWeight(player, cfg), Before.carried(player, cfg), "the whole inventory");
                // Again, now from what the first weighing kept
                assertSame(helper, Weight.carriedWeight(player, cfg), Before.carried(player, cfg), "the whole inventory again");
            }
        } finally {
            player.getInventory().clearContent();
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The second weighing of an unchanged inventory finds every container in what the first kept. A change to one bundle
    // makes a new bundle and a new shulker box, and only those two are walked again.
    @GameTest(maxTicks = 200, environment = WALKS)
    public void unchangedContainersAreNotWalkedAgain(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Counted", GameType.SURVIVAL, AT);
        HardcoreConfig.WeightSection cfg = new HardcoreConfig.WeightSection();
        try {
            fillWithShulkersOfBundles(player);
            long before = Weight.contentWalks();
            double first = Weight.carriedWeight(player, cfg);
            long walked = Weight.contentWalks() - before;
            // Every shulker box and every bundle in it, once each
            helper.assertValueEqual(walked, 27L * 28L, "walks of the first weighing");

            long again = Weight.contentWalks();
            assertSame(helper, Weight.carriedWeight(player, cfg), first, "the second weighing");
            helper.assertValueEqual(Weight.contentWalks() - again, 0L, "walks of the second weighing");

            ItemStack box = player.getInventory().getItem(14);
            List<ItemStack> bundles = box.get(DataComponents.CONTAINER).nonEmptyItemCopyStream().toList();
            List<ItemStack> changed = new ArrayList<>(bundles);
            changed.set(3, bundle(List.of(new ItemStackTemplate(Items.DIAMOND, 1))));
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(changed));
            long afterChange = Weight.contentWalks();
            assertSame(helper, Weight.carriedWeight(player, cfg), Before.carried(player, cfg), "the weighing after the change");
            helper.assertValueEqual(Weight.contentWalks() - afterChange, 2L, "walks after one bundle changed");
        } finally {
            player.getInventory().clearContent();
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A template that create would turn into an empty stack weighs nothing, so the weighing must refuse the same ones
    @GameTest
    public void refusedTemplatesAreTheOnesCreateEmpties(GameTestHelper helper) {
        List<ItemStackTemplate> templates = new ArrayList<>();
        templates.add(new ItemStackTemplate(Items.COBBLESTONE, 64));
        templates.add(new ItemStackTemplate(Items.COBBLESTONE, 65));
        templates.add(new ItemStackTemplate(Items.IRON_SWORD, 1));
        templates.add(new ItemStackTemplate(Items.IRON_SWORD, 2));
        templates.add(new ItemStackTemplate(Items.ENDER_PEARL, 17));
        templates.add(new ItemStackTemplate(Items.COBBLESTONE, 1).withCount(-1));
        // A tool made to stack, and a stone made to stack less
        templates.add(patched(Items.IRON_SWORD, 1, builder -> builder.set(DataComponents.MAX_STACK_SIZE, 16)));
        templates.add(patched(Items.COBBLESTONE, 32, builder -> builder.set(DataComponents.MAX_STACK_SIZE, 16)));
        templates.add(patched(Items.COBBLESTONE, 16, builder -> builder.set(DataComponents.MAX_STACK_SIZE, 16)));
        // Containers with too much in one slot, and with as much as fits
        templates.add(patched(Items.SHULKER_BOX, 1, builder -> builder.set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIRT, 99))))));
        templates.add(patched(Items.SHULKER_BOX, 1, builder -> builder.set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIRT, 64))))));
        templates.add(patched(Items.BUNDLE, 1, builder -> builder.set(DataComponents.BUNDLE_CONTENTS,
                new BundleContents(List.of(new ItemStackTemplate(Items.ENDER_PEARL, 20))))));
        templates.add(patched(Items.BUNDLE, 1, builder -> builder.set(DataComponents.BUNDLE_CONTENTS,
                new BundleContents(List.of(new ItemStackTemplate(Items.ENDER_PEARL, 16))))));
        // Over a bundle's whole weight
        templates.add(patched(Items.BUNDLE, 1, builder -> builder.set(DataComponents.BUNDLE_CONTENTS,
                new BundleContents(List.of(new ItemStackTemplate(Items.IRON_SWORD, 1), new ItemStackTemplate(Items.DIAMOND_SWORD, 1))))));
        templates.add(patched(Items.CROSSBOW, 1, builder -> builder.set(DataComponents.CHARGED_PROJECTILES,
                ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW, 64))))));
        templates.add(patched(Items.CROSSBOW, 1, builder -> builder.set(DataComponents.CHARGED_PROJECTILES,
                new ChargedProjectiles(List.of(new ItemStackTemplate(Items.ARROW, 65))))));
        for (ItemStackTemplate template : templates) {
            helper.assertValueEqual(Weight.refused(template), template.create().isEmpty(), "refused " + template);
        }
        helper.assertTrue(templates.stream().filter(Weight::refused).count() >= 6, "too few refused templates to test");
        helper.succeed();
    }

    // A stack whose own components change its weight is weighed on its own, next to plain stacks of the same item
    @GameTest
    public void stacksWithTheirOwnComponentsWeighAsBefore(GameTestHelper helper) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            stacks.add(new ItemStack(Items.COBBLESTONE, 64));
            stacks.add(patchedStack(Items.COBBLESTONE, 16, builder -> builder.set(DataComponents.MAX_STACK_SIZE, 16)));
            stacks.add(new ItemStack(Items.IRON_CHESTPLATE));
            stacks.add(patchedStack(Items.IRON_CHESTPLATE, 1, builder -> builder.set(DataComponents.ATTRIBUTE_MODIFIERS,
                    ItemAttributeModifiers.builder().add(Attributes.ARMOR, new AttributeModifier(Identifier.withDefaultNamespace("test"),
                            12.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.CHEST).build())));
            stacks.add(patchedStack(Items.IRON_CHESTPLATE, 1, builder -> builder.remove(DataComponents.EQUIPPABLE)));
            stacks.add(patchedStack(Items.DIAMOND, 64, builder -> builder.set(DataComponents.EQUIPPABLE,
                    Equippable.builder(EquipmentSlot.HEAD).build())));
            stacks.add(patchedStack(Items.IRON_SWORD, 1, builder -> builder.set(DataComponents.MAX_STACK_SIZE, 1)));
            stacks.add(new ItemStack(Items.IRON_SWORD));
            stacks.add(new ItemStack(Items.ELYTRA));
            stacks.add(new ItemStack(Items.NETHERITE_HORSE_ARMOR));
        }
        // In a shulker box too, where they are read as templates
        stacks.add(shulker(List.copyOf(stacks)));
        for (HardcoreConfig.WeightSection cfg : settings()) {
            for (ItemStack stack : stacks) {
                assertSame(helper, Weight.weigh(stack, cfg, 0), Before.weigh(stack, cfg, 0), stack.toString());
            }
        }
        helper.succeed();
    }

    // A setting changed in place, or the groups put in another order, must not leave old weights behind
    @GameTest
    public void settingsChangedInPlaceAreNoticed(GameTestHelper helper) {
        HardcoreConfig.WeightSection cfg = new HardcoreConfig.WeightSection();
        ItemStack iron = new ItemStack(Items.IRON_BLOCK, 64);
        ItemStack stone = new ItemStack(Items.COBBLESTONE, 64);
        ItemStack box = shulker(List.of(iron.copy(), stone.copy()));
        List<ItemStack> stacks = List.of(iron, stone, box);
        Runnable check = () -> {
            for (ItemStack stack : stacks) {
                assertSame(helper, Weight.weigh(stack, cfg, 0), Before.weigh(stack, cfg, 0), stack + " with " + cfg.groups.keySet());
            }
        };
        check.run();
        cfg.items.put("minecraft:cobblestone", 64.0);
        check.run();
        // c:storage_blocks ahead of the iron one, so an iron block takes its 9
        Map<String, Double> reordered = new LinkedHashMap<>();
        reordered.put("c:storage_blocks", cfg.groups.get("c:storage_blocks"));
        reordered.putAll(cfg.groups);
        cfg.groups.clear();
        cfg.groups.putAll(reordered);
        check.run();
        cfg.containerContents = 0.25;
        check.run();
        cfg.containerDepth = 0;
        check.run();
        helper.succeed();
    }

    private static void assertSame(GameTestHelper helper, double weight, double before, String what) {
        helper.assertTrue(Double.doubleToRawLongBits(weight) == Double.doubleToRawLongBits(before),
                what + " weighs " + weight + ", but weighed " + before + " before");
    }

    // The defaults, and each setting the weighing reads changed on its own
    private static List<HardcoreConfig.WeightSection> settings() {
        List<HardcoreConfig.WeightSection> all = new ArrayList<>();
        all.add(new HardcoreConfig.WeightSection());
        HardcoreConfig.WeightSection deeper = new HardcoreConfig.WeightSection();
        deeper.containerDepth = 4;
        all.add(deeper);
        HardcoreConfig.WeightSection shallow = new HardcoreConfig.WeightSection();
        shallow.containerDepth = 1;
        all.add(shallow);
        HardcoreConfig.WeightSection gentle = new HardcoreConfig.WeightSection();
        gentle.containerContents = 0.3;
        all.add(gentle);
        HardcoreConfig.WeightSection uncounted = new HardcoreConfig.WeightSection();
        uncounted.countContainers = false;
        all.add(uncounted);
        HardcoreConfig.WeightSection odd = new HardcoreConfig.WeightSection();
        odd.defaultWeight = 1.7;
        odd.unstackableWeight = 0.37;
        odd.toolWeight = 0.11;
        all.add(odd);
        return all;
    }

    // 27 shulker boxes in the main inventory, each made on its own as a load would, each of 27 bundles of 64 single
    // items in a mix of kinds
    private static void fillWithShulkersOfBundles(ServerPlayer player) {
        for (int box = 0; box < 27; box++) {
            List<ItemStack> bundles = new ArrayList<>();
            for (int b = 0; b < 27; b++) {
                List<ItemStackTemplate> singles = new ArrayList<>();
                for (int i = 0; i < 64; i++) {
                    singles.add(new ItemStackTemplate(LOOSE.get((box + b + i) % LOOSE.size()), 1));
                }
                bundles.add(bundle(singles));
            }
            player.getInventory().setItem(9 + box, shulker(bundles));
        }
    }

    // The hotbar: what a template walk could get wrong
    private static void putOddStacks(ServerPlayer player) {
        // Too many in one slot, which a copy turns into nothing
        player.getInventory().setItem(0, shulker(List.of(new ItemStack(Items.DIRT, 99), new ItemStack(Items.IRON_INGOT, 64))));
        // Both a container and a bundle on one stack, as only a command makes
        ItemStack both = shulker(List.of(new ItemStack(Items.COBBLESTONE, 64)));
        both.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(new ItemStackTemplate(Items.FEATHER, 3))));
        player.getInventory().setItem(1, both);
        // Five deep, past the default depth
        ItemStack deep = bundle(List.of(new ItemStackTemplate(Items.IRON_BLOCK, 2)));
        for (int i = 0; i < 4; i++) {
            deep = shulker(List.of(deep, new ItemStack(Items.GOLD_INGOT, 5 + i)));
        }
        player.getInventory().setItem(2, deep);
        // The same bundle in a shulker box and loose, so one component is weighed at two depths
        ItemStack shared = bundle(List.of(new ItemStackTemplate(Items.OAK_LOG, 7), new ItemStackTemplate(Items.STRING, 9)));
        player.getInventory().setItem(3, shared);
        player.getInventory().setItem(4, shulker(List.of(shared.copy(), shared.copy())));
        player.getInventory().setItem(5, patchedStack(Items.IRON_CHESTPLATE, 1, builder -> builder.remove(DataComponents.EQUIPPABLE)));
        player.getInventory().setItem(6, new ItemStack(Items.DIAMOND_LEGGINGS));
        player.getInventory().setItem(7, shulker(List.of(new ItemStack(Items.IRON_SWORD), new ItemStack(Items.NETHERITE_CHESTPLATE),
                patchedStack(Items.COBBLESTONE, 16, builder -> builder.set(DataComponents.MAX_STACK_SIZE, 16)))));
        player.getInventory().setItem(8, new ItemStack(Items.ANVIL));
    }

    private static ItemStack shulker(List<ItemStack> items) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(new ArrayList<>(items)));
        return box;
    }

    private static ItemStack bundle(List<ItemStackTemplate> items) {
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(items));
        return bundle;
    }

    private static ItemStackTemplate patched(Item item, int count, Consumer<DataComponentPatch.Builder> patch) {
        DataComponentPatch.Builder builder = DataComponentPatch.builder();
        patch.accept(builder);
        return new ItemStackTemplate(item, builder.build()).withCount(count);
    }

    private static ItemStack patchedStack(Item item, int count, Consumer<DataComponentPatch.Builder> patch) {
        DataComponentPatch.Builder builder = DataComponentPatch.builder();
        patch.accept(builder);
        ItemStack stack = new ItemStack(item, count);
        stack.applyComponents(builder.build());
        return stack;
    }

    // The weighing as it was before it kept anything, word for word: every item inside a container a fresh copy
    private static final class Before {
        private static final List<TagKey<Item>> TOOLS = List.of(ItemTags.SWORDS, ItemTags.AXES, ItemTags.PICKAXES,
                ItemTags.SHOVELS, ItemTags.HOES, ItemTags.SPEARS);
        private static final List<EquipmentSlot> WORN = List.of(EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST,
                EquipmentSlot.HEAD, EquipmentSlot.OFFHAND);

        // The tally's load, for an inventory with nothing on the cursor or in a crafting grid
        static double carried(ServerPlayer player, HardcoreConfig.WeightSection cfg) {
            List<ItemStack> stacks = new ArrayList<>(player.getInventory().getNonEquipmentItems());
            for (EquipmentSlot slot : WORN) {
                stacks.add(player.getItemBySlot(slot));
            }
            double load = 0.0;
            for (ItemStack stack : stacks) {
                if (!stack.isEmpty()) {
                    load += weigh(stack, cfg, 0);
                }
            }
            return load;
        }

        static double weigh(ItemStack stack, HardcoreConfig.WeightSection cfg, int depth) {
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
                Optional<TagKey<Item>> tag = Optional.ofNullable(Identifier.tryParse(group.getKey()))
                        .map(id2 -> TagKey.create(Registries.ITEM, id2));
                if (tag.isPresent() && stack.is(tag.get())) {
                    return WeightTable.each(group.getValue(), max);
                }
            }
            return max == 1 ? cfg.unstackableWeight : WeightTable.each(cfg.defaultWeight, max);
        }

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
    }
}
