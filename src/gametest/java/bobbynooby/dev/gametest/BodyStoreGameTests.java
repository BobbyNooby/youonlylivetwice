package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodySnapshot;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.bodies.GearSync;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.StoreVersions;
import bobbynooby.dev.core.UnreadableFiles;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public class BodyStoreGameTests {
    private static ItemStack damaged(net.minecraft.world.item.Item item, int damage) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.DAMAGE, damage);
        return stack;
    }

    private static BodyRecord record(Map<EquipmentSlot, ItemStack> gear) {
        BodySnapshot snapshot = new BodySnapshot(Level.OVERWORLD, new Vec3(1.5, 64, -2.5), 90f, 14f, 2f, 3.5, 40, 120,
                List.of(new MobEffectInstance(MobEffects.SPEED, 200, 1)), gear);
        return new BodyRecord(UUID.randomUUID(), "Alex", UUID.randomUUID(), YoltConfig.BodyType.MANNEQUIN, 1234L, false, snapshot);
    }

    // A body held on top of a vehicle keeps that through the file. A record from before it was kept isn't held.
    @GameTest
    public void heldSurvivesTheCodec(GameTestHelper helper) {
        BodyRecord made = record(Map.of());
        BodyRecord held = new BodyRecord(made.owner(), made.ownerName(), made.bodyEntity(), made.type(), made.loggedOutAt(),
                false, made.snapshot(), made.skin(), made.emptyAtLogout(), made.copiedEffects(), true);
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        CompoundTag encoded = (CompoundTag) BodyRecord.CODEC.encodeStart(ops, held).getOrThrow();
        helper.assertTrue(BodyRecord.CODEC.parse(ops, encoded).getOrThrow().held(), "held lost through the codec");
        encoded.remove("held");
        helper.assertFalse(BodyRecord.CODEC.parse(ops, encoded).getOrThrow().held(), "a record without the field is held");
        helper.succeed();
    }

    @GameTest
    public void recordRoundTripsThroughCodec(GameTestHelper helper) {
        BodyRecord before = record(Map.of(EquipmentSlot.CHEST, damaged(Items.IRON_CHESTPLATE, 10)));
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Tag encoded = BodyRecord.CODEC.encodeStart(ops, before).getOrThrow();
        BodyRecord after = BodyRecord.CODEC.parse(ops, encoded).getOrThrow();

        helper.assertValueEqual(after.owner(), before.owner(), "owner");
        helper.assertValueEqual(after.ownerName(), "Alex", "owner name");
        helper.assertValueEqual(after.bodyEntity(), before.bodyEntity(), "body entity");
        helper.assertValueEqual(after.type(), YoltConfig.BodyType.MANNEQUIN, "type");
        helper.assertValueEqual(after.loggedOutAt(), 1234L, "logged out at");
        BodySnapshot snap = after.snapshot();
        helper.assertValueEqual(snap.dimension(), Level.OVERWORLD, "dimension");
        helper.assertValueEqual(snap.pos(), new Vec3(1.5, 64, -2.5), "pos");
        helper.assertValueEqual(snap.yRot(), 90f, "yRot");
        helper.assertValueEqual(snap.health(), 14f, "health");
        helper.assertValueEqual(snap.absorption(), 2f, "absorption");
        helper.assertValueEqual(snap.fallDistance(), 3.5, "fall distance");
        helper.assertValueEqual(snap.fireTicks(), 40, "fire ticks");
        helper.assertValueEqual(snap.air(), 120, "air");
        helper.assertValueEqual(snap.effects().size(), 1, "effect count");
        helper.assertTrue(snap.effects().get(0).is(MobEffects.SPEED) && snap.effects().get(0).getAmplifier() == 1, "effect changed");
        ItemStack chest = snap.gear().get(EquipmentSlot.CHEST);
        helper.assertTrue(chest != null && chest.is(Items.IRON_CHESTPLATE), "chestplate lost");
        helper.assertValueEqual(chest.getOrDefault(DataComponents.DAMAGE, 0), 10, "chestplate damage");
        helper.succeed();
    }

    // Whether the body copied its owner's effects is kept with it. A record saved before that was kept counts as copied,
    // as most bodies were under the switch's default.
    @GameTest
    public void recordKeepsWhetherEffectsWereCopied(GameTestHelper helper) {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        BodyRecord made = record(Map.of());
        BodyRecord notCopied = new BodyRecord(made.owner(), made.ownerName(), made.bodyEntity(), made.type(), made.loggedOutAt(),
                made.pendingRemoval(), made.snapshot(), made.skin(), made.emptyAtLogout(), false);
        CompoundTag encoded = (CompoundTag) BodyRecord.CODEC.encodeStart(ops, notCopied).getOrThrow();
        helper.assertFalse(BodyRecord.CODEC.parse(ops, encoded).getOrThrow().copiedEffects(), "a body that copied no effects");

        encoded.remove("copied_effects");
        helper.assertTrue(BodyRecord.CODEC.parse(ops, encoded).getOrThrow().copiedEffects(), "a record saved before it was kept");
        helper.succeed();
    }

    // A record saved before bodies kept their fall, fire and air still loads, with none of the three
    @GameTest
    public void snapshotWithoutFallFireAndAirLoads(GameTestHelper helper) {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        CompoundTag encoded = (CompoundTag) BodyRecord.CODEC.encodeStart(ops, record(Map.of())).getOrThrow();
        CompoundTag snapshot = encoded.getCompoundOrEmpty("snapshot");
        snapshot.remove("fall_distance");
        snapshot.remove("fire_ticks");
        snapshot.remove("air");
        encoded.put("snapshot", snapshot);

        BodySnapshot snap = BodyRecord.CODEC.parse(ops, encoded).getOrThrow().snapshot();
        helper.assertValueEqual(snap.fallDistance(), 0.0, "fall distance");
        helper.assertValueEqual(snap.fireTicks(), 0, "fire ticks");
        helper.assertValueEqual(snap.air(), 300, "air");
        helper.assertValueEqual(snap.health(), 14f, "health");
        helper.succeed();
    }

    @GameTest
    public void storeLooksUpByOwnerAndEntity(GameTestHelper helper) {
        BodyStore store = BodyStore.get(helper.getLevel().getServer());
        BodyRecord body = record(Map.of());
        store.put(body);
        try {
            helper.assertTrue(store.byOwner(body.owner()).isPresent(), "not found by owner");
            helper.assertTrue(store.byEntity(body.bodyEntity()).isPresent(), "not found by entity");
            helper.assertTrue(store.all().contains(body), "missing from all()");
            store.put(body.withPendingRemoval(true));
            helper.assertTrue(store.byOwner(body.owner()).orElseThrow().pendingRemoval(), "update not kept");
        } finally {
            store.remove(body.owner());
        }
        helper.assertTrue(store.byOwner(body.owner()).isEmpty(), "still found by owner");
        helper.assertTrue(store.byEntity(body.bodyEntity()).isEmpty(), "still found by entity");
        helper.succeed();
    }

    @GameTest
    public void snapshotReadsALiveEntity(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Snap", GameType.SURVIVAL, new BlockPos(1, 2, 1));
        try {
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
            player.addEffect(new MobEffectInstance(MobEffects.SPEED, 200));
            BodySnapshot snap = BodySnapshot.of(player);
            helper.assertTrue(snap.gear().get(EquipmentSlot.MAINHAND).is(Items.DIAMOND_SWORD), "sword not read");
            helper.assertTrue(!snap.gear().containsKey(EquipmentSlot.HEAD), "empty slot kept");
            helper.assertTrue(snap.effects().stream().anyMatch(e -> e.is(MobEffects.SPEED)), "effect not read");
            helper.assertValueEqual(snap.pos(), player.position(), "pos");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    private static Map<EquipmentSlot, ItemStack> run(Map<EquipmentSlot, ItemStack> body, Map<EquipmentSlot, ItemStack> real) {
        Map<EquipmentSlot, ItemStack> result = new EnumMap<>(EquipmentSlot.class);
        real.forEach((slot, stack) -> result.put(slot, stack.copy()));
        GearSync.applyBodyGear(body, slot -> result.getOrDefault(slot, ItemStack.EMPTY), result::put);
        return result;
    }

    @GameTest
    public void gearSyncClearsConsumedTotem(GameTestHelper helper) {
        Map<EquipmentSlot, ItemStack> out = run(Map.of(), Map.of(EquipmentSlot.MAINHAND, new ItemStack(Items.TOTEM_OF_UNDYING)));
        helper.assertTrue(out.get(EquipmentSlot.MAINHAND).isEmpty(), "totem still there");
        helper.succeed();
    }

    @GameTest
    public void gearSyncCopiesDamage(GameTestHelper helper) {
        Map<EquipmentSlot, ItemStack> out = run(Map.of(EquipmentSlot.CHEST, damaged(Items.IRON_CHESTPLATE, 30)),
                Map.of(EquipmentSlot.CHEST, damaged(Items.IRON_CHESTPLATE, 5)));
        helper.assertValueEqual(out.get(EquipmentSlot.CHEST).getOrDefault(DataComponents.DAMAGE, 0), 30, "damage");
        helper.succeed();
    }

    // A body's chestplate with no damage component at all, not even 0: the real chestplate loses its own
    @GameTest
    public void gearSyncRemovesDamageWhenBodyHasNone(GameTestHelper helper) {
        ItemStack body = new ItemStack(Items.IRON_CHESTPLATE);
        body.remove(DataComponents.DAMAGE);
        helper.assertFalse(body.has(DataComponents.DAMAGE), "the body's chestplate still has a damage component");
        Map<EquipmentSlot, ItemStack> out = run(Map.of(EquipmentSlot.CHEST, body),
                Map.of(EquipmentSlot.CHEST, damaged(Items.IRON_CHESTPLATE, 5)));
        ItemStack chest = out.get(EquipmentSlot.CHEST);
        helper.assertTrue(chest.is(Items.IRON_CHESTPLATE), "chestplate replaced: " + chest);
        helper.assertFalse(chest.has(DataComponents.DAMAGE), "the real chestplate kept its damage: " + chest.get(DataComponents.DAMAGE));
        helper.succeed();
    }

    // The body's copy was used up and a dispenser filled the slot, so the real item goes and the new one is handed over
    @GameTest
    public void gearSyncHandsOverDifferentItem(GameTestHelper helper) {
        Map<EquipmentSlot, ItemStack> out = run(Map.of(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD)),
                Map.of(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING)));
        helper.assertTrue(out.get(EquipmentSlot.OFFHAND).is(Items.SHIELD), "the totem came back: " + out.get(EquipmentSlot.OFFHAND));
        helper.succeed();
    }

    // A plain helmet in place of a named one that broke on the body is not its copy
    @GameTest
    public void gearSyncHandsOverSameItemWithOtherComponents(GameTestHelper helper) {
        ItemStack enchanted = damaged(Items.IRON_HELMET, 5);
        enchanted.set(DataComponents.CUSTOM_NAME, Component.literal("Lucky"));
        Map<EquipmentSlot, ItemStack> out = run(Map.of(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)),
                Map.of(EquipmentSlot.HEAD, enchanted));
        ItemStack head = out.get(EquipmentSlot.HEAD);
        helper.assertTrue(head.is(Items.IRON_HELMET) && !head.has(DataComponents.CUSTOM_NAME), "the owner's helmet came back: " + head);
        helper.succeed();
    }

    // The body's totem has its effect taken off when totems are off, and is still the copy
    @GameTest
    public void gearSyncKeepsTotemWithoutItsEffect(GameTestHelper helper) {
        ItemStack stripped = new ItemStack(Items.TOTEM_OF_UNDYING);
        stripped.remove(DataComponents.DEATH_PROTECTION);
        ItemStack real = new ItemStack(Items.TOTEM_OF_UNDYING);
        Map<EquipmentSlot, ItemStack> out = run(Map.of(EquipmentSlot.MAINHAND, stripped), Map.of(EquipmentSlot.MAINHAND, real));
        helper.assertTrue(ItemStack.matches(out.get(EquipmentSlot.MAINHAND), real), "the real totem changed: " + out.get(EquipmentSlot.MAINHAND));
        helper.succeed();
    }

    @GameTest
    public void gearSyncNeverRaisesCount(GameTestHelper helper) {
        Map<EquipmentSlot, ItemStack> out = run(Map.of(EquipmentSlot.OFFHAND, new ItemStack(Items.ARROW, 3)),
                Map.of(EquipmentSlot.OFFHAND, new ItemStack(Items.ARROW, 1)));
        helper.assertValueEqual(out.get(EquipmentSlot.OFFHAND).getCount(), 1, "count");
        helper.succeed();
    }

    @GameTest
    public void gearSyncGivesNewGearToEmptySlot(GameTestHelper helper) {
        ItemStack helmet = new ItemStack(Items.IRON_HELMET);
        Map<EquipmentSlot, ItemStack> out = run(Map.of(EquipmentSlot.HEAD, helmet), Map.of());
        ItemStack head = out.get(EquipmentSlot.HEAD);
        helper.assertTrue(head != null && head.is(Items.IRON_HELMET), "helmet not given");
        helper.assertFalse(head == helmet, "the body's own stack was handed over");
        helper.succeed();
    }

    @GameTest
    public void badBodyTypeOnlySkipsThatRecord(GameTestHelper helper) {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        BodyRecord good = record(Map.of());
        BodyRecord bad = record(Map.of());
        CompoundTag goodTag = (CompoundTag) BodyRecord.CODEC.encodeStart(ops, good).getOrThrow();
        CompoundTag badTag = (CompoundTag) BodyRecord.CODEC.encodeStart(ops, bad).getOrThrow();
        badTag.putString("type", "ghost");
        ListTag list = new ListTag();
        list.add(badTag);
        list.add(goodTag);
        CompoundTag file = new CompoundTag();
        file.put("bodies", list);

        BodyStore store = BodyStore.TYPE.codec().parse(ops, file).getOrThrow();
        helper.assertTrue(store.byOwner(good.owner()).isPresent(), "good record lost");
        helper.assertTrue(store.byOwner(bad.owner()).isEmpty(), "bad record kept");
        helper.succeed();
    }

    // Junk in a temp file stands in for a body file the storage couldn't read
    @GameTest
    public void unreadableBodyFileIsCopied(GameTestHelper helper) throws IOException {
        Path dir = helper.getLevel().getServer().getWorldPath(LevelResource.DATA);
        Files.createDirectories(dir);
        Path file = Files.createTempFile(dir, "bodies-test-", ".dat");
        byte[] junk = {1, 2, 3, 4, 5};
        try {
            Files.write(file, junk);
            helper.assertTrue(UnreadableFiles.keepCopy(file, true, "body", "bodies").isEmpty(), "copied a file the store was read from");
            helper.assertTrue(copiesOf(file).isEmpty(), "a copy appeared for a file the store was read from");
            helper.assertTrue(UnreadableFiles.keepCopy(dir.resolve(file.getFileName() + ".missing"), false, "body", "bodies").isEmpty(),
                    "copied a file that isn't there");

            Optional<Path> copy = UnreadableFiles.keepCopy(file, false, "body", "bodies");
            helper.assertTrue(copy.isPresent() && Files.exists(copy.get()), "no copy of the unreadable file");
            helper.assertValueEqual(copiesOf(file).size(), 1, "copies next to the file");
            helper.assertTrue(Arrays.equals(Files.readAllBytes(copy.get()), junk), "the copy differs from the file");
        } finally {
            Files.deleteIfExists(file);
            for (Path left : copiesOf(file)) {
                Files.deleteIfExists(left);
            }
        }
        helper.succeed();
    }

    // A body file from a newer YOLT, its format one past this one's, as the body file of a data storage of the test's
    // own. get reads what it can and keeps a copy of the file before anything saves over it.
    @GameTest
    public void newerBodyFileCopiedThroughGet(GameTestHelper helper) throws IOException {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        BodyRecord record = record(Map.of(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)));
        CompoundTag data = storeData(BodyRecord.CODEC.encodeStart(ops, record).getOrThrow());
        data.putInt("format", BodyStore.FORMAT + 1);
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        NbtUtils.addCurrentDataVersion(root);
        Path folder = Files.createTempDirectory("yolt-store-test-");
        Path file = BodyStore.TYPE.id().withSuffix(".dat").resolveAgainst(folder);
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(root, file);
            byte[] written = Files.readAllBytes(file);
            SavedDataStorage storage = GraveStoreGameTests.freshStorage(helper, folder);
            List<BodyStore> got = new ArrayList<>();
            List<String> log = OfflineGameTests.yoltLog(() -> got.add(BodyStore.get(storage, folder)));
            BodyStore store = got.get(0);
            helper.assertTrue(store.fromNewer(), "the newer file did not read as newer");
            helper.assertTrue(store.byOwner(record.owner()).map(found -> found.snapshot().gear().get(EquipmentSlot.HEAD))
                    .filter(helmet -> helmet.is(Items.IRON_HELMET)).isPresent(), "the newer file's record was not read");
            List<Path> copies = copiesOf(file, ".newer-");
            helper.assertValueEqual(copies.size(), 1, "copies of the newer file");
            helper.assertTrue(Arrays.equals(Files.readAllBytes(copies.get(0)), written), "the copy differs from the file");
            helper.assertTrue(copiesOf(file, ".unreadable-").isEmpty(), "the newer file was also kept as unreadable");
            String line = "The body file " + file + " is from a newer version, so a copy of it is kept at " + copies.get(0)
                    + " before it is saved over";
            helper.assertValueEqual(log.stream().filter(entry -> entry.startsWith("The body file ")).toList(), List.of(line),
                    "the copy's log lines");
        } finally {
            GraveStoreGameTests.deleteFolder(folder);
        }
        helper.succeed();
    }

    // The second record has no owner, so it can't be read at all
    @GameTest
    public void skippedBodyRecordKeepsFileCopy(GameTestHelper helper) throws IOException {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        BodyRecord kept = record(Map.of());
        BodyRecord broken = record(Map.of());
        Tag keptTag = BodyRecord.CODEC.encodeStart(ops, kept).getOrThrow();
        CompoundTag brokenTag = (CompoundTag) BodyRecord.CODEC.encodeStart(ops, broken).getOrThrow();
        brokenTag.remove("owner");

        BodyStore loaded = BodyStore.TYPE.codec().parse(ops, storeData(keptTag, brokenTag)).getOrThrow();
        helper.assertTrue(loaded.byOwner(kept.owner()).isPresent(), "the readable record was not loaded");
        helper.assertTrue(loaded.byEntity(broken.bodyEntity()).isEmpty(), "the record with no owner was loaded");
        GraveStoreGameTests.assertFileCopiedOnce(helper, loaded::checkFile, storeData(keptTag, brokenTag));
        BodyStore whole = BodyStore.TYPE.codec().parse(ops, storeData(keptTag)).getOrThrow();
        GraveStoreGameTests.assertFileNotCopied(helper, whole::checkFile, storeData(keptTag));
        helper.succeed();
    }

    // The record's second effect has an id no effect has, as after the mod or datapack that added it went
    @GameTest
    public void unknownEffectOnlyDropsThatEffect(GameTestHelper helper) throws IOException {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        BodySnapshot snapshot = new BodySnapshot(Level.OVERWORLD, new Vec3(1.5, 64, -2.5), 90f, 14f, 2f, 3.5, 40, 120,
                List.of(new MobEffectInstance(MobEffects.SPEED, 200, 1), new MobEffectInstance(MobEffects.HASTE, 200)),
                Map.of(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE)));
        BodyRecord record = new BodyRecord(UUID.randomUUID(), "Alex", UUID.randomUUID(), YoltConfig.BodyType.MANNEQUIN,
                1234L, false, snapshot);
        CompoundTag wholeTag = (CompoundTag) BodyRecord.CODEC.encodeStart(ops, record).getOrThrow();
        CompoundTag brokenTag = wholeTag.copy();
        brokenTag.getCompoundOrEmpty("snapshot").getListOrEmpty("effects").getCompound(1).orElseThrow()
                .putString("id", "you-only-live-twice-gametest:no_such_effect");

        List<BodyStore> loaded = new ArrayList<>();
        List<String> log = OfflineGameTests.yoltLog(
                () -> loaded.add(BodyStore.TYPE.codec().parse(ops, storeData(brokenTag)).getOrThrow()));
        BodySnapshot after = loaded.get(0).byOwner(record.owner()).map(BodyRecord::snapshot).orElse(null);
        helper.assertTrue(after != null, "the record with an unknown effect was dropped");
        helper.assertTrue(after.effects().size() == 1 && after.effects().get(0).is(MobEffects.SPEED),
                "the record did not keep just its known effect: " + after.effects());
        helper.assertValueEqual(after.health(), 14f, "health");
        helper.assertTrue(after.gear().getOrDefault(EquipmentSlot.CHEST, ItemStack.EMPTY).is(Items.IRON_CHESTPLATE),
                "chestplate lost");
        long effectSkips = log.stream().filter(line -> line.startsWith("Skipping unreadable body effect record")).count();
        helper.assertValueEqual(effectSkips, 1L, "logged effect skips: " + log);
        helper.assertTrue(log.stream().noneMatch(line -> line.startsWith("Skipping unreadable body record")),
                "the whole record was logged as skipped: " + log);
        GraveStoreGameTests.assertFileCopiedOnce(helper, loaded.get(0)::checkFile, storeData(brokenTag));
        BodyStore whole = BodyStore.TYPE.codec().parse(ops, storeData(wholeTag)).getOrThrow();
        GraveStoreGameTests.assertFileNotCopied(helper, whole::checkFile, storeData(wholeTag));
        helper.succeed();
    }

    // The chestplate's id is one no item has, as after the mod that added it went. The rest of the record still loads,
    // and the log says it was read only in part.
    @GameTest
    public void partlyReadBodyRecordIsLogged(GameTestHelper helper) {
        RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        BodyRecord record = record(Map.of(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET),
                EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE)));
        CompoundTag brokenTag = (CompoundTag) BodyRecord.CODEC.encodeStart(ops, record).getOrThrow();
        brokenTag.getCompoundOrEmpty("snapshot").getCompoundOrEmpty("gear").put("chest", OfflineGameTests.unreadableItem());

        List<BodyStore> loaded = new ArrayList<>();
        List<String> log = OfflineGameTests.yoltLog(
                () -> loaded.add(BodyStore.TYPE.codec().parse(ops, storeData(brokenTag)).getOrThrow()));
        Map<EquipmentSlot, ItemStack> gear = loaded.get(0).byOwner(record.owner())
                .map(after -> after.snapshot().gear()).orElse(null);
        helper.assertTrue(gear != null, "the record with an unknown item was dropped");
        helper.assertTrue(gear.getOrDefault(EquipmentSlot.HEAD, ItemStack.EMPTY).is(Items.IRON_HELMET), "helmet lost");
        helper.assertFalse(gear.containsKey(EquipmentSlot.CHEST), "the unknown chest item was read: " + gear);
        long partial = log.stream().filter(line -> line.startsWith("Reading only part of a body record")).count();
        helper.assertValueEqual(partial, 1L, "logged partial reads: " + log);
        helper.succeed();
    }

    // A body file's data part, with the version fields at today's values so no fixer runs
    private static CompoundTag storeData(Tag... bodies) {
        ListTag list = new ListTag();
        list.addAll(Arrays.asList(bodies));
        CompoundTag data = new CompoundTag();
        data.put("bodies", list);
        data.putInt("format", BodyStore.FORMAT);
        data.putInt("data_version", StoreVersions.currentDataVersion());
        return data;
    }

    private static List<Path> copiesOf(Path file) throws IOException {
        return copiesOf(file, ".unreadable-");
    }

    private static List<Path> copiesOf(Path file, String kind) throws IOException {
        String prefix = file.getFileName() + kind;
        try (Stream<Path> files = Files.list(file.getParent())) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix)).toList();
        }
    }
}
