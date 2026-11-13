package bobbynooby.dev.gametest;

import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.ItemRule;
import bobbynooby.dev.coords.Offset;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveCompass;
import com.google.common.hash.HashCode;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DynamicOps;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.HashedPatchMap;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.HashOps;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Bees;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.SulfurCubeContent;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.component.UseRemainder;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Positions inside items, both ways, and the hashes of a click. Each test joins a player whose packets it reads, so the
// class has a batch of its own. Creative, so a leave makes no body.
public class CoordinateOffsetItemGameTests {
    private static final String ITEMS = "you-only-live-twice-gametest:coordinate_offset_items";
    // Where a compass points and where a grave lies, both far from any test
    private static final BlockPos TARGET = new BlockPos(9_001, 64, 77);
    private static final BlockPos GRAVE = new BlockPos(-4_321, 70, 1_234);
    private static final BlockPos NETHER_TARGET = new BlockPos(-1_250, 40, 2_222);

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

    // The client hashes the compass it holds, the shifted one. Unmatched, the server would send the slot and the cursor
    // again after every click, which shows as a flicker or a ghost item.
    @GameTest(environment = ITEMS)
    public void clickWithACompassCausesNoResync(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "ciclick", GameType.CREATIVE);
        try {
            ItemStack compass = compass(TARGET);
            player.getInventory().setItem(9, compass);
            player.inventoryMenu.sendAllDataToRemote();
            HashedPatchMap.HashGenerator hasher = clientHasher(player);
            HashedStack carried = HashedStack.create(ItemRule.shift(compass, CoordinateOffsets.frame(player)), hasher);
            Int2ObjectMap<HashedStack> changed = new Int2ObjectOpenHashMap<>();
            changed.put(9, HashedStack.EMPTY);
            int mark = OffsetTestKit.mark(player);
            TestPlayers.receive(player, new ServerboundContainerClickPacket(player.inventoryMenu.containerId,
                    player.inventoryMenu.getStateId(), (short) 9, (byte) 0, ContainerInput.PICKUP, changed, carried));

            helper.assertTrue(player.inventoryMenu.getCarried().is(Items.COMPASS), "the click did not pick the compass up");
            helper.assertTrue(OffsetTestKit.sent(player, mark, ClientboundContainerSetSlotPacket.class).isEmpty(),
                    "a slot was sent again after the click");
            helper.assertTrue(OffsetTestKit.sent(player, mark, ClientboundSetCursorItemPacket.class).isEmpty(),
                    "the cursor was sent again after the click");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A target in another dimension is shown with that dimension's away amount: the same from every frame the connection
    // has, one amount for each dimension, never a frame's own offset. Back in its own dimension it is in the frame again.
    @GameTest(environment = ITEMS)
    public void awayAmountIsOnePerDimensionAndStaysForTheConnection(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel nether = server.getLevel(Level.NETHER);
        ServerLevel end = server.getLevel(Level.END);
        helper.assertTrue(nether != null && end != null, "no nether or End to go to");
        ServerPlayer player = OffsetTestKit.join(helper, "ciaway", GameType.CREATIVE);
        try {
            Offset first = CoordinateOffsets.offset(player);
            player.getInventory().setItem(9, compass(TARGET));
            player.getInventory().setItem(10, compassIn(Level.NETHER, NETHER_TARGET));

            int mark = OffsetTestKit.mark(player);
            OffsetTestKit.withCoordinates(() -> player.teleportTo(nether, 37.5, 64.0, -50.5, Set.of(), 0.0F, 0.0F, true));
            helper.assertTrue(player.level() == nether, "the player did not reach the nether");
            Offset inNether = CoordinateOffsets.offset(player);
            GlobalPos fromNether = targetIn(helper, player, mark, 9);

            mark = OffsetTestKit.mark(player);
            OffsetTestKit.withCoordinates(() -> player.teleportTo(end, 41.5, 64.0, 23.5, Set.of(), 0.0F, 0.0F, true));
            helper.assertTrue(player.level() == end, "the player did not reach the End");
            Offset inEnd = CoordinateOffsets.offset(player);
            GlobalPos fromEnd = targetIn(helper, player, mark, 9);
            GlobalPos netherFromEnd = targetIn(helper, player, mark, 10);

            Offset away = CoordinateOffsets.awayOffset(player, Level.OVERWORLD);
            helper.assertValueEqual(fromNether, GlobalPos.of(Level.OVERWORLD, away.shift(TARGET)), "target seen from the nether");
            helper.assertValueEqual(fromEnd, fromNether, "target seen from the End");
            for (Offset frame : List.of(Offset.ZERO, first, inNether, inEnd)) {
                helper.assertFalse(fromEnd.pos().equals(frame.shift(TARGET)), "the target went out in a frame's offset " + frame);
            }
            Offset netherAway = CoordinateOffsets.awayOffset(player, Level.NETHER);
            helper.assertFalse(netherAway.equals(away), "the nether and the overworld share an away amount " + away);
            helper.assertValueEqual(netherFromEnd, GlobalPos.of(Level.NETHER, netherAway.shift(NETHER_TARGET)),
                    "nether target seen from the End");

            mark = OffsetTestKit.mark(player);
            BlockPos home = helper.absolutePos(new BlockPos(1, 1, 1));
            OffsetTestKit.withCoordinates(() -> player.teleportTo(helper.getLevel(), home.getX() + 0.5, home.getY(), home.getZ() + 0.5,
                    Set.of(), 0.0F, 0.0F, true));
            helper.assertTrue(player.level() == helper.getLevel(), "the player did not get back to the overworld");
            helper.assertValueEqual(targetIn(helper, player, mark, 9),
                    GlobalPos.of(Level.OVERWORLD, CoordinateOffsets.offset(player).shift(TARGET)), "target back in the overworld");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // A creative client sends back the compass it was shown, in the away amount, and the server keeps the real target
    @GameTest(environment = ITEMS)
    public void creativeRoundTripKeepsAnAwayTarget(GameTestHelper helper) {
        ServerPlayer player = joinInTheNether(helper, "ciawaycreative");
        try {
            Offset away = CoordinateOffsets.awayOffset(player, Level.OVERWORLD);
            TestPlayers.receive(player, new ServerboundSetCreativeModeSlotPacket(36, compass(away.shift(TARGET))));
            helper.assertValueEqual(player.inventoryMenu.getSlot(36).getItem().get(DataComponents.LODESTONE_TRACKER).target(),
                    Optional.of(GlobalPos.of(Level.OVERWORLD, TARGET)), "target of the compass the server keeps");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Until the client accepts the arrival's teleport it still sends stacks in the old frame, the overworld's, so a
    // compass it was shown there comes back with its real target, though the new frame is in the nether
    @GameTest(environment = ITEMS)
    public void creativeSlotBeforeTheAcceptKeepsTheOldFrame(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no nether to go to");
        ServerPlayer player = OffsetTestKit.join(helper, "ciawayearly", GameType.CREATIVE);
        try {
            Offset old = CoordinateOffsets.offset(player);
            OffsetTestKit.withCoordinates(() -> player.teleportTo(nether, 37.5, 64.0, -50.5, Set.of(), 0.0F, 0.0F, true));
            helper.assertTrue(player.level() == nether, "the player did not reach the nether");
            TestPlayers.receive(player, new ServerboundSetCreativeModeSlotPacket(36, compass(old.shift(TARGET))));
            helper.assertValueEqual(player.inventoryMenu.getSlot(36).getItem().get(DataComponents.LODESTONE_TRACKER).target(),
                    Optional.of(GlobalPos.of(Level.OVERWORLD, TARGET)), "target of the compass the server keeps");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // The client hashes the compass it was shown, in the away amount, and the click still matches the server's stack
    @GameTest(environment = ITEMS)
    public void clickHashMatchesAnAwayShiftedStack(GameTestHelper helper) {
        ServerPlayer player = joinInTheNether(helper, "ciawayclick");
        try {
            Offset away = CoordinateOffsets.awayOffset(player, Level.OVERWORLD);
            player.getInventory().setItem(9, compass(TARGET));
            player.inventoryMenu.sendAllDataToRemote();
            HashedStack carried = HashedStack.create(compass(away.shift(TARGET)), clientHasher(player));
            Int2ObjectMap<HashedStack> changed = new Int2ObjectOpenHashMap<>();
            changed.put(9, HashedStack.EMPTY);
            int mark = OffsetTestKit.mark(player);
            TestPlayers.receive(player, new ServerboundContainerClickPacket(player.inventoryMenu.containerId,
                    player.inventoryMenu.getStateId(), (short) 9, (byte) 0, ContainerInput.PICKUP, changed, carried));

            helper.assertTrue(player.inventoryMenu.getCarried().is(Items.COMPASS), "the click did not pick the compass up");
            helper.assertTrue(OffsetTestKit.sent(player, mark, ClientboundContainerSetSlotPacket.class).isEmpty(),
                    "a slot was sent again after the click");
            helper.assertTrue(OffsetTestKit.sent(player, mark, ClientboundSetCursorItemPacket.class).isEmpty(),
                    "the cursor was sent again after the click");
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
            helper.assertTrue(ItemRule.shift(sword, CoordinateOffsets.frame(player)) == sword, "the rule copied a plain stack");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    // Each rebuild of a part nested in an item, an entity's data or an explosion, once: the components with a position
    // in their NBT or in an item they hold, a living entity's effect particles and the explosion's own particle
    @GameTest(environment = ITEMS)
    public void everyNestedRebuildShifted(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cirebuilds", GameType.CREATIVE);
        try {
            Offset offset = CoordinateOffsets.offset(player);
            ItemStackTemplate inside = ItemStackTemplate.fromNonEmptyStack(compass(TARGET));
            ItemStack bees = new ItemStack(Items.BEE_NEST);
            bees.set(DataComponents.BEES, new Bees(List.of(new BeehiveBlockEntity.Occupant(
                    TypedEntityData.<EntityType<?>>of(EntityTypes.BEE, posTag("flower_pos", TARGET)), 20, 600))));
            ItemStack entity = new ItemStack(Items.ARMOR_STAND);
            entity.set(DataComponents.ENTITY_DATA, TypedEntityData.<EntityType<?>>of(EntityTypes.ARMOR_STAND,
                    posTag("home_pos", TARGET)));
            ItemStack blockEntity = new ItemStack(Items.STONE);
            blockEntity.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.<BlockEntityType<?>>of(BlockEntityTypes.END_GATEWAY,
                    posTag("exit_portal", TARGET)));
            ItemStack bucket = new ItemStack(Items.AXOLOTL_BUCKET);
            bucket.set(DataComponents.BUCKET_ENTITY_DATA, CustomData.of(posTag("home_pos", TARGET)));
            ItemStack custom = new ItemStack(Items.PAPER);
            CustomData customData = CustomData.of(posTag("marked_pos", TARGET));
            custom.set(DataComponents.CUSTOM_DATA, customData);
            ItemStack plainCustom = new ItemStack(Items.PAPER);
            CompoundTag note = new CompoundTag();
            note.putString("note", "no spot here");
            plainCustom.set(DataComponents.CUSTOM_DATA, CustomData.of(note));
            ItemStack crossbow = new ItemStack(Items.CROSSBOW);
            crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(inside));
            ItemStack remainder = new ItemStack(Items.BOWL);
            remainder.set(DataComponents.USE_REMAINDER, new UseRemainder(inside));
            ItemStack cube = new ItemStack(Items.STONE);
            cube.set(DataComponents.SULFUR_CUBE_CONTENT, new SulfurCubeContent(inside));
            List<ItemStack> stacks = List.of(bees, entity, blockEntity, bucket, custom, plainCustom, crossbow, remainder, cube);
            ItemParticleOption itemParticle = new ItemParticleOption(ParticleTypes.ITEM, inside);
            int mark = OffsetTestKit.mark(player);
            player.connection.send(new ClientboundContainerSetContentPacket(0, 1, stacks, ItemStack.EMPTY));
            player.connection.send(new ClientboundSetEntityDataPacket(4_343, List.of(new SynchedEntityData.DataValue<>(10,
                    EntityDataSerializers.PARTICLES, List.<ParticleOptions>of(ParticleTypes.HEART, itemParticle)))));
            player.connection.send(new ClientboundExplodePacket(new Vec3(1.5, 64.0, 2.5), 4.0F, 3, Optional.empty(), itemParticle,
                    SoundEvents.GENERIC_EXPLODE, WeightedList.of(), true));

            List<ItemStack> sent = OffsetTestKit.last(helper, player, mark, ClientboundContainerSetContentPacket.class).items();
            BlockPos client = offset.shift(TARGET);
            helper.assertValueEqual(posIn(sent.get(0).get(DataComponents.BEES).bees().getFirst().entityData().copyTagWithoutId(),
                    "flower_pos"), client, "a bee's flower");
            helper.assertValueEqual(posIn(sent.get(1).get(DataComponents.ENTITY_DATA).copyTagWithoutId(), "home_pos"), client,
                    "entity data");
            helper.assertValueEqual(posIn(sent.get(2).get(DataComponents.BLOCK_ENTITY_DATA).copyTagWithoutId(), "exit_portal"), client,
                    "block entity data");
            helper.assertValueEqual(posIn(sent.get(3).get(DataComponents.BUCKET_ENTITY_DATA).copyTag(), "home_pos"), client,
                    "a bucket's entity");
            helper.assertValueEqual(posIn(sent.get(4).get(DataComponents.CUSTOM_DATA).copyTag(), "marked_pos"), client, "custom data");
            helper.assertValueEqual(posIn(custom.get(DataComponents.CUSTOM_DATA).copyTag(), "marked_pos"), TARGET,
                    "custom data the server keeps");
            helper.assertTrue(sent.get(5) == plainCustom, "a stack whose custom data holds no spot was copied");
            helper.assertValueEqual(targetOf(sent.get(6).get(DataComponents.CHARGED_PROJECTILES).items().getFirst()), client,
                    "a charged projectile");
            helper.assertValueEqual(targetOf(sent.get(7).get(DataComponents.USE_REMAINDER).convertInto()), client, "a use remainder");
            helper.assertValueEqual(targetOf(sent.get(8).get(DataComponents.SULFUR_CUBE_CONTENT).absorbedBlockItemStack()), client,
                    "a sulfur cube's block");

            SynchedEntityData.DataValue<?> value = OffsetTestKit.last(helper, player, mark, ClientboundSetEntityDataPacket.class)
                    .packedItems().getFirst();
            List<?> particles = (List<?>) value.value();
            helper.assertTrue(particles.getFirst() == ParticleTypes.HEART, "a particle with no item was copied");
            helper.assertValueEqual(targetOf(((ItemParticleOption) particles.get(1)).getItem()), client, "an effect particle's item");
            ClientboundExplodePacket explosion = OffsetTestKit.last(helper, player, mark, ClientboundExplodePacket.class);
            helper.assertValueEqual(targetOf(((ItemParticleOption) explosion.explosionParticle()).getItem()), client,
                    "the explosion's own particle");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    private static CompoundTag posTag(String key, BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray(key, new int[] {pos.getX(), pos.getY(), pos.getZ()});
        return tag;
    }

    private static BlockPos posIn(CompoundTag tag, String key) {
        int[] pos = tag.getIntArray(key).orElseThrow();
        return new BlockPos(pos[0], pos[1], pos[2]);
    }

    private static BlockPos targetOf(ItemStackTemplate template) {
        return template.get(DataComponents.LODESTONE_TRACKER).target().orElseThrow().pos();
    }

    private static ItemStack compass(BlockPos target) {
        return compassIn(Level.OVERWORLD, target);
    }

    private static ItemStack compassIn(ResourceKey<Level> dimension, BlockPos target) {
        ItemStack compass = new ItemStack(Items.COMPASS);
        compass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(GlobalPos.of(dimension, target)), true));
        return compass;
    }

    // The target of the compass in this inventory slot, in the last inventory sent from packet "from" on
    private static GlobalPos targetIn(GameTestHelper helper, ServerPlayer player, int from, int slot) {
        ItemStack sent = OffsetTestKit.last(helper, player, from, ClientboundContainerSetContentPacket.class).items().get(slot);
        return sent.get(DataComponents.LODESTONE_TRACKER).target().orElseThrow();
    }

    // Joined in the overworld, then taken to the nether, where the client has accepted the arrival
    private static ServerPlayer joinInTheNether(GameTestHelper helper, String name) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no nether to go to");
        ServerPlayer player = OffsetTestKit.join(helper, name, GameType.CREATIVE);
        try {
            OffsetTestKit.withCoordinates(() -> player.teleportTo(nether, 37.5, 64.0, -50.5, Set.of(), 0.0F, 0.0F, true));
            helper.assertTrue(player.level() == nether, "the player did not reach the nether");
            OffsetTestKit.settle(helper, player);
        } catch (RuntimeException | Error e) {
            TestPlayers.leave(player);
            throw e;
        }
        return player;
    }

    // The way the server hashes a player's components for the clicks it checks
    private static HashedPatchMap.HashGenerator clientHasher(ServerPlayer player) {
        DynamicOps<HashCode> ops = player.registryAccess().createSerializationContext(HashOps.CRC32C_INSTANCE);
        return component -> component.encodeValue(ops).getOrThrow(message -> new IllegalArgumentException(message)).asInt();
    }
}
