package bobbynooby.dev.gametest;

import bobbynooby.dev.core.Profiles;
import bobbynooby.dev.graves.Grave;
import bobbynooby.dev.graves.GraveCompass;
import bobbynooby.dev.graves.GraveItems;
import bobbynooby.dev.graves.GraveStore;
import bobbynooby.dev.skins.Skins;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class GraveItemsGameTests {
    @GameTest
    public void graveHeadCarriesMarkerAndProfile(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Grave", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        try {
            ItemStack head = GraveItems.graveHead(Profiles.of(player));
            helper.assertTrue(head.is(Items.PLAYER_HEAD), "not a player head");
            helper.assertTrue(GraveItems.isGraveHead(head), "grave marker missing");
            helper.assertFalse(GraveItems.isReviveHead(head), "grave head counted as revive head");
            helper.assertTrue(GraveItems.ownerId(head).filter(player.getUUID()::equals).isPresent(), "owner id wrong");
            helper.succeed();
        } finally {
            TestPlayers.leave(player);
        }
    }

    @GameTest
    public void plainPlayerHeadIsNotMarked(GameTestHelper helper) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        helper.assertFalse(GraveItems.isGraveHead(head), "plain head counted as grave head");
        helper.assertFalse(GraveItems.isReviveHead(head), "plain head counted as revive head");
        helper.assertTrue(GraveItems.ownerId(head).isEmpty(), "plain head has an owner");
        helper.succeed();
    }

    // The markers are read from the custom data's own tag, not a copy of it. Whatever shape the data has, the answer
    // must be the one a copy gave, and reading must leave the data, which every copy of the stack shares, as it was.
    @GameTest
    public void markersReadInPlaceAsFromACopy(GameTestHelper helper) {
        for (CustomData data : markerShapes(UUID.randomUUID())) {
            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            head.set(DataComponents.CUSTOM_DATA, data);
            CompoundTag before = data.copyTag();
            helper.assertValueEqual(GraveItems.isGraveHead(head), copiedMarker(data, "grave_head"), "grave head with " + before);
            helper.assertValueEqual(GraveItems.isReviveHead(head), copiedMarker(data, "revive"), "revive head with " + before);
            helper.assertValueEqual(data.copyTag(), before, "custom data after reading it");
        }
        helper.succeed();
    }

    // The same for a compass: one whose grave stands follows it, one whose grave is gone loses its marker, and anything
    // else is left alone. A copy of the stack, which shares the data, keeps its marker whatever the compass does.
    @GameTest
    public void compassMarkersReadInPlaceAsFromACopy(GameTestHelper helper) {
        GraveStore store = GraveStore.get(helper.getLevel().getServer());
        Grave grave = new Grave(UUID.randomUUID(), UUID.randomUUID(), "Compassed", ResolvableProfile.createUnresolved("Compassed"),
                helper.getLevel().dimension(), helper.absolutePos(new BlockPos(2, 1, 2)), List.of(), 1234L);
        store.put(grave);
        try {
            LodestoneTracker toGrave = GraveCompass.compassFor(grave).get(DataComponents.LODESTONE_TRACKER);
            List<CustomData> shapes = new ArrayList<>(markerShapes(grave.id()));
            shapes.add(compassMarker(grave.id().toString()));
            shapes.add(compassMarker(UUID.randomUUID().toString()));
            shapes.add(compassMarker("not an id"));
            for (CustomData data : shapes) {
                ItemStack compass = new ItemStack(Items.COMPASS);
                compass.set(DataComponents.CUSTOM_DATA, data);
                ItemStack shared = compass.copy();
                CompoundTag before = data.copyTag();
                Optional<UUID> marked = compassMark(data);
                GraveCompass.tick(compass, helper.getLevel());
                if (marked.isEmpty()) {
                    helper.assertTrue(ItemStack.isSameItemSameComponents(compass, shared), "a compass with no marker changed: " + before);
                } else if (marked.get().equals(grave.id())) {
                    helper.assertValueEqual(compass.get(DataComponents.LODESTONE_TRACKER), toGrave, "tracker of a compass to its grave");
                    helper.assertTrue(compass.get(DataComponents.CUSTOM_DATA) == data, "the custom data of a compass to its grave changed");
                } else {
                    helper.assertValueEqual(compass.get(DataComponents.LODESTONE_TRACKER), new LodestoneTracker(Optional.empty(), false),
                            "tracker of a compass whose grave is gone");
                    helper.assertTrue(compassMark(compass.get(DataComponents.CUSTOM_DATA)).isEmpty(),
                            "the marker stayed on a compass whose grave is gone");
                }
                helper.assertValueEqual(data.copyTag(), before, "custom data after the compass ticked");
                helper.assertTrue(shared.get(DataComponents.CUSTOM_DATA) == data, "the copy's custom data changed");
            }
        } finally {
            store.remove(grave.id());
        }
        helper.succeed();
    }

    // Every shape the readers must take: none, the wrong types, either marker, both, and a grave's id
    private static List<CustomData> markerShapes(UUID graveId) {
        List<CustomData> shapes = new ArrayList<>();
        shapes.add(CustomData.EMPTY);
        CompoundTag otherMod = new CompoundTag();
        otherMod.putString("other", "value");
        shapes.add(CustomData.of(otherMod));
        CompoundTag rootNotCompound = new CompoundTag();
        rootNotCompound.putString("yolt", "grave_head");
        shapes.add(CustomData.of(rootNotCompound));
        for (String key : List.of("grave_head", "revive")) {
            for (Tag value : List.<Tag>of(ByteTag.ONE, ByteTag.ZERO, IntTag.valueOf(1), StringTag.valueOf("true"))) {
                CompoundTag inner = new CompoundTag();
                inner.put(key, value);
                CompoundTag root = new CompoundTag();
                root.put("yolt", inner);
                shapes.add(CustomData.of(root));
            }
        }
        CompoundTag both = new CompoundTag();
        both.putBoolean("grave_head", true);
        both.putBoolean("revive", true);
        both.store("grave", UUIDUtil.CODEC, graveId);
        CompoundTag bothRoot = new CompoundTag();
        bothRoot.put("yolt", both);
        shapes.add(CustomData.of(bothRoot));
        return shapes;
    }

    private static CustomData compassMarker(String graveId) {
        CompoundTag marker = new CompoundTag();
        marker.putString("grave_compass", graveId);
        CompoundTag root = new CompoundTag();
        root.put("yolt", marker);
        return CustomData.of(root);
    }

    // How the markers were read before, from a copy of the tag
    private static boolean copiedMarker(CustomData data, String key) {
        return data.copyTag().getCompound("yolt").map(inner -> inner.getBooleanOr(key, false)).orElse(false);
    }

    private static Optional<UUID> compassMark(@Nullable CustomData data) {
        return Optional.ofNullable(data).flatMap(found -> found.copyTag().getCompound("yolt"))
                .flatMap(marker -> marker.read("grave_compass", UUIDUtil.STRING_CODEC));
    }

    // A container in a container goes into the grave whole: the shulker box keeps the bundle, and the bundle its ten
    // items, with nothing spilled at the death spot
    @GameTest
    public void bundleInsideShulkerKeepsItsContentsInTheGrave(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Nested", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        BlockPos at = player.blockPosition();
        try {
            ItemStack bundle = new ItemStack(Items.BUNDLE);
            bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(
                    ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.DIAMOND, 6)),
                    ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.EMERALD, 4)))));
            ItemStack box = new ItemStack(Items.SHULKER_BOX);
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(bundle)));
            player.getInventory().add(box);
            dieBesideTest(helper, player);

            List<Grave> graves = BodyTestKit.gravesOf(helper, owner);
            helper.assertValueEqual(graves.size(), 1, "graves");
            List<ItemStack> boxes = graves.get(0).items().stream().filter(stack -> stack.is(Items.SHULKER_BOX)).toList();
            helper.assertValueEqual(boxes.size(), 1, "shulker boxes in the grave");
            List<ItemStack> inBox = boxes.get(0).getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                    .nonEmptyItemCopyStream().toList();
            helper.assertTrue(inBox.size() == 1 && inBox.get(0).is(Items.BUNDLE), "the box holds " + inBox);
            List<ItemStack> inBundle = inBox.get(0).getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY)
                    .itemCopies().toList();
            helper.assertValueEqual(GraveAsserts.count(inBundle, Items.DIAMOND), 6, "diamonds in the bundle");
            helper.assertValueEqual(GraveAsserts.count(inBundle, Items.EMERALD), 4, "emeralds in the bundle");
            for (Item item : List.of(Items.SHULKER_BOX, Items.BUNDLE, Items.DIAMOND, Items.EMERALD)) {
                helper.assertValueEqual(GraveDeathGameTests.itemsNear(helper, item, at, 4.0), 0, item + " spilled at the death spot");
            }
        } finally {
            TestPlayers.leave(player);
            BodyTestKit.removeGraves(helper, owner);
        }
        helper.succeed();
    }

    // Vanilla's death destroys curse of vanishing items in the inventory only, and so does YOLT's: a cursed sword packed in
    // a shulker box isn't in the inventory, so it reaches the grave in its box, while the loose one is gone
    @GameTest
    public void vanishingItemInsideShulkerSurvivesTheDeath(GameTestHelper helper) {
        Holder<Enchantment> vanishing = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.VANISHING_CURSE);
        ServerPlayer player = TestPlayers.join(helper, "PackedCurse", GameType.SURVIVAL, new BlockPos(1, 1, 1));
        UUID owner = player.getUUID();
        BlockPos at = player.blockPosition();
        try {
            ItemStack loose = new ItemStack(Items.IRON_SWORD);
            loose.enchant(vanishing, 1);
            ItemStack packed = loose.copy();
            ItemStack box = new ItemStack(Items.SHULKER_BOX);
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(packed)));
            player.getInventory().add(loose);
            player.getInventory().add(box);
            dieBesideTest(helper, player);

            List<Grave> graves = BodyTestKit.gravesOf(helper, owner);
            helper.assertValueEqual(graves.size(), 1, "graves");
            List<ItemStack> items = graves.get(0).items();
            helper.assertValueEqual(GraveAsserts.count(items, Items.IRON_SWORD), 0, "loose cursed swords in the grave");
            List<ItemStack> boxes = items.stream().filter(stack -> stack.is(Items.SHULKER_BOX)).toList();
            helper.assertValueEqual(boxes.size(), 1, "shulker boxes in the grave");
            List<ItemStack> inBox = boxes.get(0).getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                    .nonEmptyItemCopyStream().toList();
            helper.assertTrue(inBox.size() == 1 && ItemStack.matches(inBox.get(0), packed), "the box holds " + inBox);
            helper.assertValueEqual(GraveDeathGameTests.itemsNear(helper, Items.IRON_SWORD, at, 4.0), 0, "cursed swords dropped");
        } finally {
            TestPlayers.leave(player);
            BodyTestKit.removeGraves(helper, owner);
        }
        helper.succeed();
    }

    // A survival death with a respawn point beside the test, so a later respawn searches no random spot. The player
    // leaves in the same tick, before the kick comes.
    private static void dieBesideTest(GameTestHelper helper, ServerPlayer player) {
        GraveDeathGameTests.respawnBesideTest(helper, player);
        TestPlayers.markLoaded(player);
        player.kill(player.level());
        helper.assertTrue(player.isDeadOrDying(), "the player did not die");
    }

    // An offline player's grave head is built from the name and id alone, with no skin to copy
    @GameTest
    public void offlineGraveProfileKeepsIdAndName(GameTestHelper helper) {
        UUID id = UUID.randomUUID();
        ResolvableProfile profile = Profiles.of(Skins.known(helper.getLevel().getServer(), new NameAndId(id, "Ghost")));
        helper.assertValueEqual(profile.partialProfile().id(), id, "profile id");
        helper.assertValueEqual(profile.name(), Optional.of("Ghost"), "profile name");
        helper.assertTrue(profile.partialProfile().properties().values().stream()
                .noneMatch(property -> property.name().equals("textures")), "profile has textures");
        helper.succeed();
    }
}
