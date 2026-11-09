package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodySnapshot;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.bodies.GearSync;
import bobbynooby.dev.config.YoltConfig;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BodyStoreGameTests {
    private static ItemStack damaged(net.minecraft.world.item.Item item, int damage) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.DAMAGE, damage);
        return stack;
    }

    private static BodyRecord record(Map<EquipmentSlot, ItemStack> gear) {
        BodySnapshot snapshot = new BodySnapshot(Level.OVERWORLD, new Vec3(1.5, 64, -2.5), 90f, 14f, 2f,
                List.of(new MobEffectInstance(MobEffects.SPEED, 200, 1)), gear);
        return new BodyRecord(UUID.randomUUID(), "Alex", UUID.randomUUID(), YoltConfig.BodyType.MANNEQUIN, 1234L, false, snapshot);
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
        helper.assertValueEqual(snap.effects().size(), 1, "effect count");
        helper.assertTrue(snap.effects().get(0).is(MobEffects.SPEED) && snap.effects().get(0).getAmplifier() == 1, "effect changed");
        ItemStack chest = snap.gear().get(EquipmentSlot.CHEST);
        helper.assertTrue(chest != null && chest.is(Items.IRON_CHESTPLATE), "chestplate lost");
        helper.assertValueEqual(chest.getOrDefault(DataComponents.DAMAGE, 0), 10, "chestplate damage");
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

    @GameTest
    public void gearSyncLeavesDifferentItem(GameTestHelper helper) {
        Map<EquipmentSlot, ItemStack> out = run(Map.of(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD)),
                Map.of(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE)));
        helper.assertTrue(out.get(EquipmentSlot.MAINHAND).is(Items.IRON_AXE), "axe replaced");
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
}
