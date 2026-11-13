package bobbynooby.dev.gametest;

import bobbynooby.dev.bodies.PlayerBody;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

import static bobbynooby.dev.gametest.BodyTestKit.cleanUp;
import static bobbynooby.dev.gametest.BodyTestKit.leaveBody;
import static bobbynooby.dev.gametest.BodyTestKit.leavePlayerBody;
import static bobbynooby.dev.gametest.BodyTestKit.useDefaultBodies;
import static bobbynooby.dev.gametest.BodyTestKit.usePlayerBodies;

// Bob's ask: after a logout and a login, the main hand and offhand hold exactly what they held. ItemStack.matches
// compares the item, the count and every component, so a lost name, enchantment or bit of wear fails. Like the other
// body tests, each turns the default switches on and puts the server's own back in the same tick.
public class BodyHandsGameTests {
    // A player body that stays in the world across ticks is a player to every mob, so it has a batch of its own
    private static final String PLAYER_BODY = "you-only-live-twice-gametest:player_body";
    private static final BlockPos ON_FLOOR = new BlockPos(1, 1, 1);
    // Not the first slot, so a selected slot that came back as the default fails
    private static final int HOTBAR_SLOT = 4;

    @GameTest
    public void handsMatchAfterRelogin(GameTestHelper helper) {
        handsRoundTrip(helper, "Hands", false);
    }

    @GameTest
    public void handsMatchAfterReloginPlayerBody(GameTestHelper helper) {
        handsRoundTrip(helper, "PlayerHands", true);
    }

    @GameTest
    public void stackedOffhandKeepsCount(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "Stacked", GameType.SURVIVAL, ON_FLOOR);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            ItemStack arrows = new ItemStack(Items.ARROW, 64);
            ItemStack torches = new ItemStack(Items.TORCH, 37);
            player.setItemSlot(EquipmentSlot.MAINHAND, arrows.copy());
            player.setItemSlot(EquipmentSlot.OFFHAND, torches.copy());
            LivingEntity body = leaveBody(helper, player);
            AABB around = body.getBoundingBox().inflate(4.0);

            ServerPlayer back = rejoin(helper, player);
            assertHands(helper, back, arrows, torches);
            assertNothingElse(helper, back, 2, 64 + 37, around);
            helper.assertTrue(body.isRemoved(), "the body is still in the world");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // The body's empty hands are copied back as empty, and nothing else is pulled into them
    @GameTest
    public void emptyHandsStayEmpty(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, "EmptyHands", GameType.SURVIVAL, ON_FLOOR);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = useDefaultBodies();
        try {
            ItemStack sword = sword(helper);
            Inventory inventory = player.getInventory();
            // Next to the empty selected slot, in reach of anything that would fill the hand
            inventory.setItem(HOTBAR_SLOT + 1, sword.copy());
            inventory.setSelectedSlot(HOTBAR_SLOT);
            LivingEntity body = leaveBody(helper, player);
            helper.assertTrue(body.getMainHandItem().isEmpty() && body.getOffhandItem().isEmpty(), "the body holds something");
            AABB around = body.getBoundingBox().inflate(4.0);

            ServerPlayer back = rejoin(helper, player);
            assertHands(helper, back, ItemStack.EMPTY, ItemStack.EMPTY);
            helper.assertValueEqual(back.getInventory().getSelectedSlot(), HOTBAR_SLOT, "selected slot after login");
            helper.assertTrue(ItemStack.matches(back.getInventory().getItem(HOTBAR_SLOT + 1), sword),
                    "hotbar slot " + (HOTBAR_SLOT + 1) + " after login: " + back.getInventory().getItem(HOTBAR_SLOT + 1));
            assertNothingElse(helper, back, 1, 1, around);
            helper.assertTrue(body.isRemoved(), "the body is still in the world");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    // A zombie hits a player body that holds its shield up. The shield takes the hit and wears, as a player's does
    // (vanilla wears a shield on players only), and the login carries that wear back onto the real shield and changes
    // nothing else. The body's copies are left alone apart from the hit, so only the wear comes back. The shield is
    // raised the way a client's use packet starts it; the body's own ticks then take it past the shield's delay, and
    // the hit goes through the damage path directly.
    @GameTest(environment = PLAYER_BODY, maxTicks = 40)
    public void handsAfterBodyWasHit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = TestPlayers.join(helper, "Blocker", GameType.SURVIVAL, ON_FLOOR);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = usePlayerBodies();
        boolean waiting = false;
        try {
            ItemStack sword = sword(helper);
            ItemStack shield = shield();
            holdBoth(player, sword, shield);
            PlayerBody body = leavePlayerBody(helper, player);
            Settings.get().bodies = before;
            body.startUsingItem(InteractionHand.OFF_HAND);
            AABB around = body.getBoundingBox().inflate(4.0);
            helper.runAfterDelay(10, () -> {
                try {
                    helper.assertTrue(body.isBlocking(), "the body's shield is not up");
                    // In front of the body, so the shield faces it; never added to the world
                    Zombie zombie = new Zombie(level);
                    Vec3 front = body.position().add(Vec3.directionFromRotation(0.0F, body.getYHeadRot()).scale(2.0));
                    zombie.snapTo(front.x, front.y, front.z, 0.0F, 0.0F);
                    float health = body.getHealth();
                    body.hurtServer(level, level.damageSources().mobAttack(zombie), 6.0F);
                    helper.assertValueEqual(body.getHealth(), health, "the body's health after the blocked hit");
                    int wear = body.getOffhandItem().getDamageValue();
                    helper.assertTrue(wear > shield.getDamageValue(), "the shield took no wear from the hit");
                    helper.assertTrue(ItemStack.matches(body.getMainHandItem(), sword), "the hit changed the body's sword");

                    ServerPlayer back = rejoin(helper, player);
                    ItemStack worn = shield.copy();
                    worn.setDamageValue(wear);
                    assertHands(helper, back, sword, worn);
                    helper.assertValueEqual(back.getInventory().getSelectedSlot(), HOTBAR_SLOT, "selected slot after login");
                    assertNothingElse(helper, back, 2, 2, around);
                    helper.assertTrue(body.isRemoved(), "the body is still in the world");
                } finally {
                    cleanUp(helper, owner, before);
                }
                helper.succeed();
            });
            waiting = true;
        } finally {
            Settings.get().bodies = before;
            if (!waiting) {
                cleanUp(helper, owner, before);
            }
        }
    }

    // A named, enchanted, worn sword in a hotbar slot that isn't the first, selected, and a named, worn shield
    private static void handsRoundTrip(GameTestHelper helper, String name, boolean playerBody) {
        ServerPlayer player = TestPlayers.join(helper, name, GameType.SURVIVAL, ON_FLOOR);
        UUID owner = player.getUUID();
        YoltConfig.BodiesSection before = playerBody ? usePlayerBodies() : useDefaultBodies();
        try {
            ItemStack sword = sword(helper);
            ItemStack shield = shield();
            holdBoth(player, sword, shield);
            LivingEntity body = playerBody ? leavePlayerBody(helper, player) : leaveBody(helper, player);
            helper.assertTrue(ItemStack.matches(body.getMainHandItem(), sword), "the body's main hand: " + body.getMainHandItem());
            helper.assertTrue(ItemStack.matches(body.getOffhandItem(), shield), "the body's offhand: " + body.getOffhandItem());
            AABB around = body.getBoundingBox().inflate(4.0);

            ServerPlayer back = rejoin(helper, player);
            assertHands(helper, back, sword, shield);
            helper.assertValueEqual(back.getInventory().getSelectedSlot(), HOTBAR_SLOT, "selected slot after login");
            assertNothingElse(helper, back, 2, 2, around);
            helper.assertTrue(body.isRemoved(), "the body is still in the world");
        } finally {
            cleanUp(helper, owner, before);
        }
        helper.succeed();
    }

    private static void holdBoth(ServerPlayer player, ItemStack mainHand, ItemStack offhand) {
        Inventory inventory = player.getInventory();
        inventory.setItem(HOTBAR_SLOT, mainHand.copy());
        inventory.setSelectedSlot(HOTBAR_SLOT);
        player.setItemSlot(EquipmentSlot.OFFHAND, offhand.copy());
    }

    private static ItemStack sword(GameTestHelper helper) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Oathkeeper"));
        sword.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 3);
        sword.setDamageValue(117);
        return sword;
    }

    private static ItemStack shield() {
        ItemStack shield = new ItemStack(Items.SHIELD);
        shield.set(DataComponents.CUSTOM_NAME, Component.literal("Bulwark"));
        shield.setDamageValue(40);
        return shield;
    }

    private static void assertHands(GameTestHelper helper, ServerPlayer player, ItemStack mainHand, ItemStack offhand) {
        helper.assertTrue(ItemStack.matches(player.getMainHandItem(), mainHand),
                "main hand after login: " + player.getMainHandItem() + ", not " + mainHand);
        helper.assertTrue(ItemStack.matches(player.getOffhandItem(), offhand),
                "offhand after login: " + player.getOffhandItem() + ", not " + offhand);
    }

    // Nothing came from nowhere and nothing went: the player has just these stacks and items, and none lie about
    private static void assertNothingElse(GameTestHelper helper, ServerPlayer player, int stacks, int items, AABB around) {
        Inventory inventory = player.getInventory();
        int stacksHeld = 0;
        int itemsHeld = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                stacksHeld++;
                itemsHeld += stack.getCount();
            }
        }
        helper.assertValueEqual(stacksHeld, stacks, "stacks after login");
        helper.assertValueEqual(itemsHeld, items, "items after login");
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, around, Entity::isAlive).isEmpty(),
                "items lie on the ground");
    }

    private static ServerPlayer rejoin(GameTestHelper helper, ServerPlayer player) {
        return TestPlayers.rejoin(helper, player.getGameProfile(), GameType.SURVIVAL, ON_FLOOR);
    }
}
