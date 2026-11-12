package bobbynooby.dev.gametest;

import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.ItemRule;
import bobbynooby.dev.coords.Offset;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveCompass;
import com.mojang.datafixers.util.Pair;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Positions inside items, both ways, and the hashes of a click. Each test joins a player whose packets it reads, so the
// class has a batch of its own. Creative, so a leave makes no body.
public class CoordinateOffsetItemGameTests {
    private static final String ITEMS = "you-only-live-twice-gametest:coordinate_offset_items";
    // Where a compass points and where a grave lies, both far from any test
    private static final BlockPos TARGET = new BlockPos(9_001, 64, 77);
    private static final BlockPos GRAVE = new BlockPos(-4_321, 70, 1_234);

    @GameTest(environment = ITEMS)
    public void compassInTheInventoryShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "ciinventory", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            player.getInventory().setItem(9, compass(TARGET));
            int mark = OffsetTestKit.mark(player);
            player.inventoryMenu.sendAllDataToRemote();

            ClientboundContainerSetContentPacket content =
                    OffsetTestKit.last(helper, player, mark, ClientboundContainerSetContentPacket.class);
            LodestoneTracker sent = content.items().get(9).get(DataComponents.LODESTONE_TRACKER);
            helper.assertValueEqual(sent.target(), Optional.of(GlobalPos.of(Level.OVERWORLD, offset.shift(TARGET))),
                    "target of the compass sent");
            helper.assertTrue(sent.tracked(), "the compass sent no longer tracks its lodestone");
            helper.assertValueEqual(player.getInventory().getItem(9).get(DataComponents.LODESTONE_TRACKER).target(),
                    Optional.of(GlobalPos.of(Level.OVERWORLD, TARGET)), "target of the server's compass");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Each slot of the box keeps its place and the bundle its order, as the client shows them that way
    @GameTest(environment = ITEMS)
    public void compassInAShulkerInABundleShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cinested", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            NonNullList<ItemStack> boxSlots = NonNullList.withSize(27, ItemStack.EMPTY);
            boxSlots.set(3, compass(TARGET));
            ItemStack box = new ItemStack(Items.SHULKER_BOX);
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(boxSlots));
            ItemStack bundle = new ItemStack(Items.BUNDLE);
            bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(new ItemStackTemplate(Items.STONE),
                    ItemStackTemplate.fromNonEmptyStack(box), new ItemStackTemplate(Items.DIRT))));
            player.getInventory().setItem(10, bundle);
            int mark = OffsetTestKit.mark(player);
            player.inventoryMenu.sendAllDataToRemote();

            ItemStack sent = OffsetTestKit.last(helper, player, mark, ClientboundContainerSetContentPacket.class).items().get(10);
            List<ItemStackTemplate> inBundle = sent.get(DataComponents.BUNDLE_CONTENTS).items();
            helper.assertValueEqual(inBundle.stream().map(template -> template.item().value()).toList(),
                    List.of(Items.STONE, Items.SHULKER_BOX, Items.DIRT), "the bundle's items in order");
            NonNullList<ItemStack> sentSlots = NonNullList.withSize(27, ItemStack.EMPTY);
            inBundle.get(1).get(DataComponents.CONTAINER).copyInto(sentSlots);
            for (int slot = 0; slot < 27; slot++) {
                if (slot != 3) {
                    helper.assertTrue(sentSlots.get(slot).isEmpty(), "slot " + slot + " of the box holds " + sentSlots.get(slot));
                }
            }
            helper.assertValueEqual(sentSlots.get(3).get(DataComponents.LODESTONE_TRACKER).target(),
                    Optional.of(GlobalPos.of(Level.OVERWORLD, offset.shift(TARGET))), "target of the compass in the box");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The grave compass is a lodestone compass that tracks no lodestone, named for the grave's owner
    @GameTest(environment = ITEMS)
    public void graveCompassShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cigrave", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            String name = player.getPlainTextName();
            Grave grave = new Grave(UUID.randomUUID(), player.getUUID(), name, ResolvableProfile.createUnresolved(name),
                    Level.OVERWORLD, GRAVE, List.of(), 1L);
            ItemStack compass = GraveCompass.compassFor(grave);
            player.getInventory().setItem(11, compass);
            int mark = OffsetTestKit.mark(player);
            player.inventoryMenu.sendAllDataToRemote();

            ItemStack sent = OffsetTestKit.last(helper, player, mark, ClientboundContainerSetContentPacket.class).items().get(11);
            LodestoneTracker tracker = sent.get(DataComponents.LODESTONE_TRACKER);
            helper.assertValueEqual(tracker.target(), Optional.of(GlobalPos.of(Level.OVERWORLD, offset.shift(GRAVE))),
                    "target of the grave compass sent");
            helper.assertFalse(tracker.tracked(), "the grave compass sent tracks a lodestone");
            helper.assertValueEqual(sent.get(DataComponents.CUSTOM_NAME), compass.get(DataComponents.CUSTOM_NAME),
                    "name of the grave compass sent");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Others see what a player holds; a viewer with no offset gets the very packet
    @GameTest(environment = ITEMS)
    public void equipmentOfAnotherPlayerShifted(GameTestHelper helper) {
        ServerPlayer viewer = OffsetTestKit.join(helper, "civiewer", GameType.CREATIVE);
        ServerPlayer plain = null;
        try {
            plain = TestPlayers.join(helper, "ciplain", GameType.CREATIVE, new BlockPos(1, 1, 1));
            Offset offset = CoordinateOffsets.offset(viewer);
            ItemStack helmet = new ItemStack(Items.IRON_HELMET);
            ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(4_242,
                    List.of(Pair.of(EquipmentSlot.MAINHAND, compass(TARGET)), Pair.of(EquipmentSlot.HEAD, helmet)));
            int viewerMark = OffsetTestKit.mark(viewer);
            int plainMark = OffsetTestKit.mark(plain);
            viewer.connection.send(packet);
            plain.connection.send(packet);

            ClientboundSetEquipmentPacket sent = OffsetTestKit.last(helper, viewer, viewerMark, ClientboundSetEquipmentPacket.class);
            helper.assertValueEqual(sent.getEntity(), 4_242, "entity of the equipment");
            helper.assertValueEqual(sent.getSlots().stream().map(Pair::getFirst).toList(),
                    List.of(EquipmentSlot.MAINHAND, EquipmentSlot.HEAD), "equipment slots in order");
            helper.assertValueEqual(sent.getSlots().get(0).getSecond().get(DataComponents.LODESTONE_TRACKER).target(),
                    Optional.of(GlobalPos.of(Level.OVERWORLD, offset.shift(TARGET))), "target of the compass in hand");
            helper.assertTrue(sent.getSlots().get(1).getSecond() == helmet, "the helmet was copied");
            helper.assertTrue(OffsetTestKit.last(helper, plain, plainMark, ClientboundSetEquipmentPacket.class) == packet,
                    "a viewer with no offset got a copy");
        } finally {
            TestPlayers.leave(viewer);
            if (plain != null) {
                TestPlayers.leave(plain);
            }
        }
        helper.succeed();
    }

    @GameTest(environment = ITEMS)
    public void cursorSlotAndInventoryShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cislots", GameType.CREATIVE);
        try {
            Optional<GlobalPos> client = Optional.of(GlobalPos.of(Level.OVERWORLD, CoordinateOffsets.offset(player).shift(TARGET)));
            int mark = OffsetTestKit.mark(player);
            player.connection.send(new ClientboundSetCursorItemPacket(compass(TARGET)));
            player.connection.send(new ClientboundContainerSetSlotPacket(0, 7, 12, compass(TARGET)));
            player.connection.send(new ClientboundSetPlayerInventoryPacket(13, compass(TARGET)));

            ClientboundSetCursorItemPacket cursor = OffsetTestKit.last(helper, player, mark, ClientboundSetCursorItemPacket.class);
            helper.assertValueEqual(cursor.contents().get(DataComponents.LODESTONE_TRACKER).target(), client, "cursor compass");
            ClientboundContainerSetSlotPacket slot = OffsetTestKit.last(helper, player, mark, ClientboundContainerSetSlotPacket.class);
            helper.assertValueEqual(slot.getItem().get(DataComponents.LODESTONE_TRACKER).target(), client, "slot compass");
            helper.assertValueEqual(List.of(slot.getContainerId(), slot.getStateId(), slot.getSlot()), List.of(0, 7, 12),
                    "container, state and slot");
            ClientboundSetPlayerInventoryPacket inventory =
                    OffsetTestKit.last(helper, player, mark, ClientboundSetPlayerInventoryPacket.class);
            helper.assertValueEqual(inventory.contents().get(DataComponents.LODESTONE_TRACKER).target(), client, "inventory compass");
            helper.assertValueEqual(inventory.slot(), 13, "inventory slot");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Only a data pack could make a trade give such a compass; the rest of the offer goes as it was
    @GameTest(environment = ITEMS)
    public void tradeResultShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "citrade", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            MerchantOffer compassTrade = new MerchantOffer(new ItemCost(Items.EMERALD, 3), Optional.of(new ItemCost(Items.COMPASS)),
                    compass(TARGET), 2, 12, 5, 0.05F, 4);
            compassTrade.setSpecialPriceDiff(-1);
            MerchantOffer plainTrade = new MerchantOffer(new ItemCost(Items.WHEAT, 20), new ItemStack(Items.EMERALD), 16, 2, 0.05F);
            MerchantOffers offers = new MerchantOffers();
            offers.add(plainTrade);
            offers.add(compassTrade);
            int mark = OffsetTestKit.mark(player);
            player.connection.send(new ClientboundMerchantOffersPacket(3, offers, 2, 70, true, false));

            ClientboundMerchantOffersPacket sent = OffsetTestKit.last(helper, player, mark, ClientboundMerchantOffersPacket.class);
            helper.assertValueEqual(sent.getOffers().size(), 2, "offers");
            helper.assertTrue(sent.getOffers().get(0).getResult().is(Items.EMERALD), "the first offer is no longer first");
            MerchantOffer offer = sent.getOffers().get(1);
            helper.assertValueEqual(offer.getResult().get(DataComponents.LODESTONE_TRACKER).target(),
                    Optional.of(GlobalPos.of(Level.OVERWORLD, offset.shift(TARGET))), "target of the compass traded");
            helper.assertValueEqual(List.of(offer.getUses(), offer.getMaxUses(), offer.getXp(), offer.getDemand(),
                    offer.getSpecialPriceDiff()), List.of(2, 12, 5, 4, -1), "uses, most uses, xp, demand and price change");
            helper.assertValueEqual(offer.getPriceMultiplier(), 0.05F, "price multiplier");
            helper.assertValueEqual(offer.getItemCostA(), compassTrade.getItemCostA(), "first cost");
            helper.assertValueEqual(offer.getItemCostB(), compassTrade.getItemCostB(), "second cost");
            helper.assertValueEqual(List.of(sent.getContainerId(), sent.getVillagerLevel(), sent.getVillagerXp()), List.of(3, 2, 70),
                    "container, level and xp");
            helper.assertTrue(sent.showProgress() && !sent.canRestock(), "progress or restock changed");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // An explorer map's markers go to the client with the item, in its saved form
    @GameTest(environment = ITEMS)
    public void explorerMapMarkersShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cimap", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            ItemStack map = new ItemStack(Items.FILLED_MAP);
            map.set(DataComponents.MAP_DECORATIONS, MapDecorations.EMPTY
                    .withDecoration("+", new MapDecorations.Entry(MapDecorationTypes.RED_X, TARGET.getX() + 0.5, TARGET.getZ(), 90.0F))
                    .withDecoration("home", new MapDecorations.Entry(MapDecorationTypes.TARGET_X, GRAVE.getX(), GRAVE.getZ(), 0.0F)));
            int mark = OffsetTestKit.mark(player);
            player.connection.send(new ClientboundSetCursorItemPacket(map));

            MapDecorations sent = OffsetTestKit.last(helper, player, mark, ClientboundSetCursorItemPacket.class).contents()
                    .get(DataComponents.MAP_DECORATIONS);
            helper.assertValueEqual(sent.decorations().get("+"), new MapDecorations.Entry(MapDecorationTypes.RED_X,
                    offset.shiftX(TARGET.getX() + 0.5), offset.shiftZ(TARGET.getZ()), 90.0F), "the red marker");
            helper.assertValueEqual(sent.decorations().get("home"), new MapDecorations.Entry(MapDecorationTypes.TARGET_X,
                    offset.shiftX(GRAVE.getX()), offset.shiftZ(GRAVE.getZ()), 0.0F), "the other marker");
            helper.assertValueEqual(sent.decorations().size(), 2, "markers");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A creative client sends back whole stacks, which carry its own numbers
    @GameTest(environment = ITEMS)
    public void creativeSlotReversed(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cicreative", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            TestPlayers.receive(player, new ServerboundSetCreativeModeSlotPacket(36, compass(offset.shift(TARGET))));
            helper.assertValueEqual(player.inventoryMenu.getSlot(36).getItem().get(DataComponents.LODESTONE_TRACKER).target(),
                    Optional.of(GlobalPos.of(Level.OVERWORLD, TARGET)), "target of the compass the server keeps");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    @GameTest(environment = ITEMS)
    public void plainStacksUntouched(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "ciplainstack", GameType.CREATIVE);
        try {
            ItemStack sword = new ItemStack(Items.IRON_SWORD);
            sword.set(DataComponents.CUSTOM_NAME, Component.literal("Pointy at 1 2 3"));
            sword.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 2);
            ClientboundSetCursorItemPacket packet = new ClientboundSetCursorItemPacket(sword);
            List<ItemStack> stacks = new ArrayList<>(List.of(sword, new ItemStack(Items.DIRT, 5), ItemStack.EMPTY));
            ClientboundContainerSetContentPacket content = new ClientboundContainerSetContentPacket(0, 1, stacks, sword);
            int mark = OffsetTestKit.mark(player);
            player.connection.send(packet);
            player.connection.send(content);

            helper.assertTrue(OffsetTestKit.last(helper, player, mark, ClientboundSetCursorItemPacket.class) == packet,
                    "a copy of a plain stack went out");
            helper.assertTrue(OffsetTestKit.last(helper, player, mark, ClientboundContainerSetContentPacket.class) == content,
                    "a copy of plain contents went out");
            helper.assertTrue(ItemRule.shift(sword, CoordinateOffsets.offset(player)) == sword, "the rule copied a plain stack");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    private static ItemStack compass(BlockPos target) {
        ItemStack compass = new ItemStack(Items.COMPASS);
        compass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(GlobalPos.of(Level.OVERWORLD, target)), true));
        return compass;
    }
}
